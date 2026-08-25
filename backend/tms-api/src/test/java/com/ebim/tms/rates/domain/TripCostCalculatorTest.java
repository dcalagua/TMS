package com.ebim.tms.rates.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebim.tms.shared.reference.CostableTrip;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a card plus a shipment come to, and - just as important - what happens when the shipment
 * cannot supply what the card charges by.
 *
 * <p>Every expected figure below is written out in full rather than recomputed from the inputs: a
 * test that repeats the implementation's arithmetic proves only that the arithmetic is
 * self-consistent.
 */
class TripCostCalculatorTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();
    private static final LocalDate PLANNING_DATE = LocalDate.of(2026, 8, 20);

    @Nested
    @DisplayName("components")
    class Components {

        @Test
        @DisplayName("a flat card charges its base amount and nothing else")
        void baseOnly() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(new RateComponents(new BigDecimal("120.00"), null, null, null, null, null)), inputs());

            assertThat(estimate.currency()).isEqualTo("PEN");
            assertThat(estimate.amount()).isEqualByComparingTo("120.00");
            assertThat(estimate.isComplete()).isTrue();
            assertThat(estimate.lines()).singleElement().satisfies(line -> {
                assertThat(line.component()).isEqualTo(RateComponent.BASE);
                assertThat(line.rate()).isNull();
                assertThat(line.quantity()).isNull();
            });
        }

        @Test
        @DisplayName("every component the card names becomes one line, in a fixed order")
        void allComponents() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(new RateComponents(new BigDecimal("120.00"), new BigDecimal("0.8500"),
                            new BigDecimal("0.0400"), new BigDecimal("2.5000"), new BigDecimal("6.0000"), null)),
                    inputs());

            assertThat(estimate.lines()).extracting(CostLine::component).containsExactly(
                    RateComponent.BASE, RateComponent.DISTANCE, RateComponent.WEIGHT, RateComponent.VOLUME,
                    RateComponent.PALLETS);
            // 120.00 + (40.5 x 0.85 = 34.425 -> 34.43) + (1000 x 0.04 = 40.00)
            //        + (12 x 2.5 = 30.00) + (10 x 6 = 60.00)
            assertThat(estimate.lines()).extracting(CostLine::amount)
                    .containsExactly(new BigDecimal("120.00"), new BigDecimal("34.43"), new BigDecimal("40.00"),
                            new BigDecimal("30.00"), new BigDecimal("60.00"));
            assertThat(estimate.amount()).isEqualByComparingTo("284.43");
        }

        @Test
        @DisplayName("a measured line records its unit and where the quantity came from")
        void lineProvenance() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(new RateComponents(null, new BigDecimal("0.8500"), null, null, null, null)), inputs());

            assertThat(estimate.lines()).singleElement().satisfies(line -> {
                assertThat(line.unit()).isEqualTo(CostUnit.KM);
                assertThat(line.quantitySource()).isEqualTo(CostQuantitySource.ROUTE_REFERENCE);
                assertThat(line.quantity()).isEqualByComparingTo("40.5");
                assertThat(line.rate()).isEqualByComparingTo("0.8500");
            });
        }

        @Test
        @DisplayName("a card that charges nothing cannot price anything")
        void emptyCard() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TripCostCalculator.calculate(
                            card(new RateComponents(null, null, null, null, null, new BigDecimal("50.00"))),
                            inputs()))
                    .withMessageContaining("charges nothing");
        }
    }

    @Nested
    @DisplayName("what cannot be calculated")
    class NotCalculable {

        @Test
        @DisplayName("a per-km rate with no distance is reported, not charged at zero")
        void noDistance() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(new RateComponents(new BigDecimal("120.00"), new BigDecimal("0.8500"), null, null, null,
                            null)),
                    new CostInputs(null, BigDecimal.valueOf(1000), BigDecimal.valueOf(12), BigDecimal.valueOf(10)));

            assertThat(estimate.amount()).isEqualByComparingTo("120.00");
            assertThat(estimate.isComplete()).isFalse();
            assertThat(estimate.notCalculableLines()).singleElement().satisfies(line -> {
                assertThat(line.component()).isEqualTo(RateComponent.DISTANCE);
                assertThat(line.status()).isEqualTo(CostComponentStatus.NOT_CALCULABLE);
                assertThat(line.reason()).isEqualTo(CostComponentReason.DISTANCE_UNKNOWN);
                assertThat(line.amount()).isEqualByComparingTo("0.00");
                assertThat(line.rate()).isNull();
                assertThat(line.quantity()).isNull();
            });
        }

        @Test
        @DisplayName("a declared total of zero is unknown, not free")
        void zeroIsUnknown() {
            CostableTrip nothingDeclared = new CostableTrip(UUID.randomUUID(), COMPANY, "SH-00000002", PLANNING_DATE,
                    CARRIER, null, UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, true);

            CostEstimate estimate = TripCostCalculator.calculate(
                    card(new RateComponents(new BigDecimal("120.00"), null, new BigDecimal("0.0400"),
                            new BigDecimal("2.5000"), new BigDecimal("6.0000"), null)),
                    CostInputs.of(nothingDeclared, new BigDecimal("40.5")));

            assertThat(estimate.amount())
                    .as("charging a truckload at nothing per kilo because a field was blank is the bug this prevents")
                    .isEqualByComparingTo("120.00");
            assertThat(estimate.notCalculableLines()).extracting(CostLine::reason).containsExactly(
                    CostComponentReason.WEIGHT_UNKNOWN, CostComponentReason.VOLUME_UNKNOWN,
                    CostComponentReason.PALLETS_UNKNOWN);
        }
    }

    @Nested
    @DisplayName("the minimum")
    class Minimum {

        @Test
        @DisplayName("raises the total to the floor and says so on its own line")
        void raisesToFloor() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(new RateComponents(new BigDecimal("50.00"), null, null, null, null,
                            new BigDecimal("180.00"))),
                    inputs());

            assertThat(estimate.amount()).isEqualByComparingTo("180.00");
            assertThat(estimate.lines()).extracting(CostLine::component)
                    .containsExactly(RateComponent.BASE, RateComponent.MINIMUM_ADJUSTMENT);
            assertThat(estimate.lines().get(1).amount()).isEqualByComparingTo("130.00");
        }

        @Test
        @DisplayName("adds nothing when the components already clear it")
        void notReached() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(new RateComponents(new BigDecimal("200.00"), null, null, null, null,
                            new BigDecimal("180.00"))),
                    inputs());

            assertThat(estimate.amount()).isEqualByComparingTo("200.00");
            assertThat(estimate.lines()).extracting(CostLine::component).containsExactly(RateComponent.BASE);
        }

        @Test
        @DisplayName("applies to what was calculable, and leaves the missing line visible")
        void appliesOverAnIncompleteEstimate() {
            CostEstimate estimate = TripCostCalculator.calculate(
                    card(new RateComponents(new BigDecimal("40.00"), new BigDecimal("0.8500"), null, null, null,
                            new BigDecimal("120.00"))),
                    new CostInputs(null, BigDecimal.valueOf(1000), BigDecimal.valueOf(12), BigDecimal.valueOf(10)));

            assertThat(estimate.amount()).isEqualByComparingTo("120.00");
            assertThat(estimate.isComplete()).isFalse();
            assertThat(estimate.notCalculableLines()).extracting(CostLine::component)
                    .containsExactly(RateComponent.DISTANCE);
        }
    }

    @Test
    @DisplayName("rounding is per line, half up, so the total is the sum of what is shown")
    void perLineRounding() {
        CostEstimate estimate = TripCostCalculator.calculate(
                card(new RateComponents(null, new BigDecimal("0.3333"), new BigDecimal("0.3333"), null, null, null)),
                new CostInputs(new BigDecimal("1"), new BigDecimal("1"), null, null));

        // 0.3333 each, rounded half up to 0.33 twice - never 0.6666 rounded once to 0.67.
        assertThat(estimate.lines()).extracting(CostLine::amount)
                .containsExactly(new BigDecimal("0.33"), new BigDecimal("0.33"));
        assertThat(estimate.amount()).isEqualByComparingTo("0.66");
    }

    @Test
    @DisplayName("the same card and the same shipment always produce the same figure")
    void deterministic() {
        RateCard card = card(new RateComponents(new BigDecimal("120.00"), new BigDecimal("0.8500"),
                new BigDecimal("0.0400"), null, null, new BigDecimal("100.00")));

        assertThat(TripCostCalculator.calculate(card, inputs()).amount())
                .isEqualByComparingTo(TripCostCalculator.calculate(card, inputs()).amount());
    }

    private static CostInputs inputs() {
        return new CostInputs(new BigDecimal("40.5"), BigDecimal.valueOf(1000), BigDecimal.valueOf(12),
                BigDecimal.valueOf(10));
    }

    private static RateCard card(RateComponents components) {
        return new RateCard(COMPANY, "CARD-1", "Card one", CARRIER, RateCardScope.CARRIER, null, null, null,
                "PEN", PLANNING_DATE.minusDays(30), null, components, UUID.randomUUID());
    }
}
