package com.ebim.tms.appointments.domain;

/**
 * What kind of place a vehicle is booked into (migration V41).
 *
 * <p>All four behave identically to the scheduler - one vehicle at a time, opening hours, closures -
 * and they exist to be readable rather than to be logic. A site that calls its doors "bays" should
 * see "bay" on the screen; encoding that as a comment on a code somewhere is how a product ends up
 * speaking a language none of its users do.
 */
public enum ResourceType {

    /** A loading dock with a leveller. */
    DOCK,

    /** A door with no dock equipment - ground-level loading. */
    DOOR,

    /** A bay, usually for bulk or for vehicles that load from the side. */
    BAY,

    /** A yard slot: a place to stand, not a place to load. */
    YARD
}
