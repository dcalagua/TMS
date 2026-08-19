# Step 10 - Manual planning backend

Date: 2026-08-19
Attempt: 1
Result: **PASS**

## 0. State inherited from prior steps

The repository arrived with a clean working tree and Step 09 (`09_ORDERS`, migration V10)
complete. `docs/overnight/09_ORDERS.md` section 8 ("Handoff to Step 10") was read end to end before
anything was written, and every one of its seven points was used:

1. **`OrderStatus.PLANNED`/`TransportOrder.markPlanned` existed but were unreachable.** They are
   now reached by `OrderPlanningService.markPlanned`, with the "only a `READY_FOR_PLANNING` order
   may be planned" guard the handoff asked planning to add. No enum or schema change was needed.
2. **"Planning must decide what unassign means."** It does: removal, trip cancellation and run
   cancellation all return the order to `READY_FOR_PLANNING`
   (`OrderPlanningService.releaseFromPlanning`); a *move* between trips changes nothing about the
   order's status. `OrderService.cancel`'s message ("unassign it from its trip first") is now
   actionable, and its javadoc was updated to name the operation that does it.
3. **`uq_transport_order_id_company` already existed** and is exactly what
   `fk_trip_order_assignment_order_company` references. `tms.vehicle` turned out to be the one
   company-scoped table without an `(id, company_id)` target - V9 never needed one because nothing
   referenced vehicles - so V11 adds `uq_vehicle_id_company` additively (section 14.7 of the data
   model). V9 was not edited: applied migrations stay immutable.
4. **The `shared.reference` port pattern (rule 10) was reused,** with one refinement documented
   below: `OrderPlanningPort` and `VehicleLookupPort` are implemented in their owning module's
   *application* layer, not by an infrastructure adapter, because they carry business rules rather
   than a repository translation.
5. **`EffectiveCapacityResolver` and the order header totals were used as already-resolved values.**
   Planning computes no capacity of its own from a vehicle type, and re-derives no order totals.
6. **The explicit-version-check pattern (rule 11) was reused** for `planning_run` and `trip`, with
   a documented decision about which operations require a version and which do not (section 3).
7. `OrderFormModal`'s pattern is a frontend note; step 10 is backend-only (see section 1).

## 1. Scope

This step is the **backend** of manual planning. `frontend/tms-web` was not touched: `router.tsx`
still points `planning` and `trips` at `PlaceholderPage`, which is Step 11's work. No API client,
page or component was added, so nothing in the frontend claims a capability that does not exist.

Deliberately **not** built, matching the brief and `CLAUDE.md`'s deferred list: no solver or route
optimisation, no OR-Tools, no loading/dispatch/execution states, no EWM interaction, no live
tracking, and no trigger carrying planning logic (every invariant is a declarative constraint or
Java in `planning.application`).

## 2. Decisions made before writing code

- **An explicit assignment aggregate, never `transport_order.trip_id`.**
  `tms.trip_order_assignment` carries the *allocated* weight/volume/pallets, an `ACTIVE`/`REMOVED`
  lifecycle that preserves reassignment history, and the partial unique index that makes double
  assignment impossible under concurrency. The capacity service sums the assignment rows and never
  the order header, which is precisely what makes a future split assignment a second row rather
  than a rewrite of the capacity code. See `docs/domain/PLANNING_MANUAL_V1.md` section 3 and
  `DATA_MODEL.md` section 14.3.
- **`trip_order_line_allocation` was deliberately not created.** The brief lists it as optional. An
  empty table with no writer is speculative complexity; what mattered was not foreclosing it, and
  the `whole_order` flag plus the allocated-amount columns do exactly that. The document states
  where it would attach (`assignment_id` + `order_line_id`) and what would *not* have to change.
- **A trip has no origin of its own.** It inherits its run's, so "company/origin consistency" is
  structural rather than an invariant that can be violated. Its `company_id` is denormalized from
  the run because it references company-scoped vehicles and carriers (rule 7).
- **Capacity is live while `DRAFT`, frozen at confirmation** - the "strong V1" the brief describes,
  with both directions enforced by CHECK constraints so the row itself says which mode it is in.
  `docs/domain/CAPACITY_MODEL.md` is the full contract.
