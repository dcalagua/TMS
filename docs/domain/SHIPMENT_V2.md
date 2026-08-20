# Shipment V2

What a planned trip has to be able to say about itself before it can leave TMS, and what the
relationship between a master route and a planned shipment is. Introduced by job 07 of the
overnight-v3 pack (migration V19); it builds on
[`PLANNING_MANUAL_V1.md`](PLANNING_MANUAL_V1.md) and
[`CAPACITY_MODEL.md`](CAPACITY_MODEL.md) and replaces neither.

## Trip, Shipment, and why both names stay

Oracle OTM's vocabulary is the conceptual reference: `Transport Order -> Planning -> Shipment ->
Stops`. TMS's internal class is `Trip`, the Spanish UI says *Viaje*, and the outward-facing name is
*Shipment*. All three are the same row (`tms.trip`), and renaming the class would be churn for no
behaviour - so instead the row carries **two identities**, and which one to use is not a matter of
taste:

| | `trip_number` | `shipment_number` |
|---|---|---|
| Scope | unique inside one `planning_run` | unique installation-wide |
| Assigned by | `TripService.create`, from `MAX + 1` in the run | `tms.shipment_number_seq` |
| Means anything alone? | no - "trip 2" needs "of PL-00000017" | yes - `SH-00000042` |
| Use it for | the planning board, a planner's sentence | integrations, manifests, support calls |

`shipment_number` exists because `trip_number` cannot do that job: two runs on the same day both
have a trip 2. It is drawn from a sequence rather than from `MAX(shipment_number) + 1` so two
planners creating a trip at the same instant cannot be handed the same number, and
`uq_trip_shipment_number` is the backstop if a raw insert ever bypasses the service. The column
also has a `DEFAULT` off the same sequence, so "every trip has a shipment number" is true of the
table and not only of the one service that writes it.

## The shipment header

`TripView` (the board row *and* the header) exposes, for every trip:

company · shipment number · plan number · planning date · origin (code, name, coordinates) ·
carrier · vehicle · plate · vehicle type · planned departure · status · capacity limits and their
source · used weight/volume/pallets with utilisation percentages · stop count · order count ·
suggested route. `TripDetailView` adds the ordered stops and the assigned orders.

### Derived, not stored

Only two of those are columns on `tms.trip`, and migration V19's header lists every one that is
not, with the reason. The short version: **a shipment stores what cannot be derived and nothing
else.**

- **Origin** comes from the planning run. A trip deliberately has no `origin_id` of its own
  (migration V11), which is what makes "trip and run agree on the origin" structural rather than an
  invariant something could violate.
- **Carrier** is `tms.trip.carrier_id`, stored explicitly at vehicle assignment - and the carrier's
  *name* is resolved from that id through `CarrierLookupPort`, never from the vehicle. Resolving it
  from the vehicle would give away exactly the guarantee V11 stored the column for: a truck moved
  to another carrier would make a confirmed shipment display a carrier it was never planned with.
  `PlanningApiIntegrationTest.carrierIsTheOneThePlanNamedNotTheVehiclesCurrentOne` pins this.
- **Used weight/volume/pallets** are one grouped `SUM` over active `trip_order_assignment` rows,
  never a stored counter. A stored copy is a second source of truth that a concurrent assignment
  can leave stale - see `CAPACITY_MODEL.md`, "Where each number comes from".
- **Stop coordinates** are read live from `tms.destination`. A corrected store coordinate must
  reach an open plan immediately, and a frozen wrong one would be undetectable.

### The one snapshot, and why

`snapshot_max_weight_kg` / `_volume_m3` / `_pallets` / `capacity_snapshot_at`, frozen at
confirmation. That is the only duplication in the shipment, it predates this job (V11), and
`CAPACITY_MODEL.md` documents why it is necessary: live capacity is right while a plan is a draft
and wrong once it is binding, because a fleet edit made a week later would silently rewrite what
the plan was validated against and make an audit of it irreproducible.

### Map-ready stops

`TripStopView` carries `latitude`/`longitude`, always both or neither, null when the destination
has never been geocoded. A null pair is information, not a gap to paper over: a client renders the
stop in the list without a marker rather than inventing a position. `sequence` is always part of a
contiguous 1..N series (below), so a map can number its markers straight from it. `address` (added
by job 10, read live from the destination master like the coordinates) is the free-text line a
selected stop's detail panel shows next to its service window and order count. Drawing that map
is [job 10's](../../tms-overnight-v3/prompts/10_shipment_maps_and_stop_sequence.md) scope; this job
supplies the data it needs.

## Stops

Stops still follow assignments - `PLANNING_MANUAL_V1.md` section 7 is unchanged. Job 07 adds the
two assertions that make the rule verifiable instead of merely intended:

1. **Coverage** - `TripAssignmentService.requireStopsCoverAssignments`, run after every
   `refreshStops`: the set of destinations the trip stops at must equal the set its active
   assignments deliver to. This is the "a shipment should not silently lose a destination" rule,
   and it fires in the transaction that broke it.
2. **Sequence** - `Trip.assertStopSequenceIntegrity`, run at the end of every renumber: positions
   are exactly 1..N, each used once, one destination once.

Both raise `IllegalStateException`, not a 4xx. No request can ask for either state, so reaching one
is a defect in whatever mutated the trip; the honest answer is a rolled-back transaction and a 500,
not a shipment that looks fine and misses a delivery.

Neither is a database trigger. Migration V11's header rules out triggers carrying planning logic,
and `uq_trip_stop_trip_sequence` already covers the declarative half (uniqueness within a trip),
which leaves only contiguity and membership - both cheap in Java over data the caller already
holds.

## Route master interaction

**A master route is a template. A shipment is an instance of real orders. The link between them is
a suggestion.**

`tms.trip.route_id` is nullable and points at `tms.route` with the usual composite-FK tenant
guarantee. Migration V8 already framed a route as "a named, reusable sequence a planner can point a
Trip at later"; this is that pointer, and it is deliberately weaker than it looks. Three things it
does *not* mean, each enforced by absence rather than by a check:

- the shipment's stops are **not** required to equal the route's stops;
- they are **not** re-synchronised when the route master is later edited
  (`editingTheRouteMasterDoesNotRewriteTheShipment` proves it);
- a stop is **never** created from a route. `tms.trip_stop` always follows the trip's own active
  assignments.

`PUT /planning/trips/{id}/route` therefore does at most two things: record the route id, and - only
when the caller sets `applySequence` - reorder the stops the shipment **already has** into the
route's relative order. Destinations the route names but the shipment does not serve are ignored;
destinations the shipment serves but the route omits are kept, at the end. A route may only be
applied when it is active, in the caller's company, and departs from the same origin as the
shipment's planning run.

The alternative - making a planned shipment a materialised copy of a route - was rejected because
it makes the two drift silently and gives a master-data edit the power to rewrite a plan a human
already approved.

## Revalidation points

Every one of these re-checks from scratch rather than trusting what the board looked like:

| Operation | Capacity | Vehicle active | Same tenant | Double-booking | Order eligibility | Date/time |
|---|---|---|---|---|---|---|
| assign order | yes | live limits | FK + scope | - | `findAssignable` + origin/date | run's date |
| remove order | n/a | - | scope | - | - | - |
| move order | target's | live limits | FK + scope | - | target run's origin/date | target run's date |
| change vehicle | whole load vs new vehicle | `findAssignable` | scope | yes | - | departure vs planning date |
| apply route | - | - | route in company | - | - | route's origin vs run's |
| **confirm** | yes, then frozen | `findAssignable` | scope | (already held) | every order re-checked | departure vs planning date |

Two of those rows are new in job 07.

**Departure vs planning date** (`ShipmentTimeRules`). `planning_run.planning_date` is a plain date
and `trip.planned_departure_at` is an instant with an offset; nothing in the schema connects them,
and until this rule existed a planner could open a run for the 20th and depart a trip on the 25th.
The day is judged in **the company's own time zone** (`tms.company.time_zone`), never the server's
and never the offset the client happened to send: a depot in `America/Lima` planning the 20th means
the 20th in Lima, and judging by UTC would reject the last five hours of every planning day.
Refused as 400 - a departure on the wrong day is a malformed request, not a race.

**Orders re-checked at confirmation** (`PlanningRunService.requireOrdersStillFitRun`). Origin and
service date were checked when each order was assigned, and neither is immutable afterwards: an
order can be rescheduled while it sits on a draft trip. Confirmation is the moment the plan becomes
binding, so it is the last place to catch a shipment that would otherwise depart from the wrong
depot or on the wrong day. One batched lookup for the whole trip.

## Concurrency

Unchanged from `PLANNING_MANUAL_V1.md` section 4 - row lock for load changes, partial unique
indexes as the backstops, lock ordering by trip id everywhere. The route endpoint joins the
*version-checked* family (like vehicle change and cancellation) rather than the row-lock family:
it edits a field the caller read, so two planners who both loaded version 7 cannot both write it,
and the second is told to reload. `concurrentRouteChangesDoNotBothWin` asserts exactly one 200 and
no 5xx.

## Performance

A board of N shipments costs a fixed number of queries, not a function of N: the trips, one grouped
capacity sum, one grouped stop count, and the five batched lookups behind `ShipmentReferences`
(runs, origins, vehicles, carriers, routes). That record exists to make the rule impossible to
forget rather than merely documented - the assembler builds one per call and then formats rows out
of maps, so there is no code path on which rendering row N+1 can issue a query.
`boardQueryCountDoesNotGrowWithTheNumberOfTrips` compares a 1-trip board with a 5-trip one and
requires the statement counts to be equal.

## What was deliberately not added

- **Per-stop planned arrival/service times.** `tms.trip` has one optional departure and no
  travel-time model; a stored ETA would be inventing the routing that `CLAUDE.md` defers by
  decision (OR-Tools). V16 documents the same limitation for the double-booking rule.
- **A distance or duration copied from the master route.** Those are planner-entered hints on the
  corridor (V8); publishing them on a shipment would be publishing a figure nobody measured.
- **Stored load totals on `tms.trip`.** See "Derived, not stored".
- **An outbound integration endpoint.** This job makes the shipment *expressible*; publishing it is
  a separate scope with its own contract and versioning.
