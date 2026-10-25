package com.autumn.rag.service;

import com.autumn.rag.common.AuthException;
import com.autumn.rag.common.TooManyRequestsException;
import com.autumn.rag.config.AppProperties;
import com.autumn.rag.dao.EmailCodeDao;
import com.autumn.rag.dao.EmailCodeDao.CodeRow;
import com.autumn.rag.dao.UserDao;
import com.autumn.rag.dao.UserDao.UserRow;
import com.autumn.rag.mail.MailProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * 注册 / 登录 / 邮箱验证码。
 *
 * <p>
 * <b>整个类刻意不加 @Transactional:</b>验证码输错时,attempts+1 必须落库,然后才抛异常。
 * 如果和后续步骤包在同一个事务里,异常一抛 attempts 也跟着回滚,"最多试 5 次"就形同虚设,
 * 攻击者可以无限次猜。所以每条 SQL 各自提交,靠执行顺序和原子 UPDATE 保证正确性。
 */
@Service
public class AuthService {

    static final String PURPOSE_REGISTER = "register";

    /** BCrypt 只取前 72 字节,超出部分被静默忽略。按字节算:中文一个字 3 字节,按字符数限制不住。 */
    static final int PASSWORD_MAX_BYTES = 72;
    static final int PASSWORD_MIN_CHARS = 8;

    public record UserView(UUID id, String email, String nickname, String role) {
        static UserView of(UserRow u) {
            return new UserView(u.id(), u.email(), u.nickname(), u.role());
        }
    }

    public record LoginOut(String token, UserView user) {
    }

    private final UserDao userDao;
    private final EmailCodeDao codeDao;
    private final MailProvider mail;
    private final JwtService jwt;
    private final AppProperties.Auth cfg;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    /** 邮箱不存在时也拿它跑一次 BCrypt,让"无此用户"和"密码错误"耗时一致(见 login)。 */
    private final String dummyHash = encoder.encode("dummy-password-for-timing");

    public AuthService(UserDao userDao, EmailCodeDao codeDao, MailProvider mail,
            JwtService jwt, AppProperties props) {
        this.userDao = userDao;
        this.codeDao = codeDao;
        this.mail = mail;
        this.jwt = jwt;
        this.cfg = props.auth();
    }

    // ------------------------------------------------------------------ 发码

    /**
     * 发注册验证码。邮箱已注册时,流程和返回完全一样,只是信的内容换成"你已有账号"——
     * 否则调用方看返回就能判断某个邮箱有没有注册(邮箱枚举)。
     *
     * <p>
     * 同理,已注册邮箱也照样写一条 email_code、照样受冷却限制,两条路径在外部看来没有区别。
     *
     * <p>
     * 已知局限:两个请求同时到达时可能都通过冷却检查、各发一封。限流那一步会收紧。
     */
    public void sendRegisterCode(String rawEmail, String ip) {
        String email = normalizeEmail(rawEmail);

        codeDao.latest(email, PURPOSE_REGISTER).ifPresent(c -> {
            long wait = cfg.codeCooldownSecs() - c.ageSecs();
            if (wait > 0) {
                throw new TooManyRequestsException("发送太频繁,请 " + wait + " 秒后再试");
            }
        });

        String code = String.format("%06d", random.nextInt(1_000_000));
        // 先落库再发信:发信失败时库里多一条用不上的码,无害;反过来则可能发出一个校验不了的码
        codeDao.insert(email, PURPOSE_REGISTER, hash(email, code), ip, cfg.codeTtlMinutes());

        if (userDao.existsByEmail(email)) {
            mail.send(email, "【面试陪练】注册提醒",
                    "该邮箱已经注册过账号,直接登录即可。\n如果不是你本人操作,忽略这封邮件。");
        } else {
            mail.send(email, "【面试陪练】注册验证码",
                    "你的验证码是 " + code + "," + cfg.codeTtlMinutes() + " 分钟内有效。\n"
                            + "如果不是你本人操作,忽略这封邮件。");
        }
    }

    // ------------------------------------------------------------------ 注册

