package com.ebim.tms.tracking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One reported position of one shipment (migration V29).
 *
 * <p>Immutable once written - no setter, and the database withholds UPDATE from {@code tms_app}.
 * The reasoning differs from {@code TransportEvent}'s, which is immutable because it is a log: this
 * is immutable because it is a <em>measurement</em>. A measurement is never corrected, only
 * superseded by the next one, and a feed that sent the wrong coordinates sends the right ones a
 * minute later rather than editing history.
 *
 * <p>Unlike every other write in TMS, a row of this table has no actor and no {@code created_by}.
 * That is not an omission: nobody typed it. {@link #provider} says which feed reported it, which is
 * the strongest attribution that exists for a machine measurement, and inventing a machine actor
 * label to satisfy the shape the rest of the schema uses would be a fact TMS made up.
 */
@Entity
@Table(name = "tracking_position")
public class TrackingPosition {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "trip_id", updatable = false, nullable = false)
    private UUID tripId;

    /** When the device was here, as the feed reports it - never when TMS stored it. */
    @Column(name = "occurred_at", updatable = false, nullable = false)
    private OffsetDateTime occurredAt;

    @CreationTimestamp
    @Column(name = "received_at", updatable = false, nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "latitude", updatable = false, nullable = false)
    private BigDecimal latitude;

    @Column(name = "longitude", updatable = false, nullable = false)
    private BigDecimal longitude;

    @Column(name = "speed_kph", updatable = false)
    private BigDecimal speedKph;

    @Column(name = "heading_degrees", updatable = false)
    private BigDecimal headingDegrees;

    @Column(name = "provider", updatable = false, nullable = false)
    private String provider;

    @Column(name = "external_vehicle_reference", updatable = false)
    private String externalVehicleReference;

    @Column(name = "correlation_reference", updatable = false)
    private String correlationReference;

    protected TrackingPosition() {
        // JPA
    }

    /**
     * The only constructor. Every argument has already been normalised and range-checked by
     * {@code TrackingIngestionService}, which is the single writer - the same one-writer discipline
     * {@code TransportEventRecorder} keeps, and for the same reason: the sampling rule, the
     * staleness rule and the provider slug must be decided once or two intake paths will decide
     * them differently.
     */
    public TrackingPosition(UUID companyId, UUID tripId, OffsetDateTime occurredAt, BigDecimal latitude,
            BigDecimal longitude, BigDecimal speedKph, BigDecimal headingDegrees, String provider,
            String externalVehicleReference, String correlationReference) {
        this.companyId = companyId;
        this.tripId = tripId;
        this.occurredAt = occurredAt;
        this.latitude = latitude;
        this.longitude = longitude;
        this.speedKph = speedKph;
        this.headingDegrees = headingDegrees;
        this.provider = provider;
        this.externalVehicleReference = externalVehicleReference;
        this.correlationReference = correlationReference;
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

    public OffsetDateTime occurredAt() {
        return occurredAt;
    }

    public OffsetDateTime receivedAt() {
        return receivedAt;
    }

    public BigDecimal latitude() {
        return latitude;
    }

    public BigDecimal longitude() {
        return longitude;
    }

    public BigDecimal speedKph() {
        return speedKph;
    }

    public BigDecimal headingDegrees() {
        return headingDegrees;
    }

    public String provider() {
        return provider;
    }

    public String externalVehicleReference() {
        return externalVehicleReference;
    }

    public String correlationReference() {
        return correlationReference;
    }
}
