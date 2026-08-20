package com.ebim.tms.planning.application;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Points a draft shipment at a master route, or clears the pointer.
 *
 * <p>The route is a <em>suggestion</em>: recording it never changes which destinations the
 * shipment serves, because a stop exists only because an order is going there
 * ({@code docs/domain/SHIPMENT_V2.md}, "Route master interaction"). The only effect it may have
 * is on the <em>order</em> of the stops the shipment already has, and only when the caller asks
 * for it - which is what {@code applySequence} is for.
 *
 * @param routeId       the master route, or null to clear the shipment's route reference. When
 *                      present it must be active, in the caller's company, and depart from the
 *                      same origin as the shipment's planning run.
 * @param applySequence when true, the shipment's existing stops are reordered to follow the
 *                      route's own sequence: destinations the route names first, in the route's
 *                      order, then every other stop keeping its current relative position.
 *                      Destinations the route does not name are kept, never dropped. Ignored when
 *                      {@code routeId} is null - there would be no sequence to apply.
 * @param version       the trip's version; a route change is an edit of a field the caller read,
 *                      so it takes one, unlike an assignment ({@code PlanningActionRequest})
 */
public record TripRouteRequest(
        UUID routeId,
        boolean applySequence,
        @NotNull(message = "is required") Long version) {
}
