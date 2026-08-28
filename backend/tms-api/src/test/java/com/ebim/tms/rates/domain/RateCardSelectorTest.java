package com.ebim.tms.rates.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.reference.CostableTrip;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which agreement prices a shipment - the four filters and the four tie-breaks
 * {@link RateCardSelector} applies.
 *
 * <p>Pure, so it runs everywhere: nothing here needs a database, which is the point of the
 * selector being a function over a candidate list rather than a query.
 */
class RateCardSelectorTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();
    private static final UUID OTHER_CARRIER = UUID.randomUUID();
    private static final UUID ORIGIN = UUID.randomUUID();
    private static final UUID OTHER_ORIGIN = UUID.randomUUID();
    private static final UUID ROUTE = UUID.randomUUID();
    private static final UUID VEHICLE_TYPE = UUID.randomUUID();
    private static final UUID OTHER_VEHICLE_TYPE = UUID.randomUUID();
    private static final LocalDate PLANNING_DATE = LocalDate.of(2026, 8, 20);

    @Nested
    @DisplayName("candidates")
    class Candidates {

        @Test
        @DisplayName("no card at all is an ordinary answer, not an error")
        void noCards() {
            assertThat(RateCardSelector.select(trip(), List.of())).isEmpty();
        }

        @Test
        @DisplayName("a card of another carrier never prices this shipment")
        void anotherCarrier() {
            RateCard foreign = card("FOREIGN", OTHER_CARRIER, RateCardScope.CARRIER, null, null, null,
                    PLANNING_DATE, null);

            assertThat(RateCardSelector.select(trip(), List.of(foreign))).isEmpty();
        }

        @Test
        @DisplayName("validity is judged against the planning date, both bounds inclusive")
        void validity() {
            RateCard endedYesterday = card("OLD", CARRIER, RateCardScope.CARRIER, null, null, null,
                    PLANNING_DATE.minusDays(30), PLANNING_DATE.minusDays(1));
            RateCard startsTomorrow = card("NEXT", CARRIER, RateCardScope.CARRIER, null, null, null,
                    PLANNING_DATE.plusDays(1), null);
            RateCard endsToday = card("TODAY", CARRIER, RateCardScope.CARRIER, null, null, null,
                    PLANNING_DATE.minusDays(30), PLANNING_DATE);

            assertThat(RateCardSelector.select(trip(), List.of(endedYesterday, startsTomorrow))).isEmpty();
            assertThat(RateCardSelector.select(trip(), List.of(endsToday, endedYesterday, startsTomorrow)))
                    .contains(endsToday);
        }

        @Test
        @DisplayName("an origin-scoped card does not cover a shipment leaving somewhere else")
        void originScope() {
            RateCard elsewhere = card("OTHER-DEPOT", CARRIER, RateCardScope.ORIGIN, OTHER_ORIGIN, null, null,
                    PLANNING_DATE, null);

            assertThat(RateCardSelector.select(trip(), List.of(elsewhere))).isEmpty();
        }

        @Test
        @DisplayName("a route-scoped card only covers a shipment built from that route")
        void routeScope() {
            RateCard corridor = card("NORTE", CARRIER, RateCardScope.ROUTE, null, ROUTE, null, PLANNING_DATE, null);
            CostableTrip withoutRoute = trip(CARRIER, VEHICLE_TYPE, null);

            assertThat(RateCardSelector.select(withoutRoute, List.of(corridor))).isEmpty();
            assertThat(RateCardSelector.select(trip(), List.of(corridor))).contains(corridor);
        }

        @Test
        @DisplayName("a card naming a vehicle type never prices a shipment on another type, or on none")
        void vehicleType() {
            RateCard forOneType = card("ARTIC", CARRIER, RateCardScope.CARRIER, null, null, OTHER_VEHICLE_TYPE,
                    PLANNING_DATE, null);
            CostableTrip noVehicleYet = trip(CARRIER, null, ROUTE);

            assertThat(RateCardSelector.select(trip(), List.of(forOneType))).isEmpty();
            assertThat(RateCardSelector.select(noVehicleYet, List.of(forOneType))).isEmpty();
        }
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @Test
        @DisplayName("the narrowest scope wins: route beats origin beats carrier")
        void scopeSpecificity() {
            RateCard blanket = card("BLANKET", CARRIER, RateCardScope.CARRIER, null, null, null, PLANNING_DATE, null);
            RateCard depot = card("DEPOT", CARRIER, RateCardScope.ORIGIN, ORIGIN, null, null, PLANNING_DATE, null);
            RateCard corridor = card("CORRIDOR", CARRIER, RateCardScope.ROUTE, null, ROUTE, null,
                    PLANNING_DATE, null);

            assertThat(RateCardSelector.select(trip(), List.of(blanket, depot, corridor))).contains(corridor);
            assertThat(RateCardSelector.select(trip(), List.of(blanket, depot))).contains(depot);
            assertThat(RateCardSelector.select(trip(), List.of(blanket))).contains(blanket);
        }

        @Test
        @DisplayName("within one scope, the card naming a vehicle type beats the one that does not")
        void vehicleTypeBeatsAnyType() {
            RateCard anyType = card("ANY", CARRIER, RateCardScope.ORIGIN, ORIGIN, null, null, PLANNING_DATE, null);
            RateCard thisType = card("THIS", CARRIER, RateCardScope.ORIGIN, ORIGIN, null, VEHICLE_TYPE,
                    PLANNING_DATE, null);

            assertThat(RateCardSelector.select(trip(), List.of(anyType, thisType))).contains(thisType);
        }

        @Test
        @DisplayName("scope outranks vehicle type, deliberately")
        void scopeOutranksVehicleType() {
            RateCard carrierWideForThisTruck = card("TRUCK", CARRIER, RateCardScope.CARRIER, null, null,
                    VEHICLE_TYPE, PLANNING_DATE, null);
            RateCard corridorAnyTruck = card("CORRIDOR", CARRIER, RateCardScope.ROUTE, null, ROUTE, null,
                    PLANNING_DATE, null);

            assertThat(RateCardSelector.select(trip(), List.of(carrierWideForThisTruck, corridorAnyTruck)))
                    .contains(corridorAnyTruck);
        }

        @Test
        @DisplayName("two overlapping agreements resolve to the newer one, then to the lower code")
        void overlapIsStillDeterministic() {
            RateCard older = card("A-OLD", CARRIER, RateCardScope.CARRIER, null, null, null,
                    PLANNING_DATE.minusDays(60), null);
            RateCard newer = card("Z-NEW", CARRIER, RateCardScope.CARRIER, null, null, null,
                    PLANNING_DATE.minusDays(5), null);
            RateCard sameDayAsNewer = card("B-NEW", CARRIER, RateCardScope.CARRIER, null, null, null,
                    PLANNING_DATE.minusDays(5), null);

            assertThat(RateCardSelector.select(trip(), List.of(older, newer))).contains(newer);
            assertThat(RateCardSelector.select(trip(), List.of(newer, sameDayAsNewer, older)))
                    .as("the code tie-break keeps the answer total even for data the schema refuses")
                    .contains(sameDayAsNewer);
        }
    }

    @Nested
    @DisplayName("lane pricing (V39)")
    class Lanes {

        private static final UUID DESTINATION = UUID.randomUUID();
        private static final UUID OTHER_DESTINATION = UUID.randomUUID();

        /** A trip with exactly one destination is on a lane; one with several is not. */
        private static CostableTrip singleDrop(UUID destinationId) {
            return new CostableTrip(UUID.randomUUID(), COMPANY, "SH-00000001", PLANNING_DATE, CARRIER,
                    VEHICLE_TYPE, ORIGIN, null, BigDecimal.valueOf(1000), BigDecimal.valueOf(12),
                    BigDecimal.valueOf(10), true, destinationId, 1);
        }

        private static RateCard lane(String code, UUID originId, UUID destinationId) {
            return new RateCard(COMPANY, code, code, CARRIER, RateCardScope.LANE, originId, destinationId, null,
                    null, "PEN", PLANNING_DATE.minusDays(30), null,
                    RateComponents.flat(BigDecimal.valueOf(100)), UUID.randomUUID());
        }

        @Test
        @DisplayName("a lane card prices the shipment that runs that lane")
        void matchesTheLane() {
            RateCard card = lane("LANE-1", ORIGIN, DESTINATION);

            assertThat(RateCardSelector.select(singleDrop(DESTINATION), List.of(card))).contains(card);
        }

        @Test
        @DisplayName("a lane card does not price a shipment going somewhere else")
        void wrongDestination() {
            RateCard card = lane("LANE-1", ORIGIN, DESTINATION);

            assertThat(RateCardSelector.select(singleDrop(OTHER_DESTINATION), List.of(card))).isEmpty();
        }

        /**
         * The case that would price a multi-drop shipment against a contract that was never about
         * it. A four-stop trip has no lane, and {@code Objects.equals(null, null)} would otherwise
         * have matched every lane card whose destination happened to be null - which is exactly the
         * pricing-by-coincidence the scope rule refuses.
         */
        @Test
        @DisplayName("a multi-drop shipment is on no lane and is priced by no lane card")
        void multiDropHasNoLane() {
            CostableTrip multiDrop = new CostableTrip(UUID.randomUUID(), COMPANY, "SH-2", PLANNING_DATE,
                    CARRIER, VEHICLE_TYPE, ORIGIN, null, BigDecimal.valueOf(1000), BigDecimal.valueOf(12),
                    BigDecimal.valueOf(10), true, null, 4);

            assertThat(RateCardSelector.select(multiDrop, List.of(lane("LANE-1", ORIGIN, DESTINATION))))
                    .isEmpty();
        }

        @Test
        @DisplayName("a lane beats an origin card, and a route card beats a lane")
        void specificityOrder() {
            RateCard byCarrier = card("CAR", CARRIER, RateCardScope.CARRIER, null, null, null,
                    PLANNING_DATE.minusDays(30), null);
            RateCard byOrigin = card("ORG", CARRIER, RateCardScope.ORIGIN, ORIGIN, null, null,
                    PLANNING_DATE.minusDays(30), null);
            RateCard byLane = lane("LANE", ORIGIN, DESTINATION);

            assertThat(RateCardSelector.select(singleDrop(DESTINATION), List.of(byCarrier, byOrigin, byLane)))
                    .as("the lane is more specific than the depot it leaves")
                    .contains(byLane);
        }
    }

    private static CostableTrip trip() {
        return trip(CARRIER, VEHICLE_TYPE, ROUTE);
    }

    private static CostableTrip trip(UUID carrierId, UUID vehicleTypeId, UUID routeId) {
        return new CostableTrip(UUID.randomUUID(), COMPANY, "SH-00000001", PLANNING_DATE, carrierId, vehicleTypeId,
                ORIGIN, routeId, BigDecimal.valueOf(1000), BigDecimal.valueOf(12), BigDecimal.valueOf(10), true);
    }

    private static RateCard card(String code, UUID carrierId, RateCardScope scope, UUID originId, UUID routeId,
            UUID vehicleTypeId, LocalDate validFrom, LocalDate validTo) {
        return new RateCard(COMPANY, code, code, carrierId, scope, originId, routeId, vehicleTypeId, "PEN",
                validFrom, validTo, RateComponents.flat(BigDecimal.valueOf(100)), UUID.randomUUID());
    }
}
