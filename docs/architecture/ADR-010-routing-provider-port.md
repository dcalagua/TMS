# ADR-010 - Routing is a port with a working local implementation

**Status:** Accepted - 2026-08-28
**Migration:** V38
**Follows:** ADR-007 (tracking provider port), whose reasoning this reuses deliberately.

## Context

The only distance in TMS was `route.reference_distance_km` (V8): a number typed onto a master
route. It answers "roughly how long is this corridor" and nothing else.

Four things are about to need more than that, and none of them can be built on a master-data column:

- **Planning V2** scoring a proposal on kilometres and duration needs the distance between *these
  two stops*, which no master route knows.
- **Per-km rating** (V30's `RateComponent.DISTANCE`) multiplies a distance to produce **money**, and
  today that distance may be absent, stale, or about a different corridor.
- **Stop sequencing** needs an N x N matrix over a shipment's own destinations.
- **ETA** needs the drive from where a vehicle is *now*, which is not master data at all.

## Decision

**A `RoutingPort` in `shared.reference`, a `routing` module that implements it with a cache, and a
`RoutingProviderAdapter` seam with exactly one shipped implementation: a local geodesic estimator.**

### No vendor adapter, and that is the same call ADR-007 made

Writing one against a specific mapping service needs a concrete customer requirement, an API key
held somewhere real, and a decision about what a per-request cost is worth. None of those exist.
What the boundary buys is that writing it later changes one package: caching, timing, counting,
timeout handling and fallback already happen *around* whatever sits behind the adapter.

### The local estimator is not a stub

Great-circle distance × 1.30 road factor, divided by a speed chosen from two bands (25 kph under
15 km, 60 kph above). With no vendor configured it is the whole of routing, and it works. **Two
bands rather than one** because a 3 km city delivery and a 300 km line-haul do not average the same
speed, and assuming they do makes every urban round trip look about an hour shorter than it is -
which would then be planned as if it fitted in a shift.

### Routing never fails a decision

A provider that times out, a location with no coordinates, no provider at all: each yields either an
estimate that admits it is an estimate or an empty `Optional`, never an exception. Distances inform
planning, pricing and ETAs; they do not get to stop a planner from planning. ADR-007's rule for
positions, applied to distances.

### Haversine in Java, not PostGIS

`tms.location` already carries a generated `geography(Point)`, and `ST_Distance` would be about 0.3%
more accurate - a spheroid against a sphere. That difference is noise beside the 30% road factor,
which is the dominant error by two orders of magnitude. Paying a database round trip per leg for
0.3% on top of a 30% approximation buys nothing, and it would cost the property that makes the
calculation reproducible and unit-testable without a database. **PostGIS earns its place where the
question is genuinely spatial** - containment, for the geofences of JOB 10 - and adding a geometry
column here to look modern would cost an index nothing reads.

### The cache is company-scoped

The distance between two points is a fact about the world, not about a tenant, so a global cache
would be defensible on the merits and would hit more often. It is scoped anyway: the coordinates are
a tenant's master data, and a shared table keyed on them would let one company's warehouse
coordinates be inferred from another's cache reads. The cost is computing a shared road once per
company; the alternative is explaining a cross-tenant inference channel that exists to save
arithmetic.

### The invariant that a `CHECK` cannot express, expressed in the database anyway

`uq_travel_estimate_leg` is on **database-generated** grid columns (`round(x, 4)`, about 11 m), not
on values the application rounds. Two application instances cannot round differently and each keep a
copy of the same leg, and a lookup cannot disagree with an insert about what "the same point" means -
a defect that would look like a working cache that silently never hits.

## A defect this ADR exists partly to record

The first version of `RoutingSource` had three values: `PROVIDER`, `FALLBACK`, `CACHE`. Serving a
cached row overwrote its source with `CACHE`, which **silently laundered a straight-line estimate
into something indistinguishable from a measured road** the moment it was stored. The smoke run
caught it on the second HTTP read of the same trip.

The fix is the design: *how a number was produced* (`source`) and *where this read came from*
(`servedFromCache`) are two independent facts and are now two fields. `TravelEstimateRow.toEstimate`
preserves the stored source. A per-km charge computed from a straight line stays visibly computed
from a straight line for as long as it exists.

## Consequences

- Every estimate carries `provider`, `source` and `servedFromCache`, and all three travel into
  whatever consumed them. "How much of tonight's plan rests on straight lines" is countable.
- Metrics: `tms.routing.lookups` (hit / miss / expired / fallback / unknown / same-point / raced),
  `tms.routing.provider.calls` (ok / empty / error, tagged by provider), plus timers for provider
  calls and whole matrices.
- `TripRoutingService` is the first consumer: a trip's planned distance and drive time, on the trip
  detail view and the workspace. Planning V2, rating, sequencing and ETA attach to the same port.
- The cache is a cache: rows are disposable, `tms_app` may `DELETE`, and `evictExpired` is exposed
  for a deployment-owned sweep rather than run on the read path.

## Deliberately not decided here

- **No route polyline.** Drawing the road on a map would multiply the table by three orders of
  magnitude and nothing asks for it.
- **No traffic or time-of-day model.** Without a provider behind it that would be inventing
  precision.
- **No return leg.** Whether a vehicle goes back to base is a fleet policy this product does not
  model; adding one nobody asked for would inflate every trip figure by roughly half.
