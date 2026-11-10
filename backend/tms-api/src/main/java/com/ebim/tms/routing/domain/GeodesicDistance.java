package com.ebim.tms.routing.domain;

import com.ebim.tms.shared.reference.GeoPoint;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Great-circle distance between two points, and the road estimate built on it (migration V38).
 *
 * <p><b>Pure, and that is the point.</b> No repository, no clock, no random number: the same two
 * points produce the same kilometres today and next year, which is what lets a planning proposal be
 * reproducible and a test assert an exact figure. The same property {@code PlanningEngine} was
 * written for.
 *
 * <p><b>Why haversine and not PostGIS.</b> {@code tms.location} already carries a generated
 * {@code geography(Point)} and {@code ST_Distance} would be marginally more accurate - it uses a
 * spheroid where this uses a sphere, a difference of about 0.3% at worst. That difference is noise
 * beside {@link #ROAD_FACTOR}, which is an estimate of how much longer a road is than a straight
 * line and is the dominant error by an order of magnitude. Paying a database round trip per leg for
 * 0.3% on top of a 30% approximation would buy nothing, and it would cost the property above.
 * PostGIS earns its place where the question is genuinely spatial - containment for geofences - not
 * here.
 */
public final class GeodesicDistance {

    /**
     * How much longer a road is than the straight line under it.
     *
     * <p>1.3 is the widely used planning figure for mixed road networks and it is deliberately a
     * single constant rather than a per-corridor table: a table would imply a precision this
     * estimate does not have. A real routing provider replaces the whole calculation rather than
     * tuning this number, which is why the adapter boundary exists.
     */
    public static final BigDecimal ROAD_FACTOR = new BigDecimal("1.30");

    private GeodesicDistance() {}

    /**
     * Straight-line kilometres between two points, to three decimals. The math lives on
     * {@link GeoPoint#greatCircleKmTo}, which planning shares.
     */
    public static BigDecimal greatCircleKm(GeoPoint origin, GeoPoint destination) {
        return origin.greatCircleKmTo(destination);
    }

    /** The straight line stretched by {@link #ROAD_FACTOR} - the estimated road distance. */
    public static BigDecimal estimatedRoadKm(GeoPoint origin, GeoPoint destination) {
        BigDecimal straight = greatCircleKm(origin, destination);
        if (straight.signum() == 0) {
            return straight;
        }
        return straight.multiply(ROAD_FACTOR).setScale(3, RoundingMode.HALF_UP);
    }
}
