package com.ebim.tms.planning.application;

import com.ebim.tms.shared.reference.RouteTemplate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which master route serves each destination, and where along it each one sits.
 *
 * <p>Extracted from {@code HeuristicPlanningEngine} when {@link PlanningEngineV2} arrived (JOB 05).
 * Both engines group orders by corridor and both order them along it, and the grouping is the one
 * thing that must be <em>identical</em> between them: comparing two engines is only meaningful if
 * they were given the same corridors, and a second copy of this logic is exactly how they would
 * quietly stop being.
 *
 * <p><b>Inactive routes and empty ones are skipped.</b> A deactivated corridor is not a corridor,
 * and an order whose only route is inactive belongs to the off-corridor group rather than to a
 * route nobody drives. That rule lived here from V1 and is the reason this was extracted rather
 * than re-derived.
 *
 * <p>A destination served by several routes belongs to the first one by code. Arbitrary, but
 * <em>stably</em> arbitrary, and the alternative - splitting one destination's orders across
 * corridors - is worse for the dispatcher who has to drive the result.
 */
record Corridors(List<UUID> routeIdsInOrder, Map<UUID, UUID> routeByDestination,
        Map<UUID, Map<UUID, Integer>> positionByRoute) {

    static Corridors of(List<RouteTemplate> routes) {
        List<UUID> ids = new ArrayList<>();
        Map<UUID, UUID> routeByDestination = new HashMap<>();
        Map<UUID, Map<UUID, Integer>> positions = new HashMap<>();

        for (RouteTemplate route : routes) {
            if (!route.active() || route.destinationIds().isEmpty()) {
                continue;
            }
            ids.add(route.id());
            Map<UUID, Integer> position = new HashMap<>();
            List<UUID> destinations = route.destinationIds();
            for (int index = 0; index < destinations.size(); index++) {
                position.putIfAbsent(destinations.get(index), index);
                routeByDestination.putIfAbsent(destinations.get(index), route.id());
            }
            positions.put(route.id(), position);
        }
        return new Corridors(List.copyOf(ids), Map.copyOf(routeByDestination), Map.copyOf(positions));
    }

    UUID routeFor(UUID destinationId) {
        return routeByDestination.get(destinationId);
    }

    /** {@link Integer#MAX_VALUE} for a stop the route does not contain, so it sorts last. */
    int positionOf(UUID routeId, UUID destinationId) {
        if (routeId == null) {
            return Integer.MAX_VALUE;
        }
        return positionByRoute.getOrDefault(routeId, Map.of())
                .getOrDefault(destinationId, Integer.MAX_VALUE);
    }
}
