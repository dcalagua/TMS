package com.ebim.tms.rates.domain;

/**
 * Where a cost line's quantity came from. Mirrors {@code ck_trip_cost_component_quantity_source}
 * (migration V30).
 *
 * <p>Recorded rather than assumed, because the first thing anyone disputing an estimate asks is
 * "where did 39.5 km come from" - and the honest answer, in this product, is "a planner typed it
 * on the route master", not "we measured the road".
 */
public enum CostQuantitySource {

    /**
     * {@code tms.route.reference_distance_km} - the planner-entered hint on the master corridor
     * (V8). The only distance TMS has, and never a distance it measured.
     */
    ROUTE_REFERENCE,

    /**
     * The summed declared totals of the orders assigned to the trip - the same numbers every
     * capacity check is made against ({@code docs/domain/CAPACITY_MODEL.md}).
     */
    ORDER_DECLARED_TOTALS,
    /**
     * The distance actually measured over the shipment's own stops (V38, wired in V39).
     *
     * <p>Preferred over {@link #ROUTE_REFERENCE} because it is about this shipment rather than
     * about the corridor it resembles - and because it exists for a trip with no master route at
     * all, which before V39 simply could not be priced per kilometre.
     */
    MEASURED_ROUTE,
    /** Counted from the shipment's own stop list (V39). */
    TRIP_STOPS,
    /** The linehaul subtotal a percentage component multiplies (V39). */
    LINEHAUL_SUBTOTAL,
    /** Detention hours somebody recorded against the shipment (V39). */
    RECORDED_WAITING
}