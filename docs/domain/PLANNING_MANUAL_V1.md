# Manual planning V1

Step 10 (migration V11). This document is the contract for how a planner turns orders that are
ready for planning into trips, and what the backend guarantees while they do it.

Capacity has its own document: [`CAPACITY_MODEL.md`](CAPACITY_MODEL.md). The order lifecycle this
one plugs into is [`ORDER_LIFECYCLE_V1.md`](ORDER_LIFECYCLE_V1.md).

What a planned trip exposes as an outward-facing **Shipment** - its shipment number, the resolved
header, map-ready stops, the stop-integrity assertions and the master-route relationship - is
[`SHIPMENT_V2.md`](SHIPMENT_V2.md) (job 07, migration V19). It extends this document; every rule
below still holds.

**Not in this scope, by decision**: no solver or route optimisation (OR-Tools stays deferred), no
loading/dispatch/execution states, no EWM interaction, no live tracking. `planning_run.mode`
already distinguishes `MANUAL` from a future `AUTOMATIC`, which is the whole extent of the
forward compatibility V1 buys.

## 1. The flow

1. **Find the work.** `GET /planning/eligible-orders` lists orders in `READY_FOR_PLANNING` for the
   company, optionally filtered by origin, destination, service date or order number. Paginated,
   no order lines.
2. **Open a run.** `POST /planning/runs` for one origin and one planning date.
3. **Create trips.** `POST /planning/runs/{runId}/trips`, optionally with a vehicle from the start.
4. **Set the vehicle.** `PUT /planning/trips/{id}/vehicle` sets or swaps it, revalidating
   everything already on the trip.
5. **Assign / remove / move orders.** `POST /planning/trips/{id}/assignments`,
   `DELETE /planning/trips/{id}/assignments/{orderId}`,
   `POST /planning/trips/{id}/assignments/{orderId}/move`.
6. **Read capacity.** `GET /planning/trips/{id}/capacity` (or the summary embedded in every trip
   response and in the board).
7. **Order the stops.** `PUT /planning/trips/{id}/stops`.
8. **Confirm.** `POST /planning/runs/{id}/confirm` - or `POST /planning/runs/{id}/cancel` to
   discard the draft.

## 2. Aggregates

| Table | What it is |
|---|---|
| `tms.planning_run` | one planning session: company, `plan_number`, origin, planning date, mode, status, `version` |
| `tms.trip` | one vehicle's planned journey inside a run: trip number, vehicle, carrier, planned departure, status, frozen capacity, `version` |
| `tms.trip_stop` | the trip's ordered destination stops, with the service window envelope of what is delivered there |
| `tms.trip_order_assignment` | the explicit order-to-trip assignment: allocated load, `ACTIVE`/`REMOVED`, audit stamps |

A trip carries **no origin of its own**: it departs from its run's origin, so "trip and run agree
on company and origin" is structural rather than an invariant that could be violated. Its
`company_id` is denormalized from the run because a trip references company-scoped tables
(vehicle, carrier) and must carry the composite-FK tenant guarantee (`DATA_MODEL.md` rules 6-7).

A planning run stores **no counters**. Trip and assigned-order counts come from one grouped query
per page (`TripRepository.countByPlanningRunIds`,
`TripOrderAssignmentRepository.countByPlanningRunIds`). Unlike an order's header totals - which
are safe because the backend is the sole writer of the lines they summarise - a run's counts
change through a different aggregate on nearly every request, so storing them would buy one query
and cost a permanent drift risk.

## 3. Why an assignment aggregate, not `transport_order.trip_id`

A `trip_id` column on the order would be smaller and would make three things impossible:

1. **Partial assignment, ever.** `trip_order_assignment` carries the *allocated*
   `assigned_weight_kg`/`assigned_volume_m3`/`assigned_pallets`. Today they equal the order's own
   totals because V1 assigns whole orders (`whole_order = true`). The capacity service sums the
   assignment rows and never the order header, so the day an order is split across two trips,
   nothing in the capacity code changes - a second row with smaller numbers is all it takes. The
   optional `trip_order_line_allocation` table the brief mentions is deliberately **not** created:
   it would be an empty table with no writer. When line-level allocation arrives it hangs off
   `trip_order_assignment.id` + `transport_order_line.id` without changing anything described here.
2. **History.** Removing an order closes its assignment (`status = 'REMOVED'` plus
   `removed_at`/`removed_by`/`removal_reason`); a move closes the source row and opens a new one.
   Both records survive, so "this order was on trip 3 and was moved to trip 5 at 11:20 by X" stays
   answerable. A `trip_id` column would be overwritten and the previous plan would be gone.
