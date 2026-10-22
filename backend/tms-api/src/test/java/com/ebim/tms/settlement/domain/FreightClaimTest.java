package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The freight claim lifecycle (V71), without a database: every rule the CHECKs hold a second time. */
class FreightClaimTest {

    private static final UUID OPENER = UUID.randomUUID();
    private static final UUID DECIDER = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-22T10:00:00-05:00");

    private static FreightClaim claim(String amount) {
        return new FreightClaim(UUID.randomUUID(), "RC-000001",
                new FreightClaim.Subject(UUID.randomUUID(), UUID.randomUUID(), null, null, null),
                new FreightClaim.Terms(ClaimType.DAMAGE, LocalDate.of(2026, 10, 20), " 2 sillas rotas ",
                        new BigDecimal(amount), "usd"),
                OPENER, NOW);
    }

    private static FreightClaim submitted(String amount) {
        FreightClaim claim = claim(amount);
        claim.submit(OPENER, NOW);
        return claim;
    }

    @Test
    @DisplayName("opens OPEN, normalised: trimmed text, upper-case currency, two decimals")
    void opens() {
        FreightClaim claim = claim("45.5");
        assertThat(claim.status()).isEqualTo(ClaimStatus.OPEN);
        assertThat(claim.description()).isEqualTo("2 sillas rotas");
        assertThat(claim.currency()).isEqualTo("USD");
        assertThat(claim.claimedAmount()).isEqualByComparingTo("45.50");
        assertThat(claim.claimedAmount().scale()).isEqualTo(2);
        assertThat(claim.allowedActions())
                .containsExactly(ClaimAction.UPDATE, ClaimAction.SUBMIT, ClaimAction.CANCEL, ClaimAction.ATTACH_EVIDENCE);
    }

