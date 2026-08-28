# TMS OVERNIGHT MASTER LOG

Enterprise TMS Evolution - OTM-inspired, EBIM architecture.
Chain started **2026-08-28** on branch `dev` from commit `0757afb`.

This file is the resume point. If the run is interrupted, read `LAST_COMPLETED_JOB` below, verify
that its RESULT still matches the working tree, and continue from the next pending job.

## Status board

| Job | Title | Result | Finished | Commit | Migration | Backend tests | STOP_CHAIN |
|---|---|---|---|---|---|---|---|
| 01 | Truth Baseline + Documentation | **PASS** | 2026-08-28 01:10 | `f666d63` | none (V35 is head; V36 next) | 1312 / 0 fail | false |
| 02 | Order Lifecycle V2 | **PASS** | 2026-08-28 01:40 | `32dcc65` | **V36** | 1389 / 0 fail | false |
| 03 | Ship Units + Partial Allocation | **PASS** | 2026-08-28 01:58 | `91540cb` | **V37** | 1409 / 0 fail | false |
| 04 | Routing Matrix + Travel Time | **PASS** | 2026-08-28 02:30 | `29b484c` | **V38** | 1466 / 0 fail | false |
| 05 | Advanced Bulk Planning Engine V2 | **PASS** | 2026-08-28 02:50 | `586e7ed` | none (V38 head) | 1498 / 0 fail | false |
| 06 | Rate Engine V2 | **PASS** | 2026-08-28 03:20 | `38172c3` | **V39** | 1517 / 0 fail | false |
| 07 | Carrier Selection + Tender Waterfall | pending | - | - | - | - | - |
| 08 | Dock / Appointment Scheduling | pending | - | - | - | - | - |
| 09 | Fleet Resource Scheduling | pending | - | - | - | - | - |
| 10 | ETA + Geofencing + Predictive Tracking | pending | - | - | - | - | - |
| 11 | Freight Audit & Settlement | pending | - | - | - | - | - |
| 12 | Exception Management + Control Tower V2 | pending | - | - | - | - | - |
| 13 | Enterprise Integration Operations | pending | - | - | - | - | - |
| 14 | Enterprise UX + Frontend Testing | pending | - | - | - | - | - |
| 15 | Observability + Performance + Security | pending | - | - | - | - | - |
| 16 | Final Enterprise Certification | pending | - | - | - | - | - |

**LAST_COMPLETED_JOB = 06**

## Baseline established by JOB 01

Every gate measured, all green. Later red is therefore attributable to the job that caused it.

    Backend      ./mvnw -B test          1312 tests, 0 failures, 0 errors    BUILD SUCCESS
    Typecheck    npm run typecheck       clean
    Lint         npm run lint            0 errors, 17 pre-existing warnings  exit 0
    Frontend     npm test                37 tests, 4 files, 0 failures
    Build        npm run build           1.11 MB bundle, chunk-size advisory only
    E2E          npx playwright test     33 passed, 7 skipped (auth smoke, no credentials)
    Flyway       V1 - V35 contiguous     next available: V36 (JOB 02 used it; V37 is next)

Docker Desktop was started locally, so the 32 Testcontainers classes ran for real. No remote
environment was contacted at any point.

## Job notes

### JOB 01 - 2026-08-28 - PASS

Baseline reconstructed from code. No production code changed; documentation only.

**Key findings.** The governing `CLAUDE.md` instructed Bootstrap + SweetAlert2 and "avoid MUI" while
the product is built entirely on MUI - the highest-severity drift found, because it would have
misdirected every later frontend job. Resolved by **ADR-008** and corrections to the five
authoritative documents; historical step reports left intact as dated records.

**Discovered already built** (later jobs must extend, not rebuild): the `PlanningEngine` port with
`HEURISTIC_V1` already named; `trip_order_assignment.whole_order` with a partial unique index V11
wrote specifically to admit split allocation later; `OrderDelivery` / `DeliveryResult` with POD
evidence; explicit transition tables on trip, tender and stop status; the idempotent integration
inbox with scopes.

**Genuinely missing** (not stubs): appointments, ship units, fleet availability, carrier invoices
and settlement, geofences, a routing/distance abstraction, generic operational exceptions,
Micrometer metrics.

