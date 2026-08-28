# TMS OVERNIGHT JOB 04 RESULT

RESULT=PASS
STOP_CHAIN=false

STARTED_AT=2026-08-28 02:06 America/Lima
COMPLETED_AT=2026-08-28 02:30 America/Lima

## OBJECTIVE

Replace `route.reference_distance_km` - a number typed onto a master route - with a reusable
routing abstraction that answers origin, destination, distance, travel duration, provider and
calculatedAt; with a deterministic local fallback, a cache, a configurable external adapter seam,
metrics and error handling. No real service may be called in tests.

## BASELINE

Clean tree at `2f7dca6`. Flyway head V37, so **V38 was the next free number** - checked on the
filesystem, not assumed. Backend 1409 / frontend 47 / E2E 33 from JOB 03.

## IMPLEMENTED

**A new `routing` module** plus the port every consumer reads:

- `shared.reference.GeoPoint` - a validated point. A type rather than two loose `BigDecimal`s
  because a pair of same-typed numbers is the classic argument-order defect, and a swapped
  latitude/longitude produces a coordinate that is valid, silently wrong and usually in the sea.
- `shared.reference.TravelEstimate` / `RoutingSource` / `RoutingPort` - one leg and an N x N
  matrix, both company-scoped, both returning `Optional`/absent rather than throwing.
- `routing.domain.GeodesicDistance` - pure haversine plus a 1.30 road factor. No repository, no
  clock, no random number, so a proposal built on it is reproducible.
- `routing.domain.RoutingProviderAdapter` - the seam a real router attaches to.
- `routing.application.LocalGeodesicRoutingProvider` - **not a stub**: with no vendor configured it
  is the whole of routing. Two speed bands (25 kph under 15 km, 60 kph above) because assuming one
  speed makes every urban round trip look about an hour shorter than it is.
- `routing.application.RoutingService` - the chain (same point -> cache -> provider -> local
  estimate), Micrometer counters and timers, and a broad `catch` around the adapter so a vendor
  SDK's timeout degrades a distance instead of failing a planning run.
- `RoutingProperties` - TTL, speeds, urban threshold, provider timeout, matrix limit, each
  normalised to a working default so a half-written yaml degrades rather than failing to start.

**A real first consumer, not scaffolding**: `TripRoutingService` measures a shipment's run -
origin to first stop, then stop to stop - and `TripDetailView.routing` carries it to the API and
to a `TripRouteCard` on the trip workspace. Until now the product could not answer "how far does
this trip drive" at all.

## MIGRATIONS

**V38__routing_travel_estimate_cache.sql** - `tms.travel_estimate`. No applied migration touched.

- Full-precision coordinates plus **database-generated** 4-decimal grid columns, with
  `uq_travel_estimate_leg` on the grid. Generated rather than computed in Java so two instances
  cannot round differently and each keep a copy of the same leg - a defect that would look like a
  working cache that silently never hits.
- Directional: A->B and B->A are two rows. One-way systems and restricted turns are real, and a
  cache assuming symmetry would invent an answer for a road it was never given.
- `provider` is part of the key, so swapping routers does not serve the old one's numbers.
- `ck_travel_estimate_expiry_after_calculation`, non-negative distance/minutes, bounded
  coordinates, `source IN ('PROVIDER','FALLBACK')`.
