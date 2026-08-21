package com.ebim.tms.planning.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * What happened at one {@link TripStop}, and <em>the</em> definition of which moves between those
 * outcomes are legal (migration V27).
 *
 * <pre>
 *   PENDING -&gt; ARRIVED -&gt; IN_SERVICE -&gt; COMPLETED
 *      |          |   \________________-&gt; FAILED
 *      |          \-&gt; COMPLETED
 *      \-&gt; SKIPPED
 *      \-&gt; FAILED
 * </pre>
 *
 * <ul>
 *   <li>{@link #PENDING} - nobody has touched this stop. Every stop starts here, including every
 *       stop that existed before V27.</li>
 *   <li>{@link #ARRIVED} - the vehicle is at the destination.</li>
 *   <li>{@link #IN_SERVICE} - loading or unloading has started. Optional: a company that does not
 *       record service starts goes straight from {@link #ARRIVED} to {@link #COMPLETED}.</li>
 *   <li>{@link #COMPLETED} - served and left. Terminal.</li>
 *   <li>{@link #SKIPPED} - never attempted, by decision. Terminal, and carries no actual times at
 *       all - that is what distinguishes it from {@link #FAILED}.</li>
 *   <li>{@link #FAILED} - attempted and not served: refused at the dock, address not found. May
 *       carry an arrival or not, because "we got there and were turned away" and "we never found
 *       it" are both failures. Terminal.</li>
 * </ul>
 *
 * <p><b>Why the transition table lives here.</b> Same reason {@link TripStatus}'s does: "a stop
 * may only be completed once it has been arrived at" is a fact about the domain, answerable
 * without a database or a Spring context, and a second caller must not be able to re-derive a
 * slightly different version of it. {@code TripStopExecutionService} consults
 * {@link #canTransitionTo} for its caller-facing refusals; {@link TripStop} asserts it again as a
 * last line of defense.
 *
 * <p><b>Why there is no reopen.</b> The three terminal outcomes are terminal, exactly as
 * {@link TripStatus#COMPLETED} is. A stop closed by mistake is a correction, and a correction that
 * silently rewrote the actual times would defeat the point of recording them; V1 answers it with
 * an exception on the trip, which is a record of the mistake rather than an erasure of it.
 */
public enum StopExecutionStatus {
    PENDING,
    ARRIVED,
    IN_SERVICE,
    COMPLETED,
    SKIPPED,
    FAILED;

    /**
     * The outcomes that close a stop out. "Resolved" and not "terminal" because that is the
     * question the trip asks about them: a trip may only be completed once every stop has one of
     * these, whatever it is - see {@code TripExecutionService.complete}.
     */
    private static final Set<StopExecutionStatus> RESOLVED = EnumSet.of(COMPLETED, SKIPPED, FAILED);

    private static final Map<StopExecutionStatus, Set<StopExecutionStatus>> TRANSITIONS = Map.of(
            // PENDING -> FAILED is legal and is not a duplicate of SKIPPED: a driver who reaches
            // the street and cannot find the address never "arrived", but did attempt the stop.
            PENDING, EnumSet.of(ARRIVED, SKIPPED, FAILED),
            // ARRIVED -> COMPLETED keeps IN_SERVICE optional - see the class comment.
            ARRIVED, EnumSet.of(IN_SERVICE, COMPLETED, FAILED),
            IN_SERVICE, EnumSet.of(COMPLETED, FAILED),
            COMPLETED, EnumSet.noneOf(StopExecutionStatus.class),
            SKIPPED, EnumSet.noneOf(StopExecutionStatus.class),
            FAILED, EnumSet.noneOf(StopExecutionStatus.class));

    /** Whether {@code target} may be reached from this outcome. Reflexive moves are not transitions. */
    public boolean canTransitionTo(StopExecutionStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    /** The outcomes reachable from this one, for a UI that renders only the buttons that work. */
    public Set<StopExecutionStatus> allowedTransitions() {
        return Set.copyOf(TRANSITIONS.get(this));
    }

    /** Whether this stop has been dealt with, however it ended. */
    public boolean isResolved() {
        return RESOLVED.contains(this);
    }

    /** Whether the stop still needs somebody to do something about it. */
    public boolean isOutstanding() {
        return !isResolved();
    }

    /**
     * Whether reaching this outcome requires a typed reason on file. Both non-delivery outcomes
     * do: a stop that was not served without a reason is the exact gap this module exists to
     * close, and {@code TripStopExecutionService} opens a {@link TripException} for it in the same
     * transaction.
     */
    public boolean requiresException() {
        return this == SKIPPED || this == FAILED;
    }
}
