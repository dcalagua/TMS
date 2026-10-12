package com.ebim.tms.planning.domain;

/** The tracking report's document filter (ADR-019): shipments with documents still pending, or none. */
public enum ShipmentDocumentState {
    PENDING,
    COMPLETE
}
