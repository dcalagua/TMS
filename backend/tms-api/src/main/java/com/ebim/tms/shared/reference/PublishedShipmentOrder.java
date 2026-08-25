package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * @param deliveryResult     what happened to the goods (migration V28) - {@code "DELIVERED"},
 *                           {@code "PARTIAL"}, {@code "REJECTED"}, {@code "FAILED"},
 *                           {@code "NOT_ATTEMPTED"} - or null when nobody has recorded it yet,
 *                           which is every order on a shipment that has not run. Null and
 *                           {@code NOT_ATTEMPTED} are different answers and are never conflated
 * @param deliveredAt        when the goods changed hands, or null
 * @param deliveryReceiverName who took them. Published because the partner that ordered the
 *                           delivery is the party a signature is shown to; the receiver's identity
 *                           <em>document</em> is deliberately not published - no partner has asked
 *                           for it, and it is the more sensitive half of the pair
 * @param deliveryNotes      why it fell short, for the three results that require an explanation
 * @param evidenceCount      how many artefacts are on file. A count and not links: evidence is
 *                           served only through an authenticated, company-scoped TMS request, so
 *                           the number tells a partner what exists and the API tells them how to
 *                           ask for it
 */
public record PublishedShipmentOrder(
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
