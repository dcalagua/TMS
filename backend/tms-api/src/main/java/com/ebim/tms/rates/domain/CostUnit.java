package com.ebim.tms.rates.domain;

/**
 * What a measured {@link RateComponent}'s quantity is counted in. Mirrors
 * {@code ck_trip_cost_component_unit} (migrations V30, V39 and V63).
 *
 * <p>Stored on the line rather than inferred from the component by whoever renders it: an estimate
 * printed for a carrier has to say "1 250 KG x 0.0850" without the reader having to know what the
 * WEIGHT component happens to be measured in around here.
 */
public enum CostUnit {
    KM,
    KG,
    M3,
    PALLET,
    /**
     * A metre of deck length (V63, ADR-022 section 8): the floor the trip's stacks cover divided by
     * the vehicle's load width and floor fill factor. Never stored as a figure of its own - it is
     * derived at estimation time from the ledger and the trip's snapshot.
     */
    LOADING_METER,
    /** A drop after the first (V39). See {@code RateComponent.STOP_OFF} for why the first is free. */
    STOP,
    /** An hour of detention (V39). */
    HOUR,
    /**
     * A percentage of something else (V39) - today only of the linehaul, for the fuel surcharge.
     *
     * <p>A unit unlike the others: the quantity it multiplies is an amount rather than a physical
     * measure, which is exactly why the breakdown shows it. "12% x 840.00" is a line a controller
     * can check; a bare "100.80" is one they have to reconstruct.
     */
    PERCENT
}
