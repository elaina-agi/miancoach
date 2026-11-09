package com.autumn.rag.dao;

import com.autumn.rag.corpus.MdParser.PageMeta;
import com.autumn.rag.corpus.MdParser.Section;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 数据访问:doc/chunk 元数据、pgvector 近邻、关键词/精确命中。SQL 直写以便用 pgvector 算子。 */
@Repository
public class CorpusDao {

    private final JdbcTemplate jdbc;

    public CorpusDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 记录
    /**
     * 检索命中块(题目级)。segId>0 表示命中的是"子段向量"(parent-child),0=题目级向量;
     * rerankScore 是 cross-encoder 给的相关性分(未过重排的命中为 0,拒答阈值用它当第二判据)。
     */
    public record Hit(
            long chunkId, String group, String title, String url,
            String category, String question, double sim, String channel, long segId,
            double rerankScore) {}

    /** 树的中间行。 */
    public record TreeRow(
            long docId, String group, String title, String url, String slug,
            Long chunkId, String category, String question) {}

    /** 单题完整内容。 */
    public record ChunkFull(
            long chunkId, long docId, String group, String title,
            String url, String category, String question, String content) {}

    public record Question(long chunkId, String group, String category, String question) {}

    // ------------------------------------------------------------------ 写入
    // 固定命名空间 "BAGUWENR":同一数据库上的所有实例共用,事务提交/回滚自动释放。
    private static final long REINDEX_LOCK_KEY = 0x4241475557454E52L;

