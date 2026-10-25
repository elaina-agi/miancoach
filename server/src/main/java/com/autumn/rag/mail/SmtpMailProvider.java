package com.autumn.rag.mail;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 真实发信端:走 SMTP(目前是 Resend,smtp.resend.com:465)。
 *
 * <p><b>为什么异步:</b>一次 SMTP 发信要 TCP + TLS 握手 + 认证,连海外服务器常常要一两秒,
 * 慢的时候更久。接口不该陪着等,send() 只把任务放进队列就返回。代价是投递失败(密码错、被拒收)
 * 用户当场看不到提示,只能在日志里查;对验证码场景可以接受:收不到信,60 秒后重发即可。
 *
 * <p>线程池自己建、自己关(实现 AutoCloseable,Spring 关闭时会调 close)。
 * 不注册成 Spring Bean:AskService 按类型注入 ExecutorService,再多一个同类型 Bean 就会冲突。
 */
public class SmtpMailProvider implements MailProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailProvider.class);

    private final JavaMailSender sender;
    private final String host;
    private final InternetAddress from;
    private final ExecutorService executor;

    public SmtpMailProvider(JavaMailSender sender, String host, String fromAddress, String fromName) {
        this.sender = sender;
        this.host = host;
        this.from = parseFrom(fromAddress, fromName); // 地址写错在启动时就报,而不是等到第一次发信
        // 单线程 + 有界队列:验证码量很小,一个线程足够;队列满了直接拒绝,不让积压无限增长
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(100), r -> {
                    Thread t = new Thread(r, "mail-send");
                    t.setDaemon(true);
                    return t;
                });
    }

    @Override
    public boolean isReal() {
        return true;
    }

    @Override
    public String name() {
        return "smtp:" + host;
    }

    @Override
    public void send(String to, String subject, String text) {
        try {
            executor.execute(() -> doSend(to, subject, text));
        } catch (RejectedExecutionException e) {
            throw new MailException("发信队列已满,请稍后再试", e);
        }
    }

    private void doSend(String to, String subject, String text) {
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, StandardCharsets.UTF_8.name());
            h.setFrom(from);
            h.setTo(to);
            h.setSubject(subject);
            h.setText(text);
            sender.send(msg);
            log.info("邮件已发出 to={} subject={}", to, subject);
        } catch (Exception e) {
            // 不打印正文:里面有验证码
            log.error("邮件发送失败 to={} subject={}: {}", to, subject, e.getMessage());
        }
    }

    /** 关闭时等队列里的信发完(最多 15 秒),免得重启服务时刚受理的验证码丢掉。 */
    @Override
    public void close() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
            log.warn("关闭时仍有邮件未发出");
        }
    }

    /**
     * 地址和显示名分开配:.env 按 ISO-8859-1 读取,中文显示名写在那里会乱码,
     * 所以显示名放在 application.yml(UTF-8)里。显示名按 UTF-8 编码,收件箱里才能正确显示中文。
     */
    static InternetAddress parseFrom(String address, String name) {
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("未配置发件地址,请在 .env 里填 BAGUWEN_MAIL_FROM(如 noreply@你的域名)");
        }
        try {
            InternetAddress a = new InternetAddress(address.trim(), true);
            if (name != null && !name.isBlank()) {
                a.setPersonal(name.trim(), StandardCharsets.UTF_8.name());
            }
            return a;
        } catch (AddressException | UnsupportedEncodingException e) {
            throw new IllegalStateException("发件地址格式不正确: " + address, e);
        }
    }
}
