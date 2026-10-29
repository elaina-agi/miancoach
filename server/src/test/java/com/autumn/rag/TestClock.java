package com.autumn.rag;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 能往前拨的时钟,测跨天归零、熔断到点之类 */
public final class TestClock extends Clock {

    private Instant now = Instant.parse("2026-10-29T10:00:00Z");

    public void plus(Duration d) {
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
