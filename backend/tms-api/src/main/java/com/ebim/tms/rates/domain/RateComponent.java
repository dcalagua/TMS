package com.ebim.tms.rates.domain;

/**
 * One line of a freight charge (migrations V30 and V39).
 *
 * <p><b>The order of the constants is the order of application, and that is load-bearing.</b> These
 * components do not commute: a fuel surcharge levied before the linehaul is complete charges a
 * percentage of the wrong number, and a minimum applied before the accessorials is a different
 * agreement from one applied after. {@code TripCostCalculator} walks this enum in declaration
 * order, and {@code docs/domain/RATE_ENGINE_V2.md} states the resulting arithmetic as a contract.
 *
 * <pre>
 *   BASE + DISTANCE + WEIGHT + VOLUME + PALLETS + STOP_OFF   -&gt; the linehaul
 *   + FUEL_SURCHARGE     (a percentage of the linehaul, and of nothing else)
 *   + WAITING_TIME + TOLL + OTHER_ACCESSORIAL                -&gt; the accessorials
 *   then MINIMUM_ADJUSTMENT or MAXIMUM_ADJUSTMENT on the total
 * </pre>
 */
public enum RateComponent {

    /** The flat charge for running the shipment at all. */
    BASE(null, null),

    /**
     * Per kilometre.
     *
     * <p>Its quantity source is declared here as {@link CostQuantitySource#ROUTE_REFERENCE} but is
     * <b>overridden per estimate</b> by {@code CostInputs.distanceSource}: since V39 a distance may
     * come from the shipment's own measured route (V38) instead of from the master corridor, and
     * which one it was is a fact about that estimate rather than about this component. A line that
     * claimed MEASURED_ROUTE while the number came from a route master would be exactly the kind
     * of untraceable figure the breakdown exists to prevent.
     */
    DISTANCE(CostUnit.KM, CostQuantitySource.ROUTE_REFERENCE),

    WEIGHT(CostUnit.KG, CostQuantitySource.ORDER_DECLARED_TOTALS),

    VOLUME(CostUnit.M3, CostQuantitySource.ORDER_DECLARED_TOTALS),

    PALLETS(CostUnit.PALLET, CostQuantitySource.ORDER_DECLARED_TOTALS),

    /**
     * Per drop <em>after the first</em> (V39).
     *
     * <p>The first stop is already inside {@link #BASE} - a shipment exists to deliver something
     * somewhere - so charging it again is billing the same drop twice, and a one-stop trip must pay
     * no stop-off at all. Every carrier's multi-drop schedule is written this way and getting it
     * wrong overcharges the simplest shipment in the book.
     */
    STOP_OFF(CostUnit.STOP, CostQuantitySource.TRIP_STOPS),

    /**
     * A percentage of the linehaul (V39), and of nothing else.
     *
     * <p>Applied after every linehaul component and before every accessorial, which is why it sits
     * here rather than at the end. A fuel surcharge computed on a toll is a fuel surcharge on a
     * road authority's fee: no carrier bills it and no shipper would accept it.
     */
    FUEL_SURCHARGE(CostUnit.PERCENT, CostQuantitySource.LINEHAUL_SUBTOTAL),

    /**
     * Per hour of detention (V39).
     *
     * <p>Never present on an estimate, and that is correct rather than a gap: waiting is measured
     * on the road. The line appears as {@link CostComponentStatus#NOT_CALCULABLE} with
     * {@link CostComponentReason#WAITING_NOT_RECORDED} so that a controller comparing an estimate
     * against an invoice can see detention was never estimated, rather than wondering whether it
     * was estimated at zero.
     */
    WAITING_TIME(CostUnit.HOUR, CostQuantitySource.RECORDED_WAITING),

    /** A flat pass-through of road charges (V39). */
    TOLL(null, null),

    /** A flat named extra (V39). The card carries the name; a charge nobody can label is one nobody approves. */
    OTHER_ACCESSORIAL(null, null),

    /** The floor, applied to the total. Present only when the total fell below it. */
    MINIMUM_ADJUSTMENT(null, null),

    /** The ceiling, applied to the total (V39). Present only when the total rose above it. */
    MAXIMUM_ADJUSTMENT(null, null);

    private final CostUnit unit;
    private final CostQuantitySource quantitySource;

    RateComponent(CostUnit unit, CostQuantitySource quantitySource) {
        this.unit = unit;
        this.quantitySource = quantitySource;
    }

    /** The unit this component multiplies, or null when it is flat. */
    public CostUnit unit() {
        return unit;
    }

    /** Where its quantity comes from, or null when it is flat. */
    public CostQuantitySource quantitySource() {
        return quantitySource;
    }

    /** Whether this is a product of a rate and a quantity rather than a flat amount. */
    public boolean isMeasured() {
        return unit != null;
    }

    /** Whether this component contributes to the linehaul the fuel surcharge is taken on. */
    public boolean isLinehaul() {
        return switch (this) {
            case BASE, DISTANCE, WEIGHT, VOLUME, PALLETS, STOP_OFF -> true;
            case FUEL_SURCHARGE, WAITING_TIME, TOLL, OTHER_ACCESSORIAL,
                 MINIMUM_ADJUSTMENT, MAXIMUM_ADJUSTMENT -> false;
        };
    }

    /** Whether this component is an adjustment applied to the finished total. */
    public boolean isAdjustment() {
        return this == MINIMUM_ADJUSTMENT || this == MAXIMUM_ADJUSTMENT;
    }

    /**
     * Why this component could not be calculated. Exhaustive with no default, so a new component
     * must be given an answer out here rather than falling through to a plausible one.
     */
    public CostComponentReason missingQuantityReason() {
        return switch (this) {
            case DISTANCE -> CostComponentReason.DISTANCE_UNKNOWN;
            case WEIGHT -> CostComponentReason.WEIGHT_UNKNOWN;
            case VOLUME -> CostComponentReason.VOLUME_UNKNOWN;
            case PALLETS -> CostComponentReason.PALLETS_UNKNOWN;
            case STOP_OFF -> CostComponentReason.STOPS_UNKNOWN;
            case WAITING_TIME -> CostComponentReason.WAITING_NOT_RECORDED;
            case FUEL_SURCHARGE -> CostComponentReason.DISTANCE_UNKNOWN;
            case BASE, TOLL, OTHER_ACCESSORIAL, MINIMUM_ADJUSTMENT, MAXIMUM_ADJUSTMENT ->
                    throw new IllegalStateException(this + " has no quantity and can never be missing one");
        };
    }
}
