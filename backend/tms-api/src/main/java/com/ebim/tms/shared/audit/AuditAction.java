package com.ebim.tms.shared.audit;

/**
 * What happened to the aggregate an {@link AuditRecorder#record} call names. Mirrors
 * {@code ck_audit_event_action} (migration V22).
 */
public enum AuditAction {
    CREATE,
    UPDATE,
    ACTIVATE,
    DEACTIVATE,
    ASSIGN_ORDER,
    REMOVE_ORDER,
    MOVE_ORDER,
    VEHICLE_CHANGE,
    CONFIRM,
    CANCEL,
    CREDENTIAL_CREATE,
    CREDENTIAL_ROTATE,
    CREDENTIAL_REVOKE,
    IMPORT_EXECUTED,
    SHIPMENT_CONFIRMED
}
