package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.UnavailabilityReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * Taking a vehicle or a driver out of service for a window (migration V42).
 *
 * <p>No resource id: which truck or which person is in the path, so a request body cannot disagree
 * with the URL it was posted to.
 */
public record UnavailabilityRequest(
        @NotNull UnavailabilityReason reason,
        @NotNull OffsetDateTime startsAt,
        @NotNull OffsetDateTime endsAt,
        @Size(max = 500) String notes) {
}
