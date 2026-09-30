package com.ebim.tms.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A warehouse system's dispatch document, stored whole (V53, ADR-013 section 3). Written once; the
 * only later change is being superseded by a higher revision of the same document.
 */
@Entity
@Table(name = "external_dispatch")
public class ExternalDispatch {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "trip_id", updatable = false)
    private UUID tripId;

    @Column(name = "integration_client_id", nullable = false, updatable = false)
    private UUID integrationClientId;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "source_system", nullable = false, updatable = false)
    private String sourceSystem;

    @Column(name = "dispatch_reference", nullable = false, updatable = false)
    private String dispatchReference;

    @Column(name = "revision", nullable = false, updatable = false)
    private int revision;

    @Column(name = "transport_reference", nullable = false, updatable = false)
    private String transportReference;

    @Column(name = "load_reference", updatable = false)
    private String loadReference;

    @Column(name = "warehouse_code", updatable = false)
    private String warehouseCode;

    @Column(name = "actual_dispatch_at", nullable = false, updatable = false)
    private OffsetDateTime actualDispatchAt;

    @Column(name = "carrier_code", updatable = false)
    private String carrierCode;

    @Column(name = "carrier_name", updatable = false)
    private String carrierName;

    @Column(name = "vehicle_license_plate", updatable = false)
    private String vehicleLicensePlate;

    @Column(name = "vehicle_type", updatable = false)
    private String vehicleType;

    @Column(name = "driver_name", updatable = false)
    private String driverName;

    @Column(name = "driver_document_number", updatable = false)
    private String driverDocumentNumber;

    @Column(name = "seal_number", updatable = false)
    private String sealNumber;

    @Column(name = "transport_document_number", updatable = false)
    private String transportDocumentNumber;

    @Column(name = "total_handling_units", updatable = false)
    private Integer totalHandlingUnits;

    @Column(name = "total_weight_kg", updatable = false)
    private BigDecimal totalWeightKg;

    @Column(name = "total_volume_m3", updatable = false)
    private BigDecimal totalVolumeM3;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false)
    private ExternalDispatchOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, updatable = false)
    private DispatchVerificationStatus verificationStatus;

    @Column(name = "discrepancies", nullable = false, updatable = false)
    private String discrepancies;

    @Column(name = "payload_hash", nullable = false, updatable = false)
    private String payloadHash;

    @Column(name = "raw_payload", nullable = false, updatable = false)
    private String rawPayload;

    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "superseded_at")
    private OffsetDateTime supersededAt;

    @Column(name = "superseded_by")
    private UUID supersededBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected ExternalDispatch() {
    }

    @SuppressWarnings("java:S107")
    public ExternalDispatch(UUID companyId, UUID tripId, UUID integrationClientId, String idempotencyKey,
            String correlationId, String sourceSystem, String dispatchReference, int revision,
            String transportReference, String loadReference, String warehouseCode, OffsetDateTime actualDispatchAt,
            String carrierCode, String carrierName, String vehicleLicensePlate, String vehicleType, String driverName,
            String driverDocumentNumber, String sealNumber, String transportDocumentNumber,
            Integer totalHandlingUnits, BigDecimal totalWeightKg, BigDecimal totalVolumeM3,
            ExternalDispatchOutcome outcome, DispatchVerificationStatus verificationStatus, String discrepancies,
            String payloadHash, String rawPayload, OffsetDateTime receivedAt) {
        this.companyId = companyId;
        this.tripId = tripId;
        this.integrationClientId = integrationClientId;
        this.idempotencyKey = idempotencyKey;
        this.correlationId = correlationId;
        this.sourceSystem = sourceSystem;
        this.dispatchReference = dispatchReference;
        this.revision = revision;
        this.transportReference = transportReference;
        this.loadReference = loadReference;
        this.warehouseCode = warehouseCode;
        this.actualDispatchAt = actualDispatchAt;
        this.carrierCode = carrierCode;
        this.carrierName = carrierName;
        this.vehicleLicensePlate = vehicleLicensePlate;
        this.vehicleType = vehicleType;
        this.driverName = driverName;
        this.driverDocumentNumber = driverDocumentNumber;
        this.sealNumber = sealNumber;
        this.transportDocumentNumber = transportDocumentNumber;
        this.totalHandlingUnits = totalHandlingUnits;
        this.totalWeightKg = totalWeightKg;
        this.totalVolumeM3 = totalVolumeM3;
        this.outcome = outcome;
        this.verificationStatus = verificationStatus;
        this.discrepancies = discrepancies;
        this.payloadHash = payloadHash;
        this.rawPayload = rawPayload;
        this.receivedAt = receivedAt;
    }

    /**
     * A higher revision of the same document replaced this one (ADR-013 section 6). Stops being
     * current first; {@link #linkSupersededBy} names the successor once it has an id.
     */
    public void supersede(OffsetDateTime at) {
        if (supersededAt != null) {
            throw new IllegalStateException("dispatch document " + dispatchReference + " r" + revision
                    + " was already superseded");
        }
        this.supersededAt = at;
    }

    public void linkSupersededBy(UUID successor) {
        if (supersededAt == null) {
            throw new IllegalStateException("only a superseded document names its successor");
        }
        this.supersededBy = successor;
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

    public UUID integrationClientId() {
        return integrationClientId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String correlationId() {
        return correlationId;
    }

    public String sourceSystem() {
        return sourceSystem;
    }

    public String dispatchReference() {
        return dispatchReference;
    }

    public int revision() {
        return revision;
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

    public OffsetDateTime actualDispatchAt() {
        return actualDispatchAt;
    }

    public String carrierCode() {
        return carrierCode;
    }

    public String carrierName() {
        return carrierName;
    }

    public String vehicleLicensePlate() {
        return vehicleLicensePlate;
    }

    public String vehicleType() {
        return vehicleType;
    }

    public String driverName() {
        return driverName;
    }

    public String driverDocumentNumber() {
        return driverDocumentNumber;
    }

    public String sealNumber() {
        return sealNumber;
    }

    public String transportDocumentNumber() {
        return transportDocumentNumber;
    }

    public Integer totalHandlingUnits() {
        return totalHandlingUnits;
    }

    public BigDecimal totalWeightKg() {
        return totalWeightKg;
    }

    public BigDecimal totalVolumeM3() {
        return totalVolumeM3;
    }

    public ExternalDispatchOutcome outcome() {
        return outcome;
    }

    public DispatchVerificationStatus verificationStatus() {
        return verificationStatus;
    }

    public String discrepancies() {
        return discrepancies;
    }

    public String payloadHash() {
        return payloadHash;
    }

    public String rawPayload() {
        return rawPayload;
    }

    public OffsetDateTime receivedAt() {
        return receivedAt;
    }

    public OffsetDateTime supersededAt() {
        return supersededAt;
    }

    public UUID supersededBy() {
        return supersededBy;
    }

    public boolean isCurrent() {
        return supersededAt == null;
    }
}
