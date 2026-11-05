package com.autumn.rag.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 账号表 app_user。email 进来之前必须已经归一化成小写(AuthService 负责),
 * 这里不再处理——漏网的大写会被表上的 CHECK 拦住。
 */
@Repository
public class UserDao {

    private final JdbcTemplate jdbc;

    public UserDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** passwordHash 只用于登录比对,不能直接序列化给前端——Controller 另定义出参。disabledAt 非空 = 已禁用。 */
    public record UserRow(UUID id, String email, String passwordHash, String nickname,
            String role, Instant createdAt, Instant disabledAt) {
    }

    /** 后台用户列表的一行:不带密码哈希,带上问答 / 面试次数。 */
    public record AdminUserRow(UUID id, String email, String nickname, String role, Instant createdAt,
            Instant lastLoginAt, Instant disabledAt, int chatSessions, int quizSessions) {
    }

    private static final String COLS = "id, email, password_hash, nickname, role, created_at, disabled_at";

    private static final RowMapper<UserRow> MAPPER = (rs, i) -> new UserRow(
            rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash"),
            rs.getString("nickname"), rs.getString("role"), rs.getTimestamp("created_at").toInstant(),
            toInstant(rs.getTimestamp("disabled_at")));

    private static final RowMapper<AdminUserRow> ADMIN_MAPPER = (rs, i) -> new AdminUserRow(
            rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("nickname"), rs.getString("role"),
            rs.getTimestamp("created_at").toInstant(), toInstant(rs.getTimestamp("last_login_at")),
            toInstant(rs.getTimestamp("disabled_at")), rs.getInt("chat_sessions"), rs.getInt("quiz_sessions"));

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
        // ------------------------------------------------------------------ 写

    /**
     * 建账号,返回数据库生成的 id。
     *
     * <p>
     * 同一邮箱并发注册时,两个请求可能都通过了 Service 里"邮箱未注册"的检查,
     * 最终由 email 的 UNIQUE 约束拦下后到的那个,抛 DuplicateKeyException。
     * 先查后插只是为了给出友好提示,真正的唯一性保证在数据库。
     */
    public UUID insert(String email, String passwordHash, String nickname) {
        return jdbc.queryForObject(
                "INSERT INTO app_user(email, password_hash, nickname) VALUES (?,?,?) RETURNING id",
                UUID.class, email, passwordHash, nickname);
    }

    // ------------------------------------------------------------------ 读

    /**
     * 用 query().findFirst() 而不是 queryForObject:后者查不到会抛
     * EmptyResultDataAccessException。
     */
    public Optional<UserRow> findByEmail(String email) {
        return jdbc.query("SELECT " + COLS + " FROM app_user WHERE email = ?", MAPPER, email)
                .stream().findFirst();
    }

    public Optional<UserRow> findById(UUID id) {
        return jdbc.query("SELECT " + COLS + " FROM app_user WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public boolean existsByEmail(String email) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM app_user WHERE email = ?)", Boolean.class, email);
        return Boolean.TRUE.equals(exists);
    }

    public void touchLogin(UUID id) {
        jdbc.update("UPDATE app_user SET last_login_at = now() WHERE id = ?", id);
    }

    // ------------------------------------------------------------------ 后台管理

    /**
     * 后台用户列表,按注册时间倒序。keyword 为空时列全部,否则按邮箱 / 昵称模糊匹配。
     * 面试次数只算答过题的场次,和"复盘"页的口径一致(开了就走的空场次不算)。
     */
    public List<AdminUserRow> adminList(String keyword, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = keywordFilter(keyword, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("SELECT u.id, u.email, u.nickname, u.role, u.created_at, u.last_login_at, u.disabled_at,"
                + " (SELECT count(*) FROM chat_session c WHERE c.user_id = u.id) AS chat_sessions,"
                + " (SELECT count(*) FROM quiz_session q WHERE q.user_id = u.id"
                + "    AND EXISTS (SELECT 1 FROM quiz_answer a WHERE a.session_id = q.id)) AS quiz_sessions"
                + " FROM app_user u" + where
                + " ORDER BY u.created_at DESC LIMIT ? OFFSET ?", ADMIN_MAPPER, args.toArray());
    }

    public int adminCount(String keyword) {
        List<Object> args = new ArrayList<>();
        Integer n = jdbc.queryForObject("SELECT count(*) FROM app_user u" + keywordFilter(keyword, args),
                Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    /**
     * 按条件拼 WHERE,而不是写成 "? IS NULL OR ..."——PostgreSQL 推断不出一个单独出现的 null 参数是什么类型,会直接报错。
     * 用户输入里的 % 和 _ 要转义,否则搜 "a_b" 会把 "aXb" 也匹配出来。
     */
    private static String keywordFilter(String keyword, List<Object> args) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }
        String like = "%" + keyword.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        args.add(like);
        args.add(like);
        return " WHERE (u.email ILIKE ? OR u.nickname ILIKE ?)";
    }

    /** 返回 false = 用户不存在。已禁用的再禁用一次不改时间,保留第一次禁用的时间点。 */
    public boolean setDisabled(UUID id, boolean disabled) {
        return jdbc.update(disabled
                ? "UPDATE app_user SET disabled_at = COALESCE(disabled_at, now()) WHERE id = ?"
                : "UPDATE app_user SET disabled_at = NULL WHERE id = ?", id) > 0;
    }

    /** 角色取值由表上的 CHECK 兜底,Service 层已经校验过。 */
    public boolean setRole(UUID id, String role) {
        return jdbc.update("UPDATE app_user SET role = ? WHERE id = ?", role, id) > 0;
    }

    public boolean setNickname(UUID id, String nickname) {
        return jdbc.update("UPDATE app_user SET nickname = ? WHERE id = ?", nickname, id) > 0;
    }

    /** 问答 / 面试记录由外键 ON DELETE CASCADE 跟着删(见 V7)。 */
    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM app_user WHERE id = ?", id) > 0;
    }
}
