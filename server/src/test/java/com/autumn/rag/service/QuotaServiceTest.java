package com.autumn.rag.service;

import com.autumn.rag.common.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 每日额度:游客合用一个、登录用户各算各的、跨天归零、0 = 不限。 */
class QuotaServiceTest {

    /** 能往前拨的时钟,测跨天归零 */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-29T10:00:00Z");

        void plus(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final TestClock clock = new TestClock();

    @Test
    void 游客合用一个额度_用完后登录用户不受影响() {
        QuotaService quota = new QuotaService(2, 5, clock);
        quota.consume(null);
        quota.consume(null);

        // 不管换没换 IP,游客都在同一个池子里
        assertThrows(TooManyRequestsException.class, () -> quota.consume(null));
        assertDoesNotThrow(() -> quota.consume(UUID.randomUUID()));
    }

    @Test
    void 登录用户各算各的() {
        QuotaService quota = new QuotaService(0, 2, clock);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        quota.consume(alice);
        quota.consume(alice);

        assertThrows(TooManyRequestsException.class, () -> quota.consume(alice));
        assertDoesNotThrow(() -> quota.consume(bob));
    }

    @Test
    void 第二天归零() {
        QuotaService quota = new QuotaService(1, 1, clock);
        UUID alice = UUID.randomUUID();
        quota.consume(null);
        quota.consume(alice);
        assertThrows(TooManyRequestsException.class, () -> quota.consume(null));
        assertThrows(TooManyRequestsException.class, () -> quota.consume(alice));

        clock.plus(Duration.ofDays(1));

        assertDoesNotThrow(() -> quota.consume(null));
        assertDoesNotThrow(() -> quota.consume(alice));
    }

    @Test
    void 配成0表示不限() {
        QuotaService quota = new QuotaService(0, 0, clock);
        UUID alice = UUID.randomUUID();
        for (int i = 0; i < 1000; i++) {
            quota.consume(null);
            quota.consume(alice);
        }
    }
}
