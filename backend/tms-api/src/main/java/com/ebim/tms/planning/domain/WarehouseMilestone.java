package com.ebim.tms.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A warehouse milestone as received (V53). Informative; it never moves a lifecycle. */
@Entity
@Table(name = "warehouse_milestone")
public class WarehouseMilestone {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "integration_client_id", nullable = false, updatable = false)
    private UUID integrationClientId;

    @Column(name = "source_system", nullable = false, updatable = false)
    private String sourceSystem;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "milestone_type", nullable = false, updatable = false)
    private String milestoneType;

    @Column(name = "transport_reference", nullable = false, updatable = false)
    private String transportReference;

    @Column(name = "trip_id", updatable = false)
    private UUID tripId;

    @Column(name = "load_reference", updatable = false)
    private String loadReference;

    @Column(name = "warehouse_code", updatable = false)
    private String warehouseCode;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    protected WarehouseMilestone() {
    }

    public WarehouseMilestone(UUID companyId, UUID integrationClientId, String sourceSystem, String eventId,
            String milestoneType, String transportReference, UUID tripId, String loadReference, String warehouseCode,
            OffsetDateTime occurredAt, OffsetDateTime receivedAt) {
        this.companyId = companyId;
        this.integrationClientId = integrationClientId;
        this.sourceSystem = sourceSystem;
        this.eventId = eventId;
        this.milestoneType = milestoneType;
        this.transportReference = transportReference;
        this.tripId = tripId;
        this.loadReference = loadReference;
        this.warehouseCode = warehouseCode;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID tripId() {
        return tripId;
    }

    public String sourceSystem() {
        return sourceSystem;
    }

    public String eventId() {
        return eventId;
    }

    public String milestoneType() {
        return milestoneType;
    }

    public String transportReference() {
        return transportReference;
    }

    public String loadReference() {
        return loadReference;
    }

    public String warehouseCode() {
        return warehouseCode;
    }

    public OffsetDateTime occurredAt() {
        return occurredAt;
    }

    public OffsetDateTime receivedAt() {
        return receivedAt;
    }
}
