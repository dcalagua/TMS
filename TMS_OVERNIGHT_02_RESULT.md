# TMS OVERNIGHT JOB 02 RESULT

RESULT:
PASS

STOP_CHAIN:
false

## Objective

Give the transport order a lifecycle that reaches the end of the road: dispatch, the three delivery
outcomes, and a second attempt after a failure. Drive every one of those transitions from a domain
fact rather than from a button, integrate delivery result and POD into it, and prove the whole
vertical with tests including idempotency and concurrency.

## Initial diagnosis

Working tree clean at `f666d63`. `OrderStatus` carried four values and stopped at `PLANNED`.
`TripExecutionService.complete` carried a comment saying so in as many words: *"Leaves the orders it
carried PLANNED. There is no delivery status for them to move to."*

Three consequences, and the second is a functional defect rather than a missing feature:

1. An order on a departed vehicle read `PLANNED` - indistinguishable from one in a draft trip for
   next Tuesday.
2. **An order whose delivery was refused stayed `PLANNED` forever**: not plannable, so it never
   returned to the pool; not cancellable, because `cancel` refuses a planned order; not
   deliverable, because its trip was closed. A customer waiting for a redelivery was invisible to
   the system that owed them.
3. An order delivered in full stayed `PLANNED` too, so "what is outstanding" needed a join.

## Existing functionality reused

Everything downstream already existed and none of it was rebuilt:

- **`OrderDelivery` / `DeliveryResult`** (V28) already record delivered, partial, rejected, failed
  and not-attempted per order per stop, with POD evidence behind `EvidenceStoragePort`.
- **`OrderFulfillmentPort` / `OrderFulfillmentStatus`** already derive "what happened at the dock"
  from those rows, batched to avoid an N+1. This job **consumes** that port and leaves it untouched.
- **`OrderPlanningPort`** already ran the right way round - planning asks orders to change an order's
  state, never the reverse. Two methods were added to it in that same direction.
- `TripStatus`'s three-layer pattern (domain table, entity assertion, DB CHECK) was copied rather
  than reinvented.

## Architecture/design

**The tension this job had to resolve.** `OrderFulfillmentStatus` carries an explicit, well-argued
objection to exactly what JOB 02's brief asks for: a stored copy of the delivery fact "would be a
second copy of that fact, kept in step by whoever remembered to - and the day the two disagreed
there would be no way to say which was right."

That objection is correct, and bulldozing it would have been the wrong call. It is answered on two
grounds, recorded in **ADR-009**:

1. **These states are not the delivery fact; they are its lifecycle consequence.** The two enums
   answer different questions - "what happened at the dock" versus "what may be done next" - and
   three of the four new states cannot be derived from delivery rows at all. An order on a departed
   vehicle has no delivery row. An order with nothing recorded is not the same as one nobody
   planned. "Back in the pool for a second attempt" is a decision a person made.
2. **Drift is prevented structurally, not by discipline.** The status is recomputed from the
   delivery rows inside the same transaction as every change to them - at dispatch, at close-out,
   and again at every correction keyed afterwards. The recording window deliberately stays open
   after completion (the signed notes come back at 18:40), and the third call is what makes that
   safe. There is no moment at which the rows and the status exist and disagree.

`OrderFulfillmentStatus` was **not** replaced, not stored, and not changed.

**Where each rule lives.** The module boundary is preserved and runs the direction it already ran:

    TripExecutionService  ──▶  OrderExecutionPropagator  ──▶  OrderPlanningPort.closeOut(fulfilment)
    (planning: owns the fact)  (planning: knows which orders)   (orders: decides what it means)

`OrderExecutionPropagator` exists as its own collaborator because three call sites in two unrelated
services need it; put in either, the rule would be stated twice.

## Database migrations

**`V36__order_execution_lifecycle.sql`** - the next real number after V35. No applied migration was
touched.

- widens `ck_transport_order_status` to the eight-value vocabulary;
- adds `ix_transport_order_plannable`, a **partial** index on `(company_id, service_date)` where
  status is `READY_FOR_PLANNING`. It matters more now than before: with orders reopened for second
  attempts, the plannable pool is a small live set inside a table that keeps every order the company
  ever took, and the partial index keeps the scan proportional to outstanding work rather than to
  history;
- widens `ck_audit_event_action` with `ORDER_REOPENED`;
- **no data migration**, and the header says why: every existing row is in one of the four original
  states, all of which survive unchanged. Back-filling an order that was `PLANNED` on a completed
  trip would mean inventing a delivery outcome nobody recorded, and guessing `DELIVERED` over
  historical rows is the comfortable lie this schema refuses elsewhere.

## Backend changes

