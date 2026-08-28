# Fleet resource scheduling - V1

*Migration V42. Read `docs/architecture/ADR-003-multitenancy-company-scope.md` and
`docs/architecture/ADR-005-tenant-rls-runtime-role.md` first: everything here is company-scoped and
nothing below repeats why.*

This document covers two things that arrived together and are not the same thing. The first is a
correctness debt that had been open since JOB 07. The second is the availability layer a planner
actually uses.

---

## 1. The accepted tender and the vehicle on the shipment

### The problem

A shipment is offered to carriers that do not own the vehicle assigned to it. That is what
subcontracting *is*. But `tms.trip.carrier_id` has always meant one specific thing - **the owner of
the assigned vehicle**, written by `Trip.assignVehicle` and by nothing else - and so an acceptance
from a different carrier had nowhere to go.

JOB 07 chose, correctly, not to write it to `carrier_id`: doing so would have produced a shipment
whose carrier and whose vehicle's owner disagreed, with nothing in the schema saying which one was
true. The consequence was that the acceptance lived on the tender row and the shipment itself said
nothing about who had agreed to run it. That was recorded as open debt **D2**.

### What was rejected

**Clear the vehicle and let the shipment wait for one of the accepting carrier's.** Impossible:
`ck_trip_confirmed_is_complete` (V25) requires a vehicle on every confirmed shipment, and only
confirmed shipments are tenderable. The constraint is right; the idea is not.

**Pick a compatible vehicle of the accepting carrier automatically.** Refused. Choosing among
another company's fleet needs rules nobody has stated - which truck, on what criteria, and who is
accountable when it is the wrong one. Inventing that assignment is the same class of fabrication
this project has refused at every previous decision point.

### What was built

A third column, and one invariant.

```
tms.trip.carrier_id           the owner of the assigned vehicle - unchanged meaning
tms.trip.accepted_carrier_id  the carrier that accepted a tender - new
```

The two may disagree, and that disagreement is a **real operational state**: the carrier is agreed
and the truck is not sorted out yet. A shipment sits in it, is edited in it, is costed in it and is
re-planned in it. It may not **depart** in it.

```sql
CONSTRAINT ck_trip_departed_carrier_matches_vehicle CHECK (
    status NOT IN ('IN_TRANSIT', 'COMPLETED')
    OR accepted_carrier_id IS NULL
    OR accepted_carrier_id = carrier_id)
```

Stated over the row's status rather than over dispatch, because a `CHECK` sees a row and not a
transition - and a row that has departed is precisely the row this must never allow.

The usual three layers:

| Layer | What it does |
|---|---|
| `TripExecutionService.requireCarrierOwnsTheVehicle` | The sentence a dispatcher reads, naming what to do next |
| `Trip.dispatch` | Refuses in the aggregate, whatever called it |
| `ck_trip_departed_carrier_matches_vehicle` | Refuses in the transaction, including a raw data fix |

Only the first says what to do about it. That is the point of having three.

**Null means "nothing contradicts `carrier_id`", not "unknown".** Every existing row is null and
correct, because until V42 the disagreement was not expressible.

**The ordinary case resolves itself.** A carrier accepting work on its own truck sets
`accepted_carrier_id = carrier_id` on the spot, and nobody has anything to do. The pending state
only appears where subcontracting actually happened.

### Resolving it

Assign one of the accepting carrier's vehicles. That runs through `Trip.assignVehicle`, which sets
`carrier_id` to the new vehicle's owner, and the two agree again. There is no separate "resolve"
action, deliberately: the thing that fixes it is the thing that was missing.

---

## 2. When a vehicle or a driver cannot work

### One table, two typed columns

`tms.resource_unavailability` holds both, with `driver_id` and `vehicle_id` nullable and a `CHECK`
that exactly one is set. The same shape V30 chose for rate-card scope targets, for the same reason:
a polymorphic `resource_id` cannot carry a foreign key, and a block pointing at a deleted driver is
a hole in the one record that explains why a truck did not run.

`ResourceUnavailability` has no public constructor - `forVehicle` and `forDriver` are the only ways
in - so "a block on nothing" and "a block on both" are not states the Java type can hold.

### A reason describes one kind of thing

| Vehicle | Driver | Either |
|---|---|---|
| `MAINTENANCE` `REPAIR` `INSPECTION` | `ABSENCE` `HOLIDAY` `TRAINING` `MEDICAL` | `OTHER` |

A truck on `HOLIDAY` and a driver in `REPAIR` are both nonsense. `UnavailabilityReason` declares
which resources each value describes, and the entity refuses the mismatch - so the rule sits with
the enum rather than with whoever remembers to check it. The frontend offers two lists for the same
reason: a dropdown should not offer an option the server will reject.

