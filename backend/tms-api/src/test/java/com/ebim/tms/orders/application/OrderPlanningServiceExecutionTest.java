package com.ebim.tms.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.orders.domain.OrderPriority;
import com.ebim.tms.orders.domain.OrderStatus;
import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.orders.infrastructure.TransportOrderRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.reference.OrderFulfillmentStatus;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The execution transitions of the order lifecycle as planning drives them (migration V36):
 * what each fulfilment means, and what happens when a call arrives twice.
 *
 * <p>Idempotency is the theme. Dispatch touches every order on a trip in one transaction and is
 * retried as a whole; a retry that failed on the second order must not fail differently on the
 * first, and a completion replayed after somebody reopened the order must not undo the replan.
 */
class OrderPlanningServiceExecutionTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    private TransportOrderRepository repository;
    private OrderPlanningService service;

    @BeforeEach
    void setUp() {
        repository = mock(TransportOrderRepository.class);
        AuditActorProvider actors = mock(AuditActorProvider.class);
        when(actors.requireAppUserId()).thenReturn(ACTOR);
        service = new OrderPlanningService(repository, actors);
        when(repository.saveAndFlush(any(TransportOrder.class))).thenAnswer(call -> call.getArgument(0));
    }

    /** An order parked in {@code status}, reached the way the product reaches it. */
    private TransportOrder orderIn(OrderStatus status) {
        TransportOrder order = new TransportOrder(COMPANY, "TO-00000001", null, null, UUID.randomUUID(),
                UUID.randomUUID(), "Customer", null, LocalDate.of(2026, 1, 15), OrderPriority.NORMAL, null, null,
                ACTOR);
        switch (status) {
            case NOT_READY -> { }
            case READY_FOR_PLANNING -> order.markReadyForPlanning(ACTOR);
            case PLANNED -> {
                order.markReadyForPlanning(ACTOR);
                order.markPlanned(ACTOR);
            }
            case IN_EXECUTION -> {
                order.markReadyForPlanning(ACTOR);
                order.markPlanned(ACTOR);
                order.markInExecution(ACTOR);
            }
            case DELIVERED, PARTIALLY_DELIVERED, DELIVERY_FAILED -> {
                order.markReadyForPlanning(ACTOR);
                order.markPlanned(ACTOR);
                order.markInExecution(ACTOR);
                order.closeOut(status, ACTOR);
            }
            case CANCELLED -> order.cancel("no longer needed", ACTOR);
        }
        assertThat(order.status()).isEqualTo(status);
        when(repository.findByIdAndCompanyIdForUpdate(ORDER, COMPANY)).thenReturn(Optional.of(order));
        return order;
    }

    @Nested
    @DisplayName("markInExecution")
    class MarkInExecution {

        @Test
        @DisplayName("a planned order moves onto the road")
        void plannedMoves() {
            TransportOrder order = orderIn(OrderStatus.PLANNED);

            service.markInExecution(ORDER, COMPANY);

            assertThat(order.status()).isEqualTo(OrderStatus.IN_EXECUTION);
            verify(repository).saveAndFlush(order);
        }

        @Test
        @DisplayName("a retried dispatch changes nothing and writes nothing")
        void isIdempotent() {
            TransportOrder order = orderIn(OrderStatus.IN_EXECUTION);

            service.markInExecution(ORDER, COMPANY);

            assertThat(order.status()).isEqualTo(OrderStatus.IN_EXECUTION);
            verify(repository, never()).saveAndFlush(any(TransportOrder.class));
        }

        /**
         * The replay that would otherwise be a real bug: a dispatch retried after the trip was
         * closed out must not drag a delivered order back onto the road.
         */
        @ParameterizedTest
        @CsvSource({"DELIVERED", "PARTIALLY_DELIVERED", "DELIVERY_FAILED"})
        @DisplayName("a replayed dispatch cannot drag a closed-out order backwards")
        void doesNotUndoACloseOut(OrderStatus closed) {
            TransportOrder order = orderIn(closed);

            service.markInExecution(ORDER, COMPANY);

            assertThat(order.status()).isEqualTo(closed);
            verify(repository, never()).saveAndFlush(any(TransportOrder.class));
        }

        @ParameterizedTest
        @CsvSource({"NOT_READY", "READY_FOR_PLANNING", "CANCELLED"})
        @DisplayName("an order that was never planned cannot be dispatched, and says why")
        void refusesTheUnplannable(OrderStatus status) {
            orderIn(status);

            assertThatThrownBy(() -> service.markInExecution(ORDER, COMPANY))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("cannot be dispatched");
        }
    }

    @Nested
    @DisplayName("closeOut")
    class CloseOut {

        @ParameterizedTest
        @CsvSource({
            "DELIVERED,          DELIVERED",
            "PARTIALLY_DELIVERED, PARTIALLY_DELIVERED",
            "REJECTED,           DELIVERY_FAILED",
            "FAILED,             DELIVERY_FAILED",
            "NOT_ATTEMPTED,      DELIVERY_FAILED",
            "PENDING,            DELIVERY_FAILED",
        })
        @DisplayName("each fulfilment closes the order into the state it means")
        void mapsFulfillmentToStatus(OrderFulfillmentStatus fulfillment, OrderStatus expected) {
            TransportOrder order = orderIn(OrderStatus.IN_EXECUTION);

            service.closeOut(ORDER, COMPANY, fulfillment);

            assertThat(order.status()).isEqualTo(expected);
        }

        @Test
        @DisplayName("closing out twice with the same fulfilment writes once")
        void isIdempotent() {
            TransportOrder order = orderIn(OrderStatus.IN_EXECUTION);

            service.closeOut(ORDER, COMPANY, OrderFulfillmentStatus.DELIVERED);
            service.closeOut(ORDER, COMPANY, OrderFulfillmentStatus.DELIVERED);

            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
            verify(repository, org.mockito.Mockito.times(1)).saveAndFlush(order);
        }

        @Test
        @DisplayName("a correction after completion moves the order to the corrected outcome")
        void corrects() {
            TransportOrder order = orderIn(OrderStatus.DELIVERY_FAILED);

            service.closeOut(ORDER, COMPANY, OrderFulfillmentStatus.DELIVERED);

            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        }

        /**
         * The other replay that would be a real bug. Somebody reopened the failed order and a
         * planner has already put it on tomorrow's run; a late completion event for the old trip
         * must not silently pull it back out of the pool.
         */
        @ParameterizedTest
        @CsvSource({"READY_FOR_PLANNING", "PLANNED", "CANCELLED"})
        @DisplayName("a replayed close-out cannot undo a reopen or a cancellation")
        void doesNotUndoAReopen(OrderStatus after) {
            TransportOrder order = orderIn(after);

            service.closeOut(ORDER, COMPANY, OrderFulfillmentStatus.DELIVERED);

            assertThat(order.status()).isEqualTo(after);
            verify(repository, never()).saveAndFlush(any(TransportOrder.class));
        }
    }

    @Nested
    @DisplayName("the row lock")
    class RowLock {

        /**
         * Both execution transitions take the write lock before reading the status. Two dispatchers
         * racing the same shipment would otherwise both read PLANNED and both write, and the
         * loser's optimistic-lock failure would abort a departure that had already half happened.
         */
        @Test
        @DisplayName("both execution transitions read the order under a write lock")
        void takesTheLock() {
            orderIn(OrderStatus.PLANNED);
            service.markInExecution(ORDER, COMPANY);
            orderIn(OrderStatus.IN_EXECUTION);
            service.closeOut(ORDER, COMPANY, OrderFulfillmentStatus.DELIVERED);

            verify(repository, org.mockito.Mockito.atLeast(2)).findByIdAndCompanyIdForUpdate(ORDER, COMPANY);
            verify(repository, never()).findByIdAndCompanyId(ORDER, COMPANY);
        }
    }
}
