package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The invoice lifecycle (migration V46).
 *
 * <p>One rule is worth more than the rest and is asserted from several directions:
 * <b>a discrepancy cannot become payable without a person looking at it.</b> There is no sequence of
 * legal transitions from {@link InvoiceStatus#DISCREPANCY} to {@link InvoiceStatus#APPROVED} that
 * skips {@link InvoiceStatus#UNDER_REVIEW}.
 */
class InvoiceStatusTest {

    @Test
    @DisplayName("a discrepancy cannot be approved directly - a person has to look first")
    void discrepancyCannotBeApprovedDirectly() {
        assertThat(InvoiceStatus.DISCREPANCY.canTransitionTo(InvoiceStatus.APPROVED)).isFalse();
        assertThat(InvoiceStatus.DISCREPANCY.canTransitionTo(InvoiceStatus.EXPORTED)).isFalse();
        assertThat(InvoiceStatus.DISCREPANCY.allowedTransitions())
                .containsExactlyInAnyOrder(InvoiceStatus.UNDER_REVIEW, InvoiceStatus.MATCHING,
                        InvoiceStatus.REJECTED);
    }

    @Test
    @DisplayName("review is the only way a disputed invoice reaches approval")
    void reviewIsTheOnlyRoute() {
        assertThat(InvoiceStatus.UNDER_REVIEW.canTransitionTo(InvoiceStatus.APPROVED)).isTrue();
    }

    @Test
    @DisplayName("a clean match may be approved without review - there is nothing to review")
    void cleanMatchGoesStraightToApproval() {
        assertThat(InvoiceStatus.MATCHED.canTransitionTo(InvoiceStatus.APPROVED)).isTrue();
    }

    /**
     * Asked as its own question rather than read off the transition table, so widening the table by
     * accident cannot widen what may be handed to whoever pays.
     */
    @Test
    @DisplayName("only an approved invoice is exportable")
    void onlyApprovedIsExportable() {
        assertThat(Arrays.stream(InvoiceStatus.values()).filter(InvoiceStatus::isExportable).toList())
                .containsExactly(InvoiceStatus.APPROVED);
    }

    @Test
    @DisplayName("nothing may be exported from a state that is not approved")
    void nothingElseReachesExport() {
        assertThat(Arrays.stream(InvoiceStatus.values())
                .filter(status -> status != InvoiceStatus.APPROVED)
                .filter(status -> status.canTransitionTo(InvoiceStatus.EXPORTED))
                .toList())
                .isEmpty();
    }

    @Test
    @DisplayName("a paid or rejected invoice is finished; an exported one is not since V67")
    void terminalStates() {
        assertThat(InvoiceStatus.PAID.isTerminal()).isTrue();
        assertThat(InvoiceStatus.REJECTED.isTerminal()).isTrue();
        assertThat(InvoiceStatus.EXPORTED.isTerminal()).isFalse();
        assertThat(Arrays.stream(InvoiceStatus.values()).filter(InvoiceStatus::isTerminal).toList())
                .containsExactlyInAnyOrder(InvoiceStatus.PAID, InvoiceStatus.REJECTED);
    }

    /** ADR-023 section 2, the table as written there, row by row. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "EXPORTED,       SCHEDULED PARTIALLY_PAID PAID ON_HOLD",
            "SCHEDULED,      PARTIALLY_PAID PAID ON_HOLD",
            "PARTIALLY_PAID, PARTIALLY_PAID PAID ON_HOLD",
            "ON_HOLD,        SCHEDULED PARTIALLY_PAID PAID",
            "PAID,           ''"})
    @DisplayName("the payment states move exactly as ADR-023 section 2 says")
    void paymentTransitions(InvoiceStatus from, String to) {
        java.util.Set<InvoiceStatus> expected = to.isBlank() ? java.util.Set.of()
                : Arrays.stream(to.trim().split("\\s+")).map(InvoiceStatus::valueOf)
                        .collect(java.util.stream.Collectors.toSet());
        assertThat(from.allowedTransitions()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("nothing goes back to EXPORTED or APPROVED, and nothing reaches a payment state before the export")
    void paymentStatesFollowTheExport() {
        java.util.Set<InvoiceStatus> paymentStates = java.util.EnumSet.of(InvoiceStatus.SCHEDULED,
                InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.PAID, InvoiceStatus.ON_HOLD);
        for (InvoiceStatus status : InvoiceStatus.values()) {
            if (status != InvoiceStatus.APPROVED) {
                assertThat(status.canTransitionTo(InvoiceStatus.EXPORTED)).as(status + " -> EXPORTED").isFalse();
            }
            if (!paymentStates.contains(status) && status != InvoiceStatus.EXPORTED) {
                assertThat(status.allowedTransitions()).as(status.name()).doesNotContainAnyElementsOf(paymentStates);
            }
            if (paymentStates.contains(status) || status == InvoiceStatus.EXPORTED) {
                assertThat(status.canTransitionTo(InvoiceStatus.APPROVED)).isFalse();
                assertThat(status.canTransitionTo(InvoiceStatus.REJECTED)).isFalse();
            }
        }
    }

    @Test
    @DisplayName("a payment is recorded only after the export and never once paid")
    void acceptsPaymentOnlyAfterTheExport() {
        assertThat(Arrays.stream(InvoiceStatus.values()).filter(InvoiceStatus::acceptsPayment).toList())
                .containsExactlyInAnyOrder(InvoiceStatus.EXPORTED, InvoiceStatus.SCHEDULED,
                        InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.ON_HOLD);
    }

    @Test
    @DisplayName("a paid invoice is still an authorised one: it keeps consuming its accrual and netting its notes")
    void authorisedIncludesThePaymentStates() {
        assertThat(InvoiceStatus.AUTHORISED).containsExactlyInAnyOrder(InvoiceStatus.APPROVED, InvoiceStatus.EXPORTED,
                InvoiceStatus.SCHEDULED, InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.PAID, InvoiceStatus.ON_HOLD);
        assertThat(InvoiceStatus.OPEN_PAYABLE).doesNotContain(InvoiceStatus.PAID);
    }

    /**
     * An invoice stops being editable the moment somebody has decided on it. Editing a line under
     * an approval would change what was authorised without re-authorising it.
     */
    @Test
    @DisplayName("an approved, exported or rejected invoice can no longer be edited")
    void decidedInvoicesAreFrozen() {
        assertThat(InvoiceStatus.APPROVED.isEditable()).isFalse();
        assertThat(InvoiceStatus.EXPORTED.isEditable()).isFalse();
        assertThat(InvoiceStatus.REJECTED.isEditable()).isFalse();
        assertThat(InvoiceStatus.RECEIVED.isEditable()).isTrue();
        assertThat(InvoiceStatus.DISCREPANCY.isEditable()).isTrue();
    }

    @Test
    @DisplayName("every state before the export can be refused, except the ones already finished")
    void everythingLiveCanBeRejected() {
        assertThat(Arrays.stream(InvoiceStatus.values())
                .filter(status -> !status.isTerminal())
                // Handed over: what follows is the ERP's payment, not a refusal (V67).
                .filter(status -> status != InvoiceStatus.EXPORTED && !status.acceptsPayment())
                .filter(status -> !status.canTransitionTo(InvoiceStatus.REJECTED))
                .toList())
                .isEmpty();
    }
}
