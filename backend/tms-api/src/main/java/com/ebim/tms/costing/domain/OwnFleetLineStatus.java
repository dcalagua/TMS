package com.ebim.tms.costing.domain;

/**
 * Whether a line of an estimate has a figure behind it (V48, JOB 22).
 *
 * <p>Mirrors {@code rates.CostComponentStatus} in meaning and is deliberately a separate type: the
 * modules integrate through {@code shared.reference} or not at all, and one shared two-value enum
 * is not worth a dependency between own-fleet costing and carrier tariffs.
 */
public enum OwnFleetLineStatus {

    /** Calculated, and its amount is real. */
    APPLIED,

    /**
     * The profile charges for this and the trip supplied no quantity.
     *
     * <p>Its amount is {@code 0.00} so a sum over the lines is a plain sum - and that zero is
     * never the answer on its own, which is why an estimate with any such line has no
     * {@link OwnFleetCostEstimate#comparableTotal()}.
     */
    NOT_CALCULABLE
}
