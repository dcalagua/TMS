package com.ebim.tms.planning.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle of one attempt to place a shipment with a carrier, and <em>the</em> definition of
 * which moves between its states are legal (migration V31).
 *
 * <pre>
 *   DRAFT -&gt; SENT -&gt; ACCEPTED
 *     |        \---&gt; REJECTED
 *     |        \---&gt; EXPIRED
 *     \--------\---&gt; CANCELLED
 * </pre>
 *
 * <ul>
 *   <li>{@link #DRAFT} - prepared and not yet offered. The only state in which the offer's terms
 *       may still be edited, and the only reason this state exists.</li>
 *   <li>{@link #SENT} - the carrier has been told. Frozen: amount, deadline and instructions are
 *       what they were shown.</li>
 *   <li>{@link #ACCEPTED} - terminal. At most one per trip, ever
 *       ({@code uq_trip_tender_accepted}).</li>
 *   <li>{@link #REJECTED} - terminal, and always carries the carrier's reason.</li>
 *   <li>{@link #EXPIRED} - terminal. The deadline passed with no answer.</li>
 *   <li>{@link #CANCELLED} - terminal. The shipper withdrew the offer, or the shipment stopped
 *       being offerable underneath it.</li>
 * </ul>
 *
 * <p><b>Why DRAFT is a state and not a flag.</b> The test migration V25 applied to {@code
 * DISPATCHED} - "would the two rows differ by anything but a name?" - is the one DRAFT passes and
 * {@code DISPATCHED} failed. A draft tender is editable and publishes nothing; a sent one is frozen
 * and has left a row in the outbox that a carrier can already read. Those are different rows with
 * different rules, not one row with two labels.
 *
 * <p><b>Why there is no move back.</b> A rejection is never edited into an acceptance and an expiry
 * is never revived. A carrier who changes their mind is a <em>second attempt</em>, recorded as one,
 * because the fact that they first said no is exactly the history this table exists to keep.
 *
 * <p><b>Expiry is asked, not stored.</b> {@link #isLive()} answers whether an offer is still open in
 * principle; whether <em>this</em> offer has lapsed depends on its deadline, which is
 * {@code TripTender.effectiveStatus}'s question because a status enum cannot see a timestamp.
 * Migration V31, section 1b, explains why the stored value can lag the effective one and what that
 * costs.
 */
public enum TenderStatus {
    DRAFT,
    SENT,
    ACCEPTED,
    REJECTED,
    EXPIRED,
    CANCELLED;

    /** The states in which nothing further can happen to the attempt. */
    private static final Set<TenderStatus> TERMINAL = EnumSet.of(ACCEPTED, REJECTED, EXPIRED, CANCELLED);

    /**
     * The states that occupy a trip's single live slot ({@code uq_trip_tender_live}) - a trip may
     * have one of these at a time and any number of terminal ones behind it.
     */
    private static final Set<TenderStatus> LIVE = EnumSet.of(DRAFT, SENT);

    private static final Map<TenderStatus, Set<TenderStatus>> TRANSITIONS = Map.of(
            // A draft is sent or discarded. It cannot expire: nothing is running against it, which
            // is why ck_trip_tender_expired_had_deadline can require a deadline for an expiry.
            DRAFT, EnumSet.of(SENT, CANCELLED),
            SENT, EnumSet.of(ACCEPTED, REJECTED, EXPIRED, CANCELLED),
            ACCEPTED, EnumSet.noneOf(TenderStatus.class),
            REJECTED, EnumSet.noneOf(TenderStatus.class),
            EXPIRED, EnumSet.noneOf(TenderStatus.class),
            CANCELLED, EnumSet.noneOf(TenderStatus.class));

    /** Whether {@code target} may be reached from this state. Reflexive moves are not transitions. */
    public boolean canTransitionTo(TenderStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    /** The states reachable from this one, so a screen renders only the buttons that work. */
    public Set<TenderStatus> allowedTransitions() {
        return Set.copyOf(TRANSITIONS.get(this));
    }

    /** Whether this attempt still occupies the trip's live slot. */
    public boolean isLive() {
        return LIVE.contains(this);
    }

    /** The one state in which the offer's terms may still be edited. */
    public boolean allowsTermEdits() {
        return this == DRAFT;
    }

    /** Whether the carrier can still answer: sent, and not yet answered any other way. */
    public boolean awaitsResponse() {
        return this == SENT;
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
