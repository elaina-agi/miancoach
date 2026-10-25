package com.autumn.rag.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock 发信端:不连 SMTP,把邮件打到日志(logs/server.log),本地注册时从日志里抄验证码。
 *
 * <p>
 * 只适合开发。若线上也落到这里,用户永远收不到码——所以 MailConfig 装配它时会打 WARN。
 */
public class LogMailProvider implements MailProvider {
    private static final Logger log = LoggerFactory.getLogger(LogMailProvider.class);

    @Override
    public boolean isReal() {
        return false;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public void send(String to, String subject, String text) {
        log.info("[mock mail] to={} subject={}\n{}", to, subject, text);
    }

}
