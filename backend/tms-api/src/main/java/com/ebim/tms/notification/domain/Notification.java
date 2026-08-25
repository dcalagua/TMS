package com.ebim.tms.notification.domain;

import com.ebim.tms.shared.notification.NotificationEntityType;
import com.ebim.tms.shared.notification.NotificationSeverity;
import com.ebim.tms.shared.notification.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One operational alert (migration V32): what happened, what it is about, and whether anybody here
 * has dealt with it yet.
 *
 * <p><b>This entity never inserts.</b> Rows are written by
 * {@code NotificationRepository.insertIfAbsent}, a native {@code INSERT ... ON CONFLICT DO NOTHING}
 * - see that method and V32's comment on {@code uq_notification_company_dedupe} for why a
 * check-then-insert would be the wrong shape. There is no public constructor for that reason, and
 * every column but three is {@code updatable = false}. The three that are not are the two acts that
 * can happen to an alert after it exists - acknowledging it and resolving it.
 *
 * <p><b>Read is a company act, not a personal one.</b> {@link #readAt()} means "somebody here has
 * seen this", and there is no per-user receipt. Migration V32 section 3 states the cost of that and
 * what changing it would take; the short version is that an operational alert is work to be done
 * once, and a per-user inbox would show one phone call to five people.
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", updatable = false, nullable = false)
    private NotificationType type;

    /**
     * Stored beside the type even though {@link NotificationType#severity()} derives it.
     * Deliberate, and the opposite of the rule {@code DriverLicenseStatus} follows: a licence
     * status changes every midnight and must be computed, while an alert's severity is what the
     * board said at the moment it was raised. Keeping the column means a severity reclassified in a
     * later build does not silently rewrite what an operator was shown last quarter.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", updatable = false, nullable = false)
    private NotificationSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", updatable = false, nullable = false)
    private NotificationEntityType entityType;

    @Column(name = "entity_id", updatable = false, nullable = false)
    private UUID entityId;

    @Column(name = "entity_label", updatable = false)
    private String entityLabel;

    /** Compact JSON, placeholders only. Parsed by {@code NotificationService} on the way out. */
    @Column(name = "message_args", updatable = false)
    private String messageArgs;

    @Column(name = "dedupe_key", updatable = false, nullable = false)
    private String dedupeKey;

    @Column(name = "occurred_at", updatable = false, nullable = false)
    private OffsetDateTime occurredAt;

    /** The database default owns this - no {@code @CreationTimestamp}, because nothing here inserts. */
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "read_by")
    private UUID readBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    protected Notification() {
        // JPA - and the only constructor there is, by design. See the class comment.
    }

    /**
     * Acknowledges the alert on behalf of the whole company.
     *
     * <p>A second call is ignored rather than refused, and the first reader keeps the credit: the
     * intent was reached, and rewriting {@code read_by} to whoever clicked last would turn "who
     * picked this up" into "who looked at it most recently", which is not the same question.
     */
    public void markRead(OffsetDateTime readAt, UUID readBy) {
        if (this.readAt != null) {
            return;
        }
        this.readAt = readAt;
        this.readBy = readBy;
    }

    /**
     * Records that the condition behind the alert has closed.
     *
     * <p>Independent of {@link #markRead}: a problem can be fixed by the person who caused it
     * without anybody ever opening the bell, and an alert nobody read is still resolved. Idempotent
     * for the reason above - the first resolution is the one that happened.
     */
    public void resolve(OffsetDateTime resolvedAt) {
        if (this.resolvedAt != null) {
            return;
        }
        this.resolvedAt = resolvedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public NotificationType type() {
        return type;
    }

    public NotificationSeverity severity() {
        return severity;
    }

    public NotificationEntityType entityType() {
        return entityType;
    }

    public UUID entityId() {
        return entityId;
    }

    public String entityLabel() {
        return entityLabel;
    }

    public String messageArgs() {
        return messageArgs;
    }

    public String dedupeKey() {
        return dedupeKey;
    }

    public OffsetDateTime occurredAt() {
        return occurredAt;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime readAt() {
        return readAt;
    }

    public UUID readBy() {
        return readBy;
    }

    public OffsetDateTime resolvedAt() {
        return resolvedAt;
    }

    public boolean isUnread() {
        return readAt == null;
    }
}
