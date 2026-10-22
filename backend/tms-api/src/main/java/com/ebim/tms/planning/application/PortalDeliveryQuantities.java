package com.ebim.tms.planning.application;

import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.reference.OrderAmounts;

/**
 * The nine figures of a partial delivery (V45) from the one a driver knows: what the customer
 * refused (V70).
 *
 * <p>The attempt is what this trip carried for the order - its allocation - and what was delivered is
 * the attempt less the refusal. Nothing is left outstanding: a driver at the door reports what they
 * handed over and what came back on the truck, and an amount that is neither is a question for the
 * office, which can still correct the row with all nine figures.
 */
final class PortalDeliveryQuantities {

    private PortalDeliveryQuantities() {
    }

    static DeliveryResultRequest.DeliveryQuantitiesRequest fromRefused(OrderAmounts allocated, OrderAmounts refused) {
        OrderAmounts attempted = allocated.withoutGeometry();
        OrderAmounts refusedMeasures = refused.withoutGeometry();
        if (refusedMeasures.isNegative()) {
            throw new InvalidRequestException("Refused quantities cannot be negative.");
        }
        if (refusedMeasures.isZero()) {
            throw new InvalidRequestException("A partial delivery refuses something: enter the refused quantity.");
        }
        if (refusedMeasures.exceeds(attempted)) {
            throw new InvalidRequestException("More was refused than this trip carried for the order ("
                    + describe(attempted) + ").");
        }
        OrderAmounts delivered = attempted.minus(refusedMeasures);
        return new DeliveryResultRequest.DeliveryQuantitiesRequest(
                attempted.weightKg(), attempted.volumeM3(), attempted.pallets(),
                delivered.weightKg(), delivered.volumeM3(), delivered.pallets(),
                refusedMeasures.weightKg(), refusedMeasures.volumeM3(), refusedMeasures.pallets());
    }

    private static String describe(OrderAmounts amounts) {
        return amounts.weightKg().stripTrailingZeros().toPlainString() + " kg / "
                + amounts.volumeM3().stripTrailingZeros().toPlainString() + " m3 / "
                + amounts.pallets().stripTrailingZeros().toPlainString() + " pallets";
    }
}
