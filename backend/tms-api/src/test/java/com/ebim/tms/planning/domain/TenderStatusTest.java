package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The tender transition table, asserted exhaustively rather than sampled - the same shape
 * {@link TripStatusTest} uses, and for the same reason.
 *
 * <p>All 36 (state, state) pairs are walked by {@link #everyOtherMoveIsRefused}. A test that only
 * checked the happy moves would still pass on the day somebody allowed
 * {@code REJECTED -> ACCEPTED}, and that is precisely the day this file exists for: an acceptance
 * edited out of a refusal would destroy the one record a dispute turns on.
 */
class TenderStatusTest {

    /** The legal moves, written out independently of {@code TenderStatus}'s own map on purpose. */
    private static final Set<String> LEGAL = Set.of(
            "DRAFT->SENT",
            "DRAFT->CANCELLED",
            "SENT->ACCEPTED",
            "SENT->REJECTED",
            "SENT->EXPIRED",
            "SENT->CANCELLED");

    @Nested
    @DisplayName("the transition table")
    class Transitions {

        @Test
        @DisplayName("allows exactly the six forward moves and nothing else")
        void everyOtherMoveIsRefused() {
            for (TenderStatus from : TenderStatus.values()) {
                for (TenderStatus to : TenderStatus.values()) {
                    boolean expected = LEGAL.contains(from.name() + "->" + to.name());
                    assertThat(from.canTransitionTo(to))
                            .withFailMessage("%s -> %s should be %s", from, to, expected ? "legal" : "refused")
                            .isEqualTo(expected);
                }
            }
        }

        @Test
        @DisplayName("has no move out of any of the four terminal states")
        void terminalStatesAreDeadEnds() {
            assertThat(TenderStatus.ACCEPTED.allowedTransitions()).isEmpty();
            assertThat(TenderStatus.REJECTED.allowedTransitions()).isEmpty();
            assertThat(TenderStatus.EXPIRED.allowedTransitions()).isEmpty();
            assertThat(TenderStatus.CANCELLED.allowedTransitions()).isEmpty();
        }

        @Test
        @DisplayName("never lets a tender move to the state it is already in")
        void noSelfTransition() {
            for (TenderStatus status : TenderStatus.values()) {
                assertThat(status.canTransitionTo(status)).isFalse();
            }
        }

        @Test
        @DisplayName("refuses to turn a refusal into an acceptance, in either direction")
        void anAnswerIsNeverRewritten() {
            assertThat(TenderStatus.REJECTED.canTransitionTo(TenderStatus.ACCEPTED)).isFalse();
            assertThat(TenderStatus.ACCEPTED.canTransitionTo(TenderStatus.REJECTED)).isFalse();
            assertThat(TenderStatus.EXPIRED.canTransitionTo(TenderStatus.SENT)).isFalse();
            assertThat(TenderStatus.CANCELLED.canTransitionTo(TenderStatus.SENT)).isFalse();
        }

        @Test
        @DisplayName("cannot expire a draft: nothing is running against it")
        void onlyASentOfferCanLapse() {
            assertThat(TenderStatus.DRAFT.canTransitionTo(TenderStatus.EXPIRED)).isFalse();
            assertThat(TenderStatus.SENT.canTransitionTo(TenderStatus.EXPIRED)).isTrue();
        }

        @Test
        @DisplayName("cannot answer an offer that was never sent")
        void aDraftCannotBeAnswered() {
            assertThat(TenderStatus.DRAFT.canTransitionTo(TenderStatus.ACCEPTED)).isFalse();
            assertThat(TenderStatus.DRAFT.canTransitionTo(TenderStatus.REJECTED)).isFalse();
        }
    }

    @Nested
    @DisplayName("the state predicates")
    class Predicates {

        @Test
        @DisplayName("DRAFT is the only state whose terms may still be edited")
        void onlyADraftIsEditable() {
            for (TenderStatus status : TenderStatus.values()) {
                assertThat(status.allowsTermEdits()).isEqualTo(status == TenderStatus.DRAFT);
            }
        }

        @Test
        @DisplayName("the live states are exactly the two that occupy the trip's one slot")
        void liveMirrorsTheUniqueIndex() {
            Set<TenderStatus> live = EnumSet.noneOf(TenderStatus.class);
            for (TenderStatus status : TenderStatus.values()) {
                if (status.isLive()) {
                    live.add(status);
                }
            }
            // The Java half of uq_trip_tender_live (V31).
            assertThat(live).containsExactlyInAnyOrder(TenderStatus.DRAFT, TenderStatus.SENT);
        }

        @Test
        @DisplayName("only a sent offer is awaiting an answer")
        void awaitsResponseIsSentAlone() {
            for (TenderStatus status : TenderStatus.values()) {
                assertThat(status.awaitsResponse()).isEqualTo(status == TenderStatus.SENT);
            }
        }

        @Test
        @DisplayName("terminal means nothing follows, and the table agrees")
        void terminalAgreesWithTheTable() {
            for (TenderStatus status : TenderStatus.values()) {
                assertThat(status.isTerminal()).isEqualTo(status.allowedTransitions().isEmpty());
            }
        }

        @Test
        @DisplayName("live and terminal partition the whole enum")
        void everyStateIsExactlyOneOfTheTwo() {
            for (TenderStatus status : TenderStatus.values()) {
                assertThat(status.isLive())
                        .withFailMessage("%s is neither live nor terminal, or is both", status)
                        .isNotEqualTo(status.isTerminal());
            }
        }
    }
}
