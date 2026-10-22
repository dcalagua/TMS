package com.ebim.tms.planning.domain;

/**
 * Where a {@link TransportEvent} came from. Mirrors {@code ck_transport_event_source} (V27, V70).
 *
 * <p>Deliberately no {@code TELEMATICS}: it does not exist, GPS and telematics are deferred by
 * decision (CLAUDE.md), and a source value with no source is a promise the schema cannot keep.
 * {@link #CARRIER_PORTAL} was added by V70 exactly that way - once the carrier portal could record
 * stop and delivery facts, as an application change plus a one-line CHECK.
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
    INTEGRATION,

    /**
     * A user of the carrier, working in the carrier portal (V70, ADR-018 addendum). A person like
     * {@link #OPERATOR} - always carries the {@code app_user} who acted - but from the field, on a
     * trip of their own carrier, so the office's history can tell the two apart.
     */
    CARRIER_PORTAL;

    /**
     * The source of a fact recorded inside a request: a machine is an {@link #INTEGRATION}, a
     * carrier-scoped person a {@link #CARRIER_PORTAL}, anybody else an {@link #OPERATOR}. Decided
     * here once, so the timeline and the delivery row cannot disagree about who recorded a fact.
     *
     * @param machine whether the actor is an authenticated partner rather than a person
     * @param carrierScoped whether the person acts through a carrier membership (ADR-018)
     */
    public static TransportEventSource of(boolean machine, boolean carrierScoped) {
        if (machine) {
            return INTEGRATION;
        }
        return carrierScoped ? CARRIER_PORTAL : OPERATOR;
    }
}
