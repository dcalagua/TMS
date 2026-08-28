package com.ebim.tms.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The order lifecycle's rules, provable without a database or a Spring context (migration V36).
 *
 * <p>Mirrors {@code TripStatusTest}: the transition table is a fact about the domain, so this is
 * where "may a delivered order be cancelled" is answered, once, rather than in a service test that
 * would have to stand a whole trip up to ask it.
 */
class OrderStatusTest {

    @Nested
    @DisplayName("the planning half")
    class PlanningHalf {

        @Test
        @DisplayName("a new order can only become plannable or be cancelled")
        void notReady() {
            assertThat(OrderStatus.NOT_READY.allowedTransitions())
                    .containsExactlyInAnyOrder(OrderStatus.READY_FOR_PLANNING, OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("a plannable order can be planned, edited back to not-ready, or cancelled")
        void readyForPlanning() {
            assertThat(OrderStatus.READY_FOR_PLANNING.allowedTransitions())
                    .containsExactlyInAnyOrder(OrderStatus.NOT_READY, OrderStatus.PLANNED, OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("a planned order can depart, be released back to the pool, or be cancelled")
        void planned() {
            assertThat(OrderStatus.PLANNED.allowedTransitions()).containsExactlyInAnyOrder(
                    OrderStatus.READY_FOR_PLANNING, OrderStatus.IN_EXECUTION, OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("only a plannable order is in the pool")
        void onlyReadyIsPlannable() {
            for (OrderStatus status : OrderStatus.values()) {
                assertThat(status.isPlannable()).isEqualTo(status == OrderStatus.READY_FOR_PLANNING);
            }
        }

        @Test
        @DisplayName("only the two pre-planning states are editable")
        void editability() {
            assertThat(EnumSet.allOf(OrderStatus.class).stream().filter(OrderStatus::isEditable))
                    .containsExactlyInAnyOrder(OrderStatus.NOT_READY, OrderStatus.READY_FOR_PLANNING);
        }
    }

    @Nested
    @DisplayName("the execution half")
    class ExecutionHalf {

        @Test
        @DisplayName("an order on a departed vehicle can only reach one of the three outcomes")
        void inExecution() {
            assertThat(OrderStatus.IN_EXECUTION.allowedTransitions()).containsExactlyInAnyOrder(
                    OrderStatus.DELIVERED, OrderStatus.PARTIALLY_DELIVERED, OrderStatus.DELIVERY_FAILED);
        }

        @Test
        @DisplayName("an order on a departed vehicle cannot be cancelled: the goods are moving")
        void inExecutionCannotBeCancelled() {
            assertThat(OrderStatus.IN_EXECUTION.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        }

        @Test
        @DisplayName("an order on a departed vehicle cannot be taken back into planning")
        void inExecutionCannotBeReplanned() {
            assertThat(OrderStatus.IN_EXECUTION.canTransitionTo(OrderStatus.READY_FOR_PLANNING)).isFalse();
            assertThat(OrderStatus.IN_EXECUTION.canTransitionTo(OrderStatus.PLANNED)).isFalse();
        }

        /**
         * The rule that makes the recording window safe. A delivery is corrected in place after the
         * trip is closed - the signed notes come back at 18:40 - so every outcome has to be able to
         * replace every other, or the correction would be recorded and the order would not follow.
         */
        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"DELIVERED", "PARTIALLY_DELIVERED", "DELIVERY_FAILED"})
        @DisplayName("any outcome may be corrected into any other")
        void outcomesAreMutuallyReachable(OrderStatus from) {
            Set<OrderStatus> outcomes =
                    EnumSet.of(OrderStatus.DELIVERED, OrderStatus.PARTIALLY_DELIVERED, OrderStatus.DELIVERY_FAILED);
            for (OrderStatus to : outcomes) {
                if (to != from) {
                    assertThat(from.canTransitionTo(to))
                            .as("%s should be correctable to %s", from, to)
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("a shortfall goes back into the pool for another attempt")
        void shortfallsAreReopenable() {
            assertThat(EnumSet.allOf(OrderStatus.class).stream().filter(OrderStatus::isReopenable))
                    .containsExactlyInAnyOrder(OrderStatus.PARTIALLY_DELIVERED, OrderStatus.DELIVERY_FAILED);
            assertThat(OrderStatus.PARTIALLY_DELIVERED.canTransitionTo(OrderStatus.READY_FOR_PLANNING)).isTrue();
            assertThat(OrderStatus.DELIVERY_FAILED.canTransitionTo(OrderStatus.READY_FOR_PLANNING)).isTrue();
        }

        @Test
        @DisplayName("a delivered order is not reopenable and not cancellable: it already happened")
        void deliveredIsFinishedWork() {
            assertThat(OrderStatus.DELIVERED.isReopenable()).isFalse();
            assertThat(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.READY_FOR_PLANNING)).isFalse();
            assertThat(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        }

        @Test
        @DisplayName("a shortfall may be given up on")
        void shortfallsMayBeCancelled() {
            assertThat(OrderStatus.PARTIALLY_DELIVERED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
            assertThat(OrderStatus.DELIVERY_FAILED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        }

        @Test
        @DisplayName("the three outcomes are exactly the closed-out states")
        void closedOut() {
            assertThat(EnumSet.allOf(OrderStatus.class).stream().filter(OrderStatus::isClosedOut))
                    .containsExactlyInAnyOrder(OrderStatus.DELIVERED, OrderStatus.PARTIALLY_DELIVERED,
                            OrderStatus.DELIVERY_FAILED);
        }
    }

    @Nested
    @DisplayName("invariants that hold across the whole table")
    class Invariants {

        @Test
        @DisplayName("cancelled is the only terminal state")
        void onlyCancelledIsTerminal() {
            assertThat(EnumSet.allOf(OrderStatus.class).stream().filter(OrderStatus::isTerminal))
                    .containsExactly(OrderStatus.CANCELLED);
            assertThat(OrderStatus.CANCELLED.allowedTransitions()).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(OrderStatus.class)
        @DisplayName("no state transitions to itself: a reflexive move is not a transition")
        void noReflexiveMoves(OrderStatus status) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(OrderStatus.class)
        @DisplayName("every state has a rule, so a new one cannot be added without one")
        void everyStateIsInTheTable(OrderStatus status) {
            assertThat(status.allowedTransitions()).isNotNull();
        }

        @Test
        @DisplayName("committed demand is every state a planner put on a trip")
        void committed() {
            assertThat(EnumSet.allOf(OrderStatus.class).stream().filter(OrderStatus::isCommitted))
                    .containsExactlyInAnyOrder(OrderStatus.PLANNED, OrderStatus.IN_EXECUTION, OrderStatus.DELIVERED,
                            OrderStatus.PARTIALLY_DELIVERED, OrderStatus.DELIVERY_FAILED);
        }

        /**
         * Every state except the terminal one has to be able to lead somewhere, or an order can be
         * parked in it forever - which is exactly the bug V36 exists to fix.
         */
        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "CANCELLED")
        @DisplayName("no state is a dead end except cancelled")
        void noDeadEnds(OrderStatus status) {
            assertThat(status.allowedTransitions()).isNotEmpty();
        }
    }
}
