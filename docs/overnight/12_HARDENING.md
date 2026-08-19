# Step 12 - Quality, security, performance and architecture hardening

Date: 2026-08-19
Attempt: 1
Result: **PASS**

## 0. State inherited from prior steps

The repository arrived on a clean working tree at commit `2c2d0f5` ("overnight 11 11 manual
planning frontend"), with Steps 00-11 complete: 11 Flyway migrations, 25 tables, the IAM/security
foundation, four masterdata modules, fleet, orders and manual planning both backend and frontend.

Baseline before any edit, both measured, not assumed:

- backend `./mvnw test`: **BUILD SUCCESS**, 309 tests, 0 failures (Docker was available, so the
  Testcontainers integration tests ran for real);
- frontend `npm run test`: 36 files, **219 tests**, 0 failures.

This step added **no business module and no endpoint**. Every change is a fix, a test or a
document.

## 1. What was audited

The full chain `UI -> API client -> Controller -> Service/Use Case -> Repository -> DB -> Security
-> Tests`, for all seven modules (`iam`, `masterdata`, `orders`, `fleet`, `planning`, `shared`, and
the React application). No RPC or Edge Function exists, so there was none to review.

Two companion documents carry the detail and are the deliverables of this step:

- [`docs/security/SECURITY_REVIEW.md`](../security/SECURITY_REVIEW.md) - the security review record:
  what was checked, how, what was found, and the gaps deliberately left open.
- [`docs/performance/PERFORMANCE_BASELINE.md`](../performance/PERFORMANCE_BASELINE.md) - the
  measured performance baseline against 10,000+ orders/day, with the measurement conditions stated.

This report is the summary and the audit trail.

## 2. Findings

Severity: **P0** exploitable/broken now, **P1** broken under a plausible condition, **P2**
hardening with real value, **P3** noted, no action justified.

| # | Sev | Area | Finding | Status |
|---|---|---|---|---|
| 1 | P1 | Planning concurrency | `PlanningRunService.confirm`/`cancel` locked trips in **trip-number** order while `TripService.moveOrder` locked by **trip id** - an ABBA deadlock against a concurrent move, surfacing as HTTP 500 | **Fixed** |
| 2 | P1 | Performance | N+1 on the planning board: `TripViewAssembler.toViews` read the lazy `trip.stops()` per trip, costing `9 + N` statements (309 at a 300-vehicle fleet) | **Fixed** |
| 3 | P1 | Performance | `GET /planning/eligible-orders` had no index for its actual predicate; it scanned 5,000 heap blocks and discarded 80% of what it read | **Fixed** |
| 4 | P1 | Error handling | `DataIntegrityViolationException` and `PessimisticLockingFailureException` had no handler and were answered as 500 | **Fixed** |
| 5 | P2 | Tenant isolation | Four batched `findAllInCompany` lookups filtered the tenant in Java after an unscoped `findAllById` | **Fixed** |
| 6 | P2 | Performance | The order list page sorted rows it could have read in order; 20,275 buffers for a selective status filter | **Fixed** |
| 7 | P2 | Performance | `TripRepository.findByPlanningRunIdOrderByTripNumberAsc` had no ordered index | **Fixed** |
| 8 | P2 | Scale | Identity resolution runs two SQL statements per authenticated request | **Open, documented** |
| 9 | P2 | Scale | `GET /masterdata/frequencies/{id}/exceptions` is unpaginated | **Open, documented** |
| 10 | P2 | Scale | Lookup dropdowns fetch `size: 200` and would silently truncate for a large-master company | **Open, documented** |
| 11 | P3 | Security | `FORCE ROW LEVEL SECURITY` is not set (already an explicit, documented decision) | **Open, documented** |
| 12 | P3 | Database | `created_by`/`updated_by` foreign keys are unindexed | **Open, documented** |

**No P0 finding.** Every P0/P1 was fixed. Items 8-12 are recorded with the reason each was left and
what closing it would require - none of them is an authorization gap.

### 2.1 The two findings worth reading in full

**#1, the lock-order deadlock.** `TripRepository.findByIdAndCompanyIdForUpdate` documents its own
contract: callers taking more than one trip lock must take them in a deterministic order.
`moveOrder` obeyed it; the run-wide operations, written later, walked the run's trips in trip-number
order instead. Two orderings over the same rows is the textbook deadlock, and PostgreSQL's victim
transaction reached the caller as a 500 with a lost operation. The fix (`lockTrips`) takes all locks
in ascending trip-id order and returns them by id, so both methods still *iterate* in trip-number
order and every user-facing message is unchanged.

**#2, the board N+1.** Three modules already counted children with one grouped query
(`RouteStopRepository.countByRouteIds`, `TransportOrderLineRepository.countByOrderIds`,
`TripRepository.countByPlanningRunIds`). Planning was the one place that had drifted, and it drifted
on the screen a planner keeps open all day, where the row count *is* the fleet size. Measured at
`9 + N` statements; now a flat 9 at any board size.

## 3. Changes made

Nine main-source files, one new repository, one new migration, one test file. No historical
migration was edited (verified with `git diff` restricted to the migration directory).

**Backend - correctness and security**

- `planning/application/PlanningRunService` - new `lockTrips`, used by `confirm` and `cancel` (#1).
- `shared/api/ApiExceptionHandler` - handlers mapping `DataIntegrityViolationException` and
  `PessimisticLockingFailureException` to `409 conflict`, logged at WARN with the correlation id and
  a deliberately generic caller-facing detail (#4).
- `masterdata/infrastructure/{Origin,Destination}Repository`,
  `fleet/infrastructure/{Vehicle,VehicleType,Carrier}Repository`,
  `orders/infrastructure/TransportOrderRepository` - new `findByIdInAndCompanyId` (#5).
- `masterdata/infrastructure/{Origin,Destination}LookupAdapter`,
  `orders/application/OrderPlanningService`, `fleet/application/VehicleLookupService` - use it, so
  a row of another tenant is never read rather than read and discarded (#5).

**Backend - performance**

- `planning/infrastructure/TripStopRepository` (new) - `countByTripIds`, one grouped query (#2).
- `planning/application/TripViewAssembler` - uses it for the board; `toDetail` still counts in
  memory, deliberately, because it is one trip and already rendering those stops (#2).
- `db/migration/V12__performance_indexes.sql` (new) - `ix_transport_order_planning_pool` (partial,
  #3), `ix_transport_order_company_status_service_date` (#6), `ix_trip_planning_run_number` (#7).
  Indexes only; no structural change.

**Tests**

- `PlanningApiIntegrationTest.boardQueryCountDoesNotGrowWithTheNumberOfTrips` - renders a 1-trip and
  a 5-trip board with Hibernate statistics enabled and asserts the JDBC statement counts are
  **equal**. Verified to fail (13 vs 9) with the fix reverted, so it is a real guard.
- `PlanningApiIntegrationTest.confirmDoesNotDeadlockAgainstAConcurrentMove` - fires a confirm and a
  move at the same barrier and asserts neither ever answers 5xx. A 409 or 404 from the loser is
  legitimate and accepted, so the test cannot fail spuriously.

**Documentation**

- `docs/security/SECURITY_REVIEW.md`, `docs/performance/PERFORMANCE_BASELINE.md`, this report, and
  `docs/README.md` updated to index them.

## 4. Measured results

**Query count** (Hibernate statistics, real PostgreSQL, in the test suite):

| Planning board | Before | After |
|---|---|---|
| 1 trip | 9 statements | 9 |
| 5 trips | 13 statements | 9 |

**Query shape** (`EXPLAIN (ANALYZE, BUFFERS)` on a throwaway PostGIS container seeded with
**900,000 orders** - 90 days x 10,000/day - with a realistic status mix; the container was removed
afterwards and no shared database was touched):

| Query | Metric | Before V12 | After V12 |
|---|---|---|---|
| Eligible orders (1,000 matches) | buffers | 5,051 | **28** |
| | execution | 30.1 ms | **1.3 ms** |
| Order list, selective status (2%) | buffers | 20,275 | **28** |
| | execution | 71.4 ms | **2.3 ms** |
| Order list, common status (92%) | buffers | 31 | 28 |
| | execution | 0.33 ms | 0.26 ms |

The last row is included on purpose: the new index removes a cliff, it does not make the common
case faster, and reporting only the flattering numbers would misrepresent it. Storage cost of the
two new order indexes is ~8.5 MB on a 313 MB table (~2.8%).

## 5. Architecture review

Verified, all clean:

- **No controller reaches a repository.** Enforced by `LayeringTest`'s ArchUnit rules
  (`controllers_must_not_reach_repositories`, `controllers_must_not_use_persistence_apis`), which
  also pin controllers to `api`, use cases to `application` and repositories to `infrastructure`.
- **No module cycles.** `ModuleBoundaryTest.business_modules_must_not_depend_on_each_other`.
  Cross-module needs go through ports in `shared.reference`, and this step's changes kept that
  shape - `TripStopRepository` is internal to `planning`.
- **No frontend business-table Supabase access.** The only `supabase.*` calls in the whole `src`
  tree are the four `auth.*` methods.
- **No business logic in SQL.** The migrations contain constraints, indexes and one
  `set_updated_at` trigger. No RPC, no business logic in a trigger, no giant stored procedure.
- **EWM remains external.** No reference to EWM anywhere in the schema or the code.
- **`shared` is not a dumping ground.** It holds `api`, `audit`, `config`, `reference`, `security`
  and `web`, each a cross-cutting concern with a stated reason, and it depends on no business
  module.

## 6. Observability

Already in place and re-verified rather than rebuilt:

- **Correlation id** on every request, established by `CorrelationIdFilter` *ahead of* the security
  chain, so even a 401 is traceable. It is in the MDC (so every log line carries it via the
  `logging.pattern.level` configuration), in the response header, and in every error document.
  A client-supplied value is sanitised to a bounded printable token before it reaches a log line,
  which blocks log forging and header injection through `X-Correlation-Id`.
- **No PII or secret in logs.** Log statements identify people by `app_user` UUID, never by email;
  no token, password or key is ever logged. The two log lines this step added follow the same rule:
  correlation id, method and URI, with the exception object going to the log and not to the caller.
- **Actuator** exposes `health` and `info` only, `health` with `show-details: never` by default.
  `/actuator/health` is the only public actuator path; `/actuator/info` requires authentication.

**`metrics` was deliberately not exposed.** The step brief lists metrics under observability, and
the honest answer is that exposing it safely is not a configuration change tonight: TMS has no
operator/system permission in its catalogue, so adding `metrics` to the exposure list would let
*any* authenticated warehouse user read JVM, HTTP-route and connection-pool internals. The two
correct ways to close that - a separate management port bound to an internal network, or a new
`system.metrics:read` permission with a migration and a role mapping - are respectively a
deployment decision and an authorization-model change. Both are recorded here so the choice is made
deliberately rather than by omission.

## 7. Test results after remediation

**Backend** - `./mvnw test`, Docker available so the Testcontainers integration tests ran for real:

```
Tests run: 311, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

309 before, 311 after: the two added tests, no test removed or weakened.

**Targeted** - `./mvnw -Dtest=PlanningApiIntegrationTest test`: 25 tests, 0 failures (23 before).

**Frontend** - `npm run test`: 36 files, 219 tests, 0 failures. `npm run typecheck`: clean.
`npm run lint`: clean, 2 pre-existing `only-export-components` warnings, unchanged by this step.

**Migration replay** - `FlywayMigrationIntegrationTest` applies V1-V12 to an empty database, runs
`validate` (which fails on any edited checksum), replays the history onto a second empty database
and asserts an identical schema. All pass with V12 present.

## 8. Environment

Docker Desktop was running, so nothing in this step is an unverified claim: every integration test
and the 900,000-row performance measurement ran against real PostgreSQL. Java 21.0.9, Node 22.17.0,
Maven via the committed wrapper.

## 9. Handoff to Step 13

1. **Open items are items 8-12 of section 2**, with the reasoning in the two companion documents.
   None is an authorization gap; three are scale ceilings that need a product decision, not a patch.
2. **The lock-order rule is now load-bearing in two places.** Any future run-wide or multi-trip
   operation must take trip locks in ascending trip **id** order - `PlanningRunService.lockTrips` is
   the one helper to use. Locking by trip number is what this step fixed.
3. **The N+1 guard is a real test, not a smoke test.** If a future change makes the board's
   statement count grow with the fleet, `boardQueryCountDoesNotGrowWithTheNumberOfTrips` fails with
   the two counts in the message.
4. **V12 is indexes only.** If a later step needs to change what the eligible-order pool filters on,
   `ix_transport_order_planning_pool` must change with it or it silently stops being used.
5. **The metrics decision in section 6 is open on purpose** and is the natural first item if Step 13
   or 14 covers deployment or operations.
6. **Nothing was committed or pushed.** The working tree carries the changes for human review.
