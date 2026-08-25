package com.ebim.tms.shared.notification;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One alert, as the module raising it describes it (migration V32).
 *
 * <p>Deliberately without a severity and without a company: severity is a property of
 * {@link NotificationType}, and the tenant comes from the {@code CompanyScope} the publisher is
 * called with - the same rule {@code AuditRecorder} follows, so an alert is scoped exactly like the
 * write that produced it rather than like whoever happened to be authenticated.
 *
 * @param type what happened
 * @param entityId the row the panel navigates to. Its kind is fixed by
 *     {@link NotificationType#entityType()} rather than passed here, so a {@code TENDER_REJECTED}
 *     alert cannot be raised pointing at a driver
 * @param entityLabel how to name that row on screen - a shipment number, a driver code.
 *     Snapshotted, so the bell renders without joining two other modules' tables. May be null when
 *     the raiser genuinely has no label; the panel then shows the type alone
 * @param dedupeKey what makes this one fact. Always built through
 *     {@link NotificationType#dedupeKey(UUID)} or its two-argument form
 * @param occurredAt when the fact happened - the operator's own time where they supplied one, not
 *     the instant this record was built
 * @param messageArgs the placeholders the translated sentence needs, and nothing else. No rendered
 *     text, no markup, no secret, and never a business detail the entity's own table already holds
 *     (V32 section 2). May be empty
 */
public record NotificationRequest(
        NotificationType type,
        UUID entityId,
        String entityLabel,
        String dedupeKey,
        OffsetDateTime occurredAt,
        Map<String, Object> messageArgs) {

    public NotificationRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (dedupeKey == null || dedupeKey.isBlank()) {
            throw new IllegalArgumentException("dedupeKey is required: without it an alert cannot be "
                    + "raised once, and a retried request would ring the bell twice");
        }
        messageArgs = messageArgs == null ? Map.of() : Map.copyOf(messageArgs);
    }

    /** The alert's kind, which follows from its type and is never chosen separately. */
    public NotificationEntityType entityType() {
        return type.entityType();
    }

    public NotificationSeverity severity() {
        return type.severity();
    }
}
