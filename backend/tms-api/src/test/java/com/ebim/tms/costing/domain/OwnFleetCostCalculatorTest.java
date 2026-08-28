package com.ebim.tms.costing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.reference.TransportCostNature;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic and, more importantly, the refusals (V48, JOB 22).
 *
 * <p>Runs with no Spring context and no database, which is the point of the calculator being a pure
 * function: every economic rule below is provable on a laptop with Docker stopped.
 */
class OwnFleetCostCalculatorTest {

    private static OwnFleetRates full() {
        return new OwnFleetRates("PEN",
                new BigDecimal("100.00"),   // fixed trip
                new BigDecimal("0.6500"),   // fuel per km
                new BigDecimal("18.0000"),  // driver per hour
                new BigDecimal("12.0000"),  // vehicle per hour
                new BigDecimal("0.0800"),   // maintenance per km
                new BigDecimal("0.2000"),   // depreciation per km
                null);
    }

    private static OwnFleetCostInputs measured(String km, long minutes) {
        return new OwnFleetCostInputs(new BigDecimal(km), OwnFleetQuantitySource.MEASURED_ROUTE,
                minutes, OwnFleetQuantitySource.TRIP_EXECUTION_WINDOW);
    }

    @Nested
    @DisplayName("a complete profile over a measured trip")
    class Complete {

        @Test
        @DisplayName("adds up exactly, to the cent")
        void addsUp() {
            // 120 km, 3.5 h - the worked example in docs/domain/OWN_FLEET_COSTING_V1.md.
            OwnFleetCostEstimate estimate = OwnFleetCostCalculator.calculate(full(), measured("120", 210));

            assertThat(estimate.isComplete()).isTrue();
            // 100.00 + 78.00 + 63.00 + 42.00 + 9.60 + 24.00
            assertThat(estimate.comparableTotal()).isEqualByComparingTo("316.60");
            assertThat(estimate.currency()).isEqualTo("PEN");
            assertThat(estimate.nature()).isEqualTo(TransportCostNature.OWN_FLEET_INTERNAL_COST);
        }

        @Test
        @DisplayName("keeps quantity, rate and provenance on every line")
        void keepsProvenance() {
            OwnFleetCostEstimate estimate = OwnFleetCostCalculator.calculate(full(), measured("120", 210));

            OwnFleetCostLine fuel = line(estimate, OwnFleetComponent.FUEL_PER_KM);
            assertThat(fuel.rate()).isEqualByComparingTo("0.6500");
            assertThat(fuel.quantity()).isEqualByComparingTo("120");
            assertThat(fuel.amount()).isEqualByComparingTo("78.00");
            assertThat(fuel.quantitySource()).isEqualTo(OwnFleetQuantitySource.MEASURED_ROUTE);

            OwnFleetCostLine driver = line(estimate, OwnFleetComponent.DRIVER_PER_HOUR);
            assertThat(driver.quantity()).isEqualByComparingTo("3.5000");
            assertThat(driver.amount()).isEqualByComparingTo("63.00");
        }

        @Test
        @DisplayName("charges nothing for a component the profile has no rate for")
        void silentOnUnconfigured() {
            OwnFleetCostEstimate estimate = OwnFleetCostCalculator.calculate(full(), measured("120", 210));

            // Toll is null on this profile: not charged, not missing, and not a line at all.
            assertThat(estimate.lines()).noneMatch(l -> l.component() == OwnFleetComponent.TOLL);
            assertThat(estimate.isComplete()).isTrue();
        }
    }

    @Nested
    @DisplayName("what is unknown never becomes zero")
    class UnknownIsNotZero {

        @Test
        @DisplayName("no distance withholds the total even though five components could be calculated")
        void noDistanceNoTotal() {
            OwnFleetCostInputs noDistance = new OwnFleetCostInputs(null, null,
                    210L, OwnFleetQuantitySource.TRIP_EXECUTION_WINDOW);

            OwnFleetCostEstimate estimate = OwnFleetCostCalculator.calculate(full(), noDistance);

            assertThat(estimate.comparableTotal()).isNull();
            assertThat(estimate.isComplete()).isFalse();
            assertThat(estimate.blockingReasons()).containsExactly(OwnFleetCostReason.DISTANCE_UNKNOWN);
            // The diagnostic subtotal is still there and is deliberately NOT the total.
            assertThat(estimate.partialSubtotal()).isEqualByComparingTo("205.00");
        }

