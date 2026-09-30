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
import java.util.EnumSet;
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
 * {@code allocate} runs on and is what keeps {@code OrderStatus} out of planning.
 *
 * <p><b>Every call is inside its caller's transaction</b>, so an order can never be left in
 * execution by a departure that was rolled back, and a close-out can never disagree with the
 * delivery rows it was derived from - the rows and the status move together or not at all.
 */
@Component
public class OrderExecutionPropagator {

    /** A trip that can still carry an order somewhere: everything short of finished or cancelled. */
    private static final Set<TripStatus> OPEN_CARRIER_STATES = EnumSet.of(
            TripStatus.DRAFT, TripStatus.CONFIRMED, TripStatus.READY_FOR_DISPATCH, TripStatus.IN_TRANSIT);

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
     * The vehicle left: every order still on the trip is told so.
     *
     * <p>Removed assignments are not touched. An order taken off the trip before it departed was
     * released back to the plannable pool at that moment and is somebody else's problem now.
     *
     * <p>What the departure means for each order is decided in the orders module: a fully planned
     * order moves to {@code IN_EXECUTION}, and a split order that still has something to place
     * stays where it is (R1 of {@code docs/domain/SPLIT_ORDER_EXECUTION.md}). The rows are locked
     * first, in id order, so this departure and another trip's close-out of the same order cannot
     * deadlock.
     */
    public void dispatched(CompanyScope scope, Trip trip) {
        Set<UUID> orderIds = activeOrderIds(trip);
        if (orderIds.isEmpty()) {
            return;
        }
        orderPlanningPort.lockForExecution(orderIds, scope.companyId());
        for (UUID orderId : orderIds) {
            orderPlanningPort.markInExecution(orderId, scope.companyId());
        }
    }

    /**
     * The shipment was closed out: every order for which it was the <em>last open carrier</em> is
     * closed out with whatever the delivery rows say about it right now.
     *
     * <p>R2 of {@code docs/domain/SPLIT_ORDER_EXECUTION.md}: a split order has several carriers,
     * and the first one to finish is not the end of the order. An order that still has an ACTIVE
     * assignment on another trip that has not finished is left alone here; that trip's close-out
     * is the one that will close it, and it will read the deliveries of every attempt. An order
     * with something still to place is {@code READY_FOR_PLANNING}, which cannot reach an outcome,
     * so the orders module leaves it alone as well.
     *
     * <p>The orders are locked <em>before</em> their other carriers are read. Two trips of the same
     * order completing at the same instant would otherwise each see the other still on the road,
     * and both leave the order open for ever. With the lock, the second one waits for the first
     * to commit and then sees it finished.
     *
     * <p>The fulfilment is read in one batched call rather than per order - the same N+1 discipline
     * {@code OrderFulfillmentPort} was written for. An order with nothing recorded comes back
     * {@code PENDING} and closes as failed, which is the honest reading of "the trip is over and we
     * cannot show the customer got it" and is corrected the moment somebody keys the note.
     */
    public void closedOut(CompanyScope scope, Trip trip) {
        closeOutWhereLastCarrier(scope, trip, activeOrderIds(trip));
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
     * close an order out early, because a later stop may still change what it is owed. For the same
     * reason, a correction on the first carrier of a split order whose second carrier is still
     * open changes nothing yet (R2).
     */
    public void deliveryRecorded(CompanyScope scope, Trip trip, UUID orderId) {
        if (trip.status() != TripStatus.COMPLETED) {
            return;
        }
        closeOutWhereLastCarrier(scope, trip, Set.of(orderId));
    }

    private void closeOutWhereLastCarrier(CompanyScope scope, Trip trip, Set<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return;
        }
        orderPlanningPort.lockForExecution(orderIds, scope.companyId());
        Set<UUID> stillCarried = Set.copyOf(assignments.findOrdersWithOtherOpenCarrier(orderIds,
                scope.companyId(), trip.id(), AssignmentStatus.ACTIVE, OPEN_CARRIER_STATES));
        Set<UUID> closing = new LinkedHashSet<>(orderIds);
        closing.removeAll(stillCarried);
        if (closing.isEmpty()) {
            return;
        }
        Map<UUID, OrderFulfillmentStatus> fulfillment =
                orderFulfillmentPort.fulfillmentOf(closing, scope.companyId());
        for (UUID orderId : closing) {
            orderPlanningPort.closeOut(orderId, scope.companyId(),
                    fulfillment.getOrDefault(orderId, OrderFulfillmentStatus.PENDING));
        }
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
