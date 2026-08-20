package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shipment rules that live in the {@link Trip} aggregate itself, exercised without a database
 * because none of them needs one: stop sequencing, the route-as-suggestion semantics, and the
 * integrity assertions that make "a shipment silently lost a stop" a failed transaction rather
 * than a delivery a driver never makes.
 *
 * <p>The database-backed half of the same rules - concurrency, the composite foreign keys, the
 * uniqueness of a shipment number - lives in {@code PlanningApiIntegrationTest} and
 * {@code PlanningConstraintIntegrationTest}, both of which need Docker.
 */
class TripShipmentTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final LocalDate PLANNING_DATE = LocalDate.of(2026, 8, 20);

    private static final UUID DEST_A = UUID.randomUUID();
    private static final UUID DEST_B = UUID.randomUUID();
    private static final UUID DEST_C = UUID.randomUUID();

    private static Trip trip() {
        return new Trip(COMPANY, RUN, PLANNING_DATE, 1, "SH-00000001", null, null, null, ACTOR);
    }

    private static StopPlan plan(UUID destinationId) {
        return new StopPlan(destinationId, null, null);
    }

    private static List<UUID> destinationsInOrder(Trip trip) {
        return trip.stops().stream().map(TripStop::destinationId).toList();
    }

    @Test
    @DisplayName("stops are numbered 1..N with no gaps after every sync")
    void syncStopsProducesAContiguousSequence() {
        Trip trip = trip();

        trip.syncStops(List.of(plan(DEST_A), plan(DEST_B), plan(DEST_C)), ACTOR);

        assertThat(trip.stops().stream().map(TripStop::sequence)).containsExactly(1, 2, 3);
        assertThatCode(trip::assertStopSequenceIntegrity).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("removing the middle destination renumbers the rest without leaving a gap")
    void removingAStopClosesTheGap() {
        Trip trip = trip();
        trip.syncStops(List.of(plan(DEST_A), plan(DEST_B), plan(DEST_C)), ACTOR);

        trip.syncStops(List.of(plan(DEST_A), plan(DEST_C)), ACTOR);

        assertThat(destinationsInOrder(trip)).containsExactly(DEST_A, DEST_C);
        assertThat(trip.stops().stream().map(TripStop::sequence)).containsExactly(1, 2);
    }

    @Test
    @DisplayName("a sync keeps the planner's manual ordering and appends what is new")
    void syncPreservesManualOrdering() {
        Trip trip = trip();
        trip.syncStops(List.of(plan(DEST_A), plan(DEST_B)), ACTOR);
        trip.reorderStops(List.of(DEST_B, DEST_A), ACTOR);

        trip.syncStops(List.of(plan(DEST_A), plan(DEST_B), plan(DEST_C)), ACTOR);

        assertThat(destinationsInOrder(trip)).containsExactly(DEST_B, DEST_A, DEST_C);
    }

    @Test
    @DisplayName("a stop keeps the window envelope its orders imply after a resync")
    void syncRefreshesTheWindowEnvelope() {
        Trip trip = trip();
        trip.syncStops(List.of(new StopPlan(DEST_A, LocalTime.of(8, 0), LocalTime.of(12, 0))), ACTOR);

        trip.syncStops(List.of(new StopPlan(DEST_A, LocalTime.of(7, 0), LocalTime.of(15, 0))), ACTOR);

        TripStop stop = trip.stops().get(0);
        assertThat(stop.serviceWindowStart()).isEqualTo(LocalTime.of(7, 0));
        assertThat(stop.serviceWindowEnd()).isEqualTo(LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("applying a route reorders the shipment's existing stops into the route's order")
    void applyRouteReordersExistingStops() {
        Trip trip = trip();
        trip.syncStops(List.of(plan(DEST_A), plan(DEST_B), plan(DEST_C)), ACTOR);
        UUID routeId = UUID.randomUUID();

        trip.applyRoute(routeId, List.of(DEST_C, DEST_A, DEST_B), ACTOR);

        assertThat(trip.routeId()).isEqualTo(routeId);
        assertThat(destinationsInOrder(trip)).containsExactly(DEST_C, DEST_A, DEST_B);
        assertThat(trip.stops().stream().map(TripStop::sequence)).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("a route the shipment does not fully serve reorders only what is there")
    void applyRouteIgnoresDestinationsTheShipmentDoesNotServe() {
        Trip trip = trip();
        trip.syncStops(List.of(plan(DEST_A), plan(DEST_B)), ACTOR);

        trip.applyRoute(UUID.randomUUID(), List.of(DEST_C, DEST_B, DEST_A), ACTOR);

        // DEST_C is in the corridor but nothing is being delivered there: not invented as a stop.
        assertThat(destinationsInOrder(trip)).containsExactly(DEST_B, DEST_A);
    }

    @Test
    @DisplayName("a route that omits a served destination keeps it, at the end")
    void applyRouteNeverDropsAServedDestination() {
        Trip trip = trip();
        trip.syncStops(List.of(plan(DEST_A), plan(DEST_B), plan(DEST_C)), ACTOR);

        trip.applyRoute(UUID.randomUUID(), List.of(DEST_C), ACTOR);

        assertThat(destinationsInOrder(trip)).containsExactly(DEST_C, DEST_A, DEST_B);
        assertThat(trip.stops()).hasSize(3);
    }

    @Test
    @DisplayName("recording a route without applying its sequence leaves the stop order untouched")
    void applyRouteWithoutSequenceKeepsTheOrder() {
        Trip trip = trip();
        trip.syncStops(List.of(plan(DEST_A), plan(DEST_B)), ACTOR);
        UUID routeId = UUID.randomUUID();

        trip.applyRoute(routeId, List.of(), ACTOR);

        assertThat(trip.routeId()).isEqualTo(routeId);
        assertThat(destinationsInOrder(trip)).containsExactly(DEST_A, DEST_B);
    }

    @Test
    @DisplayName("clearing the route reference keeps every stop")
    void clearingTheRouteKeepsTheStops() {
        Trip trip = trip();
        trip.syncStops(List.of(plan(DEST_A), plan(DEST_B)), ACTOR);
        trip.applyRoute(UUID.randomUUID(), List.of(DEST_B, DEST_A), ACTOR);

        trip.applyRoute(null, List.of(), ACTOR);

        assertThat(trip.routeId()).isNull();
        assertThat(destinationsInOrder(trip)).containsExactly(DEST_B, DEST_A);
    }

    @Test
    @DisplayName("an explicit reorder must name exactly the stops the shipment has")
    void reorderRejectsAForeignDestination() {
        Trip trip = trip();
        trip.syncStops(List.of(plan(DEST_A), plan(DEST_B)), ACTOR);

        assertThatCode(() -> trip.reorderStops(List.of(DEST_A, DEST_C), ACTOR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the sequence assertion catches a stop list that is not 1..N")
    void assertStopSequenceIntegrityCatchesAGap() {
        Trip trip = trip();
        trip.syncStops(List.of(plan(DEST_A), plan(DEST_B)), ACTOR);
        // Simulates the only way this can happen: something wrote a position without renumbering.
        trip.stops().get(1).applySequence(5, ACTOR);

        assertThatIllegalStateException().isThrownBy(trip::assertStopSequenceIntegrity)
                .withMessageContaining("SH-00000001");
    }

    @Test
    @DisplayName("cancelling a shipment's last assignment empties its stop list rather than stranding one")
    void syncToNothingEmptiesTheStops() {
        Trip trip = trip();
        trip.syncStops(List.of(plan(DEST_A), plan(DEST_B)), ACTOR);

        trip.syncStops(List.of(), ACTOR);

        assertThat(trip.stops()).isEmpty();
        assertThatCode(trip::assertStopSequenceIntegrity).doesNotThrowAnyException();
    }
}
