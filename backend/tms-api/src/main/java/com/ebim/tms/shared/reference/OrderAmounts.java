package com.ebim.tms.shared.reference;

import java.math.BigDecimal;

/**
 * A portion of an order's demand, in the three measures a vehicle is actually constrained by
 * (migration V37).
 *
 * <p><b>Why three measures and not a "quantity".</b> An order's lines each carry their own
 * {@code quantity} and {@code uom}, and summing 40 boxes and 3 drums into "43" is a number that
 * means nothing - which is exactly why {@code transport_order} has never had a quantity column and
 * has always carried weight, volume and pallets instead. Those three are summable, they are what
 * {@code vehicle_type} limits, and they are what {@code trip_order_assignment} has stored since
 * V11. A split of "70 of 100 pallets" is therefore expressed here as it is expressed everywhere
 * else in the product, rather than through a fourth measure invented for the split.
 *
 * <p><b>{@code BigDecimal} throughout</b>, never {@code double}: these are quantities a customer is
 * invoiced against and a vehicle is loaded against, and the scales are the columns' own -
 * weight 3dp, volume 4dp, pallets 2dp.
 *
 * <p>Null is normalised to zero on construction. An order whose weight is simply not known
 * contributes nothing to a weight limit, which is a different statement from "the order is
 * invalid" and is the one V10 chose when it defaulted the totals to zero.
 */
public record OrderAmounts(BigDecimal weightKg, BigDecimal volumeM3, BigDecimal pallets) {

    public static final OrderAmounts NONE =
            new OrderAmounts(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    public OrderAmounts {
        weightKg = zeroIfNull(weightKg);
        volumeM3 = zeroIfNull(volumeM3);
        pallets = zeroIfNull(pallets);
    }

    /** Everything the order asks for - the ceiling every allocation is measured against. */
    public static OrderAmounts wholeOf(PlannableOrder order) {
        return new OrderAmounts(order.totalWeightKg(), order.totalVolumeM3(), order.totalPallets());
    }

    public OrderAmounts plus(OrderAmounts other) {
        return new OrderAmounts(weightKg.add(other.weightKg), volumeM3.add(other.volumeM3),
                pallets.add(other.pallets));
    }

    public OrderAmounts minus(OrderAmounts other) {
        return new OrderAmounts(weightKg.subtract(other.weightKg), volumeM3.subtract(other.volumeM3),
                pallets.subtract(other.pallets));
    }

    /** Nothing at all - the state of an order nobody has put on a trip. */
    public boolean isZero() {
        return weightKg.signum() == 0 && volumeM3.signum() == 0 && pallets.signum() == 0;
    }

    /** At least one measure is below zero. Never a legal allocation. */
    public boolean isNegative() {
        return weightKg.signum() < 0 || volumeM3.signum() < 0 || pallets.signum() < 0;
    }

    /**
     * Whether this exceeds {@code ceiling} in any measure.
     *
     * <p>Compared with {@link BigDecimal#compareTo} and never {@code equals}: {@code 70.0} and
     * {@code 70.000} are the same quantity and different objects, and a ledger that thought
     * otherwise would refuse a split that exactly fills an order.
     */
    public boolean exceeds(OrderAmounts ceiling) {
        return weightKg.compareTo(ceiling.weightKg) > 0
                || volumeM3.compareTo(ceiling.volumeM3) > 0
                || pallets.compareTo(ceiling.pallets) > 0;
    }

    /**
     * Whether this is the whole of {@code total} in every measure.
     *
     * <p>An order with nothing known - all three measures zero - is fully allocated by an
     * allocation of nothing, which is what keeps V1's behaviour intact: assigning such an order has
     * always made it {@code PLANNED}, and it must go on doing so.
     */
    public boolean covers(OrderAmounts total) {
        return weightKg.compareTo(total.weightKg) >= 0
                && volumeM3.compareTo(total.volumeM3) >= 0
                && pallets.compareTo(total.pallets) >= 0;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
