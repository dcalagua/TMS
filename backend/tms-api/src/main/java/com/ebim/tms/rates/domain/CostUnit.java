package com.ebim.tms.rates.domain;

/**
 * What a measured {@link RateComponent}'s quantity is counted in. Mirrors
 * {@code ck_trip_cost_component_unit} (migration V30).
 *
 * <p>Stored on the line rather than inferred from the component by whoever renders it: an estimate
 * printed for a carrier has to say "1 250 KG x 0.0850" without the reader having to know what the
 * WEIGHT component happens to be measured in around here.
 */
public enum CostUnit {
    KM,
    KG,
    M3,
    PALLET
}
