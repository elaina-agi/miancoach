package com.autumn.rag.service;

import com.autumn.rag.common.NotFoundException;
import com.autumn.rag.dao.FavoriteDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FavoriteServiceTest {
    private final FavoriteDao dao = mock(FavoriteDao.class);
    private final FavoriteService service = new FavoriteService(dao);
    private final UUID user = UUID.randomUUID();

    @Test
    void 收藏不存在的题返回404且不写入() {
        assertThrows(NotFoundException.class, () -> service.add(user, 10));
        verify(dao, never()).add(any(), anyLong());
    }

    @Test
    void 收藏先锁源题且只写当前用户() {
        when(dao.lockQuestion(10)).thenReturn(true);
        assertTrue(service.add(user, 10).favorited());
        var order = inOrder(dao);
        order.verify(dao).lockQuestion(10);
        order.verify(dao).add(user, 10);
    }

    @Test
    void 取消收藏幂等且只作用当前用户() {
        assertFalse(service.remove(user, 10).favorited());
        assertFalse(service.remove(user, 10).favorited());
        verify(dao, times(2)).remove(user, 10);
    }

    @Test
    void 收藏状态按用户查() {
        when(dao.contains(user, 10)).thenReturn(true);
        assertTrue(service.state(user, 10).favorited());
        assertFalse(service.state(UUID.randomUUID(), 10).favorited());
    }

    @Test
    void 分页限幅过滤去空白且偏移不溢出() {
        when(dao.list(user, "java", 100, 214748364600L)).thenReturn(List.of());
        var p = service.list(user, " java ", Integer.MAX_VALUE, 1000);
        assertEquals(100, p.size());
        verify(dao).list(user, "java", 100, 214748364600L);
        verify(dao).count(user, "java");
        p = service.list(user, "  ", -1, 0);
        assertEquals(1, p.page());
        assertEquals(1, p.size());
        verify(dao).list(user, null, 1, 0L);
    }

    @Test
    void 非法题目ID不访问数据库() {
        assertThrows(IllegalArgumentException.class, () -> service.add(user, 0));
        assertThrows(IllegalArgumentException.class, () -> service.remove(user, -1));
        assertThrows(IllegalArgumentException.class, () -> service.state(user, 0));
        verifyNoInteractions(dao);
    }
}
