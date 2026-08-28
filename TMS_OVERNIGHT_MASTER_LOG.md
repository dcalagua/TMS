# TMS OVERNIGHT MASTER LOG

Enterprise TMS Evolution - OTM-inspired, EBIM architecture.
Chain started **2026-08-28** on branch `dev` from commit `0757afb`.

This file is the resume point. If the run is interrupted, read `LAST_COMPLETED_JOB` below, verify
that its RESULT still matches the working tree, and continue from the next pending job.

## Status board

| Job | Title | Result | Finished | Commit | Migration | Backend tests | STOP_CHAIN |
|---|---|---|---|---|---|---|---|
| 01 | Truth Baseline + Documentation | **PASS** | 2026-08-28 01:10 | `f666d63` | none (V35 is head; V36 next) | 1312 / 0 fail | false |
| 02 | Order Lifecycle V2 | pending | - | - | - | - | - |
| 03 | Ship Units + Partial Allocation | pending | - | - | - | - | - |
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

**LAST_COMPLETED_JOB = 01**

## Baseline established by JOB 01

Every gate measured, all green. Later red is therefore attributable to the job that caused it.

    Backend      ./mvnw -B test          1312 tests, 0 failures, 0 errors    BUILD SUCCESS
    Typecheck    npm run typecheck       clean
    Lint         npm run lint            0 errors, 17 pre-existing warnings  exit 0
    Frontend     npm test                37 tests, 4 files, 0 failures
    Build        npm run build           1.11 MB bundle, chunk-size advisory only
    E2E          npx playwright test     33 passed, 7 skipped (auth smoke, no credentials)
    Flyway       V1 - V35 contiguous     next available: V36

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
