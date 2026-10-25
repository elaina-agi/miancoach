package com.autumn.rag.service;

import com.autumn.rag.config.AppProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * 登录 token 的签发与校验(HS256)。
 *
 * <p>
 * token 里只放用户 id(sub),不放角色/邮箱:鉴权时按 id 回查 app_user,
 * 改角色、删用户能在下一次请求立即生效;代价是每个请求多一次主键查询。
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final JwtParser parser;
    private final Duration ttl;

    /** 有两个构造器时 Spring 不知道用哪个,@Autowired 指定这一个。 */
    @Autowired
    public JwtService(AppProperties props) {
        this(props.auth().jwtSecret(), Duration.ofHours(props.auth().tokenTtlHours()));
    }

    /** 包内可见,单测直接 new,不必拼一整个 AppProperties。secret 为空时随机生成。 */
    JwtService(String base64Secret, Duration ttl) {
        this.key = buildKey(base64Secret);
        this.parser = Jwts.parser().verifyWith(key).build(); // 线程安全,建一次复用
        this.ttl = ttl;
    }

    private static SecretKey buildKey(String base64Secret) {
        if (base64Secret == null || base64Secret.isBlank()) {
            log.warn("未配置 BAGUWEN_JWT_SECRET,已随机生成签名密钥:重启后所有用户需要重新登录。生产环境必须配置");
            return Jwts.SIG.HS256.key().build();
        }
        // 不足 32 字节时 hmacShaKeyFor 抛 WeakKeyException,启动即失败——比带着弱密钥上线强
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret.trim()));
    }

    public String issue(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并取出用户 id。签名不对、过期、格式错、未签名一律返回 empty,不区分原因:
     * 对调用方来说结果都是 401,具体原因只打 debug 日志,不回给客户端。
     *
     * <p>
     * parseSignedClaims 只接受带签名的 token。alg=none 这种"无签名 token"会被直接拒绝,
     * 这是 JWT 最经典的漏洞,不能用 parse() 这种不强制签名的方法。
     */
    public Optional<UUID> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            String sub = parser.parseSignedClaims(token).getPayload().getSubject();
            return Optional.ofNullable(sub).map(UUID::fromString);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("token 无效: {}", e.getMessage());
            return Optional.empty();
        }
    }
}