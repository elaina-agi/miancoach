package com.autumn.rag.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Optional;

/**
 * 邮箱验证码 email_code。规则(过期/次数/一次性/冷却)在 AuthService,这里只管读写。
 *
 * <p>
 * <b>时间只用数据库的 now():</b>写入时在 SQL 里算 expires_at,读取时也在 SQL 里算"是否过期""发出多少秒"。
 * 如果写用数据库时钟、比较用 JVM 的 Instant.now(),两边时钟一旦有偏差,冷却和过期判断就会不准。
 */
@Repository
public class EmailCodeDao {
    private final JdbcTemplate jdbc;

    public EmailCodeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 最新一条验证码。expired / ageSecs 是查询时由数据库算出来的,不是表里的列。
     *
     * @param ageSecs 距离发出过了多少秒,用于 60 秒冷却
     */
    public record CodeRow(long id, String codeHash, int attempts,
            boolean consumed, boolean expired, long ageSecs) {
    }
    // ------------------------------------------------------------------ 写

    /** ?::inet:参数是字符串,要显式转型(与 ChatDao 写 ?::jsonb 同理);ip 为 null 时 NULL::inet 也合法。 */
    public void insert(String email, String purpose, String codeHash, String ip, int ttlMinutes) {
        jdbc.update("INSERT INTO email_code(email, purpose, code_hash, ip, expires_at) "
                + "VALUES (?,?,?,?::inet, now() + ? * interval '1 minute')",
                email, purpose, codeHash, ip, ttlMinutes);
    }

    /** 输错一次,返回累加后的次数。用 attempts = attempts + 1 在库里原子自增,不是读出来加一再写回。 */
    public int incrementAttempts(long id) {
        Integer n = jdbc.queryForObject(
                "UPDATE email_code SET attempts = attempts + 1 WHERE id = ? RETURNING attempts",
                Integer.class, id);
        return n == null ? 0 : n;
    }

    /**
     * 标记已使用,成功返回 true。
     *
     * <p>
     * WHERE consumed_at IS NULL 让它成为原子操作:同一个码被两个请求同时提交时,
     * 只有一个能更新到 1 行,另一个更新 0 行、拿到 false。"一次性"靠的是这条 UPDATE,不是先查后改。
     */
    public boolean consume(long id) {
        return jdbc.update(
                "UPDATE email_code SET consumed_at = now() WHERE id = ? AND consumed_at IS NULL", id) == 1;
    }

    // ------------------------------------------------------------------ 读

    /** 该邮箱该用途下最新的一条。只认最新这一条,更早的码自然作废(见 V6 注释)。 */
    public Optional<CodeRow> latest(String email, String purpose) {
        return jdbc.query("SELECT id, code_hash, attempts, "
                + "consumed_at IS NOT NULL AS consumed, "
                + "expires_at <= now() AS expired, "
                + "EXTRACT(EPOCH FROM now() - created_at)::bigint AS age_secs "
                + "FROM email_code WHERE email = ? AND purpose = ? "
                + "ORDER BY created_at DESC LIMIT 1",
                (rs, i) -> new CodeRow(rs.getLong("id"), rs.getString("code_hash"),
                        rs.getInt("attempts"), rs.getBoolean("consumed"),
                        rs.getBoolean("expired"), rs.getLong("age_secs")),
                email, purpose)
                .stream().findFirst();
    }
}
