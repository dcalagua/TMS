package com.ebim.tms.costing.application;

/**
 * Why a trip has no own-fleet cost at all (V48, JOB 22).
 *
 * <p>Distinct from {@code OwnFleetCostReason}, which says why a total is missing from an estimate
 * that exists. These are the cases where there is no estimate to have a gap in, and each is a
 * different job for a different person: assign a vehicle, configure a profile, or nothing at all
 * because the shipment is subcontracted and has a carrier's price instead.
 */
public enum OwnFleetQuoteUnavailable {

    /** Nothing is assigned yet, so there is no truck whose costs could be modelled. */
    NO_VEHICLE_ASSIGNED,

    /**
     * The shipment runs on a carrier's truck. It has a price, not an internal cost, and modelling
     * one for it would be inventing a second figure for a shipment that already has a real one.
     */
    NOT_OWN_FLEET,

    /**
     * No profile covers this vehicle or its type on this date.
     *
     * <p><b>The honest answer, and the one this whole job exists to protect.</b> A company that has
     * not configured its rates has no own-fleet cost - not a cost of zero, which would make every
     * unconfigured truck the cheapest option on every screen.
     */
    NO_PROFILE_IN_FORCE
}
