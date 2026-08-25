package com.ebim.tms.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One ordered destination stop of a {@link Route} (migration V8). A pure child of its route in
 * lifecycle terms - only ever created, updated or removed through {@link Route#replaceStops} -
 * but unlike {@link FrequencyWeeklyRule} it carries its own {@code companyId}, denormalized from
 * the parent route, because it references another company-scoped table ({@code destination_id})
 * and needs the same composite-FK tenant guarantee {@code Destination.zoneId} uses (see the V8
 * migration comment and {@code docs/database/DATA_MODEL.md} section 9 rule 6).
 */
@Entity
@Table(name = "route_stop")
public class RouteStop {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false, updatable = false)
    private Route route;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "destination_id", updatable = false, nullable = false)
    private UUID destinationId;

    /** 1-based position within the route, contiguous - see {@link Route#replaceStops}. */
    @Column(name = "sequence", nullable = false)
    private int sequence;

    /**
     * Per-stop replacement for the destination {@link Location}'s service time, on this route
     * only (migration V24); {@code null} - the ordinary case - means "use the location's". See
     * {@link #effectiveServiceTimeMinutes}.
     */
    @Column(name = "service_time_override_minutes")
    private Integer serviceTimeOverrideMinutes;

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

    protected RouteStop() {
        // JPA
    }

    RouteStop(Route route, UUID companyId, UUID destinationId, int sequence, Integer serviceTimeOverrideMinutes,
            UUID actorId) {
        this.route = route;
        this.companyId = companyId;
        this.destinationId = destinationId;
        this.sequence = sequence;
        this.serviceTimeOverrideMinutes = serviceTimeOverrideMinutes;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public UUID destinationId() {
        return destinationId;
    }

    public int sequence() {
        return sequence;
    }

    /** {@code null} when this stop does not override the location's service time - the ordinary case. */
    public Integer serviceTimeOverrideMinutes() {
        return serviceTimeOverrideMinutes;
    }

    /**
     * How long this stop actually takes:
     *
     * <pre>{@code effectiveServiceTime = routeStop.serviceTimeOverride ?? location.serviceTimeMinutes}</pre>
     *
     * <p>{@link Location} stays the source of truth. This method never writes back to it, and an
     * override says nothing about the place in general - only that <em>this corridor</em> serves
     * it differently (the night route reaching a store off-hours, say). Stated here, once, so
     * every reader resolves it the same way instead of each writing its own {@code ?:}.
     *
     * @param destination the stop's destination, or {@code null} when it could not be resolved -
     *     rendering a saved route is deliberately laxer than editing one ({@code RouteService}),
     *     so a caller may legitimately hold no location here. Then only an override can answer,
     *     and without one the result is {@code null}: unknown, not zero.
     */
    public Integer effectiveServiceTimeMinutes(Location destination) {
        if (serviceTimeOverrideMinutes != null) {
            return serviceTimeOverrideMinutes;
        }
        return destination == null ? null : destination.serviceTimeMinutes();
    }

    void applyChanges(int sequence, Integer serviceTimeOverrideMinutes, UUID actorId) {
        this.sequence = sequence;
        this.serviceTimeOverrideMinutes = serviceTimeOverrideMinutes;
        this.updatedBy = actorId;
    }
}
