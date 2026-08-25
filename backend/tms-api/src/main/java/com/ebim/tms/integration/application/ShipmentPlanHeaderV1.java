package com.ebim.tms.integration.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The header of the external {@code ShipmentPlan V1} contract (job 08) - one row of
 * {@code GET /integration/v1/shipments}, and the {@code shipment} field of one
 * {@code GET /integration/v1/shipments/{shipmentNumber}}.
 *
 * <p>Deliberately its own type rather than {@code shared.reference.PublishedShipment} reused
 * as-is: this is the <em>frozen, versioned</em> wire shape a partner integrates against, and
 * {@code PublishedShipment} is free to change for reasons internal to how {@code planning} reads
 * a trip. {@link IntegrationShipmentService} is the one place that maps between them, so a future
 * {@code ShipmentPlan V2} adds a second mapping method here, never a breaking change to this one.
 *
 * @param companyCode           the caller's own company, identified by code rather than uuid -
 *                              the same reasoning {@code IntegrationIdentityController}'s
 *                              {@code companyCode} field documents: publishing an internal id to
 *                              an external system invites it to be used as a key
 * @param status                {@code "CONFIRMED"}, {@code "READY_FOR_DISPATCH"},
 *                              {@code "IN_TRANSIT"}, {@code "COMPLETED"} or {@code "CANCELLED"}.
 *                              The last three arrived with migration V25 and are additive: a
 *                              partner that filters on {@code CONFIRMED} keeps seeing exactly
 *                              what it saw before
 * @param actualDepartureAt     when the vehicle really left, or null - reported alongside
 *                              {@code plannedDepartureAt}, which keeps meaning what the plan asked
 *                              for
 * @param capacitySource        {@code "SNAPSHOT"} (a confirmed shipment's frozen capacity at
 *                              confirmation) or {@code "NONE"}
 * @param weightUtilizationPct  percentage used, one decimal place, or null when the limit is
 *                              unknown or zero - never a division by zero, never a lie
 */
public record ShipmentPlanHeaderV1(
        UUID id,
        String companyCode,
        String companyName,
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