    @Test
    @DisplayName("refuses a claim without type, description, a positive amount or an ISO currency")
    void refusesIncompleteTerms() {
        FreightClaim.Subject subject = new FreightClaim.Subject(UUID.randomUUID(), UUID.randomUUID(), null, null, null);
        LocalDate day = LocalDate.of(2026, 10, 20);
        assertThatThrownBy(() -> new FreightClaim(UUID.randomUUID(), "RC-000002", subject,
                new FreightClaim.Terms(null, day, "x", BigDecimal.TEN, "USD"), OPENER, NOW))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new FreightClaim(UUID.randomUUID(), "RC-000002", subject,
                new FreightClaim.Terms(ClaimType.LOSS, day, "  ", BigDecimal.TEN, "USD"), OPENER, NOW))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new FreightClaim(UUID.randomUUID(), "RC-000002", subject,
                new FreightClaim.Terms(ClaimType.LOSS, day, "x", BigDecimal.ZERO, "USD"), OPENER, NOW))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new FreightClaim(UUID.randomUUID(), "RC-000002", subject,
                new FreightClaim.Terms(ClaimType.LOSS, day, "x", BigDecimal.TEN, "US"), OPENER, NOW))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("is edited while OPEN and frozen once submitted")
    void editableUntilSubmitted() {
        FreightClaim claim = claim("10");
        claim.update(new FreightClaim.Terms(ClaimType.SHORTAGE, LocalDate.of(2026, 10, 21), "Faltan 2", new BigDecimal("12"),
                "PEN"));
        assertThat(claim.claimType()).isEqualTo(ClaimType.SHORTAGE);
        claim.submit(OPENER, NOW);
        assertThat(claim.status()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        assertThat(claim.submittedBy()).isEqualTo(OPENER);
        assertThatThrownBy(() -> claim.update(new FreightClaim.Terms(ClaimType.LOSS, LocalDate.of(2026, 10, 21), "x",
                BigDecimal.ONE, "PEN"))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> claim.submit(OPENER, NOW)).isInstanceOf(ConflictException.class);
    }

    @Nested
    @DisplayName("the decision")
    class Decision {

        @Test
        @DisplayName("an acceptance takes the whole claimed amount and its recovery, from somebody else")
        void accept() {
            FreightClaim claim = submitted("80");
            assertThatThrownBy(() -> claim.accept(OPENER, RecoveryMethod.PENALTY, null, NOW))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("cannot accept");
            assertThatThrownBy(() -> claim.accept(DECIDER, null, null, NOW)).isInstanceOf(InvalidRequestException.class);

            claim.accept(DECIDER, RecoveryMethod.PENALTY, null, NOW);
            assertThat(claim.status()).isEqualTo(ClaimStatus.ACCEPTED);
            assertThat(claim.resolution()).isEqualTo(ClaimResolution.ACCEPTED);
            assertThat(claim.acceptedAmount()).isEqualByComparingTo("80");
            assertThat(claim.decidedBy()).isEqualTo(DECIDER);
            assertThat(claim.allowedActions()).containsExactly(ClaimAction.CLOSE, ClaimAction.ATTACH_EVIDENCE);
        }

        @Test
        @DisplayName("a partial agreement is more than zero, less than claimed, and says why")
        void partial() {
            FreightClaim claim = submitted("80");
            assertThatThrownBy(() -> claim.partiallyAccept(DECIDER, new BigDecimal("80"), RecoveryMethod.CREDIT_NOTE,
                    "x", NOW)).isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> claim.partiallyAccept(DECIDER, BigDecimal.ZERO, RecoveryMethod.CREDIT_NOTE,
                    "x", NOW)).isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> claim.partiallyAccept(DECIDER, new BigDecimal("40"), RecoveryMethod.CREDIT_NOTE,
                    " ", NOW)).isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> claim.partiallyAccept(OPENER, new BigDecimal("40"), RecoveryMethod.CREDIT_NOTE,
                    "Acuerdo", NOW)).isInstanceOf(ConflictException.class);

            claim.partiallyAccept(DECIDER, new BigDecimal("40.005"), RecoveryMethod.CREDIT_NOTE, "Acuerdo", NOW);
            assertThat(claim.status()).isEqualTo(ClaimStatus.PARTIALLY_ACCEPTED);
            assertThat(claim.acceptedAmount()).isEqualByComparingTo("40.01");
        }

        @Test
        @DisplayName("a rejection says why, recovers nothing, and may come from the opener")
        void reject() {
            FreightClaim claim = submitted("80");
            assertThatThrownBy(() -> claim.reject(OPENER, null, NOW)).isInstanceOf(InvalidRequestException.class);
            claim.reject(OPENER, "Daño de origen", NOW);
            assertThat(claim.status()).isEqualTo(ClaimStatus.REJECTED);
            assertThat(claim.acceptedAmount()).isNull();
            assertThat(claim.recoveryMethod()).isNull();
        }

        @Test
        @DisplayName("nothing is decided before review")
        void notBeforeReview() {
            FreightClaim claim = claim("80");
            assertThatThrownBy(() -> claim.accept(DECIDER, RecoveryMethod.PENALTY, null, NOW))
                    .isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> claim.reject(DECIDER, "x", NOW)).isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    @DisplayName("closing and cancelling")
    class Ending {

        @Test
        @DisplayName("an accepted claim closes on the document that recovered it; a rejected one on none")
        void close() {
            FreightClaim accepted = submitted("80");
            accepted.accept(DECIDER, RecoveryMethod.PENALTY, null, NOW);
            assertThatThrownBy(() -> accepted.close(DECIDER, null, null, NOW)).isInstanceOf(InvalidRequestException.class);
            UUID invoice = UUID.randomUUID();
            accepted.close(DECIDER, invoice, "Descontado", NOW);
            assertThat(accepted.status()).isEqualTo(ClaimStatus.CLOSED);
            assertThat(accepted.resolution()).isEqualTo(ClaimResolution.ACCEPTED);
            assertThat(accepted.settlementInvoiceId()).isEqualTo(invoice);
            assertThat(accepted.allowedActions()).isEmpty();

            FreightClaim rejected = submitted("80");
            rejected.reject(DECIDER, "No procede", NOW);
            assertThatThrownBy(() -> rejected.close(DECIDER, UUID.randomUUID(), null, NOW))
                    .isInstanceOf(InvalidRequestException.class);
            rejected.close(DECIDER, null, null, NOW);
            assertThat(rejected.status()).isEqualTo(ClaimStatus.CLOSED);
        }

        @Test
        @DisplayName("only an undecided claim is cancelled, with a reason; a closed one takes no evidence")
        void cancel() {
            FreightClaim open = claim("80");
            assertThatThrownBy(() -> open.cancel(" ")).isInstanceOf(InvalidRequestException.class);
            open.cancel("Duplicado");
            assertThat(open.status()).isEqualTo(ClaimStatus.CANCELLED);
            assertThatThrownBy(open::requireAcceptsEvidence).isInstanceOf(ConflictException.class);

            FreightClaim decided = submitted("80");
            decided.reject(DECIDER, "No", NOW);
            assertThatThrownBy(() -> decided.cancel("x")).isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> claim("1").close(DECIDER, null, null, NOW)).isInstanceOf(ConflictException.class);
        }
    }
}
