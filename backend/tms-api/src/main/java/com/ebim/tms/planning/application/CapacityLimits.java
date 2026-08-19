package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import java.math.BigDecimal;

/**
 * The three limits one trip must respect, with one explicit null semantic:
 *
 * <ul>
 *   <li><b>{@code null} means unlimited</b> - the dimension is not constrained at all. In V1 this
 *       happens for exactly one real reason: a draft trip with no vehicle yet. A planner may fill
 *       such a trip freely; they simply cannot confirm it (a confirmed trip always has a
 *       vehicle).</li>
 *   <li><b>Zero means zero</b> - a real limit of nothing, which a tanker's {@code max_pallets}
 *       legitimately is. Anything above zero is refused, and the percentage is reported as
 *       undefined rather than dividing by it.</li>
 * </ul>
 *
 * <p>Those two are different answers and the difference is deliberate: treating a zero limit as
 * "unlimited" would let a bulk-liquid vehicle be loaded with pallets, and treating unlimited as
 * zero would make a vehicle-less draft trip unusable. See {@code docs/domain/CAPACITY_MODEL.md}.
 */
public record CapacityLimits(BigDecimal maxWeightKg, BigDecimal maxVolumeM3, BigDecimal maxPallets) {

    /** A trip with no vehicle: no dimension constrains it, and it cannot be confirmed. */
    public static CapacityLimits unlimited() {
        return new CapacityLimits(null, null, null);
    }

    /** Live limits, resolved from the vehicle's current effective capacity - a draft trip's source. */
    public static CapacityLimits of(VehicleCapacityReference vehicle) {
        return new CapacityLimits(vehicle.maxWeightKg(), vehicle.maxVolumeM3(),
                vehicle.maxPallets() == null ? null : BigDecimal.valueOf(vehicle.maxPallets()));
    }

    /** The limits frozen at confirmation - a confirmed trip's source, immune to later fleet edits. */
    public static CapacityLimits ofSnapshot(Trip trip) {
        return new CapacityLimits(trip.snapshotMaxWeightKg(), trip.snapshotMaxVolumeM3(),
                trip.snapshotMaxPallets() == null ? null : BigDecimal.valueOf(trip.snapshotMaxPallets()));
    }

    public boolean isFullyUnlimited() {
        return maxWeightKg == null && maxVolumeM3 == null && maxPallets == null;
    }
}
