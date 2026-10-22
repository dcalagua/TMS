package com.ebim.tms.shared.reference;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What planning knows about how one order is travelling, asked by the customer tracking link
 * (migration V74, ADR-029).
 *
 * <p>The direction is {@link OrderFulfillmentPort}'s: {@code orders} owns the link and the order, and
 * planning owns trips, stops and delivery results. So orders asks and planning answers in plain values
 * - trip, stop and result <em>names</em> as strings, because their enums live in planning.
 *
 * <p>One call per public view, never per row: the link is for one order.
 */
public interface OrderTrackingPort {

    /**
     * The order's legs - one per trip that carries it, or carried it and recorded a result - oldest
     * first. A draft or cancelled trip is not a leg: a plan nobody confirmed is not a promise to a
     * customer. An order of another company has no legs.
     *
     * @param destinationId the order's destination, which is how a trip's stop for it is found before
     *     a delivery has been recorded against that stop
     */
    List<OrderTrackingLeg> legsOf(UUID orderId, UUID destinationId, UUID companyId);

    /**
     * One trip's part in delivering the order.
     *
     * @param tripStatus a {@code TripStatus} name: CONFIRMED, READY_FOR_DISPATCH, IN_TRANSIT, COMPLETED
     * @param stopStatus a {@code StopExecutionStatus} name, or null when the trip has no stop for it
     * @param deliveryResult a {@code DeliveryResult} name, or null when nothing has been recorded
     * @param activeAssignment whether the order is still on this trip ({@code ACTIVE}); a leg whose
     *     assignment was removed after a result is history, kept so the result is not lost
     * @param carrierId the carrier the trip is planned with, for display through {@link CarrierLookupPort}
     * @param vehicleId the vehicle on the trip, for its plate through {@link VehicleLookupPort}
     */
    record OrderTrackingLeg(
            String tripStatus,
            boolean activeAssignment,
            LocalDate planningDate,
            OffsetDateTime plannedDepartureAt,
            OffsetDateTime actualDepartureAt,
            OffsetDateTime actualCompletionAt,
            UUID carrierId,
            UUID vehicleId,
            String stopStatus,
            OffsetDateTime stopArrivedAt,
            OffsetDateTime stopEtaArrivalAt,
            LocalTime serviceWindowStart,
            LocalTime serviceWindowEnd,
            String deliveryResult,
            OffsetDateTime deliveredAt,
            String receiverName) {
    }
}
