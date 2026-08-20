package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TripStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The board-row view of a trip - and the shipment header
 * ({@code docs/domain/SHIPMENT_V2.md}): who is running it, from where, for which plan and day,
 * with what capacity and how much of it is used. Never the assignments or stops themselves; the
 * same list/detail split {@code OrderView}/{@code OrderDetailView} uses, for the same reason: a
 * planning board for a 300-trip day must not fan out into one query per trip.
 *
 * <p>Everything here beyond the trip's own columns is <em>resolved</em>, not stored: the origin
 * from the planning run, the carrier from {@code carrierId}, the vehicle and its type from
 * {@code fleet}, the route from {@code masterdata}, the load from a grouped sum over active
 * assignments. Migration V19's header lists each one and why it is not a column - the two that
 * genuinely could not be derived, {@code shipmentNumber} and {@code routeId}, are.
 *
 * @param shipmentNumber      the trip's identity outside this installation's planning board;
 *                            {@code tripNumber} is its identity inside {@code planNumber} and
 *                            means nothing without it
 * @param companyId           the tenant every other id here belongs to, echoed so a consumer of
 *                            one serialized shipment never has to infer it from the request
 * @param originId            the ship-from location, inherited from the planning run - a trip has
 *                            no origin of its own, so the two can never disagree (migration V11)
 * @param carrierName         resolved from {@code carrierId}, never from the vehicle: a vehicle
 *                            moved to another carrier must not rewrite who a confirmed shipment
 *                            was planned with (see {@code CarrierLookupPort})
 * @param routeId             the master route this shipment was built from, if any - a
 *                            suggestion, never a constraint on its stops
 */
public record TripView(
        UUID id,
        UUID companyId,
        UUID planningRunId,
        String planNumber,
        LocalDate planningDate,
        int tripNumber,
        String shipmentNumber,
        TripStatus status,
        UUID originId,
        String originCode,
        String originName,
        BigDecimal originLatitude,
        BigDecimal originLongitude,
        UUID vehicleId,
        String vehicleCode,
        String vehicleLicensePlate,
        String vehicleTypeCode,
        UUID carrierId,
        String carrierName,
        UUID routeId,
        String routeCode,
        String routeName,
        OffsetDateTime plannedDepartureAt,
        TripCapacityView capacity,
        int stopCount,
        long orderCount,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
