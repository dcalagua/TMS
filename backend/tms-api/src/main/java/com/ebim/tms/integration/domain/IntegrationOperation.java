package com.ebim.tms.integration.domain;

/**
 * The inbound operations the versioned API exposes, as they are recorded in the integration
 * inbox and as they scope an {@code Idempotency-Key}.
 *
 * <p>An idempotency key is unique per (company, credential, operation), not globally: the same
 * partner correlation id may legitimately accompany a location delivery and an order delivery,
 * and forcing them to differ would be a rule about our storage leaking into their design.
 */
public enum IntegrationOperation {

    LOCATION_UPSERT("location.upsert", IntegrationScope.LOCATION_WRITE),
    LOCATION_BATCH("location.batch", IntegrationScope.LOCATION_WRITE),
    ORDER_UPSERT("order.upsert", IntegrationScope.ORDER_WRITE),
    ORDER_BATCH("order.batch", IntegrationScope.ORDER_WRITE);

    private final String code;
    private final IntegrationScope requiredScope;

    IntegrationOperation(String code, IntegrationScope requiredScope) {
        this.code = code;
        this.requiredScope = requiredScope;
    }

    /** The value stored in {@code integration_request.operation}. */
    public String code() {
        return code;
    }

    /**
     * The scope an endpoint for this operation must require. Declared here so the answer lives
     * next to the operation, even though the actual check is the {@code @PreAuthorize} on the
     * controller method - a constant expression is what method security can evaluate.
     */
    public IntegrationScope requiredScope() {
        return requiredScope;
    }
}
