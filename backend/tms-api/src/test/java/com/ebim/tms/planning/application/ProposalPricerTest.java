package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.application.PlanningProposal.ProposedTrip;
import com.ebim.tms.planning.application.ProposalPricing.UnpricedReason;
import com.ebim.tms.shared.reference.CarrierQuotationPort;
import com.ebim.tms.shared.reference.CarrierQuote;
import com.ebim.tms.shared.reference.CostableTrip;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.TravelEstimate;
import com.ebim.tms.shared.reference.RoutingSource;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pricing a plan nobody has committed to (JOB 11, closing open debt D1).
 *
 * <p>{@code PlanningKpis.totalCost} was null by design and documented why. The debt is closed by
 * asking {@link CarrierQuotationPort} - the same port, selector and calculator a tender and an
 * invoice use - so that a plan compared on price and the bill that follows it come from one set of
 * rules.
 *
 * <p><b>Most of what matters here is what the pricer refuses to do.</b> A number that is nearly
 * right is worse than none, because comparing two engines on cost is the entire purpose of the
 * figure, and the three nests below are the three ways a plausible wrong number could have been
 * produced.
 */
class ProposalPricerTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID ORIGIN = UUID.randomUUID();
    private static final UUID DEST_A = UUID.randomUUID();
    private static final UUID DEST_B = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 9, 7);

    private final CarrierQuotationPort quotationPort = mock(CarrierQuotationPort.class);
    private final ProposalPricer pricer = new ProposalPricer(quotationPort);

    // --- fixtures ---------------------------------------------------------------------

    private static VehicleCapacityReference vehicle(UUID id, UUID carrierId) {
        return new VehicleCapacityReference(id, "V-1", "PLT-1", carrierId, "Carrier", UUID.randomUUID(),
                "TYPE", BigDecimal.valueOf(10000), BigDecimal.valueOf(40), 20, true, "AVAILABLE");
    }

    private static PlannableOrder order(UUID id, UUID destination, double weightKg) {
        return new PlannableOrder(id, "TO-1", ORIGIN, destination, null, null, DATE, "NORMAL",
                null, null, BigDecimal.valueOf(weightKg), BigDecimal.ONE, BigDecimal.ONE, null, null, null);
    }

    private static TravelMatrix matrixOver(UUID... pairsFromTo) {
        TravelMatrix.Builder builder = new TravelMatrix.Builder();
        for (int index = 0; index < pairsFromTo.length; index += 2) {
            builder.add(pairsFromTo[index], pairsFromTo[index + 1], TravelEstimate.computed(
                    BigDecimal.valueOf(120).setScale(3), Duration.ofMinutes(90), "local",
                    RoutingSource.PROVIDER, OffsetDateTime.parse("2026-09-01T00:00:00Z")));
        }
        return builder.build();
    }

    private PlanningInput input(TravelMatrix travel, List<VehicleCapacityReference> vehicles,
            List<PlannableOrder> orders) {
        return new PlanningInput(ORIGIN, DATE, orders, vehicles, List.of(), travel, Map.of(),
                PlanningShift.DEFAULT);
    }

    private static CarrierQuote quote(UUID carrierId, String amount, String currency) {
        return new CarrierQuote(carrierId, new BigDecimal(amount), currency, UUID.randomUUID(), "RC-1", false);
    }

    // --- the happy path ---------------------------------------------------------------

    @Nested
    @DisplayName("when every trip has an agreement in one currency")
    class Priced {

        @Test
        @DisplayName("the total is the sum, and it says which currency it is in")
        void sumsTheTrips() {
            UUID carrier = UUID.randomUUID();
            UUID vehicleA = UUID.randomUUID();
            UUID vehicleB = UUID.randomUUID();
            UUID orderA = UUID.randomUUID();
            UUID orderB = UUID.randomUUID();
            when(quotationPort.quoteWithKnownDistance(eq(COMPANY), any(), eq(carrier), any()))
                    .thenReturn(Optional.of(quote(carrier, "840.00", "PEN")));

            ProposalPricing pricing = pricer.price(COMPANY,
                    input(matrixOver(ORIGIN, DEST_A, ORIGIN, DEST_B),
                            List.of(vehicle(vehicleA, carrier), vehicle(vehicleB, carrier)),
                            List.of(order(orderA, DEST_A, 1000), order(orderB, DEST_B, 2000))),
                    List.of(new ProposedTrip(vehicleA, null, List.of(orderA), List.of(DEST_A)),
                            new ProposedTrip(vehicleB, null, List.of(orderB), List.of(DEST_B))));

            assertThat(pricing.totalCost()).isEqualByComparingTo("1680.00");
            assertThat(pricing.currency()).isEqualTo("PEN");
            assertThat(pricing.reason()).isNull();
            assertThat(pricing.pricedTrips()).isEqualTo(2);
        }

        /**
         * The distance handed to the quote is the run the plan actually proposes, leg by leg, not a
         * figure looked up from a shipment that does not exist. A proposal has no row to look up.
         */
        @Test
        @DisplayName("the distance passed to the quote is the proposed run's own")
        void passesTheProposedDistance() {
            UUID carrier = UUID.randomUUID();
            UUID vehicleId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            when(quotationPort.quoteWithKnownDistance(any(), any(), any(), any()))
                    .thenReturn(Optional.of(quote(carrier, "100.00", "PEN")));

            pricer.price(COMPANY,
                    input(matrixOver(ORIGIN, DEST_A, DEST_A, DEST_B),
                            List.of(vehicle(vehicleId, carrier)),
                            List.of(order(orderId, DEST_A, 1000))),
                    List.of(new ProposedTrip(vehicleId, null, List.of(orderId), List.of(DEST_A, DEST_B))));

            ArgumentCaptor<BigDecimal> distance = ArgumentCaptor.forClass(BigDecimal.class);
            org.mockito.Mockito.verify(quotationPort)
                    .quoteWithKnownDistance(any(), any(), any(), distance.capture());
            // Two legs of 120km: origin to A, A to B.
            assertThat(distance.getValue()).isEqualByComparingTo("240.000");
        }

        /**
         * A lane is one origin to one destination. A multi-drop shipment is not on a lane, and
         * naming one of its stops as "the" destination would price it against an agreement that was
         * never about it - the rule {@code CostableTrip.soleDestinationId} states.
         */
        @Test
        @DisplayName("a multi-drop proposal is quoted with no sole destination")
        void multiDropHasNoLane() {
            UUID carrier = UUID.randomUUID();
            UUID vehicleId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            when(quotationPort.quoteWithKnownDistance(any(), any(), any(), any()))
                    .thenReturn(Optional.of(quote(carrier, "100.00", "PEN")));

            pricer.price(COMPANY,
                    input(matrixOver(ORIGIN, DEST_A, DEST_A, DEST_B),
                            List.of(vehicle(vehicleId, carrier)),
                            List.of(order(orderId, DEST_A, 1000))),
                    List.of(new ProposedTrip(vehicleId, null, List.of(orderId), List.of(DEST_A, DEST_B))));

            ArgumentCaptor<CostableTrip> costable = ArgumentCaptor.forClass(CostableTrip.class);
            org.mockito.Mockito.verify(quotationPort)
                    .quoteWithKnownDistance(any(), costable.capture(), any(), any());
            assertThat(costable.getValue().soleDestinationId()).isNull();
            assertThat(costable.getValue().stopCount()).isEqualTo(2);
            // No trip id: this shipment does not exist yet, which is the whole point.
            assertThat(costable.getValue().tripId()).isNull();
        }
    }

    // --- the refusals, which are the substance ----------------------------------------

    @Nested
    @DisplayName("what it refuses to report")
    class Refusals {

        /**
         * The rule the whole figure depends on. A sum that omits the trips nobody has an agreement
         * for makes the worse plan look cheaper, and comparing engines on cost is what the number
         * is for.
         */
        @Test
        @DisplayName("one unpriceable trip means no total at all, not a total over the rest")
        void noPartialTotals() {
            UUID carrier = UUID.randomUUID();
            UUID priced = UUID.randomUUID();
            UUID unpriced = UUID.randomUUID();
            UUID orderA = UUID.randomUUID();
            UUID orderB = UUID.randomUUID();
            UUID otherCarrier = UUID.randomUUID();
            when(quotationPort.quoteWithKnownDistance(any(), any(), eq(carrier), any()))
                    .thenReturn(Optional.of(quote(carrier, "840.00", "PEN")));
            when(quotationPort.quoteWithKnownDistance(any(), any(), eq(otherCarrier), any()))
                    .thenReturn(Optional.empty());

            ProposalPricing pricing = pricer.price(COMPANY,
                    input(matrixOver(ORIGIN, DEST_A, ORIGIN, DEST_B),
                            List.of(vehicle(priced, carrier), vehicle(unpriced, otherCarrier)),
                            List.of(order(orderA, DEST_A, 1000), order(orderB, DEST_B, 1000))),
                    List.of(new ProposedTrip(priced, null, List.of(orderA), List.of(DEST_A)),
                            new ProposedTrip(unpriced, null, List.of(orderB), List.of(DEST_B))));

            assertThat(pricing.totalCost()).isNull();
            assertThat(pricing.currency()).isNull();
            assertThat(pricing.reason()).isEqualTo(UnpricedReason.NO_AGREEMENT_FOR_SOME_TRIP);
            // The count is still reported: "1 of 2 have an agreement" is what tells a planner what
            // to fix, and it is not a price.
            assertThat(pricing.pricedTrips()).isEqualTo(1);
            assertThat(pricing.totalTrips()).isEqualTo(2);
        }

        /** No FX rate is invented here, exactly as {@code CarrierQuote} refuses to invent one. */
        @Test
        @DisplayName("agreements in two currencies do not add up, and are not converted")
        void noCurrencyConversion() {
            UUID soles = UUID.randomUUID();
            UUID dollars = UUID.randomUUID();
            UUID vehicleA = UUID.randomUUID();
            UUID vehicleB = UUID.randomUUID();
            UUID orderA = UUID.randomUUID();
            UUID orderB = UUID.randomUUID();
            when(quotationPort.quoteWithKnownDistance(any(), any(), eq(soles), any()))
                    .thenReturn(Optional.of(quote(soles, "4000.00", "PEN")));
            when(quotationPort.quoteWithKnownDistance(any(), any(), eq(dollars), any()))
                    .thenReturn(Optional.of(quote(dollars, "900.00", "USD")));

            ProposalPricing pricing = pricer.price(COMPANY,
                    input(matrixOver(ORIGIN, DEST_A, ORIGIN, DEST_B),
                            List.of(vehicle(vehicleA, soles), vehicle(vehicleB, dollars)),
                            List.of(order(orderA, DEST_A, 1000), order(orderB, DEST_B, 1000))),
                    List.of(new ProposedTrip(vehicleA, null, List.of(orderA), List.of(DEST_A)),
                            new ProposedTrip(vehicleB, null, List.of(orderB), List.of(DEST_B))));

            assertThat(pricing.totalCost()).isNull();
            assertThat(pricing.reason()).isEqualTo(UnpricedReason.MIXED_CURRENCIES);
        }

        /**
         * {@code TravelMatrix.distanceKm} answers zero for a leg it does not know, which is right
         * for planning and wrong for money. A per-kilometre charge over a pile of zeros is a price
         * that looks calculated and is not.
         */
        @Test
        @DisplayName("a run with an unmeasurable leg is quoted with a null distance, never a short sum")
        void unknownLegGivesNoDistance() {
            UUID carrier = UUID.randomUUID();
            UUID vehicleId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            when(quotationPort.quoteWithKnownDistance(any(), any(), any(), any()))
                    .thenReturn(Optional.of(quote(carrier, "100.00", "PEN")));

            pricer.price(COMPANY,
                    // Only the first leg is known; DEST_A to DEST_B is not.
                    input(matrixOver(ORIGIN, DEST_A),
                            List.of(vehicle(vehicleId, carrier)),
                            List.of(order(orderId, DEST_A, 1000))),
                    List.of(new ProposedTrip(vehicleId, null, List.of(orderId), List.of(DEST_A, DEST_B))));

            ArgumentCaptor<BigDecimal> distance = ArgumentCaptor.forClass(BigDecimal.class);
            org.mockito.Mockito.verify(quotationPort)
                    .quoteWithKnownDistance(any(), any(), any(), distance.capture());
            // Null, not 120: a run half of which could not be measured has no distance.
            assertThat(distance.getValue()).isNull();
        }

        /**
         * Own fleet has no rate card, because a rate card is an agreement with a carrier and there
         * is no agreement with yourself. Pricing it at zero would make any plan that used it
         * unbeatable.
         */
        @Test
        @DisplayName("an own-fleet vehicle is not priced at zero")
        void ownFleetIsNotFree() {
            UUID vehicleId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();

            ProposalPricing pricing = pricer.price(COMPANY,
                    input(matrixOver(ORIGIN, DEST_A),
                            List.of(vehicle(vehicleId, null)),
                            List.of(order(orderId, DEST_A, 1000))),
                    List.of(new ProposedTrip(vehicleId, null, List.of(orderId), List.of(DEST_A))));

            assertThat(pricing.totalCost()).isNull();
            assertThat(pricing.reason()).isEqualTo(UnpricedReason.NO_AGREEMENT_FOR_SOME_TRIP);
        }

        @Test
        @DisplayName("an empty plan has nothing to price, which is not the same as failing to price it")
        void emptyPlan() {
            ProposalPricing pricing = pricer.price(COMPANY,
                    input(TravelMatrix.EMPTY, List.of(), List.of()), List.of());

            assertThat(pricing.reason()).isEqualTo(UnpricedReason.NO_TRIPS);
            assertThat(pricing.totalTrips()).isZero();
        }
    }

    @Nested
    @DisplayName("the KPI block it feeds")
    class Kpis {

        @Test
        @DisplayName("pricedWith moves the total onto the block, and keeps the reason beside it")
        void pricedWithCarriesBoth() {
            PlanningKpis priced = PlanningKpis.NONE.pricedWith(
                    ProposalPricing.of(new BigDecimal("1680.00"), "PEN", 2));

            assertThat(priced.totalCost()).isEqualByComparingTo("1680.00");
            assertThat(priced.pricing().currency()).isEqualTo("PEN");
        }

        @Test
        @DisplayName("an unpriced plan reports null and the reason, never a zero")
        void unpricedIsNullNotZero() {
            PlanningKpis unpriced = PlanningKpis.NONE.pricedWith(
                    ProposalPricing.none(UnpricedReason.MIXED_CURRENCIES, 1, 2));

            assertThat(unpriced.totalCost()).isNull();
            assertThat(unpriced.pricing().reason()).isEqualTo(UnpricedReason.MIXED_CURRENCIES);
        }

        /** Every consumer gets a non-null pricing block, so no screen has to guard it. */
        @Test
        @DisplayName("a KPI block built the old way still reports a pricing answer")
        void legacyShapeIsNotAsked() {
            assertThat(PlanningKpis.NONE.pricing()).isEqualTo(ProposalPricing.NOT_ASKED);
        }
    }
}
