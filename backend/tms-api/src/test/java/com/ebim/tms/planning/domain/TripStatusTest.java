package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The transition table, asserted exhaustively rather than sampled.
 *
 * <p>Every one of the 36 (state, state) pairs is covered by {@link #everyOtherMoveIsRefused},
 * which walks the whole cross product and refuses anything not in the legal set below. A test that
 * only checked the happy moves would still pass on the day someone allowed
 * {@code COMPLETED -> DRAFT}, and that is precisely the day this file exists for.
 */
class TripStatusTest {

    /** The legal moves, written out independently of {@code TripStatus}'s own map on purpose. */
    private static final Set<String> LEGAL = Set.of(
            "DRAFT->CONFIRMED",
            "DRAFT->CANCELLED",
            "CONFIRMED->READY_FOR_DISPATCH",
            "CONFIRMED->CANCELLED",
            "READY_FOR_DISPATCH->IN_TRANSIT",
            "READY_FOR_DISPATCH->CANCELLED",
            "IN_TRANSIT->COMPLETED");

    @Nested
    @DisplayName("the transition table")
    class Transitions {

        @Test
        @DisplayName("allows exactly the seven forward moves and nothing else")
        void everyOtherMoveIsRefused() {
            for (TripStatus from : TripStatus.values()) {
                for (TripStatus to : TripStatus.values()) {
                    boolean expected = LEGAL.contains(from.name() + "->" + to.name());
                    assertThat(from.canTransitionTo(to))
                            .withFailMessage("%s -> %s should be %s", from, to, expected ? "legal" : "refused")
                            .isEqualTo(expected);
                }
            }
        }

        @Test
        @DisplayName("has no move out of a terminal state")
        void terminalStatesAreDeadEnds() {
            assertThat(TripStatus.COMPLETED.allowedTransitions()).isEmpty();
            assertThat(TripStatus.CANCELLED.allowedTransitions()).isEmpty();
        }

        @Test
        @DisplayName("never lets a trip move to the state it is already in")
        void noSelfTransition() {
            for (TripStatus status : TripStatus.values()) {
                assertThat(status.canTransitionTo(status)).isFalse();
            }
        }

        @Test
        @DisplayName("refuses cancellation once the vehicle has left")
        void aDepartedTripCannotBeCancelled() {
            assertThat(TripStatus.IN_TRANSIT.canTransitionTo(TripStatus.CANCELLED)).isFalse();
            assertThat(TripStatus.IN_TRANSIT.allowedTransitions()).containsExactly(TripStatus.COMPLETED);
        }

        @Test
        @DisplayName("never moves backwards")
        void thereIsNoUndo() {
            assertThat(TripStatus.IN_TRANSIT.canTransitionTo(TripStatus.READY_FOR_DISPATCH)).isFalse();
            assertThat(TripStatus.READY_FOR_DISPATCH.canTransitionTo(TripStatus.CONFIRMED)).isFalse();
            assertThat(TripStatus.CONFIRMED.canTransitionTo(TripStatus.DRAFT)).isFalse();
        }
    }

    @Nested
    @DisplayName("the state predicates")
    class Predicates {

        @Test
        @DisplayName("DRAFT is the only state that still allows planning edits")
        void onlyDraftIsEditable() {
            for (TripStatus status : TripStatus.values()) {
                assertThat(status.allowsPlanningEdits()).isEqualTo(status == TripStatus.DRAFT);
            }
        }

        @Test
        @DisplayName("the committed states are exactly the four that carry a capacity snapshot")
        void committedMirrorsTheSnapshotConstraint() {
            Set<TripStatus> committed = EnumSet.noneOf(TripStatus.class);
            for (TripStatus status : TripStatus.values()) {
                if (status.isCommitted()) {
                    committed.add(status);
                }
            }
            // The Java half of ck_trip_confirmed_is_complete / ck_trip_draft_has_no_snapshot (V25).
            // CANCELLED is deliberately out: a trip cancelled from DRAFT never had a snapshot, and
            // one cancelled after confirmation is read from its own capacitySnapshotAt instead.
            assertThat(committed).containsExactlyInAnyOrder(TripStatus.CONFIRMED, TripStatus.READY_FOR_DISPATCH,
                    TripStatus.IN_TRANSIT, TripStatus.COMPLETED);
        }

        @Test
        @DisplayName("a trip is in execution while it is committed and not finished")
        void inExecutionExcludesTheEndpoints() {
            assertThat(TripStatus.CONFIRMED.isInExecution()).isTrue();
            assertThat(TripStatus.READY_FOR_DISPATCH.isInExecution()).isTrue();
            assertThat(TripStatus.IN_TRANSIT.isInExecution()).isTrue();
            assertThat(TripStatus.DRAFT.isInExecution()).isFalse();
            assertThat(TripStatus.COMPLETED.isInExecution()).isFalse();
            assertThat(TripStatus.CANCELLED.isInExecution()).isFalse();
        }

        @Test
        @DisplayName("terminal means nothing follows, and the table agrees")
        void terminalAgreesWithTheTable() {
            for (TripStatus status : TripStatus.values()) {
                assertThat(status.isTerminal()).isEqualTo(status.allowedTransitions().isEmpty());
            }
        }
    }
}
