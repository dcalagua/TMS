package com.ebim.tms.platform.application;

import com.ebim.tms.platform.domain.PlatformProvisioning;
import com.ebim.tms.platform.infrastructure.PlatformProvisioningRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes down why a provisioning call failed, in a transaction of its own.
 *
 * <p>{@code REQUIRES_NEW} is the whole point. The business transaction has already rolled back by
 * the time this runs - that is what guarantees no half-built tenant survives - and a record written
 * inside it would have rolled back with it, leaving a control plane that got a 500 and a TMS that
 * has no idea anything was ever attempted.
 *
 * <p>What it stores is deliberately thin: the problem code, and a short business sentence. Never an
 * exception message, a stack frame or SQL. The stack trace belongs in the log, behind the
 * correlation id that is also on this row.
 */
@Service
public class ProvisioningJournal {

    private final PlatformProvisioningRepository repository;
    private final ProvisioningProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ProvisioningJournal(PlatformProvisioningRepository repository, ProvisioningProperties properties,
            ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(TenantProvisioningRequest request, ProvisioningCaller caller, String idempotencyKey,
            String correlationId, String errorCode, String errorSummary, int httpStatus,
            OffsetDateTime receivedAt) {

        String payloadHash = ProvisioningPayloadHash.of(mapper, request);
        String snapshot = properties.retainRequestSnapshot() ? mapper.writeValueAsString(request) : null;

        PlatformProvisioning record = repository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    existing.retrying(request.controlPlaneTenantId(), payloadHash, snapshot, correlationId,
                            caller.subject(), caller.jti(), caller.actorId(), caller.actorRole());
                    return existing;
                })
                .orElseGet(() -> PlatformProvisioning.attempt(request.controlPlaneTenantId(), idempotencyKey,
                        payloadHash, snapshot, correlationId, caller.subject(), caller.jti(),
                        caller.actorId(), caller.actorRole(), receivedAt));

        record.failed(errorCode, errorSummary, httpStatus, OffsetDateTime.now(clock));
        repository.save(record);
    }
}
