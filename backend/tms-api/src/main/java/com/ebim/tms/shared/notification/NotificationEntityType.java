package com.ebim.tms.shared.notification;

/**
 * What an alert is about, and therefore where clicking it goes (migration V32).
 *
 * <p>Two values because two are all that can be raised today. Adding a third is a change to this
 * enum, to {@code ck_notification_entity_type} and to the frontend's route map, in that order -
 * which is the point: a value here is a promise that the panel can navigate somewhere, and an
 * alert that leads nowhere is worse than no alert.
 */
public enum NotificationEntityType {

    /** A shipment. The trip workspace, {@code /trips/{id}}. */
    TRIP,

    /** A person in the driver master. The drivers screen, {@code /fleet/drivers}. */
    DRIVER
}
