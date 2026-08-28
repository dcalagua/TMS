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
| 04 | Routing Matrix + Travel Time | pending | - | - | - | - | - |
| 05 | Advanced Bulk Planning Engine V2 | pending | - | - | - | - | - |
| 06 | Rate Engine V2 | pending | - | - | - | - | - |
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

**LAST_COMPLETED_JOB = 03**

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
