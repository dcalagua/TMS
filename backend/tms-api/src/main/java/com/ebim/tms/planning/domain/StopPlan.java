package com.ebim.tms.planning.domain;

import java.time.LocalTime;
import java.util.UUID;

/**
 * What one destination of a trip needs to look like after the trip's assignments changed: the
 * destination and the service window envelope its currently assigned orders imply.
 *
 * <p>The window is an <em>envelope</em> (earliest requested start, latest requested end), not an
 * intersection: V1 has no routing or time-feasibility solver, so the honest statement a stop can
 * make is "the requests at this destination span this range", not "here is a feasible slot".
 * Null when no order assigned to that destination declares a window. See
 * {@code docs/domain/PLANNING_MANUAL_V1.md}, "Stops follow assignments".
 */
public record StopPlan(UUID destinationId, LocalTime serviceWindowStart, LocalTime serviceWindowEnd) {
}
