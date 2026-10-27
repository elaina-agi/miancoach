package com.autumn.rag.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 问答历史:会话与消息两张表。结构对称于 {@link QuizDao}(抽题会话)。
 *
 * <p>存的是**文本记录**,不含任何向量——向量是检索用的索引(在 chunk / chunk_seg 里),
 * 历史记录是回看用的账本,没有东西要拿它去搜。
 *
 * <p>时间戳统一返回 {@link Instant}(与 QuizDao 一致),Jackson 会序列化成 ISO-8601,
 * 前端 {@code new Date(str)} 可直接解析,用来做"今天/昨天/更早"的分组。
 */
@Repository
public class ChatDao {

    private final JdbcTemplate jdbc;

    public ChatDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SessionRow(UUID id, String title, Instant createdAt, Instant updatedAt) {}

    /** refs 原样存 JSON 文本,读出来也是文本;解析成对象是上层(Service)的事。 */
    public record MessageRow(long id, String role, String content, String refsJson,
                             boolean noAnswer, Instant createdAt) {}

    // ------------------------------------------------------------------ 写

    /** 建会话,返回数据库生成的 id(与 QuizDao.createSession 同款写法)。 */
    public UUID createSession(UUID userId, String title) {
        return jdbc.queryForObject(
                "INSERT INTO chat_session(user_id, title) VALUES (?,?) RETURNING id",
                UUID.class, userId, title);
    }

    /**
     * 追加一条消息。
     *
     * @param role     user | assistant
     * @param refsJson 引用卡片快照的 JSON 文本;用户消息传 null(列可为空)
     */
    public void insertMessage(UUID sessionId, String role, String content,
                              String refsJson, boolean noAnswer) {
        // ?::jsonb 显式转型:参数是普通字符串,不转型 PG 会当 text 处理(与 QuizDao 写 grade 同款)
        jdbc.update("INSERT INTO chat_message(session_id, role, content, refs, no_answer) "
                        + "VALUES (?,?,?,?::jsonb,?)",
                sessionId, role, content, refsJson, noAnswer);
    }

    /**
     * 把会话顶到列表最前。列表按 updated_at 倒序,而 updated_at 是冗余字段(见 V5 迁移的注释),
     * 不会随消息自动变化——所以每次追加消息后必须显式调用一次,否则会话会永远沉在列表底部。
     */
    public void touchSession(UUID sessionId) {
        jdbc.update("UPDATE chat_session SET updated_at = now() WHERE id = ?", sessionId);
    }

    // ------------------------------------------------------------------ 读

    /** 某个用户的会话列表,最近活跃的在前。limit 由调用方给(接口默认 50)。 */
    public List<SessionRow> sessions(UUID userId, int limit) {
        return jdbc.query("SELECT id, title, created_at, updated_at FROM chat_session "
                        + "WHERE user_id = ? ORDER BY updated_at DESC LIMIT ?",
                (rs, i) -> new SessionRow(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                userId, limit);
    }

    /** 会话存在且属于这个用户。读消息、往里写问答之前都要先过这一关。 */
    public boolean ownsSession(UUID sessionId, UUID userId) {
        Boolean owns = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM chat_session WHERE id = ? AND user_id = ?)",
                Boolean.class, sessionId, userId);
        return Boolean.TRUE.equals(owns);
    }

    /** 一个会话的全部消息,按写入顺序(回放用)。refs::text 是为了拿到原始 JSON 文本,与 QuizDao 读 grade 同款。 */
    public List<MessageRow> messages(UUID sessionId) {
        return jdbc.query("SELECT id, role, content, refs::text AS refs_json, no_answer, created_at "
                        + "FROM chat_message WHERE session_id = ? ORDER BY id",
                (rs, i) -> new MessageRow(rs.getLong("id"), rs.getString("role"), rs.getString("content"),
                        rs.getString("refs_json"), rs.getBoolean("no_answer"),
                        rs.getTimestamp("created_at").toInstant()),
                sessionId);
    }

    /**
     * 删会话。消息由外键 ON DELETE CASCADE 一并清掉,不会留孤儿行。
     * 归属条件直接写进 WHERE,不先查再删:返回 0 就是"不存在或不是你的",两种情况对外不区分。
     */
    public int deleteSession(UUID sessionId, UUID userId) {
        return jdbc.update("DELETE FROM chat_session WHERE id = ? AND user_id = ?", sessionId, userId);
    }
}
