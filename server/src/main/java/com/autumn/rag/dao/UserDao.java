package com.autumn.rag.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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

    /** passwordHash 只用于登录比对,不能直接序列化给前端——Controller 另定义出参。 */
    public record UserRow(UUID id, String email, String passwordHash, String nickname,
            String role, Instant createdAt) {
    }

    private static final String COLS = "id, email, password_hash, nickname, role, created_at";

    private static final RowMapper<UserRow> MAPPER = (rs, i) -> new UserRow(
            rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash"),
            rs.getString("nickname"), rs.getString("role"), rs.getTimestamp("created_at").toInstant());
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
}
