package com.ebim.tms.planning.application;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/**
 * An explicit stop ordering for a trip: the destinations, in the sequence the planner wants them
 * visited. Must be exactly the destinations the trip currently serves - stops exist to order what
 * is assigned, not to invent visits, so adding one here is refused with 400 rather than silently
 * creating a stop nothing is delivered at.
 *
 * <p>Sequence numbers are server-assigned from this list's order (1..N, contiguous), the same
 * convention {@code RouteService.replaceStops} uses - a client never sends a position number.
 */
public record TripStopOrderRequest(
        @NotEmpty(message = "must contain at least one destination") List<UUID> destinationIds) {
}
