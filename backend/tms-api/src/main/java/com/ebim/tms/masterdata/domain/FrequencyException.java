package com.ebim.tms.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A single-date override of a {@link Frequency}'s weekly cadence: an extra service date or a
 * blackout (migration V7), optionally with its own cutoff time (migration V24). Unlike
 * {@link FrequencyWeeklyRule}, exceptions are managed one at a time through their own
 * repository/service methods rather than diffed as a whole collection - each row is an
 * independent calendar fact, not a slot in a fixed Monday-Sunday grid.
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

    /**
     * Replaces the matching {@link FrequencyWeeklyRule}'s cutoff for this date only; {@code null}
     * means "no opinion", so the weekly rule decides. See {@link FrequencyCalendar#effectiveCutoff}
     * - the precedence is stated there once, not re-derived by each reader.
     */
    @Column(name = "cutoff_time_override")
    private LocalTime cutoffTimeOverride;

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

    /**
     * A date exception, optionally replacing the weekly rule's cutoff for that date alone.
     *
     * @throws IllegalArgumentException when a blackout is given a cutoff. Nothing is dispatched
     *     on a closed date, so "the last moment to order" is not a question that has an answer,
     *     and a stored one would survive a later toggle back to open as a time nobody chose.
     *     {@code FrequencyService} rejects the same combination first, as a 400 rather than a
     *     500; this guard is what makes the rule hold for every other write path too, and it
     *     mirrors {@code ck_frequency_exception_cutoff_requires_service} in V24.
     */
    public FrequencyException(UUID frequencyId, LocalDate exceptionDate, boolean serviceOverride,
            LocalTime cutoffTimeOverride, String note, UUID actorId) {
        if (cutoffTimeOverride != null && !serviceOverride) {
            throw new IllegalArgumentException("A closed date cannot carry a cutoff override.");
        }
        this.frequencyId = frequencyId;
        this.exceptionDate = exceptionDate;
        this.serviceOverride = serviceOverride;
        this.cutoffTimeOverride = cutoffTimeOverride;
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

    /** {@code null} when this date does not replace the weekly rule's cutoff - the ordinary case. */
    public LocalTime cutoffTimeOverride() {
        return cutoffTimeOverride;
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
