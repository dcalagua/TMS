package com.ebim.tms.integration.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One order carried on a shipment, in the external {@code ShipmentPlan V1} contract.
 *
 * @param externalSource     the system that originally sent this order to TMS through the inbound
 *                           integration API, or null for an order created by hand
 * @param externalReference  that system's own identifier for the order - lets a partner reconcile
 *                           a shipment against the order it sent in without maintaining its own
 *                           mapping from {@code orderNumber}
 * @param weightKg           the amount actually assigned to this trip, not the order's own total
 */
public record ShipmentPlanOrderV1(
        UUID orderId,
        String orderNumber,
        String externalSource,
        String externalReference,
        String destinationCode,
        BigDecimal weightKg,
        BigDecimal volumeM3,
        BigDecimal pallets) {
}
