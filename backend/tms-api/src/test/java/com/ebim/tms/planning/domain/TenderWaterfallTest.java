package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The waterfall's own rules, provable with no database and no Spring context (migration V40).
 *
 * <p>The sequence the brief names - A rejected, B expired, C accepted - is asserted here as domain
 * behaviour, and again over HTTP by the service test. Both are worth having: this one says the
 * aggregate is correct, that one says the wiring is.
 */
class TenderWaterfallTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TRIP = UUID.randomUUID();
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-08-28T06:00:00Z");

    private static TenderWaterfall waterfall(int maxAttempts, int carriers) {
        TenderWaterfall waterfall = new TenderWaterfall(COMPANY, TRIP, maxAttempts, 120, T0, UUID.randomUUID());
        for (int i = 0; i < carriers; i++) {
            waterfall.addCandidate(UUID.nameUUIDFromBytes(("carrier-" + i).getBytes()),
                    BigDecimal.valueOf(700 + i * 100L), "PEN", UUID.randomUUID());
        }
        return waterfall;
    }

    /** Offers the next candidate and answers it, as the service does. */
    private static void answer(TenderWaterfall waterfall, WaterfallCandidateStatus outcome, OffsetDateTime at) {
        TenderWaterfallCandidate next = waterfall.nextToOffer().orElseThrow();
        next.offered(UUID.randomUUID());
        next.decided(outcome, at);
    }

    @Nested
    @DisplayName("walking the list")
    class Walking {

        @Test
        @DisplayName("ranks are dense and one-based, and the first offered is rank 1")
        void ranksAreDense() {
            TenderWaterfall waterfall = waterfall(4, 3);

            assertThat(waterfall.candidates()).extracting(TenderWaterfallCandidate::rank)
                    .containsExactly(1, 2, 3);
            assertThat(waterfall.nextToOffer().orElseThrow().rank()).isEqualTo(1);
        }

        /** The sequence the brief names. */
        @Test
        @DisplayName("A rejected, B expired, C accepted")
        void theWaterfall() {
            TenderWaterfall waterfall = waterfall(4, 3);

            answer(waterfall, WaterfallCandidateStatus.REJECTED, T0.plusMinutes(30));
            assertThat(waterfall.nextToOffer().orElseThrow().rank()).isEqualTo(2);

            answer(waterfall, WaterfallCandidateStatus.EXPIRED, T0.plusHours(3));
            assertThat(waterfall.nextToOffer().orElseThrow().rank()).isEqualTo(3);

            TenderWaterfallCandidate third = waterfall.nextToOffer().orElseThrow();
            third.offered(UUID.randomUUID());
            third.decided(WaterfallCandidateStatus.ACCEPTED, T0.plusHours(4));
            assertThat(waterfall.finish(WaterfallStatus.ACCEPTED, null, T0.plusHours(4))).isTrue();

            assertThat(waterfall.status()).isEqualTo(WaterfallStatus.ACCEPTED);
            assertThat(waterfall.attemptsUsed()).isEqualTo(3);
            assertThat(waterfall.candidates()).extracting(TenderWaterfallCandidate::status)
                    .containsExactly(WaterfallCandidateStatus.REJECTED, WaterfallCandidateStatus.EXPIRED,
                            WaterfallCandidateStatus.ACCEPTED);
        }

        @Test
        @DisplayName("only one offer is out at a time")
        void oneOfferAtATime() {
            TenderWaterfall waterfall = waterfall(4, 3);
            waterfall.nextToOffer().orElseThrow().offered(UUID.randomUUID());

            assertThat(waterfall.offered()).isPresent();
            // The one that is out is not pending, so it cannot be offered again; the next pending
            // one is rank 2, and the service refuses to offer while one is already out.
            assertThat(waterfall.nextToOffer().orElseThrow().rank()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("the attempt ceiling")
    class Ceiling {

        @Test
        @DisplayName("stops offering once the ceiling is reached, even with carriers left on the list")
        void stopsAtTheCeiling() {
            TenderWaterfall waterfall = waterfall(2, 5);

            answer(waterfall, WaterfallCandidateStatus.REJECTED, T0.plusMinutes(10));
            answer(waterfall, WaterfallCandidateStatus.REJECTED, T0.plusMinutes(20));

            assertThat(waterfall.hasReachedAttemptCeiling()).isTrue();
            assertThat(waterfall.nextToOffer()).isEmpty();
            assertThat(waterfall.candidates()).extracting(TenderWaterfallCandidate::status)
                    .contains(WaterfallCandidateStatus.PENDING);
        }

        @Test
        @DisplayName("an offer that is still out counts against the ceiling")
        void anOpenOfferCounts() {
            TenderWaterfall waterfall = waterfall(1, 3);
            waterfall.nextToOffer().orElseThrow().offered(UUID.randomUUID());

            assertThat(waterfall.attemptsUsed()).isEqualTo(1);
            assertThat(waterfall.nextToOffer()).isEmpty();
        }
    }

    @Nested
    @DisplayName("ending")
    class Ending {

        /**
         * A candidate never reached is SKIPPED, not left PENDING: a finished waterfall showing
         * pending candidates reads as one still waiting to continue.
         */
        @Test
        @DisplayName("candidates never reached are marked skipped, not left pending")
        void unreachedCandidatesAreSkipped() {
            TenderWaterfall waterfall = waterfall(4, 4);
            answer(waterfall, WaterfallCandidateStatus.ACCEPTED, T0.plusMinutes(10));

            waterfall.finish(WaterfallStatus.ACCEPTED, null, T0.plusMinutes(10));

            assertThat(waterfall.candidates()).extracting(TenderWaterfallCandidate::status)
                    .containsExactly(WaterfallCandidateStatus.ACCEPTED, WaterfallCandidateStatus.SKIPPED,
                            WaterfallCandidateStatus.SKIPPED, WaterfallCandidateStatus.SKIPPED);
        }

        /**
         * The race the idempotency exists for: a carrier accepting and a dispatcher stopping the
         * waterfall in the same instant must not produce a waterfall whose outcome was rewritten.
         */
        @Test
        @DisplayName("a waterfall that has already ended cannot be ended again")
        void finishIsIdempotent() {
            TenderWaterfall waterfall = waterfall(4, 3);
            answer(waterfall, WaterfallCandidateStatus.ACCEPTED, T0.plusMinutes(10));

            assertThat(waterfall.finish(WaterfallStatus.ACCEPTED, null, T0.plusMinutes(10))).isTrue();
            assertThat(waterfall.finish(WaterfallStatus.CANCELLED, "too late", T0.plusMinutes(20))).isFalse();

            assertThat(waterfall.status()).isEqualTo(WaterfallStatus.ACCEPTED);
            assertThat(waterfall.outcomeNote()).isNull();
        }

        @Test
        @DisplayName("ACTIVE is not an outcome")
        void activeIsNotAnOutcome() {
            TenderWaterfall waterfall = waterfall(4, 3);

            assertThatThrownBy(() -> waterfall.finish(WaterfallStatus.ACTIVE, null, T0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an exhausted waterfall is not a cancelled one")
        void exhaustedIsNotCancelled() {
            TenderWaterfall waterfall = waterfall(4, 2);
            answer(waterfall, WaterfallCandidateStatus.REJECTED, T0.plusMinutes(10));
            answer(waterfall, WaterfallCandidateStatus.REJECTED, T0.plusMinutes(20));

            assertThat(waterfall.nextToOffer()).isEmpty();
            waterfall.finish(WaterfallStatus.EXHAUSTED, "everyone said no", T0.plusMinutes(20));

            // Nobody decided to stop; the list ran out. A dispatcher acts differently on each.
            assertThat(waterfall.status()).isEqualTo(WaterfallStatus.EXHAUSTED);
            assertThat(waterfall.status().isFinished()).isTrue();
        }
    }

    @Nested
    @DisplayName("a candidate's own rules")
    class Candidates {

        /**
         * Walking a candidate back to PENDING would let one refusal consume two attempts, or none.
         */
        @Test
        @DisplayName("a candidate cannot be walked backwards into pending")
        void noGoingBack() {
            TenderWaterfall waterfall = waterfall(4, 2);
            TenderWaterfallCandidate first = waterfall.nextToOffer().orElseThrow();
            first.offered(UUID.randomUUID());

            assertThatThrownBy(() -> first.decided(WaterfallCandidateStatus.PENDING, T0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> first.decided(WaterfallCandidateStatus.OFFERED, T0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * SKIPPED has no tender behind it, which is why it is not an answer: inventing an empty
         * tender so the column could be non-null would record an offer that was never made.
         */
        @Test
        @DisplayName("skipped is not an answer a carrier can give")
        void skippedIsNotAnAnswer() {
            TenderWaterfall waterfall = waterfall(4, 2);
            TenderWaterfallCandidate first = waterfall.nextToOffer().orElseThrow();
            first.offered(UUID.randomUUID());

            assertThatThrownBy(() -> first.decided(WaterfallCandidateStatus.SKIPPED, T0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the quoted price is snapshotted with the card that produced it")
        void quoteIsSnapshotted() {
            TenderWaterfall waterfall = waterfall(4, 1);
            TenderWaterfallCandidate only = waterfall.candidates().get(0);

            assertThat(only.quotedAmount()).isEqualByComparingTo("700");
            assertThat(only.quotedCurrency()).isEqualTo("PEN");
            assertThat(only.rateCardId()).isNotNull();
        }
    }
}
