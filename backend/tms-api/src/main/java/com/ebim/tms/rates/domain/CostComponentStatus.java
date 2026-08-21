package com.ebim.tms.rates.domain;

/**
 * Whether a cost line contributed to the total. Mirrors {@code ck_trip_cost_component_status}
 * (migration V30).
 */
public enum CostComponentStatus {

    APPLIED,

    /**
     * The card charges for this and the shipment cannot supply the quantity. The line is kept,
     * with its {@link CostComponentReason}, and contributes 0.00 - which is what makes an
     * incomplete estimate visibly incomplete instead of quietly short.
     */
    NOT_CALCULABLE
}
