package com.ebim.tms.planning.domain;

/**
 * Where a {@link TransportEvent} came from. Mirrors {@code ck_transport_event_source} (V27).
 *
 * <p>Three values, and deliberately no {@code DRIVER_APP} or {@code TELEMATICS}: neither exists,
 * GPS and telematics are deferred by decision (CLAUDE.md), and a source value with no source is a
 * promise the schema cannot keep. Adding one when a feed appears is an application change plus a
 * one-line CHECK, which is the same bet migration V20 made with its event types.
 */
public enum TransportEventSource {

    /** A person, working in the TMS UI. Always carries the {@code app_user} who acted. */
    OPERATOR,

    /**
     * A TMS rule that recorded a fact nobody typed. Rare in V1 and reserved rather than
     * speculative: the moment a scheduled job closes a stale trip out, that is this.
     */
    SYSTEM,

    /**
     * An authenticated partner over the M2M API, identified by its machine label because it has no
     * {@code app_user} - the same identity model {@code AuditActor.machine} uses.
     */
    INTEGRATION
}
