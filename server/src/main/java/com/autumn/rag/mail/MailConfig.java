package com.autumn.rag.mail;

import com.autumn.rag.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/** 装配发信端。provider 语义与 LlmConfig.useReal 一致。 */
@Configuration
public class MailConfig {
    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    /**
     * JavaMailSender 由 Spring Boot 按 spring.mail.* 自动创建(host 有默认值,所以总会创建);
     * 它在真正发信前不会连网络,mock 模式下创建了也无害。
     */
    @Bean
    public MailProvider mailProvider(AppProperties props, MailProperties smtp, JavaMailSender sender) {
        AppProperties.Mail m = props.mail();
        if (!useSmtp(m.provider(), smtp.getPassword())) {
            log.warn("发信端为 mock:验证码只写日志,不会真正发出。要真发信,在 .env 里填 BAGUWEN_SMTP_PASSWORD");
            return new LogMailProvider();
        }
        SmtpMailProvider p = new SmtpMailProvider(sender, smtp.getHost(), m.from(), m.fromName());
        log.info("发信端为 {},发件人 {}", p.name(), m.from());
        return p;
    }

    /**
     * provider:
     *   auto(默认) 配了 SMTP 密码就真发,没配走 mock —— 免配置跑通
     *   smtp       显式要求真发;缺密码直接启动失败(不静默降级,免得以为能收到信其实没发)
     *   mock       强制 mock
     */
    static boolean useSmtp(String provider, String password) {
        String p = (provider == null || provider.isBlank()) ? "auto" : provider.trim().toLowerCase();
        boolean hasPassword = password != null && !password.isBlank();
        return switch (p) {
            case "mock" -> false;
            case "auto" -> hasPassword;
            case "smtp" -> {
                if (!hasPassword) {
                    throw new IllegalStateException("app.mail.provider=smtp 但没配 SMTP 密码。"
                            + "请在项目根 .env 里填 BAGUWEN_SMTP_PASSWORD(或保持 auto,没配时会自动走 mock)。");
                }
                yield true;
            }
            default -> throw new IllegalStateException(
                    "app.mail.provider 只支持 auto|smtp|mock,当前值: " + provider);
        };
    }
}
