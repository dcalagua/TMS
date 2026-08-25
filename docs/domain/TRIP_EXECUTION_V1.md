# Trip Execution V1

How a *planned* trip becomes an *operated* one.

Migrations: `V25__trip_execution_lifecycle.sql` (the trip),
`V27__trip_stop_execution_and_transport_events.sql` (its stops, the timeline and the problems).
Code: `planning.domain.TripStatus`, `planning.domain.StopExecutionStatus`, `planning.domain.Trip`,
`planning.domain.TripStop`, `planning.domain.TransportEvent`, `planning.domain.TripException`,
`planning.application.TripExecutionService`, `planning.application.TripStopExecutionService`,
`planning.application.TripExceptionService`, `planning.application.TransportEventRecorder`,
`planning.application.ShipmentEventPublisher`.
UI: `pages/trips/TripsPage.tsx`, `pages/trips/TripWorkspacePage.tsx`,
`pages/trips/TripTimeline.tsx`, `pages/trips/TripProblemDrawer.tsx`.

Companion documents: [`PLANNING_MANUAL_V1.md`](PLANNING_MANUAL_V1.md) for how a trip is built,
[`SHIPMENT_V2.md`](SHIPMENT_V2.md) for what a trip looks like to an external system,
[`CAPACITY_MODEL.md`](CAPACITY_MODEL.md) for which capacity a trip reports in which state.

---

## 1. The problem this solves

Until V24 a trip had three states: `DRAFT` while its planning run was open, `CONFIRMED` once the
run was confirmed, `CANCELLED` when a planner discarded a draft.

That is a complete *planning* lifecycle and an empty *execution* one. Nothing recorded that a
truck had been loaded, that it had left, or that it had come back — so "which shipments are still
out there right now?" was not a question the database could answer, and "how late do we actually
leave?" was not a question anyone could ask.

## 2. The states

```
DRAFT ──confirm──► CONFIRMED ──ready──► READY_FOR_DISPATCH ──dispatch──► IN_TRANSIT ──complete──► COMPLETED
                        │                       │
                        └───────cancel──────────┴──────cancel───► CANCELLED
   │
   └──────────────────────────cancel───────────────────────────►
```

| State | Meaning | What may still change |
|---|---|---|
| `DRAFT` | The run is open and the planner is still building. | Everything: vehicle, orders, stop order, route. |
| `CONFIRMED` | The plan is binding. Capacity is frozen onto the trip. | Only execution facts. |
| `READY_FOR_DISPATCH` | Loaded, documented, waiting for a driver. | Only execution facts. |
| `IN_TRANSIT` | The vehicle has left. `actualDepartureAt` says when. | Only completion. |
| `COMPLETED` | Terminal. The trip finished and the vehicle is released. | Nothing. |
| `CANCELLED` | Terminal. Its orders went back to the eligible pool. | Nothing. |

`CONFIRMED`, `READY_FOR_DISPATCH`, `IN_TRANSIT` and `COMPLETED` are the **committed** states:
`TripStatus.isCommitted()`, and the four listed in migration V25's
`ck_trip_confirmed_is_complete`.

### Why five states and not seven

The brief that produced this feature named `DISPATCHED` and `IN_TRANSIT` as separate steps. They
are one state here, because nothing distinguishes them: **dispatching *is* the departure**. A
`DISPATCHED` row and an `IN_TRANSIT` row would carry exactly the same columns with no way to tell
which is which — a state that exists only to be named.

### Why there is no way back

There is no un-dispatch, no reopen, no move from `COMPLETED` to anything. Undoing a departure
would mean the actual times are editable, and the entire value of an actual time is that it is
not. A trip recorded wrongly is corrected the way every other historical mistake in TMS is: with
a new record and an audit trail, not by rewriting the old one.

### Why `IN_TRANSIT` cannot be cancelled

Once the vehicle has left, "this trip did not happen" has stopped being true. An aborted run is a
trip that **completed** badly, and representing that properly needs a per-stop delivery outcome —
delivered, partial, refused, with quantities and a reason — which V1 deliberately does not have
(see §7). Allowing cancellation here would also silently return orders to the eligible pool that
may already be sitting on a customer's dock.

## 3. Where the transition table lives

In `planning.domain.TripStatus`, as data:

