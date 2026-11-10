package com.ebim.tms.planning.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * An order delivered at a portal stop (V70): what was loaded for it on this trip - the ceiling a
 * partial delivery is measured against - and the result recorded for it, if any.
 *
 * @param delivery null while nothing has been recorded for this order at this stop
 * @param lines the order's products (V75), so a partial delivery can say which came back. Empty when
 *     only part of the order travels on this trip: its refusal is recorded in kilos
 */
public record PortalStopOrderView(UUID orderId, String orderNumber, String customerName,
        BigDecimal assignedWeightKg, BigDecimal assignedVolumeM3, BigDecimal assignedPallets,
        PortalDeliveryView delivery, List<PortalOrderLineView> lines) {

    /** One product of the order: how many were ordered, in what unit, and what one weighs. */
    public record PortalOrderLineView(UUID orderLineId, int lineNumber, String materialCode,
            String materialDescription, BigDecimal quantity, String uom, BigDecimal unitWeightKg) {
    }
}
