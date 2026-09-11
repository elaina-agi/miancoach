package com.autumn.rag.dao;

import com.autumn.rag.corpus.MdParser.PageMeta;
import com.autumn.rag.corpus.MdParser.Section;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDate;
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
    /** 检索命中块(题目级)。 */
    public record Hit(
            long chunkId, String group, String title, String url,
            String category, String question, double sim, String channel) {}

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
    public long insertDoc(PageMeta meta) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO doc(group_tag, slug, title, url, lastmod, source, md_file) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setString(1, meta.group());
            ps.setString(2, meta.slug());
            ps.setString(3, meta.title());
            ps.setString(4, emptyToNull(meta.url()));
            ps.setObject(5, parseDate(meta.lastmod()), Types.DATE);
            ps.setString(6, emptyToNull(meta.source()));
            ps.setString(7, meta.mdFile());
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void insertChunk(long docId, Section s, float[] embedding) {
        jdbc.update(
                "INSERT INTO chunk(doc_id, category, question, question_key, content, embedding) "
                        + "VALUES (?,?,?,?,?, CAST(? AS vector))",
                docId, emptyToNull(s.category()), s.question(), s.questionKey(),
                s.content(), vectorLiteral(embedding));
    }

    /** 全量重灌前清库(chunk 先删,doc 级联)。quiz_answer 里被删 chunk 置空,历史保留。 */
    public void wipe() {
        jdbc.update("DELETE FROM chunk");
        jdbc.update("DELETE FROM doc");
    }

    /** 当前 chunk.embedding 的列维度(pgvector 把维度存在 atttypmod)。 */
    public int embeddingColumnDims() {
        Integer m = jdbc.queryForObject(
                "SELECT atttypmod FROM pg_attribute WHERE attrelid = 'chunk'::regclass AND attname = 'embedding'",
                Integer.class);
        return m == null ? -1 : m;
    }

    /**
     * 让向量列维度跟随配置(换 embedding 模型时维度可能变)。
     * 只在 reindex 流程里调用——此时表已清空,改列类型安全;顺手重建 HNSW 索引。
     */
    public void ensureEmbeddingDims(int dims) {
        if (dims <= 0) {
            throw new IllegalArgumentException("app.embed.dims 必须为正数,当前: " + dims);
        }
        int cur = embeddingColumnDims();
        if (cur == dims) {
            return;
        }
        jdbc.execute("DROP INDEX IF EXISTS idx_chunk_embedding");
        jdbc.execute("ALTER TABLE chunk ALTER COLUMN embedding TYPE vector(" + dims + ")");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_embedding "
                + "ON chunk USING hnsw (embedding vector_cosine_ops)");
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
        String sql = "SELECT c.id, d.group_tag, c.category, c.question FROM chunk c "
                + "JOIN doc d ON d.id = c.doc_id "
                + (group == null || group.isBlank() ? "" : "WHERE d.group_tag = ? ")
                + "ORDER BY random() LIMIT 1";
        Object[] args = (group == null || group.isBlank()) ? new Object[0] : new Object[]{group};
        return jdbc.query(sql, rs -> rs.next() ? new Question(
                rs.getLong("id"), rs.getString("group_tag"), rs.getString("category"),
                rs.getString("question")) : null, args);
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
    /** 向量近邻:cosine 距离升序,sim = 1 - distance。 */
    public List<Hit> vectorTopK(float[] queryVec, int k) {
        String lit = vectorLiteral(queryVec);
        return jdbc.query("SELECT c.id, d.group_tag, d.title, d.url, c.category, c.question, "
                        + "1 - (c.embedding <=> CAST(? AS vector)) AS sim "
                        + "FROM chunk c JOIN doc d ON d.id = c.doc_id "
                        + "ORDER BY c.embedding <=> CAST(? AS vector) LIMIT ?",
                (rs, i) -> new Hit(
                        rs.getLong("id"), rs.getString("group_tag"), rs.getString("title"), rs.getString("url"),
                        rs.getString("category"), rs.getString("question"), rs.getDouble("sim"), "vector"),
                lit, lit, k);
    }

    /** 题干精确命中(normalize 后)。返回同题多页可能多块,排序按 doc。 */
    public List<Hit> exactHits(String questionKey) {
        return jdbc.query("SELECT c.id, d.group_tag, d.title, d.url, c.category, c.question, 1.0 AS sim "
                        + "FROM chunk c JOIN doc d ON d.id = c.doc_id "
                        + "WHERE c.question_key = ? ORDER BY d.id LIMIT 8",
                (rs, i) -> new Hit(
                        rs.getLong("id"), rs.getString("group_tag"), rs.getString("title"), rs.getString("url"),
                        rs.getString("category"), rs.getString("question"), rs.getDouble("sim"), "exact"),
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
                        rs.getString("category"), rs.getString("question"), rs.getDouble("sim"), "keyword"),
                like, like, like, limit);
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
