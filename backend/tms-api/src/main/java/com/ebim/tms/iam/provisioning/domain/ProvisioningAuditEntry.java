package com.ebim.tms.iam.provisioning.domain;

import java.util.UUID;

/**
 * One row of {@code tms.platform_provisioning_audit}. Never carries the token, the Authorization
 * header or the request body.
 */
public record ProvisioningAuditEntry(
        Operation operation,
        Result result,
        int httpStatus,
        String errorCode,
        UUID controlPlaneTenantId,
        String idempotencyKey,
        UUID provisioningId,
        String correlationId,
        String m2mSubject,
        String m2mJti,
        String actorId,
        String actorRole) {

    public enum Operation { CREATE_TENANT, GET_TENANT_STATUS }

    public enum Result { CREATED, REPLAYED, FOUND, REJECTED, CONFLICT, ERROR }
}
