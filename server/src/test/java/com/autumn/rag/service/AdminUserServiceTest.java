package com.autumn.rag.service;

import com.autumn.rag.common.NotFoundException;
import com.autumn.rag.dao.UserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 重点:不能对自己禁用 / 删除 / 降级,免得后台里一个管理员都不剩。 */
class AdminUserServiceTest {

    private final UUID me = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private UserDao dao;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        dao = mock(UserDao.class);
        service = new AdminUserService(dao);
    }

    @Test
    void 不能对自己禁用_删除_降级() {
        assertThrows(IllegalArgumentException.class, () -> service.setDisabled(me, me, true));
        assertThrows(IllegalArgumentException.class, () -> service.delete(me, me));
        assertThrows(IllegalArgumentException.class, () -> service.setRole(me, me, "USER"));

        verify(dao, never()).setDisabled(any(), anyBoolean());
        verify(dao, never()).delete(any());
        verify(dao, never()).setRole(any(), anyString());
    }

    @Test
    void 对别人可以() {
        when(dao.setDisabled(other, true)).thenReturn(true);
        when(dao.setRole(other, "ADMIN")).thenReturn(true);
        when(dao.delete(other)).thenReturn(true);

        service.setDisabled(me, other, true);
        service.setRole(me, other, "ADMIN");
        service.delete(me, other);

        verify(dao).setDisabled(other, true);
        verify(dao).setRole(other, "ADMIN");
        verify(dao).delete(other);
    }

    @Test
    void 角色只能是USER或ADMIN() {
        assertThrows(IllegalArgumentException.class, () -> service.setRole(me, other, "ROOT"));
        assertThrows(IllegalArgumentException.class, () -> service.setRole(me, other, null));
        verify(dao, never()).setRole(any(), anyString());
    }

    @Test
    void 昵称_空白清空_超长拒绝() {
        when(dao.setNickname(other, null)).thenReturn(true);
        service.setNickname(other, "   ");
        verify(dao).setNickname(other, null);

        assertThrows(IllegalArgumentException.class, () -> service.setNickname(other, "字".repeat(21)));
    }

    @Test
    void 用户不存在404() {
        when(dao.delete(other)).thenReturn(false);
        assertThrows(NotFoundException.class, () -> service.delete(me, other));
    }
}
