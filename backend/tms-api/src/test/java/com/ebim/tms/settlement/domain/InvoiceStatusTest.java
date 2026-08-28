package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("an exported or rejected invoice is finished")
    void terminalStates() {
        assertThat(InvoiceStatus.EXPORTED.isTerminal()).isTrue();
        assertThat(InvoiceStatus.REJECTED.isTerminal()).isTrue();
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
    @DisplayName("every state can be refused, except the ones already finished")
    void everythingLiveCanBeRejected() {
        assertThat(Arrays.stream(InvoiceStatus.values())
                .filter(status -> !status.isTerminal())
                .filter(status -> !status.canTransitionTo(InvoiceStatus.REJECTED))
                .toList())
                .isEmpty();
    }
}
