package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One order carried on a published shipment.
 *
 * @param externalSource     the sending system that originally delivered this order (inbound
 *                           integration API), or null for an order created by hand in TMS
 * @param externalReference  that system's own identifier for the order, paired with
 *                           {@code externalSource} - so a consumer of this API can reconcile a
 *                           shipment against the order it originally sent in, without maintaining
 *                           its own mapping from {@code orderNumber}
 * @param weightKg           the amount actually assigned to this trip, not the order's own total
 *                           - identical today (V1 assigns whole orders) but the honest figure the
 *                           day a partial assignment exists
 */
public record PublishedShipmentOrder(
        UUID orderId,
        String orderNumber,
        String externalSource,
        String externalReference,
        String destinationCode,
        BigDecimal weightKg,
        BigDecimal volumeM3,
        BigDecimal pallets) {
}
