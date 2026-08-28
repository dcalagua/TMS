# Phase 2 — JOB 21: Work Assignments & Resource Sequencing

```
RESULT=      PASS
STOP_CHAIN=  false

STARTED_AT=   2026-08-28 11:39 America/Lima
COMPLETED_AT= 2026-08-28 11:56 America/Lima
```

## D5 CAPABILITY PROOF

```
WORK_ASSIGNMENT=      YES
RESOURCE_SEQUENCE=    YES
ROUTING_FEASIBILITY=  YES
DRIVER_GUARDS=        YES
VEHICLE_GUARDS=       YES
CONCURRENCY=          YES
PLANNING_BOUNDARY=    YES
UI=                   YES
D5_RESOLVED=          YES
```

| Pillar | Evidence |
|---|---|
| Work assignment | `tms.work_assignment` + `work_assignment_trip`; entities, repository, 6 endpoints |
| Resource sequence | Ordered trips, 1-based contiguous; one PUT replaces the whole day |
| Routing feasibility | `WorkSequenceValidator` via `RoutingPort` (V38); 18 unit tests |
| Driver guards | Shift (V42), unavailability, licence expiry — three distinct reasons |
| Vehicle guards | Unavailability and maintenance, reported apart |
| Concurrency | Two partial unique indexes; 2 real two-thread races, exactly one winner each |
| Planning boundary | `ResourceRejectionReason` in `shared.reference`, nine typed causes |
| UI | `/work-assignments`, in the menu and in the whole-menu E2E smoke |

## BASELINE

Backend 1761 / 0 / 0 · Flyway V1–V46 · next free **V47**.

## DOMAIN_DECISIONS

**1. A work assignment organises shipments; it never grants authority.** The rule the whole job is
built around, stated in the migration, the aggregate, the service and the controller:
**it must not become an alternative route past a dispatch guard.** `TripExecutionService` remains the
only authority on whether a vehicle may leave. A shipment whose accepted carrier does not own the
vehicle (V42, debt D2) is *reported* as `CARRIER_MISMATCH` and repaired nowhere — putting it in
somebody's day changes nothing about whether it can depart.

**2. The core invariant, as a pure function.**

```
previous.end + reposition(previous.lastStop, next.origin) <= next.start
```

Two shipments that do not overlap in time can still be impossible, because the truck has to get from
one to the other. Measured through `RoutingPort` and **never invented**.

**3. An unmeasurable leg is `ROUTING_UNKNOWN`, not zero.** A day built on a reposition nobody
measured is a day nobody has checked, and calling it feasible is the most expensive kind of silence —
the truck is committed and the second shipment is late. Same rule V43 applies to stop ETAs and V45
to delivered quantities.

**4. Nine typed reasons, not one `RESOURCE_NOT_AVAILABLE`.** The system knows the cause. An expired
licence, a truck in the workshop and a gap too short to drive are three problems with three different
fixes — and the person who resolves each is different, which is why `MAINTENANCE_BLOCK` is separate
from `VEHICLE_UNAVAILABLE`: a workshop books a truck out and a planner cannot argue with it.

**5. Every operation revalidates the whole sequence.** Add, remove, reorder, swap the driver, swap
the vehicle — **one endpoint**, because moving a shipment breaks the leg into it *and* the leg out of
it. Three endpoints would be three ways to reach one revalidation and three places to forget it.
`reordersAffectBothJoins` asserts exactly this.

**6. V42's refusal of overnight shifts is respected, not quietly removed.** An assignment is one
operational *date*, and work crossing midnight is refused with `SHIFT_CONFLICT` rather than waved
through. Accepting it would grant overnight support the shift model does not have, and the validator
would be checking against a rule that does not exist.

**7. A driver with no shift configured is not refused.** A company that configured nothing has said
nothing — the same reading V41 gave a dock with no calendar. Refusing would make the feature unusable
for every installation that has not filled the shift table in.

