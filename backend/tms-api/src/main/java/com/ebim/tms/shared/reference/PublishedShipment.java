package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The header of a trip as an external <em>Shipment</em> - everything
 * {@code docs/domain/SHIPMENT_V2.md}'s {@code TripView} already resolves, carried across the
 * {@code planning}/{@code shared} module boundary in the port's own vocabulary rather than
 * {@code planning.application.TripView} itself ({@code ModuleBoundaryTest} forbids the latter).
 *
 * <p>Carries no company code or name: {@code planning} has no company lookup of its own (a trip
 * only ever stores {@code company_id}), and the one caller of this port already has the caller's
 * company as a resolved {@code CompanyScope} - {@code IntegrationPrincipal.companyScope()} - so
 * asking {@code planning} to re-resolve it would be a second source for the same fact.
 *
 * <p>Never carries stops or orders - see {@link PublishedShipmentDetail} for those, and
 * {@link ShipmentPublicationPort#search}'s class comment for why the split exists.
 *
 * @param status               any {@code planning.domain.TripStatus} except {@code "DRAFT"} - see
 *                              {@link ShipmentPublicationPort}'s class comment for why a draft is
 *                              not a shipment
 * @param actualDepartureAt    when the vehicle really left, or null while it has not. Reported
 *                              beside {@code plannedDepartureAt}, never instead of it, so a
 *                              partner can compute the delay rather than being told a departure
 *                              time that quietly changed meaning (migration V25)
 * @param capacitySource       {@code "SNAPSHOT"} for a confirmed shipment's frozen capacity, or
 *                              {@code "NONE"} - a cancelled shipment that was never confirmed has
 *                              no snapshot to report
 * @param maxWeightKg          null when {@code capacitySource} is {@code "NONE"}
 * @param usedWeightKg         the load at the moment of publication - always a number, never null
 * @param weightUtilizationPct null when the weight limit is unknown or zero, one decimal place -
 *                              see {@code planning.application.CapacityDimension#percentUsed()}
 */
public record PublishedShipment(
        UUID id,
        UUID companyId,
        String shipmentNumber,
        String planNumber,
        LocalDate planningDate,
        String status,
        String originCode,
        String originName,
        BigDecimal originLatitude,
        BigDecimal originLongitude,
        OffsetDateTime plannedDepartureAt,
        OffsetDateTime readyAt,
        OffsetDateTime actualDepartureAt,
        OffsetDateTime actualCompletionAt,
        String carrierCode,
        String carrierName,
        String vehicleCode,
        String vehicleLicensePlate,
        String vehicleTypeCode,
        String capacitySource,
        BigDecimal maxWeightKg,
        BigDecimal maxVolumeM3,
        BigDecimal maxPallets,
        BigDecimal usedWeightKg,
        BigDecimal usedVolumeM3,
        BigDecimal usedPallets,
        BigDecimal weightUtilizationPct,
        BigDecimal volumeUtilizationPct,
        BigDecimal palletsUtilizationPct,
        int stopCount,
        long orderCount,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
