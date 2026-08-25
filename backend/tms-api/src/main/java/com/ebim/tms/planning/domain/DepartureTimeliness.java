package com.ebim.tms.planning.domain;

/**
 * How a shipment's departure compares to the departure that was planned for it - the control
 * tower's vocabulary for "is this one late".
 *
 * <p><b>Six values and not a boolean.</b> "Delayed" collapses three different situations a
 * dispatcher acts on differently: a truck that left forty minutes late is a report, a truck that
 * has not left and was due an hour ago is a phone call, and a truck with no planned departure at
 * all is a planning gap. A boolean would answer all three with the same red dot, and the two that
 * are not yet facts would have to be invented by whoever renders it.
 *
 * <p><b>Nothing here is a heuristic.</b> Every value is decided by comparing two recorded instants
 * - {@code trip.planned_departure_at} against {@code trip.actual_departure_at}, or against the
 * current time when the vehicle has not left. There is no grace period, no "probably late", and no
 * inference from a stop's progress: a shipment that departed one minute after its plan is
 * {@link #LATE} by one minute, and the screen shows the minute so a person, not this enum, decides
 * whether one minute matters. See {@link DepartureDelay} for the rule itself.
 */
public enum DepartureTimeliness {

    /**
     * The trip was cancelled, so there is no departure to judge. Distinct from
     * {@link #NOT_SCHEDULED}: this one was never going to leave, that one still might.
     */
    NOT_APPLICABLE,

    /**
     * No planned departure is on file, so nothing can be compared. A draft trip is allowed to be
     * here - a departure is only required at confirmation - which is exactly why this is worth
     * showing rather than silently treating as on time.
     */
    NOT_SCHEDULED,

    /** Planned, not departed, and its planned instant has not arrived yet. The normal morning. */
    SCHEDULED,

    /**
     * Planned, not departed, and its planned instant has passed. The only value that changes on
     * its own as the clock moves, which is why the server stamps the instant it judged against
     * ({@code ControlTowerView.generatedAt}) rather than letting a stale browser tab decide.
     */
    OVERDUE,

    /** Departed at or before the planned instant. */
    ON_TIME,

    /** Departed after the planned instant, by {@code DepartureDelay.minutes}. */
    LATE;

    /** The two values a control tower counts as a problem: it left late, or it has not left. */
    public boolean isDelayed() {
        return this == LATE || this == OVERDUE;
    }
}