Published `docs/architecture/TMS_CURRENT_CAPABILITY_MAP.md` - 25 rows, each partial or missing
capability carrying the job that closes it.

### JOB 02 - 2026-08-28 - PASS

The order lifecycle now reaches the end of the road. **V36** adds `IN_EXECUTION`, `DELIVERED`,
`PARTIALLY_DELIVERED` and `DELIVERY_FAILED`, plus `POST /orders/{id}/reopen` for a second attempt.

**The defect this closed.** An order whose delivery was refused stayed `PLANNED` forever: not
plannable, not cancellable, not deliverable. A customer waiting for a redelivery was invisible to
the system that owed them.

**The design tension.** `OrderFulfillmentStatus` carried a reasoned objection to storing delivery
outcomes on the order. It was upheld rather than overruled: the new states are the lifecycle
*consequence* of the delivery fact, not a copy of it, and drift is prevented structurally - the
status is recomputed from the delivery rows in the same transaction as every change to them,
including corrections keyed after the trip closed. `OrderFulfillmentStatus` is unchanged.
Recorded in **ADR-009**; the lifecycle is documented in `docs/domain/ORDER_LIFECYCLE_V2.md`.

**Caught in passing.** `OrderBacklogTotals` would have silently dropped departed orders out of the
KPI planned-rate - a reporting regression no existing test would have flagged, because the figure
would still have been a number. It now derives `planned()` from leaf counters so the identity holds
by construction.

**Tests:** 1389 backend (+77), 42 frontend (+5), 33 E2E. New: `OrderStatusTest` (40 pure-domain),
`OrderPlanningServiceExecutionTest` (idempotency, replay safety, the row lock),
`OrderExecutionPropagatorTest`, and six smoke steps driving the vertical over HTTP from dispatch
through close-out, correction and reopen.

### JOB 03 - 2026-08-28 - PASS

One order can now be split across several trips without being duplicated. **V37** adds the
allocation ledger's ceiling.

**What was already there.** V11 had written the ledger and said so in its header: the assignment
row carries *allocated* amounts, capacity already sums that table, and the whole-order unique index
was deliberately partial so a split would fall outside it. The job was to give it a ceiling, not to
build it.

**The design call.** `allocated <= ordered` had to survive two planners racing, and a CHECK cannot
sum a ledger - so the running total lives on the order row. That looks like the thing ADR-009 argued
against a job earlier; it is not. ADR-009 rejected storing a derived figure *for convenience*. Here
storing it is the only way the rule becomes a database guarantee instead of a hope.

**No ship_unit table**, and that is deliberate: a ship unit here is a portion of demand in the three
measures a vehicle is constrained by. Order lines carry their own `uom`, so a fourth "quantity"
measure would be one nothing else in the product uses. Documented in
`docs/domain/SHIP_UNITS_AND_ALLOCATION_V1.md`.

**Proved over HTTP**: 70 pallets on one truck, 30 on another, one order row, two assignment rows,
the stored total agreeing with the ledger recomputed from those rows - and two concurrent
70-of-100 splits where exactly one wins.

**Known sharp edge**, stated rather than hidden: deliveries record an outcome, not an amount, so an
order reopened after a *partial* delivery is replanned in full.

**Tests:** 1409 backend (+20), 47 frontend (+5), 33 E2E.

### JOB 04 - 2026-08-28 - PASS

STARTED_AT=02:06 · COMPLETED_AT=02:30 · HEAD_BEFORE=`2f7dca6` · HEAD_AFTER=`29b484c` · MIGRATION=**V38**
BACKEND_PASS=1466 · BACKEND_FAIL=0 (clean build) · FRONTEND_PASS=55 · E2E_PASS=33 · RETRIES=2, both recovered

Distance and travel time now live behind `RoutingPort` with a company-scoped cache
(`tms.travel_estimate`), a `RoutingProviderAdapter` seam and a local geodesic estimator that is the
whole of routing when no vendor is configured. **ADR-010** records the reasoning, which follows
ADR-007's for tracking.

**Not scaffolding.** `TripRoutingService` is a real consumer: a trip reports how far it drives and
how long that takes, on the API and on the workspace, and names the legs it could not measure.

