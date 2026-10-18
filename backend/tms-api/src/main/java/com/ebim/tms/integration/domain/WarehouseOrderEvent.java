package com.ebim.tms.integration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * What the warehouse did to its order, as received (migration V61, WAREHOUSE_EXECUTION_V1 7.5): it
 * cancelled it or closed it short. Kept whatever TMS did with it, and never changed - the outcome is
 * decided before the row is written, in the same transaction as the hold it may have placed.
 */
@Entity
@Table(name = "warehouse_order_event")
public class WarehouseOrderEvent {

    /** {@code ck_warehouse_order_event_type}. */
    public enum Type {
        ORDER_CANCELLED,
        ORDER_SHORT_CLOSED
    }

    /** {@code ck_warehouse_order_event_outcome}: what TMS did. {@code DUPLICATE} and {@code INVALID} are never stored. */
    public enum Outcome {
        HOLD_PLACED,
        ALREADY_HELD,
        NOTED,
        ORDER_CLOSED,
        UNKNOWN_ORDER
    }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "integration_client_id", updatable = false, nullable = false)
    private UUID integrationClientId;

    @Column(name = "source_system", updatable = false, nullable = false)
    private String sourceSystem;

    @Column(name = "event_id", updatable = false, nullable = false)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", updatable = false, nullable = false)
    private Type eventType;

    @Column(name = "external_source", updatable = false)
    private String externalSource;

    @Column(name = "external_reference", updatable = false, nullable = false)
    private String externalReference;

    @Column(name = "warehouse_order_number", updatable = false)
    private String warehouseOrderNumber;

    @Column(name = "warehouse_code", updatable = false)
    private String warehouseCode;

    @Column(name = "reason", updatable = false)
    private String reason;

    @Column(name = "occurred_at", updatable = false, nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "hold_id", updatable = false)
    private UUID holdId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", updatable = false, nullable = false)
    private Outcome outcome;

    @Column(name = "payload", updatable = false, nullable = false)
    private String payload;

    protected WarehouseOrderEvent() {
        // JPA
    }

    public WarehouseOrderEvent(UUID companyId, UUID integrationClientId, String sourceSystem, String eventId,
            Type eventType, String externalSource, String externalReference, String warehouseOrderNumber,
            String warehouseCode, String reason, OffsetDateTime occurredAt, UUID orderId, UUID holdId,
            Outcome outcome, String payload) {
        this.companyId = companyId;
        this.integrationClientId = integrationClientId;
        this.sourceSystem = sourceSystem;
        this.eventId = eventId;
        this.eventType = eventType;
        this.externalSource = externalSource;
        this.externalReference = externalReference;
        this.warehouseOrderNumber = warehouseOrderNumber;
        this.warehouseCode = warehouseCode;
        this.reason = reason;
        this.occurredAt = occurredAt;
        this.orderId = orderId;
        this.holdId = holdId;
        this.outcome = outcome;
        this.payload = payload;
    }

    public UUID id() {
        return id;
    }

    public Outcome outcome() {
        return outcome;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID holdId() {
        return holdId;
    }
}
