# JOB 09 - Fleet Resource Scheduling

**RESULT = PASS** · **STOP_CHAIN = false** · **MIGRATION = V42**

| | |
|---|---|
| Started | 2026-08-28 04:26 America/Lima |
| Completed | 2026-08-28 04:55 America/Lima |
| HEAD before | `af57109` |
| Backend, `./mvnw clean test` | **1617 pass, 0 fail, 0 skipped** |
| Frontend, `vitest run` | **69 pass** |
| E2E, `playwright test` | **34 pass, 7 skipped** |
| Typecheck / lint / build | clean |
| Flyway | V1-V42, contiguous, no gaps |
| Retries | 5 attempted, 5 recovered |

---

## What this job was for

Two things. One of them was a debt, and it was the reason this job existed.

### D2, closed

Since JOB 07 an accepted tender could name a carrier that did not own the vehicle on the shipment,
and there was nowhere to record it. JOB 07 was right to refuse to write it to `carrier_id` - that
would have produced a shipment whose carrier and whose vehicle's owner disagreed with nothing saying
which was true - but the consequence was that the acceptance lived on the tender row and the
shipment itself said nothing about who had agreed to run it.

Two of the three resolutions the brief offered turned out to be unavailable:

- **Clear the vehicle.** Impossible. `ck_trip_confirmed_is_complete` (V25) requires a vehicle on
  every confirmed shipment, and only confirmed shipments are tenderable. The constraint is right.
- **Select a compatible vehicle atomically.** Refused, on the grounds this project has refused
  every comparable thing: choosing among another company's fleet needs rules nobody has stated, and
  inventing the assignment would be a fabrication with a truck attached to it.

So the third: **model the pending state and make it blocking.** `accepted_carrier_id` records who
agreed; `carrier_id` goes on meaning exactly what it always meant, the owner of the assigned
vehicle. The two may disagree - that is a real operational moment, "the carrier is agreed and the
truck is not sorted out yet" - and a shipment in it may be planned, costed, edited and cancelled,
and **may not depart**.

Three layers, as everything here has:

| Layer | |
|---|---|
| `TripExecutionService.requireCarrierOwnsTheVehicle` | the sentence a dispatcher reads, naming what to do next |
| `Trip.dispatch` | refuses in the aggregate, whatever called it |
| `ck_trip_departed_carrier_matches_vehicle` | refuses in the transaction, including a raw data fix |

Stated over the row's status rather than over the transition, because a `CHECK` sees a row and not a
transition - and a row that has *departed* under the wrong carrier is exactly the row that must
never exist.

There is no "resolve" action. Assigning one of the accepting carrier's vehicles runs through
`Trip.assignVehicle`, which sets `carrier_id` to the new owner and makes the two agree. The thing
that fixes it is the thing that was missing.

**Null means "nothing contradicts `carrier_id`", not "unknown".** Every pre-V42 row is null and
correct, because the disagreement was not expressible before. And the ordinary case resolves
itself: a carrier accepting work on its own truck matches on the spot and nobody has anything to do.

### Availability

`tms.resource_unavailability` for both vehicles and drivers, `tms.driver_shift` for weekly hours,
and `ResourceAvailabilityPort` so planning can ask without reaching into the fleet's tables.

**Overlapping downtime is impossible, not discouraged** - two partial `EXCLUDE USING gist`
constraints, one per resource column. Two overlapping "in maintenance" rows on one truck are two
statements of one fact, and the second is what makes a downtime report double-count.
`twoSimultaneousBlocksOneWins` runs two real threads at one truck and asserts exactly one row
survives.

---

## Defects found and fixed: 3

**1. The delete ignored which resource it was on.** `release(scope, blockId)` looked a block up by
id alone, so the driver-facing endpoint could delete a vehicle's block and the vehicle-facing one a
person's. That is not untidiness - it is an authorization hole. V26 separated `fleet.driver:manage`
from `fleet.vehicle:manage` precisely so that whoever books trucks into the workshop cannot see who
is off sick, and a delete by bare id would have made the split mean nothing at the one place it has
to hold. Fixed with `requireBlockOn`, which resolves by resource *and* id, returns 404 rather than
403 (the caller is not entitled to learn the id exists elsewhere), and is now asserted by
`releaseIsScopedToItsResource`. The same fix applies to `clearShift`.

**2. An integration acceptance would have erased the last person who touched the shipment.**
`recordCarrierAcceptance` took an actor and wrote it to `updatedBy`; a carrier answering over the
integration API has no app user, so the field would have been overwritten with null. Recording
"nobody" over a real name loses a fact in order to record the absence of one. `updatedBy` is now
left alone when there is no person, asserted by `machineAcceptanceLeavesUpdatedByAlone`.

