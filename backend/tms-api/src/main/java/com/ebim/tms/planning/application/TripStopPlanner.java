package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.StopPlan;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.shared.reference.PlannableOrder;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns "what is currently assigned to this trip" into "what the stop list must look like".
 *
 * <p>One stop per distinct destination, in assignment order for anything new, each carrying the
 * <em>envelope</em> of the requested windows of the orders delivered there (earliest start,
 * latest end; both null when no order at that destination asked for one). An envelope, not an
 * intersection: V1 has no time-feasibility solver, so the honest statement is "the requests here
 * span this range" - claiming a feasible slot would be inventing routing that
 * {@code CLAUDE.md} defers by decision.
 *
 * <p>{@link com.ebim.tms.planning.domain.Trip#syncStops} then applies this while preserving any
 * ordering the planner set by hand.
 */
final class TripStopPlanner {

    private TripStopPlanner() {}

    static List<StopPlan> plan(List<TripOrderAssignment> activeAssignments, Map<UUID, PlannableOrder> orders) {
        Map<UUID, StopPlan> byDestination = new LinkedHashMap<>();
        for (TripOrderAssignment assignment : activeAssignments) {
            PlannableOrder order = orders.get(assignment.orderId());
            if (order == null) {
                // Only reachable if an order vanished from under an active assignment, which the
                // ON DELETE RESTRICT foreign key prevents; skipping keeps a read from failing.
                continue;
            }
            byDestination.merge(order.destinationId(),
                    new StopPlan(order.destinationId(), order.requestedWindowStart(), order.requestedWindowEnd()),
                    TripStopPlanner::envelope);
        }
        return new ArrayList<>(byDestination.values());
    }

    private static StopPlan envelope(StopPlan current, StopPlan added) {
        return new StopPlan(current.destinationId(),
                earliest(current.serviceWindowStart(), added.serviceWindowStart()),
                latest(current.serviceWindowEnd(), added.serviceWindowEnd()));
    }

    /**
     * A missing window does not narrow the envelope: an order with no requested window is
     * deliverable whenever the stop is served, so it neither widens nor tightens what the orders
     * that <em>did</em> ask for imply.
     */
    private static LocalTime earliest(LocalTime left, LocalTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    private static LocalTime latest(LocalTime left, LocalTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }
}