- RLS with `p_tenant_company_scope`; `GRANT SELECT, INSERT, UPDATE, DELETE` - UPDATE because an
  expired leg is refreshed in place, DELETE for the retention sweep (V29's reason).

## BACKEND

Files listed under FILES_CHANGED. The module boundary registry and the schema-exposure table
registry were both updated - the latter is what proved the RLS is right rather than merely written.

## FRONTEND

`TripRouteMetrics` / `TripRouteLegView` types, `TripRouteCard` (distance, driving time, per-leg
breakdown, an explicit **Estimado** chip, and a warning when a leg could not be measured), mounted
first in the workspace's right column. ES + EN parity for every new string.

## DATABASE

Flyway contiguous V1-V38. Every Testcontainers class applies the full history to a fresh database,
so "the history applies cleanly to an empty database" is asserted 30+ times per run.

## SECURITY

Company-scoped end to end: `RoutingPort` takes `companyId`, the cache queries carry the predicate
in SQL, and RLS is the backstop. `SchemaExposureIntegrationTest` - which enumerates every
company-scoped table and demands the tenant policy - failed until `travel_estimate` was correctly
policed, then passed.

## TENANT_TESTS

- `RoutingCacheConstraintIntegrationTest.theCacheIsCompanyScoped` - the same leg for two companies
  is two rows.
- `RoutingServiceIntegrationTest.theCacheDoesNotLeakAcrossCompanies` - company B computing the same
  road is **not** served company A's row, proved through the service.
- `RoutingServiceTest.cacheIsCompanyScoped` - the caller's company reaches the query.
- `TripRoutingServiceTest.companyScoped` - planning asks with the trip's own company.

## AUDIT

None, deliberately. A distance lookup is a read, not a business act; auditing one per leg would
write thousands of rows per planning run and bury the trail it exists to make readable - V27's
reasoning for not auditing stop transitions. What a figure was and where it came from is on the
estimate itself and in the cache row.

## OBSERVABILITY

`tms.routing.lookups` tagged hit / miss / expired / fallback / unknown / same-point / raced;
`tms.routing.provider.calls` tagged ok / empty / error plus provider;
`tms.routing.provider.duration` and `tms.routing.matrix.duration` timers. Asserted in tests, not
merely emitted.

## TESTS_FOCUSED

`GeodesicDistanceTest` 10, `RoutingServiceTest` 21, `RoutingServiceIntegrationTest` 8,
`RoutingCacheConstraintIntegrationTest` 9, `TripRoutingServiceTest` 8, smoke step 13b.
Covered: valid coordinates, missing coordinates, same location, provider timeout, cache hit, cache
miss, expired cache, provider unavailable, fallback calculation, N x N matrix, cross-company
protection - every case the brief listed.

## TESTS_CLEAN

`./mvnw -B clean test` - **1466 tests, 0 failures, 0 errors**, BUILD SUCCESS. (+57 over JOB 03.)

## FRONTEND_TESTS

`npm run typecheck` clean; `npm run lint` 0 errors (17 pre-existing warnings); `npm test`
**55 passed** (+8); `npm run build` succeeds.

## E2E

33 passed, 7 skipped (authenticated smoke, no credentials). No regression.

## RETRIES_ATTEMPTED=2
## RETRIES_RECOVERED=2

1. **TYPE C.** `ck_travel_estimate_expiry_after_calculation` refused the integration test's way of
   forcing an expiry. The constraint is right - it stops a row being born expired - and the test was
   manufacturing an impossible state. Fixed by ageing the row honestly (both `calculated_at` and
   `expires_at` move). One cycle.
2. **TYPE C, and the important one.** The smoke run failed on `routing.estimated == false`. Root
   cause: `RoutingSource` had a `CACHE` value, and serving a cached row overwrote its source with
   it - **silently laundering a straight-line estimate into something indistinguishable from a
   measured road the moment it was stored**. Fixed at the design level rather than in the test:
   *how a number was produced* (`source`) and *where this read came from* (`servedFromCache`)
   are now two independent fields, and `TravelEstimateRow.toEstimate` preserves the stored source.
   One cycle. Recorded in ADR-010 because it is the kind of defect that would have reached a
   customer as a promised delivery hour computed from a straight line.

## BLOCKED_GATES

None. Docker was already up (`docker info` checked first, per TYPE E); all Testcontainers classes
ran. No remote environment contacted.

## KNOWN_LIMITATIONS

- **No vendor routing adapter.** By decision (ADR-010), following ADR-007. Every distance in the
  product today is a local estimate and says so through `source = FALLBACK`, which survives
  caching and reaches the UI as an explicit "Estimado" chip.
- **The 1.30 road factor is a planning constant**, not a measurement. A real router replaces the
  calculation rather than tuning it.
- **No return-to-base leg** in a trip's distance: whether a vehicle returns is a fleet policy this
  product does not model.
- **Delivery quantity remains unmodelled** (JOB 03's known limitation, unchanged). Nothing in JOB 04
  needed it and nothing here inferred it. An order reopened after a *partial* delivery is still
  replanned in full.

## FILES_CHANGED

    backend/.../db/migration/V38__routing_travel_estimate_cache.sql        new
    backend/.../shared/reference/{GeoPoint,TravelEstimate,RoutingSource,RoutingPort}.java  new
    backend/.../routing/domain/{GeodesicDistance,RoutingProviderAdapter,TravelEstimateRow}.java  new
    backend/.../routing/application/{RoutingProperties,RoutingConfig,LocalGeodesicRoutingProvider,RoutingService}.java  new
    backend/.../routing/infrastructure/TravelEstimateRepository.java       new
    backend/.../routing/package-info.java                                  new
    backend/.../planning/application/{TripRouteMetrics,TripRoutingService}.java  new
    backend/.../planning/application/{TripDetailView,TripViewAssembler}.java     routing on the detail view
    backend/.../architecture/ModuleBoundaryTest.java                       routing registered
    backend/.../database/SchemaExposureIntegrationTest.java                travel_estimate registered
    frontend/.../pages/trips/TripRouteCard.tsx (+ test)                    new
    frontend/.../pages/trips/TripWorkspacePage.tsx                         card mounted
    frontend/.../shared/api/planningApi.ts                                 routing types
    docs/architecture/ADR-010-routing-provider-port.md                     new

## LOCAL_COMMIT

One local commit. No push.

## NEXT_JOB

**JOB 05 - Advanced Bulk Planning Engine V2.** JOB 04's dependency is satisfied: `RoutingPort` is
functional, cached, metered and already consumed by planning, so V2 can score kilometres and
duration against real figures rather than a master-data column. Next migration: **V39**.