```java
TripStatus.CONFIRMED.canTransitionTo(TripStatus.READY_FOR_DISPATCH)  // true
TripStatus.IN_TRANSIT.canTransitionTo(TripStatus.CANCELLED)          // false
```

One fact, three enforcement layers, in this order:

1. **`TripExecutionService`** asks `canTransitionTo` and refuses with a `409` naming both states —
   the sentence a dispatcher reads.
2. **`Trip`** asserts it again inside every mutator and throws `IllegalStateException` — a defect
   report for a caller that skipped step 1, not a user-facing message.
3. **PostgreSQL** enforces what each state *guarantees* (`ck_trip_ready_requires_timestamp`,
   `ck_trip_in_transit_requires_departure`, `ck_trip_completed_requires_completion`,
   `ck_trip_execution_times_ordered`), so an incoherent row is impossible even from raw SQL.

The **frontend holds no copy at all**. `TripView.allowedTransitions` carries the answer to the
browser, and `TripWorkspacePage` renders buttons from it. A second transition table in TypeScript
is a table that goes out of date.

## 4. Actual times

Three columns, each written once by its own transition:

| Column | Set by | Meaning |
|---|---|---|
| `ready_at` / `ready_by` | ready | Loaded and waiting. |
| `actual_departure_at` / `dispatched_by` | dispatch | When the vehicle really left. |
| `actual_completion_at` / `completed_by` | complete | When the trip finished. |

Three rules govern all of them.

**They are operator-supplied, not `now()`.** `TripExecutionRequest.occurredAt` is optional: omit it
and the server stamps its own clock (a dispatcher acting live), or send it to record something
that happened earlier. A dispatcher who reaches a keyboard at 09:05 must be able to record the
08:40 departure they watched, or the actual times describe the office rather than the fleet.

**They never overwrite the plan.** `plannedDepartureAt` keeps meaning what the plan asked for.
The gap between it and `actualDepartureAt` is the delay — the number the operation is actually
judged on — and a dispatch that rewrote the plan would erase the only evidence there was one.

**They are validated, in both directions.** A time in the future is refused (with a five-minute
tolerance, because the browser sends the clock of the machine it runs on). A time before the step
it follows is refused. `ck_trip_execution_times_ordered` is the database's own copy.

**What is *not* stored** is the moment the button was pressed. `tms.audit_event` (V22) already
records that, with the actor and the request that carried it, for every one of these transitions.
A second copy would be a second source of truth for the same fact.

## 5. The actions

| Endpoint | Authority | From | To |
|---|---|---|---|
| `POST /planning/trips/{id}/ready` | `planning.trip:execute` | `CONFIRMED` | `READY_FOR_DISPATCH` |
| `POST /planning/trips/{id}/dispatch` | `planning.trip:execute` | `READY_FOR_DISPATCH` | `IN_TRANSIT` |
| `POST /planning/trips/{id}/complete` | `planning.trip:execute` | `IN_TRANSIT` | `COMPLETED` |
| `POST /planning/trips/{id}/cancel` | `planning.trip:manage` **or** `:execute` | `DRAFT`, `CONFIRMED`, `READY_FOR_DISPATCH` | `CANCELLED` |
| `GET /planning/trips` | `planning.trip:read` | — | — |

### `planning.trip:execute` is a new permission

Building a plan and running it are different jobs. A dispatcher who moves trips through their day
must not thereby be able to reopen the plan and reassign orders (`planning.trip:manage`), and the
reverse is just as true. V25 seeds `planning.trip:execute` and grants it to `ORGANIZATION_ADMIN`,
`COMPANY_ADMIN` and `PLANNER` — TMS ships no dispatcher role yet, so nobody loses anything today.
A customer that wants the split creates a custom role, which is what the `(role, permission)`
model is for. `VIEWER` does not get it: V3 granted `VIEWER` every `read` permission and nothing
else, and `execute` is not a read.

### Every action

- takes the trip's row lock first (`findByIdAndCompanyIdForUpdate`) — the same serialization point
  the rest of the planning module uses, so two dispatchers cannot both read `READY_FOR_DISPATCH`
  and both write a departure;
- is company-scoped by a `CompanyScope` resolved server-side, never by an id from the client;
- checks the caller's `version` against the persisted row;
- re-validates the vehicle (`ready`, `dispatch`) — confirmation may have been yesterday and the
  truck may have gone into maintenance since;
