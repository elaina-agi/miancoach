package com.autumn.rag.mail;

/** 发信失败。unchecked,与 llm 包的 LlmException 同理:调用方大多只能整体失败,不强制每层 try。 */
public class MailException extends RuntimeException {

    public MailException(String message, Throwable cause) {
        super(message, cause);
    }

}
