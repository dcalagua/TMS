package com.ebim.tms.costing.domain;

/**
 * Where a component's quantity came from (V48, JOB 22).
 *
 * <p>Kept beside every line because two estimates of the same trip can differ by a factor and both
 * be honest - one measured over a road network, the other over a straight line. A total whose
 * provenance has been dropped is a number nobody can check.
 *
 * <p>Distinct from {@code rates.CostQuantitySource}, which describes where a carrier rate card's
 * quantities came from. Sharing one enum would have meant a value like {@code ORDER_DECLARED_TOTALS}
 * appearing where it can never occur, and hiding {@code RESOURCE_DUTY_WINDOW} inside a vocabulary
 * about tariffs.
 *
 * <h2>There is no WITH_REPOSITION distance, on purpose</h2>
 *
 * Duty time includes the empty run to reach a trip's origin; distance does not. V47 froze the
 * reposition's <b>minutes</b> when the sequence was validated and not its kilometres, and
 * re-measuring the leg at quote time would give a figure that drifts away from the frozen one as
 * the routing cache changes - two numbers about the same empty leg, disagreeing. So V1 charges the
 * driver and the vehicle for repositioning and does not charge fuel, maintenance or depreciation
 * for it. <b>That understates a multi-trip day's distance costs</b>, it is recorded as such in
 * {@code docs/domain/OWN_FLEET_COSTING_V1.md}, and it understates rather than overstates, which is
 * the direction that does not make own fleet look better than it is.
 */
public enum OwnFleetQuantitySource {

    /** Distance from the routing provider over a real road network. */
    MEASURED_ROUTE,

    /**
     * Distance from the local geodesic estimator - a straight line, and understated on any real
     * road. Still a quantity, still stated as an estimate, never silently promoted (ADR-010).
     */
    STRAIGHT_LINE_ESTIMATE,

    /** Time from the trip's own planned execution window alone. */
    TRIP_EXECUTION_WINDOW,

    /**
     * Time from the trip's execution plus the reposition that had to happen before it could start.
     *
     * <p>The reposition is charged to the trip it repositions <b>to</b>, never to the one it
     * leaves - you drive the empty leg because of the next job. That rule is what stops forty
     * minutes being billed twice when a resource runs two trips in a day.
     */
    RESOURCE_DUTY_WINDOW,

    /** A flat charge from the profile. Needs no quantity, so cannot be unknown. */
    PROFILE_FLAT
}
