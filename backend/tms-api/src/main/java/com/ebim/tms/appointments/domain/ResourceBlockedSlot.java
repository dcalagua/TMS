package com.ebim.tms.appointments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A door closed for a specific interval (migration V41): a holiday, a stocktake, a broken leveller.
 *
 * <p><b>Absolute instants</b>, unlike {@link ResourceCalendarEntry}'s local opening hours. A closure
 * is a specific interval somebody decided on, not a weekly rule, and the two must not be confused -
 * "closed next Tuesday" and "closed on Tuesdays" are different sentences with the same words.
 */
@Entity
@Table(name = "resource_blocked_slot")
public class ResourceBlockedSlot {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "resource_id", updatable = false, nullable = false)
    private UUID resourceId;

    @Column(name = "starts_at", updatable = false, nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", updatable = false, nullable = false)
    private OffsetDateTime endsAt;

    @Column(name = "reason", updatable = false, nullable = false)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected ResourceBlockedSlot() {}

    public ResourceBlockedSlot(UUID companyId, UUID resourceId, OffsetDateTime startsAt, OffsetDateTime endsAt,
            String reason, UUID actorId) {
        this.companyId = companyId;
        this.resourceId = resourceId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.reason = reason;
        this.createdBy = actorId;
    }

    /**
     * Whether this closure overlaps the given window.
     *
     * <p>Half-open on both sides: a booking that ends exactly when a closure begins does not
     * overlap it, which is the same convention {@code tstzrange}'s {@code &&} uses and therefore
     * the same answer the database would give.
     */
    public boolean overlaps(OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        return startsAt.isBefore(windowEnd) && endsAt.isAfter(windowStart);
    }

    public UUID id() {
        return id;
    }

    public UUID resourceId() {
        return resourceId;
    }

    public OffsetDateTime startsAt() {
        return startsAt;
    }

    public OffsetDateTime endsAt() {
        return endsAt;
    }

    public String reason() {
        return reason;
    }
}
