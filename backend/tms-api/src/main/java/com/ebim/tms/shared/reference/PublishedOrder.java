package com.ebim.tms.shared.reference;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A transport order as the integration API shows it to a partner that reads orders back
 * (WAREHOUSE_EXECUTION_V1 7.4): its key, its status and, when cancelled, when and why.
 *
 * @param status an {@code OrderStatus} name; a string because the enum lives in {@code orders}
 * @param cancelledAt when it was cancelled, or null. A cancelled order cannot be edited again, so its
 *     last update is its cancellation
 */
public record PublishedOrder(
        UUID id,
        String orderNumber,
        String externalSource,
        String externalReference,
        String status,
        OffsetDateTime cancelledAt,
        String cancelReason,
        OffsetDateTime updatedAt) {
}
