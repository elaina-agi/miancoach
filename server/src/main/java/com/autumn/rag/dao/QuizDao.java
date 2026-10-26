package com.autumn.rag.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 面试会话 / 作答 / 薄弱聚合。 */
@Repository
public class QuizDao {

    private final JdbcTemplate jdbc;

    public QuizDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SessionRow(UUID id, String groupTag, String status, Instant createdAt) {}

    public record AnswerRow(long id, Long chunkId, String groupTag, String category,
                            String question, String userAnswer, Integer score, String gradeJson,
                            Instant createdAt) {}

    public record WeakRow(String groupTag, String category, long answered, Double avgScore, long missed) {}

    // ------------------------------------------------------------------ 写入
    public UUID createSession(String groupTag) {
        return jdbc.queryForObject(
                "INSERT INTO quiz_session(group_tag) VALUES (?) RETURNING id",
                UUID.class, (groupTag == null || groupTag.isBlank()) ? null : groupTag);
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
    public List<SessionRow> sessions(int limit) {
        return jdbc.query("SELECT id, group_tag, status, created_at FROM quiz_session "
                        + "ORDER BY created_at DESC LIMIT ?",
                (rs, i) -> new SessionRow(rs.getObject("id", UUID.class), rs.getString("group_tag"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant()),
                limit);
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

    public List<WeakRow> weakness() {
        return jdbc.query(
                "SELECT group_tag, category, count(*) AS answered, "
                        + "ROUND(AVG(score)::numeric, 1) AS avg_score, "
                        + "COALESCE(SUM(CASE WHEN grade IS NOT NULL THEN jsonb_array_length(grade->'missing') ELSE 0 END), 0) AS missed "
                        + "FROM quiz_answer GROUP BY group_tag, category ORDER BY missed DESC NULLS LAST, answered DESC",
                (rs, i) -> new WeakRow(rs.getString("group_tag"), rs.getString("category"),
                        rs.getLong("answered"),
                        // ROUND(AVG(...)) 返回 numeric(即 BigDecimal),getObject 直接 cast Double 会 500
                        rs.getObject("avg_score") == null ? null : rs.getBigDecimal("avg_score").doubleValue(),
                        rs.getLong("missed")));
    }

    private static AnswerRow row(ResultSet rs) throws java.sql.SQLException {
        return new AnswerRow(rs.getLong("id"), rs.getObject("chunk_id") == null ? null : rs.getLong("chunk_id"),
                rs.getString("group_tag"), rs.getString("category"), rs.getString("question"),
                rs.getString("user_answer"), rs.getObject("score") == null ? null : rs.getInt("score"),
                rs.getString("grade_json"), rs.getTimestamp("created_at").toInstant());
    }
}
