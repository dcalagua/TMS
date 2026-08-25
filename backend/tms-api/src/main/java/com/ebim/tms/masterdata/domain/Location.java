package com.ebim.tms.masterdata.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * The canonical physical place (migration V14): an origin, a store, a customer delivery point,
 * a distribution centre, a plant or a hub. What it <em>is</em> is {@link LocationType}; what it
 * may <em>do</em> is the set of {@link LocationRole}s it holds.
 *
 * <p>This is the only physical-place record. {@code tms.origin} and {@code tms.destination}
 * were retired by V23, which repointed {@code route}, {@code route_stop},
 * {@code transport_order}, {@code planning_run} and {@code trip_stop} at this table. See
 * {@code docs/domain/LOCATIONS.md}.
 *
 * <p>One store holds both roles: it is the destination of the delivery and the origin of the
 * return, with one address, one pair of coordinates and one {@code active} flag.
 *
 * <p>{@code roles} is owned here: every mutation goes through {@link #replaceRoles}, which diffs
 * the incoming set against what is persisted and lets {@code orphanRemoval} delete the rest -
 * the same pattern {@link Frequency#replaceWeeklyRules} uses.
 */
@Entity
@Table(name = "location")
public class Location {

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
    @Column(name = "location_type", nullable = false)
    private LocationType type;

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

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    /**
     * Plain UUID rather than a JPA association: a zone is a filter dimension a location carries,
     * not a parent whose graph should be walked, and a lazy association here would put a proxy on
     * every row of every list page.
     */
    @Column(name = "zone_id")
    private UUID zoneId;

    @Column(name = "service_time_minutes", nullable = false)
    private int serviceTimeMinutes;

    @Column(name = "external_system")
    private String externalSystem;

    @Column(name = "external_reference")
    private String externalReference;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "location", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<LocationRoleAssignment> roles = new LinkedHashSet<>();

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

    protected Location() {
        // JPA
    }

    public Location(UUID companyId, String code, String name, LocationType type, String address,
            String addressReference, String district, String province, String department, String country,
            String timeZone, BigDecimal latitude, BigDecimal longitude, UUID zoneId, int serviceTimeMinutes,
            String externalSystem, String externalReference, UUID actorId) {
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
        this.timeZone = timeZone;
        this.latitude = latitude;
        this.longitude = longitude;
        this.zoneId = zoneId;
        this.serviceTimeMinutes = serviceTimeMinutes;
        this.externalSystem = externalSystem;
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

    public LocationType type() {
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

    public String timeZone() {
        return timeZone;
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

    public String externalSystem() {
        return externalSystem;
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

    /** The roles as a value set, ordered by the enum's own declaration order for a stable API response. */
    public Set<LocationRole> roles() {
        Set<LocationRole> held = EnumSet.noneOf(LocationRole.class);
        roles.forEach(assignment -> held.add(assignment.role()));
        return held;
    }

    /**
     * The assignment rows themselves rather than their values. Package-private and used only by
     * {@code LocationModelTest}, which pins {@link #replaceRoles} as a diff: an unchanged role has
     * to keep its own row so its {@code created_at} keeps saying when the location first took it.
     */
    Set<LocationRoleAssignment> roleAssignments() {
        return Set.copyOf(roles);
    }

    public boolean hasRole(LocationRole role) {
        return roles.stream().anyMatch(assignment -> assignment.role() == role);
    }

    public void applyChanges(String code, String name, LocationType type, String address, String addressReference,
            String district, String province, String department, String country, String timeZone,
            BigDecimal latitude, BigDecimal longitude, UUID zoneId, int serviceTimeMinutes,
            String externalSystem, String externalReference, UUID actorId) {
        this.code = code;
        this.name = name;
        this.type = type;
        this.address = address;
        this.addressReference = addressReference;
        this.district = district;
        this.province = province;
        this.department = department;
        this.country = country;
        this.timeZone = timeZone;
        this.latitude = latitude;
        this.longitude = longitude;
        this.zoneId = zoneId;
        this.serviceTimeMinutes = serviceTimeMinutes;
        this.externalSystem = externalSystem;
        this.externalReference = externalReference;
        this.updatedBy = actorId;
    }

    /**
     * Diffs the requested roles against the persisted ones: roles already held are left alone
     * (so their {@code created_at} keeps saying when the location first took that role), new
     * ones are added, and the rest are removed through {@code orphanRemoval}.
     */
    public void replaceRoles(Collection<LocationRole> requested) {
        // Built by addAll rather than EnumSet.copyOf, which throws on an empty non-EnumSet.
        Set<LocationRole> target = EnumSet.noneOf(LocationRole.class);
        target.addAll(requested);
        roles.removeIf(assignment -> !target.contains(assignment.role()));
        Set<LocationRole> held = roles();
        for (LocationRole role : target) {
            if (!held.contains(role)) {
                roles.add(new LocationRoleAssignment(this, role));
            }
        }
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
