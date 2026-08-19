package com.ebim.tms.masterdata.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A reusable planned corridor/service sequence (migration V8): a company-scoped header plus the
 * ordered {@link RouteStop} children it owns. Deliberately distinct from a future dynamically
 * calculated Trip route - this entity has no geometry, no live position and no optimizer output;
 * it is just a named sequence a planner can point a Trip at later.
 *
 * <p>{@code stops} is owned here: every mutation goes through {@link #replaceStops(List, UUID)},
 * which diffs the incoming ordered list of destination ids against what is already persisted
 * (a destination present in both gets its sequence updated in place, a newly present one is
 * added, a persisted one no longer present is removed via {@code orphanRemoval}) inside the same
 * transaction as the header update - the same pattern {@link Frequency#replaceWeeklyRules} uses
 * for its weekly rules, keyed by destination id instead of day of week.
 */
@Entity
@Table(name = "route")
public class Route {

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

    @Column(name = "origin_id", nullable = false)
    private UUID originId;

    @Column(name = "zone_id")
    private UUID zoneId;

    @Column(name = "frequency_id")
    private UUID frequencyId;

    @Column(name = "reference_distance_km", precision = 8, scale = 2)
    private BigDecimal referenceDistanceKm;

    @Column(name = "reference_duration_minutes")
    private Integer referenceDurationMinutes;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "route", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<RouteStop> stops = new LinkedHashSet<>();

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

    protected Route() {
        // JPA
    }

    public Route(UUID companyId, String code, String name, UUID originId, UUID zoneId, UUID frequencyId,
            BigDecimal referenceDistanceKm, Integer referenceDurationMinutes, UUID actorId) {
        this.companyId = companyId;
        this.code = code;
        this.name = name;
        this.originId = originId;
        this.zoneId = zoneId;
        this.frequencyId = frequencyId;
        this.referenceDistanceKm = referenceDistanceKm;
        this.referenceDurationMinutes = referenceDurationMinutes;
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

    public UUID originId() {
        return originId;
    }

    public UUID zoneId() {
        return zoneId;
    }

    public UUID frequencyId() {
        return frequencyId;
    }

    public BigDecimal referenceDistanceKm() {
        return referenceDistanceKm;
    }

    public Integer referenceDurationMinutes() {
        return referenceDurationMinutes;
    }

    public boolean active() {
        return active;
    }

    /** Ordered stops, ascending by sequence (1..N, no gaps - see {@link #replaceStops}). */
    public List<RouteStop> stops() {
        return stops.stream().sorted(Comparator.comparingInt(RouteStop::sequence)).toList();
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

    public void applyChanges(String code, String name, UUID originId, UUID zoneId, UUID frequencyId,
            BigDecimal referenceDistanceKm, Integer referenceDurationMinutes, UUID actorId) {
        this.code = code;
        this.name = name;
        this.originId = originId;
        this.zoneId = zoneId;
        this.frequencyId = frequencyId;
        this.referenceDistanceKm = referenceDistanceKm;
        this.referenceDurationMinutes = referenceDurationMinutes;
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

    /**
     * Replaces the whole ordered stop list with {@code destinationIds} in one pass: sequence is
     * assigned from list order (1..N), a destination present in both the new list and what is
     * persisted is updated in place (its sequence only), a newly present destination is added,
     * and a persisted destination absent from {@code destinationIds} is removed (deleted on
     * flush via {@code orphanRemoval}). The caller must have already rejected a duplicate
     * destination id within {@code destinationIds} - a {@link Map} would otherwise silently drop
     * one, exactly as {@link Frequency#replaceWeeklyRules} requires for {@code dayOfWeek}.
     */
    public void replaceStops(List<UUID> destinationIds, UUID actorId) {
        Map<UUID, Integer> targetSequenceByDestination = new LinkedHashMap<>();
        int sequence = 1;
        for (UUID destinationId : destinationIds) {
            targetSequenceByDestination.put(destinationId, sequence++);
        }

        stops.removeIf(stop -> !targetSequenceByDestination.containsKey(stop.destinationId()));

        Map<UUID, RouteStop> existingByDestination = new LinkedHashMap<>();
        for (RouteStop stop : stops) {
            existingByDestination.put(stop.destinationId(), stop);
        }

        for (Map.Entry<UUID, Integer> entry : targetSequenceByDestination.entrySet()) {
            RouteStop existing = existingByDestination.get(entry.getKey());
            if (existing != null) {
                existing.applyChanges(entry.getValue(), actorId);
            } else {
                stops.add(new RouteStop(this, companyId, entry.getKey(), entry.getValue(), actorId));
            }
        }
    }
}
