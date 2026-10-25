package com.autumn.rag.service;

import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;

import com.autumn.rag.service.JwtService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    /** 32 字节全零,只用于测试。现场生成,免得手数 base64 字符数。 */
    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);

    private final JwtService jwt = new JwtService(SECRET, Duration.ofHours(1));

    @Test
    void issueThenParse() {
        UUID id = UUID.randomUUID();
        assertEquals(Optional.of(id), jwt.parse(jwt.issue(id)));
    }

    /** 把 A 的签名接到 B 的 payload 上:冒充别人最直接的办法。 */
    @Test
    void swappedPayloadRejected() {
        String[] a = jwt.issue(UUID.randomUUID()).split("\\.");
        String[] b = jwt.issue(UUID.randomUUID()).split("\\.");
        assertTrue(jwt.parse(a[0] + "." + b[1] + "." + a[2]).isEmpty());
    }

    @Test
    void otherKeyRejected() {
        JwtService other = new JwtService(null, Duration.ofHours(1)); // 随机密钥
        assertTrue(jwt.parse(other.issue(UUID.randomUUID())).isEmpty());
    }

    @Test
    void expiredRejected() {
        JwtService expired = new JwtService(SECRET, Duration.ofSeconds(-1)); // 签出来就已过期
        assertTrue(jwt.parse(expired.issue(UUID.randomUUID())).isEmpty());
    }

    /** alg=none:不签名的 token。必须拒绝。 */
    @Test
    void unsignedTokenRejected() {
        String header = b64url("{\"alg\":\"none\"}");
        String payload = b64url("{\"sub\":\"" + UUID.randomUUID() + "\"}");
        assertTrue(jwt.parse(header + "." + payload + ".").isEmpty());
    }

    @Test
    void garbageRejected() {
        assertTrue(jwt.parse(null).isEmpty());
        assertTrue(jwt.parse("").isEmpty());
        assertTrue(jwt.parse("abc.def.ghi").isEmpty());
    }

    @Test
    void weakSecretFailsFast() {
        String short16 = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(WeakKeyException.class, () -> new JwtService(short16, Duration.ofHours(1)));
    }

    private static String b64url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}