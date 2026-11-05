package com.autumn.rag.web;

import com.autumn.rag.common.AuthException;
import com.autumn.rag.common.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminInterceptorTest {

    private CurrentUserResolver resolver;
    private AdminInterceptor interceptor;
    private HttpServletRequest req;

    @BeforeEach
    void setUp() {
        resolver = mock(CurrentUserResolver.class);
        interceptor = new AdminInterceptor(resolver);
        req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer t");
    }

    @Test
    void 游客401() {
        when(resolver.resolve("Bearer t")).thenReturn(null);
        assertThrows(AuthException.class, () -> interceptor.preHandle(req, null, null));
    }

    @Test
    void 普通用户403() {
        when(resolver.resolve("Bearer t")).thenReturn(new LoginUser(UUID.randomUUID(), "USER"));
        assertThrows(ForbiddenException.class, () -> interceptor.preHandle(req, null, null));
    }

    @Test
    void 管理员放行() {
        when(resolver.resolve("Bearer t")).thenReturn(new LoginUser(UUID.randomUUID(), "ADMIN"));
        assertTrue(interceptor.preHandle(req, null, null));
    }
}
