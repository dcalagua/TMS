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
    ORDER_BATCH("order.batch", IntegrationScope.ORDER_WRITE),

    /**
     * A run of reported positions (migration V29). There is no single-position sibling, unlike the
     * two pairs above: a device reporting one ping sends a batch of one, and a second operation
     * would be a second shape of the same thing for no gain - see
     * {@code IntegrationTrackingController}.
     */
    TRACKING_BATCH("tracking.batch", IntegrationScope.TRACKING_WRITE),

    /**
     * A carrier's answer to one tender (migration V31). Singular and with no batch sibling: a
     * carrier answers the shipment in front of them, one commercial decision at a time, and a
     * batch form would invite a client to accept twenty loads with one call and then have to be
     * told which four of them failed.
     *
     * <p>The read side - "which offers am I holding" - is not an operation at all, for the reason
     * {@code IntegrationShipmentController} gives: it has no payload to fingerprint and no
     * idempotency key to honour, so there is nothing to record beyond the access log.
     */
    TENDER_RESPONSE("tender.response", IntegrationScope.TENDER_RESPOND);

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