**The defect worth remembering.** `RoutingSource` originally had a `CACHE` value, so serving a
cached row overwrote its source - silently turning a straight-line estimate into something
indistinguishable from a measured road the moment it was stored. The smoke run caught it on the
second read of the same trip. Fixed at the design level: *how a number was produced* and *where the
read came from* are now two independent fields. A per-km charge computed from a straight line stays
visibly so.

**Also caught:** `ck_travel_estimate_expiry_after_calculation` refused a test that was manufacturing
a row born already expired. The constraint was right; the test now ages rows honestly.

**Delivery quantity** (JOB 03's known limitation) is unchanged and was not needed here. Nothing in
JOB 04 inferred a quantity.

### JOB 05 - 2026-08-28 - PASS

STARTED_AT=02:31 · COMPLETED_AT=02:50 · HEAD_BEFORE=`714d16c` · HEAD_AFTER=`586e7ed` · MIGRATION=**none**
BACKEND_PASS=1498 · BACKEND_FAIL=0 (clean) · FRONTEND_PASS=55 · E2E_PASS=33 · RETRIES=3, all recovered

`PLANNING_V2` joins `HEURISTIC_V1` behind the same port. It packs against **pending** amounts (V37),
sequences stops nearest-neighbour on the **travel matrix** (V38), and refuses trips that will not
fit a shift. **Default remains `HEURISTIC_V1`** - opt-in per run, so no existing caller's proposals
change silently.

**The comparison is measured, not asserted.** Six stops fed farthest-first: V1 drives out and back,
V2 drives 85 km straight out with identical loads. No matrix → identical trips. One-hour shift → V2
plans **fewer** orders, correctly, and that case is in the suite on purpose.

**Cost is null and stays null.** Pricing a proposal needs a rating port that takes a proposal, not a
persisted shipment - JOB 06. Documented, and said on screen, rather than filled with a number two
engines would be compared on.

**Caught in passing.** My first V2 carried its own copy of `Corridors` that ignored
`route.active()`. Extracted V1's instead: two engines must group identically or the comparison is
meaningless. Also: a javadoc claiming "in registration order" over a `Map.copyOf` - the doc was the
lie and the implementation was fixed to match it.

**Open, and named:** service time per location is carried by the input but passed empty, so
durations are driving-only today. **Delivery quantity** (JOB 03) unchanged and not inferred.

### JOB 06 - 2026-08-28 - PASS

STARTED_AT=02:51 · COMPLETED_AT=03:20 · HEAD_BEFORE=`3ec0d67` · HEAD_AFTER=`38172c3` · MIGRATION=**V39**
BACKEND_PASS=1517 · BACKEND_FAIL=0 (clean) · FRONTEND_PASS=55 · E2E_PASS=33 · RETRIES=6, all recovered

Six charges added (stop-off, fuel, waiting, toll, accessorial, ceiling) plus **LANE** scope. **The
order of application is the contract**: fuel is a percentage of the linehaul and of nothing after
it, and the limits come after the accessorials. On a 175 linehaul with a 50 toll at 12%, the wrong
reading is off by 6.00 on every shipment - which is why it is asserted rather than assumed.

**The first drop is free**: it is already inside the base. **A multi-drop shipment is on no lane** -
the matcher refuses a null destination rather than matching by coincidence.

`DISTANCE` now prefers the shipment's **measured route** (JOB 04), so a trip with no master route
can be priced per kilometre at all.

**The retry worth remembering (#4).** I changed `DISTANCE`'s quantity-source constant to
`MEASURED_ROUTE` before wiring any measured distance, so every line would have *claimed* it while
the number still came from the route master. A pre-existing test caught it. Provenance is now a fact
about the estimate, not a constant on the component.

**Also caught:** I had made a lone minimum count as "a charge", which V30 deliberately refuses - a
floor is a rule about other charges. Reverted in entity and migration.

**Open:** proposal pricing still unwired, so JOB 05's `totalCost` stays null; the pieces exist but a
number without tests would be the fabrication JOB 05 refused. **Waiting time** is never populated -
honest, not broken. **Delivery quantity** (JOB 03) unchanged and not inferred.
