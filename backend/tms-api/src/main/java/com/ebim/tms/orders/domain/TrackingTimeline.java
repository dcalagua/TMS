package com.ebim.tms.orders.domain;

import com.ebim.tms.shared.reference.OrderTrackingPort.OrderTrackingLeg;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a customer tracking link says about an order: one status, a timeline, the leg it is about, and
 * when the order closed (ADR-029 decision 4). Pure - no clock, no database - so every rule here is a
 * unit test.
 *
 * <h2>Which leg</h2>
 *
 * <p>An order may travel on more than one trip (V37 splits, or a second attempt after a failed one), so
 * the link speaks about one <em>current</em> leg: the most advanced of the trips still carrying it and
 * without a result; failing that, the last one that recorded a result. A customer asks "where is my
 * order", singular.
 *
 * <h2>Which status</h2>
 *
 * <p>A cancelled order is {@code CANCELLED} whatever its trips say. A leg still under way wins next -
 * an order 60 delivered and 40 on the road is on the road. Then the order's own terminal state
 * ({@code DELIVERED}, {@code PARTIALLY_DELIVERED}, {@code DELIVERY_FAILED}), which planning closes from
 * every attempt's quantities; then the last recorded result; then {@code RECEIVED}.
 *
 * @param closedAt when the order reached a final state - the anchor of the link's soft expiry - or null
 *     while it is still open
 */
