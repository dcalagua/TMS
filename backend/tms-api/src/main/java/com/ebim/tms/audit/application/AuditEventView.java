package com.ebim.tms.audit.application;

import com.ebim.tms.audit.domain.AuditEvent;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditAggregateType;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One entry of the audit trail, as a compliance or administration screen reads it.
 *
 * <p>{@code metadata} is parsed into a map rather than passed through as the stored JSON string.
 * The screen renders it as labelled rows, and handing a client a string to parse invites two
 * parsers with two opinions about a malformed one. When it cannot be parsed - which means
 * something truncated it on the way in ({@code AuditEventRecorder} caps it at 4000 characters) -
 * the entry is still served, with an empty map: the fact that the action happened is what the
 * trail is for, and losing the whole row because its annotation is unreadable would be the wrong
 * trade.
 *
 * <p>Both actor fields can be null and both being null is not an error: {@code AuditActor} is
 * either a person or a machine, and a machine has a label instead of an email. The screen shows
 * whichever is there.
 *
 * @param actorEmail        the person who did it, or null when a machine did
 * @param actorMachineLabel the credential that did it, or null when a person did
 * @param metadata          the action's own annotation - never a payload, never credentials
 */
public record AuditEventView(
        UUID id,
        OffsetDateTime occurredAt,
        UUID actorAppUserId,
        String actorEmail,
        String actorMachineLabel,
        AuditAggregateType aggregateType,
        UUID aggregateId,
        AuditAction action,
        String correlationId,
        Map<String, String> metadata) {

    static AuditEventView from(AuditEvent event, Map<String, String> metadata) {
        return new AuditEventView(event.id(), event.occurredAt(), event.actorAppUserId(), event.actorEmail(),
                event.actorMachineLabel(), event.aggregateType(), event.aggregateId(), event.action(),
                event.correlationId(), metadata);
    }
}
