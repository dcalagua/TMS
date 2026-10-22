package com.ebim.tms.shared.settings;

/**
 * How a customer tracking link shows the vehicle plate (migration V74, ADR-029 decision 5).
 *
 * <p>There is deliberately no {@code FULL}: a whole plate, a time and a place together are more than
 * "where is my order" needs, and the link is a bearer credential anyone holding the message can open.
 */
public enum TrackingPlateDisplay {
    /** The last three characters only, the rest replaced: enough to recognise the truck at the dock. */
    MASKED,
    /** Not shown at all. */
    HIDDEN
}
