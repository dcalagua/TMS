package com.ebim.tms.integration.application;

import java.time.OffsetDateTime;

/**
 * A transport order as a partner reads it back (WAREHOUSE_EXECUTION_V1 7.4): one body of
 * {@code GET /integration/v1/orders/{orderNumber}} and one row of {@code GET /integration/v1/orders}.
 * The frozen wire shape; {@code shared.reference.PublishedOrder} is free to change behind it.
 *
 * @param status an order status name, e.g. {@code CANCELLED}
 * @param cancelledAt when it was cancelled, null otherwise
 */
public record OrderStatusV1(
        String orderNumber,
        String externalSource,
        String externalReference,
        String status,
        OffsetDateTime cancelledAt,
        String cancelReason,
        OffsetDateTime updatedAt) {
}
