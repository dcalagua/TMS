package com.ebim.tms.planning.domain;

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
 * One row of the transactional outbox behind the outbound Shipment integration (job 08, migration
 * V20): a fact about a trip's publishable state that a partner may not yet have seen.
 *
 * <p>Written in the <em>same</em> transaction as the state change it records - never in its own,
 * unlike {@code integration.domain.IntegrationRequest} (V18), which deliberately survives a
 * rolled-back business transaction. Here the opposite guarantee is the point: if the trip
 * confirmation this row describes is rolled back, the row must vanish with it, or a partner could
 * be told about a shipment TMS itself does not believe was confirmed. See
 * {@code docs/integrations/OUTBOUND_SHIPMENT_V1.md}, "Change feed".
 *
 * <p>Immutable once written - there is no setter beyond the constructor, matching the "written
 * once" shape {@code IntegrationRequest} already establishes for the inbound side.
 */
@Entity
@Table(name = "shipment_outbox_event")
public class ShipmentOutboxEvent {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "trip_id", updatable = false, nullable = false)
    private UUID tripId;

    @Column(name = "shipment_number", updatable = false, nullable = false)
    private String shipmentNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", updatable = false, nullable = false)
    private ShipmentEventType eventType;

    @Column(name = "occurred_at", updatable = false, nullable = false)
    private OffsetDateTime occurredAt;

    protected ShipmentOutboxEvent() {
        // JPA
    }

    public ShipmentOutboxEvent(
            UUID companyId, UUID tripId, String shipmentNumber, ShipmentEventType eventType, OffsetDateTime occurredAt) {
        this.companyId = companyId;
        this.tripId = tripId;
        this.shipmentNumber = shipmentNumber;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
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

    public String shipmentNumber() {
        return shipmentNumber;
    }

    public ShipmentEventType eventType() {
        return eventType;
    }

    public OffsetDateTime occurredAt() {
        return occurredAt;
    }
}
