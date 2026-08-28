package com.ebim.tms.shared.reference;

/**
 * How a {@link TravelEstimate}'s number was <em>produced</em> (migration V38).
 *
 * <p><b>Not where it was read from.</b> That is {@link TravelEstimate#servedFromCache()}, and the
 * two are deliberately separate values because conflating them hides the thing that matters: a
 * straight-line estimate served out of the cache is still a straight-line estimate, and a single
 * field that said "CACHE" would launder it into looking measured the moment it was stored. The
 * first version of this enum had exactly that defect and the smoke run caught it.
 *
 * <p>Carried on every answer rather than logged, because the two are not interchangeable to a
 * reader: a planner comparing proposals, a controller explaining a per-km charge and an operator
 * reading an ETA all need to know whether the figure was computed for these two points or estimated
 * from the line between them.
 */
public enum RoutingSource {

    /** A routing provider was asked and answered. */
    PROVIDER,

    /**
     * Estimated from the coordinates themselves, because no provider was available.
     *
     * <p>Not an error and not a degraded mode to hide: with no vendor adapter configured this is
     * the ordinary case, and an estimate that says it is an estimate is more useful than a refusal.
     * It is counted as its own metric so "how much of tonight's plan rests on straight lines" is a
     * question with an answer.
     */
    FALLBACK
}