        @Test
        @DisplayName("an un-costable trip must never come out cheaper than a costed one")
        void unknownNeverWins() {
            OwnFleetCostEstimate costable = OwnFleetCostCalculator.calculate(full(), measured("120", 210));
            OwnFleetCostEstimate notCostable =
                    OwnFleetCostCalculator.calculate(full(), OwnFleetCostInputs.NOTHING_MEASURED);

            // The whole safety property of this job in one assertion: the trip nobody could measure
            // has NO total, so nothing can rank it below the trip that could be measured. Had the
            // calculator summed what it had, this trip would have scored 100.00 against 316.60 and
            // won every comparison by being unmeasurable.
            assertThat(costable.comparableTotal()).isEqualByComparingTo("316.60");
            assertThat(notCostable.comparableTotal()).isNull();
            assertThat(notCostable.partialSubtotal()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("a zero rate is charged; a null rate is not charged at all")
        void zeroIsNotNull() {
            OwnFleetRates zeroDepreciation = new OwnFleetRates("PEN", new BigDecimal("100.00"),
                    null, null, null, null, BigDecimal.ZERO, null);

            OwnFleetCostEstimate withDistance = OwnFleetCostCalculator.calculate(
                    zeroDepreciation, measured("120", 210));
            OwnFleetCostEstimate withoutDistance = OwnFleetCostCalculator.calculate(
                    zeroDepreciation, OwnFleetCostInputs.NOTHING_MEASURED);

            // Charged at zero: it appears, it adds nothing, and the estimate is complete.
            assertThat(withDistance.isComplete()).isTrue();
            assertThat(withDistance.comparableTotal()).isEqualByComparingTo("100.00");
            // ...but it is a configured rate, so it still demands its kilometres.
            assertThat(withoutDistance.isComplete()).isFalse();
            assertThat(withoutDistance.blockingReasons()).containsExactly(OwnFleetCostReason.DISTANCE_UNKNOWN);
        }

        @Test
        @DisplayName("a non-calculable line keeps the rate that would have applied")
        void keepsTheRate() {
            OwnFleetCostEstimate estimate = OwnFleetCostCalculator.calculate(
                    full(), OwnFleetCostInputs.NOTHING_MEASURED);

            OwnFleetCostLine fuel = line(estimate, OwnFleetComponent.FUEL_PER_KM);
            assertThat(fuel.isApplied()).isFalse();
            assertThat(fuel.rate()).isEqualByComparingTo("0.6500");
            assertThat(fuel.quantity()).isNull();
            assertThat(fuel.amount()).isEqualByComparingTo("0.00");
            assertThat(fuel.reason()).isEqualTo(OwnFleetCostReason.DISTANCE_UNKNOWN);
        }
    }

    @Nested
    @DisplayName("a fixed-only profile")
    class FixedOnly {

        @Test
        @DisplayName("is complete with nothing measured at all")
        void completeWithNoMeasurements() {
            OwnFleetRates flat = new OwnFleetRates("PEN", new BigDecimal("250.00"),
                    null, null, null, null, null, new BigDecimal("30.00"));

            OwnFleetCostEstimate estimate = OwnFleetCostCalculator.calculate(
                    flat, OwnFleetCostInputs.NOTHING_MEASURED);

            // A company whose only modelled cost is a flat trip charge plus an expected toll has
            // said something real, and no measurement can be missing from it.
            assertThat(estimate.isComplete()).isTrue();
            assertThat(estimate.comparableTotal()).isEqualByComparingTo("280.00");
        }
    }

    @Nested
    @DisplayName("time and distance are charged over the resource's duty, not the trip alone")
    class Reposition {

        @Test
        @DisplayName("the reposition is inside the quantity and its provenance says so")
        void repositionCounted() {
            // Trip B runs 3h and needed a 40m empty run to reach its origin: 3h40m of duty.
            OwnFleetCostInputs withReposition = new OwnFleetCostInputs(
                    new BigDecimal("150"), OwnFleetQuantitySource.MEASURED_ROUTE,
                    220L, OwnFleetQuantitySource.RESOURCE_DUTY_WINDOW);

            OwnFleetCostEstimate estimate = OwnFleetCostCalculator.calculate(full(), withReposition);

            OwnFleetCostLine driver = line(estimate, OwnFleetComponent.DRIVER_PER_HOUR);
            assertThat(driver.quantity()).isEqualByComparingTo("3.6667");
            assertThat(driver.quantitySource()).isEqualTo(OwnFleetQuantitySource.RESOURCE_DUTY_WINDOW);
            // Distance is the trip's own route and NOT the empty run - V47 froze the reposition's
            // minutes, not its kilometres, so V1 charges the driver and the vehicle for
            // repositioning and does not charge fuel for it. A known understatement, recorded.
            assertThat(line(estimate, OwnFleetComponent.FUEL_PER_KM).quantitySource())
                    .isEqualTo(OwnFleetQuantitySource.MEASURED_ROUTE);
        }
    }

    @Nested
    @DisplayName("money")
    class Money {

        @Test
        @DisplayName("rounds each line once and never accumulates a fraction of a cent")
        void rounding() {
            OwnFleetRates awkward = new OwnFleetRates("PEN", null,
                    new BigDecimal("0.3333"), null, null, new BigDecimal("0.3333"),
                    new BigDecimal("0.3333"), null);

            OwnFleetCostEstimate estimate = OwnFleetCostCalculator.calculate(awkward, measured("7", 60));

            // 0.3333 x 7 = 2.3331 -> 2.33, three times. The total is a sum of rounded lines, so it
            // equals what the screen shows added up rather than being 0.01 away from it.
            assertThat(estimate.comparableTotal()).isEqualByComparingTo("6.99");
            assertThat(estimate.lines()).allSatisfy(l -> assertThat(l.amount().scale()).isEqualTo(2));
        }
    }

    @Test
    @DisplayName("inputs refuse a quantity without its provenance")
    void provenanceIsMandatory() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new OwnFleetCostInputs(new BigDecimal("10"), null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provenance");
    }

    private static OwnFleetCostLine line(OwnFleetCostEstimate estimate, OwnFleetComponent component) {
        return estimate.lines().stream().filter(l -> l.component() == component).findFirst().orElseThrow();
    }
}
