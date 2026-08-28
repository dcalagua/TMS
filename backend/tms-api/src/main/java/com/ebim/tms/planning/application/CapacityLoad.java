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
 */
public record CapacityLoad(BigDecimal weightKg, BigDecimal volumeM3, BigDecimal pallets, long orderCount) {

    public static final CapacityLoad EMPTY =
            new CapacityLoad(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);

    /** The load one whole order adds, treating an unknown weight/volume/pallet count as zero. */
    public static CapacityLoad of(PlannableOrder order) {
        return new CapacityLoad(zeroIfNull(order.totalWeightKg()), zeroIfNull(order.totalVolumeM3()),
                zeroIfNull(order.totalPallets()), 1);
    }

    /**
     * The load one <em>allocation</em> adds - the whole order, or the slice of it going on this
     * trip (migration V37). The order count is one either way: a truck carrying part of an order is
     * carrying that order, and counting it as a fraction would make the board's order count
     * meaningless.
     */
    public static CapacityLoad of(OrderAmounts amounts) {
        return new CapacityLoad(amounts.weightKg(), amounts.volumeM3(), amounts.pallets(), 1);
    }

    /** The database's grouped answer for one trip, mapped straight through. */
    public static CapacityLoad of(TripOrderAssignmentRepository.TripLoad load) {
        if (load == null) {
            return EMPTY;
        }
        return new CapacityLoad(zeroIfNull(load.getWeightKg()), zeroIfNull(load.getVolumeM3()),
                zeroIfNull(load.getPallets()), load.getOrderCount());
    }

    public CapacityLoad plus(CapacityLoad other) {
        return new CapacityLoad(weightKg.add(other.weightKg), volumeM3.add(other.volumeM3),
                pallets.add(other.pallets), orderCount + other.orderCount);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
