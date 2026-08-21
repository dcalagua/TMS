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
    ORDER_DECLARED_TOTALS
}
