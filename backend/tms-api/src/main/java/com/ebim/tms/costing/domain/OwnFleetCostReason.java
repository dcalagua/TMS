package com.ebim.tms.costing.domain;

/**
 * Why a component the profile charges for could not be calculated (V48, JOB 22).
 *
 * <p>Typed rather than a message so the UI can say what to fix and a caller can branch. A trip with
 * no distance is repaired by geocoding a location; one with no window is repaired by planning it.
 * Those are different jobs for different people, and one free-text "unavailable" would send both to
 * whoever read it first.
 */
public enum OwnFleetCostReason {

    /**
     * Routing could measure no distance for this trip - typically a stop whose location has no
     * coordinates. NOT the same as a zero-kilometre trip, and the reason this enum exists.
     */
    DISTANCE_UNKNOWN,

    /** The trip has no planned execution window, so nothing says how long the resource is tied up. */
    DUTY_UNKNOWN
}
