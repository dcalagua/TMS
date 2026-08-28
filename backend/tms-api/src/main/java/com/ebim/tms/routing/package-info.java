/**
 * How far apart two places are, and how long the drive takes (migration V38).
 *
 * <p>This module exists so that four other things can stop guessing. Until it arrived the only
 * distance in TMS was {@code tms.route.reference_distance_km} - a number typed onto a master route,
 * which answers "roughly how long is this corridor" and nothing else. Planning cannot score a
 * proposal on kilometres with that, a per-km rate multiplies it to produce money, stop sequencing
 * needs an N x N over a shipment's own destinations, and an ETA needs the drive from where a
 * vehicle is now - none of which a master route knows.
 *
 * <h2>The chain</h2>
 *
 * <pre>
 *   RoutingPort.estimate
 *       ──▶ the same point?      0 km, 0 min, nothing stored
 *       ──▶ cache, still fresh?  serve it
 *       ──▶ a provider?          ask, store, serve
 *       ──▶ otherwise            estimate locally, store, serve as FALLBACK
 * </pre>
 *
 * <h2>What this module owns and does not own</h2>
 *
 * <p>It owns the contract, the cache, the retention policy and the local estimator. It owns
 * <b>no vendor code</b>, exactly as {@code tracking} owns none (ADR-007): {@code
 * RoutingProviderAdapter} is where a real router attaches, and writing one needs a concrete
 * customer requirement, a key held somewhere real and a decision about what a per-request cost is
 * worth. What the boundary guarantees is that writing it later changes this package alone -
 * caching, timing, counting and falling back already happen around whatever sits behind it.
 *
 * <h2>The rule every caller can rely on</h2>
 *
 * <p><b>Routing never fails a decision.</b> A provider that times out, a location with no
 * coordinates, no provider configured at all: each produces either an estimate that admits it is an
 * estimate or an empty answer, never an exception. Distances inform planning, pricing and ETAs;
 * they do not get to stop a planner from planning. That is ADR-007's rule for positions, applied to
 * distances, and it is why {@link com.ebim.tms.routing.application.LocalGeodesicRoutingProvider}
 * is not a stub - with no vendor configured it is the whole of routing, and it works.
 *
 * <h2>Why the estimate is honest about being one</h2>
 *
 * <p>Every answer carries a {@code provider} and a {@code source}, and both travel through the
 * cache into whatever consumed them. A kilometre figure that turns into a charge has to be
 * traceable to how it was produced, and "how much of tonight's plan rests on straight lines" has to
 * be a question with a countable answer rather than an assumption.
 */
package com.ebim.tms.routing;
