package com.ebim.tms.planning.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The trip lifecycle, planning and execution, and <em>the</em> definition of which moves between
 * its states are legal (migrations V11 and V25).
 *
 * <pre>
 *   DRAFT -&gt; CONFIRMED -&gt; READY_FOR_DISPATCH -&gt; IN_TRANSIT -&gt; COMPLETED
 *                \_____________/____________/
 *                       \-&gt; CANCELLED
 * </pre>
 *
 * <ul>
 *   <li>{@link #DRAFT} - the trip's run is open. The only state in which vehicle, assignments and
 *       stops may change.</li>
 *   <li>{@link #CONFIRMED} - the plan is binding and the capacity it was validated against is
 *       frozen onto the trip. Nothing about <em>what</em> it carries changes from here on.</li>
 *   <li>{@link #READY_FOR_DISPATCH} - loaded, documented, waiting for the driver.</li>
 *   <li>{@link #IN_TRANSIT} - the vehicle has left; {@code actualDepartureAt} says when.</li>
 *   <li>{@link #COMPLETED} - terminal. The trip finished and the vehicle is released.</li>
 *   <li>{@link #CANCELLED} - terminal. Reachable from every state before departure; cancelling a
 *       trip releases its orders back to the eligible pool.</li>
 * </ul>
 *
 * <p><b>Why the transition table lives here.</b> The rule "a trip may only be dispatched from
 * {@code READY_FOR_DISPATCH}" is a fact about the domain, not about a service method or an HTTP
 * verb, and it has to be answerable without a database or a Spring context - which is what makes
 * it unit-testable and what stops a second caller (a future mobile driver app, a batch job)
 * re-deriving a slightly different version of it. {@code TripExecutionService} consults
 * {@link #canTransitionTo} for its caller-facing refusals and {@link Trip} asserts it again as a
 * last line of defense, the same two-layer shape {@code Trip.assertStopSequenceIntegrity} uses.
 *
 * <p><b>Why {@code DISPATCHED} is not a state.</b> Dispatching <em>is</em> the departure: a
 * DISPATCHED row and an IN_TRANSIT row would carry exactly the same columns with nothing to tell
 * them apart, which is a state that exists only to be named. See migration V25's header.
 *
 * <p>There is no move back. A trip that left and should not have is cancelled before it leaves or
 * completed after; V1 has no un-dispatch, because undoing a departure would mean the actual times
 * are editable and the whole point of them is that they are not.
 */
public enum TripStatus {
    DRAFT,
    CONFIRMED,
    READY_FOR_DISPATCH,
    IN_TRANSIT,
    COMPLETED,
    CANCELLED;

    /**
     * The states in which the plan is binding: the trip has been confirmed and carries the frozen
     * capacity snapshot it was validated against. Mirrors migration V25's
     * {@code ck_trip_confirmed_is_complete}.
     */
    private static final Set<TripStatus> COMMITTED =
            EnumSet.of(CONFIRMED, READY_FOR_DISPATCH, IN_TRANSIT, COMPLETED);

    private static final Set<TripStatus> TERMINAL = EnumSet.of(COMPLETED, CANCELLED);

    private static final Map<TripStatus, Set<TripStatus>> TRANSITIONS = Map.of(
            DRAFT, EnumSet.of(CONFIRMED, CANCELLED),
            CONFIRMED, EnumSet.of(READY_FOR_DISPATCH, CANCELLED),
            READY_FOR_DISPATCH, EnumSet.of(IN_TRANSIT, CANCELLED),
            // No CANCELLED: once the vehicle has left, "this trip did not happen" is not true any
            // more. An aborted run is completed and reported on; representing it properly needs a
            // per-stop delivery outcome, which V1 deliberately does not have (migration V25).
            IN_TRANSIT, EnumSet.of(COMPLETED),
            COMPLETED, EnumSet.noneOf(TripStatus.class),
            CANCELLED, EnumSet.noneOf(TripStatus.class));

    /** Whether {@code target} may be reached from this state. Reflexive moves are not transitions. */
    public boolean canTransitionTo(TripStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    /** The states reachable from this one, for a UI that wants to render only the buttons that work. */
    public Set<TripStatus> allowedTransitions() {
        return Set.copyOf(TRANSITIONS.get(this));
    }

    /** The one state in which what a trip carries may still change. */
    public boolean allowsPlanningEdits() {
        return this == DRAFT;
    }

    /**
     * Whether the plan is binding here - confirmed, and therefore carrying a capacity snapshot,
     * publishable to a partner and locked against assignment edits.
     */
    public boolean isCommitted() {
        return COMMITTED.contains(this);
    }

    /** Whether the trip is being operated right now: confirmed, not yet finished, not cancelled. */
    public boolean isInExecution() {
        return this == CONFIRMED || this == READY_FOR_DISPATCH || this == IN_TRANSIT;
    }

    /** Whether nothing further can happen to the trip. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
