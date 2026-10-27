package com.autumn.rag.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 面试会话 / 作答 / 薄弱聚合。场次归属记在 quiz_session.user_id,作答没有这一列:
 * 按用户统计时 JOIN quiz_session(见 V7 迁移的注释)。
 */
@Repository
public class QuizDao {

    private final JdbcTemplate jdbc;

    public QuizDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** answered = 已作答题数;avgScore 只算有分数的题(mock 批改没有分),一题都没评分时为 null。 */
    public record SessionRow(UUID id, String groupTag, String status, Instant createdAt,
                             long answered, Double avgScore) {}

    public record AnswerRow(long id, Long chunkId, String groupTag, String category,
                            String question, String userAnswer, Integer score, String gradeJson,
                            Instant createdAt) {}

    public record WeakRow(String groupTag, String category, long answered, Double avgScore, long missed) {}

    /** 复盘页顶部的总览:只统计真正答过题的场次,开了就走的空场次不算。 */
    public record Overview(long sessions, long answered, Double avgScore, long missed) {}

    // ------------------------------------------------------------------ 写入
    public UUID createSession(UUID userId, String groupTag) {
        return jdbc.queryForObject(
                "INSERT INTO quiz_session(user_id, group_tag) VALUES (?,?) RETURNING id",
                UUID.class, userId, (groupTag == null || groupTag.isBlank()) ? null : groupTag);
    }

    public long saveAnswer(UUID sessionId, Long chunkId, String groupTag, String category,
                           String question, String userAnswer, String gradeJson, Integer score) {
        return jdbc.queryForObject(
                "INSERT INTO quiz_answer(session_id, chunk_id, group_tag, category, question, user_answer, grade, score) "
                        + "VALUES (?,?,?,?,?,?,?::jsonb,?) RETURNING id",
                Long.class, sessionId, chunkId, groupTag, category, question, userAnswer,
                gradeJson == null ? null : gradeJson, score);
    }

    // ------------------------------------------------------------------ 读
    /** answeredOnly:只要答过题的场次。抽了题没答就离开的场次很多,复盘列表里全是空行没有意义。 */
    public List<SessionRow> sessions(UUID userId, int limit, boolean answeredOnly) {
        return jdbc.query("SELECT s.id, s.group_tag, s.status, s.created_at, "
                        + "count(a.id) AS answered, ROUND(AVG(a.score)::numeric, 1) AS avg_score "
                        + "FROM quiz_session s LEFT JOIN quiz_answer a ON a.session_id = s.id "
                        + "WHERE s.user_id = ? "
                        + "GROUP BY s.id "
                        + (answeredOnly ? "HAVING count(a.id) > 0 " : "")
                        + "ORDER BY s.created_at DESC LIMIT ?",
                (rs, i) -> new SessionRow(rs.getObject("id", UUID.class), rs.getString("group_tag"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                        rs.getLong("answered"), decimal(rs, "avg_score")),
                userId, limit);
    }

    /** 场次存在且属于这个用户。抽题、批改、回看、出报告之前都要先过这一关。 */
    public boolean ownsSession(UUID sessionId, UUID userId) {
        Boolean owns = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM quiz_session WHERE id = ? AND user_id = ?)",
                Boolean.class, sessionId, userId);
        return Boolean.TRUE.equals(owns);
    }

    /** 某场已出过的题(作答过的),再抽题时跳过。 */
    public List<Long> answeredChunkIds(UUID sessionId) {
        return jdbc.queryForList("SELECT chunk_id FROM quiz_answer WHERE session_id = ? AND chunk_id IS NOT NULL",
                Long.class, sessionId);
    }

    public List<AnswerRow> answers(UUID sessionId) {
        return jdbc.query("SELECT id, chunk_id, group_tag, category, question, user_answer, score, "
                        + "grade::text AS grade_json, created_at "
                        + "FROM quiz_answer WHERE session_id = ? ORDER BY id",
                (rs, i) -> row(rs), sessionId);
    }

    /** 累计漏点:每题批改里 missing 数组的长度求和(薄弱点和总览共用) */
    private static final String MISSED =
            "COALESCE(SUM(CASE WHEN a.grade IS NOT NULL THEN jsonb_array_length(a.grade->'missing') ELSE 0 END), 0)";

    public List<WeakRow> weakness(UUID userId) {
        return jdbc.query(
                "SELECT a.group_tag, a.category, count(*) AS answered, "
                        + "ROUND(AVG(a.score)::numeric, 1) AS avg_score, " + MISSED + " AS missed "
                        + "FROM quiz_answer a JOIN quiz_session s ON s.id = a.session_id WHERE s.user_id = ? "
                        + "GROUP BY a.group_tag, a.category ORDER BY missed DESC NULLS LAST, answered DESC",
                (rs, i) -> new WeakRow(rs.getString("group_tag"), rs.getString("category"),
                        rs.getLong("answered"), decimal(rs, "avg_score"), rs.getLong("missed")),
                userId);
    }

    public Overview overview(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(DISTINCT a.session_id) AS sessions, count(*) AS answered, "
                        + "ROUND(AVG(a.score)::numeric, 1) AS avg_score, " + MISSED + " AS missed "
                        + "FROM quiz_answer a JOIN quiz_session s ON s.id = a.session_id WHERE s.user_id = ?",
                (rs, i) -> new Overview(rs.getLong("sessions"), rs.getLong("answered"),
                        decimal(rs, "avg_score"), rs.getLong("missed")),
                userId);
    }

    /** ROUND(AVG(...)) 返回 numeric(即 BigDecimal),getObject 直接 cast Double 会 500 */
    private static Double decimal(ResultSet rs, String col) throws java.sql.SQLException {
        return rs.getObject(col) == null ? null : rs.getBigDecimal(col).doubleValue();
    }

    private static AnswerRow row(ResultSet rs) throws java.sql.SQLException {
        return new AnswerRow(rs.getLong("id"), rs.getObject("chunk_id") == null ? null : rs.getLong("chunk_id"),
                rs.getString("group_tag"), rs.getString("category"), rs.getString("question"),
                rs.getString("user_answer"), rs.getObject("score") == null ? null : rs.getInt("score"),
                rs.getString("grade_json"), rs.getTimestamp("created_at").toInstant());
    }
}
