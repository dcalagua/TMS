package com.ebim.tms.shared.reference;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The filter half of {@link OrderPlanningPort#searchAssignable}: which orders a planner is
 * looking at. {@code companyId} is not optional and is never taken from a client parameter - it
 * comes from the resolved company scope, exactly like every other query in TMS.
 *
 * <p>{@code serviceDate} is a single date rather than a range because a planning run plans one
 * date (see {@code docs/domain/PLANNING_MANUAL_V1.md}, "Eligibility"); the free eligible-order
 * search leaves it null to browse the backlog.
 */
public record PlannableOrderQuery(
        UUID companyId,
        UUID originId,
        UUID destinationId,
        LocalDate serviceDate,
        String orderNumber) {
}
