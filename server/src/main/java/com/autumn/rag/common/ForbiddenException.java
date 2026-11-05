package com.autumn.rag.common;

/** 已登录但没有权限(比如普通用户调管理接口)。由 GlobalExceptionHandler 映射成 403。 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }

}
