package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.PlannableOrder;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The assignment aggregate's own operations, shared by {@link TripService} (assign, remove, move,
 * cancel a trip) and {@link PlanningRunService} (cancel a whole run).
 *
 * <p>Exists so those two services cannot drift on the three things that must always happen
 * together: closing an assignment rather than deleting it, releasing the order back to the
 * eligible pool, and bringing the trip's stop list back in line with what is left.
 *
 * <p>Every method assumes the caller already holds the trip's row lock
 * ({@code TripRepository.findByIdAndCompanyIdForUpdate}) and runs inside its transaction.
 */
@Service
public class TripAssignmentService {

    private final TripOrderAssignmentRepository assignmentRepository;
    private final OrderPlanningPort orderPlanningPort;

    public TripAssignmentService(
            TripOrderAssignmentRepository assignmentRepository, OrderPlanningPort orderPlanningPort) {
        this.assignmentRepository = assignmentRepository;
        this.orderPlanningPort = orderPlanningPort;
    }

    /** The trip's current load, summed by the database over active rows only. */
    @Transactional(readOnly = true)
    public CapacityLoad currentLoad(UUID tripId) {
        return CapacityLoad.of(assignmentRepository.loadByTripId(tripId, AssignmentStatus.ACTIVE));
    }

    @Transactional(readOnly = true)
    public List<TripOrderAssignment> activeAssignments(UUID tripId) {
        return assignmentRepository.findByTripIdAndStatusOrderByAssignedAtAsc(tripId, AssignmentStatus.ACTIVE);
    }

    /**
     * Opens an assignment. Deliberately does <em>not</em> touch the order's status: assigning a
     * free order also moves it to {@code PLANNED} ({@code TripService.assignOrder} does that
     * immediately after), while moving an already-planned order between trips must leave the
     * status exactly where it is. Keeping the two steps separate is what lets one method serve
     * both without a boolean flag deciding whether the order lifecycle advances.
     *
     * <p>The {@code DataIntegrityViolationException} branch is not defensive decoration: it is the
     * moment the partial unique index {@code uq_trip_order_assignment_open_whole_order} catches
     * two planners who both passed their own "is this order still free?" check in their own
     * snapshot and then both tried to insert. One of them lands here and is told, in the language
     * of the planning board, to reload.
     */
    public TripOrderAssignment open(Trip trip, PlannableOrder order, OrderAmounts amounts, UUID actorId) {
        // The whole order, or a slice of it (migration V37). A row that covers everything the
        // customer ordered is flagged whole, which is what puts it under V11's installation-wide
        // unique index; a slice is flagged false and is deliberately outside it.
        boolean wholeOrder = amounts.covers(OrderAmounts.wholeOf(order));
        TripOrderAssignment assignment =
                new TripOrderAssignment(trip.companyId(), trip.id(), order.id(), amounts, wholeOrder, actorId);
        try {
            assignmentRepository.saveAndFlush(assignment);
        } catch (DataIntegrityViolationException raced) {
            throw new ConflictException("Order " + order.orderNumber()
                    + " was assigned to a trip by someone else. Reload the planning board and try again.");
        }
        return assignment;
    }

    /**
     * Closes an assignment, keeping it as history.
     *
     * <p>Flushed immediately, and that is load-bearing: a move closes the source assignment and
     * opens the target one in the same transaction, and Hibernate's flush order runs insertions
     * before updates. Without this flush the new row would hit
     * {@code uq_trip_order_assignment_open_whole_order} against a row this transaction has already
     * logically closed - the same flush-ordering trap {@code V10}'s deferrable line-number
     * constraint documents, here solved by ordering the statements rather than deferring the
     * check (a partial index cannot be {@code DEFERRABLE}).
     */
    public void close(TripOrderAssignment assignment, String reason, UUID actorId) {
        assignment.close(reason, actorId);
        assignmentRepository.saveAndFlush(assignment);
    }

    /**
     * Closes an assignment and gives its amounts back to the order's pending pool (migration V37).
     *
     * <p>Releases exactly what this row carried, not "the whole order": with split allocation the
     * order may still be on another trip, and returning all of it would leave the ledger claiming
     * that nothing is on a truck while a truck is loaded.
     */
    public void closeAndRelease(TripOrderAssignment assignment, String reason, UUID actorId) {
        close(assignment, reason, actorId);
        orderPlanningPort.releaseAllocation(assignment.orderId(), assignment.companyId(), assignment.assigned());
    }

    /** Closes every active assignment of a trip and releases all of its orders - trip or run cancellation. */
    public void releaseAll(Trip trip, String reason, UUID actorId) {
        for (TripOrderAssignment assignment : activeAssignments(trip.id())) {
            closeAndRelease(assignment, reason, actorId);
        }
        trip.syncStops(List.of(), actorId);
    }

    /**
     * Recomputes the trip's stop list from what is now assigned, preserving the planner's manual
     * ordering (see {@link Trip#syncStops}). Called after every assignment change - a stop list
     * that lags behind its assignments is what makes a driver miss a delivery.
     *
     * <p>Verifies the result before returning ({@link #requireStopsCoverAssignments}): the whole
     * point of the stop list is that it is derivable from the assignments, so checking that it
     * actually was costs one set comparison over data already in hand and turns "a shipment
     * silently lost a destination" from a class of production bug into a failed transaction.
     */
    public void refreshStops(Trip trip, UUID companyId, UUID actorId) {
        List<TripOrderAssignment> active = activeAssignments(trip.id());
        Map<UUID, PlannableOrder> orders = orderPlanningPort.findAllInCompany(
                active.stream().map(TripOrderAssignment::orderId).collect(Collectors.toSet()), companyId);
        trip.syncStops(TripStopPlanner.plan(active, orders), actorId);
        requireStopsCoverAssignments(trip, active, orders);
    }

    /**
     * The stop-coverage invariant: every destination an active assignment delivers to has exactly
     * one stop, and no stop exists that nothing is delivered at.
     *
     * <p>Stated as an {@link IllegalStateException} rather than a caller-facing 4xx on purpose -
     * no request can ask for this state, so reaching it is a defect in whatever mutated the trip,
     * and the honest answer is 500 plus a rolled-back transaction rather than a shipment that
     * looks fine and misses a delivery. {@link Trip#assertStopSequenceIntegrity} is its sibling
     * for positions; this one is for membership.
     */
    void requireStopsCoverAssignments(
            Trip trip, List<TripOrderAssignment> active, Map<UUID, PlannableOrder> orders) {
        Set<UUID> assigned = active.stream()
                .map(assignment -> orders.get(assignment.orderId()))
                .filter(Objects::nonNull)
                .map(PlannableOrder::destinationId)
                .collect(Collectors.toSet());
        Set<UUID> stopped = trip.stops().stream().map(TripStop::destinationId).collect(Collectors.toSet());
        if (!stopped.equals(assigned)) {
            throw new IllegalStateException("trip " + trip.shipmentNumber()
                    + " stops at " + stopped + " but its active assignments deliver to " + assigned);
        }
        trip.assertStopSequenceIntegrity();
    }

}
