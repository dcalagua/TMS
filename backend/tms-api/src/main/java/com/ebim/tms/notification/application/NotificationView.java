package com.ebim.tms.notification.application;

import com.ebim.tms.shared.notification.NotificationEntityType;
import com.ebim.tms.shared.notification.NotificationSeverity;
import com.ebim.tms.shared.notification.NotificationType;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One alert as the bell reads it.
 *
 * <p><b>No title and no message.</b> The response carries {@link #type} and {@link #messageArgs},
 * and the frontend's {@code notifications} namespace turns the pair into a sentence in whichever
 * language the operator is reading. Migration V32 section 2 gives the reason: a sentence rendered
 * server-side is rendered in one language and one wording, and the history would be stuck in both.
 *
 * @param entityLabel how to name the thing this is about - a shipment number, a driver code.
 *     Snapshotted when the alert was raised, so an alert about a shipment that has since been
 *     renumbered still says what it said at the time. Null only for an alert raised without one
 * @param messageArgs the placeholders {@link #type}'s sentence needs. Empty when the alert was
 *     raised without any, and empty as well - deliberately - when the stored JSON can no longer be
 *     parsed: an alert that renders with a gap in it is better than an alert that fails a panel
 * @param readAt when somebody in this company acknowledged it, or null. Not per-user - see
 *     migration V32 section 3
 * @param resolvedAt when the condition behind it closed, or null. Independent of {@link #readAt}:
 *     a problem can be fixed without anybody opening the bell
 */
public record NotificationView(
        UUID id,
        NotificationType type,
        NotificationSeverity severity,
        NotificationEntityType entityType,
        UUID entityId,
        String entityLabel,
        Map<String, Object> messageArgs,
        OffsetDateTime occurredAt,
        OffsetDateTime readAt,
        OffsetDateTime resolvedAt) {
}
