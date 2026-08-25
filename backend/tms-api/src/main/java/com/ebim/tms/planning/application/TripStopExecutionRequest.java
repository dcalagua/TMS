package com.ebim.tms.planning.application;

import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * The body of a stop transition that needs no reason: arrived, service started, completed
 * (migration V27).
 *
 * <p><b>No {@code version}</b>, unlike {@link TripExecutionRequest}. The guard here is
 * {@code StopExecutionStatus}'s transition table applied under the trip's row lock, which is the
 * same relationship the assignment endpoints have with the assignment uniqueness invariant
 * ({@code TripController}'s class comment states the rule). Requiring one would also be actively
 * unhelpful: every stop action bumps the trip's version, so a dispatcher working seven stops would
 * have to reload between each - and what a stale version would protect against here is a
 * lost update that the transition table already makes impossible.
 *
 * @param occurredAt when it happened - the real arrival, not the moment the button was pressed.
 *     Optional: omitted means "now". {@code TripStopExecutionService} refuses a future time and
 *     one that would put the stop, or the trip, out of order
 * @param notes optional free text about the stop ("gate closed, waited 20 minutes"). Replaces what
 *     is on the stop and is kept in full on the timeline entry - see {@code TripStop.recordOutcome}
 */
public record TripStopExecutionRequest(
        OffsetDateTime occurredAt,
        @Size(max = 500, message = "must be at most 500 characters") String notes) {
}
