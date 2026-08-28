package com.ebim.tms.shared.audit;

/**
 * What an {@link AuditRecorder#record} call describes a change to. Mirrors
 * {@code ck_audit_event_aggregate_type} (migrations V22, V26, V30, V34 and V35).
 */
public enum AuditAggregateType {
    LOCATION,
    CARRIER,
    VEHICLE,
    DRIVER,
    TRANSPORT_ORDER,
    TRIP,
    PLANNING_RUN,
    INTEGRATION_CLIENT,
    MASTER_DATA_IMPORT_BATCH,
    ORDER_IMPORT_BATCH,
    SHIPMENT,
    /** A commercial agreement with a carrier (migration V30). */
    RATE_CARD,
    /** What one trip was estimated at and what it actually cost (migration V30). */
    TRIP_COST,

    /**
     * The tenant itself (migration V34): its profile, its time zone and its operational settings.
     * Separate from {@link #MEMBERSHIP} because they answer different questions - "who changed our
     * shipment prefix" and "who let this person in" are not asked by the same person on the same
     * day.
     */
    COMPANY,

    /**
     * A person's global profile - the name behind an email. Distinct from {@link #MEMBERSHIP}
     * because {@code tms.app_user} is installation-wide: a change here follows that person into
     * every organization they work for, and a change to their membership does not leave one company.
     */
    APP_USER,

    /**
     * One person's access to one company. Named after the row that actually changes when access is
     * granted, changed or revoked, so {@code aggregate_id} stays resolvable.
     */
    MEMBERSHIP,

    /**
     * An outbound webhook endpoint (migration V35). Separate from {@link #INTEGRATION_CLIENT}
     * because the question it answers is the opposite one: a credential row explains who was let
     * <em>in</em>, and this explains where this company's operational data was sent <em>out</em>.
     * Somebody investigating a leak asks the second question, and it should not require reading
     * through every credential change to find it.
     */
    WEBHOOK_SUBSCRIPTION,

    /**
     * A dock, door, bay or yard slot (migration V41). Master data, audited like every other master:
     * taking a door out of service changes what the whole site can promise.
     */
    LOCATION_RESOURCE,

    /**
     * A booking against a door (migration V41).
     *
     * <p>Its own aggregate rather than a note on a trip: it exists before a trip does, outlives one
     * that is cancelled, and a carrier arguing about a missed slot asks about the booking rather
     * than about the shipment that happened to be behind it.
     */
    APPOINTMENT,

    /**
     * A carrier's invoice (migration V46).
     *
     * <p>Its own aggregate because a freight audit is about the document, not about the shipments
     * it bills: one invoice can cover ten trips, and "who approved this expenditure" is a question
     * about the invoice.
     */
    CARRIER_INVOICE,

    /** What running one of our own trucks is modelled to cost (V48, JOB 22). */
    OWN_FLEET_COST_PROFILE
}
