package com.autumn.rag.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 收藏的是公共题库中的父题;所有操作的 userId 必须来自已认证的用户。 */
@Repository
public class FavoriteDao {
    private final JdbcTemplate jdbc;

    public FavoriteDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Item(long chunkId, String group, String title, String category,
                       String question, Instant createdAt) {}

    /** 在收藏事务中锁住源题,避免检查通过后被并发重建删掉造成外键错误。 */
    public boolean lockQuestion(long chunkId) {
        return !jdbc.queryForList("SELECT id FROM chunk WHERE id = ? FOR KEY SHARE", Long.class, chunkId).isEmpty();
    }

    /** 唯一约束 + DO NOTHING:重复点击/并发收藏都幂等,且不改变原来的收藏时间。 */
    public void add(UUID userId, long chunkId) {
        jdbc.update("INSERT INTO question_favorite(user_id, chunk_id) VALUES (?,?) "
                + "ON CONFLICT (user_id, chunk_id) DO NOTHING", userId, chunkId);
    }

    public void remove(UUID userId, long chunkId) {
        jdbc.update("DELETE FROM question_favorite WHERE user_id = ? AND chunk_id = ?", userId, chunkId);
    }

    public boolean contains(UUID userId, long chunkId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM question_favorite WHERE user_id = ? AND chunk_id = ?)",
                Boolean.class, userId, chunkId));
    }

    private static final String FROM = " FROM question_favorite f JOIN chunk c ON c.id = f.chunk_id "
            + "JOIN doc d ON d.id = c.doc_id WHERE f.user_id = ?";

    public long count(UUID userId, String group) {
        List<Object> args = args(userId, group);
        Long count = jdbc.queryForObject("SELECT count(*)" + FROM + filter(group), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public List<Item> list(UUID userId, String group, int limit, long offset) {
        List<Object> args = args(userId, group);
        args.add(limit);
        args.add(offset);
        return jdbc.query("SELECT c.id, d.group_tag, d.title, c.category, c.question, f.created_at"
                        + FROM + filter(group) + " ORDER BY f.created_at DESC, f.chunk_id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new Item(rs.getLong("id"), rs.getString("group_tag"), rs.getString("title"),
                        rs.getString("category"), rs.getString("question"), rs.getTimestamp("created_at").toInstant()),
                args.toArray());
    }

    private static String filter(String group) {
        return group == null ? "" : " AND d.group_tag = ?";
    }

    private static List<Object> args(UUID userId, String group) {
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (group != null) args.add(group);
        return args;
    }
}
