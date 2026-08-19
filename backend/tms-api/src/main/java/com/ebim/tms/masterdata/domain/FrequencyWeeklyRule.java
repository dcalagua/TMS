package com.ebim.tms.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One configured day of a {@link Frequency}'s weekly cadence (migration V7). A pure child of
 * its {@link Frequency}, exactly like {@code MembershipRole} is a child of {@code Membership}:
 * it is only ever created, updated or removed through {@link Frequency}'s own methods, never
 * persisted on its own.
 */
@Entity
@Table(name = "frequency_weekly_rule")
public class FrequencyWeeklyRule {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "frequency_id", nullable = false, updatable = false)
    private Frequency frequency;

    /** ISO-8601: 1 = Monday .. 7 = Sunday. */
    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "cutoff_time")
    private LocalTime cutoffTime;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

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

    protected FrequencyWeeklyRule() {
        // JPA
    }

    FrequencyWeeklyRule(Frequency frequency, int dayOfWeek, boolean enabled, LocalTime cutoffTime,
            Integer leadTimeDays, UUID actorId) {
        this.frequency = frequency;
        this.dayOfWeek = dayOfWeek;
        this.enabled = enabled;
        this.cutoffTime = cutoffTime;
        this.leadTimeDays = leadTimeDays;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public int dayOfWeek() {
        return dayOfWeek;
    }

    public boolean enabled() {
        return enabled;
    }

    public LocalTime cutoffTime() {
        return cutoffTime;
    }

    public Integer leadTimeDays() {
        return leadTimeDays;
    }

    void applyChanges(boolean enabled, LocalTime cutoffTime, Integer leadTimeDays, UUID actorId) {
        this.enabled = enabled;
        this.cutoffTime = cutoffTime;
        this.leadTimeDays = leadTimeDays;
        this.updatedBy = actorId;
    }
}
