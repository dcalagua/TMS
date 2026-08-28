# TMS OVERNIGHT JOB 05 RESULT

RESULT=PASS
STOP_CHAIN=false

STARTED_AT=2026-08-28 02:31 America/Lima
COMPLETED_AT=2026-08-28 02:50 America/Lima

## OBJECTIVE

Keep the current engine as `HEURISTIC_V1` and add a V2 behind the same abstraction, using JOB 04's
real routing matrix. Hard constraints, soft objectives, KPIs, a reviewable proposal that never
auto-dispatches, and a reproducible head-to-head comparison of the two engines.

## BASELINE

Clean tree at `714d16c`. Backend 1466, frontend 55, E2E 33. `RoutingPort` from JOB 04 functional,
cached and already consumed - so JOB 05's stated dependency was genuinely satisfied rather than
assumed.

## IMPLEMENTED

- **`PlanningEngineV2`** ("PLANNING_V2"), a second implementation of the existing port. Three
  additions over V1, each of which V1 had no way to make:
  1. packs against `PlannableOrder.pending()` rather than order totals, so a part-allocated order
     (V37) reserves only what it still needs;
  2. sequences a trip's stops **nearest-neighbour from the origin** using the travel matrix;
  3. refuses a trip that cannot be driven inside a `PlanningShift`.
- **`PlanningKpis`** on every proposal: trips, vehicles, planned/unplanned/late orders, total km,
  total minutes, three utilisation percentages, planned rate, km per order, an estimated-distance
  flag, and cost.
- **`PlanningEngines`** registry with per-run selection; **the default remains `HEURISTIC_V1`**.
- **`TravelMatrix`** and **`PlanningShift`** as engine inputs; `AutoPlanningService` resolves the
  whole matrix in **one** routing call per run.
- Two new `UnplannedReason` values: `EXCEEDS_SHIFT` and `FULLY_ALLOCATED`.
- **`Corridors` extracted** from V1 into a shared package-private class. Not tidying: my first V2
  had its own copy that ignored `route.active()`, which would have grouped orders onto corridors
  nobody drives. Two engines must group **identically** or comparing them is meaningless.

## MIGRATIONS

**None.** JOB 05 added no schema, and inventing one to look substantial would be worse than saying
so. Flyway head remains **V38**; **V39** is the next free number.

## BACKEND

Files under FILES_CHANGED. `AutoPlanningService` now takes the registry rather than a single
engine, resolves places and the matrix, and merges calendar exclusions into the KPI block so the
unplanned count is the whole truth about the day rather than only the part the engine touched.

## FRONTEND

