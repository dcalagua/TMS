package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rules {@link TripTender} owns on top of the transition table: the shape of a legal offer, the
 * source/actor pairing of an answer, and the one thing a status enum cannot express -
 * {@link TripTender#effectiveStatus}.
 *
 * <p>Pure, with no Spring and no database. Every rule asserted here has a {@code CHECK} constraint
 * behind it in migration V31; what this file pins is that the entity refuses the same thing first,
 * with a message that names the problem rather than a constraint.
 *
 * <p><b>Times are relative, never literal.</b> {@code cancel()} stamps from the wall clock, so a
 * test that hard-coded an instant would pass or fail depending on when it ran.
 */
class TripTenderTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TRIP = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();
    private static final UUID PLANNER = UUID.randomUUID();
    private static final UUID CLIENT = UUID.randomUUID();

    private static final OffsetDateTime SENT_AT = OffsetDateTime.parse("2026-08-21T10:00:00Z");
    private static final OffsetDateTime DEADLINE = SENT_AT.plusHours(6);

    @Nested
    @DisplayName("the offer")
    class Offer {

        @Test
        @DisplayName("starts as a draft that has been sent to nobody")
        void newTendersAreDrafts() {
            TripTender tender = draft(BigDecimal.valueOf(1240), "PEN", DEADLINE);

            assertThat(tender.status()).isEqualTo(TenderStatus.DRAFT);
            assertThat(tender.sentAt()).isNull();
            assertThat(tender.respondedAt()).isNull();
            assertThat(tender.attempt()).isEqualTo(1);
        }

        @Test
        @DisplayName("refuses an amount without a currency, and a currency without an amount")
        void theOfferPairIsAllOrNothing() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> draft(BigDecimal.TEN, null, null))
                    .withMessageContaining("currency");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> draft(null, "PEN", null))
                    .withMessageContaining("currency");
        }

        @Test
        @DisplayName("accepts an offer that names no price at all")
        void aPricelessOfferIsLegal() {
            TripTender tender = draft(null, null, null);

            assertThat(tender.offeredAmount()).isNull();
            assertThat(tender.currency()).isNull();
        }

        @Test
        @DisplayName("lets a draft's terms be rewritten")
        void aDraftIsEditable() {
            TripTender tender = draft(BigDecimal.valueOf(1240), "PEN", DEADLINE);

            tender.updateTerms(BigDecimal.valueOf(1180), "PEN", "Gate B", DEADLINE.plusHours(1), PLANNER);

            assertThat(tender.offeredAmount()).isEqualByComparingTo("1180");
            assertThat(tender.notes()).isEqualTo("Gate B");
            assertThat(tender.expiresAt()).isEqualTo(DEADLINE.plusHours(1));
        }

        @Test
        @DisplayName("freezes the terms once the offer has gone out")
        void aSentOfferIsFrozen() {
            TripTender tender = sent(DEADLINE);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> tender.updateTerms(BigDecimal.ONE, "PEN", null, null, PLANNER))
                    .withMessageContaining("frozen");
        }
    }

    @Nested
    @DisplayName("sending")
    class Sending {

        @Test
        @DisplayName("records when it left and who released it")
        void sendStampsTheOffer() {
            TripTender tender = draft(BigDecimal.valueOf(1240), "PEN", DEADLINE);

            tender.send(SENT_AT, PLANNER);

            assertThat(tender.status()).isEqualTo(TenderStatus.SENT);
            assertThat(tender.sentAt()).isEqualTo(SENT_AT);
            assertThat(tender.sentBy()).isEqualTo(PLANNER);
        }

        @Test
        @DisplayName("refuses a deadline that is not after the moment of sending")
        void anOfferCannotExpireBeforeItArrives() {
            TripTender tender = draft(null, null, SENT_AT);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> tender.send(SENT_AT, PLANNER))
                    .withMessageContaining("deadline");
        }

        @Test
        @DisplayName("refuses to send an offer twice")
        void sendIsNotRepeatable() {
            TripTender tender = sent(DEADLINE);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> tender.send(SENT_AT.plusMinutes(1), PLANNER))
                    .withMessageContaining("cannot move from SENT to SENT");
        }
    }

    @Nested
    @DisplayName("the answer")
    class Answering {

        @Test
        @DisplayName("an operator acceptance names a person and no credential")
        void operatorAcceptance() {
            TripTender tender = sent(DEADLINE);

            tender.accept(SENT_AT.plusHours(1), TenderResponseSource.OPERATOR, PLANNER, null, "12t confirmed");

            assertThat(tender.status()).isEqualTo(TenderStatus.ACCEPTED);
            assertThat(tender.responseSource()).isEqualTo(TenderResponseSource.OPERATOR);
            assertThat(tender.respondedBy()).isEqualTo(PLANNER);
            assertThat(tender.respondedByClient()).isNull();
            assertThat(tender.responseNotes()).isEqualTo("12t confirmed");
        }

        @Test
        @DisplayName("an integration acceptance names a credential and no person")
        void integrationAcceptance() {
            TripTender tender = sent(DEADLINE);

            tender.accept(SENT_AT.plusHours(1), TenderResponseSource.INTEGRATION, null, CLIENT, null);

            assertThat(tender.responseSource()).isEqualTo(TenderResponseSource.INTEGRATION);
            assertThat(tender.respondedBy()).isNull();
            assertThat(tender.respondedByClient()).isEqualTo(CLIENT);
        }

        @Test
        @DisplayName("refuses a source that disagrees with the actor it was given")
        void theSourceAndTheActorMustAgree() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> sent(DEADLINE)
                            .accept(SENT_AT.plusHours(1), TenderResponseSource.OPERATOR, null, CLIENT, null))
                    .withMessageContaining("OPERATOR");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> sent(DEADLINE)
                            .accept(SENT_AT.plusHours(1), TenderResponseSource.INTEGRATION, PLANNER, null, null))
                    .withMessageContaining("INTEGRATION");
        }

        @Test
        @DisplayName("refuses a rejection with no reason")
        void aRefusalAlwaysSaysWhy() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> sent(DEADLINE)
                            .reject(SENT_AT.plusHours(1), TenderResponseSource.OPERATOR, PLANNER, null, "  "))
                    .withMessageContaining("reason");
        }

        @Test
        @DisplayName("refuses an answer dated before the offer was sent")
        void timeOnlyMovesForward() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> sent(DEADLINE)
                            .accept(SENT_AT.minusMinutes(1), TenderResponseSource.OPERATOR, PLANNER, null, null))
                    .withMessageContaining("before it was sent");
        }

        @Test
        @DisplayName("refuses a second answer on an offer that already has one")
        void anAnswerIsFinal() {
            TripTender tender = sent(DEADLINE);
            tender.accept(SENT_AT.plusHours(1), TenderResponseSource.OPERATOR, PLANNER, null, null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> tender.reject(SENT_AT.plusHours(2), TenderResponseSource.OPERATOR, PLANNER,
                            null, "changed our mind"))
                    .withMessageContaining("cannot move from ACCEPTED to REJECTED");
        }
    }

    @Nested
    @DisplayName("expiry")
    class Expiry {

        @Test
        @DisplayName("reports a sent offer past its deadline as expired, before anything is written")
        void effectiveStatusAppliesTheDeadline() {
            TripTender tender = sent(DEADLINE);

            assertThat(tender.effectiveStatus(DEADLINE.minusSeconds(1))).isEqualTo(TenderStatus.SENT);
            assertThat(tender.effectiveStatus(DEADLINE)).isEqualTo(TenderStatus.EXPIRED);
            assertThat(tender.effectiveStatus(DEADLINE.plusHours(1))).isEqualTo(TenderStatus.EXPIRED);
            // ...and the column has not moved. This gap is the whole of migration V31 section 1b.
            assertThat(tender.status()).isEqualTo(TenderStatus.SENT);
        }

        @Test
        @DisplayName("never expires an offer that has no deadline")
        void noDeadlineMeansNoLapse() {
            TripTender tender = sent(null);

            assertThat(tender.hasLapsedAt(SENT_AT.plusYears(1))).isFalse();
            assertThat(tender.effectiveStatus(SENT_AT.plusYears(1))).isEqualTo(TenderStatus.SENT);
        }

        @Test
        @DisplayName("never re-expires an offer that has already been answered")
        void anAnsweredOfferDoesNotLapse() {
            TripTender tender = sent(DEADLINE);
            tender.reject(SENT_AT.plusHours(1), TenderResponseSource.OPERATOR, PLANNER, null, "no truck");

            assertThat(tender.hasLapsedAt(DEADLINE.plusHours(1))).isFalse();
            assertThat(tender.effectiveStatus(DEADLINE.plusHours(1))).isEqualTo(TenderStatus.REJECTED);
        }

        @Test
        @DisplayName("materialises the lapse without naming anybody as its author")
        void expireRecordsWhenItWasResolved() {
            TripTender tender = sent(DEADLINE);

            tender.expire(DEADLINE.plusHours(3), null);

            assertThat(tender.status()).isEqualTo(TenderStatus.EXPIRED);
            // Resolved three hours after it lapsed, and both facts survive: expiresAt says when the
            // offer was due, expiredAt says when TMS noticed.
            assertThat(tender.expiredAt()).isEqualTo(DEADLINE.plusHours(3));
            assertThat(tender.expiresAt()).isEqualTo(DEADLINE);
        }

        @Test
        @DisplayName("refuses to expire an offer that never had a deadline")
        void expiryNeedsADeadline() {
            TripTender tender = sent(null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> tender.expire(SENT_AT.plusYears(1), null))
                    .withMessageContaining("no deadline");
        }
    }

    @Nested
    @DisplayName("withdrawal")
    class Withdrawal {

        @Test
        @DisplayName("withdraws a draft that was never sent")
        void aDraftCanBeDiscarded() {
            TripTender tender = draft(null, null, null);

            tender.cancel("Replanned", PLANNER);

            assertThat(tender.status()).isEqualTo(TenderStatus.CANCELLED);
            assertThat(tender.cancelReason()).isEqualTo("Replanned");
            assertThat(tender.cancelledAt()).isNotNull();
        }

        @Test
        @DisplayName("always carries a reason")
        void aWithdrawalAlwaysSaysWhy() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> sent(DEADLINE).cancel("   ", PLANNER))
                    .withMessageContaining("reason");
        }

        @Test
        @DisplayName("cannot pull back an offer the carrier has already accepted")
        void anAcceptedOfferIsNotWithdrawable() {
            TripTender tender = sent(DEADLINE);
            tender.accept(SENT_AT.plusHours(1), TenderResponseSource.OPERATOR, PLANNER, null, null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> tender.cancel("Too late", PLANNER))
                    .withMessageContaining("cannot move from ACCEPTED to CANCELLED");
        }
    }

    private static TripTender draft(BigDecimal amount, String currency, OffsetDateTime expiresAt) {
        return new TripTender(COMPANY, TRIP, CARRIER, 1, amount, currency, null, expiresAt, PLANNER);
    }

    private static TripTender sent(OffsetDateTime expiresAt) {
        TripTender tender = draft(BigDecimal.valueOf(1240), "PEN", expiresAt);
        tender.send(SENT_AT, PLANNER);
        return tender;
    }
}
