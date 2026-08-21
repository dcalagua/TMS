package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.IntegrationRequest;
import com.ebim.tms.integration.domain.IntegrationRequestStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One inbox row as the administration API shows it: what a partner sent, what happened, and how
 * long it took.
 *
 * <p>{@code payloadSnapshot} is deliberately absent even when the deployment retains one. The
 * history endpoint answers "did this delivery land", which needs the reference and the status,
 * not the customer names inside the body. Reading a retained payload is a database-level
 * operation for someone investigating an incident, not a routine screen.
 */
public record IntegrationRequestView(
        UUID id,
        UUID integrationClientId,
        String operation,
        String idempotencyKey,
        String externalSystem,
        String externalReference,
        IntegrationRequestStatus status,
        int httpStatus,
        int itemCount,
        int succeededCount,
        int failedCount,
        UUID resourceId,
        String errorSummary,
        String correlationId,
        OffsetDateTime receivedAt,
        OffsetDateTime completedAt,
        int durationMs) {

    public static IntegrationRequestView from(IntegrationRequest request) {
        return new IntegrationRequestView(
                request.id(),
                request.integrationClientId(),
                request.operation(),
                request.idempotencyKey(),
                request.externalSystem(),
                request.externalReference(),
                request.status(),
                request.httpStatus(),
                request.itemCount(),
                request.succeededCount(),
                request.failedCount(),
                request.resourceId(),
                request.errorSummary(),
                request.correlationId(),
                request.receivedAt(),
                request.completedAt(),
                request.durationMs());
    }
}
