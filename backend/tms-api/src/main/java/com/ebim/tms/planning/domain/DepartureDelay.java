package com.ebim.tms.planning.domain;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * The departure-delay rule, as one pure function over three instants.
 *
 * <p>It lives in {@code domain} and not in the control tower's service for the reason
 * {@link TripStatus}'s transition table does: "this shipment is running late" is a fact about the
 * domain, it has to be answerable without a database or a Spring context, and the moment a second
 * caller needs it - a report, an alert job, a partner API - a copy in a service would start
 * drifting from this one. It is also the only part of the control tower that can be unit-tested
 * without infrastructure, which is where the risk actually is.
 *
 * <p><b>Planned versus actual, and nothing else.</b> {@link Trip#plannedDepartureAt()} is what the
 * plan asked for and {@link Trip#actualDepartureAt()} is what happened; migration V25 keeps them in
 * separate columns precisely so the gap between them survives. Where the second is missing the rule
 * falls back to the current time, which is not an estimate of the departure - it is the statement
 * "this has not happened and was due", which is a different and equally checkable fact.
 *
 * <p><b>No grace period in V1.</b> A tolerance ("late means more than fifteen minutes late") is a
 * commercial policy, and no customer has stated one. Inventing a default would quietly reclassify
 * real lateness as punctuality in every report built on top of this. Instead {@link #minutes} is
 * always reported, so a screen can show {@code +3 min} and {@code +95 min} differently and an
 * operator judges the threshold. When a policy does arrive it belongs here, beside the rule it
 * changes, not in the six screens that ask the question.
 *
 * @param timeliness which of the six situations this trip is in
 * @param minutes    how many minutes late, truncated towards zero: the gap to the actual departure
 *                   when it left late, the gap to now when it is overdue and still has not left,
 *                   and null in every other case. Never negative - "early" is reported as
 *                   {@link DepartureTimeliness#ON_TIME}, because no operational decision is made
 *                   differently for a truck that left ten minutes early
 */
public record DepartureDelay(DepartureTimeliness timeliness, Long minutes) {

    /**
     * The states in which a departure is still ahead of the trip, and therefore the states in which
     * "planned time passed, still here" means {@link DepartureTimeliness#OVERDUE}.
     *
     * <p>{@link TripStatus#DRAFT} is deliberately one of them. A trip planned to leave at 08:00
     * that is still a draft at 09:30 is the worst version of late - nobody has even committed to
     * it - and excluding drafts would let the one case that most needs a dispatcher fall off the
     * board. Public so the control tower's aggregate count and this per-row verdict are driven by
     * the same set instead of two lists that can drift.
     */
    public static final Set<TripStatus> AWAITING_DEPARTURE =
            Set.copyOf(EnumSet.of(TripStatus.DRAFT, TripStatus.CONFIRMED, TripStatus.READY_FOR_DISPATCH));

    /**
     * Judges one trip.
     *
     * @param now the instant to judge an un-departed trip against - passed in rather than read
     *     from the clock so that every row of one control tower response is judged against the
     *     same instant, and so this stays testable
     */
    public static DepartureDelay of(TripStatus status, OffsetDateTime plannedDepartureAt,
            OffsetDateTime actualDepartureAt, OffsetDateTime now) {
        if (status == TripStatus.CANCELLED) {
            return new DepartureDelay(DepartureTimeliness.NOT_APPLICABLE, null);
        }
        if (plannedDepartureAt == null) {
            return new DepartureDelay(DepartureTimeliness.NOT_SCHEDULED, null);
        }
        if (actualDepartureAt != null) {
            return actualDepartureAt.isAfter(plannedDepartureAt)
                    ? new DepartureDelay(DepartureTimeliness.LATE,
                            minutesBetween(plannedDepartureAt, actualDepartureAt))
                    : new DepartureDelay(DepartureTimeliness.ON_TIME, null);
        }
        // Departed with no departure time recorded cannot happen through Trip.dispatch, which
        // writes one in the same transition that sets IN_TRANSIT. Answering NOT_APPLICABLE rather
        // than measuring against now() keeps a data anomaly from being displayed as a growing
        // delay on a truck that is already out.
        if (!AWAITING_DEPARTURE.contains(status)) {
            return new DepartureDelay(DepartureTimeliness.NOT_APPLICABLE, null);
        }
        return now.isAfter(plannedDepartureAt)
                ? new DepartureDelay(DepartureTimeliness.OVERDUE, minutesBetween(plannedDepartureAt, now))
                : new DepartureDelay(DepartureTimeliness.SCHEDULED, null);
    }

    public boolean isDelayed() {
        return timeliness.isDelayed();
    }

    private static long minutesBetween(OffsetDateTime from, OffsetDateTime to) {
        return Duration.between(from, to).toMinutes();
    }
}
