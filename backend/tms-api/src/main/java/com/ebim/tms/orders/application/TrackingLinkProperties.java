package com.ebim.tms.orders.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The bounds of a customer tracking link, {@code tms.tracking-links.*} (migration V74, ADR-029). Every
 * value limits how long, how widely or how fast a bearer credential can be used, so each has a
 * conservative default here as well as in {@code application.yml}: a deployment that forgets the block
 * gets the documented behaviour, not an unbounded one.
 *
 * @param maxLifetime the hard cap from creation
 * @param afterClosure the soft end, after the order reaches a final state
 * @param maxActivePerOrder live links one order may have at a time
 */
@ConfigurationProperties(prefix = "tms.tracking-links")
public record TrackingLinkProperties(
        Duration maxLifetime,
        Duration afterClosure,
        Integer maxActivePerOrder,
        RateLimit rateLimit) {

    public static final Duration DEFAULT_MAX_LIFETIME = Duration.ofDays(30);
    public static final Duration DEFAULT_AFTER_CLOSURE = Duration.ofDays(7);
    public static final int DEFAULT_MAX_ACTIVE_PER_ORDER = 5;

    public TrackingLinkProperties {
        maxLifetime = positiveOr(maxLifetime, DEFAULT_MAX_LIFETIME);
        afterClosure = positiveOr(afterClosure, DEFAULT_AFTER_CLOSURE);
        maxActivePerOrder = maxActivePerOrder == null || maxActivePerOrder < 1
                ? DEFAULT_MAX_ACTIVE_PER_ORDER
                : maxActivePerOrder;
        rateLimit = rateLimit == null ? RateLimit.DEFAULTS : rateLimit;
    }

    /** What a deployment with no {@code tms.tracking-links} block gets. */
    public static TrackingLinkProperties defaults() {
        return new TrackingLinkProperties(null, null, null, null);
    }

    /**
     * @param requestsPerMinute per client address, every request to the public endpoint
     * @param missesPerTenMinutes per client address, tokens that resolve to nothing: the enumeration brake
     * @param viewsPerLinkPerMinute per token, so one link cannot be used to hammer the database
     */
    public record RateLimit(Integer requestsPerMinute, Integer missesPerTenMinutes, Integer viewsPerLinkPerMinute) {

        static final RateLimit DEFAULTS = new RateLimit(null, null, null);

        public RateLimit {
            requestsPerMinute = atLeastOne(requestsPerMinute, 30);
            missesPerTenMinutes = atLeastOne(missesPerTenMinutes, 10);
            viewsPerLinkPerMinute = atLeastOne(viewsPerLinkPerMinute, 60);
        }

        private static int atLeastOne(Integer value, int fallback) {
            return value == null || value < 1 ? fallback : value;
        }
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