**3. Caught by existing guards, all three working as designed.** `SchemaExposureIntegrationTest`
refused the two new tables until they were declared with their grants and their reasoning.
`ck_trip_committed_requires_confirmed_at` and `ck_trip_ready_actor_pair` both rejected a test
fixture that built a shipment state the lifecycle does not allow - the fixture was wrong, not the
constraints.

## Rules that were deliberately not broken

**No new `fleet.availability:*` permission pair.** The obvious design, and the wrong one. A single
availability permission would hand vehicle-downtime clerks a view of drivers' `MEDICAL` and
`ABSENCE` reasons - the most personal data in the fleet module - and undo the split V26 made on
purpose. Reads and writes go through the existing `fleet.vehicle:*` and `fleet.driver:*` instead,
so the permission count is unchanged and the boundary holds.

**Minutes since local midnight, not a `time` column.** The JOB 08 lesson, applied before it could
bite again: `hibernate.jdbc.time_zone: UTC` normalises temporal values on write, and a `time`
column for shift hours would have shifted every depot's hours by its own offset.
`hoursSurviveTheRoundTrip` asserts both halves - 06:00 comes back as 06:00, and 360 is what is
stored.

**No system actor was invented.** Nothing in V42 needs one, and JOB 07's refusal stands.

---

## Test counts

Backend **1585 → 1617** (+32). Frontend **60 → 69** (+9). E2E **34 pass / 7 skipped**, unchanged -
this job added no screen to the menu, and the availability drawer opens from inside the two existing
fleet pages.

The **7 skipped** figure and JOB 08's need reading together: JOB 08 reported 1585 pass *with 7
skipped* because Docker was down for part of that run; this run had Docker up throughout, so the
Testcontainers tests that were skipped then all executed. **0 skipped in the backend** is therefore
a better result than 7, not a regression, and no failing test was converted into a skip anywhere.

---

## Scope not delivered, and why

**No work-assignment table.** Sequencing several shipments onto one driver-and-vehicle pair, with
travel time between them, is a real feature and a large one: it needs a scheduling model, a
rebalancing story and a screen of its own. What V42 ships is the layer underneath it - availability
that every planner and every engine can already read. A table nothing writes to would have been
scaffolding, which this brief forbids. Recorded as **D5** below rather than claimed.

**No hours-of-service model.** `PlanningShift` is a configurable ceiling and this product holds no
jurisdiction's driving rules. A number an operation sets to what it actually does is worth more than
a compliance model that is wrong everywhere but one country.

---

## Open debt register

| # | Debt | State | Note |
|---|---|---|---|
| **D1** | Delivered quantity is not captured per order line | **OPEN** | Untouched by this job. Nothing here infers a quantity |
| **D2** | An accepted tender can leave `shipment.carrier != shipment.vehicle.owner` | **CLOSED (V42)** | `accepted_carrier_id` + `ck_trip_departed_carrier_matches_vehicle`, three layers, invariant and concurrency tests |
| **D3** | No arrival/departure checkpoint per stop beyond the executed timestamps | **OPEN** | JOB 10's. Not needed and not inferred here |
| **D4** | No system-actor model, so no unattended tender scheduler | **DEFERRED_WITH_REASON** | Unchanged. Nothing in V42 needed one |
| **D5** | No work assignment: several shipments cannot be sequenced onto one driver-and-vehicle pair with travel time between them | **OPEN (new)** | Deliberate. V42 delivers the availability layer it would be built on |

---

## Files

**Migration** `V42__fleet_resource_scheduling.sql`

**Backend** `Trip`, `TripTenderService`, `TripExecutionService`, `TripView`, `TripViewAssembler`,
`AuditAction`; new `fleet.domain.ResourceUnavailability` / `UnavailabilityReason` / `DriverShift`,
`fleet.infrastructure.ResourceUnavailabilityRepository` / `DriverShiftRepository`,
`fleet.application.ResourceAvailabilityService` and its four DTOs,
`fleet.api.ResourceAvailabilityController`, `shared.reference.ResourceAvailabilityPort` /
`ResourceBlock`

**Tests** `TripAcceptedCarrierTest` (new, the D2 invariant),
`ResourceAvailabilityIntegrationTest` (new, incl. the two-thread race),
`PlanningConstraintIntegrationTest` (+3, the database half of D2),
`SchemaExposureIntegrationTest`, `fleetAvailability.test.ts` (new)

**Frontend** `shared/api/fleetAvailabilityApi.ts`, `pages/fleet/AvailabilityDrawer.tsx`,
`VehiclesPage`, `DriversPage`, `TripDetailDrawer` (the subcontracted badge), `planningApi`,
`lib/enums`

**Docs** `docs/domain/FLEET_AVAILABILITY_V1.md` (new),
`docs/domain/CARRIER_TENDERING_V1.md` (section 3 superseded in part)

---

**NEXT_JOB** - **JOB 10 - ETA / Geofencing**, which must evaluate **D3**. Next migration **V43**.
