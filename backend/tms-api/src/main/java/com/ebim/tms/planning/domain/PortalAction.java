package com.ebim.tms.planning.domain;

/**
 * An execution action a carrier user takes from the portal (V70, ADR-018 addendum). Mirrors
 * {@code ck_portal_action_receipt_action}: it names what an {@code Idempotency-Key} was spent on, so
 * the same key cannot be replayed onto a different action.
 */
public enum PortalAction {
    STOP_ARRIVE,
    STOP_START_SERVICE,
    STOP_COMPLETE,
    STOP_FAIL,
    DELIVERY_RECORD,
    INCIDENT_REPORT
}