- `OrderStatus` - four new states, an explicit transition table, and the predicates the services and
  the UI read (`isEditable`, `isCommitted`, `isPlannable`, `isClosedOut`, `isReopenable`,
  `isTerminal`).
- `TransportOrder` - `markInExecution`, `closeOut`, `reopenForPlanning`, each asserting the table
  through a private `transitionTo`. The two write methods return whether anything moved, which is
  what makes idempotency cheap for the caller. The older transitions deliberately keep their
  existing shape - retrofitting assertions onto paths already covered by tests would be a behaviour
  change with no benefit this job needs.
- `OrderPlanningPort` - `markInExecution` and `closeOut(fulfilment)`.
- `OrderPlanningService` - both implemented under a **row lock**, both idempotent, with
  `closureFor` as the exhaustive fulfilment→status mapping (no default, so a new fulfilment value
  cannot fall through silently). `backlogTotals` extended.
- `OrderService` - `cancel` now also refuses `IN_EXECUTION` and `DELIVERED`; new
  `reopenForPlanning`, audited as `ORDER_REOPENED` with the status it was reopened from.
- `OrderExecutionPropagator` - new, in planning.
- `TripExecutionService` - dispatch and complete propagate; the stale comment on `complete` was
  replaced with what it now does.
- `TripDeliveryService` - a delivery recorded on a completed trip re-derives the order's status.
- `OrderBacklogTotals` - counts the committed states as leaves and derives `planned()` from them, so
  the KPI identity `INPUT = PLANNED + UNPLANNED` still holds and the planned-rate did **not** fall
  the day the execution states arrived. That was a real regression risk and it is covered.
- `TransportOrderRepository` - `findByIdAndCompanyIdForUpdate`.
- `AuditAction` - `ORDER_REOPENED`.
- `OrderController` - `POST /orders/{id}/reopen`, documented in OpenAPI.

## Frontend changes

- `ordersApi.ts` - the eight-value `OrderStatus`, `REOPENABLE_ORDER_STATUSES`, and
  `reopenOrderForPlanning`.
- `OrdersPage.tsx` - tones for the new states (the two shortfalls are `overdue`, not `cancelled`:
  they are work someone still owes a customer, which is what a list has to surface), a "Reabrir para
  planificar" action that asks for the reason through `promptDialog`, and cancel hidden for
  `IN_EXECUTION` / `DELIVERED`.
- `enums.ts` / `i18n.ts` - Spanish labels for the four new states and the reopen flow, with English
  parity in the correct dictionaries.

## Security and tenant isolation

Every new path is company-scoped: both port methods take `companyId` and load through
`findByIdAndCompanyIdForUpdate`, which carries the predicate in the query rather than filtering
after loading. `POST /orders/{id}/reopen` requires `orders.order:manage`, the same authority as
cancel.

No new entity and therefore no new tenancy question. Existing isolation tests continue to pass, and
the smoke run's two isolation steps (12, 13) run before the new execution steps precisely so that
their assertions about a still-`CONFIRMED` trip stay meaningful.

## Audit / observability

`ORDER_REOPENED` records who reopened an order, from which status, and why. Dispatch and close-out
deliberately mint **no** new action: both already produce `SHIPMENT_DISPATCHED` and
`SHIPMENT_COMPLETED`, and the orders' moves are that one fact's consequence rather than forty
separate decisions - writing forty rows per completed trip would bury the trail it exists to make
readable, which is the same reasoning V27 gives for not auditing stop transitions.

## Tests executed

Backend:
PASS: `./mvnw -B test` - **1389 tests, 0 failures, 0 errors** (baseline was 1312; **+77**).
New: `OrderStatusTest` (40, pure domain), `OrderPlanningServiceExecutionTest` (20, mapping +
idempotency + the row lock), `OrderExecutionPropagatorTest` (11), six new smoke steps, and
`OrderConstraintIntegrationTest.statusIsRestricted` strengthened.
FAIL: none.

Frontend:
PASS: `npm run typecheck` clean; `npm run lint` 0 errors, 17 pre-existing warnings; `npm test`
**42 tests** (was 37), 0 failures; `npm run build` succeeds.
FAIL: none.

Integration:
PASS: the extended smoke run drives the whole vertical over HTTP against a real PostgreSQL:
ready → dispatch → **order `IN_EXECUTION`** → cancel and edit both refused → stop arrived and
completed → partial delivery recorded mid-trip → **order still `IN_EXECUTION`** (not closed early)
→ trip completed → **order `PARTIALLY_DELIVERED`** → correction to `DELIVERED` after completion →
**order `DELIVERED`**, reopen and cancel both refused → correction to `REJECTED` → **order
`DELIVERY_FAILED`** → reopened → **`READY_FOR_PLANNING`, back in the eligible-orders pool**, one
`ORDER_REOPENED` audit row, first attempt's delivery row kept.
FAIL: none.

