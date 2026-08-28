package com.ebim.tms.appointments.domain;

/**
 * What the vehicle is at the door for (migration V41).
 *
 * <p>Two values and not a free-text label, because the difference changes what the site does: a
 * pickup queues against outbound staging and a delivery against inbound receiving, and a door that
 * serves both usually serves them at different hours. A third value would need a real requirement -
 * a cross-dock is a pickup and a delivery at one door, which is two appointments and not a new kind.
 */
public enum AppointmentPurpose {

    /** The vehicle is collecting - loading at this door. */
    PICKUP,

    /** The vehicle is delivering - unloading at this door. */
    DELIVERY
}
