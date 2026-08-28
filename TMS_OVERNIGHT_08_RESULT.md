# TMS OVERNIGHT JOB 08 RESULT

JOB=08 Dock / Appointment Scheduling
RESULT=PASS
STOP_CHAIN=false

STARTED_AT=2026-08-28 03:55 America/Lima
COMPLETED_AT=2026-08-28 04:26 America/Lima

HEAD_BEFORE=1d2d3e5
HEAD_AFTER=(this commit)
MIGRATION=V41

BACKEND_CLEAN_PASS=1585
BACKEND_CLEAN_FAIL=0
FRONTEND_PASS=60
FRONTEND_FAIL=0
E2E_PASS=34
E2E_FAIL=0
E2E_SKIPPED=7
RETRIES=6 attempted, 6 recovered

## OBJECTIVE

A real appointment module: location resources, dock calendars, blocked slots, appointments on trip
stops, the seven-state lifecycle, timezone correctness, and **no double booking enforced in the
database rather than the frontend** - with a real concurrency test.

## BASELINE

Verified against the filesystem before starting: clean tree at `1d2d3e5`, results 01-07 present,
Flyway head **V40**, and no appointment code anywhere - JOB 08 had not been started.

## IMPLEMENTED

A new `appointments` module: `LocationResource` (a dock/door/bay/yard), `ResourceCalendarEntry`
(local opening hours per weekday), `ResourceBlockedSlot` (closures), `Appointment`, their services,
two controllers with nine endpoints, and a `/appointments` dock board with a booking drawer.

**The invariant, in the database.** `ex_appointment_no_double_booking` is an
`EXCLUDE USING gist (resource_id WITH =, tstzrange(window_start, window_end) WITH &&)` over the
statuses that hold a door. An EXCLUDE and not a unique index because what is refused is an
*overlap*; `btree_gist` supplies the `=` half. The service's own check exists to produce a readable
refusal and is explicitly **not** the guarantee.

**A door takes one vehicle.** Six doors are six rows. PostgreSQL can refuse two overlapping ranges
on one key and cannot refuse "more than N overlapping", so a capacity column would move the
invariant back into application code - which is exactly where the spreadsheet this replaces fails.

## MIGRATIONS

**V41__dock_appointment_scheduling.sql**, the next free number, verified on disk. No applied
migration touched. Four tables, `btree_gist`, the exclusion constraint, RLS + tenant policy on all
four, composite FKs pinning doors and shipments to one company, three permissions, four audit
actions and two aggregate types.

## SECURITY / TENANT_TESTS

`fk_appointment_resource_company` makes "a company's booking cannot name another company's door" a
database fact. Three tenancy tests pass through the service: company B cannot book company A's door,
cannot read its bookings, and sees nothing of them on its own board. A fourth asserts two companies
may each have a `DOCK-1`, which is how sites actually label them.

VIEWER holds the read - unlike a tender, a booking carries no price, and the yard, gate and
warehouse all need the board. Configuring doors is an administrator's authority.

## AUDIT

Four actions: `APPOINTMENT_BOOKED`, `_RESCHEDULED`, `_CANCELLED`, `_NO_SHOW`. Arriving and
completing are recorded on the row and mint no action - a row per operational step would bury the
four that carry money. An appointment is its own aggregate type, not a note on a trip: it exists
before a trip does and outlives one that is cancelled.

## OBSERVABILITY

`tms.appointments.bookings` tagged booked / rescheduled / confirmed / arrived / completed /
cancelled / no-show / raced. The `raced` tag counts the losers of the exclusion race, which is the
number that says whether the dock board is being fought over.

## TESTS_FOCUSED

`AppointmentStatusTest` (27, pure) and `AppointmentServiceIntegrationTest` (22, real PostgreSQL).

The one the feature is judged by: **two threads booking one door for one hour at the same instant,
through the real service and the real constraint - exactly one wins**, and the loser is refused
rather than 500-ing. Also: back-to-back bookings do not overlap (the half-open convention, worth an
hour of dock capacity per door per day), cancelling and no-show free the slot while keeping the
record, opening hours read in Lima's offset both ways, closures and out-of-service doors refuse,
rescheduling does not conflict with itself, and `AppointmentStatusTest` asserts the Java occupancy
set equals the constraint's `WHERE` clause.

## TESTS_CLEAN

`./mvnw -B clean test` - **1585 tests, 0 failures, 0 errors**. (+49.)

## FRONTEND / E2E

