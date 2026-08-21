package com.ebim.tms.integration.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One order carried on a shipment, in the external {@code ShipmentPlan V1} contract.
 *
 * <p>The five delivery fields arrived with migration V28 and are <b>additive</b>: a partner that
 * integrated before them keeps seeing exactly what it saw, and one that wants delivery outcomes
 * reads them here after a {@code DELIVERY_RESULT_RECORDED} event tells it there is something new.
 * That pairing - a lean event, a re-read of the shipment - is the outbox contract V20 established,
 * and it is why the event carries no result of its own.
 *
 * @param externalSource     the system that originally sent this order to TMS through the inbound
 *                           integration API, or null for an order created by hand
 * @param externalReference  that system's own identifier for the order - lets a partner reconcile
 *                           a shipment against the order it sent in without maintaining its own
 *                           mapping from {@code orderNumber}
 * @param weightKg           the amount actually assigned to this trip, not the order's own total
 * @param deliveryResult     {@code "DELIVERED"}, {@code "PARTIAL"}, {@code "REJECTED"},
 *                           {@code "FAILED"}, {@code "NOT_ATTEMPTED"}, or null when nobody has
 *                           recorded the delivery yet. Null is "not known", never "not delivered"
 * @param deliveredAt        when the goods changed hands, or null
 * @param deliveryReceiverName who took them, where a name was recorded. The receiver's identity
 *                           document is deliberately not published
 * @param deliveryNotes      why the delivery fell short, for the results that require it
 * @param evidenceCount      how many proof-of-delivery artefacts are on file. A count and not
 *                           links - the bytes are served only through an authenticated,
 *                           company-scoped TMS request
 */
public record ShipmentPlanOrderV1(
        UUID orderId,
        String orderNumber,
        String externalSource,
        String externalReference,
        String destinationCode,
        BigDecimal weightKg,
        BigDecimal volumeM3,
        BigDecimal pallets,
        String deliveryResult,
        OffsetDateTime deliveredAt,
        String deliveryReceiverName,
        String deliveryNotes,
        int evidenceCount) {
}