3. **A database-level concurrency invariant.** See below.

## 4. Concurrency

Two mechanisms, each covering what the other cannot.

**The trip's row lock.** Every mutation begins with
`TripRepository.findByIdAndCompanyIdForUpdate` / `findByIdAndCompanyIdForAssignment`
(`SELECT ... FOR UPDATE`), so the "read the current load → decide it fits → write the assignment"
sequence cannot interleave with another planner doing the same on the same trip. Two planners
filling one truck therefore serialise, and the second one sees the first one's load. A move takes
**both** locks, ordered by trip id, so two opposite moves between the same two trips cannot
deadlock.

**The partial unique index.** A row lock on trip A says nothing about trip B, so two planners
assigning *the same order* to *different trips* would both pass their own check. That case is
refused by the database:

```sql
CREATE UNIQUE INDEX uq_trip_order_assignment_open_whole_order
    ON tms.trip_order_assignment (order_id)
    WHERE status = 'ACTIVE' AND whole_order;
```

The index is partial in both directions: closed history rows are outside it (so a reassignment is
legal), and `whole_order = false` rows are outside it (so future split allocation is not blocked
by the invariant that guards V1). The loser of the race gets a 409 telling them to reload - or a
400 if the winner had already committed and the order was no longer eligible when the loser looked.
Both are refusals; a second success is impossible.
`PlanningConstraintIntegrationTest.concurrentAssignmentOfTheSameOrderBlocksThenFails` proves the
blocking behaviour against two real transactions, and
`PlanningApiIntegrationTest.concurrentAssignmentProducesExactlyOneAssignment` proves the API's
behaviour under two concurrent callers.

**Statement order matters.** A partial index cannot be `DEFERRABLE`, so a move must close the
source assignment *before* inserting the target one, and must flush in that order -
`TripAssignmentService.close` calls `saveAndFlush` for exactly this reason. Hibernate flushes
insertions before updates, so without that explicit flush the new row would collide with a row this
same transaction has already logically closed. (V10 hit the same flush-ordering trap and solved it
with a deferrable constraint; here the statement order is the fix.)

**Versions.** `planning_run.version` and `trip.version` are checked explicitly against the
caller's value before anything changes (rule 11 in `DATA_MODEL.md` section 13), on the operations
where a planner edits a field they read: trip vehicle, trip cancel, run confirm, run cancel, and
trip creation (which presents the *run's* version). Assignment operations deliberately take no
version - they are covered by the row lock and the uniqueness invariant, and requiring one would
mean any planner's assignment invalidated every other planner's open board. They do *bump* the
trip's version (`PESSIMISTIC_FORCE_INCREMENT`), so a vehicle change from a board that has since
been filled is refused rather than applied against a load the caller never saw.

## 5. Eligibility: what may go on a trip

An order may be assigned only when **all** of these hold, each refused with 400 and a message
naming the reason:

- it belongs to the caller's company (guaranteed twice: the company-scoped lookup, and the
  composite foreign key `fk_trip_order_assignment_order_company`);
- its status is `READY_FOR_PLANNING`;
- its origin is the run's origin;
- its service date **equals** the run's planning date.

