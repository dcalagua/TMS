package com.ebim.tms.orders.api;

import com.ebim.tms.orders.application.TrackingLinkProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The brakes on the anonymous tracking endpoint (ADR-029 T1, T11): fixed windows per client address
 * (every request; and, tighter, every miss) and per token (views).
 *
 * <p><b>In memory, per instance, bounded.</b> Each window table holds at most {@link #MAX_KEYS} keys;
 * when one is full, expired windows are swept and, if it is still full, a <em>new</em> key is refused
 * rather than admitted - a flood from many addresses must not grow the heap, and with 256-bit tokens
 * refusing is the cheaper failure. A deployment with several instances multiplies the limits by their
 * number; the CDN or WAF in front of it is the outer layer (ADR-029, accepted risks).
 *
 * <p>The miss counter is what makes enumeration pointless on top of what the token's entropy already
 * makes impossible: after a few wrong tokens an address stops being answered at all for ten minutes.
 */
@Component
public class PublicTrackingRateLimiter {

    static final int MAX_KEYS = 20_000;

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    private final Windows requests;
    private final Windows misses;
    private final Windows views;
    private final int missLimit;

    public PublicTrackingRateLimiter(TrackingLinkProperties properties, Clock clock) {
        TrackingLinkProperties.RateLimit limits = properties.rateLimit();
        this.requests = new Windows(clock, MINUTE, limits.requestsPerMinute());
        this.misses = new Windows(clock, TEN_MINUTES, limits.missesPerTenMinutes());
        this.views = new Windows(clock, MINUTE, limits.viewsPerLinkPerMinute());
        this.missLimit = limits.missesPerTenMinutes();
    }

    /**
     * Admits one request from {@code client}, or says how many seconds to wait. An address that has used
     * up its misses is refused before the request is counted, so a blocked scanner cannot keep its own
     * window warm.
     *
     * @return 0 when admitted, else the seconds until the caller may try again
     */
    public long admit(String client, String tokenKey) {
        long blocked = misses.waitIfAtLeast(client, missLimit);
        if (blocked > 0) {
            return blocked;
        }
        long wait = requests.acquire(client);
        if (wait > 0) {
            return wait;
        }
        return views.acquire(tokenKey);
    }

    /** One token from {@code client} that resolved to nothing usable. */
    public void recordMiss(String client) {
        misses.acquire(client);
    }

    /** A fixed-window counter per key: {@code limit} events per {@code window}. */
    static final class Windows {

        private final Clock clock;
        private final long windowMillis;
        private final int limit;
        private final Map<String, Window> byKey = new ConcurrentHashMap<>();

        Windows(Clock clock, Duration window, int limit) {
            this.clock = clock;
            this.windowMillis = window.toMillis();
            this.limit = limit;
        }

        /** Counts one event; 0 if within the limit, else seconds until the window rolls. */
        long acquire(String key) {
            long now = clock.millis();
            if (!byKey.containsKey(key) && byKey.size() >= MAX_KEYS) {
                sweep(now);
                if (byKey.size() >= MAX_KEYS) {
                    return secondsUntil(now + windowMillis, now);
                }
            }
            Window window = byKey.compute(key, (k, current) ->
                    current == null || now >= current.start + windowMillis
                            ? new Window(now, 1)
                            : new Window(current.start, current.count + 1));
            return window.count <= limit ? 0 : secondsUntil(window.start + windowMillis, now);
        }

        /** Without counting: seconds to wait if this key's current window already holds {@code threshold}. */
        long waitIfAtLeast(String key, int threshold) {
            long now = clock.millis();
            Window window = byKey.get(key);
            if (window == null || now >= window.start + windowMillis || window.count < threshold) {
                return 0;
            }
            return secondsUntil(window.start + windowMillis, now);
        }

        private void sweep(long now) {
            byKey.entrySet().removeIf(entry -> now >= entry.getValue().start + windowMillis);
        }

        int size() {
            return byKey.size();
        }

        private static long secondsUntil(long end, long now) {
            return Math.max(1, (end - now + 999) / 1000);
        }
    }

    private record Window(long start, int count) {
    }
}
