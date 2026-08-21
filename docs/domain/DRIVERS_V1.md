# Drivers V1

The fleet master for the person who drives, and the rule set that decides whether they may be put
on a shipment.

Migration: **V26** (`V26__fleet_driver_and_trip_assignment.sql`).
Schema detail: `docs/database/DATA_MODEL.md` section 20.

---

## 1. Why this exists

Until V25 a trip named a vehicle and a carrier and stopped there. That is enough to plan a load
and not enough to run one: nobody could say who was at the wheel, so "call the driver of
SH-00000142" was not a question the system could answer, and a licence that expired last month
could not stop a dispatch it should have stopped.

V1 adds exactly two things - a company-scoped person master, and one nullable column on the trip
that points at it - plus the four rules that make the pointer mean something.

## 2. The master

`tms.driver`, in the `fleet` module next to `Carrier`, `VehicleType` and `Vehicle`.

| Field | Required | Notes |
|---|---|---|
| `code` | yes | normalized upper case, unique per company - every master's code |
| `firstName` / `lastName` | yes | trimmed, **not** upper-cased: they are printed on a manifest |
| `documentType` / `documentNumber` | yes | free-text pair, unique per company |
| `phone` | no | a dispatcher's next action after "who is driving" is to call them |
| `licenseNumber` | yes | normalized upper case, unique per company |
| `licenseCategory` | no | free text - licence classes are national catalogues |
| `licenseExpiresOn` | no | the last valid day, **inclusive** |
| `carrierId` | no | null = the company's own staff |
| `active` | - | its own endpoint, never a field on the request |

Two name columns and not one `fullName`: a dispatch list sorts and searches by surname, and
splitting a single field back apart is guesswork the moment a driver has two of each. The
concatenation a screen shows (`"Quispe, Ana"`) is derived, so the two can never disagree with it.

`documentType`/`documentNumber` are free text for the same reason `carrier.tax_id_type` is
(migration V9): identity-document types vary by country - DNI, CURP, CPF, ... - and TMS must not
hardcode a per-country catalogue to stay usable beyond one market. Java suggests common values; it
does not restrict them.

### 2.1 Deactivated, never deleted

A driver who leaves the fleet is deactivated. The trips they ran keep pointing at them and keep
resolving their name (`DriverLookupPort.findAllInCompany` does not filter on `active`), which is
what makes the historical question answerable at all. `ON DELETE RESTRICT` makes deletion
impossible while any trip references them.

## 3. Licence status

One rule, one place: `shared.reference.DriverLicenseStatus`.

| Status | Condition | Blocks? |
|---|---|---|
| `UNRECORDED` | no expiry on file | no |
| `VALID` | expires more than 30 days after the day asked about | no |
| `EXPIRING_SOON` | expires within 30 days, inclusive of the day itself | no - a badge, not a block |
| `EXPIRED` | expiry date has passed | **yes** |

Three properties of this table are decisions rather than arithmetic:

1. **The expiry day is inclusive.** A licence that expires on the 30th is valid *on* the 30th -
   that is what a driver is told when they are handed it, so that is what the rule has to mean.
2. **`UNRECORDED` blocks nothing.** Not knowing when a licence expires is not evidence that it
   has. The alternative would make every driver imported from a spreadsheet without expiry dates
   unusable, which is the opposite of what a master is for.
3. **`EXPIRING_SOON` blocks nothing either.** Refusing a driver whose licence runs out next week
   would strand shipments that finish today. It is a prompt to renew, shown as an amber badge on
   the driver list, on the trip board and in the driver picker.

**Which day it is judged against differs by caller, and deliberately so:**

| Caller | Day | Why |
|---|---|---|
| Drivers screen | today (company zone) | "whose licence needs renewing" |
| Trip board / shipment header | the trip's **planning date** | "is this licence good on the day this shipment runs" |
| `TripService.updateDriver` | the trip's **planning date** | a plan built Friday for Monday must be refused if the licence lapses over the weekend |
| `TripExecutionService` (ready, dispatch) | today (company zone) | a shipment leaving a day late leaves with today's licences |

This is why the rule takes the day as a parameter and why `DriverLookupPort` deliberately does
*not* answer "is this driver assignable" on its own - it has no way to know which day is meant.

## 4. Assigning a driver to a trip

`PUT /api/v1/planning/trips/{id}/driver`, body `{ driverId, version }`.

Authorised by `planning.trip:manage` **or** `planning.trip:execute`, like cancellation: a planner
names the driver when they build the shipment, a dispatcher swaps one at 05:00 when the person who
was going to drive calls in sick.

### 4.1 The four rules

1. **Tenant and state.** The driver must resolve inside the caller's company and be `active`.
   Anything else is `400` - and a driver of another company is indistinguishable from one that
   does not exist, which is the discipline every cross-module lookup in TMS follows.
