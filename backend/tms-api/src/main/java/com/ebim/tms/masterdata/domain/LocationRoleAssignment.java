package com.ebim.tms.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One {@link LocationRole} held by one {@link Location} (migration V14). A pure child of its
 * location, exactly like {@code FrequencyWeeklyRule} is a child of {@code Frequency}: it is
 * only ever created or removed through {@link Location#replaceRoles}, never persisted on its
 * own, and it carries no {@code company_id} because the tenant is the parent's.
 *
 * <p>There is no {@code updated_at} and no actor column. A role assignment has no updatable
 * state - it either exists or it does not - and who changed the set of roles is recorded on
 * the location's own {@code updated_by}, which is the row an audit reader would look at.
 */
@Entity
@Table(name = "location_role")
public class LocationRoleAssignment {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false, updatable = false)
    private Location location;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, updatable = false)
    private LocationRole role;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    protected LocationRoleAssignment() {
        // JPA
    }

    LocationRoleAssignment(Location location, LocationRole role) {
        this.location = location;
        this.role = role;
    }

    public UUID id() {
        return id;
    }

    public Location location() {
        return location;
    }

    public LocationRole role() {
        return role;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
