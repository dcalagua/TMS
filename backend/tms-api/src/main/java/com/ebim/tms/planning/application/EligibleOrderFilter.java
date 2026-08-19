package com.ebim.tms.planning.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The filters of the eligible-order search. All optional: a planner opening a run filters by its
 * origin and planning date, while someone reviewing the backlog leaves both blank.
 *
 * <p>There is no status filter - "eligible" already means {@code READY_FOR_PLANNING} and nothing
 * else, decided server-side ({@code OrderPlanningPort.searchAssignable}), never by a client
 * parameter.
 */
public record EligibleOrderFilter(UUID originId, UUID destinationId, LocalDate serviceDate, String orderNumber) {
}
