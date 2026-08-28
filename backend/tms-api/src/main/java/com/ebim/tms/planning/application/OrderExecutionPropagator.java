package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.shared.reference.OrderFulfillmentPort;
import com.ebim.tms.shared.reference.OrderFulfillmentStatus;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Carries what a shipment did to the orders it was carrying (migration V36).
 *
 * <p><b>Why this exists as its own collaborator.</b> Three call sites need it - a departure, a
 * close-out, and every delivery corrected after the close-out - and they live in two services that
 * share nothing else. Put in either of them it would be reached from the other, and the rule "an
 * order on a departed vehicle is IN_EXECUTION" would end up stated twice.
 *
 * <p><b>What it does not decide.</b> Which lifecycle state a fact puts an order into is an
 * <em>orders</em> rule and is decided in {@code OrderPlanningService}. This class only knows which
 * orders a trip is carrying and which fact to report about them. That division is the same one
 * {@code markPlanned} already runs on and is what keeps {@code OrderStatus} out of planning.
 *
 * <p><b>Every call is inside its caller's transaction</b>, so an order can never be left in
 * execution by a departure that was rolled back, and a close-out can never disagree with the
 * delivery rows it was derived from - the rows and the status move together or not at all.
 */
@Component
public class OrderExecutionPropagator {

    private final TripOrderAssignmentRepository assignments;
    private final OrderPlanningPort orderPlanningPort;
    private final OrderFulfillmentPort orderFulfillmentPort;

    public OrderExecutionPropagator(TripOrderAssignmentRepository assignments, OrderPlanningPort orderPlanningPort,
            OrderFulfillmentPort orderFulfillmentPort) {
        this.assignments = assignments;
        this.orderPlanningPort = orderPlanningPort;
        this.orderFulfillmentPort = orderFulfillmentPort;
    }

    /**
     * The vehicle left: every order still on the trip moves to {@code IN_EXECUTION}.
     *
     * <p>Removed assignments are not touched. An order taken off the trip before it departed was
     * released back to the plannable pool at that moment and is somebody else's problem now.
     */
    public void dispatched(CompanyScope scope, Trip trip) {
        for (UUID orderId : activeOrderIds(trip)) {
            orderPlanningPort.markInExecution(orderId, scope.companyId());
        }
    }

    /**
     * The shipment was closed out: every order it carried is closed out with whatever the delivery
     * rows say about it right now.
     *
     * <p>The fulfilment is read in one batched call rather than per order - the same N+1 discipline
     * {@code OrderFulfillmentPort} was written for. An order with nothing recorded comes back
     * {@code PENDING} and closes as failed, which is the honest reading of "the trip is over and we
     * cannot show the customer got it" and is corrected the moment somebody keys the note.
     */
    public void closedOut(CompanyScope scope, Trip trip) {
        Set<UUID> orderIds = activeOrderIds(trip);
        if (orderIds.isEmpty()) {
            return;
        }
        Map<UUID, OrderFulfillmentStatus> fulfillment =
                orderFulfillmentPort.fulfillmentOf(orderIds, scope.companyId());
        for (UUID orderId : orderIds) {
            orderPlanningPort.closeOut(orderId, scope.companyId(),
                    fulfillment.getOrDefault(orderId, OrderFulfillmentStatus.PENDING));
        }
    }

    /**
     * A delivery was recorded or corrected against a shipment that is already closed out.
     *
     * <p>This is the half that makes the order's state incapable of drifting from the delivery
     * rows. The recording window stays open after completion on purpose - the signed notes come
     * back at 18:40 - so without this an order closed out as failed at 18:00 would stay failed
     * after the note proving delivery was keyed forty minutes later.
     *
     * <p>Does nothing while the trip is still running: the order is {@code IN_EXECUTION} and the
     * close-out at completion is what will read the rows. Recording a delivery mid-trip must not
     * close an order out early, because a later stop may still change what it is owed.
     */
    public void deliveryRecorded(CompanyScope scope, Trip trip, UUID orderId) {
        if (trip.status() != TripStatus.COMPLETED) {
            return;
        }
        Map<UUID, OrderFulfillmentStatus> fulfillment =
                orderFulfillmentPort.fulfillmentOf(Set.of(orderId), scope.companyId());
        orderPlanningPort.closeOut(orderId, scope.companyId(),
                fulfillment.getOrDefault(orderId, OrderFulfillmentStatus.PENDING));
    }

    /**
     * The orders currently on the trip, in assignment order and without duplicates. A set because
     * split allocation is coming: one order on two assignments of the same trip must be reported
     * once, not twice.
     */
    private Set<UUID> activeOrderIds(Trip trip) {
        List<TripOrderAssignment> active =
                assignments.findByTripIdAndStatusOrderByAssignedAtAsc(trip.id(), AssignmentStatus.ACTIVE);
        Set<UUID> orderIds = new LinkedHashSet<>();
        active.forEach(assignment -> orderIds.add(assignment.orderId()));
        return orderIds;
    }
}
