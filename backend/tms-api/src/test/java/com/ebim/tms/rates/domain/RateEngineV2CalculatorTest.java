package com.ebim.tms.rates.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.reference.CostableTrip;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The V39 charges and, above all, the order they are applied in.
 *
 * <p><b>These components do not commute, and that is the whole point of this suite.</b> A fuel
 * surcharge taken after the tolls charges a percentage of a road authority's fee. A minimum applied
 * before the accessorials is a different agreement from one applied after. Each of those is a
 * plausible implementation that produces the wrong invoice, and the only thing standing between the
 * two is an assertion about arithmetic - so the arithmetic is asserted here, on numbers small
 * enough to check by hand.
 */
class RateEngineV2CalculatorTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 8, 21);

    /** A card with only the components a test names; everything else is deliberately absent. */
    private static RateCard card(RateComponents components) {
        return new RateCard(COMPANY, "RC-1", "Card", CARRIER, RateCardScope.CARRIER, null, null, null,
                "PEN", DATE, null, components, UUID.randomUUID());
    }

    private static RateComponents components(String base, String perKm, String perStop, String fuelPercent,
            String toll, String accessorial, String label, String minimum, String maximum) {
        return new RateComponents(money(base), rate(perKm), null, null, null, money(minimum),
                rate(perStop), rate(fuelPercent), null, money(toll), money(accessorial), label, money(maximum));
    }

    private static BigDecimal money(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static BigDecimal rate(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static CostableTrip trip() {
        return new CostableTrip(UUID.randomUUID(), COMPANY, "SH-1", DATE, CARRIER, null, null, null,
                BigDecimal.valueOf(1000), BigDecimal.valueOf(10), BigDecimal.valueOf(8), true, null, null);
    }

    private static CostInputs inputs(String distanceKm, Integer stops) {
        return CostInputs.of(trip(), money(distanceKm), CostQuantitySource.MEASURED_ROUTE, stops, null);
    }

    private static BigDecimal amountOf(CostEstimate estimate, RateComponent component) {
        return estimate.lines().stream()
                .filter(line -> line.component() == component)
                .map(CostLine::amount)
                .findFirst()
                .orElse(null);
    }

    // --- stop-off --------------------------------------------------------------------

    @Nested
    @DisplayName("the stop-off charge")
    class StopOff {

        /**
         * The first drop is already inside the base. A one-stop shipment paying a stop-off is the
         * simplest shipment in the book being overcharged, which is exactly the defect a schedule
         * written "per additional stop" is meant to prevent.
         */
        @Test
        @DisplayName("a single-stop shipment pays no stop-off at all")
        void oneStopIsFree() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(components("100.00", null, "25.0000", null, null, null, null, null, null)),
                    inputs(null, 1));

            assertThat(amountOf(estimate, RateComponent.STOP_OFF)).isEqualByComparingTo("0.00");
            assertThat(estimate.amount()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("four stops pay for three")
        void chargesDropsAfterTheFirst() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(components("100.00", null, "25.0000", null, null, null, null, null, null)),
                    inputs(null, 4));

            assertThat(amountOf(estimate, RateComponent.STOP_OFF)).isEqualByComparingTo("75.00");
            assertThat(estimate.amount()).isEqualByComparingTo("175.00");
        }

        @Test
        @DisplayName("a shipment with no stops recorded says so rather than charging nothing quietly")
        void unknownStops() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(components("100.00", null, "25.0000", null, null, null, null, null, null)),
                    inputs(null, null));

            CostLine line = estimate.lines().stream()
                    .filter(l -> l.component() == RateComponent.STOP_OFF).findFirst().orElseThrow();
            assertThat(line.status()).isEqualTo(CostComponentStatus.NOT_CALCULABLE);
            assertThat(line.reason()).isEqualTo(CostComponentReason.STOPS_UNKNOWN);
        }
    }

    // --- the fuel surcharge ----------------------------------------------------------

    @Nested
    @DisplayName("the fuel surcharge")
    class Fuel {

        /**
         * The single most important assertion in this file. 100 base + 3 stops x 25 = 175 linehaul;
         * 12% of that is 21.00. A 50.00 toll is added AFTER, and 12% of 225 would be 27.00 - so the
         * two implementations differ by six soles on a two-hundred-sol shipment, every time, in the
         * carrier's favour or the shipper's depending on which way it is wrong.
         */
        @Test
        @DisplayName("is a percentage of the linehaul, and never of the tolls that follow it")
        void appliesToTheLinehaulOnly() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(components("100.00", null, "25.0000", "12.0000", "50.00", null, null, null, null)),
                    inputs(null, 4));

            assertThat(amountOf(estimate, RateComponent.FUEL_SURCHARGE)).isEqualByComparingTo("21.00");
            assertThat(estimate.amount()).isEqualByComparingTo("246.00");
        }

        @Test
        @DisplayName("shows what it multiplied, so a controller can check it")
        void showsItsWorking() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(components("200.00", null, null, "10.0000", null, null, null, null, null)),
                    inputs(null, 1));

            CostLine line = estimate.lines().stream()
                    .filter(l -> l.component() == RateComponent.FUEL_SURCHARGE).findFirst().orElseThrow();
            assertThat(line.rate()).isEqualByComparingTo("10.0000");
            assertThat(line.quantity()).isEqualByComparingTo("200.00");
            assertThat(line.unit()).isEqualTo(CostUnit.PERCENT);
            assertThat(line.quantitySource()).isEqualTo(CostQuantitySource.LINEHAUL_SUBTOTAL);
            assertThat(line.amount()).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("a card that says nothing about fuel gets no fuel line at all")
        void absentMeansAbsent() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(components("200.00", null, null, null, null, null, null, null, null)),
                    inputs(null, 1));

            assertThat(estimate.lines()).noneMatch(line -> line.component() == RateComponent.FUEL_SURCHARGE);
        }
    }

    // --- accessorials ----------------------------------------------------------------

    @Nested
    @DisplayName("the accessorials")
    class Accessorials {

        @Test
        @DisplayName("a toll and a named extra are flat and are added after the surcharge")
        void flatCharges() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(components("100.00", null, null, null, "40.00", "15.00", "Escolta", null, null)),
                    inputs(null, 1));

            assertThat(amountOf(estimate, RateComponent.TOLL)).isEqualByComparingTo("40.00");
            assertThat(amountOf(estimate, RateComponent.OTHER_ACCESSORIAL)).isEqualByComparingTo("15.00");
            assertThat(estimate.amount()).isEqualByComparingTo("155.00");
        }

        /**
         * Detention is measured on the road. An estimate that showed it as zero would be claiming
         * the truck will not wait; showing it as unknown is the truth.
         */
        @Test
        @DisplayName("waiting time on an estimate is never zero: it is not yet known")
        void waitingIsNotKnownAtEstimateTime() {
            RateComponents withWaiting = new RateComponents(money("100.00"), null, null, null, null, null,
                    null, null, rate("30.0000"), null, null, null, null);

            CostEstimate estimate = TripCostCalculator.calculate(card(withWaiting), inputs(null, 1));

            CostLine line = estimate.lines().stream()
                    .filter(l -> l.component() == RateComponent.WAITING_TIME).findFirst().orElseThrow();
            assertThat(line.status()).isEqualTo(CostComponentStatus.NOT_CALCULABLE);
            assertThat(line.reason()).isEqualTo(CostComponentReason.WAITING_NOT_RECORDED);
            assertThat(estimate.amount()).isEqualByComparingTo("100.00");
        }
    }

    // --- the floor and the ceiling ---------------------------------------------------

    @Nested
    @DisplayName("the limits")
    class Limits {

        @Test
        @DisplayName("the ceiling caps the total and appears as a negative line")
        void maximumCaps() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(components("500.00", null, null, null, "100.00", null, null, null, "400.00")),
                    inputs(null, 1));

            assertThat(estimate.amount()).isEqualByComparingTo("400.00");
            // Negative on purpose: a ceiling rendered as a positive number would read as one more
            // charge on the very breakdown somebody is checking.
            assertThat(amountOf(estimate, RateComponent.MAXIMUM_ADJUSTMENT)).isEqualByComparingTo("-200.00");
        }

        @Test
        @DisplayName("the limits are applied after every accessorial, not before")
        void limitsComeLast() {
            // 100 base + 100 toll = 200, capped at 150. Applying the cap to the linehaul alone
            // would have left 100 + 100 = 200 and no cap at all.
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(components("100.00", null, null, null, "100.00", null, null, null, "150.00")),
                    inputs(null, 1));

            assertThat(estimate.amount()).isEqualByComparingTo("150.00");
        }

        @Test
        @DisplayName("a total between the two limits is adjusted by neither")
        void withinBothLimits() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(components("300.00", null, null, null, null, null, null, "100.00", "500.00")),
                    inputs(null, 1));

            assertThat(estimate.amount()).isEqualByComparingTo("300.00");
            assertThat(estimate.lines()).noneMatch(RateEngineV2CalculatorTest::isAdjustment);
        }

        @Test
        @DisplayName("the floor still works, and a floor and a ceiling never both fire")
        void minimumStillApplies() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(components("50.00", null, null, null, null, null, null, "120.00", "500.00")),
                    inputs(null, 1));

            assertThat(estimate.amount()).isEqualByComparingTo("120.00");
            assertThat(amountOf(estimate, RateComponent.MINIMUM_ADJUSTMENT)).isEqualByComparingTo("70.00");
            assertThat(amountOf(estimate, RateComponent.MAXIMUM_ADJUSTMENT)).isNull();
        }
    }

    // --- provenance ------------------------------------------------------------------

    @Nested
    @DisplayName("where the kilometres came from")
    class Provenance {

        @Test
        @DisplayName("a measured run is traced to the shipment, not to a master corridor")
        void measured() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(components(null, "2.5000", null, null, null, null, null, null, null)),
                    inputs("40.000", 1));

            CostLine line = estimate.lines().stream()
                    .filter(l -> l.component() == RateComponent.DISTANCE).findFirst().orElseThrow();
            assertThat(line.quantitySource()).isEqualTo(CostQuantitySource.MEASURED_ROUTE);
            assertThat(line.amount()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("a fallback to the master corridor says so")
        void referenceFallback() {
            CostInputs fromRoute = CostInputs.of(trip(), money("40.000"),
                    CostQuantitySource.ROUTE_REFERENCE, 1, null);

            CostEstimate estimate = TripCostCalculator.calculate(
                    card(components(null, "2.5000", null, null, null, null, null, null, null)), fromRoute);

            assertThat(estimate.lines().stream()
                    .filter(l -> l.component() == RateComponent.DISTANCE).findFirst().orElseThrow()
                    .quantitySource()).isEqualTo(CostQuantitySource.ROUTE_REFERENCE);
        }
    }

    // --- the whole breakdown ---------------------------------------------------------

    @Test
    @DisplayName("a full agreement prices in the documented order, and the lines sum to the total")
    void theWholeBreakdown() {
        RateComponents full = new RateComponents(
                money("200.00"), rate("1.5000"), null, null, rate("3.0000"), money("100.00"),
                rate("20.0000"), rate("10.0000"), null, money("35.00"), money("15.00"), "Escolta", money("2000.00"));

        CostEstimate estimate = TripCostCalculator.calculate(card(full), inputs("100.000", 3));

        // 200 base + 150 distance (100 x 1.5) + 24 pallets (8 x 3) + 40 stop-off (2 x 20) = 414
        // linehaul; 10% = 41.40; + 35 toll + 15 escolta = 505.40. Between the floor and the ceiling.
        assertThat(amountOf(estimate, RateComponent.BASE)).isEqualByComparingTo("200.00");
        assertThat(amountOf(estimate, RateComponent.DISTANCE)).isEqualByComparingTo("150.00");
        assertThat(amountOf(estimate, RateComponent.PALLETS)).isEqualByComparingTo("24.00");
        assertThat(amountOf(estimate, RateComponent.STOP_OFF)).isEqualByComparingTo("40.00");
        assertThat(amountOf(estimate, RateComponent.FUEL_SURCHARGE)).isEqualByComparingTo("41.40");
        assertThat(estimate.amount()).isEqualByComparingTo("505.40");

        // The breakdown is the total: every line, in order, adding up to the figure on the invoice.
        BigDecimal summed = estimate.lines().stream()
                .map(CostLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(summed).isEqualByComparingTo(estimate.amount());

        assertThat(estimate.lines().stream().map(CostLine::component)).containsExactly(
                RateComponent.BASE, RateComponent.DISTANCE, RateComponent.PALLETS, RateComponent.STOP_OFF,
                RateComponent.FUEL_SURCHARGE, RateComponent.TOLL, RateComponent.OTHER_ACCESSORIAL);
    }

    private static boolean isAdjustment(CostLine line) {
        return line.component().isAdjustment();
    }
}
