package com.autumn.rag.mail;

import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 不连 SMTP:JavaMailSender 用替身,只检查拼出来的邮件对不对。close() 会等队列发完,之后再断言。 */
class SmtpMailProviderTest {

    private static JavaMailSender senderStub() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((jakarta.mail.Session) null));
        return sender;
    }

    @Test
    void buildsUtf8MessageWithDisplayName() throws Exception {
        JavaMailSender sender = senderStub();
        SmtpMailProvider p = new SmtpMailProvider(sender, "smtp.resend.com", "noreply@example.com", "面试陪练");

        p.send("user@example.com", "【面试陪练】注册验证码", "你的验证码是 123456");
        p.close();

        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(cap.capture());
        MimeMessage m = cap.getValue();
        InternetAddress from = (InternetAddress) m.getFrom()[0];
        assertEquals("noreply@example.com", from.getAddress());
        assertEquals("面试陪练", from.getPersonal()); // 中文显示名编码后能还原,收件箱里才不会乱码
        assertEquals("user@example.com", m.getRecipients(Message.RecipientType.TO)[0].toString());
        assertEquals("【面试陪练】注册验证码", m.getSubject());
        assertTrue(m.getContent().toString().contains("123456"));
    }

    /** 投递失败(密码错、被拒收)只记日志,不抛给调用方——异步发信本来也没法抛回去。 */
    @Test
    void deliveryFailureDoesNotPropagate() throws Exception {
        JavaMailSender sender = senderStub();
        doThrow(new MailSendException("535 Authentication failed")).when(sender).send(any(MimeMessage.class));
        SmtpMailProvider p = new SmtpMailProvider(sender, "smtp.resend.com", "noreply@example.com", null);

        assertDoesNotThrow(() -> p.send("user@example.com", "s", "t"));
        p.close();
        verify(sender).send(any(MimeMessage.class));
    }

    /** 发件地址没配或写错:构造时就失败,也就是启动失败,而不是等到用户点"获取验证码"。 */
    @Test
    void badFromFailsAtStartup() {
        JavaMailSender sender = senderStub();
        assertThrows(IllegalStateException.class, () -> new SmtpMailProvider(sender, "h", "", "x"));
        assertThrows(IllegalStateException.class, () -> new SmtpMailProvider(sender, "h", "not an address", "x"));
    }

    @Test
    void providerSelection() {
        assertFalse(MailConfig.useSmtp("auto", ""));
        assertTrue(MailConfig.useSmtp("auto", "re_x"));
        assertTrue(MailConfig.useSmtp(null, "re_x"));      // 没配 provider 等同 auto
        assertFalse(MailConfig.useSmtp("mock", "re_x"));   // 强制 mock 时有密码也不发
        assertTrue(MailConfig.useSmtp("SMTP", "re_x"));
        assertThrows(IllegalStateException.class, () -> MailConfig.useSmtp("smtp", ""));
        assertThrows(IllegalStateException.class, () -> MailConfig.useSmtp("qq", "re_x"));
    }
}
