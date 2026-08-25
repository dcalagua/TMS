package com.ebim.tms.rates.domain;

/**
 * Why a {@link RateComponent} the card charges for could not be calculated for this shipment.
 * Mirrors {@code ck_trip_cost_component_reason} (migration V30).
 *
 * <p>A code and not a sentence: the line is shown to an operator in their own language, and a
 * message frozen into a row at estimation time would still be in whichever language the person who
 * ran it was using.
 *
 * <p>{@code *_UNKNOWN} rather than {@code *_ZERO} for the three declared totals, and that wording
 * is the rule: a trip whose orders declare no weight at all has an unknown weight, not a weight of
 * nothing. Charging a truckload at zero per kilo because nobody filled the field in would produce
 * an estimate that is confidently wrong, which is worse than one that says what it is missing.
 */
public enum CostComponentReason {

    /** No route on the shipment, or a route with no reference distance. */
    DISTANCE_UNKNOWN,

    WEIGHT_UNKNOWN,

    VOLUME_UNKNOWN,

    PALLETS_UNKNOWN
}
