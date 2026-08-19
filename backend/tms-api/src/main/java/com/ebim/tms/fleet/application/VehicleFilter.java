package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.VehicleAvailabilityStatus;
import java.util.UUID;

/** The optional list filters for {@code GET /fleet/vehicles}, bound alongside {@code PageQuery}. */
public record VehicleFilter(
        String code, String licensePlate, UUID carrierId, UUID vehicleTypeId,
        VehicleAvailabilityStatus availabilityStatus, Boolean active) {
}
