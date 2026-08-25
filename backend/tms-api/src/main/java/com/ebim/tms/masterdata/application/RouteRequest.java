package com.ebim.tms.masterdata.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Create and update share one shape; see {@link OriginRequest} for the reasoning behind the
 * lower-case-accepting code pattern and the company/id coming from context rather than the body.
 *
 * <p>{@code stops} is the whole ordered stop list, not a delta: its array order <em>is</em> the
 * sequence, assigned server-side starting at 1 - see
 * {@code com.ebim.tms.masterdata.domain.Route#replaceStops}. There is no client-supplied sequence
 * number, so a request can never ask for a gap or a duplicate position. {@code RouteService}
 * separately rejects a repeated destination id, which bean validation alone cannot express.
 *
 * <p>Until V24 this field was a plain {@code destinationIds} array of ids. It became a list of
 * objects when a stop gained something to say beyond which place it is (its service-time
 * override), and it was replaced rather than paralleled by a second array: two positionally
 * aligned lists are a bug waiting for the day one of them is filtered. Routes are not part of the
 * inbound integration contract ({@code docs/integrations/INBOUND_API_V1.md}), so the only client
 * of this shape is this repository's own frontend.
 */
public record RouteRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$",
                message = "must be 1-32 characters: letters, digits, underscore or hyphen") String code,
        @NotBlank @Size(max = 200) String name,
        @NotNull UUID originId,
        UUID zoneId,
        UUID frequencyId,
        @DecimalMin(value = "0", message = "must be zero or greater") BigDecimal referenceDistanceKm,
        @Min(value = 0, message = "must be zero or greater") Integer referenceDurationMinutes,
        @NotEmpty(message = "a route needs at least one destination stop")
                List<@NotNull @Valid RouteStopRequest> stops) {

    /**
     * @param serviceTimeOverrideMinutes optional; omit it - the ordinary case - and the
     *     destination location's own service time applies. Zero is a real value (a drop-and-go
     *     stop), not a synonym for omitting the field.
     */
    public record RouteStopRequest(
            @NotNull UUID destinationId,
            @Min(value = 0, message = "must be zero or greater") Integer serviceTimeOverrideMinutes) {
    }
}