- writes its outbox event and its audit event **in the same transaction**;
- is idempotent.

### Idempotency, and why it is checked before the version

A trip already in the state an action would produce is returned unchanged: no second event, no
second audit row, no error. That check runs **before** the version check, deliberately — a
dispatcher whose request timed out and who pressed the button again is holding a stale version *by
definition*, and answering "someone else changed this, reload" to a retry of an operation that
already succeeded is a worse lie than accepting it. Nothing is lost: the intent was reached, and
reaching it twice is not a different outcome. Every other conflict — a genuinely competing edit,
an illegal move — still fails.

### Cancelling a confirmed trip

Two things follow from a trip having been confirmed before it was cancelled:

1. **A reason is required.** Discarding a sketch needs no explanation; withdrawing a shipment a
   carrier was told about does, and the reason is the only record of why.
2. **A `SHIPMENT_CANCELLED` event is published.** A partner handed a confirmed shipment has to be
   told it is not happening.

Both are conditional on `confirmedAt` being set, not on the current status — by the time either
runs, the status is already `CANCELLED`.

In every case the orders go back to `READY_FOR_PLANNING` and can be replanned.

## 6. What a partner sees

`tms.shipment_outbox_event` gains three sources it did not have (V25):

| Event | Written by |
|---|---|
| `SHIPMENT_CONFIRMED` | `PlanningRunService.confirm` |
| `SHIPMENT_READY` | `TripExecutionService.markReadyForDispatch` |
| `SHIPMENT_DISPATCHED` | `TripExecutionService.dispatch` |
| `SHIPMENT_COMPLETED` | `TripExecutionService.complete` |
| `SHIPMENT_CANCELLED` | `TripService.cancel`, for a trip that was confirmed |
| `SHIPMENT_CHANGED` | *nothing* — the committed states are still locked against edits |

`ShipmentEventPublisher` is the single place that pairs an outbox row with its audit event, shared
by confirmation and by the execution transitions, so the two can never drift.

The `ShipmentPlan V1` wire contract changes **additively**: `status` gains three values, and
`readyAt` / `actualDepartureAt` / `actualCompletionAt` are new nullable fields. A partner that
filters on `CONFIRMED` sees exactly what it saw before — a shipment that moves to `IN_TRANSIT`
simply stops matching that filter, which is what "give me what is still only planned" should mean.
See [`../integrations/OUTBOUND_SHIPMENT_V1.md`](../integrations/OUTBOUND_SHIPMENT_V1.md).

## 7. The Trips screen

`/trips` is the execution board: every trip of the company, indexed by **day** rather than by
planning run.

A dispatcher's question is "what is leaving today", which spans every run that produced a trip for
that date — and `PlanningBoardPage` can only ever show one run. That is why this screen exists
next to the planning board instead of inside it, and why the row is the **shipment number** rather
than "trip 2 of PL-17", which means nothing outside its own board.

`/trips/{id}` is the workspace: header, lifecycle timeline, carrier/vehicle, capacity, stops with
their map, and orders. A full route and not a modal, deliberately — a dispatcher stays inside one
trip for minutes at a time, and `/trips/{id}` is what gets pasted into a chat when someone asks
where a truck is. Destructive and irreversible actions (complete, cancel) go through SweetAlert2;
cancellation asks for its reason *inside* the confirmation, so there is no round trip to a `400`.

## 8. Stop execution

V25 closed by saying `tms.trip_stop` would stay a planning row. V27 changes that, because the two
questions a dispatcher actually spends the day on are per stop and neither is answerable from a
trip that is merely `IN_TRANSIT`:

- *"customer 4 says nothing arrived — did we get there?"*
- *"we are closing the day; which deliveries did not happen, and why?"*

```
PENDING ──arrive──► ARRIVED ──service──► IN_SERVICE ──complete──► COMPLETED
   │                   │  └──────────────complete──────────────►
   ├──skip──► SKIPPED  └──fail──► FAILED ◄──fail──┘
   └──fail──► FAILED
```

`IN_SERVICE` is optional and `ARRIVED → COMPLETED` is legal: a company that does not record
service starts must not be forced through a button that means nothing to it. It exists at all
because service *duration* is already a modelled planning input (`route_stop.service_time_override_minutes`,
V24), so recording when service really started is what lets the planned figure be checked.

