package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import java.math.BigDecimal;
import java.math.RoundingMode;

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
 *
 * <p><b>Floor and height (V62, ADR-022 section 5).</b> {@code maxFloorAreaM2 = length x width x
 * fillFactor} and {@code maxStackHeightM = height} of the vehicle type; null when the type has no
 * such dimension, which is every vehicle type until someone enters one - so every existing vehicle
 * behaves exactly as before. {@code loadWidthM} constrains nothing here; it is carried for the
 * loading-metre figure (ADR-022 section 8).
 */
public record CapacityLimits(BigDecimal maxWeightKg, BigDecimal maxVolumeM3, BigDecimal maxPallets,
        BigDecimal maxFloorAreaM2, BigDecimal maxStackHeightM, BigDecimal loadWidthM) {

    /** Floor area is stored with three decimals on the trip snapshot (V62). */
    private static final int FLOOR_SCALE = 3;

    /** The pre-V62 shape: no floor, no height. */
    public CapacityLimits(BigDecimal maxWeightKg, BigDecimal maxVolumeM3, BigDecimal maxPallets) {
        this(maxWeightKg, maxVolumeM3, maxPallets, null, null, null);
    }

    /** A trip with no vehicle: no dimension constrains it, and it cannot be confirmed. */
    public static CapacityLimits unlimited() {
        return new CapacityLimits(null, null, null, null, null, null);
    }

    /** Live limits, resolved from the vehicle's current effective capacity - a draft trip's source. */
    public static CapacityLimits of(VehicleCapacityReference vehicle) {
        return new CapacityLimits(vehicle.maxWeightKg(), vehicle.maxVolumeM3(),
                vehicle.maxPallets() == null ? null : BigDecimal.valueOf(vehicle.maxPallets()),
                floorOf(vehicle), vehicle.heightM(), vehicle.widthM());
    }

    /** The limits frozen at confirmation - a confirmed trip's source, immune to later fleet edits. */
    public static CapacityLimits ofSnapshot(Trip trip) {
        return new CapacityLimits(trip.snapshotMaxWeightKg(), trip.snapshotMaxVolumeM3(),
                trip.snapshotMaxPallets() == null ? null : BigDecimal.valueOf(trip.snapshotMaxPallets()),
                trip.snapshotMaxFloorAreaM2(), trip.snapshotMaxStackHeightM(), trip.snapshotLoadWidthM());
    }

    /**
     * {@code length x width x fillFactor}, or null (unlimited) when the vehicle has no length or no
     * width. The fill factor is never null on a reference: fleet resolves it (type, company, property).
     */
    public static BigDecimal floorOf(VehicleCapacityReference vehicle) {
        if (vehicle.lengthM() == null || vehicle.widthM() == null || vehicle.floorFillFactor() == null) {
            return null;
        }
        return vehicle.lengthM().multiply(vehicle.widthM()).multiply(vehicle.floorFillFactor())
                .setScale(FLOOR_SCALE, RoundingMode.HALF_UP);
    }

    public boolean isFullyUnlimited() {
        return maxWeightKg == null && maxVolumeM3 == null && maxPallets == null && maxFloorAreaM2 == null
                && maxStackHeightM == null;
    }

    /**
     * Whether {@code load} fits, in every dimension at once. The single definition of "fits" in
     * TMS: {@code PlanningCapacityService.requireWithinCapacity} asks this first and then only
     * builds a message, and both planning engines ask it while packing. Two answers to that
     * question would mean an engine could propose a trip the service then refuses.
     *
     * <p>A null limit never constrains; a zero limit refuses anything above zero. That asymmetry
     * is the record contract above, not a special case here. The height compares the tallest stack
     * with the vehicle's height; it is never a sum.
     */
    public boolean accommodates(CapacityLoad load) {
        return within(load.weightKg(), maxWeightKg)
                && within(load.volumeM3(), maxVolumeM3)
                && within(load.pallets(), maxPallets)
                && within(load.floorAreaM2(), maxFloorAreaM2)
                && within(load.maxStackHeightM(), maxStackHeightM);
    }

    /**
     * The dimension of {@code load} closest to its limit (ADR-022 section 7): {@code WEIGHT},
     * {@code VOLUME}, {@code FLOOR}, {@code HEIGHT} or {@code PALLETS}, or null when no dimension has
     * a percentage (unlimited, or a zero limit). Ties resolve in that order, so the answer is stable.
     */
    public String bindingDimension(CapacityLoad load) {
        String binding = null;
        BigDecimal highest = null;
        String[] names = {"WEIGHT", "VOLUME", "FLOOR", "HEIGHT", "PALLETS"};
        BigDecimal[][] pairs = {
            {load.weightKg(), maxWeightKg}, {load.volumeM3(), maxVolumeM3}, {load.floorAreaM2(), maxFloorAreaM2},
            {load.maxStackHeightM(), maxStackHeightM}, {load.pallets(), maxPallets}};
        for (int i = 0; i < names.length; i++) {
            BigDecimal limit = pairs[i][1];
            if (limit == null || limit.signum() <= 0) {
                continue;
            }
            BigDecimal ratio = pairs[i][0].divide(limit, 6, RoundingMode.HALF_UP);
            if (highest == null || ratio.compareTo(highest) > 0) {
                highest = ratio;
                binding = names[i];
            }
        }
        return binding;
    }

    /** Whether no unit of an order is taller than this vehicle can carry (ADR-022, ITEM_TALLER_THAN_VEHICLE). */
    public boolean admitsUnitHeight(BigDecimal unitHeightM) {
        return unitHeightM == null || maxStackHeightM == null || unitHeightM.compareTo(maxStackHeightM) <= 0;
    }

    private static boolean within(BigDecimal used, BigDecimal limit) {
        return limit == null || used.compareTo(limit) <= 0;
    }
}
