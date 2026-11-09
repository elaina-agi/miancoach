package com.autumn.rag.web;

import com.autumn.rag.common.GlobalExceptionHandler;
import com.autumn.rag.common.NotFoundException;
import com.autumn.rag.dao.UserDao;
import com.autumn.rag.service.FavoriteService;
import com.autumn.rag.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FavoriteControllerTest {
    private final FavoriteService favorites = mock(FavoriteService.class);
    private final JwtService jwt = mock(JwtService.class);
    private final UserDao users = mock(UserDao.class);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new FavoriteController(favorites))
                .setCustomArgumentResolvers(new CurrentUserResolver(jwt, users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(jwt.parse("alice")).thenReturn(Optional.of(alice));
        when(jwt.parse("bob")).thenReturn(Optional.of(bob));
        when(users.findById(alice)).thenReturn(Optional.of(user(alice, null)));
        when(users.findById(bob)).thenReturn(Optional.of(user(bob, null)));
    }

    private UserDao.UserRow user(UUID id, Instant disabledAt) {
        return new UserDao.UserRow(id, "test@example.com", "unused", null, "USER", Instant.now(), disabledAt);
    }

    @Test
    void 所有收藏接口都要求登录() throws Exception {
        mvc.perform(get("/api/favorites")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/favorites/1")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/favorites/1")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/favorites/1")).andExpect(status().isUnauthorized());
        verifyNoInteractions(favorites);
    }

    @Test
    void 过期和禁用账号被拒绝() throws Exception {
        when(jwt.parse("expired")).thenReturn(Optional.empty());
        mvc.perform(get("/api/favorites").header("Authorization", "Bearer expired"))
                .andExpect(status().isUnauthorized());
        when(users.findById(alice)).thenReturn(Optional.of(user(alice, Instant.now())));
        mvc.perform(put("/api/favorites/1").header("Authorization", "Bearer alice"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(favorites);
    }

    @Test
    void 伪造userId不会改变查询归属() throws Exception {
        when(favorites.list(alice, "java", 2, 10)).thenReturn(new FavoriteService.Page(List.of(), 0, 2, 10));
        mvc.perform(get("/api/favorites").header("Authorization", "Bearer alice")
                        .param("userId", bob.toString()).param("group", "java").param("page", "2").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2));
        verify(favorites).list(alice, "java", 2, 10);
    }

    @Test
    void 两个用户写入和删除同一道题仍使用各自身份() throws Exception {
        when(favorites.add(any(), eq(1L))).thenReturn(new FavoriteService.State(true));
        when(favorites.remove(any(), eq(1L))).thenReturn(new FavoriteService.State(false));
        mvc.perform(put("/api/favorites/1").header("Authorization", "Bearer alice")
                        .contentType("application/json").content("{\"userId\":\"" + bob + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.favorited").value(true));
        mvc.perform(delete("/api/favorites/1").header("Authorization", "Bearer bob").param("userId", alice.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.favorited").value(false));
        verify(favorites).add(alice, 1);
        verify(favorites).remove(bob, 1);
    }

    @Test
    void 不存在题目返回404() throws Exception {
        when(favorites.add(alice, 999)).thenThrow(new NotFoundException("题目不存在或已下架"));
        mvc.perform(put("/api/favorites/999").header("Authorization", "Bearer alice"))
                .andExpect(status().isNotFound());
    }
}
