package com.ebim.tms.masterdata.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
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
 * A named delivery schedule (migration V7): a company-scoped header plus the
 * {@link FrequencyWeeklyRule} children that carry its Monday-Sunday cadence. Deliberately not
 * five or seven hardcoded boolean columns - the brief this implements asks for a model that can
 * grow fortnightly, cutoff and lead-time behaviour without another migration touching this
 * table, and {@link com.ebim.tms.masterdata.domain.FrequencyException} covers date-specific
 * overrides separately.
 *
 * <p>{@code weeklyRules} is owned here: every mutation goes through {@link
 * #replaceWeeklyRules(List, UUID)}, which diffs the incoming set against what is already
 * persisted (update matching days, add new ones, remove missing ones via {@code
 * orphanRemoval}) inside the same transaction as the header update - so a
 * {@code FrequencyService.update} call can never leave a partially-replaced, orphaned rule
 * behind.
 */
@Entity
@Table(name = "frequency")
public class Frequency {

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

    @Column(name = "description")
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "frequency", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<FrequencyWeeklyRule> weeklyRules = new LinkedHashSet<>();

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

    protected Frequency() {
        // JPA
    }

    public Frequency(UUID companyId, String code, String name, String description, UUID actorId) {
        this.companyId = companyId;
        this.code = code;
        this.name = name;
        this.description = description;
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

    public String description() {
        return description;
    }

    public boolean active() {
        return active;
    }

    /** Configured days, ascending Monday(1)..Sunday(7); days with no row are simply absent. */
    public List<FrequencyWeeklyRule> weeklyRules() {
        return weeklyRules.stream().sorted(Comparator.comparingInt(FrequencyWeeklyRule::dayOfWeek)).toList();
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

    public void applyChanges(String code, String name, String description, UUID actorId) {
        this.code = code;
        this.name = name;
        this.description = description;
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
     * Replaces the whole weekly cadence with {@code inputs} in one pass: a day present in
     * {@code inputs} but not yet persisted is added, a day present in both is updated in place,
     * and a persisted day absent from {@code inputs} is removed (deleted on flush via {@code
     * orphanRemoval}). The caller must have already rejected a duplicate {@code dayOfWeek}
     * within {@code inputs} - see {@link FrequencyWeeklyRuleInput}.
     */
    public void replaceWeeklyRules(List<FrequencyWeeklyRuleInput> inputs, UUID actorId) {
        Map<Integer, FrequencyWeeklyRuleInput> byDay = new LinkedHashMap<>();
        for (FrequencyWeeklyRuleInput input : inputs) {
            byDay.put(input.dayOfWeek(), input);
        }

        weeklyRules.removeIf(rule -> !byDay.containsKey(rule.dayOfWeek()));

        Map<Integer, FrequencyWeeklyRule> existingByDay = new LinkedHashMap<>();
        for (FrequencyWeeklyRule rule : weeklyRules) {
            existingByDay.put(rule.dayOfWeek(), rule);
        }

        for (FrequencyWeeklyRuleInput input : byDay.values()) {
            FrequencyWeeklyRule existing = existingByDay.get(input.dayOfWeek());
            if (existing != null) {
                existing.applyChanges(input.enabled(), input.cutoffTime(), input.leadTimeDays(), actorId);
            } else {
                weeklyRules.add(new FrequencyWeeklyRule(
                        this, input.dayOfWeek(), input.enabled(), input.cutoffTime(), input.leadTimeDays(), actorId));
            }
        }
    }
}
