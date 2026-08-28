package com.ebim.tms.routing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.reference.GeoPoint;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The distance arithmetic, provable with no database and no Spring context (migration V38).
 *
 * <p>The reference figures are real: Lima's centre, Callao's port and Arequipa are three places
 * this product's first operation actually drives between, and asserting against a tolerance around
 * a known great-circle distance is what would catch a swapped latitude/longitude or a degrees/
 * radians slip - both of which produce numbers that look plausible in isolation.
 */
class GeodesicDistanceTest {

    private static GeoPoint at(String latitude, String longitude) {
        return new GeoPoint(new BigDecimal(latitude), new BigDecimal(longitude));
    }

    private static final GeoPoint LIMA = at("-12.046374", "-77.042793");
    private static final GeoPoint CALLAO = at("-12.052780", "-77.132790");
    private static final GeoPoint AREQUIPA = at("-16.409047", "-71.537451");

    @Nested
    @DisplayName("great-circle distance")
    class GreatCircle {

        @Test
        @DisplayName("a point is exactly zero from itself, without a millimetre of rounding noise")
        void samePointIsZero() {
            assertThat(GeodesicDistance.greatCircleKm(LIMA, LIMA)).isEqualByComparingTo("0");
            assertThat(GeodesicDistance.estimatedRoadKm(LIMA, LIMA)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("an equal point written at a different scale is still zero")
        void equalAtDifferentScale() {
            GeoPoint sameLima = at("-12.0463740", "-77.0427930");

            assertThat(GeodesicDistance.greatCircleKm(LIMA, sameLima)).isEqualByComparingTo("0");
        }

        /** Lima centre to Callao port is about 9.8 km in a straight line. */
        @Test
        @DisplayName("a short urban hop matches the known figure")
        void shortHop() {
            BigDecimal km = GeodesicDistance.greatCircleKm(LIMA, CALLAO);

            assertThat(km.doubleValue()).isBetween(9.0, 10.5);
        }

        /** Lima to Arequipa is about 765 km in a straight line. */
        @Test
        @DisplayName("a long haul matches the known figure")
        void longHaul() {
            BigDecimal km = GeodesicDistance.greatCircleKm(LIMA, AREQUIPA);

            assertThat(km.doubleValue()).isBetween(750.0, 780.0);
        }

        @Test
        @DisplayName("distance is symmetric: the straight line does not care which way you drive")
        void symmetric() {
            assertThat(GeodesicDistance.greatCircleKm(LIMA, AREQUIPA))
                    .isEqualByComparingTo(GeodesicDistance.greatCircleKm(AREQUIPA, LIMA));
        }

        /**
         * The defect a two-BigDecimal signature invites and {@link GeoPoint} exists to prevent -
         * pinned here so that the guard is shown to matter rather than merely asserted to.
         */
        @Test
        @DisplayName("swapping latitude and longitude produces a wildly different answer")
        void swappedCoordinatesAreNotHarmless() {
            BigDecimal correct = GeodesicDistance.greatCircleKm(LIMA, AREQUIPA);
            BigDecimal swapped = GeodesicDistance.greatCircleKm(
                    at("-77.042793", "-12.046374"), at("-71.537451", "-16.409047"));

            assertThat(swapped).isNotEqualByComparingTo(correct);
        }

        @Test
        @DisplayName("the same two points give the same answer every time")
        void deterministic() {
            assertThat(GeodesicDistance.greatCircleKm(LIMA, AREQUIPA))
                    .isEqualByComparingTo(GeodesicDistance.greatCircleKm(LIMA, AREQUIPA));
        }
    }

    @Nested
    @DisplayName("the road estimate")
    class RoadEstimate {

        @Test
        @DisplayName("a road is the straight line stretched by the road factor")
        void appliesTheRoadFactor() {
            BigDecimal straight = GeodesicDistance.greatCircleKm(LIMA, AREQUIPA);
            BigDecimal road = GeodesicDistance.estimatedRoadKm(LIMA, AREQUIPA);

            assertThat(road.doubleValue())
                    .isCloseTo(straight.doubleValue() * 1.30, org.assertj.core.data.Offset.offset(0.01));
        }

        @Test
        @DisplayName("a road is never shorter than the straight line under it")
        void neverShorterThanStraightLine() {
            assertThat(GeodesicDistance.estimatedRoadKm(LIMA, CALLAO))
                    .isGreaterThanOrEqualTo(GeodesicDistance.greatCircleKm(LIMA, CALLAO));
        }

        @Test
        @DisplayName("kilometres are reported to three decimals, the column's own scale")
        void threeDecimals() {
            assertThat(GeodesicDistance.estimatedRoadKm(LIMA, CALLAO).scale()).isEqualTo(3);
        }
    }
}
