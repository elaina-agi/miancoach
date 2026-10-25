package com.autumn.rag.common;

/** 请求过于频繁(验证码冷却、限流)。由 GlobalExceptionHandler 映射成 429。 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }

}
