package com.ebim.tms.settlement.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Where a carrier's invoice is in the freight audit (migration V46).
 *
 * <p>A transition table, not a set of labels. The rule that makes it worth having is stated
 * negatively: <b>there is no path from a discrepancy to approval that does not pass through a
 * person looking at it.</b> An invoice whose figures disagree with the shipment's cannot become
 * payable by any sequence of legal moves that skips {@link #UNDER_REVIEW}.
 *
 * <p>Modelled the same way {@code TripStatus} and {@code AppointmentStatus} are, for the same
 * reason: the states a thing may move to next belong beside the states themselves, so a screen
 * renders what the server allows rather than guessing.
 */
public enum InvoiceStatus {

    /** Keyed by a person or posted by a partner. Nothing has been compared yet. */
    RECEIVED,

    /**
     * Matching is running.
     *
     * <p>Its own state rather than an instant, because matching reads several shipments and a
     * reader who refreshes mid-run must see something true rather than the previous verdict.
     */
    MATCHING,

    /** The invoice agrees with what TMS expected, within tolerance. */
    MATCHED,

    /** It does not, and nobody has looked yet. */
    DISCREPANCY,

    /** Somebody is working it. */
    UNDER_REVIEW,

    /** A person authorised the obligation. */
    APPROVED,

    /** A person refused it, with a reason. */
    REJECTED,

    /** Handed to whoever pays. Terminal. */
    EXPORTED;

    private static final Map<InvoiceStatus, Set<InvoiceStatus>> TRANSITIONS = Map.of(
            RECEIVED, EnumSet.of(MATCHING, REJECTED),
            // Re-matching is legal from MATCHING itself: a run that read a shipment mid-costing is
            // re-run rather than unwound.
            MATCHING, EnumSet.of(MATCHING, MATCHED, DISCREPANCY, REJECTED),
            // A clean match may be approved directly. It may also be re-matched - a cost corrected
            // after the fact must be able to change the verdict.
            MATCHED, EnumSet.of(MATCHING, APPROVED, REJECTED),
            // THE rule. A discrepancy goes to a person or is refused. It does NOT go to APPROVED.
            DISCREPANCY, EnumSet.of(UNDER_REVIEW, MATCHING, REJECTED),
            UNDER_REVIEW, EnumSet.of(APPROVED, REJECTED, MATCHING),
            APPROVED, EnumSet.of(EXPORTED, REJECTED),
            // Terminal both ways. A rejected invoice is re-received as a new document, because a
            // carrier who disagrees issues a credit note and a new number - they do not edit the
            // one that was refused.
            REJECTED, EnumSet.noneOf(InvoiceStatus.class),
            EXPORTED, EnumSet.noneOf(InvoiceStatus.class));

    /** The states this one may move to. Empty for a terminal state. */
    public Set<InvoiceStatus> allowedTransitions() {
        return TRANSITIONS.getOrDefault(this, EnumSet.noneOf(InvoiceStatus.class));
    }

    public boolean canTransitionTo(InvoiceStatus target) {
        return allowedTransitions().contains(target);
    }

    /**
     * Whether an invoice in this state may be handed to whoever pays.
     *
     * <p>Only {@link #APPROVED}. Asked as its own question rather than read off the transition
     * table so that "may this be exported" has one answer in one place, and so that widening the
     * table by accident cannot widen this.
     */
    public boolean isExportable() {
        return this == APPROVED;
    }

    /** Whether the invoice can still be edited - its lines, its total, its dates. */
    public boolean isEditable() {
        return this == RECEIVED || this == DISCREPANCY || this == UNDER_REVIEW;
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
