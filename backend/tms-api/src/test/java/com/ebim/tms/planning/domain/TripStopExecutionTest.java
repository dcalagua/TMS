package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a stop records, and the rule it gives the trip in exchange: a trip is not finished while
 * one of its stops is still outstanding (migration V27).
 *
 * <p>Works the stops through {@code TripStop.recordOutcome} directly rather than through
 * {@code Trip.recordStopOutcome}, because the latter finds its stop by id and a {@link TripStop}
 * that has never been flushed has none - JPA assigns it. The id-matching half is exercised by
 * {@code TripStopExecutionServiceTest} against a mocked repository, and by the API integration
 * test against a real database. What is asserted here is the part that is pure domain: which
 * timestamp each outcome writes, and what "resolved" means to the trip above it.
 */
class TripStopExecutionTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID VEHICLE = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID DESTINATION_A = UUID.randomUUID();
    private static final UUID DESTINATION_B = UUID.randomUUID();
    private static final LocalDate PLANNING_DATE = LocalDate.of(2026, 8, 20);
    private static final OffsetDateTime PLANNED_DEPARTURE = OffsetDateTime.parse("2026-08-20T08:00:00Z");
    private static final OffsetDateTime ARRIVAL = OffsetDateTime.parse("2026-08-20T09:30:00Z");
    private static final OffsetDateTime SERVICE_START = OffsetDateTime.parse("2026-08-20T09:45:00Z");
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-08-20T10:05:00Z");

    /** A trip on the road with two stops - the only state in which stops may be worked. */
    private static Trip dispatchedTripWithTwoStops() {
        Trip trip = new Trip(COMPANY, RUN, PLANNING_DATE, 1, "SH-00000001", VEHICLE, CARRIER, PLANNED_DEPARTURE,
                ACTOR);
        trip.syncStops(List.of(
                new StopPlan(DESTINATION_A, null, null),
                new StopPlan(DESTINATION_B, null, null)), ACTOR);
        trip.confirm(BigDecimal.valueOf(8000), BigDecimal.valueOf(32), 18, ACTOR);
        trip.markReadyForDispatch(trip.confirmedAt(), ACTOR);
        trip.dispatch(trip.readyAt(), ACTOR);
        return trip;
    }

    @Nested
    @DisplayName("a stop")
    class Stop {

        @Test
        @DisplayName("starts PENDING with no actual times at all")
        void startsPending() {
            TripStop stop = dispatchedTripWithTwoStops().stops().get(0);

            assertThat(stop.executionStatus()).isEqualTo(StopExecutionStatus.PENDING);
            assertThat(stop.actualArrivalAt()).isNull();
            assertThat(stop.serviceStartedAt()).isNull();
            assertThat(stop.actualDepartureAt()).isNull();
        }

        @Test
        @DisplayName("writes one timestamp per step and never overwrites the previous one")
        void recordsEachStepSeparately() {
            TripStop stop = dispatchedTripWithTwoStops().stops().get(0);

            stop.recordOutcome(StopExecutionStatus.ARRIVED, ARRIVAL, null, ACTOR);
            stop.recordOutcome(StopExecutionStatus.IN_SERVICE, SERVICE_START, null, ACTOR);
            stop.recordOutcome(StopExecutionStatus.COMPLETED, DEPARTURE, null, ACTOR);

            assertThat(stop.executionStatus()).isEqualTo(StopExecutionStatus.COMPLETED);
            assertThat(stop.actualArrivalAt()).isEqualTo(ARRIVAL);
            assertThat(stop.serviceStartedAt()).isEqualTo(SERVICE_START);
            assertThat(stop.actualDepartureAt()).isEqualTo(DEPARTURE);
        }

        @Test
        @DisplayName("leaves the planned service window untouched by what actually happened")
        void executionNeverRewritesThePlan() {
            Trip trip = new Trip(COMPANY, RUN, PLANNING_DATE, 1, "SH-00000002", VEHICLE, CARRIER,
                    PLANNED_DEPARTURE, ACTOR);
            trip.syncStops(List.of(new StopPlan(DESTINATION_A,
                    LocalTime.of(8, 0), LocalTime.of(9, 0))), ACTOR);
            TripStop stop = trip.stops().get(0);

            stop.recordOutcome(StopExecutionStatus.ARRIVED, ARRIVAL, null, ACTOR);

            assertThat(stop.serviceWindowStart()).isEqualTo(LocalTime.of(8, 0));
            assertThat(stop.serviceWindowEnd()).isEqualTo(LocalTime.of(9, 0));
        }

        @Test
        @DisplayName("records a skip with no times, because nothing was attempted")
        void skippingWritesNoTimes() {
            TripStop stop = dispatchedTripWithTwoStops().stops().get(0);

            stop.recordOutcome(StopExecutionStatus.SKIPPED, ARRIVAL, "customer called to cancel", ACTOR);

            assertThat(stop.executionStatus()).isEqualTo(StopExecutionStatus.SKIPPED);
            assertThat(stop.actualArrivalAt()).isNull();
            assertThat(stop.actualDepartureAt()).isNull();
            assertThat(stop.executionNotes()).isEqualTo("customer called to cancel");
        }

        @Test
        @DisplayName("keeps the arrival it already had when it fails after arriving")
        void failingAfterArrivalKeepsTheArrival() {
            TripStop stop = dispatchedTripWithTwoStops().stops().get(0);

            stop.recordOutcome(StopExecutionStatus.ARRIVED, ARRIVAL, null, ACTOR);
            stop.recordOutcome(StopExecutionStatus.FAILED, DEPARTURE, "refused at the dock", ACTOR);

            assertThat(stop.executionStatus()).isEqualTo(StopExecutionStatus.FAILED);
            assertThat(stop.actualArrivalAt()).isEqualTo(ARRIVAL);
            assertThat(stop.actualDepartureAt()).isNull();
        }

        @Test
        @DisplayName("keeps its note when a later step supplies none")
        void notesSurviveAStepThatSaysNothing() {
            TripStop stop = dispatchedTripWithTwoStops().stops().get(0);

            stop.recordOutcome(StopExecutionStatus.ARRIVED, ARRIVAL, "gate closed, waiting", ACTOR);
            stop.recordOutcome(StopExecutionStatus.COMPLETED, DEPARTURE, null, ACTOR);

            assertThat(stop.executionNotes()).isEqualTo("gate closed, waiting");
        }

        @Test
        @DisplayName("refuses a move its transition table does not allow")
        void refusesAnIllegalMove() {
            TripStop stop = dispatchedTripWithTwoStops().stops().get(0);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> stop.recordOutcome(StopExecutionStatus.COMPLETED, DEPARTURE, null, ACTOR))
                    .withMessageContaining("PENDING");
        }
    }

    @Nested
    @DisplayName("the trip above it")
    class TripRules {

        @Test
        @DisplayName("refuses to work a stop before the vehicle has left")
        void stopsCannotBeWorkedBeforeDispatch() {
            Trip trip = new Trip(COMPANY, RUN, PLANNING_DATE, 1, "SH-00000003", VEHICLE, CARRIER,
                    PLANNED_DEPARTURE, ACTOR);
            trip.syncStops(List.of(new StopPlan(DESTINATION_A, null, null)), ACTOR);
            trip.confirm(BigDecimal.valueOf(8000), BigDecimal.valueOf(32), 18, ACTOR);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> trip.recordStopOutcome(UUID.randomUUID(), StopExecutionStatus.ARRIVED,
                            ARRIVAL, null, ACTOR))
                    .withMessageContaining("CONFIRMED");
        }

        @Test
        @DisplayName("counts every unresolved stop, in visiting order")
        void reportsWhatIsStillOutstanding() {
            Trip trip = dispatchedTripWithTwoStops();
            trip.stops().get(0).recordOutcome(StopExecutionStatus.ARRIVED, ARRIVAL, null, ACTOR);

            assertThat(trip.hasUnresolvedStops()).isTrue();
            assertThat(trip.unresolvedStops()).extracting(TripStop::sequence).containsExactly(1, 2);
        }

        @Test
        @DisplayName("cannot be completed while a stop is still outstanding")
        void completionIsBlockedByAnOutstandingStop() {
            Trip trip = dispatchedTripWithTwoStops();
            trip.stops().get(0).recordOutcome(StopExecutionStatus.ARRIVED, ARRIVAL, null, ACTOR);
            trip.stops().get(0).recordOutcome(StopExecutionStatus.COMPLETED, DEPARTURE, null, ACTOR);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> trip.complete(DEPARTURE, ACTOR))
                    .withMessageContaining("not been resolved");
            assertThat(trip.status()).isEqualTo(TripStatus.IN_TRANSIT);
        }

        @Test
        @DisplayName("completes once every stop has an outcome, whatever those outcomes are")
        void completesWhenEveryStopIsResolved() {
            Trip trip = dispatchedTripWithTwoStops();
            trip.stops().get(0).recordOutcome(StopExecutionStatus.ARRIVED, ARRIVAL, null, ACTOR);
            trip.stops().get(0).recordOutcome(StopExecutionStatus.COMPLETED, DEPARTURE, null, ACTOR);
            trip.stops().get(1).recordOutcome(StopExecutionStatus.FAILED, DEPARTURE, "nobody there", ACTOR);

            trip.complete(DEPARTURE, ACTOR);

            assertThat(trip.status()).isEqualTo(TripStatus.COMPLETED);
            assertThat(trip.hasUnresolvedStops()).isFalse();
        }

        @Test
        @DisplayName("still completes a trip that has no stops at all")
        void noStopsIsNotAnOutstandingStop() {
            Trip trip = new Trip(COMPANY, RUN, PLANNING_DATE, 1, "SH-00000004", VEHICLE, CARRIER,
                    PLANNED_DEPARTURE, ACTOR);
            trip.confirm(BigDecimal.valueOf(8000), BigDecimal.valueOf(32), 18, ACTOR);
            trip.markReadyForDispatch(trip.confirmedAt(), ACTOR);
            trip.dispatch(trip.readyAt(), ACTOR);

            trip.complete(DEPARTURE, ACTOR);

            assertThat(trip.status()).isEqualTo(TripStatus.COMPLETED);
        }
    }
}
