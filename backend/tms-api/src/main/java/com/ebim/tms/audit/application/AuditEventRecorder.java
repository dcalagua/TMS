package com.ebim.tms.audit.application;

import com.ebim.tms.audit.domain.AuditEvent;
import com.ebim.tms.audit.infrastructure.AuditEventRepository;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActor;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.security.CompanyScope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The only implementation of {@link AuditRecorder}: writes one {@link AuditEvent} per call, in
 * the caller's own transaction, and stamps it with the actor {@link AuditActorProvider} resolves
 * from the current request. See {@link AuditRecorder}'s class comment for why every other
 * business module reaches this through the port rather than importing this class.
 *
 * <p>Every call also increments the {@code tms.audit.events} counter, tagged {@code
 * aggregateType} and {@code action} - a cheap, general way to watch volume (import counts among
 * them: {@code aggregateType=MASTER_DATA_IMPORT_BATCH,action=IMPORT_EXECUTED}) without querying
 * {@code tms.audit_event} itself.
 */
@Service
public class AuditEventRecorder implements AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditEventRecorder.class);

    /** Matches {@code ck_audit_event_metadata_length} (migration V22). */
    private static final int MAX_METADATA_LENGTH = 4000;

    private static final String EVENTS_METRIC = "tms.audit.events";

    private final AuditEventRepository repository;
    private final AuditActorProvider auditActorProvider;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public AuditEventRecorder(AuditEventRepository repository, AuditActorProvider auditActorProvider,
            ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.auditActorProvider = auditActorProvider;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void record(CompanyScope scope, AuditAggregateType aggregateType, UUID aggregateId, AuditAction action,
            Map<String, Object> metadata) {
        AuditActor actor = auditActorProvider.current().orElseThrow(() -> new IllegalStateException(
                "no authenticated actor: an audit event must be recorded inside an authenticated request"));
        repository.save(new AuditEvent(scope.companyId(), actor.appUserId(), actor.email(),
                actor.machineActorLabel(), aggregateType, aggregateId, action, actor.correlationId(),
                writeMetadata(aggregateType, metadata)));
        Counter.builder(EVENTS_METRIC)
                .tag("aggregateType", aggregateType.name())
                .tag("action", action.name())
                .register(meterRegistry)
                .increment();
    }

    /**
     * Serialisation failure never loses the event itself - who did what to which aggregate is
     * recorded either way, only the supporting detail is dropped.
     */
    private String writeMetadata(AuditAggregateType aggregateType, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(metadata);
            return json.length() > MAX_METADATA_LENGTH ? json.substring(0, MAX_METADATA_LENGTH) : json;
        } catch (JacksonException notSerialisable) {
            log.warn("Audit event metadata for aggregate type {} could not be serialised; "
                    + "recording the event without it.", aggregateType, notSerialisable);
            return null;
        }
    }
}
