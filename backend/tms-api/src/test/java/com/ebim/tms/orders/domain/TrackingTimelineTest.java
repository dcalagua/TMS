package com.ebim.tms.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.orders.domain.TrackingTimeline.StepCode;
import com.ebim.tms.orders.domain.TrackingTimeline.StepState;
import com.ebim.tms.shared.reference.OrderTrackingPort.OrderTrackingLeg;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-029 decision 4: the customer's status and timeline, derived from the order and its legs. */
class TrackingTimelineTest {

    private static final OffsetDateTime CREATED = OffsetDateTime.of(2026, 10, 20, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime UPDATED = CREATED.plusDays(1);
    private static final OffsetDateTime PLANNED_DEPARTURE = CREATED.plusDays(2);
    private static final OffsetDateTime DEPARTED = PLANNED_DEPARTURE.plusMinutes(10);
    private static final OffsetDateTime ARRIVED = DEPARTED.plusHours(2);
    private static final OffsetDateTime DELIVERED_AT = ARRIVED.plusMinutes(20);

    @Test
    @DisplayName("an order on no confirmed trip is RECEIVED, and only the first step is done")
    void received() {
        TrackingTimeline timeline = TrackingTimeline.of(OrderStatus.READY_FOR_PLANNING, CREATED, UPDATED, List.of());

        assertThat(timeline.status()).isEqualTo(CustomerTrackingStatus.RECEIVED);
        assertThat(timeline.currentLeg()).isNull();
        assertThat(timeline.closedAt()).isNull();
        assertThat(states(timeline)).containsExactly(StepState.DONE, StepState.PENDING, StepState.PENDING,
                StepState.PENDING, StepState.PENDING);
    }

    @Test
    @DisplayName("planned, en ruta, llegó: each follows the trip and the stop")
    void underWay() {
        assertThat(TrackingTimeline.of(OrderStatus.PLANNED, CREATED, UPDATED, List.of(leg("CONFIRMED", null, null, null)))
                .status()).isEqualTo(CustomerTrackingStatus.PLANNED);

        TrackingTimeline inTransit = TrackingTimeline.of(OrderStatus.IN_EXECUTION, CREATED, UPDATED,
                List.of(leg("IN_TRANSIT", DEPARTED, null, null)));
        assertThat(inTransit.status()).isEqualTo(CustomerTrackingStatus.IN_TRANSIT);
        assertThat(inTransit.steps().get(2).at()).isEqualTo(DEPARTED);
        assertThat(states(inTransit)).containsExactly(StepState.DONE, StepState.DONE, StepState.DONE,
                StepState.PENDING, StepState.PENDING);

        TrackingTimeline arrived = TrackingTimeline.of(OrderStatus.IN_EXECUTION, CREATED, UPDATED,
                List.of(leg("IN_TRANSIT", DEPARTED, ARRIVED, null)));
        assertThat(arrived.status()).isEqualTo(CustomerTrackingStatus.ARRIVED);
        assertThat(arrived.steps().get(3).at()).isEqualTo(ARRIVED);
        assertThat(arrived.hasOutcome()).isFalse();
    }

    @Test
    @DisplayName("a recorded delivery is the outcome even before the trip closes; the order closing dates the soft expiry")
    void delivered() {
        TrackingTimeline recorded = TrackingTimeline.of(OrderStatus.IN_EXECUTION, CREATED, UPDATED,
                List.of(leg("IN_TRANSIT", DEPARTED, ARRIVED, "DELIVERED")));
        assertThat(recorded.status()).isEqualTo(CustomerTrackingStatus.DELIVERED);
        assertThat(recorded.closedAt()).as("still open: the trip has not closed the order").isNull();
        assertThat(recorded.steps().get(4).outcome()).isEqualTo(CustomerTrackingStatus.DELIVERED);
        assertThat(recorded.steps().get(4).at()).isEqualTo(DELIVERED_AT);

        TrackingTimeline closed = TrackingTimeline.of(OrderStatus.DELIVERED, CREATED, UPDATED.plusDays(5),
                List.of(leg("COMPLETED", DEPARTED, ARRIVED, "DELIVERED")));
        assertThat(closed.status()).isEqualTo(CustomerTrackingStatus.DELIVERED);
        assertThat(closed.closedAt()).as("the handover, not the paperwork").isEqualTo(DELIVERED_AT);
        assertThat(closed.hasOutcome()).isTrue();
        assertThat(states(closed)).containsOnly(StepState.DONE);
    }

    @Test
    @DisplayName("partial, rejected and failed are three different outcomes")
    void otherOutcomes() {
        assertThat(TrackingTimeline.of(OrderStatus.PARTIALLY_DELIVERED, CREATED, UPDATED,
                List.of(leg("COMPLETED", DEPARTED, ARRIVED, "PARTIAL"))).status())
                .isEqualTo(CustomerTrackingStatus.PARTIALLY_DELIVERED);
        assertThat(TrackingTimeline.of(OrderStatus.DELIVERY_FAILED, CREATED, UPDATED,
                List.of(leg("COMPLETED", DEPARTED, ARRIVED, "REJECTED"))).status())
                .isEqualTo(CustomerTrackingStatus.REJECTED);
        TrackingTimeline failed = TrackingTimeline.of(OrderStatus.DELIVERY_FAILED, CREATED, UPDATED,
                List.of(leg("COMPLETED", DEPARTED, null, "NOT_ATTEMPTED")));
        assertThat(failed.status()).isEqualTo(CustomerTrackingStatus.NOT_DELIVERED);
        assertThat(failed.steps().get(3).state()).as("no arrival is invented for a stop that was never attempted").isEqualTo(StepState.PENDING);
    }

    @Test
    @DisplayName("a cancelled order is CANCELLED whatever its trips say, with a two-step timeline")
    void cancelled() {
        TrackingTimeline timeline = TrackingTimeline.of(OrderStatus.CANCELLED, CREATED, UPDATED,
                List.of(leg("CONFIRMED", null, null, null)));

        assertThat(timeline.status()).isEqualTo(CustomerTrackingStatus.CANCELLED);
        assertThat(timeline.closedAt()).isEqualTo(UPDATED);
        assertThat(timeline.steps()).extracting(TrackingTimeline.Step::code)
                .containsExactly(StepCode.RECEIVED, StepCode.OUTCOME);
        assertThat(timeline.currentLeg()).isNull();
    }

    @Test
    @DisplayName("a split order: 60 delivered on the first trip and 40 on the road is on the road")
    void splitOrderUnderWay() {
        OrderTrackingLeg first = leg("COMPLETED", DEPARTED, ARRIVED, "DELIVERED");
        OrderTrackingLeg second = leg("IN_TRANSIT", DEPARTED.plusDays(1), null, null);

        TrackingTimeline timeline = TrackingTimeline.of(OrderStatus.IN_EXECUTION, CREATED, UPDATED, List.of(first, second));
        assertThat(timeline.status()).isEqualTo(CustomerTrackingStatus.IN_TRANSIT);
        assertThat(timeline.currentLeg()).isSameAs(second);
    }

    @Test
    @DisplayName("delivered on a trip while the rest of the order is not yet planned reads as partly delivered")
    void splitOrderWithARemainder() {
        TrackingTimeline timeline = TrackingTimeline.of(OrderStatus.READY_FOR_PLANNING, CREATED, UPDATED,
                List.of(leg("COMPLETED", DEPARTED, ARRIVED, "DELIVERED")));
        assertThat(timeline.status()).isEqualTo(CustomerTrackingStatus.PARTIALLY_DELIVERED);
        assertThat(timeline.closedAt()).isNull();
    }

    private static List<StepState> states(TrackingTimeline timeline) {
        return timeline.steps().stream().map(TrackingTimeline.Step::state).toList();
    }

    private static OrderTrackingLeg leg(String tripStatus, OffsetDateTime departed, OffsetDateTime arrived,
            String result) {
        String stopStatus = result != null ? "COMPLETED" : arrived != null ? "ARRIVED" : "PENDING";
        if ("NOT_ATTEMPTED".equals(result)) {
            stopStatus = "SKIPPED";
        }
        return new OrderTrackingLeg(tripStatus, true, LocalDate.of(2026, 10, 22), PLANNED_DEPARTURE, departed,
                null, UUID.randomUUID(), UUID.randomUUID(), stopStatus, arrived, null, null, null, result,
                result == null || "NOT_ATTEMPTED".equals(result) ? null : DELIVERED_AT,
                result == null ? null : "María López");
    }
}
