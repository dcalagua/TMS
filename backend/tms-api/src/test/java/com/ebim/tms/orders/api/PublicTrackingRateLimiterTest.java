package com.ebim.tms.orders.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.orders.application.TrackingLinkProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-029 T1/T11: the brakes on the anonymous endpoint, with a clock the test moves. */
class PublicTrackingRateLimiterTest {

    private final MutableClock clock = new MutableClock();

    private PublicTrackingRateLimiter limiter(int perMinute, int misses, int views) {
        return new PublicTrackingRateLimiter(new TrackingLinkProperties(null, null, null,
                new TrackingLinkProperties.RateLimit(perMinute, misses, views)), clock);
    }

    @Test
    @DisplayName("an address gets its quota per minute, then a Retry-After, then a fresh window")
    void perAddress() {
        PublicTrackingRateLimiter limiter = limiter(3, 100, 100);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.admit("10.0.0.1", "t" + i)).isZero();
        }
        long wait = limiter.admit("10.0.0.1", "t9");
        assertThat(wait).isBetween(1L, 60L);
        assertThat(limiter.admit("10.0.0.2", "t9")).as("another address is not affected").isZero();

        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.admit("10.0.0.1", "t10")).isZero();
    }

    @Test
    @DisplayName("after a few wrong tokens an address is refused for ten minutes, even with a good one")
    void missesBlockTheAddress() {
        PublicTrackingRateLimiter limiter = limiter(100, 3, 100);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.admit("10.0.0.3", "guess" + i)).isZero();
            limiter.recordMiss("10.0.0.3");
        }
        long wait = limiter.admit("10.0.0.3", "a-real-token");
        assertThat(wait).isBetween(500L, 600L);

        clock.advance(Duration.ofMinutes(9));
        assertThat(limiter.admit("10.0.0.3", "a-real-token")).isPositive();
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertThat(limiter.admit("10.0.0.3", "a-real-token")).isZero();
    }

    @Test
    @DisplayName("one link has its own ceiling across every address that opens it")
    void perLink() {
        PublicTrackingRateLimiter limiter = limiter(100, 100, 2);
        assertThat(limiter.admit("10.0.1.1", "same")).isZero();
        assertThat(limiter.admit("10.0.1.2", "same")).isZero();
        assertThat(limiter.admit("10.0.1.3", "same")).isPositive();
        assertThat(limiter.admit("10.0.1.3", "other")).isZero();
    }

    @Test
    @DisplayName("the window table never grows past its bound: a flood of new addresses is refused, not stored")
    void bounded() {
        PublicTrackingRateLimiter.Windows windows =
                new PublicTrackingRateLimiter.Windows(clock, Duration.ofMinutes(1), 5);
        for (int i = 0; i < PublicTrackingRateLimiter.MAX_KEYS; i++) {
            assertThat(windows.acquire("k" + i)).isZero();
        }
        assertThat(windows.acquire("one-too-many")).isPositive();
        assertThat(windows.size()).isEqualTo(PublicTrackingRateLimiter.MAX_KEYS);

        clock.advance(Duration.ofMinutes(2));
        assertThat(windows.acquire("one-too-many")).as("expired windows are swept to make room").isZero();
        assertThat(windows.size()).isEqualTo(1);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-22T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
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
}
