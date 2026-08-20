package com.ebim.tms.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A loading/dispatch point: warehouse, distribution center, plant or hub (migration V6).
 *
 * <p>Coordinates are plain {@code latitude}/{@code longitude} columns so CRUD stays ordinary
 * JPA - the database derives its own generated {@code geography} column from them for future
 * spatial queries (architecture section 8); this entity does not map that column at all.
 *
 * <p>{@code createdAt}/{@code updatedAt} are read back from the database rather than computed
 * here, for the same reason as {@link Zone}: the {@code updated_at} trigger is authoritative.
 */
@Entity
@Table(name = "origin")
public class Origin {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_type", nullable = false)
    private OriginType type;

    @Column(name = "address")
    private String address;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Column(name = "external_reference")
    private String externalReference;

    /**
     * The canonical {@code tms.location} this origin projects (migration V14), and the
     * authoritative mapping for the later unification - see
     * {@code docs/architecture/ADR_LOCATION_MODEL.md} section 3. Equal to this origin's own id
     * for every row that existed before V14. Nullable because the constraint suites seed origins
     * with direct SQL; {@code LocationCompatibilityProjector} is the only writer in the
     * application.
     */
    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected Origin() {
        // JPA
    }

    public Origin(UUID companyId, String code, String name, OriginType type, String address,
            BigDecimal latitude, BigDecimal longitude, String timeZone, String externalReference, UUID actorId) {
        this.companyId = companyId;
        this.code = code;
        this.name = name;
        this.type = type;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timeZone = timeZone;
        this.externalReference = externalReference;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public OriginType type() {
        return type;
    }

    public String address() {
        return address;
    }

    public BigDecimal latitude() {
        return latitude;
    }

    public BigDecimal longitude() {
        return longitude;
    }

    public String timeZone() {
        return timeZone;
    }

    public String externalReference() {
        return externalReference;
    }

    public boolean active() {
        return active;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public UUID updatedBy() {
        return updatedBy;
    }

    public UUID locationId() {
        return locationId;
    }

    /** Set once, by {@code LocationCompatibilityProjector}, when the canonical location exists. */
    public void linkToLocation(UUID locationId) {
        this.locationId = locationId;
    }

    public void applyChanges(String code, String name, OriginType type, String address, BigDecimal latitude,
            BigDecimal longitude, String timeZone, String externalReference, UUID actorId) {
        this.code = code;
        this.name = name;
        this.type = type;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timeZone = timeZone;
        this.externalReference = externalReference;
        this.updatedBy = actorId;
    }

    public void activate(UUID actorId) {
        this.active = true;
        this.updatedBy = actorId;
    }

    public void deactivate(UUID actorId) {
        this.active = false;
        this.updatedBy = actorId;
    }
}
