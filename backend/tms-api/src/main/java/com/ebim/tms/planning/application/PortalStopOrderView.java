package com.ebim.tms.planning.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An order delivered at a portal stop (V70): what was loaded for it on this trip - the ceiling a
 * partial delivery is measured against - and the result recorded for it, if any.
 *
 * @param delivery null while nothing has been recorded for this order at this stop
 */
public record PortalStopOrderView(UUID orderId, String orderNumber, String customerName,
        BigDecimal assignedWeightKg, BigDecimal assignedVolumeM3, BigDecimal assignedPallets,
        PortalDeliveryView delivery) {
}
