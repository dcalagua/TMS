package com.ebim.tms.rates.domain;

/**
 * One line of an estimate (migration V30). Five ways a road shipment can be charged, and the
 * adjustment that applies the card's floor - no more, and every one of them computable from data
 * TMS already holds.
 *
 * <p>Declaration order is the order an estimate is presented in, which is also the order it is
 * calculated in: the flat amount, then the unit components, then the floor that may raise the
 * total. A screen never has to re-sort these to be readable.
 */
public enum RateComponent {

    /** The flat amount per shipment. No quantity, no unit. */
    BASE(null, null),

    /**
     * Distance x rate. The quantity is the master route's reference distance and nothing else -
     * TMS measures no road distance, so a trip built without a route has this line reported as
     * non-calculable rather than charged at zero.
     */
    DISTANCE(CostUnit.KM, CostQuantitySource.ROUTE_REFERENCE),

    WEIGHT(CostUnit.KG, CostQuantitySource.ORDER_DECLARED_TOTALS),

    VOLUME(CostUnit.M3, CostQuantitySource.ORDER_DECLARED_TOTALS),

    PALLETS(CostUnit.PALLET, CostQuantitySource.ORDER_DECLARED_TOTALS),

    /**
     * What was added to reach the card's {@code minimumAmount}, when the components alone came to
     * less. Its own line rather than a silent correction of the total, so an operator can see that
     * it was the minimum and not the tariff that set the price.
     */
    MINIMUM_ADJUSTMENT(null, null);

    private final CostUnit unit;
    private final CostQuantitySource quantitySource;

    RateComponent(CostUnit unit, CostQuantitySource quantitySource) {
        this.unit = unit;
        this.quantitySource = quantitySource;
    }

    /** The unit this component's quantity is measured in, or null for a flat amount. */
    public CostUnit unit() {
        return unit;
    }

    /** Where this component's quantity comes from, or null for a flat amount. */
    public CostQuantitySource quantitySource() {
        return quantitySource;
    }

    /** Whether this component multiplies a rate by a quantity, as opposed to being a flat amount. */
    public boolean isMeasured() {
        return unit != null;
    }

    /** Why this component could not be calculated when its quantity is missing. */
    public CostComponentReason missingQuantityReason() {
        return switch (this) {
            case DISTANCE -> CostComponentReason.DISTANCE_UNKNOWN;
            case WEIGHT -> CostComponentReason.WEIGHT_UNKNOWN;
            case VOLUME -> CostComponentReason.VOLUME_UNKNOWN;
            case PALLETS -> CostComponentReason.PALLETS_UNKNOWN;
            case BASE, MINIMUM_ADJUSTMENT -> throw new IllegalStateException(
                    this + " has no quantity and can never be missing one");
        };
    }
}