`SKIPPED` and `FAILED` are two different facts and the UI must never merge them:

| Outcome | Meaning | Actual times |
| --- | --- | --- |
| `SKIPPED` | Never attempted, by decision — the customer cancelled at 06:00 | None at all, enforced by CHECK |
| `FAILED` | Attempted and not served — refused at the dock, address not found | Whatever it had; arrival optional |

Both are terminal, both require a **typed reason**, and both open a `TripException` in the same
transaction as the transition. That is what makes *"how many deliveries did we miss last week, and
why"* a query instead of a reading exercise over free text.

Stops are worked **only while the trip is `IN_TRANSIT`**. An arrival recorded against a shipment
that has not been dispatched is not an early arrival, it is a mistake — and letting it through
would make `actualDepartureAt` stop meaning what it says.

### A trip is not finished while a stop is outstanding

`complete` refuses while any stop is still `PENDING`, `ARRIVED` or `IN_SERVICE`, naming them. There
is **no override flag**: a stop that genuinely should not be counted is skipped, which takes a
typed reason and is the honest way to say so. A trip closed over three stops nobody ever touched is
a day that *looks* finished, and stopping that from being recordable is the whole point.

The rule is a service rule, not a CHECK — no CHECK can see another table's rows. `Trip.complete`
asserts it again as a last line of defense, the same two-layer shape the transition table uses.

### No actor columns on `tms.trip_stop`

V25 paired every trip-level actual time with the `app_user` who reported it. V27 deliberately does
not, because there is now a better place: every stop transition writes a `tms.transport_event` in
the same transaction, and that table is append-only, company-scoped and actor-stamped. Five more
actor columns plus five more pair CHECKs would be a second copy of a fact the log already holds.

## 9. The timeline — `tms.transport_event`

One append-only row per execution fact, trip-level and stop-level in the same table so that "show
me this shipment's day" is one ordered read and not a merge of four. **Not event sourcing**: the
trip and its stops stay the source of truth and are never rebuilt by replaying it.

| Family | Entries | Written by |
| --- | --- | --- |
| Trip | `TRIP_CONFIRMED`, `TRIP_READY`, `TRIP_DISPATCHED`, `TRIP_COMPLETED`, `TRIP_CANCELLED` | `ShipmentEventPublisher`, beside the outbox row and the audit row |
| Stop | `ARRIVED_AT_STOP`, `SERVICE_STARTED`, `STOP_COMPLETED`, `STOP_SKIPPED`, `STOP_FAILED` | `TripStopExecutionService`, one per transition |
| Either | `EXCEPTION_REPORTED`, `EXCEPTION_RESOLVED` | `TripExceptionService` |

Each row carries `event_time` (when it happened, operator-supplied) *and* `recorded_at` (when it
was typed). The gap between the two is the interesting part: an arrival backdated six hours is a
different fact from one entered as it happened, and the workspace says so rather than making a
supervisor go looking. Position columns exist and are always null — nothing reports one, and
telematics is deferred by decision.

**Why not `tms.audit_event`.** The two answer different questions for different readers.
`audit_event` is a security and compliance trail in the vocabulary of CREATE/UPDATE/CANCEL; this is
an operational trail a dispatcher reads inside the trip, in the vocabulary of a delivery day, with
notes and a position that have no business in a compliance log. Stop transitions therefore write
**only** a transport event: two records of one fact, in two vocabularies, is two records that can
drift. The trip-level transitions keep their audit rows because they already had them and because
"somebody confirmed this shipment" is a compliance fact as well as an operational one.

**Why not `tms.shipment_outbox_event`.** That is an outbound integration queue with a delivery
cursor, whose rows exist to be consumed once. These exist to be read forever.

## 10. Problems — `tms.trip_exception`

The one mutable table of the three, and `OPEN`/`RESOLVED` is the only thing that earns it that: an
append-only log cannot answer "what went wrong today that nobody has closed out", because a
reported entry and a resolved entry are two rows and joining them on every screen refresh is not a
design.

