package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the {@link Trip} aggregate itself guarantees about execution: the transitions it accepts,
 * the ones it refuses, and what each one records. No database - none of it needs one.
 *
 * <p>The database-backed half (the CHECK constraints of migration V25, the row locks, the events)
 * lives in {@code PlanningApiIntegrationTest} and needs Docker.
 */
class TripExecutionLifecycleTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID VEHICLE = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID DISPATCHER = UUID.randomUUID();
    private static final LocalDate PLANNING_DATE = LocalDate.of(2026, 8, 20);
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-08-20T08:00:00Z");

    private static Trip draft() {
        return new Trip(COMPANY, RUN, PLANNING_DATE, 1, "SH-00000001", VEHICLE, CARRIER, DEPARTURE, ACTOR);
    }

    private static Trip confirmed() {
        Trip trip = draft();
        trip.confirm(BigDecimal.valueOf(8000), BigDecimal.valueOf(32), 18, ACTOR);
        return trip;
    }

    private static Trip ready() {
        Trip trip = confirmed();
        trip.markReadyForDispatch(OffsetDateTime.parse("2026-08-20T07:30:00Z"), DISPATCHER);
        return trip;
    }

    private static Trip inTransit() {
        Trip trip = ready();
        trip.dispatch(OffsetDateTime.parse("2026-08-20T08:12:00Z"), DISPATCHER);
        return trip;
    }

    @Nested
    @DisplayName("the happy path")
    class HappyPath {

        @Test
        @DisplayName("walks DRAFT to COMPLETED, recording one actor and one time per step")
        void fullLifecycle() {
            Trip trip = draft();
            assertThat(trip.status()).isEqualTo(TripStatus.DRAFT);

            trip.confirm(BigDecimal.valueOf(8000), BigDecimal.valueOf(32), 18, ACTOR);
            assertThat(trip.status()).isEqualTo(TripStatus.CONFIRMED);
            assertThat(trip.confirmedAt()).isNotNull();

            OffsetDateTime readyAt = OffsetDateTime.parse("2026-08-20T07:30:00Z");
            trip.markReadyForDispatch(readyAt, DISPATCHER);
            assertThat(trip.status()).isEqualTo(TripStatus.READY_FOR_DISPATCH);
            assertThat(trip.readyAt()).isEqualTo(readyAt);
            assertThat(trip.readyBy()).isEqualTo(DISPATCHER);

            OffsetDateTime left = OffsetDateTime.parse("2026-08-20T08:12:00Z");
            trip.dispatch(left, DISPATCHER);
            assertThat(trip.status()).isEqualTo(TripStatus.IN_TRANSIT);
            assertThat(trip.actualDepartureAt()).isEqualTo(left);
            assertThat(trip.dispatchedBy()).isEqualTo(DISPATCHER);

            OffsetDateTime back = OffsetDateTime.parse("2026-08-20T17:40:00Z");
            trip.complete(back, DISPATCHER);
            assertThat(trip.status()).isEqualTo(TripStatus.COMPLETED);
            assertThat(trip.actualCompletionAt()).isEqualTo(back);
            assertThat(trip.completedBy()).isEqualTo(DISPATCHER);
        }

        @Test
        @DisplayName("keeps the planned departure untouched when the real one is recorded")
        void dispatchDoesNotRewriteThePlan() {
            Trip trip = ready();

            trip.dispatch(OffsetDateTime.parse("2026-08-20T08:47:00Z"), DISPATCHER);

            assertThat(trip.plannedDepartureAt()).isEqualTo(DEPARTURE);
            assertThat(trip.actualDepartureAt()).isEqualTo(OffsetDateTime.parse("2026-08-20T08:47:00Z"));
        }

        @Test
        @DisplayName("keeps the capacity snapshot readable for the whole of execution")
        void theSnapshotSurvivesEveryTransition() {
            Trip trip = inTransit();
            assertThat(trip.hasCapacitySnapshot()).isTrue();
            assertThat(trip.snapshotMaxWeightKg()).isEqualByComparingTo(BigDecimal.valueOf(8000));

            trip.complete(OffsetDateTime.parse("2026-08-20T17:00:00Z"), DISPATCHER);

            assertThat(trip.hasCapacitySnapshot()).isTrue();
            assertThat(trip.snapshotMaxPallets()).isEqualTo(18);
        }
    }

    @Nested
    @DisplayName("illegal moves")
    class IllegalMoves {

        @Test
        @DisplayName("a draft trip cannot be dispatched without being confirmed and made ready")
        void noSkippingAhead() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> draft().dispatch(DEPARTURE, DISPATCHER))
                    .withMessageContaining("DRAFT")
                    .withMessageContaining("IN_TRANSIT");
        }

        @Test
        @DisplayName("a confirmed trip cannot be completed without ever departing")
        void noSkippingTheRoad() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> confirmed().complete(DEPARTURE, DISPATCHER));
        }

        @Test
        @DisplayName("a trip that has left cannot be cancelled")
        void noCancellingADepartedTrip() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> inTransit().cancel("Customer called", DISPATCHER))
                    .withMessageContaining("IN_TRANSIT");
        }

        @Test
        @DisplayName("nothing follows a completed trip")
        void completedIsTerminal() {
            Trip trip = inTransit();
            trip.complete(OffsetDateTime.parse("2026-08-20T17:00:00Z"), DISPATCHER);

            assertThatIllegalStateException().isThrownBy(() -> trip.cancel("too late", DISPATCHER));
            assertThatIllegalStateException()
                    .isThrownBy(() -> trip.complete(OffsetDateTime.parse("2026-08-20T18:00:00Z"), DISPATCHER));
        }

        @Test
        @DisplayName("a trip cannot be confirmed twice")
        void confirmationIsNotRepeatable() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> confirmed().confirm(BigDecimal.ONE, BigDecimal.ONE, 1, ACTOR));
        }
    }

    @Nested
    @DisplayName("cancellation")
    class Cancellation {

        @Test
        @DisplayName("is reachable from every state before departure")
        void reachableBeforeDeparture() {
            for (Trip trip : new Trip[] {draft(), confirmed(), ready()}) {
                TripStatus before = trip.status();
                trip.cancel("Nothing to load", ACTOR);
                assertThat(trip.status())
                        .withFailMessage("cancelling from %s", before)
                        .isEqualTo(TripStatus.CANCELLED);
            }
        }

        @Test
        @DisplayName("keeps everything the trip had already recorded")
        void historyIsNotErased() {
            Trip trip = ready();
            OffsetDateTime confirmedAt = trip.confirmedAt();
            OffsetDateTime readyAt = trip.readyAt();

            trip.cancel("Vehicle broke down at the dock", DISPATCHER);

            assertThat(trip.status()).isEqualTo(TripStatus.CANCELLED);
            assertThat(trip.confirmedAt()).isEqualTo(confirmedAt);
            assertThat(trip.readyAt()).isEqualTo(readyAt);
            assertThat(trip.cancelReason()).isEqualTo("Vehicle broke down at the dock");
            assertThat(trip.cancelledAt()).isNotNull();
            // Still true, and the reason TripViewAssembler asks this instead of the status: a
            // shipment cancelled after confirmation must still report the capacity it was
            // validated against.
            assertThat(trip.hasCapacitySnapshot()).isTrue();
        }

        @Test
        @DisplayName("leaves a trip cancelled from draft with nothing to snapshot")
        void aDiscardedDraftNeverHadOne() {
            Trip trip = draft();

            trip.cancel(null, ACTOR);

            assertThat(trip.hasCapacitySnapshot()).isFalse();
            assertThat(trip.confirmedAt()).isNull();
        }
    }
}
