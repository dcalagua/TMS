package com.ebim.tms.shared.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which master route carries an order from its origin to its destination (ADR-014 section 6).
 *
 * <p>Four answers, and the difference between them is the whole point:
 *
 * <ul>
 *   <li>{@link Status#RESOLVED} - exactly one active route leaving the origin has the destination
 *       as a stop;</li>
 *   <li>{@link Status#NOT_FOUND} - the origin has active routes and none of them stops there;</li>
 *   <li>{@link Status#AMBIGUOUS} - several do, and <b>none is picked</b>: choosing "the first by
 *       code" is silently wrong for exactly the orders a planner most needs to look at;</li>
 *   <li>{@link Status#NOT_CONFIGURED} - the origin has no active route at all. The company does
 *       not use the route master there, so nothing is blocked (ADR-014 section 10, question 1).</li>
 * </ul>
 *
 * <p>A candidate is what {@code Corridors} has always counted as a corridor: an active route with
 * at least one stop ({@link RouteTemplate#servesAsCorridor()}). The resolution is never persisted
 * on the order - a route edited in the master data is picked up at the next read.
 *
 * @param candidates every compatible route: one for {@code RESOLVED}, several for {@code AMBIGUOUS},
 *                   none otherwise
 */
public record RouteResolution(Status status, List<ResolvedRoute> candidates) {

    public enum Status {
        RESOLVED,
        NOT_FOUND,
        AMBIGUOUS,
        NOT_CONFIGURED
    }

    /**
     * One compatible route.
     *
     * @param frequencyId the route's own operating calendar ({@code route.frequency_id}, V8), or
     *                    null when the route has none
     * @param position    where the destination sits on the route, 0-based
     */
    public record ResolvedRoute(UUID routeId, String code, String name, UUID frequencyId, int position) {
    }

    public RouteResolution {
        candidates = List.copyOf(candidates);
    }

    public static final RouteResolution NOT_CONFIGURED = new RouteResolution(Status.NOT_CONFIGURED, List.of());

    /**
     * The one rule, as a pure function.
     *
     * @param routesFromOrigin   the company's routes leaving the order's origin, in code order; inactive
     *                           and empty ones are ignored here rather than trusted to be filtered
     * @param frequencyByRouteId each route's frequency, where it has one
     */
    public static RouteResolution resolve(List<RouteTemplate> routesFromOrigin, Map<UUID, UUID> frequencyByRouteId,
            UUID destinationId) {
        boolean anyCorridor = false;
        List<ResolvedRoute> compatible = new ArrayList<>();
        for (RouteTemplate route : routesFromOrigin) {
            if (!route.servesAsCorridor()) {
                continue;
            }
            anyCorridor = true;
            int position = route.destinationIds().indexOf(destinationId);
            if (position >= 0) {
                compatible.add(new ResolvedRoute(route.id(), route.code(), route.name(),
                        frequencyByRouteId.get(route.id()), position));
            }
        }
        if (!anyCorridor) {
            return NOT_CONFIGURED;
        }
        if (compatible.isEmpty()) {
            return new RouteResolution(Status.NOT_FOUND, List.of());
        }
        return new RouteResolution(compatible.size() == 1 ? Status.RESOLVED : Status.AMBIGUOUS, compatible);
    }

    /** The route, only when exactly one resolved. */
    public Optional<ResolvedRoute> route() {
        return status == Status.RESOLVED ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    /** The route code a board shows: the resolved one, or null. */
    public String routeCode() {
        return route().map(ResolvedRoute::code).orElse(null);
    }
}
