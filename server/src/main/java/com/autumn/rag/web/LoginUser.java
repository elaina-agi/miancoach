package com.autumn.rag.web;

import java.util.UUID;

/** 当前请求的登录用户。每次请求按 token 里的 id 回查 app_user 得到,改角色、删用户立即生效。 */
public record LoginUser(UUID id, String role) {}
