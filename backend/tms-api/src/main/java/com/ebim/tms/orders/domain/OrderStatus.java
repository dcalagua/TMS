package com.ebim.tms.orders.domain;

/**
 * The minimal V1 order lifecycle, documented in full in
 * {@code docs/domain/ORDER_LIFECYCLE_V1.md}: an order is created {@link #NOT_READY}, becomes
 * {@link #READY_FOR_PLANNING} once {@code OrderService.markReadyForPlanning} accepts it,
 * becomes {@link #PLANNED} once planning ({@code OrderPlanningService}, step 10) assigns it
 * to a trip, or is {@link #CANCELLED}. There is no dispatch/delivery status yet - the step 09
 * brief is explicit that this module must not pretend that integration exists.
 */
public enum OrderStatus {
    NOT_READY,
    READY_FOR_PLANNING,
    PLANNED,
    CANCELLED
}
