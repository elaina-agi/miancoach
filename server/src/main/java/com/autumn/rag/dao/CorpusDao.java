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
import java.util.ArrayList;
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

    public long insertChunk(long docId, Section s, float[] embedding) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO chunk(doc_id, category, question, question_key, content, embedding) "
                            + "VALUES (?,?,?,?,?, CAST(? AS vector))",
                    new String[]{"id"});
            ps.setLong(1, docId);
            ps.setString(2, emptyToNull(s.category()));
            ps.setString(3, s.question());
            ps.setString(4, s.questionKey());
            ps.setString(5, s.content());
            ps.setString(6, vectorLiteral(embedding));
            return ps;
        }, kh);
        return kh.getKey().longValue();
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
        // 子段向量列跟随(chunk 级换维度时子段也必须换,两者始终同一空间)
        jdbc.execute("DROP INDEX IF EXISTS idx_chunk_seg_embedding");
        jdbc.execute("ALTER TABLE chunk_seg ALTER COLUMN embedding TYPE vector(" + dims + ")");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_seg_embedding "
                + "ON chunk_seg USING hnsw (embedding vector_cosine_ops)");
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
