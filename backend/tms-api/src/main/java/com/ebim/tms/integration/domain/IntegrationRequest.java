package com.ebim.tms.integration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One row of the integration inbox (migration V18): what a partner delivered, what happened to
 * it, and when.
 *
 * <p><b>Write-once.</b> Every column is {@code updatable = false} and there is no mutator. An
 * audit record that can be edited is not an audit record, and making that a mapping property
 * rather than a convention means a future {@code save()} on a loaded row cannot quietly rewrite
 * history.
 *
 * <p>It carries no credential material. {@code integrationClientId} names the credential, which
 * is an identity and not a secret; the secret and the bearer token appear nowhere.
 */
@Entity
@Table(name = "integration_request")
public class IntegrationRequest {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "integration_client_id", updatable = false, nullable = false)
    private UUID integrationClientId;

    @Column(name = "operation", updatable = false, nullable = false)
    private String operation;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "external_system", updatable = false)
    private String externalSystem;

    @Column(name = "external_reference", updatable = false)
    private String externalReference;

    @Column(name = "payload_hash", updatable = false, nullable = false)
    private String payloadHash;

    @Column(name = "payload_snapshot", updatable = false)
    private String payloadSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", updatable = false, nullable = false)
    private IntegrationRequestStatus status;

    @Column(name = "http_status", updatable = false, nullable = false)
    private int httpStatus;

    @Column(name = "item_count", updatable = false, nullable = false)
    private int itemCount;

    @Column(name = "succeeded_count", updatable = false, nullable = false)
    private int succeededCount;

    @Column(name = "failed_count", updatable = false, nullable = false)
    private int failedCount;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @Column(name = "response_body", updatable = false)
    private String responseBody;

    @Column(name = "error_summary", updatable = false)
    private String errorSummary;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "received_at", updatable = false, nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "completed_at", updatable = false, nullable = false)
    private OffsetDateTime completedAt;

    @Column(name = "duration_ms", updatable = false, nullable = false)
    private int durationMs;

    protected IntegrationRequest() {
        // JPA
    }

    /**
     * The only constructor: an inbox row exists complete or not at all. Long, and deliberately
     * not softened with a builder - a builder would make it possible to forget the payload hash
     * or the status, which are the two fields the record is for.
     */
    public IntegrationRequest(UUID companyId, UUID integrationClientId, IntegrationOperation operation,
            String idempotencyKey, String externalSystem, String externalReference, String payloadHash,
            String payloadSnapshot, IntegrationRequestStatus status, int httpStatus, int itemCount,
            int succeededCount, int failedCount, UUID resourceId, String responseBody, String errorSummary,
            String correlationId, OffsetDateTime receivedAt, OffsetDateTime completedAt) {
        this.companyId = companyId;
        this.integrationClientId = integrationClientId;
        this.operation = operation.code();
        this.idempotencyKey = idempotencyKey;
        this.externalSystem = externalSystem;
        this.externalReference = externalReference;
        this.payloadHash = payloadHash;
        this.payloadSnapshot = payloadSnapshot;
        this.status = status;
        this.httpStatus = httpStatus;
        this.itemCount = itemCount;
        this.succeededCount = succeededCount;
        this.failedCount = failedCount;
        this.resourceId = resourceId;
        this.responseBody = responseBody;
        this.errorSummary = errorSummary;
        this.correlationId = correlationId;
        this.receivedAt = receivedAt;
        this.completedAt = completedAt.isBefore(receivedAt) ? receivedAt : completedAt;
        this.durationMs = (int) Math.max(0,
                java.time.Duration.between(receivedAt, this.completedAt).toMillis());
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID integrationClientId() {
        return integrationClientId;
    }

    public String operation() {
        return operation;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String externalSystem() {
        return externalSystem;
    }

    public String externalReference() {
        return externalReference;
    }

    public String payloadHash() {
        return payloadHash;
    }

    public String payloadSnapshot() {
        return payloadSnapshot;
    }

    public IntegrationRequestStatus status() {
        return status;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public int itemCount() {
        return itemCount;
    }

    public int succeededCount() {
        return succeededCount;
    }

    public int failedCount() {
        return failedCount;
    }

    public UUID resourceId() {
        return resourceId;
    }

    public String responseBody() {
        return responseBody;
    }

    public String errorSummary() {
        return errorSummary;
    }

    public String correlationId() {
        return correlationId;
    }

    public OffsetDateTime receivedAt() {
        return receivedAt;
    }

    public OffsetDateTime completedAt() {
        return completedAt;
    }

    public int durationMs() {
        return durationMs;
    }
}
