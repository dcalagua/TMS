package com.ebim.tms.planning.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The catalogue of operational problems a trip can hit. Mirrors {@code ck_trip_exception_type}
 * (migration V27).
 *
 * <p><b>Operational, never technical.</b> A rejected payload, a failed migration or a 500 is not
 * one of these: those belong to logs, {@code tms.integration_request} and the error handler.
 * Mixing the two vocabularies would produce a dispatcher's screen full of stack traces.
 *
 * <p><b>Small on purpose.</b> Seven values that cover what a delivery day throws at a fleet, with
 * {@link #OTHER} as an honest escape hatch rather than a dumping ground - it requires notes, so
 * choosing it costs a sentence and choosing a typed value does not. That asymmetry is the only
 * thing that keeps a catalogue like this from collapsing into a single value.
 */
public enum TripExceptionType {

    /** Held up on the road. A trip-level problem by default; may name the stop it was heading to. */
    TRAFFIC_DELAY,

    /** The vehicle cannot continue. Trip-level: it is not a statement about any one delivery. */
    VEHICLE_BREAKDOWN,

    /** Nobody there, or outside the hours the customer actually keeps. */
    CUSTOMER_CLOSED,

    /** The customer refused the goods. Different from {@link #DELIVERY_FAILED}: someone decided. */
    DELIVERY_REJECTED,

    /** The address on the order does not lead anywhere the driver could deliver to. */
    ADDRESS_NOT_FOUND,

    /** Attempted and not completed for a reason none of the above names exactly. */
    DELIVERY_FAILED,

    /** Anything else. Requires notes - see the class comment. */
    OTHER;

    /**
     * The four types that are statements about a delivery rather than about a journey, and which
     * are therefore meaningless without the stop they are about. Mirrors
     * {@code ck_trip_exception_stop_scope}.
     */
    private static final Set<TripExceptionType> STOP_SCOPED =
            EnumSet.of(CUSTOMER_CLOSED, DELIVERY_REJECTED, ADDRESS_NOT_FOUND, DELIVERY_FAILED);

    /** Whether reporting this type without naming a stop is a contradiction. */
    public boolean requiresStop() {
        return STOP_SCOPED.contains(this);
    }

    /** Whether the type says so little on its own that a sentence of explanation is mandatory. */
    public boolean requiresNotes() {
        return this == OTHER;
    }
}
