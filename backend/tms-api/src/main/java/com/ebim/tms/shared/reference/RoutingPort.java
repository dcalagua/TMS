package com.ebim.tms.shared.reference;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * How far apart two points are and how long the drive takes, for everything that needs to know
 * (migration V38).
 *
 * <p><b>Why this exists before there is a vendor behind it.</b> Until now the only distance in the
 * product was {@code route.reference_distance_km}, a number somebody typed onto a master route. It
 * cannot answer "how far is this order's destination from that one", which is the question planning
 * scoring, per-km rating, stop sequencing and ETA all reduce to. Putting the abstraction in first -
 * exactly as {@code PlanningEngine} and {@code TrackingProviderPort} were - is what lets a real
 * routing service arrive later as one more implementation instead of a rewrite of four modules.
 *
 * <p><b>Never throws for a road it cannot measure.</b> A location with no coordinates, a provider
 * that timed out, a provider that is not configured at all: every one of those returns either an
 * estimate marked {@link RoutingSource#FALLBACK} or an empty {@link Optional}, and never an
 * exception. Routing informs decisions; it does not get to stop a planner from making one. That is
 * the same rule ADR-007 applies to positions.
 *
 * <p><b>Company-scoped, and deliberately so.</b> The distance between two points is a fact about
 * the world rather than about a tenant, but the coordinates that identify them are a tenant's
 * master data. Scoping the cache means a company's locations never appear in a row another company
 * can read, at the cost of computing a shared road twice. See migration V38's header.
 */
public interface RoutingPort {

    /**
     * One leg. Empty when either endpoint has no coordinates - which is master data being
     * incomplete, not a failure, and the caller's job is to carry on saying "unknown".
     */
    Optional<TravelEstimate> estimate(UUID companyId, GeoPoint origin, GeoPoint destination);

    /**
     * Every origin against every destination, in one pass.
     *
     * <p>Batched because the callers that need a matrix need a whole one: an engine scoring
     * fifteen candidate stop orders asks for 15x15 and would otherwise issue 225 round trips
     * through the cache. Duplicate pairs are collapsed, so an N x N over a list containing the same
     * point twice costs what the distinct pairs cost.
     *
     * <p>A pair whose endpoints cannot be measured is simply absent from the result rather than
     * present with a null - the caller reads it with {@code getOrDefault} and treats a miss as
     * unknown, which is the same shape {@code OrderFulfillmentPort} uses.
     */
    Map<Leg, TravelEstimate> matrix(UUID companyId, List<GeoPoint> origins, List<GeoPoint> destinations);

    /**
     * An ordered pair of points - the key of a matrix answer.
     *
     * <p>A record rather than a string key so that a caller cannot accidentally look up a leg with
     * the endpoints the wrong way round and get a plausible answer for a different road.
     */
    record Leg(GeoPoint origin, GeoPoint destination) {

        public Leg {
            if (origin == null || destination == null) {
                throw new IllegalArgumentException("a leg needs both endpoints");
            }
        }
    }
}
