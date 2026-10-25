package com.autumn.rag.mail;

/**
 * 发信端。与 llm 包的 ChatProvider 同一套约定:接口 + mock + 真实实现,由 MailConfig 按 provider 装配。
 *
 * <p>
 * 不叫 MailSender:Spring 自带 org.springframework.mail.MailSender,接 SMTP
 * 时两个会同时出现,重名容易 import 错。
 */
public interface MailProvider {
    /** 是否真的发信(否 = mock,验证码只打到日志)。 */
    boolean isReal();

    /** 标识,用于日志(如 mock、smtp:smtp.qq.com)。 */
    String name();

    /**
     * 发一封纯文本邮件。正文由调用方拼好,发信端不关心内容是验证码还是别的。
     *
     * <p>实现可以是异步的(SmtpMailProvider 就是):方法返回只代表"已受理",不代表已送达。
     * SMTP 连不上、认证失败这类投递错误由实现自己记日志,不抛给调用方。
     *
     * @throws MailException 连受理都做不到(如发信队列已满)
     */
    void send(String to, String subject, String text);
}