typecheck clean; lint 0 errors (17 pre-existing warnings); `npm test` **60** (+5); build succeeds.
E2E **34 passed** (+1), 7 skipped - the new route was added to `e2e/support/modules.ts`, whose own
header warns that a copied list stops covering new screens.

## RETRIES_ATTEMPTED=6 / RETRIES_RECOVERED=6

1. **TYPE C.** `AppointmentTripPort` was written inside the appointments module, so planning's
   adapter made planning depend on appointments. `ModuleBoundaryTest` caught it; the port moved to
   `shared.reference` where every other cross-module port lives.
2. **TYPE C.** `CapabilityTest` - three new permissions with no capability. Added `APPOINTMENTS_VIEW`
   and `APPOINTMENTS_MANAGE`.
3. **TYPE C.** `TenancyConstraintIntegrationTest`'s deliberately exact permission counts. Updated
   47→50 and 132→141 with the per-role breakdown, which is the schema contract that test exists to
   hold.
4. **TYPE C.** Spring Boot 4 removed `@MockBean`; switched to `@MockitoBean`. Then two fixture
   column errors (`address_line`, `location_role.company_id`) and `@EnabledIf` not being inherited
   by `@Nested` classes.
5. **TYPE C, and the one worth recording.** Opening hours stored in a `time` column came back
   shifted: `hibernate.jdbc.time_zone: UTC` normalises temporal values on write, so 00:00 became
   `05:00+00` and 23:59 became `04:59+00` - close before open, and the CHECK refused the row. Had
   the constraint not caught it, **every site's opening hours would have silently shifted by its own
   UTC offset**. Fixed at the design level: minutes since local midnight, which no configuration can
   zone-shift. `LocalTime` is still the type every caller sees.
6. **TYPE C.** A frontend file was written before its directory existed and silently lost; rewritten
   after `mkdir`.

## OPEN_DEBTS

| # | Debt | Status |
|---|---|---|
| **D1** | `PlanningKpis.totalCost` not wired | **OPEN** - nothing in JOB 08 needed it |
| **D2** | accepted tender / carrier / vehicle invariant | **OPEN** - JOB 09's, as required |
| **D3** | delivery quantity not modelled | **OPEN** - nothing here needed or inferred a quantity |
| **D4** | no automatic tender scheduler (no system actor) | **DEFERRED_WITH_REASON** - unchanged; nothing in JOB 08 introduced a machine actor |

## KNOWN_LIMITATIONS

- **No capacity per door.** By design - see §2 of the domain doc.
- **No recurring appointments**; **no automatic booking from planning** - which door a shipment uses
  is a site's (often a customer's) decision.
- **No time-grid calendar view.** The board is a day at a site as a table, which is what a gate
  reads; a grid is a presentation change on the same data.
- **`locationTimeZone` resolves from `tms.location`** through a new `LocationTimeZonePort`. A site
  with no zone falls back to the company's - never the server's.

## FILES_CHANGED

    backend/.../db/migration/V41__dock_appointment_scheduling.sql          new
    backend/.../appointments/domain/{Appointment,LocationResource,ResourceCalendarEntry,ResourceBlockedSlot}.java
    backend/.../appointments/domain/{AppointmentStatus,AppointmentPurpose,ResourceType}.java
    backend/.../appointments/application/{AppointmentService,LocationResourceService,...}.java
    backend/.../appointments/infrastructure/*.java                         4 repositories
    backend/.../appointments/api/{AppointmentController,LocationResourceController}.java
    backend/.../shared/reference/{AppointmentTripPort,LocationTimeZonePort}.java   new
    backend/.../planning/infrastructure/AppointmentTripAdapter.java        new
    backend/.../masterdata/infrastructure/LocationTimeZoneAdapter.java     new
    backend/.../shared/security/{Permission,Capability}.java               3 permissions, 2 capabilities
    backend/.../shared/audit/{AuditAction,AuditAggregateType}.java         4 actions, 2 aggregates
    frontend/.../pages/appointments/{AppointmentsPage,BookAppointmentDrawer}.tsx   new
    frontend/.../shared/api/appointmentsApi.ts (+ test)                    new
    e2e/support/modules.ts                                                 route registered
    docs/domain/APPOINTMENTS_V1.md                                         new

## LOCAL_COMMIT

One local commit. No push.

## NEXT_JOB

**JOB 09 - Fleet Resource Scheduling**, which must also resolve **D2**: the accepted-tender vs
vehicle-owner invariant. Next migration: **V42**.
