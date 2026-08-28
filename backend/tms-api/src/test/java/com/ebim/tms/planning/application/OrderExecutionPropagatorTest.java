package com.ebim.tms.planning.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.shared.reference.OrderFulfillmentPort;
import com.ebim.tms.shared.reference.OrderFulfillmentStatus;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a shipment tells the orders it carries (migration V36).
 *
 * <p>The mapping from a fulfilment to a lifecycle state is deliberately <em>not</em> asserted here:
 * it belongs to the orders module and is proved in {@code OrderStatusTest} and the smoke run. What
 * this covers is the half that lives in planning - which orders are told, when, and how often.
 */
class OrderExecutionPropagatorTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TRIP = UUID.randomUUID();
    private static final UUID ORDER_A = UUID.randomUUID();
    private static final UUID ORDER_B = UUID.randomUUID();

    private TripOrderAssignmentRepository assignments;
    private OrderPlanningPort orderPlanningPort;
    private OrderFulfillmentPort orderFulfillmentPort;
    private OrderExecutionPropagator propagator;
    private CompanyScope scope;
    private Trip trip;

    @BeforeEach
    void setUp() {
        assignments = mock(TripOrderAssignmentRepository.class);
        orderPlanningPort = mock(OrderPlanningPort.class);
        orderFulfillmentPort = mock(OrderFulfillmentPort.class);
        propagator = new OrderExecutionPropagator(assignments, orderPlanningPort, orderFulfillmentPort);

        scope = mock(CompanyScope.class);
        when(scope.companyId()).thenReturn(COMPANY);
        trip = mock(Trip.class);
        when(trip.id()).thenReturn(TRIP);
    }

    private void carrying(UUID... orderIds) {
        List<TripOrderAssignment> active = java.util.Arrays.stream(orderIds).map(orderId -> {
            TripOrderAssignment assignment = mock(TripOrderAssignment.class);
            when(assignment.orderId()).thenReturn(orderId);
            return assignment;
        }).toList();
        when(assignments.findByTripIdAndStatusOrderByAssignedAtAsc(TRIP, AssignmentStatus.ACTIVE))
                .thenReturn(active);
    }

    @Nested
    @DisplayName("departure")
    class Departure {

        @Test
        @DisplayName("every order still on the trip is told the vehicle left")
        void tellsEveryOrder() {
            carrying(ORDER_A, ORDER_B);

            propagator.dispatched(scope, trip);

            verify(orderPlanningPort).markInExecution(ORDER_A, COMPANY);
            verify(orderPlanningPort).markInExecution(ORDER_B, COMPANY);
        }

        @Test
        @DisplayName("an order removed from the trip before it left is not told anything")
        void ignoresRemovedAssignments() {
            // The repository is asked for ACTIVE rows only, so a removed one never reaches here.
            carrying(ORDER_A);

            propagator.dispatched(scope, trip);

            verify(orderPlanningPort).markInExecution(ORDER_A, COMPANY);
            verify(orderPlanningPort, never()).markInExecution(eq(ORDER_B), any());
        }

        @Test
        @DisplayName("an empty trip tells nobody and asks nothing")
        void emptyTrip() {
            carrying();

            propagator.dispatched(scope, trip);

            verifyNoInteractions(orderPlanningPort);
        }

        /**
         * One order on two assignments of the same trip - the shape split allocation will produce -
         * must be told once. Telling it twice is harmless today because the port is idempotent, and
         * it would stop being harmless the moment anything counts the calls.
         */
        @Test
        @DisplayName("an order carried twice by the same trip is told once")
        void deduplicates() {
            carrying(ORDER_A, ORDER_A);

            propagator.dispatched(scope, trip);

            verify(orderPlanningPort, times(1)).markInExecution(ORDER_A, COMPANY);
        }
    }

    @Nested
    @DisplayName("close-out")
    class CloseOut {

        @Test
        @DisplayName("each order is closed out with what the delivery rows say about it")
        void closesEachOrderWithItsOwnFulfillment() {
            carrying(ORDER_A, ORDER_B);
            when(orderFulfillmentPort.fulfillmentOf(anyCollection(), eq(COMPANY)))
                    .thenReturn(Map.of(ORDER_A, OrderFulfillmentStatus.DELIVERED,
                            ORDER_B, OrderFulfillmentStatus.REJECTED));

            propagator.closedOut(scope, trip);

            verify(orderPlanningPort).closeOut(ORDER_A, COMPANY, OrderFulfillmentStatus.DELIVERED);
            verify(orderPlanningPort).closeOut(ORDER_B, COMPANY, OrderFulfillmentStatus.REJECTED);
        }

        /**
         * The N+1 the batch port exists to prevent. Two orders, one lookup.
         */
        @Test
        @DisplayName("the fulfilment of a whole trip is read in one call, not one per order")
        void readsFulfillmentInOneCall() {
            carrying(ORDER_A, ORDER_B);
            when(orderFulfillmentPort.fulfillmentOf(anyCollection(), eq(COMPANY)))
                    .thenReturn(Map.of(ORDER_A, OrderFulfillmentStatus.DELIVERED,
                            ORDER_B, OrderFulfillmentStatus.DELIVERED));

            propagator.closedOut(scope, trip);

            verify(orderFulfillmentPort, times(1)).fulfillmentOf(anyCollection(), eq(COMPANY));
        }

        @Test
        @DisplayName("an order with nothing recorded is closed out as PENDING, which orders reads as failed")
        void nothingRecorded() {
            carrying(ORDER_A);
            when(orderFulfillmentPort.fulfillmentOf(anyCollection(), eq(COMPANY))).thenReturn(Map.of());

            propagator.closedOut(scope, trip);

            verify(orderPlanningPort).closeOut(ORDER_A, COMPANY, OrderFulfillmentStatus.PENDING);
        }

        @Test
        @DisplayName("an empty trip asks the fulfilment port nothing at all")
        void emptyTrip() {
            carrying();

            propagator.closedOut(scope, trip);

            verifyNoInteractions(orderFulfillmentPort);
            verifyNoInteractions(orderPlanningPort);
        }
    }

    @Nested
    @DisplayName("a delivery recorded against the trip")
    class DeliveryRecorded {

        /**
         * The rule that keeps the order's state from drifting from the delivery rows: a note keyed
         * after the shipment was closed has to move the order too.
         */
        @Test
        @DisplayName("on a completed trip, the one order is closed out again from the corrected rows")
        void correctionAfterCompletion() {
            when(trip.status()).thenReturn(TripStatus.COMPLETED);
            when(orderFulfillmentPort.fulfillmentOf(Set.of(ORDER_A), COMPANY))
                    .thenReturn(Map.of(ORDER_A, OrderFulfillmentStatus.DELIVERED));

            propagator.deliveryRecorded(scope, trip, ORDER_A);

            verify(orderPlanningPort).closeOut(ORDER_A, COMPANY, OrderFulfillmentStatus.DELIVERED);
        }

        /**
         * Recording mid-trip must not close an order out early: a later stop can still change what
         * it is owed, and the close-out at completion is what reads the rows then.
         */
        @Test
        @DisplayName("on a running trip, nothing happens to the order")
        void midTripIsANoOp() {
            when(trip.status()).thenReturn(TripStatus.IN_TRANSIT);

            propagator.deliveryRecorded(scope, trip, ORDER_A);

            verifyNoInteractions(orderPlanningPort);
            verifyNoInteractions(orderFulfillmentPort);
        }

        @Test
        @DisplayName("it does not go looking at the trip's other orders")
        void touchesOnlyTheOrderRecorded() {
            when(trip.status()).thenReturn(TripStatus.COMPLETED);
            when(orderFulfillmentPort.fulfillmentOf(Set.of(ORDER_A), COMPANY))
                    .thenReturn(Map.of(ORDER_A, OrderFulfillmentStatus.DELIVERED));

            propagator.deliveryRecorded(scope, trip, ORDER_A);

            verify(orderPlanningPort, never()).closeOut(eq(ORDER_B), any(), any());
            verifyNoInteractions(assignments);
        }
    }
}