**8. Feasibility is reported everywhere and enforced only at `/confirm`.** A planner may build an
impossible day and look at it — that is how a problem gets diagnosed. Committing to one is a
different act, and the refusal names every conflict.

## MIGRATIONS

```
V47__work_assignment.sql
```

Two tables with RLS, tenant policies and grants; two partial unique indexes; two permissions seeded
with role grants.

## CONCURRENCY_TESTS

`uq_work_assignment_vehicle_day` and `uq_work_assignment_driver_day` — one live assignment per
resource per day, enforced in the database because **a check and a write are not one operation**.
Two real two-thread races: one vehicle in two days → exactly one; one driver in two days → exactly
one. The alternative is a truck in two people's plans.

## PLANNING_BOUNDARY

`ResourceRejectionReason` lives in `shared.reference` so `planning` can read it without reaching into
`fleet` — the boundary `ModuleBoundaryTest` enforces. **Planning V2 was not rewritten**: the capacity
is additive and available when planning chooses to consume it.

## TESTS_FOCUSED

`WorkSequenceValidatorTest` (18) · `WorkAssignmentApiIntegrationTest` (8) ·
`workAssignments.test.ts` (7)

Every case the brief listed is covered, including the exact boundary (arriving precisely on time is
allowed; one minute later is not) and the case the feature turns on
(`unmeasurableLegIsNotZero`).

```
BACKEND_CLEAN_PASS=  1787
BACKEND_CLEAN_FAIL=  0
FRONTEND_PASS=       114
FRONTEND_FAIL=       0
E2E_PASS=            36
E2E_FAIL=            0
E2E_SKIPPED=         7
ACCESSIBILITY=       not addressed (JOB 26)
PERFORMANCE=         not addressed (JOB 25)
RETRIES=             0
DEFECTS_FOUND=       1
DEFECTS_FIXED=       1
```

## DEFECTS

**The origin of a shipment was resolved as its first stop's destination.** Written that way, then
caught on review before any test ran. A reposition is measured from where the previous shipment
finished to where the next one *starts* — which is the depot it leaves from, not the first place it
visits. It would have been wrong by exactly one leg on every join in every day, and the resulting
figures would have looked entirely plausible. Fixed to read the planning run's origin.

## OPEN_DEBTS

```
D1 RESOLVED · D2 RESOLVED (and preserved here) · D3 RESOLVED · D4 DEFERRED_WITH_REASON
D5 RESOLVED  ← this job
D6 OPEN → JOB 22 · D7 RESOLVED · D8 RESOLVED · D9 OPEN → JOB 26 · D10 OPEN
```

**D10 stays open, untouched.** V19 makes cost allocation technically possible; no approved business
rule exists for it, and choosing `DELIVERED_QUANTITY`, `WEIGHT`, `VOLUME` or `PALLETS` as a default
would be inventing one. Settlement works without it.

## FILES_CHANGED

```
A  db/migration/V47__work_assignment.sql
A  fleet/domain/{WorkAssignment, WorkAssignmentTrip, WorkSequenceValidator}
A  fleet/application/{WorkAssignmentService, WorkAssignmentRequest, WorkAssignmentView}
A  fleet/infrastructure/WorkAssignmentRepository · fleet/api/WorkAssignmentController
A  shared/reference/{ResourceRejectionReason, TripSchedulingPort}
A  planning/infrastructure/TripSchedulingAdapter
A  WorkSequenceValidatorTest, WorkAssignmentApiIntegrationTest
A  frontend workAssignmentsApi.ts, WorkAssignmentsPage.tsx, workAssignments.test.ts
M  Permission, Capability, SchemaExposureIntegrationTest, TenancyConstraintIntegrationTest,
   enums.ts, navConfig.tsx, lazyRoutes.tsx, App.tsx, e2e/support/modules.ts
```

```
NEXT_JOB= 22 — Own Fleet Costing V1 (D6). Next migration V48.
```
