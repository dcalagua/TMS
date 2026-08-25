package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.shared.reference.DriverLicenseStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
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
 * @param driverName          the driver planned to run this shipment, resolved from
 *                            {@code driverId} through {@code DriverLookupPort}, or null when
 *                            none has been named - which is legal in every state (migration V26)
 * @param driverPhone         carried on the shipment header because a dispatcher's next action
 *                            after "who is driving SH-00000142" is to call them, and making the
 *                            screen fetch the fleet master for it would be one round trip and
 *                            one extra permission away from the answer
 * @param driverLicenseStatus judged against this trip's <em>planning date</em> and not against
 *                            today: the board is indexed by the day the trip runs, so "expiring
 *                            soon" here means soon relative to the day this driver drives. The
 *                            server decides it ({@code DriverLicenseStatus}) so the badge and the
 *                            refusal can never disagree
 * @param routeId             the master route this shipment was built from, if any - a
 *                            suggestion, never a constraint on its stops
 * @param plannedDepartureAt  what the plan asked for; never rewritten by what happened
 * @param readyAt             when the shipment was declared loaded and waiting, or null
 * @param actualDepartureAt   when the vehicle really left, or null - the gap against
 *                            {@code plannedDepartureAt} is the departure delay, which is why both
 *                            are here and neither is derived from the other (migration V25)
 * @param actualCompletionAt  when the trip finished, or null
 * @param allowedTransitions  the states this trip may still move to, decided by
 *                            {@code TripStatus}'s transition table and sent to the browser so a
 *                            screen renders the buttons that work instead of keeping a second copy
 *                            of the lifecycle in TypeScript. Never authorization: the server
 *                            re-checks every transition it is asked to make
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
        UUID driverId,
        String driverCode,
        String driverName,
        String driverPhone,
        String driverLicenseNumber,
        LocalDate driverLicenseExpiresOn,
        DriverLicenseStatus driverLicenseStatus,
        UUID routeId,
        String routeCode,
        String routeName,
        OffsetDateTime plannedDepartureAt,
        OffsetDateTime readyAt,
        OffsetDateTime actualDepartureAt,
        OffsetDateTime actualCompletionAt,
        OffsetDateTime cancelledAt,
        String cancelReason,
        Set<TripStatus> allowedTransitions,
        TripCapacityView capacity,
        int stopCount,
        long orderCount,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
