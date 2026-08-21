package com.ebim.tms.tracking.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed settings for position tracking, under {@code tms.tracking}.
 *
 * <p>There is no {@code enabled} flag here, deliberately, and that is a departure from
 * {@code EvidenceStorageProperties} worth stating. Evidence storage defaults to off because it
 * needs somewhere to put bytes and a deployment that has not said where must not have one guessed.
 * Intake needs nothing: it is already gated by a scope that an administrator has to grant to a
 * named credential, which is a better switch than a property - it is per-partner, revocable in one
 * click, and audited. A second global flag would only add a way for the feature to be off while
 * the credential says it is on.
 *
 * @param minInterval the sampling floor: how far apart two <em>kept</em> positions of the same
 *     shipment and feed must be. Everything denser is accepted and dropped
 *     ({@code TrackingIntakeOutcome.THINNED}), never refused - a partner must not have to
 *     re-engineer their sender to talk to us. 60 seconds by default, which is the resolution at
 *     which "where is my delivery" is answerable and roughly 500 points per vehicle-day; see
 *     {@code docs/domain/TRACKING_V1.md}, "Volume and retention", for what other values cost
 * @param maxAge how far back a reported position may be. A day by default: a feed replaying its
 *     buffer after an outage is normal and welcome, a feed replaying last month is misconfigured,
 *     and storing what a retention sweep is about to remove is work nobody benefits from
 * @param maxFutureSkew how far ahead of TMS's clock a position may claim to be. Five minutes,
 *     which covers an unsynchronised device; past that the timestamp is wrong, and accepting it
 *     would park a vehicle at the top of every "latest position" query until real time caught up
 * @param trackLimit the most positions one read returns. The map draws a recent trail, not a
 *     forensic reconstruction, and an unbounded read of the largest table in the schema is how a
 *     screen becomes a way to hurt the database
 */
@ConfigurationProperties(prefix = "tms.tracking")
public record TrackingProperties(Duration minInterval, Duration maxAge, Duration maxFutureSkew, Integer trackLimit) {

    public static final Duration DEFAULT_MIN_INTERVAL = Duration.ofSeconds(60);
    public static final Duration DEFAULT_MAX_AGE = Duration.ofHours(24);
    public static final Duration DEFAULT_MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    public static final int DEFAULT_TRACK_LIMIT = 200;

    /**
     * A ceiling on the ceiling. A deployment may raise {@code trackLimit} for a long-haul fleet,
     * but not to a number that turns one screen into an unbounded scan - the same reasoning
     * {@code EvidenceStorageProperties.ABSOLUTE_MAX_FILE_SIZE} applies to its own setting.
     */
    public static final int MAX_TRACK_LIMIT = 2000;

    public TrackingProperties {
        minInterval = atLeastZero(minInterval, DEFAULT_MIN_INTERVAL);
        maxAge = positive(maxAge, DEFAULT_MAX_AGE);
        maxFutureSkew = atLeastZero(maxFutureSkew, DEFAULT_MAX_FUTURE_SKEW);
        trackLimit = clampTrackLimit(trackLimit);
    }

    /**
     * Zero is a legal value and means "keep everything this feed sends". Negative is not a
     * configuration, it is a typo, and reading it as "keep everything" would silently multiply a
     * deployment's row count by sixty.
     */
    private static Duration atLeastZero(Duration configured, Duration fallback) {
        return configured == null || configured.isNegative() ? fallback : configured;
    }

    private static Duration positive(Duration configured, Duration fallback) {
        return configured == null || configured.isNegative() || configured.isZero() ? fallback : configured;
    }

    private static int clampTrackLimit(Integer configured) {
        if (configured == null || configured <= 0) {
            return DEFAULT_TRACK_LIMIT;
        }
        return Math.min(configured, MAX_TRACK_LIMIT);
    }
}
