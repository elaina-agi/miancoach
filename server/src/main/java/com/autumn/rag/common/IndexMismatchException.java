package com.autumn.rag.common;

/** 索引与当前向量模型不一致(或尚未建索引):必须重新索引才能检索。 */
public class IndexMismatchException extends RuntimeException {

    public IndexMismatchException(String message) {
        super(message);
    }
}