- **Null and zero limits are different answers, explicitly.** Null means unlimited (a trip with no
  vehicle yet, the only real V1 case); zero means a real limit of nothing (a tanker's pallets).
  Neither produces a division by zero, and a zero limit reports `percentUsed: null` rather than 0%
  ("plenty of room") or 100% ("full"), both of which would be lies.
- **A run stores no counters.** Section 12.3's justification for persisting an order's header
  totals (single writer, same transaction) does not hold for a run's trip/order counts, which
  change through a different aggregate on nearly every request. One grouped query per page instead.
- **Assignment eligibility is company + `READY_FOR_PLANNING` + same origin + *equal* service date.**
  The date rule is equality rather than a range, so a day's plan says exactly what it promised; the
  trade-off (a backlog order must be re-dated in Orders first) is stated in the domain document
  rather than hidden.
- **Versions are required where a planner edits a field they read** (trip vehicle, trip cancel, run
  confirm, run cancel, trip create - which presents the *run's* version) and deliberately **not**
  on assignment operations, which are covered by the row lock and the uniqueness invariant.
  Requiring one there would mean any planner's assignment invalidated every other planner's open
  board. Assignments still *bump* the trip's version, so a vehicle change made from a board that
  has since been filled is refused.
- **Ports that carry rules live in the application layer.** `OriginLookupPort`'s adapter sits in
  `masterdata.infrastructure` because it is a pure repository translation. `OrderPlanningPort` moves
  an order through its lifecycle and `VehicleLookupPort` applies `EffectiveCapacityResolver`, so
  both are implemented by `@Service` classes in `orders.application`/`fleet.application`. Verified
  against `ModuleBoundaryTest`/`LayeringTest` before the dependent code was written: `planning`
  imports neither `orders` nor `fleet`.

## 3. Concurrency, in the two places it actually bites

| Race | What stops it |
|---|---|
| two planners filling the **same trip** | the trip's row lock (`SELECT ... FOR UPDATE`) taken before every mutation, so "read the load → decide it fits → write" cannot interleave |
| two planners assigning the **same order to different trips** | `uq_trip_order_assignment_open_whole_order`, a partial unique index - a row lock on trip A cannot say anything about trip B |
| two opposite **moves** between the same two trips | both locks taken in trip-id order, so they cannot deadlock |
| a **stale board** changing a vehicle | assignment operations bump the trip's version (`PESSIMISTIC_FORCE_INCREMENT`), and the vehicle change checks it |

Two implementation details were found by running the tests, not by reading the code:

1. **A partial index cannot be `DEFERRABLE`,** so a move must close the source assignment *and
   flush* before inserting the target one - Hibernate flushes insertions before updates, so without
   the explicit `saveAndFlush` in `TripAssignmentService.close` the new row collides with a row the
   same transaction has already logically closed. V10 met the same flush-ordering trap and solved it
   by deferring its constraint; here the fix is statement order, and both are documented side by
   side.
2. **`Trip.touch()` alone does not bump a version.** Setting `updatedBy` to the same actor leaves
   the entity clean, so Hibernate writes nothing and the version stands still while the trip's load
   changes underneath every open board - the first version of `staleVersionIsRefused` failed for
   exactly this reason. Fixed with a second, explicitly documented finder
   (`findByIdAndCompanyIdForAssignment`, `PESSIMISTIC_FORCE_INCREMENT`) used only by the operations
   that change what a trip *carries*; the version-checking operations keep the plain
   `PESSIMISTIC_WRITE` finder, because a lock that increments before the comparison would make every
   such request conflict with itself.

## 4. Vertical slice

    (Step 11 UI)  ->  PlanningRunController / TripController
                  ->  PlanningRunService / TripService / TripAssignmentService
                      + PlanningCapacityService + TripViewAssembler + TripStopPlanner
                  ->  PlanningRunRepository / TripRepository / TripOrderAssignmentRepository
                      + OrderPlanningPort (orders.application) + VehicleLookupPort (fleet.application)
                      + OriginLookupPort / DestinationLookupPort (masterdata.infrastructure)
                  ->  tms.planning_run / tms.trip / tms.trip_stop / tms.trip_order_assignment
                  ->  RLS (deny-all) + @PreAuthorize + CompanyScope
                  ->  the tests in section 5

Endpoints (all company-scoped through `X-Company-Id`, all permissions in
`docs/domain/PLANNING_MANUAL_V1.md` section 9):

| Method | Path |
|---|---|
| `GET` | `/api/v1/planning/eligible-orders` |
| `GET` `POST` | `/api/v1/planning/runs` |
| `GET` | `/api/v1/planning/runs/{id}` |
| `POST` | `/api/v1/planning/runs/{id}/confirm`, `/cancel` |
| `POST` | `/api/v1/planning/runs/{runId}/trips` |
| `GET` | `/api/v1/planning/trips/{id}`, `/{id}/capacity` |
| `PUT` | `/api/v1/planning/trips/{id}/vehicle`, `/{id}/stops` |
| `POST` | `/api/v1/planning/trips/{id}/assignments`, `/{id}/assignments/{orderId}/move`, `/{id}/cancel` |
| `DELETE` | `/api/v1/planning/trips/{id}/assignments/{orderId}` |

Performance: the board is constant in the number of trips (one trip query, one grouped load query,
one batched vehicle lookup, one batched origin lookup); trip detail adds one assignment query and
two batched lookups; eligible orders are paginated projections. No endpoint in this module can
reach an order line.

## 5. Tests

Everything below was executed this session against a disposable Testcontainers PostgreSQL. Full
backend suite: **309 tests, 0 failures, 0 errors, 0 skipped** (`./mvnw -o test`).

| Requirement from the brief | Test |
|---|---|
| concurrent whole-order assignment conflict | `PlanningApiIntegrationTest.concurrentAssignmentProducesExactlyOneAssignment` (two threads through the real filter chain) and `PlanningConstraintIntegrationTest.concurrentAssignmentOfTheSameOrderBlocksThenFails` (two real transactions: the second blocks, then fails with 23505) |
| over-weight rejection | `PlanningApiIntegrationTest.overWeightIsRejected` |
| over-volume rejection | `PlanningApiIntegrationTest.overVolumeIsRejected` |
| over-pallet rejection | `PlanningApiIntegrationTest.overPalletIsRejected` |
| vehicle change to smaller capacity rejected | `PlanningApiIntegrationTest.smallerVehicleIsRejected` (and the trip keeps the vehicle it had) |
| move A -> B succeeds atomically if B has room | `PlanningApiIntegrationTest.moveSucceedsWhenTargetHasRoom` (order stays `PLANNED`, two assignment rows survive, one active) |
| move rejected leaves A unchanged | `PlanningApiIntegrationTest.rejectedMoveLeavesTheSourceUnchanged` (no history row written either) |
| cross-company / origin / date assignment rejected | `PlanningApiIntegrationTest.outOfScopeOrdersAreRejected`, plus `PlanningConstraintIntegrationTest.assignmentIsTenantPinned` at the database level |
| confirmed plan mutation rules | `PlanningApiIntegrationTest.confirmationFreezesCapacityAndLocks` (assign/remove/cancel/re-confirm all refused; the snapshot survives a later fleet capacity change), `confirmationRefusesAnIncompleteTrip`, `confirmationRefusesAnUnavailableVehicle` |
| capacity percentages, zero/null edges | `PlanningCapacityServiceTest` (7 cases: per-dimension percentages, unlimited, zero limit with and without load, empty trip, inclusive boundary, multi-dimension refusal message) and `PlanningApiIntegrationTest.zeroPalletVehicleIsARealLimit`, `tripWithoutVehicleIsUnlimited`, `assignComputesCapacityAndCreatesStop` |
| database invariants | `PlanningConstraintIntegrationTest` (17 cases: the open-assignment index and its partiality in both directions, tenant pinning of assignments/vehicles/origins/destinations, confirmed-vs-draft snapshot coherence, one open run per scope, global plan numbers, trip numbers per run, deferrable stop sequence, cascade and window checks) |
| RLS on the new tables | `SchemaExposureIntegrationTest` (list extended with the four V11 tables) |
| module boundaries | `ModuleBoundaryTest`/`LayeringTest`: `planning` depends on neither `orders` nor `fleet` |
| permissions | `PlanningApiIntegrationTest.viewerCannotManagePlanning`, `crossCompanyRunIsNotFound` |
| lifecycle | `removalReleasesTheOrder`, `cancellingARunReleasesEveryOrder`, `duplicateOpenRunIsRefused`, `doubleAssignmentIsRefused`, `staleVersionIsRefused`, `stopsFollowAssignmentsAndCanBeReordered`, `eligibleOrdersReflectAssignment` |

Two honest notes about the tests:

- **The concurrency test asserts one success and one refusal, not one specific status code.** The
  loser gets 409 when it passed its own eligibility check and the unique index caught the insert,
  and 400 when the winner had already committed and the order was no longer eligible by the time it
  looked. Both are refusals; asserting one of them specifically would be asserting a scheduling
  accident. The invariant that matters - exactly one active assignment row afterwards - is asserted
  directly against the database.
- **The stop-window assertion compares the stop's window to the API's own report of the assigned
  orders' windows, not to wall-clock literals.** The fixture seeds orders through raw SQL while the
  application reads `time` columns through Hibernate under `hibernate.jdbc.time_zone: UTC`, so the
  two disagree by the JVM's offset for values written outside JPA. An order written *through the
  API* round-trips unchanged (`OrderApiIntegrationTest` asserts `08:00:00` in and out), so this is a
  fixture artefact rather than an application defect - but it is worth knowing before anyone seeds
  `time` data by hand. Flagged in section 8.

## 6. Constraint compliance

| Constraint | How |
|---|---|
| never push, never deploy | nothing was pushed, committed or staged; no deployment exists |
| never mutate a remote/shared database | every test ran against a local, disposable Testcontainers PostgreSQL; no Supabase project or shared database was touched |
| no real secrets | no `.env` file was read or created |
| no destructive Git operations | none run |
| Flyway is the only migration owner | V11 is the only schema change; `supabase/migrations` still does not exist (`MigrationConventionTest`) |
| applied migrations are immutable | V9 was not edited; `tms.vehicle`'s missing composite-FK target was added by a new `ALTER TABLE` in V11 |
| Java owns business logic and authorization | eligibility, capacity, state transitions, locking, tenancy and `@PreAuthorize` are all backend-side; the database holds the invariants as constraints, not the logic |
| no giant trigger for planning logic | the only triggers on the new tables are the existing `set_updated_at` timestamp triggers |
| React talks to Spring Boot for business data | no frontend change was made; nothing added a direct Supabase call |
| TMS independent from EWM | no cross-product reference of any kind was added |
| vertical slice checked end to end | section 4, read and verified layer by layer |
| do not claim untested passes | every number in section 5 comes from a run executed this session; the two implementation bugs in section 3 were found by running the tests, not by reasoning about the code |
| deferred-by-decision items untouched | no solver, no OR-Tools, no GPS, no EWM, no execution states, no Realtime |

## 7. Files

Added:

```
backend/tms-api/src/main/resources/db/migration/V11__planning_manual.sql
backend/tms-api/src/main/java/com/ebim/tms/shared/reference/{PlannableOrder,PlannableOrderQuery,
  OrderPlanningPort,VehicleCapacityReference,VehicleLookupPort}.java
backend/tms-api/src/main/java/com/ebim/tms/orders/application/OrderPlanningService.java
backend/tms-api/src/main/java/com/ebim/tms/fleet/application/VehicleLookupService.java
backend/tms-api/src/main/java/com/ebim/tms/planning/domain/{PlanningRun,PlanningRunStatus,
  PlanningMode,Trip,TripStatus,TripStop,StopPlan,TripOrderAssignment,AssignmentStatus}.java
backend/tms-api/src/main/java/com/ebim/tms/planning/application/{PlanningRunService,TripService,
  TripAssignmentService,PlanningCapacityService,TripViewAssembler,TripStopPlanner,CapacityLimits,
  CapacityLoad,CapacityDimension,CapacitySource,TripCapacityView,PlanningRunRequest,
  PlanningRunFilter,PlanningActionRequest,TripCreateRequest,TripVehicleRequest,AssignOrderRequest,
  MoveOrderRequest,TripStopOrderRequest,EligibleOrderFilter,EligibleOrderView,PlanningRunView,
  PlanningRunDetailView,TripView,TripDetailView,TripAssignmentView,TripStopView}.java
backend/tms-api/src/main/java/com/ebim/tms/planning/infrastructure/{PlanningRunRepository,
  TripRepository,TripOrderAssignmentRepository,PlanningRunSpecifications}.java
backend/tms-api/src/main/java/com/ebim/tms/planning/api/{PlanningRunController,TripController}.java
backend/tms-api/src/test/java/com/ebim/tms/database/PlanningConstraintIntegrationTest.java
backend/tms-api/src/test/java/com/ebim/tms/planning/application/PlanningCapacityServiceTest.java
backend/tms-api/src/test/java/com/ebim/tms/planning/api/PlanningApiIntegrationTest.java
docs/domain/PLANNING_MANUAL_V1.md
docs/domain/CAPACITY_MODEL.md
docs/overnight/10_MANUAL_PLANNING_BACKEND.md
```

Modified:

```
backend/tms-api/src/main/java/com/ebim/tms/orders/domain/{TransportOrder,OrderStatus}.java
  javadoc only: markPlanned/PLANNED now name the planning service that reaches them
backend/tms-api/src/main/java/com/ebim/tms/orders/application/OrderService.java
  javadoc only: cancel() now names the operation that unassigns a planned order
backend/tms-api/src/test/java/com/ebim/tms/database/SchemaExposureIntegrationTest.java
  the four V11 tables added to the RLS and not-in-public checks
docs/database/DATA_MODEL.md
  new section 14 (the V11 model), new rules 12-13, the V11 test row and the RLS row in section 6
```

## 8. Handoff to Step 11 (manual planning frontend)

1. **The board is one call.** `GET /planning/runs/{id}` returns the run plus every trip with its
   capacity summary and counts - no per-trip follow-up needed. Open a trip's panel with
   `GET /planning/trips/{id}` (assignments + stops). Do not build a screen that loops over trips.
2. **Never compute capacity in the browser.** Every response carries `capacity` with `used`,
   `limit`, `remaining`, `percentUsed`, `exceeded`, `unlimited` and `source` per dimension. Render
   `unlimited: true` as "no vehicle yet" and `percentUsed: null` with a real limit as "n/a" (a zero
   limit) - see `docs/domain/CAPACITY_MODEL.md`.
3. **Versions.** Send the trip's `version` for a vehicle change or a trip cancellation, and the
   run's for confirm/cancel/trip-create. Assignment calls take none. A 409 with "changed by someone
   else" means reload the board - that is the intended UX, and `staleVersionIsRefused` covers it.
4. **Drag-and-drop maps cleanly**: order card → `POST /trips/{id}/assignments`; card dragged between
   trips → `POST /trips/{from}/assignments/{orderId}/move`; card off a trip →
   `DELETE /trips/{id}/assignments/{orderId}`; stop list reordered → `PUT /trips/{id}/stops` with
   the full destination list. All of them return the updated trip detail, so the panel can be
   repainted from the response without a refetch.
5. **Refusals are the interesting states.** Over-capacity is a 409 whose `detail` names every
   dimension that failed; out-of-scope orders are 400s naming origin or service date. Both are
   written to be shown to a planner verbatim (SweetAlert2), not swallowed.
6. **`GET /planning/eligible-orders`** takes `originId`, `serviceDate`, `destinationId`,
   `orderNumber`, plus the standard `page`/`size`/`sort`. Assigning an order removes it from this
   list immediately (it becomes `PLANNED`), and removing it puts it back.
7. **`time` columns and `hibernate.jdbc.time_zone: UTC`.** Values written through JPA round-trip
   correctly, but anything inserted into a `time` column by hand (a seed script, a data fix) will
   read back shifted by the JVM's offset. If a future step wants to seed order windows outside the
   API, decide that setting deliberately rather than discovering it - section 5 has the detail.
8. **Frontend routes are still placeholders.** `router.tsx` points `planning` and `trips` at
   `PlaceholderPage`, and `Capability.PLANNING_VIEW/MANAGE`/`TRIPS_VIEW/MANAGE` already exist for
   menu gating - remembering, as always, that hiding a button is not authorization.

## 9. Result

Manual planning V1 is complete on the backend: planning runs, trips, planning-instance stops and an
explicit order-to-trip assignment aggregate that keeps its history, with one capacity service that
computes weight/volume/pallet utilisation server-side, refuses an over-capacity write inside the
transaction that would have made it, revalidates every assignment when a vehicle changes, and
treats an unlimited limit and a zero limit as the different answers they are. Concurrency is
handled where it actually bites: a row lock for two planners on one trip, a partial unique index for
two planners on one order, ordered locking for opposing moves, and a forced version bump so a stale
board cannot change a vehicle it never saw filled. A plan is confirmed as a unit, revalidated from
scratch at that moment, and frozen against later fleet edits. `planning` imports neither `orders`
nor `fleet` - both are reached through ports in `shared.reference`, implemented by the owning
module's application layer because they carry rules rather than translations. 309 backend tests
pass, including 23 API-level planning tests, 17 database-level invariant tests and 7 capacity unit
tests; two real defects (a flush-ordering collision on the partial index and a version that stood
still while a trip's load changed) were found by running them.

TMS_GATE=PASS
