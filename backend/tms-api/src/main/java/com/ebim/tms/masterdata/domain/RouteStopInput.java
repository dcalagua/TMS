package com.ebim.tms.masterdata.domain;

import java.util.UUID;

/**
 * One requested stop of a route, as {@link Route#replaceStops} takes them: the destination plus
 * whatever this corridor says about it. The same role {@link FrequencyWeeklyRuleInput} plays for
 * {@link Frequency#replaceWeeklyRules} - a validated, transport-free description of the wanted
 * state, so the entity never sees an API record and the application layer never builds a
 * {@link RouteStop} directly.
 *
 * <p>No {@code sequence}: the position is the caller's list order, assigned 1..N server-side, so
 * a request can never ask for a gap or a duplicate position.
 *
 * @param serviceTimeOverrideMinutes {@code null} to use the destination location's service time,
 *     which is the ordinary case. Zero is a real value (a drop-and-go stop), not a synonym for
 *     null - see {@link RouteStop#effectiveServiceTimeMinutes}.
 */
public record RouteStopInput(UUID destinationId, Integer serviceTimeOverrideMinutes) {
}
