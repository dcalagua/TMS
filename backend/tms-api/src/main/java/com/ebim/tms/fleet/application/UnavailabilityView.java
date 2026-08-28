package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.ResourceUnavailability;
import com.ebim.tms.fleet.domain.UnavailabilityReason;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One window in which a vehicle or a driver cannot work (migration V42). */
public record UnavailabilityView(
        UUID id,
        UUID vehicleId,
        UUID driverId,
        UnavailabilityReason reason,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        String notes) {

    public static UnavailabilityView of(ResourceUnavailability block) {
        return new UnavailabilityView(block.id(), block.vehicleId(), block.driverId(), block.reason(),
                block.startsAt(), block.endsAt(), block.notes());
    }
}
