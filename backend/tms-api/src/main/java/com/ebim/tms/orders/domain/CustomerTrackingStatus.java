package com.ebim.tms.orders.domain;

/**
 * Where an order stands, in the words a customer reads on a tracking link (ADR-029 decision 4).
 *
 * <p>Deliberately not {@link OrderStatus}: that one is the office's lifecycle and says
 * {@code READY_FOR_PLANNING} or {@code IN_EXECUTION}, which mean nothing to the person waiting for the
 * goods. This one is derived - from the order and from what planning knows about its trips - and never
 * stored.
 */
public enum CustomerTrackingStatus {
    /** The order exists; no confirmed trip carries it yet. */
    RECEIVED,
    /** A confirmed trip carries it and has not left. */
    PLANNED,
    /** The trip has left. */
    IN_TRANSIT,
    /** The vehicle is at the customer's stop. */
    ARRIVED,
    DELIVERED,
    PARTIALLY_DELIVERED,
    /** The customer refused the goods. */
    REJECTED,
    /** The delivery was attempted and failed, or not attempted. */
    NOT_DELIVERED,
    CANCELLED
}
