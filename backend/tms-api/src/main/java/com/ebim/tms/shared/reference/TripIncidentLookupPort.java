package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What planning knows about a trip's incidents, asked by the freight claims of settlement (V71).
 *
 * <p>Answered by {@code planning}, which owns {@code tms.trip}, {@code tms.order_delivery} and
 * {@code tms.trip_exception}. Settlement opens a claim against the carrier of a trip, prefilled
 * from what was recorded about it - a PARTIAL delivery with refused quantities, a reported
 * exception - and lists the open exceptions beside the claims in the incident centre. It reads all
 * of that through this port and writes none of it: resolving an exception stays planning's
 * ({@code POST /planning/trips/{id}/exceptions/{exceptionId}/resolve}).
 *
 * <p>Every method is company-scoped; an id of another company is simply absent.
 */
public interface TripIncidentLookupPort {

    /** The trip a claim is opened against. Empty when it is not this company's. */
    Optional<ClaimableTrip> findTrip(UUID tripId, UUID companyId);

    /** Shipment numbers and carriers of several trips, one query. Absent ids are not this company's. */
    Map<UUID, ClaimableTrip> findTrips(Collection<UUID> tripIds, UUID companyId);

    /** One recorded delivery, with its lines. Empty when it is not this company's. */
    Optional<DeliveryIncident> findDelivery(UUID orderDeliveryId, UUID companyId);

    /** One exception. Empty when it is not this company's. */
    Optional<ExceptionIncident> findException(UUID exceptionId, UUID companyId);

    /**
     * The company's trip exceptions for the incident centre, newest first, capped at {@code limit}.
     *
     * @param open      true for the OPEN ones, false for the RESOLVED ones, null for both
     * @param carrierId only exceptions of trips this carrier ran; null for every trip
     * @param type      a {@code TripExceptionType} name; null for every type
     * @param from      reported at or after this instant; null for no lower bound
     * @param to        reported before this instant; null for no upper bound
     */
    List<ExceptionIncident> findExceptions(UUID companyId, Boolean open, UUID carrierId, String type,
            OffsetDateTime from, OffsetDateTime to, int limit);

    /**
     * @param carrierId the trip's carrier; null for an own-fleet trip, which nobody can be claimed from
     * @param draft     a DRAFT trip never ran, so it has nothing to claim for
     */
    record ClaimableTrip(UUID tripId, String shipmentNumber, UUID carrierId, String status, boolean draft,
            LocalDate planningDate) {
    }

    /**
     * @param result      a {@code DeliveryResult} name: DELIVERED, PARTIAL, REJECTED, FAILED, NOT_ATTEMPTED
     * @param deliveredAt null unless something was handed over
     * @param lines       what arrived per order line (V45); empty when only the totals were recorded
     */
    record DeliveryIncident(UUID orderDeliveryId, UUID tripId, UUID orderId, String result,
            OffsetDateTime deliveredAt, String notes, BigDecimal refusedWeightKg, BigDecimal refusedPallets,
            List<DeliveryIncidentLine> lines) {

        public DeliveryIncident {
            lines = List.copyOf(lines);
        }
    }

    /** One order line of a delivery, in the line's own unit. */
    record DeliveryIncidentLine(UUID orderLineId, String uom, BigDecimal quantityAttempted,
            BigDecimal quantityDelivered, BigDecimal quantityRefused, String notes) {
    }

    /**
     * @param type   a {@code TripExceptionType} name
     * @param status OPEN or RESOLVED
     */
    record ExceptionIncident(UUID exceptionId, UUID tripId, String shipmentNumber, UUID carrierId,
            UUID tripStopId, String type, String status, OffsetDateTime reportedAt, String notes,
            OffsetDateTime resolvedAt) {
    }
}