public record TrackingTimeline(
        CustomerTrackingStatus status,
        List<Step> steps,
        OrderTrackingLeg currentLeg,
        OffsetDateTime closedAt) {

    public enum StepCode { RECEIVED, PLANNED, IN_TRANSIT, ARRIVED, OUTCOME }

    public enum StepState { DONE, PENDING }

    /** @param outcome on the {@code OUTCOME} step once reached: what the outcome was; null elsewhere */
    public record Step(StepCode code, StepState state, OffsetDateTime at, CustomerTrackingStatus outcome) {
    }

    private static final Set<OrderStatus> STILL_OPEN_FOR_MORE =
            Set.of(OrderStatus.NOT_READY, OrderStatus.READY_FOR_PLANNING, OrderStatus.PLANNED);

    private static final Set<CustomerTrackingStatus> OUTCOMES = Set.of(CustomerTrackingStatus.DELIVERED,
            CustomerTrackingStatus.PARTIALLY_DELIVERED, CustomerTrackingStatus.REJECTED,
            CustomerTrackingStatus.NOT_DELIVERED);

    public TrackingTimeline {
        steps = List.copyOf(steps);
    }

    public static TrackingTimeline of(OrderStatus orderStatus, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            List<OrderTrackingLeg> legs) {
        Objects.requireNonNull(orderStatus, "orderStatus");
        List<OrderTrackingLeg> all = legs == null ? List.of() : List.copyOf(legs);

        OrderTrackingLeg underWay = all.stream()
                .filter(leg -> leg.activeAssignment() && !resolved(leg))
                .max(Comparator.comparingInt(TrackingTimeline::progress))
                .orElse(null);
        OrderTrackingLeg lastResolved = all.stream()
                .filter(TrackingTimeline::resolved)
                .reduce((earlier, later) -> later)
                .orElse(null);
        OrderTrackingLeg current = underWay != null ? underWay : lastResolved;

        CustomerTrackingStatus status = statusOf(orderStatus, underWay, lastResolved);
        OffsetDateTime closedAt = closedAt(orderStatus, updatedAt, all);

        if (status == CustomerTrackingStatus.CANCELLED) {
            return new TrackingTimeline(status, List.of(
                    new Step(StepCode.RECEIVED, StepState.DONE, createdAt, null),
                    new Step(StepCode.OUTCOME, StepState.DONE, updatedAt, CustomerTrackingStatus.CANCELLED)),
                    null, closedAt);
        }
        return new TrackingTimeline(status, steps(status, createdAt, current), current, closedAt);
    }

    /** True once the outcome is known: the link may stop showing an arrival window. */
    public boolean hasOutcome() {
        return OUTCOMES.contains(status) || status == CustomerTrackingStatus.CANCELLED;
    }

    private static CustomerTrackingStatus statusOf(OrderStatus orderStatus, OrderTrackingLeg underWay,
            OrderTrackingLeg lastResolved) {
        if (orderStatus == OrderStatus.CANCELLED) {
            return CustomerTrackingStatus.CANCELLED;
        }
        if (underWay != null) {
            return switch (progress(underWay)) {
                case 2 -> CustomerTrackingStatus.ARRIVED;
                case 1 -> CustomerTrackingStatus.IN_TRANSIT;
                default -> CustomerTrackingStatus.PLANNED;
            };
        }
        switch (orderStatus) {
            case DELIVERED:
                return CustomerTrackingStatus.DELIVERED;
            case PARTIALLY_DELIVERED:
                return CustomerTrackingStatus.PARTIALLY_DELIVERED;
            case DELIVERY_FAILED:
                return lastResolved != null && "REJECTED".equals(lastResolved.deliveryResult())
                        ? CustomerTrackingStatus.REJECTED
                        : CustomerTrackingStatus.NOT_DELIVERED;
            default:
                break;
        }
        if (lastResolved == null) {
            return CustomerTrackingStatus.RECEIVED;
        }
        return switch (lastResolved.deliveryResult()) {
            // Delivered on this trip while the order still has a remainder to plan: part of it arrived.
            case "DELIVERED" -> STILL_OPEN_FOR_MORE.contains(orderStatus)
                    ? CustomerTrackingStatus.PARTIALLY_DELIVERED
                    : CustomerTrackingStatus.DELIVERED;
            case "PARTIAL" -> CustomerTrackingStatus.PARTIALLY_DELIVERED;
            case "REJECTED" -> CustomerTrackingStatus.REJECTED;
            default -> CustomerTrackingStatus.NOT_DELIVERED;
        };
    }

    private static List<Step> steps(CustomerTrackingStatus status, OffsetDateTime createdAt, OrderTrackingLeg leg) {
        boolean outcome = OUTCOMES.contains(status);
        boolean planned = leg != null || outcome;
        boolean departed = leg != null && progress(leg) >= 1 || outcome;
        boolean arrived = leg != null && (progress(leg) >= 2
                || (resolved(leg) && !"NOT_ATTEMPTED".equals(leg.deliveryResult())));

        List<Step> steps = new ArrayList<>(5);
        steps.add(new Step(StepCode.RECEIVED, StepState.DONE, createdAt, null));
        steps.add(new Step(StepCode.PLANNED, state(planned), leg == null ? null : leg.plannedDepartureAt(), null));
        steps.add(new Step(StepCode.IN_TRANSIT, state(departed),
                leg == null ? null : leg.actualDepartureAt(), null));
        steps.add(new Step(StepCode.ARRIVED, state(arrived), leg == null ? null : leg.stopArrivedAt(), null));
        steps.add(new Step(StepCode.OUTCOME, state(outcome),
                outcome && leg != null ? leg.deliveredAt() : null, outcome ? status : null));
        return steps;
    }

    private static StepState state(boolean done) {
        return done ? StepState.DONE : StepState.PENDING;
    }

    private static OffsetDateTime closedAt(OrderStatus orderStatus, OffsetDateTime updatedAt,
            List<OrderTrackingLeg> legs) {
        return switch (orderStatus) {
            case CANCELLED -> updatedAt;
            case DELIVERED, PARTIALLY_DELIVERED, DELIVERY_FAILED -> legs.stream()
                    .map(OrderTrackingLeg::deliveredAt)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(updatedAt);
            default -> null;
        };
    }

    private static boolean resolved(OrderTrackingLeg leg) {
        return leg.deliveryResult() != null;
    }

    /** 0 = planned, 1 = the vehicle has left, 2 = it is at the stop. */
    private static int progress(OrderTrackingLeg leg) {
        if (leg.stopArrivedAt() != null || "ARRIVED".equals(leg.stopStatus()) || "IN_SERVICE".equals(leg.stopStatus())
                || "COMPLETED".equals(leg.stopStatus())) {
            return 2;
        }
        if (leg.actualDepartureAt() != null || "IN_TRANSIT".equals(leg.tripStatus())
                || "COMPLETED".equals(leg.tripStatus())) {
            return 1;
        }
        return 0;
    }
}
