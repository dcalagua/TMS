package com.ebim.tms.shared.audit;

/**
 * What an {@link AuditRecorder#record} call describes a change to. Mirrors
 * {@code ck_audit_event_aggregate_type} (migration V22).
 */
public enum AuditAggregateType {
    LOCATION,
    CARRIER,
    VEHICLE,
    TRANSPORT_ORDER,
    TRIP,
    PLANNING_RUN,
    INTEGRATION_CLIENT,
    MASTER_DATA_IMPORT_BATCH,
    ORDER_IMPORT_BATCH,
    SHIPMENT
}
