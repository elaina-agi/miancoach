package com.autumn.rag.service;

import com.autumn.rag.common.NotFoundException;
import com.autumn.rag.dao.FavoriteDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class FavoriteService {
    private final FavoriteDao dao;

    public FavoriteService(FavoriteDao dao) {
        this.dao = dao;
    }

    public record State(boolean favorited) {}
    public record Page(List<FavoriteDao.Item> items, long total, int page, int size) {}

    // 总数和当前页在同一数据库快照中读取,不被并发收藏/重建拆成两个不一致的结果。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(UUID userId, String group, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        String g = group == null || group.isBlank() ? null : group.trim();
        return new Page(dao.list(userId, g, s, (p - 1L) * s), dao.count(userId, g), p, s);
    }

    public State state(UUID userId, long chunkId) {
        validateId(chunkId);
        return new State(dao.contains(userId, chunkId));
    }

    @Transactional
    public State add(UUID userId, long chunkId) {
        validateId(chunkId);
        if (!dao.lockQuestion(chunkId)) throw new NotFoundException("题目不存在或已下架");
        dao.add(userId, chunkId);
        return new State(true);
    }

    /** 不存在/已取消/源题已删都视为取消成功,不能影响其他用户。 */
    public State remove(UUID userId, long chunkId) {
        validateId(chunkId);
        dao.remove(userId, chunkId);
        return new State(false);
    }

    private static void validateId(long id) {
        if (id <= 0) throw new IllegalArgumentException("题目 ID 必须为正数");
    }
}
