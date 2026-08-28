package com.ebim.tms.shared.reference;

/**
 * Whether a figure is somebody's PRICE or our own COST (V48, JOB 22).
 *
 * <p><b>Why this enum exists at all.</b> Both numbers answer "what does moving this shipment come
 * to", both are money, both are in a currency, and putting them in one column called {@code price}
 * would make them look interchangeable. They are not, and a planner deciding between them is making
 * a decision this distinction is the whole content of.
 *
 * <p>A carrier's figure is a <b>commercial price</b>: agreed, binding, and already containing their
 * costs, their overhead and their margin. Our figure is an <b>internal cost estimate</b>: modelled,
 * binding nobody, containing no margin, and only ever as good as the rates somebody typed into the
 * profile. An own-fleet estimate coming out lower than a carrier price is the expected result of
 * comparing a number with margin against a number without one - it is not, on its own, evidence
 * that running it ourselves is cheaper.
 *
 * <p>So the nature travels with the amount from the calculator to the screen, and every place that
 * shows both labels them. See {@code docs/domain/OWN_FLEET_COSTING_V1.md}.
 */
public enum TransportCostNature {

    /** What a carrier has agreed to be paid. Contains their margin. Binding. */
    EXTERNAL_CARRIER_PRICE,

    /** What we model our own truck consuming. Contains no margin. Binds nobody. */
    OWN_FLEET_INTERNAL_COST
}
