package com.ebim.tms.integration.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * A run of reported positions from one feed.
 *
 * <p>{@code provider} sits here rather than on each position deliberately: it identifies the
 * <em>sender</em>, and a payload able to claim two providers in one delivery would be a state
 * nothing downstream can act on. It is also, equally deliberately, taken from the body rather than
 * derived from the credential - one credential legitimately relays several upstream feeds (a 4PL
 * forwarding two carriers' telematics), and forcing a credential per feed would make onboarding a
 * subcontractor a key-management exercise. Nothing rests on it: it is a label on the data, the
 * tenant comes from the credential, and a sender that mislabels its own feed has mislabelled only
 * its own feed.
 *
 * @param positions never empty. The batch of one is the normal shape for a device reporting live -
 *     see {@code IntegrationTrackingController} for why there is no single-position endpoint
 */
public record TrackingBatchRequest(

        @NotNull(message = "provider is required")
        @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}$",
                message = "provider must be 2 to 64 characters of letters, digits, '.', '_' or '-', "
                        + "starting with a letter or a digit")
        String provider,

        @NotEmpty(message = "positions must contain at least one item")
        List<@Valid @NotNull TrackingPositionReport> positions) {

    public TrackingBatchRequest {
        positions = positions == null ? List.of() : List.copyOf(positions);
    }
}
