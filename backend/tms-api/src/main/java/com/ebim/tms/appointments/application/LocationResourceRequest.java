package com.ebim.tms.appointments.application;

import com.ebim.tms.appointments.domain.ResourceType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** A dock, door, bay or yard slot at a site (migration V41). */
public record LocationResourceRequest(
        @NotNull(message = "is required") UUID locationId,
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$",
                message = "must be 1-32 characters: letters, digits, underscore or hyphen") String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull(message = "is required") ResourceType resourceType,
        /** How long a booking here lasts when nobody says otherwise. */
        @Min(value = 5, message = "must be at least 5 minutes")
        @Max(value = 1440, message = "must be at most 24 hours") int defaultSlotMinutes) {
}