The date rule is deliberately equality rather than a range: a run plans one day, and an order
whose date does not match belongs on a different run. Re-dating an order is an Orders operation,
not something planning does silently. The trade-off - a late backlog order must be re-dated before
it can be planned - is accepted for V1 because the alternative (planning "everything up to this
date") makes it impossible to answer what a given day's plan actually promised.

Assignment is also refused (409) when the order already has an open assignment; the message points
at `move` rather than telling the planner to remove and re-add.

## 6. State rules

**Planning run**: `DRAFT` → `CONFIRMED`, or `DRAFT` → `CANCELLED`.

- `DRAFT` is the only state in which anything below it may change.
- `CONFIRMED` locks the run and the *plan* of every trip in it. Terminal for the run itself: a run
  is a planning artefact, and what happens next happens to its trips, not to it.
- `CANCELLED` cancels every trip and returns every order to `READY_FOR_PLANNING`, so no order is
  left stranded in `PLANNED` with no trip to run it.

**Trip**: `DRAFT` → `CONFIRMED` (only through its run's confirmation), and from there through the
execution lifecycle — `READY_FOR_DISPATCH` → `IN_TRANSIT` → `COMPLETED`, with `CANCELLED`
reachable from every state before departure. The transition table, the actual times and the
`planning.trip:execute` authority are documented in
[`TRIP_EXECUTION_V1.md`](TRIP_EXECUTION_V1.md); this document stops where the plan does.

- A confirmed trip refuses assignment, removal, move and vehicle change - all with 409 and the
  trip's current status in the message. That has not changed: execution never edits *what* a
  shipment carries, only what happened to it.
- Cancellation is the one exception, and V25 widened it: a confirmed or ready trip may still be
  cancelled (with a mandatory reason, and it publishes `SHIPMENT_CANCELLED`), because a shipment
  that will not run has to be withdrawable before the truck leaves. Cancelling any trip releases
  its orders, exactly like cancelling the run does.

**Confirmation revalidates everything** rather than trusting the board the planner was looking at,
because minutes may have passed. Each trip in turn (locked while it is checked) must have a
vehicle, a planned departure, a vehicle that is *still* active and available, at least one order,
and a load that fits. Its stop list is re-synchronised, then its capacity is frozen. If any trip
fails, the whole confirmation rolls back - a plan is confirmed as a unit.

## 7. Stops follow assignments

`tms.trip_stop` is a planning-instance stop, unrelated to the master `tms.route_stop`: a trip in
V1 is not required to follow any master route.

Stops are maintained by the backend, never sent by a client:

- assigning an order to a destination the trip does not yet serve **appends** a stop;
- removing the last order for a destination **removes** its stop;
- everything else keeps the planner's ordering - the one thing manual planning exists to let them
  do - and is renumbered 1..N with no gaps;
- each stop's `service_window_start`/`_end` is the **envelope** (earliest requested start, latest
  requested end) of the orders delivered there, and null when none of them asked for a window. An
  envelope, not an intersection: V1 has no time-feasibility solver, so "the requests here span this
  range" is a statement the system can stand behind, while "here is a feasible slot" would not be.

`PUT /planning/trips/{id}/stops` reorders. The submitted list must be exactly the destinations the
trip currently serves; anything else is refused with 400, because a stop nothing is delivered at is
not a stop.

## 8. Order lifecycle interaction

Planning owns the two transitions Orders left to it
(`docs/overnight/09_ORDERS.md` section 8, point 2), through `OrderPlanningPort`:

| Planning action | Order transition |
|---|---|
| assign | `READY_FOR_PLANNING` → `PLANNED` |
| remove, trip cancel, run cancel | `PLANNED` → `READY_FOR_PLANNING` |
| move between trips | none - the order changes trip, it does not return to the pool |

`OrderPlanningService` (in `orders.application`, not in `planning`) decides whether a transition is
legal; planning decides when to ask. That is also why this port is implemented in the application
layer while `OriginLookupPort` is implemented by an infrastructure adapter: this one carries
business rules, that one is a repository translation.

## 9. Authorization

| Endpoint | Required |
|---|---|
| `GET /planning/eligible-orders` | `planning.plan:read` **and** `orders.order:read` |
| `GET /planning/runs`, `POST /planning/runs` | `planning.plan:read` / `planning.plan:manage` |
| `GET /planning/runs/{id}` | `planning.plan:read` **and** `planning.trip:read` |
| `POST /planning/runs/{id}/confirm`, `.../cancel` | `planning.plan:manage` **and** `planning.trip:manage` |
| `POST /planning/runs/{runId}/trips` | `planning.trip:manage` |
| `GET /planning/trips/{id}` | `planning.trip:read` **and** `orders.order:read` |
| `GET /planning/trips/{id}/capacity` | `planning.trip:read` |
| every other trip endpoint | `planning.trip:manage` |

The rule behind the table: a response never smuggles data the caller could not have read from the
module that owns it, and an operation that changes trips requires the trip permission even when it
is triggered at run level. Every role that holds `planning.plan:read` already holds
`planning.trip:read` and `orders.order:read` (migrations V3/V5), so this costs no existing role
anything - it stops a *future* narrower role from being over-privileged by accident.

## 10. Performance

- Eligible orders: paginated, projected from the order header, never touching a line.
- Planning board (`GET /planning/runs/{id}`): the run, one query for its trips, one grouped query
  for every trip's load, one batched vehicle lookup, one batched origin lookup. Constant in the
  number of trips.
- Trip detail: adds one assignment query, one batched order lookup and one batched destination
  lookup. Still no order lines.
- Run list: one page query plus two grouped count queries and one batched origin lookup.

`ix_trip_order_assignment_trip_active` is partial on `status = 'ACTIVE'`, so the index the hot path
scans does not grow with history.
