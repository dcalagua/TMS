package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.shared.geometry.OrderGeometry;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.OrderGeometryProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The load an allocation puts on a <em>specific</em> vehicle (ADR-022 section 5): the one way
 * planning turns amounts into a {@link CapacityLoad}, so the manual assignment, the move, the vehicle
 * swap, the run confirmation and both engines re-stack identically and then ask the one
 * {@link CapacityLimits#accommodates}.
 *
 * <h2>Why a vehicle changes the figures</h2>
 *
 * <p>An order's floor area, planning volume and stack height are stored at the company's reference
 * height (V62) - that is what the V37 ledger counts, and it must not change with the truck. But how
 * many units go in a stack depends on the vehicle: 0.30 m crates are stacks of eight under 2.40 m and
 * stacks of seven under 2.20 m. Comparing the 2.40 m stacks (2.40 m tall) with a 2.20 m vehicle
 * refused a load that fits it as stacks of seven; comparing the reference floor with a taller vehicle
 * charged floor the load does not take. So for a vehicle with a height the order is re-stacked
 * against that height with the same {@link OrderGeometry} the order was written with.
 *
 * <h2>Slices</h2>
 *
 * <p>A partial allocation is a share of the order's floor ({@code slice.floor / order.floor}); the
 * re-stacking difference is applied in that proportion, to the floor and to the volume. For the whole
 * order this is the re-stacked figure exactly; at the reference height it is the stored figure
 * exactly - so nothing changes for a vehicle as tall as the reference, or for any vehicle without a
 * height, or for any order without a measured line.
 *
 * <p>A unit taller than the vehicle is not re-stacked into fitting: its line is {@code DOES_NOT_FIT}
 * with a stack as tall as the unit, so the height dimension refuses it, and callers ask
 * {@link CapacityLimits#admitsUnitHeight} first to say {@code ITEM_TALLER_THAN_VEHICLE}.
 *
 * <p>Pure: no repository. Profiles are loaded once for a whole set of orders by the caller
 * ({@code OrderPlanningPort.geometryProfilesOf}).
 */
final class VehicleLoads {

    private static final int VOLUME_SCALE = 4;
    private static final int FLOOR_SCALE = 4;
    private static final int SHARE_SCALE = 10;

    private VehicleLoads() {}

    /**
     * The load {@code slice} of an order puts on a vehicle with {@code limits}.
     *
     * @param profile the order's geometry profile, or null when it has no measured line
     */
    static CapacityLoad of(OrderAmounts slice, OrderGeometryProfile profile, CapacityLimits limits) {
        return CapacityLoad.of(amountsOn(slice, profile, limits));
    }

    /** The same, as amounts. */
    static OrderAmounts amountsOn(OrderAmounts slice, OrderGeometryProfile profile, CapacityLimits limits) {
        BigDecimal height = limits == null ? null : limits.maxStackHeightM();
        if (profile == null || height == null || profile.referenceHeightM() == null
                || height.compareTo(profile.referenceHeightM()) == 0) {
            return slice;
        }
        OrderGeometry reference = profile.at(profile.referenceHeightM());
        OrderGeometry vehicle = profile.at(height);
        if (!reference.anyMeasured() || !vehicle.anyMeasured()) {
            return slice;
        }
        BigDecimal share = shareOf(slice, profile);
        BigDecimal floor = slice.floorAreaM2()
                .add(vehicle.floorAreaM2().subtract(reference.floorAreaM2()).multiply(share));
        BigDecimal volume = slice.volumeM3()
                .add(vehicle.planningVolumeM3().subtract(reference.planningVolumeM3()).multiply(share));
        return new OrderAmounts(slice.weightKg(), nonNegative(volume).setScale(VOLUME_SCALE, RoundingMode.UP),
                slice.pallets(), nonNegative(floor).setScale(FLOOR_SCALE, RoundingMode.UP),
                vehicle.maxStackHeightM());
    }

    /**
     * A trip's active rows on a vehicle with {@code limits}: summed like the database's
     * {@code loadByTripId} - weight, volume, pallets, floor - with the height as a maximum.
     */
    static CapacityLoad ofAssignments(Collection<TripOrderAssignment> rows, Map<UUID, OrderGeometryProfile> profiles,
            CapacityLimits limits) {
        CapacityLoad load = CapacityLoad.EMPTY;
        for (TripOrderAssignment row : rows) {
            load = load.plus(of(row.assigned(), profiles.get(row.orderId()), limits));
        }
        return load;
    }

    /** The orders of {@code rows} that may need re-stacking: those whose allocation carries a stack. */
    static Set<UUID> geometricOrders(Collection<TripOrderAssignment> rows) {
        return rows.stream()
                .filter(row -> row.assignedStackHeightM() != null)
                .map(TripOrderAssignment::orderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** Whether re-stacking can change anything for these limits: only a vehicle with a height. */
    static boolean restacks(CapacityLimits limits) {
        return limits != null && limits.maxStackHeightM() != null;
    }

    private static BigDecimal shareOf(OrderAmounts slice, OrderGeometryProfile profile) {
        BigDecimal orderFloor = profile.floorAreaM2();
        if (orderFloor == null || orderFloor.signum() <= 0) {
            return BigDecimal.ONE;
        }
        return slice.floorAreaM2().divide(orderFloor, SHARE_SCALE, RoundingMode.HALF_UP).min(BigDecimal.ONE);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