Catalogue: `TRAFFIC_DELAY`, `VEHICLE_BREAKDOWN`, `CUSTOMER_CLOSED`, `DELIVERY_REJECTED`,
`ADDRESS_NOT_FOUND`, `DELIVERY_FAILED`, `OTHER`. Small on purpose. The four delivery-shaped ones
must name a stop (enforced by CHECK); `OTHER` requires notes, so choosing it costs a sentence and
choosing a typed value does not — that asymmetry is the only thing that keeps a catalogue like this
from collapsing into a single value.

**Operational only.** A rejected payload, a failed migration or a 500 is not one of these: those
belong to logs, `tms.integration_request` and the error handler. Mixing the vocabularies would
produce a dispatcher's screen full of stack traces.

Two states and nothing else — no assignment, no severity, no escalation ladder, no SLA clock,
because no rule in TMS reads one. Resolving requires a note: "RESOLVED" with no explanation records
a click, not an outcome. An open problem does **not** block completing the trip; only an
unresolved *stop* does. A traffic delay that is still open at 18:00 is normal, and refusing to
close the day over it would teach dispatchers to stop reporting them.

Reporting stays available on any trip past `DRAFT`, cancelled and completed included: these are
written up when somebody has time, which is rarely while the truck is still out.

## 11. The workspace

`/trips/{id}` now shows, beside the header and the lifecycle card:

- each **stop** with its outcome badge, its actual times, its dwell time and the actions the server
  still allows (`allowedExecutionTransitions` — the screen derives nothing from the status itself,
  exactly as it does not derive the trip's own buttons);
- the **problems** list, with a *Report a problem* action and a *Close* action per open row;
- the **timeline**, read-only by construction because the log is append-only on the server.

Skipping a stop, failing one and reporting a trip-level problem share one drawer: they differ only
in which stop the problem attaches to and in what the button says.

## 12. Deliberately not in V1

- **No planned arrival or departure per stop.** There is nothing to put in them: TMS has no ETA
  engine and route optimisation is deferred by decision. What a stop plans is its service *window*
  and, through its master route, its service *duration* — both real, both already stored. Two
  columns holding an arrival nobody computes would read as a plan the actual times could be judged
  against, and there would be no such plan.
- ~~**No proof of delivery, signature, photo or per-order delivered quantity.**~~ **Closed by
  migration V28** — see [`PROOF_OF_DELIVERY_V1.md`](PROOF_OF_DELIVERY_V1.md). A `COMPLETED` stop
  still means "the vehicle served this destination", and that is why the stop's label still reads
  *Served* and not *Delivered*: what was handed over is now recorded per **order**, in
  `tms.order_delivery`, beside the stop's status rather than inside it. Quantities remain out of
  scope — `PARTIAL` carries a mandatory note instead.
- **No per-stop execution in the outbound Shipment contract.** Partners receive trip-level events
  (`SHIPMENT_*`) plus `DELIVERY_RESULT_RECORDED` (V28), which is order-level rather than stop-level.
  Publishing the *stop* outcomes themselves is still a contract decision with its own versioning
  question, not a side effect of recording them internally.
- **No cross-trip exceptions screen.** The problems of one trip are on that trip. "Every open
  problem in the company" is a real supervisor question and the index for it exists
  (`ix_trip_exception_company_open`); the screen is the next increment, not this one.
- **No order-level delivery *status*.** `OrderStatus` stops at `PLANNED`. Completing a trip leaves
  its orders `PLANNED` rather than inventing a `DELIVERED` they have no lifecycle for; that
  belongs to the orders module, with its own transitions and its own migration. Migration V28
  records the delivery **result** on the trip side (`tms.order_delivery`) without touching that
  status — the two are different things, and the order's own lifecycle is still **the known gap to
  close next**, now with a table ready to feed it.
- ~~**No GPS, no telematics, no live position.**~~ **Partly closed by migration V29** — see
  [`TRACKING_V1.md`](TRACKING_V1.md) and ADR-007. TMS now owns a normalised position contract, its
  storage and its sampling policy, and draws a trail on the trip; it still ships **no vendor
  adapter**, and ETA, geofencing and automatic arrival detection remain deferred.
  Nothing about this document changes as a result, because **positions inform people and people
  record facts**: no status is derived from a position, no stop is closed by one, and
  `transport_event`'s own latitude/longitude columns stay unused (§9). `IN_TRANSIT` still means "a
  person said it left" — a fact TMS owns, not a position it guesses.
- **No cost, no settlement, no freight rating.** Not in scope for execution V1.
