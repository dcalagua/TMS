package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.EmissionCalculator;
import com.ebim.tms.settlement.domain.UnallocatedReason;
import com.ebim.tms.shared.reference.FuelType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One trip's carbon footprint and its split between the orders it carried (migration V73).
 *
 * @param status        why there is or is not a figure; anything but CALCULATED means "sin datos"
 * @param kgCo2e        null unless calculated - never a zero standing in for "unknown"
 * @param distanceKm    the trip workspace's own distance (V38), with its provenance below
 * @param estimated     any leg came from the local estimator: the footprint is an estimate too
 * @param basis         the company's cost-allocation basis (V68), the one the split used
 * @param gramsPerTonneKm intensity over the kilograms carried; null without a figure or a weight
 */
public record TripEmissionView(
        UUID tripId,
        String shipmentNumber,
        EmissionCalculator.Status status,
        BigDecimal kgCo2e,
        BigDecimal distanceKm,
        boolean estimated,
        int unmeasurableLegs,
        String provider,
        String vehiclePlate,
        UUID vehicleTypeId,
        String vehicleTypeCode,
        String vehicleTypeName,
        FuelType fuelType,
        BigDecimal factorKgCo2ePerKm,
        String basis,
        BigDecimal weightKg,
        BigDecimal gramsPerTonneKm,
        int unallocatedOrderCount,
        UnallocatedReason unallocatedReason,
        List<OrderEmission> orders) {

    /**
     * @param kgCo2e null when the order could not be measured on the basis (or the trip has no figure)
     */
    public record OrderEmission(
            UUID assignmentId,
            UUID orderId,
            String orderNumber,
            String customerLabel,
            UUID destinationId,
            String destinationLabel,
            BigDecimal basisQuantity,
            BigDecimal share,
            BigDecimal kgCo2e) {
    }
}
