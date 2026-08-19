package com.ebim.tms.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A single-date override of a {@link Frequency}'s weekly cadence: an extra service date or a
 * blackout (migration V7). Unlike {@link FrequencyWeeklyRule}, exceptions are managed one at a
 * time through their own repository/service methods rather than diffed as a whole collection -
 * each row is an independent calendar fact, not a slot in a fixed Monday-Sunday grid.
 */
@Entity
@Table(name = "frequency_exception")
public class FrequencyException {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "frequency_id", nullable = false, updatable = false)
    private UUID frequencyId;

    @Column(name = "exception_date", nullable = false)
    private LocalDate exceptionDate;

    @Column(name = "service_override", nullable = false)
    private boolean serviceOverride;

    @Column(name = "note")
    private String note;

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

    protected FrequencyException() {
        // JPA
    }

    public FrequencyException(UUID frequencyId, LocalDate exceptionDate, boolean serviceOverride, String note,
            UUID actorId) {
        this.frequencyId = frequencyId;
        this.exceptionDate = exceptionDate;
        this.serviceOverride = serviceOverride;
        this.note = note;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public UUID frequencyId() {
        return frequencyId;
    }

    public LocalDate exceptionDate() {
        return exceptionDate;
    }

    public boolean serviceOverride() {
        return serviceOverride;
    }

    public String note() {
        return note;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }
}
