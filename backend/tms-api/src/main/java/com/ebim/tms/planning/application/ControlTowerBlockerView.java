package com.ebim.tms.planning.application;

import java.util.UUID;

/**
 * A shipment that will not leave today unless somebody does something (JOB 12).
 *
 * <p>Every other panel on this screen reports what <em>has</em> happened - a stop past its window, an
 * exception somebody raised, a departure already late. This one reports what is <b>about to</b>: the
 * states that make {@code TripExecutionService.dispatch} refuse, surfaced before a dispatcher walks
 * to the gate and finds out.
 *
 * <p>Each reason is a refusal that already exists in code and in the database. Nothing here is a new
 * rule, and nothing here is advisory - a shipment on this list genuinely cannot depart in its
 * current state.
 *
 * @param tripId         the shipment
 * @param tripNumber     what a dispatcher calls it out loud
 * @param shipmentNumber the number a carrier knows it by
 * @param reason         which refusal applies
 * @param detail         the specifics - the carrier that accepted, or the block and when it lifts.
 *                       Free text because it is read and never switched on
 */
public record ControlTowerBlockerView(
        UUID tripId,
        Integer tripNumber,
        String shipmentNumber,
        BlockerReason reason,
        String detail) {

    /**
     * Why a shipment cannot leave.
     *
     * <p>Separate values because each needs a different person to act - a planner, a workshop, a
     * supervisor - which is the same reason {@code UnplannedReason} is not one "could not plan".
     */
    public enum BlockerReason {

        /**
         * Accepted by a carrier that does not own the vehicle on it (V42, debt D2). A planner
         * assigns one of that carrier's vehicles and it clears.
         */
        AWAITING_CARRIER_VEHICLE,

        /** The vehicle is out of service at the planned departure (V42). */
        VEHICLE_UNAVAILABLE,

        /** The driver cannot work at the planned departure (V42). */
        DRIVER_UNAVAILABLE
    }
}
