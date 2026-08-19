package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.OrderPriority;
import com.ebim.tms.orders.domain.OrderStatus;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The optional list filters for {@code GET /orders}, bound alongside
 * {@link com.ebim.tms.shared.api.PageQuery} - the step brief's "origin/destination/date/status/
 * priority filters", plus {@code orderNumber} as the natural free-text search field every other
 * module's list screen has (its {@code code} equivalent).
 *
 * <p>{@code serviceDateFrom}/{@code serviceDateTo} form a range rather than a single exact date,
 * because a planner searching for "what needs to move this week" needs a window, not one day.
 * Either bound may be supplied alone.
 */
public record OrderFilter(
        String orderNumber,
        UUID originId,
        UUID destinationId,
        LocalDate serviceDateFrom,
        LocalDate serviceDateTo,
        OrderStatus status,
        OrderPriority priority) {
}