2. **Licence.** Not `EXPIRED` on the trip's planning date. `400`.
3. **Carrier compatibility.** If the trip names a carrier *and* the driver names one, they must be
   the same. `400`. Only-both-known: a driver with no carrier is own staff, and lending them a
   subcontracted truck for a day is a real arrangement. Checked from both directions - swapping
   the vehicle re-runs it, since the trip's carrier comes from the vehicle.
4. **Double booking.** At most one non-cancelled trip per driver per planning date. `409`, backed
   by `uq_trip_driver_active_planning_date` as the concurrency backstop.

### 4.2 The assignment window

`DRAFT`, `CONFIRMED`, `READY_FOR_DISPATCH`. Refused from `IN_TRANSIT` onwards.

Wider than the vehicle's draft-only window, and the asymmetry is the point: swapping a vehicle
changes what the plan was *validated against* (the capacity a confirmed trip is frozen at,
`docs/domain/CAPACITY_MODEL.md`), while swapping a driver changes nothing a shipment was proved
against. Once the vehicle has left, who is driving stops being a plan and becomes a fact.

### 4.3 Clearing is an instruction, not an omission

`driverId: null` clears the assignment and releases the person for another trip that day. "The
driver we had is off and we do not have another yet" is a real state a dispatcher records, which
is why the field is nullable here and mandatory on `TripVehicleRequest` - a shipment with no
vehicle cannot be confirmed, so "no vehicle" is never worth submitting.

## 5. Execution

`TripExecutionService` re-checks the driver at **ready** and at **dispatch**, exactly as it
re-checks the vehicle and for the same reason: the assignment may have been days ago, and a
licence that lapsed in between must stop the truck at the gate rather than on the road.

- driver no longer `active` → `409`
- licence `EXPIRED` as of today → `409`
- **no driver at all → passes.** Naming one is not required by any state, so this must not become
  a back-door mandate.

Not applied to **complete**: a trip already on the road has to be closeable whatever has since
happened to the fleet or the personnel file. Refusing would leave a shipment permanently
`IN_TRANSIT`, which is worse than the edit it is reacting to.

## 6. Authorization

`fleet.driver:read` and `fleet.driver:manage`, a resource of their own rather than a widening of
`fleet.vehicle:*`. A driver record holds personal data - name, identity document, phone - that a
vehicle record does not, and an installation must be able to let a fleet clerk maintain trucks
without opening the personnel file.

Seeded roles: `ORGANIZATION_ADMIN` and `COMPANY_ADMIN` get both; `PLANNER` and `VIEWER` get
`read`. A planner needs to *pick* a driver, which is a read of this master plus
`planning.trip:manage` on the trip - not a write here.

## 7. UI

- **Maestros → Flota → Conductores** (`/fleet/drivers`): list with filters by code, name, carrier,
  licence status and active state; a reusable drawer for create/edit; the licence badge on every
  row with its date underneath.
- **Planning board → trip drawer**: an *Assign driver* / *Change driver* action next to the vehicle
  one, and the driver plus their licence in the shipment header.
- **Trips → workspace**: the driver, their phone and their licence badge on the assignment card,
  and the same assign/change action - this is where a post-confirmation swap actually happens.
- **Trips list**: a driver column (with the badge, but only when it is `EXPIRED` or
  `EXPIRING_SOON` - a badge on every row is no badge) and a driver filter.

The badge is always the server's answer rendered. The browser could subtract two dates just as
easily, and that is exactly the problem: the same comparison decides whether an assignment is
refused, so a second copy in TypeScript would eventually show green on a driver the endpoint
behind the button rejects.

## 8. Deliberately not in V1

- **No driver snapshot on the trip.** See `docs/database/DATA_MODEL.md` section 20.5.
- **No hours of service, rest periods or shift calendar.** Availability is `active` plus the
  one-trip-per-day index - the same "current-state flag, not a scheduling calendar" line
  `vehicle.availability_status` draws. A real hours-of-service model needs legal rules per country
  and per-stop execution times TMS does not record yet (migration V25's closing note).
- **No co-driver.** One driver per trip is what the double-booking index and the assignment rules
  are written against; a crew model is a join table, not a nullable column, and nothing in V1 asks
  for one.
- **No licence-document storage.** Supabase Storage is deferred by decision; the licence number
  plus an expiry date is the part planning can act on.
- **No bulk driver import.** The Import Center covers locations, carriers, vehicle types and
  vehicles; adding drivers to it is a self-contained follow-up that reuses
  `shared.imports` unchanged.
- **The driver is not published on the outbound shipment contract.** `PublishedShipment`
  (`docs/integrations/OUTBOUND_SHIPMENT_V1.md`) is unchanged by V26. Sending a named person and
  their identity document to an external partner is a data-protection decision, not a field
  addition, and nothing has asked for it yet.
