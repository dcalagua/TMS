package com.ebim.tms.planning.application;

import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.PlannableOrder;
import java.math.BigDecimal;

/**
 * How much of a trip is used, always as the sum of the <em>active assignment rows</em> and never
 * of the order headers behind them - which is what makes a future partial assignment invisible to
 * every capacity calculation (see {@code TripOrderAssignment}).
 *
 * <p>Never null in any dimension: an order with unknown weight contributes zero, because
 * "unknown" and "none" are indistinguishable at the point a truck is loaded, and a null here
 * would silently disable a limit.
 *
 * <p>Since V62 (ADR-022 section 5) two more dimensions: {@code floorAreaM2}, summed like the other
 * three, and {@code maxStackHeightM}, which is <b>not</b> summed - a load is as tall as its tallest
 * stack, so {@link #plus} takes the maximum. A load with nothing measured has zero floor and a
 * height of zero, which no real height limit refuses.
 */
public record CapacityLoad(BigDecimal weightKg, BigDecimal volumeM3, BigDecimal pallets, BigDecimal floorAreaM2,
        BigDecimal maxStackHeightM, long orderCount) {

    public static final CapacityLoad EMPTY = new CapacityLoad(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, 0);

    public CapacityLoad {
        weightKg = zeroIfNull(weightKg);
        volumeM3 = zeroIfNull(volumeM3);
        pallets = zeroIfNull(pallets);
        floorAreaM2 = zeroIfNull(floorAreaM2);
        maxStackHeightM = zeroIfNull(maxStackHeightM);
    }

    /** The pre-V62 shape: nothing measured, so no floor and no height. */
    public CapacityLoad(BigDecimal weightKg, BigDecimal volumeM3, BigDecimal pallets, long orderCount) {
        this(weightKg, volumeM3, pallets, BigDecimal.ZERO, BigDecimal.ZERO, orderCount);
    }

    /**
     * The load one whole order adds, treating an unknown measure as zero. The volume is the order's
     * planning volume (V62), which is its declared total until a line is measured.
     */
    public static CapacityLoad of(PlannableOrder order) {
        return of(order.ordered());
    }

    /**
     * The load one <em>allocation</em> adds - the whole order, or the slice of it going on this
     * trip (migration V37). The order count is one either way: a truck carrying part of an order is
     * carrying that order, and counting it as a fraction would make the board's order count
     * meaningless.
     */
    public static CapacityLoad of(OrderAmounts amounts) {
        return new CapacityLoad(amounts.weightKg(), amounts.volumeM3(), amounts.pallets(), amounts.floorAreaM2(),
                amounts.maxStackHeightM(), 1);
    }

    /** The database's grouped answer for one trip, mapped straight through. */
    public static CapacityLoad of(TripOrderAssignmentRepository.TripLoad load) {
        if (load == null) {
            return EMPTY;
        }
        return new CapacityLoad(load.getWeightKg(), load.getVolumeM3(), load.getPallets(), load.getFloorAreaM2(),
                load.getMaxStackHeightM(), load.getOrderCount());
    }

    public CapacityLoad plus(CapacityLoad other) {
        return new CapacityLoad(weightKg.add(other.weightKg), volumeM3.add(other.volumeM3),
                pallets.add(other.pallets), floorAreaM2.add(other.floorAreaM2),
                maxStackHeightM.max(other.maxStackHeightM), orderCount + other.orderCount);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
