package com.autumn.rag.common;

/** 和当前状态冲突(比如索引已经在重建,又点了一次)。由 GlobalExceptionHandler 映射成 409。 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }

}
