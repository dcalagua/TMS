package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The stop transition table, asserted exhaustively rather than sampled - the same shape, and the
 * same reasoning, as {@link TripStatusTest}.
 *
 * <p>All 36 (outcome, outcome) pairs are walked by {@link Transitions#everyOtherMoveIsRefused}. A
 * test that only checked the happy moves would still pass on the day someone allowed
 * {@code COMPLETED -> PENDING}, and that is the day this file exists for.
 */
class StopExecutionStatusTest {

    /** The legal moves, written out independently of the enum's own map on purpose. */
    private static final Set<String> LEGAL = Set.of(
            "PENDING->ARRIVED",
            "PENDING->SKIPPED",
            "PENDING->FAILED",
            "ARRIVED->IN_SERVICE",
            "ARRIVED->COMPLETED",
            "ARRIVED->FAILED",
            "IN_SERVICE->COMPLETED",
            "IN_SERVICE->FAILED");

    @Nested
    @DisplayName("the transition table")
    class Transitions {

        @Test
        @DisplayName("allows exactly the eight forward moves and nothing else")
        void everyOtherMoveIsRefused() {
            for (StopExecutionStatus from : StopExecutionStatus.values()) {
                for (StopExecutionStatus to : StopExecutionStatus.values()) {
                    boolean expected = LEGAL.contains(from.name() + "->" + to.name());
                    assertThat(from.canTransitionTo(to))
                            .as("%s -> %s", from, to)
                            .isEqualTo(expected);
                }
            }
        }

        @Test
        @DisplayName("never allows a stop to be reopened")
        void terminalOutcomesGoNowhere() {
            for (StopExecutionStatus terminal
                    : EnumSet.of(StopExecutionStatus.COMPLETED, StopExecutionStatus.SKIPPED,
                            StopExecutionStatus.FAILED)) {
                assertThat(terminal.allowedTransitions()).isEmpty();
            }
        }

        @Test
        @DisplayName("keeps IN_SERVICE optional by allowing ARRIVED to complete directly")
        void serviceStartIsNotMandatory() {
            assertThat(StopExecutionStatus.ARRIVED.canTransitionTo(StopExecutionStatus.COMPLETED)).isTrue();
        }

        @Test
        @DisplayName("lets a stop fail without an arrival, for an address that was never found")
        void failureDoesNotRequireArrival() {
            assertThat(StopExecutionStatus.PENDING.canTransitionTo(StopExecutionStatus.FAILED)).isTrue();
        }
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @Test
        @DisplayName("counts all three terminal outcomes as resolved, whatever they say happened")
        void everyTerminalOutcomeResolvesTheStop() {
            assertThat(StopExecutionStatus.COMPLETED.isResolved()).isTrue();
            assertThat(StopExecutionStatus.SKIPPED.isResolved()).isTrue();
            assertThat(StopExecutionStatus.FAILED.isResolved()).isTrue();
        }

        @Test
        @DisplayName("counts everything a trip could still be waiting on as outstanding")
        void inFlightOutcomesAreOutstanding() {
            assertThat(StopExecutionStatus.PENDING.isOutstanding()).isTrue();
            assertThat(StopExecutionStatus.ARRIVED.isOutstanding()).isTrue();
            assertThat(StopExecutionStatus.IN_SERVICE.isOutstanding()).isTrue();
        }

        @Test
        @DisplayName("demands a typed reason for exactly the two non-delivery outcomes")
        void onlyNonDeliveriesNeedAnException() {
            for (StopExecutionStatus status : StopExecutionStatus.values()) {
                boolean expected =
                        status == StopExecutionStatus.SKIPPED || status == StopExecutionStatus.FAILED;
                assertThat(status.requiresException()).as("%s", status).isEqualTo(expected);
            }
        }
    }
}