    /** 非阻塞事务锁。必须在事务内调用,否则 auto-commit 会在 SELECT 后立即释放锁。 */
    public boolean tryReindexLock() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("reindex 必须在数据库事务内执行");
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, REINDEX_LOCK_KEY));
    }

    /**
     * 按板块 + slug 更新原文档并返回原 ID。保留历史方法名,但语义是 upsert。
     * 标题、来源、路径等元数据更新不改变文档身份;移动板块或改 slug 则是新文档。
     */
    public long insertDoc(PageMeta meta) {
        return jdbc.queryForObject(
                "INSERT INTO doc(group_tag, slug, title, url, lastmod, source, md_file) "
                        + "VALUES (?,?,?,?,?,?,?) "
                        + "ON CONFLICT (group_tag, slug) DO UPDATE SET "
                        + "title = EXCLUDED.title, url = EXCLUDED.url, lastmod = EXCLUDED.lastmod, "
                        + "source = EXCLUDED.source, md_file = EXCLUDED.md_file RETURNING id",
                Long.class, meta.group(), meta.slug(), meta.title(), emptyToNull(meta.url()),
                parseDate(meta.lastmod()), emptyToNull(meta.source()), meta.mdFile());
    }

    /**
     * 同文档 + 完整题干是同题,不是 question_key 的模糊/归一化匹配。
     * 答案、类目、向量改变只更新原行,收藏/作答等外键继续指向原 ID。
     */
    public long insertChunk(long docId, Section s, float[] embedding) {
        return jdbc.queryForObject(
                "INSERT INTO chunk(doc_id, category, question, question_key, content, embedding) "
                        + "VALUES (?,?,?,?,?, CAST(? AS vector)) "
                        + "ON CONFLICT (doc_id, question) DO UPDATE SET "
                        + "category = EXCLUDED.category, question_key = EXCLUDED.question_key, "
                        + "content = EXCLUDED.content, embedding = EXCLUDED.embedding RETURNING id",
                Long.class, docId, emptyToNull(s.category()), s.question(), s.questionKey(),
                s.content(), vectorLiteral(embedding));
    }

    /** 写入子段(parent-child 的 child 级)。 */
    public void insertSeg(long chunkId, int seq, String content, float[] embedding) {
        jdbc.update("INSERT INTO chunk_seg(chunk_id, seq, content, embedding) "
                        + "VALUES (?,?,?, CAST(? AS vector))",
                chunkId, seq, content, vectorLiteral(embedding));
    }

    public int countSegs() {
        Integer c = jdbc.queryForObject("SELECT count(*) FROM chunk_seg", Integer.class);
        return c == null ? 0 : c;
    }

    /**
     * 显式清空全部语料的旧入口,不再用于 reindex。重建只删除本次已经消失的题目/文档。
     * 保留该方法以免外部维护代码失去兼容性。
     */
    public void wipe() {
        jdbc.update("DELETE FROM chunk");
        jdbc.update("DELETE FROM doc");
    }

    /** 子段只是可再生的检索索引,每轮重新切分;父题 ID 不随它一起删除。 */
    public void clearSegs() {
        jdbc.update("DELETE FROM chunk_seg");
    }

    /** 仅删除本次语料不再包含的题目,其收藏等外键按各自的 ON DELETE 规则处理。 */
    public int deleteMissingChunks(Collection<Long> retainedIds) {
        return jdbc.update("DELETE FROM chunk WHERE NOT (id = ANY (?))", retainedIdArray(retainedIds));
    }

    /** 必须在清理缺失题目之后调用。 */
    public int deleteMissingDocs(Collection<Long> retainedIds) {
        return jdbc.update("DELETE FROM doc WHERE NOT (id = ANY (?))", retainedIdArray(retainedIds));
    }

    private static SqlArrayValue retainedIdArray(Collection<Long> retainedIds) {
        if (retainedIds == null || retainedIds.isEmpty() || retainedIds.stream().anyMatch(id -> id == null)) {
            throw new IllegalArgumentException("保留 ID 集合不能为空或包含 null,拒绝清空语料");
        }
        // 一个 PG 数组参数,避免大型语料触发 JDBC 占位符数量上限;Spring 负责释放 SQL Array。
        return new SqlArrayValue("bigint", retainedIds.toArray());
    }

    /** 当前 chunk.embedding 的列维度(pgvector 把维度存在 atttypmod)。 */
    public int embeddingColumnDims() {
        Integer m = jdbc.queryForObject(
                "SELECT atttypmod FROM pg_attribute WHERE attrelid = 'chunk'::regclass AND attname = 'embedding'",
                Integer.class);
        return m == null ? -1 : m;
    }

    /**
     * 让向量列维度跟随配置。调用前须清空 chunk_seg;chunk 原行和 ID 必须保留。
     * 只有维度改变才清旧向量并 ALTER,绝不尝试把非空旧向量 cast 成不同维度。
     * 所有 DDL/DML 都由 reindex 的同一事务包裹,失败可一并回滚。
     */
    public void ensureEmbeddingDims(int dims) {
        if (dims <= 0) {
            throw new IllegalArgumentException("app.embed.dims 必须为正数,当前: " + dims);
        }
        if (embeddingColumnDims() != dims) {
            jdbc.execute("DROP INDEX IF EXISTS idx_chunk_embedding");
            jdbc.update("UPDATE chunk SET embedding = NULL WHERE embedding IS NOT NULL");
            jdbc.execute("ALTER TABLE chunk ALTER COLUMN embedding TYPE vector(" + dims + ")");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_embedding "
                    + "ON chunk USING hnsw (embedding vector_cosine_ops)");
        }
        // 分别检查两列,即使旧库曾出现父/子维度不一致也能修复,不必碰维度没变的父题。
        Integer segDims = jdbc.queryForObject(
                "SELECT atttypmod FROM pg_attribute WHERE attrelid = 'chunk_seg'::regclass AND attname = 'embedding'",
                Integer.class);
        if (segDims == null || segDims != dims) {
            jdbc.execute("DROP INDEX IF EXISTS idx_chunk_seg_embedding");
            jdbc.execute("ALTER TABLE chunk_seg ALTER COLUMN embedding TYPE vector(" + dims + ")");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_seg_embedding "
                    + "ON chunk_seg USING hnsw (embedding vector_cosine_ops)");
        }
    }

    public int countChunks() {
        Integer c = jdbc.queryForObject("SELECT count(*) FROM chunk", Integer.class);
        return c == null ? 0 : c;
    }

    public int countDocs() {
        Integer c = jdbc.queryForObject("SELECT count(*) FROM doc", Integer.class);
        return c == null ? 0 : c;
    }

    public Map<String, Integer> groupChunkCounts() {
        return jdbc.query("SELECT d.group_tag, count(c.id) FROM chunk c "
                        + "JOIN doc d ON d.id = c.doc_id GROUP BY d.group_tag ORDER BY d.group_tag",
                rs -> {
                    var m = new java.util.LinkedHashMap<String, Integer>();
                    while (rs.next()) {
                        m.put(rs.getString(1), rs.getInt(2));
                    }
                    return m;
                });
    }

    // ------------------------------------------------------------------ 树 / 详情
    public List<TreeRow> treeRows() {
        return jdbc.query("SELECT d.id AS doc_id, d.group_tag, d.title, d.url, d.slug, "
                        + "c.id AS chunk_id, c.category, c.question "
                        + "FROM chunk c JOIN doc d ON d.id = c.doc_id "
                        + "ORDER BY d.group_tag, d.id, c.category NULLS FIRST, c.id",
                (rs, i) -> new TreeRow(
                        rs.getLong("doc_id"), rs.getString("group_tag"), rs.getString("title"),
                        rs.getString("url"), rs.getString("slug"),
                        rs.getLong("chunk_id"), rs.getString("category"), rs.getString("question")));
    }

    public ChunkFull getChunk(long id) {
        return jdbc.query(
                "SELECT c.id, c.doc_id, d.group_tag, d.title, d.url, c.category, c.question, c.content "
                        + "FROM chunk c JOIN doc d ON d.id = c.doc_id WHERE c.id = ?",
                rs -> rs.next() ? new ChunkFull(
                        rs.getLong("id"), rs.getLong("doc_id"), rs.getString("group_tag"),
                        rs.getString("title"), rs.getString("url"), rs.getString("category"),
                        rs.getString("question"), rs.getString("content")) : null,
                id);
    }

    public Question randomQuestion(String group) {
        return randomQuestion(group, List.of());
    }

    /** 随机抽一题,跳过 exclude 里的题(同一场面试里已出过的)。没有可抽的返回 null,由调用方决定是否放开限制。 */
    public Question randomQuestion(String group, List<Long> exclude) {
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (group != null && !group.isBlank()) {
            where.add("d.group_tag = ?");
            args.add(group);
        }
        if (!exclude.isEmpty()) {
            where.add("c.id NOT IN (" + String.join(",", Collections.nCopies(exclude.size(), "?")) + ")");
            args.addAll(exclude);
        }
        String sql = "SELECT c.id, d.group_tag, c.category, c.question FROM chunk c "
                + "JOIN doc d ON d.id = c.doc_id "
                + (where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where) + " ")
                + "ORDER BY random() LIMIT 1";
        return jdbc.query(sql, rs -> rs.next() ? new Question(
                rs.getLong("id"), rs.getString("group_tag"), rs.getString("category"),
                rs.getString("question")) : null, args.toArray());
    }

    /** 随机取 n 道题(评测抽样本用)。 */
    public List<Question> randomQuestions(int limit) {
        return jdbc.query("SELECT c.id, d.group_tag, c.category, c.question FROM chunk c "
                        + "JOIN doc d ON d.id = c.doc_id ORDER BY random() LIMIT ?",
                (rs, i) -> new Question(rs.getLong("id"), rs.getString("group_tag"),
                        rs.getString("category"), rs.getString("question")),
                limit);
    }

    /** 全部题目(按 doc/chunk 顺序,golden 集改写用)。 */
    public List<Question> allQuestions() {
        return jdbc.query("SELECT c.id, d.group_tag, c.category, c.question FROM chunk c "
                        + "JOIN doc d ON d.id = c.doc_id ORDER BY d.id, c.id",
                (rs, i) -> new Question(rs.getLong("id"), rs.getString("group_tag"),
                        rs.getString("category"), rs.getString("question")));
    }

    /** 最长的 n 道题(答案长、细节多的题才值得出"细节探针")。 */
    public record LongChunk(long chunkId, String question, int length) {}

    public List<LongChunk> longestChunks(int limit) {
        return jdbc.query("SELECT c.id, c.question, length(c.content) AS len FROM chunk c "
                        + "ORDER BY len DESC LIMIT ?",
                (rs, i) -> new LongChunk(rs.getLong("id"), rs.getString("question"), rs.getInt("len")),
                limit);
    }

    // ------------------------------------------------------------------ 检索
    /** 向量近邻:cosine 距离升序,sim = 1 - distance(题目级)。 */
    public List<Hit> vectorTopK(float[] queryVec, int k) {
        String lit = vectorLiteral(queryVec);
        return jdbc.query("SELECT c.id, d.group_tag, d.title, d.url, c.category, c.question, "
                        + "1 - (c.embedding <=> CAST(? AS vector)) AS sim "
                        + "FROM chunk c JOIN doc d ON d.id = c.doc_id "
                        + "ORDER BY c.embedding <=> CAST(? AS vector) LIMIT ?",
                (rs, i) -> new Hit(
                        rs.getLong("id"), rs.getString("group_tag"), rs.getString("title"), rs.getString("url"),
                        rs.getString("category"), rs.getString("question"), rs.getDouble("sim"), "vector", 0L, 0.0),
                lit, lit, k);
    }

    /** 子段级向量近邻(parent-child 的 child 通道):返回父题 hit,segId 供生成端做上下文倾斜。 */
    public List<Hit> vectorSegTopK(float[] queryVec, int k) {
        String lit = vectorLiteral(queryVec);
        return jdbc.query("SELECT c.id AS chunk_id, s.id AS seg_id, d.group_tag, d.title, d.url, "
                        + "c.category, c.question, 1 - (s.embedding <=> CAST(? AS vector)) AS sim "
                        + "FROM chunk_seg s "
                        + "JOIN chunk c ON c.id = s.chunk_id "
                        + "JOIN doc d ON d.id = c.doc_id "
                        + "ORDER BY s.embedding <=> CAST(? AS vector) LIMIT ?",
                (rs, i) -> new Hit(
                        rs.getLong("chunk_id"), rs.getString("group_tag"), rs.getString("title"), rs.getString("url"),
                        rs.getString("category"), rs.getString("question"), rs.getDouble("sim"), "vector",
                        rs.getLong("seg_id"), 0.0),
                lit, lit, k);
    }

    /** 题干精确命中(normalize 后)。返回同题多页可能多块,排序按 doc。 */
    public List<Hit> exactHits(String questionKey) {
        return jdbc.query("SELECT c.id, d.group_tag, d.title, d.url, c.category, c.question, 1.0 AS sim "
                        + "FROM chunk c JOIN doc d ON d.id = c.doc_id "
                        + "WHERE c.question_key = ? ORDER BY d.id LIMIT 8",
                (rs, i) -> new Hit(
                        rs.getLong("id"), rs.getString("group_tag"), rs.getString("title"), rs.getString("url"),
                        rs.getString("category"), rs.getString("question"), rs.getDouble("sim"), "exact", 0L, 0.0),
                questionKey);
    }

    /** 关键词兜底:题干/正文 ILIKE,题干命中优先。 */
    public List<Hit> keywordHits(String token, int limit) {
        String like = "%" + token.toLowerCase(Locale.ROOT) + "%";
        return jdbc.query("SELECT c.id, d.group_tag, d.title, d.url, c.category, c.question, 0.0 AS sim "
                        + "FROM chunk c JOIN doc d ON d.id = c.doc_id "
                        + "WHERE c.question ILIKE ? OR c.content ILIKE ? "
                        + "ORDER BY (c.question ILIKE ?) DESC, c.id LIMIT ?",
                (rs, i) -> new Hit(
                        rs.getLong("id"), rs.getString("group_tag"), rs.getString("title"), rs.getString("url"),
                        rs.getString("category"), rs.getString("question"), rs.getDouble("sim"), "keyword", 0L, 0.0),
                like, like, like, limit);
    }

    /** 子段正文(生成端"命中片段前置"用)。 */
    public String getSegContent(long segId) {
        return jdbc.query("SELECT content FROM chunk_seg WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, segId);
    }

    /** 批量取重排文本:题干 + 正文前 800 字符(cross-encoder 输入够用了,省流量)。 */
    public Map<Long, String> rerankTexts(List<Long> chunkIds) {
        if (chunkIds.isEmpty()) {
            return Map.of();
        }
        String in = String.join(",", java.util.Collections.nCopies(chunkIds.size(), "?"));
        return jdbc.query("SELECT id, question || E'\\n' || left(content, 800) AS text "
                        + "FROM chunk WHERE id IN (" + in + ")",
                rs -> {
                    var m = new java.util.LinkedHashMap<Long, String>();
                    while (rs.next()) {
                        m.put(rs.getLong("id"), rs.getString("text"));
                    }
                    return m;
                }, chunkIds.toArray());
    }

    /** 批量取子段正文(子段命中的重排证据:细节常在父题中后部,截前 800 字会把它截没)。 */
    public Map<Long, String> segTexts(List<Long> segIds) {
        if (segIds.isEmpty()) {
            return Map.of();
        }
        String in = String.join(",", java.util.Collections.nCopies(segIds.size(), "?"));
        return jdbc.query("SELECT id, content FROM chunk_seg WHERE id IN (" + in + ")",
                rs -> {
                    var m = new java.util.LinkedHashMap<Long, String>();
                    while (rs.next()) {
                        m.put(rs.getLong("id"), rs.getString("content"));
                    }
                    return m;
                }, segIds.toArray());
    }

    // ------------------------------------------------------------------ helpers
    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 6 + 8);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
