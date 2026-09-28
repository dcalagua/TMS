package com.ebim.tms.planning.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * The body of an execution transition: ready, dispatch, complete.
 *
 * <p>{@code version} is mandatory and checked against the persisted row, like every other
 * operation that changes a field the caller read ({@code PlanningActionRequest} documents the
 * rule and its exceptions).
 *
 * @param occurredAt when the fact being recorded actually happened - the ready time, the real
 *     departure, the real completion. Optional: omitted means "now", which is what a dispatcher
 *     pressing the button as the truck leaves means. Sent explicitly, it lets the same dispatcher
 *     record at 09:05 that the truck left at 08:40, which is the only way the actual times end up
 *     describing the fleet instead of describing when somebody reached a keyboard.
 *     {@code TripExecutionService} refuses a future time and one that would put the lifecycle out
 *     of order.
 */
public record TripExecutionRequest(
        @NotNull(message = "is required") Long version,
        OffsetDateTime occurredAt,
        // ADR-013 section 4: only read by dispatch, and only when the company requires the
        // warehouse to confirm the departure. Ignored in MANUAL and HYBRID.
        @Size(max = 500, message = "must be at most 500 characters") String overrideReason) {

    public TripExecutionRequest(Long version, OffsetDateTime occurredAt) {
        this(version, occurredAt, null);
    }
}
