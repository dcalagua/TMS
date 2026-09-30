package com.ebim.tms.shared.reference;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The one way {@code planning} asks whether a place can actually be served on a date, without
 * depending on {@code com.ebim.tms.masterdata} - the rule {@link OriginLookupPort}'s class comment
 * states for the whole codebase.
 *
 * <p>Why planning asks at all: creating an order is not the moment to enforce a delivery calendar.
 * A customer may order on Tuesday for a store that is served on Mondays, Wednesdays and Fridays,
 * and refusing the order would be refusing the business. What must not happen is that order
 * silently landing on a Tuesday truck. So the calendar is a <em>planning</em> filter, and this port
 * is where the two modules meet ({@code docs/domain/ORDERS.md}, "Calendar is a planning rule").
 *
 * <p>Batched on purpose. A planning run for a distribution centre asks about every distinct
 * destination in the day's backlog at once; one call per order would be an N+1 across a module
 * boundary, which is the hardest kind to notice later.
 */
public interface ServiceCalendarPort {

    /**
     * Of {@code locationIds}, the ones this company may serve on {@code date} according to their
     * service-calendar associations.
     *
     * <p>A location with <em>no</em> calendar at all is returned as serviceable. That is the
     * deliberate reading: an operator who has not configured a calendar has not said "never", and
     * treating silence as a refusal would make automatic planning useless on the day it is turned
     * on. A location that has a calendar and is not covered by it is excluded.
     */
    Set<UUID> serviceableOn(Set<UUID> locationIds, LocalDate date, UUID companyId);

    /**
     * The detail behind {@link #serviceableOn}, for the release read model (ADR-014): for each
     * location, whether its calendar is configured, whether it serves {@code date}, and the cutoff
     * and lead time of the frequency that does. A location with no calendar answers
     * {@link CalendarVerdict#NOT_CONFIGURED}; an inactive one does not run. Every id asked about is
     * answered.
     */
    Map<UUID, CalendarVerdict> locationCalendarsOn(Set<UUID> locationIds, LocalDate date, UUID companyId);

    /**
     * The same question for bare frequencies - a route's own operating calendar
     * ({@code route.frequency_id}, V8). A frequency that does not resolve in this company answers
     * {@link CalendarVerdict#NOT_CONFIGURED}.
     */
    Map<UUID, CalendarVerdict> frequencyCalendarsOn(Set<UUID> frequencyIds, LocalDate date, UUID companyId);

    /**
     * Of {@code destinationIds}, the ones whose route from {@code originId} resolves
     * ({@code RouteResolution.Status.RESOLVED}) to a route with a frequency that does <b>not</b> run
     * on {@code date} - what automatic planning excludes beside {@link #serviceableOn} (ADR-014
     * section 5, approved 2026-09-27). Empty when nothing is excluded: a destination with no route,
     * an ambiguous one, or a route without a frequency is never excluded here.
     */
    Set<UUID> idleByRouteOn(UUID originId, Set<UUID> destinationIds, LocalDate date, UUID companyId);
}