E2E:
PASS: `npx playwright test` - 33 passed, 7 skipped (authenticated smoke, no credentials).
FAIL: none.

## Environment blocked gates

**None.** Docker was already running from JOB 01, so all 32 Testcontainers classes ran for real. No
remote environment was contacted.

## Issues discovered

1. **A stale-compile trap in my own loop.** `./mvnw -q compile` swallowed a compilation failure I
   had introduced (a dropped closing brace in `AuditAction`), and the next test run failed with a
   confusing Hibernate `EnumHelper` NPE from a half-written `target/classes` rather than with the
   syntax error. Two "compiles cleanly" statements I made before catching it were wrong.
2. `OrderBacklogTotals` would have silently dropped orders out of the KPI planned-rate as soon as
   they departed - a reporting regression that no existing test would have caught, because the
   figure would still have been *a* number.
3. `OrderConstraintIntegrationTest` used `'DELIVERED'` as its example of a value outside the status
   catalogue. The day that became a real state, the assertion stopped meaning anything.
4. The smoke test already had a `tripVersion()` helper; my first edit added a duplicate.

## Issues fixed

1. Stopped using `-q` for compile checks and verified every build by exit code and by
   `test-compile` output. The dropped brace was restored.
2. `OrderBacklogTotals` reshaped to carry leaf counters with `planned()` derived, preserving the
   identity by construction. `KpiServiceTest`'s fixture now spreads the same committed total across
   the four states, so the report's assertions are unchanged and the invariant is proved rather
   than assumed.
3. The constraint test now asserts all eight values are **accepted** and that a genuine non-member
   (`DISPATCHED`) is refused - strengthened rather than merely repaired.
4. Duplicate helper removed.

## Remaining risks

- **Low.** All gates green, and the one behavioural change to an existing path (cancel refusing two
  more states) is covered both by the domain test and by the smoke run.
- `PARTIALLY_DELIVERED` says something is still owed but not **how much**. That is deliberate and is
  JOB 03's: putting a delivered-quantity column on the order would place the allocation ledger in
  the wrong table and have to be undone.
- An order reopened after a partial delivery re-enters the pool as a whole order. Until ship units
  exist, a planner replanning it is replanning the whole order, not the remainder. Named in
  ADR-009 and in `ORDER_LIFECYCLE_V2.md`.
- No back-fill means orders on trips completed before V36 keep reading `PLANNED`. They close out
  correctly the moment anything is recorded against them.

## Main files changed

    backend/.../db/migration/V36__order_execution_lifecycle.sql   new
    backend/.../orders/domain/OrderStatus.java                    8 states + transition table
    backend/.../orders/domain/TransportOrder.java                 3 execution transitions
    backend/.../orders/application/OrderPlanningService.java      port impl, row lock, mapping
    backend/.../orders/application/OrderService.java              cancel rules + reopenForPlanning
    backend/.../orders/api/OrderController.java                   POST /orders/{id}/reopen
    backend/.../orders/infrastructure/TransportOrderRepository.java  pessimistic finder
    backend/.../planning/application/OrderExecutionPropagator.java   new
    backend/.../planning/application/TripExecutionService.java    dispatch + complete propagate
    backend/.../planning/application/TripDeliveryService.java     corrections re-derive
    backend/.../shared/reference/OrderPlanningPort.java           2 methods
    backend/.../shared/reference/OrderBacklogTotals.java          leaf counters, derived planned()
    backend/.../shared/audit/AuditAction.java                     ORDER_REOPENED
    frontend/.../shared/api/ordersApi.ts                          8 states + reopen client
    frontend/.../pages/orders/OrdersPage.tsx                      tones, guards, reopen action
    frontend/.../lib/enums.ts, lib/i18n.ts                        ES labels + EN parity
    docs/architecture/ADR-009-order-execution-lifecycle.md        new
    docs/domain/ORDER_LIFECYCLE_V2.md                             new
    docs/domain/ORDER_LIFECYCLE_V1.md                             superseded banner

## Local commit

One local commit. No push.

## Recommended next job

**JOB 03 - Ship Units + Partial Allocation.** It is the direct continuation: `PARTIALLY_DELIVERED`
now exists and cannot yet say how much is outstanding, and reopening a partial order replans the
whole of it. JOB 01 also found that V11 wrote
`uq_trip_order_assignment_open_whole_order` as a partial index scoped to `whole_order = true`
specifically so split allocation could arrive without touching an applied migration - so the schema
is already waiting for it.

The next migration will be **V37**.
