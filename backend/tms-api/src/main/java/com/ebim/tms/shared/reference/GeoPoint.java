package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A point on the earth, in the same WGS-84 degrees {@code tms.location.latitude/longitude} store
 * (migration V14).
 *
 * <p><b>Why a type and not two loose {@code BigDecimal}s.</b> Every routing call takes two of
 * these, and a pair of same-typed numbers is the classic argument-order defect: swapping a latitude
 * and a longitude produces a coordinate that is perfectly valid, silently wrong, and usually in the
 * sea. V14's own comment warns about the equivalent trap in SQL; this is the same guard in Java.
 *
 * <p>Validated on construction, because a routing answer computed from -400 degrees is not an
 * answer. Both values are required: {@code ck_location_coordinates_pair} already refuses half a
 * coordinate in the database, and a caller holding one of the two has nothing to route with -
 * {@link #of} returns {@code null} for that case so the caller can say "unknown" rather than guess.
 */
public record GeoPoint(BigDecimal latitude, BigDecimal longitude) {

    /** Mean earth radius in kilometres (IUGG). */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    public GeoPoint {
        if (latitude == null || longitude == null) {
            throw new IllegalArgumentException("a point needs both a latitude and a longitude");
        }
        if (latitude.compareTo(new BigDecimal("-90")) < 0 || latitude.compareTo(new BigDecimal("90")) > 0) {
            throw new IllegalArgumentException("latitude out of range: " + latitude);
        }
        if (longitude.compareTo(new BigDecimal("-180")) < 0 || longitude.compareTo(new BigDecimal("180")) > 0) {
            throw new IllegalArgumentException("longitude out of range: " + longitude);
        }
    }

    /**
     * The point, or {@code null} when either coordinate is missing.
     *
     * <p>Null rather than an exception because "this location has no coordinates" is an ordinary
     * state of master data, not a programming error - {@code tms.location} allows it, and a routing
     * caller's correct response is to report an unknown distance rather than to fail.
     */
    public static GeoPoint of(BigDecimal latitude, BigDecimal longitude) {
        return latitude == null || longitude == null ? null : new GeoPoint(latitude, longitude);
    }

    /**
     * Straight-line kilometres from this point to {@code other}, to three decimals (haversine on
     * the mean earth radius).
     *
     * <p>Here rather than in {@code routing} because two modules ask it: routing builds its road
     * estimate on it (ADR-010), and planning reports how far from a stop a carrier said "I
     * arrived" (V75). Neither may import the other, and both already share this type.
     *
     * <p>Returns exactly zero for two points that are the same, without going through the
     * trigonometry: the formula's own rounding can otherwise produce a few millimetres of distance
     * between a point and itself, and "this stop is 0.000003 km from where I am" is a number that
     * makes a reader distrust everything beside it.
     */
    public BigDecimal greatCircleKmTo(GeoPoint other) {
        if (sameAs(other)) {
            return BigDecimal.ZERO.setScale(3);
        }

        double lat1 = Math.toRadians(latitudeDegrees());
        double lat2 = Math.toRadians(other.latitudeDegrees());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(other.longitudeDegrees() - longitudeDegrees());

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        // atan2 rather than asin: asin loses precision for the short hops that make up most of a
        // distribution route, which is exactly where this is used most.
        double centralAngle = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return BigDecimal.valueOf(EARTH_RADIUS_KM * centralAngle).setScale(3, RoundingMode.HALF_UP);
    }

    /** Whether this is the same point as {@code other} once both are rounded to the cache's grid. */
    public boolean sameAs(GeoPoint other) {
        return other != null
                && latitude.compareTo(other.latitude) == 0
                && longitude.compareTo(other.longitude) == 0;
    }

    public double latitudeDegrees() {
        return latitude.doubleValue();
    }

    public double longitudeDegrees() {
        return longitude.doubleValue();
    }
}
