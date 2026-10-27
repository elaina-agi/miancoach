package com.autumn.rag.web;

import com.autumn.rag.common.AuthException;
import com.autumn.rag.dao.UserDao;
import com.autumn.rag.dao.UserDao.UserRow;
import com.autumn.rag.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrentUserResolverTest {

    private final UUID id = UUID.randomUUID();
    private JwtService jwt;
    private UserDao users;
    private CurrentUserResolver resolver;

    @BeforeEach
    void setUp() {
        jwt = mock(JwtService.class);
        users = mock(UserDao.class);
        resolver = new CurrentUserResolver(jwt, users);
    }

    @Test
    void 不带token是游客() {
        assertNull(resolver.resolve(null));
        assertNull(resolver.resolve("  "));
    }

    @Test
    void 带了无效token不降级成游客而是401() {
        when(jwt.parse("bad")).thenReturn(Optional.empty());

        assertThrows(AuthException.class, () -> resolver.resolve("Bearer bad"));
        assertThrows(AuthException.class, () -> resolver.resolve("Basic xxx"));
    }

    @Test
    void token有效但用户已删也是401() {
        when(jwt.parse("ok")).thenReturn(Optional.of(id));
        when(users.findById(id)).thenReturn(Optional.empty());

        assertThrows(AuthException.class, () -> resolver.resolve("Bearer ok"));
    }

    @Test
    void 有效token解析出用户_前缀不区分大小写() {
        when(jwt.parse("ok")).thenReturn(Optional.of(id));
        when(users.findById(id)).thenReturn(Optional.of(
                new UserRow(id, "a@b.com", "hash", null, "USER", Instant.now())));

        LoginUser u = resolver.resolve("bearer ok");

        assertEquals(id, u.id());
        assertEquals("USER", u.role());
    }
}
