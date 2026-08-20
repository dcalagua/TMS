package com.ebim.tms.planning.domain;

/**
 * What {@link ShipmentOutboxEvent} records. Mirrors {@code ck_shipment_outbox_event_type}
 * (migration V20).
 *
 * <p>{@link #SHIPMENT_CONFIRMED} is the only value {@code PlanningRunService} ever writes.
 * {@link #SHIPMENT_CHANGED} and {@link #SHIPMENT_CANCELLED} are reserved: {@link TripStatus}'s
 * class comment already states a confirmed trip is locked against every mutation, and
 * {@code TripService.cancel}/{@code PlanningRunService.cancel} only ever cancel a
 * {@link TripStatus#DRAFT} trip - one that was never published in the first place. Neither event
 * has a source today. The schema accepts both anyway so the day either business rule changes,
 * emitting the event is an application change, not a migration.
 */
public enum ShipmentEventType {
    SHIPMENT_CONFIRMED,
    SHIPMENT_CHANGED,
    SHIPMENT_CANCELLED
}
