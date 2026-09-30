package com.ebim.tms.iam.provisioning.application;

/**
 * The stable error codes of the provisioning contract, with their HTTP status.
 *
 * <p>Changing a code is a contract change: MasterAdmin reads {@code code} from the body and decides
 * whether to retry from the status (400/401/403/404/409 are final, 5xx are retried). The vocabulary
 * is the one eSupplier's GENERIC implementation already uses, so the control plane sees one set of
 * codes across the suite.
 */
public enum ProvisioningErrorCode {

    UNAUTHENTICATED(401, "Missing bearer token"),
    INVALID_M2M_TOKEN(401, "Invalid M2M token"),
    MISSING_SCOPE(403, "Token lacks the required scope"),
    ACCESS_DENIED(403, "Access denied"),
    PROVISIONING_NOT_FOUND(404, "No provisioning exists for this controlPlaneTenantId"),
    IDEMPOTENCY_KEY_REQUIRED(400, "Idempotency-Key header is required"),
    INVALID_IDEMPOTENCY_KEY(400, "Idempotency-Key must match ^[A-Za-z0-9._:-]{8,200}$"),
    INVALID_REQUEST(400, "Request body is invalid"),
    UNSUPPORTED_MEDIA_TYPE(415, "Content-Type must be application/json"),
    IDEMPOTENCY_CONFLICT(409, "Idempotency-Key was already used with a different payload"),
    TENANT_ALREADY_PROVISIONED(409, "This controlPlaneTenantId is already provisioned with the same contract"),
    TENANT_CONFLICT(409, "Tenant conflicts with an existing TMS organization or provisioning"),
    ADMIN_EMAIL_CONFLICT(409, "Admin email belongs to a deactivated TMS profile"),
    PROVISIONING_FAILED(500, "Provisioning failed; no changes were persisted"),
    M2M_NOT_CONFIGURED(503, "Platform provisioning is not enabled");

    private final int httpStatus;
    private final String message;

    ProvisioningErrorCode(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }

    /** A 409: the request was understood and refused because of what already exists. */
    public boolean isConflict() {
        return httpStatus == 409;
    }
}
