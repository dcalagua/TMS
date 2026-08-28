package com.ebim.tms.routing.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How routing behaves, tunable per deployment (migration V38). Mirrors {@code TrackingProperties}'
 * shape: a record whose compact constructor normalises anything absent or nonsensical to a working
 * default, so a half-written {@code application.yml} degrades to sane behaviour instead of failing
 * to start.
 *
 * @param cacheTtl        how long a computed estimate stays usable. A road does not move; what
 *                        changes is traffic and roadworks, so this is a freshness policy rather
 *                        than a correctness one. Thirty days by default - long enough that a
 *                        night's planning costs almost no provider calls, short enough that a
 *                        rebuilt road is picked up within a billing cycle
 * @param averageSpeedKph the speed the local estimate assumes when nothing better is known. One
 *                        figure and not a curve: a curve would imply knowledge of road classes
 *                        this estimate does not have
 * @param urbanSpeedKph   the speed assumed for short hops, where stop-start driving dominates.
 *                        Two bands rather than one because the difference between a 3 km city
 *                        delivery and a 300 km line-haul is not a rounding error - assuming one
 *                        speed for both makes every urban round trip look an hour shorter than it is
 * @param urbanThresholdKm below this distance the urban speed applies
 * @param providerTimeout  how long a routing provider gets before the local estimate is used
 *                         instead. Applied by {@code RoutingService}, not left to each adapter
 * @param matrixLimit      the largest matrix a single call may ask for, so one request cannot ask
 *                         the cache for a million rows
 */
@ConfigurationProperties(prefix = "tms.routing")
public record RoutingProperties(
        Duration cacheTtl,
        Integer averageSpeedKph,
        Integer urbanSpeedKph,
        Integer urbanThresholdKm,
        Duration providerTimeout,
        Integer matrixLimit) {

    public static final Duration DEFAULT_CACHE_TTL = Duration.ofDays(30);
    public static final int DEFAULT_AVERAGE_SPEED_KPH = 60;
    public static final int DEFAULT_URBAN_SPEED_KPH = 25;
    public static final int DEFAULT_URBAN_THRESHOLD_KM = 15;
    public static final Duration DEFAULT_PROVIDER_TIMEOUT = Duration.ofSeconds(5);
    public static final int DEFAULT_MATRIX_LIMIT = 2500;

    /** 50 x 50. Beyond this a caller wants a solver, not a matrix, and should say so. */
    public static final int MAX_MATRIX_LIMIT = 10_000;

    public RoutingProperties {
        cacheTtl = positive(cacheTtl, DEFAULT_CACHE_TTL);
        averageSpeedKph = positive(averageSpeedKph, DEFAULT_AVERAGE_SPEED_KPH);
        urbanSpeedKph = positive(urbanSpeedKph, DEFAULT_URBAN_SPEED_KPH);
        urbanThresholdKm = atLeastZero(urbanThresholdKm, DEFAULT_URBAN_THRESHOLD_KM);
        providerTimeout = positive(providerTimeout, DEFAULT_PROVIDER_TIMEOUT);
        matrixLimit = clampMatrixLimit(matrixLimit);
    }

    private static Duration positive(Duration configured, Duration fallback) {
        return configured == null || configured.isNegative() || configured.isZero() ? fallback : configured;
    }

    private static int positive(Integer configured, int fallback) {
        return configured == null || configured <= 0 ? fallback : configured;
    }

    private static int atLeastZero(Integer configured, int fallback) {
        return configured == null || configured < 0 ? fallback : configured;
    }

    private static int clampMatrixLimit(Integer configured) {
        if (configured == null || configured <= 0) {
            return DEFAULT_MATRIX_LIMIT;
        }
        return Math.min(configured, MAX_MATRIX_LIMIT);
    }
}
