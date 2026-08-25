package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TransportEvent;
import com.ebim.tms.planning.domain.TransportEventSource;
import com.ebim.tms.planning.domain.TransportEventType;
import com.ebim.tms.planning.infrastructure.TransportEventRepository;
import com.ebim.tms.shared.audit.AuditActor;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The only writer of {@code tms.transport_event} (migration V27): every execution fact of a trip
 * and its stops enters the timeline through here.
 *
 * <p>One writer rather than five call sites, for the reason {@link ShipmentEventPublisher} gives
 * for its own pairing: the actor, the source and the "does this event type name a stop" rule must
 * be decided once, or a stop transition somewhere will eventually be logged as a trip one.
 *
 * <p>Runs in the caller's transaction and declares none of its own - the timeline entry and the
 * transition it describes stand or fall together, exactly as the outbox row does. A dispatch that
 * rolls back must not leave a line saying the truck left.
 *
 * <p>The actor is resolved server-side from the request, never accepted from a payload: a log the
 * client can sign somebody else's name to is not a log. That mirrors
 * {@code AuditEventRecorder}'s contract and is why this takes no actor parameter.
 */
@Component
public class TransportEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(TransportEventRecorder.class);

    /** Matches {@code ck_transport_event_metadata_length} (migration V27). */
    private static final int MAX_METADATA_LENGTH = 4000;

    private final TransportEventRepository repository;
    private final AuditActorProvider auditActorProvider;
    private final ObjectMapper objectMapper;

    public TransportEventRecorder(TransportEventRepository repository, AuditActorProvider auditActorProvider,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.auditActorProvider = auditActorProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * Appends one entry to a trip's timeline.
     *
     * @param tripStopId the stop this happened at, or null for a trip-level fact. Which types
     *     require which is {@link TransportEventType}'s answer and {@link TransportEvent}'s
     *     assertion
     * @param eventTime when the fact happened - the operator's own time, so a backdated departure
     *     lands in the timeline where it belongs rather than where it was typed
     * @param notes optional free text from whoever recorded it
     * @param metadata compact business detail; never a secret, never a copy of the row this points
     *     at, and dropped rather than allowed to fail the write if it cannot be serialised
     */
    public TransportEvent record(CompanyScope scope, UUID tripId, UUID tripStopId, TransportEventType eventType,
            OffsetDateTime eventTime, String notes, Map<String, Object> metadata) {
        AuditActor actor = auditActorProvider.current().orElseThrow(() -> new IllegalStateException(
                "no authenticated actor: a transport event must be recorded inside an authenticated request"));
        return repository.saveAndFlush(new TransportEvent(
                scope.companyId(), tripId, tripStopId, eventType, eventTime, sourceOf(actor),
                actor.appUserId(), actor.email(), actor.machineActorLabel(),
                // No position in V1: nothing reports one. The columns exist so that the day a
                // telematics feed or a driver app does, recording it is a change here and not a
                // migration - see the column comment in V27.
                null, null,
                blankToNull(notes), writeMetadata(eventType, metadata)));
    }

    /**
     * A person acting in the UI is an {@code OPERATOR}; an authenticated partner over the M2M API
     * is an {@code INTEGRATION}. {@link TransportEventSource#SYSTEM} has no producer yet and is
     * deliberately unreachable from here: it describes a fact nobody typed, and every path into
     * this recorder today runs inside somebody's request.
     */
    private static TransportEventSource sourceOf(AuditActor actor) {
        return actor.isMachine() ? TransportEventSource.INTEGRATION : TransportEventSource.OPERATOR;
    }

    /**
     * Serialisation failure never loses the event itself - what happened, when and to whom is
     * recorded either way, only the supporting detail is dropped. Same trade-off, same reasoning
     * as {@code AuditEventRecorder.writeMetadata}.
     */
    private String writeMetadata(TransportEventType eventType, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(metadata);
            return json.length() > MAX_METADATA_LENGTH ? json.substring(0, MAX_METADATA_LENGTH) : json;
        } catch (JacksonException notSerialisable) {
            log.warn("Transport event metadata for {} could not be serialised; recording the event without it.",
                    eventType, notSerialisable);
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
