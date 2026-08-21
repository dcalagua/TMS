package com.ebim.tms.planning.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a {@link TransportEvent} records. Mirrors {@code ck_transport_event_type} (migration V27).
 *
 * <p>Two families and one pair that belongs to both:
 *
 * <ul>
 *   <li><b>Trip-level</b> - {@link #TRIP_CONFIRMED}, {@link #TRIP_READY}, {@link #TRIP_DISPATCHED},
 *       {@link #TRIP_COMPLETED}, {@link #TRIP_CANCELLED}. Written by {@code ShipmentEventPublisher}
 *       alongside the outbox row and the audit row, so the three cannot drift.</li>
 *   <li><b>Stop-level</b> - {@link #ARRIVED_AT_STOP}, {@link #SERVICE_STARTED},
 *       {@link #STOP_COMPLETED}, {@link #STOP_SKIPPED}, {@link #STOP_FAILED}. Written by
 *       {@code TripStopExecutionService}, one per transition of {@link StopExecutionStatus}.
 *       {@link #DELIVERY_RECORDED} joins them in migration V28: it is stop-level because a
 *       delivery happens at a stop, and which order it was about travels in the entry's metadata
 *       rather than in its type - the timeline is read as a sequence of stops, not as a ledger of
 *       orders.</li>
 *   <li><b>Commercial</b> - the five {@code TENDER_*} (migration V31). Trip-scoped like the first
 *       family and written by {@code ShipmentEventPublisher} for the same reason, but describing a
 *       conversation with a party outside this company rather than a state of the shipment.</li>
 *   <li>{@link #EXCEPTION_REPORTED} and {@link #EXCEPTION_RESOLVED} are legitimately either: a
 *       breakdown happens to a trip, a refused delivery happens at a stop.</li>
 * </ul>
 *
 * <p><b>Why the trip family is named TRIP_ and the outbox family SHIPMENT_.</b> They are the same
 * transitions told to two audiences. {@link ShipmentEventType} is the partner-facing vocabulary of
 * a published shipment; this is the internal vocabulary of a trip being operated, and it has
 * entries - every stop-level one - that no partner subscribes to. Mapping between them is
 * {@code ShipmentEventPublisher}'s single responsibility rather than a name they happen to share.
 */
public enum TransportEventType {
    TRIP_CONFIRMED,
    TRIP_READY,
    TRIP_DISPATCHED,
    TRIP_COMPLETED,
    TRIP_CANCELLED,
    ARRIVED_AT_STOP,
    SERVICE_STARTED,
    STOP_COMPLETED,
    STOP_SKIPPED,
    STOP_FAILED,
    /** A delivery result was recorded for one of the stop's orders (migration V28). */
    DELIVERY_RECORDED,
    /**
     * The five tender transitions (migration V31). Trip-scoped: an offer is made for the whole
     * shipment, so there is no one stop it could be about.
     *
     * <p>They earn a place in the timeline where V27 argued the per-stop transitions did not need
     * an audit row: "10:12 offered to ACME, 10:40 ACME accepted" is what a dispatcher opening a
     * shipment at 11:00 reads in order to know whether the truck is coming, and it is the one part
     * of the day that involves a party outside this company.
     */
    TENDER_SENT,
    TENDER_ACCEPTED,
    TENDER_REJECTED,
    TENDER_EXPIRED,
    TENDER_CANCELLED,
    EXCEPTION_REPORTED,
    EXCEPTION_RESOLVED;

    private static final Set<TransportEventType> STOP_SCOPED = EnumSet.of(
            ARRIVED_AT_STOP, SERVICE_STARTED, STOP_COMPLETED, STOP_SKIPPED, STOP_FAILED,
            DELIVERY_RECORDED);

    private static final Set<TransportEventType> TRIP_SCOPED = EnumSet.of(
            TRIP_CONFIRMED, TRIP_READY, TRIP_DISPATCHED, TRIP_COMPLETED, TRIP_CANCELLED,
            TENDER_SENT, TENDER_ACCEPTED, TENDER_REJECTED, TENDER_EXPIRED, TENDER_CANCELLED);

    /** Whether this event is meaningless without the stop it happened at. */
    public boolean requiresStop() {
        return STOP_SCOPED.contains(this);
    }

    /** Whether naming a stop for this event would be a contradiction - the trip has no one stop. */
    public boolean forbidsStop() {
        return TRIP_SCOPED.contains(this);
    }

    /** The event a stop reaching {@code outcome} produces, or null when the outcome logs nothing. */
    public static TransportEventType forStopOutcome(StopExecutionStatus outcome) {
        return switch (outcome) {
            case ARRIVED -> ARRIVED_AT_STOP;
            case IN_SERVICE -> SERVICE_STARTED;
            case COMPLETED -> STOP_COMPLETED;
            case SKIPPED -> STOP_SKIPPED;
            case FAILED -> STOP_FAILED;
            // Nothing transitions *to* PENDING: it is where every stop starts, and a stop that was
            // never touched has no event by definition.
            case PENDING -> null;
        };
    }
}