    /** 注册成功直接返回 token,省掉一次登录。 */
    public LoginOut register(String rawEmail, String code, String password, String nickname) {
        String email = normalizeEmail(rawEmail);
        // 密码格式在消耗验证码之前校验:密码不合规时不应该白白用掉一个码
        validatePassword(password);

        // 先验码、后查重:如果先查重,这个接口就成了"邮箱是否已注册"的探测器。
        // 已注册邮箱拿到的是"注册提醒"而不是验证码,所以在这一步就会以"验证码错误"失败。
        verifyAndConsume(email, PURPOSE_REGISTER, code);

        UUID id;
        try {
            id = userDao.insert(email, encoder.encode(password), blankToNull(nickname));
        } catch (DuplicateKeyException e) {
            // 能走到这里,只可能是同一邮箱的两个注册请求并发,另一个先插入成功
            throw new IllegalArgumentException("该邮箱已注册,请直接登录");
        }
        UserRow u = userDao.findById(id).orElseThrow();
        return new LoginOut(jwt.issue(id), UserView.of(u));
    }

    // ------------------------------------------------------------------ 登录

    /**
     * 邮箱不存在和密码错误:同一句提示,而且耗时一致。
     * 如果查不到用户就直接返回,不跑 BCrypt(约几十毫秒),攻击者测响应时间就能区分出哪些邮箱已注册。
     */
    public LoginOut login(String rawEmail, String password) {
        UserRow u = userDao.findByEmail(normalizeEmail(rawEmail)).orElse(null);
        boolean ok = encoder.matches(password, u == null ? dummyHash : u.passwordHash());
        if (u == null || !ok) {
            throw new AuthException("邮箱或密码错误");
        }
        return new LoginOut(jwt.issue(u.id()), UserView.of(u));
    }

    public UserView me(UUID userId) {
        // token 有效但用户已被删:按未登录处理
        return userDao.findById(userId).map(UserView::of)
                .orElseThrow(() -> new AuthException("用户不存在"));
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 校验并消耗验证码。判断顺序有讲究:
     * 1. 次数检查在比对之前——否则第 6 次猜对了照样能通过
     * 2. 比对失败先把 attempts+1 落库,再抛异常(见类注释)
     * 3. 最后用原子 UPDATE 消耗:同一个码被并发提交两次,只有一个能拿到
     *
     * <p>
     * "没有码""已用过""已过期""码不对"返回同一句提示,不告诉调用方具体是哪种。
     */
    private void verifyAndConsume(String email, String purpose, String code) {
        CodeRow c = codeDao.latest(email, purpose)
                .filter(r -> !r.consumed() && !r.expired())
                .orElseThrow(() -> new IllegalArgumentException("验证码错误或已失效"));

        if (c.attempts() >= cfg.codeMaxAttempts()) {
            throw new IllegalArgumentException("验证码错误次数过多,请重新获取");
        }
        // MessageDigest.isEqual 是常量时间比较,不会因为前几位对上了就返回得慢一点
        boolean match = MessageDigest.isEqual(
                hash(email, code).getBytes(StandardCharsets.UTF_8),
                c.codeHash().getBytes(StandardCharsets.UTF_8));
        if (!match) {
            codeDao.incrementAttempts(c.id());
            throw new IllegalArgumentException("验证码错误或已失效");
        }
        if (!codeDao.consume(c.id())) {
            throw new IllegalArgumentException("验证码错误或已失效"); // 被并发请求抢先用掉了
        }
    }

    /**
     * SHA-256(email:code)。把 email 拼进去,同一个码在不同邮箱下的哈希不同。
     *
     * <p>
     * 说实话,6 位数字只有一百万种,库泄漏后离线穷举一秒就能跑完,哈希挡不住。
     * 它只防"有人顺手翻了下表就看到明文码"。真正的防线是 10 分钟过期 + 最多 5 次。
     */
    static String hash(String email, String code) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((email + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // 每个 JDK 都必须支持 SHA-256,不会发生
        }
    }

    /** Locale.ROOT:土耳其语环境下 "I".toLowerCase() 是 "ı"(无点),会把同一个邮箱变成两个。 */
    static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < PASSWORD_MIN_CHARS) {
            throw new IllegalArgumentException("密码至少 " + PASSWORD_MIN_CHARS + " 位");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            throw new IllegalArgumentException("密码过长");
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}