`OTHER` exists because an operation always has a reason nobody anticipated, and a planner picking
the nearest wrong value loses more information than a vague one does.

### Overlaps are impossible, not discouraged

```sql
EXCLUDE USING gist (vehicle_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
    WHERE (vehicle_id IS NOT NULL)
```

and the matching one for drivers. Two overlapping "in maintenance" rows on one truck are two
statements of one fact, and the second is what makes a downtime report double-count.

Two partial constraints and not one, so a driver's blocks and a vehicle's never collide with each
other. `ResourceAvailabilityService` refuses first with a sentence naming the window in the way;
the constraint refuses in the transaction that raced past that check. Two threads, one row - the
same arrangement dock booking has with `ex_appointment_no_double_booking` (V41), and asserted the
same way.

**Half-open.** A truck out of the workshop at 12:00 is available at 12:00, matching `tstzrange`'s
`&&` exactly. Getting this wrong costs an hour of capacity on every resource, every day.

### Releasing deletes the row

A maintenance window entered by mistake is not a fact about the truck, and leaving it behind with
zero length would put a phantom in every downtime figure. The decision survives where a reversal
belongs: `RESOURCE_BLOCKED` and `RESOURCE_RELEASED` on the audit trail, against the vehicle or the
driver rather than against a new aggregate type - a planner asking "why did TR-04 not run on the
14th" reads it on the truck.

### The permission split is load-bearing

Vehicle downtime is guarded by `fleet.vehicle:manage`, driver absence by `fleet.driver:manage`.
**No new `fleet.availability:*` pair**, deliberately. V26 separated those two permissions because a
driver record holds personal data a truck record does not - and a driver's absence reason
(`MEDICAL`, `ABSENCE`) is the most personal thing in the fleet module. One availability permission
would hand whoever books trucks into the workshop a view of who is off sick.

That makes `requireBlockOn` an authorization check rather than tidiness: the delete looks the block
up by *resource and id*, so the driver endpoint cannot remove a vehicle's block and the vehicle
endpoint cannot remove a person's. Without it the permission split would mean nothing at the one
place it has to hold. 404 rather than 403 for a block on a different resource - the caller is not
entitled to learn that the id exists on something else.

---

## 3. Driver shifts

`tms.driver_shift`: one row per driver per day of the week, ISO-8601 numbering (1 = Monday).

**Stored as minutes since local midnight**, and that is the whole point of the shape. This
application sets `hibernate.jdbc.time_zone: UTC`, which normalises temporal values on write. JOB 08
found it turning a dock's 00:00 into `05:00+00` and its 23:59 into `04:59+00` - close before open,
caught only because a `CHECK` happened to be looking. Without that constraint every site's opening
hours would have shifted silently by its own UTC offset. An integer count of minutes cannot be
shifted by a driver, a dialect or a deployment's clock.

`startsAt()` and `endsAt()` hand a `LocalTime` back for display. Nothing persists that type, and
`ResourceAvailabilityIntegrationTest.hoursSurviveTheRoundTrip` asserts **both** halves - that 06:00
comes back as 06:00, and that 360 is what is stored.

**No overnight shifts.** `ends_at_minutes > starts_at_minutes`. A shift running 22:00-06:00 is two
rows on two days, which is more typing and less arithmetic than the wrap-around branch every
containment check would otherwise need. Same decision V41 made for opening hours.

`PUT` and not `POST`, because `uq_driver_shift_day` allows one row per driver per day: "Tuesday is
now 07:00-16:00" is the sentence an operation says, and making them delete Tuesday first would be
ceremony over a rule the database already states.

---

## 4. How planning sees it

`ResourceAvailabilityPort` (`shared.reference`), implemented by fleet. `planning` asks one question
- "is anything blocking these two at this instant" - and gets the first block found, with its
reason and when it lifts.

Narrow on purpose. A dispatcher stopped at the gate needs the reason the truck is not going, not a
list of every future workshop booking. `TripExecutionService` checks it at **dispatch**, not only
when the shipment was planned, for the same reason it re-checks a driving licence there: a truck
booked into the workshop this morning was assignable last night, and the record of why it did not
run is worth more than a shipment that pretended it did.

---

## 5. Deliberately not built

**No work-assignment table.** Sequencing several shipments onto one driver-and-vehicle pair, with
travel time between them, is a real feature and a large one - it needs a scheduling model, a
rebalancing story and its own screen. V42 delivers the layer underneath it: availability that every
planner and every engine can already read. Shipping an empty table nothing writes to would have
been scaffolding.

**No hours-of-service model.** `PlanningShift` is a configurable ceiling and this product holds no
jurisdiction's driving rules. A number an operation sets to what it actually does is worth more than
a compliance model that is wrong in every country but one.

**No automatic vehicle selection on tender acceptance.** See section 1.
