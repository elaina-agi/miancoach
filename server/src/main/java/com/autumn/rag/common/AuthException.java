package com.autumn.rag.common;

/** 未登录 / 登录失败 / token 无效。由 GlobalExceptionHandler 映射成 401。 */
public class AuthException extends RuntimeException{

    public AuthException(String message) {
        super(message);
    }
    
}
