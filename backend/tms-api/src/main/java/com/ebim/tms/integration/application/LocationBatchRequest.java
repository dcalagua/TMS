package com.ebim.tms.integration.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * A batch of locations.
 *
 * <p>An object wrapper rather than a bare JSON array, so that the payload can grow a sibling field
 * later - a source system name, a snapshot timestamp - without becoming a breaking change for
 * every partner. A top-level array has nowhere to put one.
 *
 * <p>The size bound is not declared here as a {@code @Size} annotation because the limit is
 * configurable ({@code tms.integration.max-batch-size}); it is enforced in
 * {@code IntegrationLocationService} against the resolved value.
 */
public record LocationBatchRequest(
        @NotEmpty(message = "locations must contain at least one item")
        List<@Valid @NotNull LocationUpsertRequest> locations) {

    public LocationBatchRequest {
        locations = locations == null ? List.of() : List.copyOf(locations);
    }
}
