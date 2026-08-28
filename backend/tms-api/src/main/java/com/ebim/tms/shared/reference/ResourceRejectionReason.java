package com.ebim.tms.shared.reference;

/**
 * Why a driver-and-vehicle pairing cannot run a shipment (migration V47).
 *
 * <p>Nine values, and the point of having nine is that <b>the system knows the cause</b>. Collapsing
 * them into one {@code RESOURCE_NOT_AVAILABLE} would throw away the only part a planner can act on:
 * a licence that expired, a truck in the workshop and a gap too short to drive are three different
 * problems with three different fixes, and a planner told only "unavailable" has to go and find out
 * which.
 *
 * <p>Lives in {@code shared.reference} rather than in {@code fleet} because {@code planning} reads
 * it - the boundary {@code ModuleBoundaryTest} enforces.
 */
public enum ResourceRejectionReason {

    /** The driver cannot work at that moment - absence, holiday, medical (V42). */
    DRIVER_UNAVAILABLE,

    /** The vehicle cannot work at that moment, for a reason that is not maintenance. */
    VEHICLE_UNAVAILABLE,

    /**
     * The vehicle is in the workshop.
     *
     * <p>Distinct from {@link #VEHICLE_UNAVAILABLE} because the person who resolves it is different:
     * a workshop books a truck out, and a planner cannot argue with it.
     */
    MAINTENANCE_BLOCK,

    /** The work falls outside the driver's hours for that day (V42's weekly shift). */
    SHIFT_CONFLICT,

    /** Two shipments in the day want the same vehicle or driver at the same time. */
    TRIP_OVERLAP,

    /**
     * The gap between two shipments is shorter than the drive between them.
     *
     * <p>The reason a work assignment needs routing at all: two shipments that do not overlap in
     * time can still be impossible, because the truck has to get from one to the other.
     */
    INSUFFICIENT_REPOSITION_TIME,

    /**
     * The drive between two shipments could not be measured.
     *
     * <p><b>Not the same as zero</b>, and this is the value that keeps it from being treated as
     * such. A destination with no coordinates makes a leg unmeasurable, and a day built on an
     * unmeasured reposition is a day nobody has checked - so it is refused rather than assumed
     * feasible. The same rule V43 applies to stop ETAs and V45 to delivered quantities.
     */
    ROUTING_UNKNOWN,

    /** The driver's licence has expired, or expires before the day being planned. */
    LICENSE_INVALID,

    /**
     * The shipment was accepted by a carrier that does not own this vehicle (V42, debt D2).
     *
     * <p>Reported and never resolved here. Scheduling a shipment does not make it dispatchable, and
     * a work assignment that quietly cleared this would be an alternative route past a dispatch
     * guard - which is the one thing V47 must not become.
     */
    CARRIER_MISMATCH
}
