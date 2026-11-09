package com.autumn.rag.service;

import com.autumn.rag.config.AppProperties;
import com.autumn.rag.corpus.CorpusScanner;
import com.autumn.rag.corpus.MdParser;
import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.dao.FavoriteDao;
import com.autumn.rag.dao.IndexMetaDao;
import com.autumn.rag.llm.EmbeddingProvider;
import com.autumn.rag.llm.MockEmbedding;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 可选真实 PostgreSQL + pgvector 回归。只读取 BAGUWEN_TEST_DB_*，绝不复用应用 .env。
 * 测试库必须已安装 vector 扩展；每个测试独立随机 schema，结束仅删除自己创建的 schema。
 * 运行：mvn -Dtest=FavoriteReindexIntegrationTest test（先设置三个测试环境变量）。
 */
@EnabledIfEnvironmentVariable(named = "BAGUWEN_TEST_DB_URL", matches = ".+")
class FavoriteReindexIntegrationTest {
    private String schema;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private CorpusDao corpus;
    private FavoriteService favorites;
    private CorpusScanner scanner;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setup() {
        String url = System.getenv("BAGUWEN_TEST_DB_URL");
        String user = System.getenv("BAGUWEN_TEST_DB_USER");
        String password = System.getenv("BAGUWEN_TEST_DB_PASSWORD");
        assertNotNull(user, "请显式配置测试数据库用户");
        assertNotNull(password, "请显式配置测试数据库密码");
        var base = new DriverManagerDataSource(url, user, password);
        admin = new JdbcTemplate(base);
        assertEquals(1, admin.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class),
                "请在独立测试库预先安装 pgvector，不由测试修改公共扩展");
        schema = "favorite_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(url, user, password);
        var props = new Properties();
        props.setProperty("currentSchema", schema + ",public");
        ds.setConnectionProperties(props);
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).createSchemas(false).load().migrate();
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        corpus = new CorpusDao(jdbc);
        favorites = new FavoriteService(new FavoriteDao(jdbc));
        scanner = mock(CorpusScanner.class);
        alice = createUser("alice@example.test");
        bob = createUser("bob@example.test");
    }

    @AfterEach
    void cleanup() {
        if (schema != null) {
            // 只允许清理本测试生成的绝对命名空间,绝不 DROP public / 用户提供的 schema。
            assertTrue(schema.matches("favorite_test_[0-9a-f]{32}"));
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private UUID createUser(String email) {
        return jdbc.queryForObject("INSERT INTO app_user(email,password_hash) VALUES (?, 'unused') RETURNING id", UUID.class, email);
    }

    private AppProperties properties(int dims) {
        return new AppProperties(null, null,
                new AppProperties.Embed("mock", "", "", "test", dims, 2, false, 10, 10),
                null, null, null, null, null);
    }

    private IngestService ingest(int dims, EmbeddingProvider provider) {
        return new IngestService(properties(dims), scanner, corpus, new IndexMetaDao(jdbc), provider);
    }

    private void rebuild(int dims) {
        var props = properties(dims);
        tx.executeWithoutResult(s -> ingest(dims, new MockEmbedding(props.embed())).reindex(IngestService.Progress.NONE));
    }

    private MdParser.ParsedPage page(String group, String slug, MdParser.Section... sections) {
        return new MdParser.ParsedPage(new MdParser.PageMeta(group, slug, slug, "https://example.test/" + slug,
                "2026-11-09", "test", group + "/" + slug + ".md"), List.of(sections));
    }

    private MdParser.Section question(String title, String answer) {
        return new MdParser.Section("基础", title, MdParser.normalizeKey(title), answer);
    }

    private long id(String title) {
        return jdbc.queryForObject("SELECT id FROM chunk WHERE question = ?", Long.class, title);
    }

    private void favorite(UUID user, long id) {
        tx.executeWithoutResult(s -> favorites.add(user, id));
    }

    @Test
    void 并发收藏幂等且用户隔离分页筛选正确() throws Exception {
        when(scanner.loadAll()).thenReturn(List.of(
                page("java", "jvm", question("A", "答案 A"), question("B", "答案 B")),
                page("database", "mysql", question("C", "答案 C"))));
        rebuild(8);
        long a = id("A"), b = id("B"), c = id("C");
        var executor = Executors.newFixedThreadPool(4);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) tasks.add(executor.submit(() -> favorite(alice, a)));
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        favorite(alice, b);
        favorite(alice, c);
        favorite(bob, a);
        assertEquals(3, favorites.list(alice, null, 1, 20).total());
        assertEquals(1, favorites.list(bob, null, 1, 20).total());
        assertEquals(2, favorites.list(alice, "java", 1, 1).total());
        assertEquals(1, favorites.list(alice, "java", 2, 1).items().size());
        assertNotEquals(favorites.list(alice, "java", 1, 1).items().get(0).chunkId(),
                favorites.list(alice, "java", 2, 1).items().get(0).chunkId());
        favorites.remove(bob, a);
        favorites.remove(bob, a);
        assertTrue(favorites.state(alice, a).favorited());
        assertFalse(favorites.state(bob, a).favorited());
    }

    @Test
    void 答案更新重排及更换维度不改变ID和收藏时间() {
        when(scanner.loadAll()).thenReturn(List.of(page("java", "jvm",
                question("A", "长答案。\n".repeat(150)), question("B", "旧答案"))));
        rebuild(8);
        long a = id("A"), b = id("B");
        favorite(alice, a);
        var savedAt = favorites.list(alice, null, 1, 20).items().get(0).createdAt();
        when(scanner.loadAll()).thenReturn(List.of(page("java", "jvm",
                question("B", "新答案"), question("A", "更新后的长答案。\n".repeat(150)))));
        rebuild(16);
        assertEquals(a, id("A"));
        assertEquals(b, id("B"));
        assertEquals("新答案", corpus.getChunk(b).content());
        assertTrue(favorites.state(alice, a).favorited());
        assertEquals(savedAt, favorites.list(alice, null, 1, 20).items().get(0).createdAt());
        assertEquals(16, corpus.embeddingColumnDims());
        assertTrue(corpus.countSegs() > 0);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chunk_seg WHERE vector_dims(embedding) <> 16", Integer.class));
    }

    @Test
    void 重建失败连同向量列变更一起回滚() {
        when(scanner.loadAll()).thenReturn(List.of(page("java", "jvm", question("A", "旧答案"))));
        rebuild(8);
        long a = id("A");
        favorite(alice, a);
        var broken = mock(EmbeddingProvider.class);
        when(broken.dims()).thenReturn(16);
        when(broken.embed(anyList())).thenThrow(new IllegalStateException("模拟向量服务失败"));
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s ->
                ingest(16, broken).reindex(IngestService.Progress.NONE)));
        assertEquals(a, id("A"));
        assertEquals(8, corpus.embeddingColumnDims());
        assertEquals("旧答案", corpus.getChunk(a).content());
        assertTrue(favorites.state(alice, a).favorited());
    }

    @Test
    void 重建事务锁跨连接互斥且提交后释放() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(s -> {
                assertTrue(corpus.tryReindexLock());
                var other = executor.submit(() -> tx.execute(status -> corpus.tryReindexLock()));
                try {
                    assertFalse(other.get(10, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new AssertionError("第二个连接应立即拿不到重建锁", e);
                }
            });
            assertEquals(Boolean.TRUE, tx.execute(s -> corpus.tryReindexLock()));
            assertThrows(IllegalStateException.class, corpus::tryReindexLock);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 真正删题清理收藏但不影响留存题目() {
        when(scanner.loadAll()).thenReturn(List.of(page("java", "jvm", question("A", "答案 A"), question("B", "答案 B"))));
        rebuild(8);
        long a = id("A"), b = id("B");
        favorite(alice, a);
        favorite(alice, b);
        when(scanner.loadAll()).thenReturn(List.of(page("java", "jvm", question("B", "答案 B"))));
        rebuild(8);
        assertNull(corpus.getChunk(a));
        assertEquals(b, id("B"));
        assertFalse(favorites.state(alice, a).favorited());
        assertEquals(1, favorites.list(alice, null, 1, 20).total());
        jdbc.update("DELETE FROM app_user WHERE id = ?", alice);
        assertEquals(0, favorites.list(alice, null, 1, 20).total());
    }
}