`AutoPlanDrawer` gains an engine toggle (previewing under each is how the two are compared on one
day's data), a KPI block, utilisation chips, an "estimated distances" warning, and an explicit
notice that cost is not computed. ES + EN parity.

## DATABASE

Untouched. Flyway contiguous V1-V38.

## SECURITY

No new endpoint and no new authority: `engine` is a parameter on the two existing auto-plan
endpoints, which already require `planning.plan:read`/`:manage`. An unknown engine name is a 400
that lists the real ones, never a 500. Materialising a proposal still goes through
`TripService`, so every capacity, tenancy and double-booking rule applies to an engine's output
exactly as to a planner's click.

## TENANT_TESTS

No new tenant surface was created, so no new negative test was owed. The existing
`AutoPlanningServiceTest` company-scope coverage passes unchanged, and the routing matrix is
resolved with `scope.companyId()` (JOB 04's isolation tests cover the cache itself).

## AUDIT

Unchanged: `AUTO_PLAN` is still recorded when a proposal is written. The engine name already
travelled on the proposal and now reaches the audit trail through it, so "which rules produced this
board" is answerable after those rules change.

## OBSERVABILITY

No new metrics. Planning metrics are named as JOB 15's in the capability map and were left there
rather than half-done here.

## TESTS_FOCUSED

`PlanningEngineV2Test` 22, `PlanningEngineComparisonTest` 5, `PlanningEnginesTest` 5.

The comparison is the one that matters. On a six-stop corridor fed farthest-first: V1 drives out
and back along the line, V2 drives **85 km straight out**, both carrying identical loads. With no
matrix the two produce **identical** trips. And - included deliberately - with a one-hour shift V2
plans **fewer** orders than V1 and is right to, because the rest cannot be driven.

## TESTS_CLEAN

`./mvnw -B clean test` - **1498 tests, 0 failures, 0 errors**, BUILD SUCCESS. (+32.)

## FRONTEND_TESTS

typecheck clean; lint 0 errors (17 pre-existing warnings); `npm test` **55 passed**; build succeeds.

## E2E

33 passed, 7 skipped. No regression.

## RETRIES_ATTEMPTED=3
## RETRIES_RECOVERED=3

1. **TYPE C.** Clean build caught `AutoPlanningService` referencing an `originLookupPort` field
   that did not exist, and a controller signature that no longer matched - both invisible to an
   incremental build. Added the field and updated the controller.
2. **TYPE C.** `PlanningEngineV2Test.splitsRatherThanOverrunning` failed. Root cause was **my
   fixture's arithmetic**, not the engine: I claimed each stop was individually reachable in a
   two-hour shift when the far one alone takes four. Engine unchanged; fixture corrected to a
   four-hour shift where each stop fits alone and the pair does not, which is the case actually
   worth asserting.
3. **TYPE C, and worth recording.** `PlanningEnginesTest.listsWhatExists` failed because
   `Map.copyOf` makes no ordering promise - I built a `LinkedHashMap`, copied it, and then
   advertised "in registration order" in the javadoc. **The doc was the lie, not the test.** Fixed
   by storing the ordered name list, so the class now does what it says.

## BLOCKED_GATES

None. Docker up throughout; every Testcontainers class ran. No remote environment contacted.

## KNOWN_LIMITATIONS

- **Cost is not computed for a proposal** and `totalCost` is null everywhere. Pricing a
  hypothetical trip needs a rating port that takes a proposal rather than a persisted shipment -
  JOB 06. Deliberately left absent rather than filled with a plausible number, because two engines
  would be compared on it. Stated on screen as well as in the doc.
- **No engine-proposed splits.** V37 makes splitting expressible; which part goes on which truck is
  still a planner's decision.
- **Nearest-neighbour, not an optimal tour.** No 2-opt, no solver. This is the honest heuristic.
- **`PlanningShift` is a configurable ceiling, not a driving-hours regulation.** This product holds
  no jurisdiction's rules and does not pretend to.
- **Service time per location is not yet populated** - the input carries the map and
  `AutoPlanningService` passes it empty, so durations are driving-only today. Wiring the location's
  own `service_time_minutes` is a small follow-up and is named here rather than glossed.
- **Delivery quantity remains unmodelled** (JOB 03). Nothing in JOB 05 needed or inferred it.

## FILES_CHANGED

    backend/.../planning/application/PlanningEngineV2.java            new
    backend/.../planning/application/PlanningEngines.java             new
    backend/.../planning/application/PlanningKpis.java                new
    backend/.../planning/application/TravelMatrix.java                new
    backend/.../planning/application/PlanningShift.java               new
    backend/.../planning/application/Corridors.java                   extracted from V1
    backend/.../planning/application/HeuristicPlanningEngine.java     nested Corridors removed
    backend/.../planning/application/PlanningInput.java               matrix, service times, shift
    backend/.../planning/application/PlanningProposal.java            kpis + 2 reasons
    backend/.../planning/application/AutoPlanView.java                kpis
    backend/.../planning/application/AutoPlanningService.java         registry, matrix resolution
    backend/.../planning/application/PlanningActionRequest.java       engine
    backend/.../planning/api/PlanningRunController.java               engine parameter
    frontend/.../pages/planning/AutoPlanDrawer.tsx                    engine toggle, KPIs
    frontend/.../shared/api/planningApi.ts                            kpis, engine, 2 reasons
    docs/domain/PLANNING_ENGINES_V2.md                                new

## LOCAL_COMMIT

One local commit. No push.

## NEXT_JOB

**JOB 06 - Rate Engine V2.** It is the right next step and it also closes JOB 05's one open KPI:
a rating port that prices a proposal rather than a persisted shipment is exactly what `totalCost`
is waiting for. Next migration: **V39**.
