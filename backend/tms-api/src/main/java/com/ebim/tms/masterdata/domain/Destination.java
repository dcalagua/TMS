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
 * A delivery/service point: customer, store, branch, hub, distribution center or delivery
 * point (migration V7). Follows the same shape as {@link Origin}: coordinates are plain
 * columns for ordinary JPA CRUD, the database derives its own generated {@code geography}
 * column from them.
 *
 * <p>{@code zoneId} is a plain UUID, not a JPA association: {@code DestinationService} resolves
 * the referenced {@link Zone} explicitly (batched for lists) to build {@code
 * com.ebim.tms.masterdata.application.DestinationView}. A mapped {@code @ManyToOne} would go
 * stale within the same persistence context the moment {@code applyChanges} assigns a new
 * {@code zoneId} without a flush/refresh, which is worse than one extra explicit query. The
 * database's composite FK ({@code fk_destination_zone_company}) is what actually guarantees the
 * referenced zone belongs to this destination's company; the Java-side lookup in {@code
 * DestinationService} is the primary check, that constraint is defense in depth.
 */
@Entity
@Table(name = "destination")
public class Destination {

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
    @Column(name = "destination_type", nullable = false)
    private DestinationType type;

    @Column(name = "address")
    private String address;

    @Column(name = "address_reference")
    private String addressReference;

    @Column(name = "district")
    private String district;

    @Column(name = "province")
    private String province;

    @Column(name = "department")
    private String department;

    @Column(name = "country", nullable = false)
    private String country;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "zone_id")
    private UUID zoneId;

    @Column(name = "service_time_minutes", nullable = false)
    private int serviceTimeMinutes;

    @Column(name = "external_reference")
    private String externalReference;

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

    protected Destination() {
        // JPA
    }

    public Destination(UUID companyId, String code, String name, DestinationType type, String address,
            String addressReference, String district, String province, String department, String country,
            BigDecimal latitude, BigDecimal longitude, UUID zoneId, int serviceTimeMinutes,
            String externalReference, UUID actorId) {
        this.companyId = companyId;
        this.code = code;
        this.name = name;
        this.type = type;
        this.address = address;
        this.addressReference = addressReference;
        this.district = district;
        this.province = province;
        this.department = department;
        this.country = country;
        this.latitude = latitude;
        this.longitude = longitude;
        this.zoneId = zoneId;
        this.serviceTimeMinutes = serviceTimeMinutes;
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

    public DestinationType type() {
        return type;
    }

    public String address() {
        return address;
    }

    public String addressReference() {
        return addressReference;
    }

    public String district() {
        return district;
    }

    public String province() {
        return province;
    }

    public String department() {
        return department;
    }

    public String country() {
        return country;
    }

    public BigDecimal latitude() {
        return latitude;
    }

    public BigDecimal longitude() {
        return longitude;
    }

    public UUID zoneId() {
        return zoneId;
    }

    public int serviceTimeMinutes() {
        return serviceTimeMinutes;
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

    public void applyChanges(String code, String name, DestinationType type, String address,
            String addressReference, String district, String province, String department, String country,
            BigDecimal latitude, BigDecimal longitude, UUID zoneId, int serviceTimeMinutes,
            String externalReference, UUID actorId) {
        this.code = code;
        this.name = name;
        this.type = type;
        this.address = address;
        this.addressReference = addressReference;
        this.district = district;
        this.province = province;
        this.department = department;
        this.country = country;
        this.latitude = latitude;
        this.longitude = longitude;
        this.zoneId = zoneId;
        this.serviceTimeMinutes = serviceTimeMinutes;
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
