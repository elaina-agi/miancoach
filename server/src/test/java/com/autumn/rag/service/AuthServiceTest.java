package com.autumn.rag.service;

import com.autumn.rag.common.AuthException;
import com.autumn.rag.common.TooManyRequestsException;
import com.autumn.rag.config.AppProperties;
import com.autumn.rag.dao.EmailCodeDao;
import com.autumn.rag.dao.EmailCodeDao.CodeRow;
import com.autumn.rag.dao.UserDao;
import com.autumn.rag.dao.UserDao.UserRow;
import com.autumn.rag.mail.MailProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 不启动 Spring、不连库:DAO 和发信端都用 Mockito 替身,JwtService 用真的。
 * 重点覆盖验证码的几条规则,以及"失败时哪些写操作必须发生 / 绝不能发生"。
 */
class AuthServiceTest {

    private static final String EMAIL = "foo@x.com";
    private static final String CODE = "123456";

    private UserDao userDao;
    private EmailCodeDao codeDao;
    private MailProvider mail;
    private JwtService jwt;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        userDao = mock(UserDao.class);
        codeDao = mock(EmailCodeDao.class);
        mail = mock(MailProvider.class);
        jwt = new JwtService(Base64.getEncoder().encodeToString(new byte[32]), Duration.ofHours(1));
        // 只有 auth 这一项会被用到,其余传 null
        AppProperties props = new AppProperties(null, null, null, null, null,
                new AppProperties.Auth("", 1, 10, 5, 60), null); // mail 传 null:发信端整个被 mock 掉了
        auth = new AuthService(userDao, codeDao, mail, jwt, props);
    }

    /** 一条未用、未过期、发出 120 秒(已过冷却)的验证码。 */
    private static CodeRow validCode(int attempts) {
        return new CodeRow(1L, AuthService.hash(EMAIL, CODE), attempts, false, false, 120);
    }

    // ------------------------------------------------------------------ 发码

    @Test
    void sendCode_mailedCodeMatchesStoredHash() {
        when(codeDao.latest(EMAIL, "register")).thenReturn(Optional.empty());

        auth.sendRegisterCode("  Foo@X.com ", "127.0.0.1"); // 顺带验证归一化

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(EMAIL), anyString(), text.capture());
        Matcher m = Pattern.compile("\\d{6}").matcher(text.getValue());
        assertTrue(m.find(), "邮件里应该有 6 位验证码");
        verify(codeDao).insert(EMAIL, "register", AuthService.hash(EMAIL, m.group()), "127.0.0.1", 10);
    }

    /** 已注册邮箱:照样落库、照样发信,但信里没有验证码。 */
    @Test
    void sendCode_registeredEmail_noCodeInMail() {
        when(codeDao.latest(EMAIL, "register")).thenReturn(Optional.empty());
        when(userDao.existsByEmail(EMAIL)).thenReturn(true);

        auth.sendRegisterCode(EMAIL, null);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(EMAIL), anyString(), text.capture());
        assertFalse(Pattern.compile("\\d{6}").matcher(text.getValue()).find());
        verify(codeDao).insert(eq(EMAIL), eq("register"), anyString(), any(), eq(10));
    }

    @Test
    void sendCode_withinCooldown_rejectedAndNothingSent() {
        when(codeDao.latest(EMAIL, "register"))
                .thenReturn(Optional.of(new CodeRow(1L, "h", 0, false, false, 10)));

        assertThrows(TooManyRequestsException.class, () -> auth.sendRegisterCode(EMAIL, null));
        verify(codeDao, never()).insert(any(), any(), any(), any(), any(Integer.class));
        verify(mail, never()).send(any(), any(), any());
    }

    // ------------------------------------------------------------------ 注册

    @Test
    void register_success_returnsUsableToken() {
        UUID id = UUID.randomUUID();
        when(codeDao.latest(EMAIL, "register")).thenReturn(Optional.of(validCode(0)));
        when(codeDao.consume(1L)).thenReturn(true);
        when(userDao.insert(eq(EMAIL), anyString(), eq("小明"))).thenReturn(id);
        when(userDao.findById(id)).thenReturn(Optional.of(
                new UserRow(id, EMAIL, "hash", "小明", "USER", Instant.now())));

        AuthService.LoginOut out = auth.register(EMAIL, CODE, "password123", " 小明 ");

        assertEquals(Optional.of(id), jwt.parse(out.token()));
        assertEquals("USER", out.user().role());
    }

    /** 输错:attempts 必须 +1(这条写操作不能因为抛异常而丢失),且不能建用户。 */
    @Test
    void register_wrongCode_incrementsAttempts() {
        when(codeDao.latest(EMAIL, "register")).thenReturn(Optional.of(validCode(0)));

        assertThrows(IllegalArgumentException.class,
                () -> auth.register(EMAIL, "000000", "password123", null));
        verify(codeDao).incrementAttempts(1L);
        verify(codeDao, never()).consume(anyLong());
        verify(userDao, never()).insert(any(), any(), any());
    }

    /** 次数用尽后,即使码是对的也不能通过。 */
    @Test
    void register_attemptsExhausted_evenCorrectCodeRejected() {
        when(codeDao.latest(EMAIL, "register")).thenReturn(Optional.of(validCode(5)));

        assertThrows(IllegalArgumentException.class,
                () -> auth.register(EMAIL, CODE, "password123", null));
        verify(codeDao, never()).consume(anyLong());
        verify(userDao, never()).insert(any(), any(), any());
    }

    @Test
    void register_expiredOrConsumed_rejected() {
        CodeRow expired = new CodeRow(1L, AuthService.hash(EMAIL, CODE), 0, false, true, 900);
        CodeRow consumed = new CodeRow(1L, AuthService.hash(EMAIL, CODE), 0, true, false, 120);

        when(codeDao.latest(EMAIL, "register")).thenReturn(Optional.of(expired));
        assertThrows(IllegalArgumentException.class,
                () -> auth.register(EMAIL, CODE, "password123", null));

        when(codeDao.latest(EMAIL, "register")).thenReturn(Optional.of(consumed));
        assertThrows(IllegalArgumentException.class,
                () -> auth.register(EMAIL, CODE, "password123", null));

        verify(userDao, never()).insert(any(), any(), any());
    }

    /** 同一个码并发提交:consume 返回 false 的那个请求必须失败。 */
    @Test
    void register_consumeRace_loserRejected() {
        when(codeDao.latest(EMAIL, "register")).thenReturn(Optional.of(validCode(0)));
        when(codeDao.consume(1L)).thenReturn(false);

        assertThrows(IllegalArgumentException.class,
                () -> auth.register(EMAIL, CODE, "password123", null));
        verify(userDao, never()).insert(any(), any(), any());
    }

    @Test
    void register_duplicateEmail_friendlyError() {
        when(codeDao.latest(EMAIL, "register")).thenReturn(Optional.of(validCode(0)));
        when(codeDao.consume(1L)).thenReturn(true);
        when(userDao.insert(any(), any(), any())).thenThrow(new DuplicateKeyException("dup"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> auth.register(EMAIL, CODE, "password123", null));
        assertTrue(e.getMessage().contains("已注册"));
    }

    /** 密码不合规时连验证码都不碰,不白白消耗一次机会。 */
    @Test
    void register_badPassword_codeUntouched() {
        assertThrows(IllegalArgumentException.class, () -> auth.register(EMAIL, CODE, "short", null));
        // 25 个汉字 = 75 字节,超过 BCrypt 的 72 字节上限;按字符数算只有 25,会被漏过
        String longCn = "密".repeat(25);
        assertThrows(IllegalArgumentException.class, () -> auth.register(EMAIL, CODE, longCn, null));
        verify(codeDao, never()).latest(any(), any());
    }

    // ------------------------------------------------------------------ 登录

    @Test
    void login_successAndFailures() {
        UUID id = UUID.randomUUID();
        String hash = new BCryptPasswordEncoder().encode("password123");
        when(userDao.findByEmail(EMAIL)).thenReturn(Optional.of(
                new UserRow(id, EMAIL, hash, null, "USER", Instant.now())));

        assertEquals(Optional.of(id), jwt.parse(auth.login("FOO@x.com", "password123").token()));
        assertThrows(AuthException.class, () -> auth.login(EMAIL, "wrong-password"));

        when(userDao.findByEmail("nobody@x.com")).thenReturn(Optional.empty());
        AuthException e = assertThrows(AuthException.class, () -> auth.login("nobody@x.com", "password123"));
        assertEquals("邮箱或密码错误", e.getMessage()); // 与密码错误同一句提示
    }
}
