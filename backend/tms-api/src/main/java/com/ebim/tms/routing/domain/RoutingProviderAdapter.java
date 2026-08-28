package com.ebim.tms.routing.domain;

import com.ebim.tms.shared.reference.GeoPoint;
import com.ebim.tms.shared.reference.TravelEstimate;
import java.util.Optional;

/**
 * A thing that can measure a road (migration V38).
 *
 * <p>The seam a real routing service plugs into. {@code LocalGeodesicRoutingProvider} is the only
 * implementation that ships, and it is not a stub: it produces a usable estimate from the
 * coordinates alone and says so through {@link com.ebim.tms.shared.reference.RoutingSource#FALLBACK}.
 *
 * <p><b>No vendor adapter in V1</b>, and that follows ADR-007's precedent for tracking rather than
 * being an oversight. Writing one against a specific mapping service needs a concrete customer
 * requirement, an API key held somewhere real, and a decision about what a per-request cost is
 * worth. What this interface guarantees is that writing it later changes this package and nothing
 * else: {@code RoutingService} already caches, times, counts and falls back around whatever sits
 * here.
 *
 * <p><b>Implementations must not throw.</b> A provider that is unreachable, slow or rate-limited
 * returns {@link Optional#empty()} and lets {@code RoutingService} fall back. Routing informs
 * decisions and never blocks one - the same rule ADR-007 states for positions. An implementation
 * that lets an exception escape is caught and counted anyway, but returning empty is what lets it
 * say "I could not" without the cost of a stack trace on the hot path.
 */
public interface RoutingProviderAdapter {

    /**
     * A stable name recorded on every estimate this produces, so a figure can be traced back to
     * what made it after the configuration has changed.
     */
    String name();

    /**
     * Whether this adapter can be asked right now - configuration present, circuit closed.
     * Checked before {@link #estimate} so an unconfigured provider costs nothing per call.
     */
    boolean isAvailable();

    /** The leg, or empty if this adapter could not produce one. Never throws for a routing failure. */
    Optional<TravelEstimate> estimate(GeoPoint origin, GeoPoint destination);
}
