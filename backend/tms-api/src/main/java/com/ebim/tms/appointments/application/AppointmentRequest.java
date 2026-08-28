package com.ebim.tms.appointments.application;

import com.ebim.tms.appointments.domain.AppointmentPurpose;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A request to hold a door for a window (migration V41).
 *
 * <p>{@code windowEnd} is optional: omitting it means the door's own {@code defaultSlotMinutes},
 * which is what a dispatcher booking an ordinary drop actually wants and what stops every booking
 * being an arithmetic exercise. Stating it is for the loads that are not ordinary.
 *
 * <p>The instants are absolute and the client sends them with an offset. The site's time zone is
 * how the window is <em>displayed</em> and how the door's local opening hours are read - see
 * {@code Appointment}.
 */
public record AppointmentRequest(
        @NotNull(message = "is required") UUID resourceId,
        @NotNull(message = "is required") AppointmentPurpose purpose,
        @NotNull(message = "is required") OffsetDateTime windowStart,
        OffsetDateTime windowEnd,
        /** Optional: a slot may be booked before the shipment that will use it exists. */
        UUID tripId,
        UUID tripStopId,
        @Size(max = 120, message = "must be at most 120 characters") String reference,
        @Size(max = 500, message = "must be at most 500 characters") String notes) {
}
