# TMS by EBIM - split orders meet execution (analysis and proposed behaviour)

**Status:** Proposed - awaiting approval. **No production code implements this yet.**
**Evidence:** `SplitOrderExecutionCharacterizationTest` (7 scenarios, all pinning current behaviour)
**Builds on:** `SHIP_UNITS_AND_ALLOCATION_V1.md` (V37), ADR-009 (V36), `TRIP_EXECUTION_V1.md` (V25)

## 1. The approved rule

> Dispatching a partial allocation must never stop the rest of the order from staying plannable.

The brief fixes two constraints on the answer: no new `OrderStatus`, and no naive fix of
`markInExecution`. This document records what the product does today, why a one-line fix would be
wrong, and the exact behaviour proposed instead.

## 2. What the product does today

Each row is asserted by a test in `SplitOrderExecutionCharacterizationTest`.

| # | Scenario | Today | Verdict |
|---|---|---|---|
| 1 | 100 pallets, trip A carries 60, 40 unplanned, A dispatches | **409**: `markInExecution` refuses `READY_FOR_PLANNING` and rolls back the whole departure. The trip stays `READY_FOR_DISPATCH` | DEFECT |
| 2 | 60 on A + 40 on B (order `PLANNED`), A dispatches and closes out | A leaves and the order goes `IN_EXECUTION`. Closing A out makes the order **`DELIVERED`** from A's row alone, and **zeroes `allocated_*`** while B, which has not left, still carries 40. B then leaves and the order stays `DELIVERED` | DEFECT |
| 3 | One trip delivered while the rest is unplanned | **Unreachable**: scenario 1 keeps the trip at the dock | Hidden by 1 |
| 4 | Cancel an order with 60 of 100 on a draft trip | Was **allowed**: the order became `CANCELLED` with its `ACTIVE` assignment and `allocated = 60` still in place, and a later dispatch of that trip failed on the cancelled order. Now **409** | DEFECT, **fixed by Phase 0 B** |
| 4b | Edit that same order | **Allowed**. The edit resets it to `NOT_READY` (`TransportOrder.applyChanges`) with the assignment still under it | DEFECT (same root as 4) |
| 5a | Remove a partial allocation from a draft trip | Exactly its share returns; ledger and running total agree | Correct |
| 5b | Cancel a confirmed trip carrying a partial allocation | Only that trip's share returns, and the order is plannable again | Correct |

### Root causes

1. **Execution is keyed on the order's status, but a split order has no single execution
   moment.** `OrderExecutionPropagator.dispatched` calls `markInExecution` for every order on the
   departing trip, and the status of a part-planned order is, by V37's design, `READY_FOR_PLANNING`.
2. **Close-out is order-wide, but a split order has several carriers.** `closedOut(trip)` closes
   every order the trip carried, as if that trip were the order's only one:
   - It maps that trip's fulfilment to the order's final outcome.
   - It calls `applyAllocated(NONE)`, which discards the allocation held by trips that have not
     even left.
3. **The status guards test the status, not the ledger.** `cancel` and `update` accept
   `READY_FOR_PLANNING` without looking at `allocated_*`, which V37 made non-zero for exactly that
   status.

A naive fix, where `markInExecution` accepts `READY_FOR_PLANNING`, would move a part-planned order
to `IN_EXECUTION`. `allocate` and the eligible-orders search both require `READY_FOR_PLANNING`, so
the remaining 40 would become **unplannable**. That breaks the approved rule outright. It would
also make scenario 3 reachable, where root cause 2 then closes the order out after its first
delivery.

## 3. Can the current `OrderStatus` represent this?

**Yes, without a new status and without a schema change.** It needs the three rules below and one
change of *definition* (not of column) for `allocated_*`. The price is one explicit limitation,
L1.

### Rule R1 - status follows the plan while anything is still to be placed

- An order enters `IN_EXECUTION` **only from `PLANNED`**, i.e. when nothing of it is left to place.
- A trip that departs with part of a `READY_FOR_PLANNING` order leaves that order **exactly where
  it is**: its remainder stays in the pool and its departed share stays allocated.
- `markInExecution` therefore becomes a no-op, not a refusal, for a `READY_FOR_PLANNING` order that
  holds an allocation.
- It keeps refusing `NOT_READY` and `CANCELLED`. After rule R3 neither can hold an allocation.

When the remainder is later planned in full, the order becomes `PLANNED` through the existing
`allocate`. The next departure of any trip carrying it then moves it to `IN_EXECUTION`, which is
what `markInExecution` does today.

### Rule R2 - an order closes out with its last carrier, not its first

`closedOut(trip)` and `deliveryRecorded(trip, order)` close an order out **only when both hold**:

- no *other* trip in `DRAFT`, `CONFIRMED`, `READY_FOR_DISPATCH` or `IN_TRANSIT` holds an `ACTIVE`
  assignment of it (an **open carrier**); and
- nothing of it is pending, i.e. it is not `READY_FOR_PLANNING`.

Otherwise the call is a no-op. The last carrier's close-out reads the fulfilment of **every**
attempt, which `OrderFulfillmentAdapter` already sums when quantities are recorded (V45). It maps
that fulfilment through `OrderPlanningService.closureFor` as today, and only then consumes the
allocation (`applyAllocated(NONE)`).

With R2, scenario 2 becomes:
- A closes out and the order stays `IN_EXECUTION` with 100 allocated.
- B leaves and nothing changes.
- B closes out and the order closes with the combined outcome.

### Rule R3 - an allocation pins the order's content

- `OrderService.cancel` and `OrderService.update` refuse an order in `READY_FOR_PLANNING` whose
  `allocated_*` is non-zero, and name the trips holding it.
- The integration upsert already refuses anything that is not `NOT_READY` or `READY_FOR_PLANNING`,
  and gains the same refusal.
- The way out is the one scenario 5 shows works: remove the assignment, or cancel the trip.
- Phase 0 B implements the cancel half of R3. The update half (4b) is proposed here and not yet
  implemented.

### The definition that changes

`transport_order.allocated_*` is documented today as *"the part on trips that have not closed
out"*. Under R2 it means **"the part committed to trips since the order last entered the pool"**:
open trips plus closed trips whose order has not yet closed out.

- `pending = ordered − allocated` then keeps meaning "what a planner may still place", and never
  offers again something that is on the road or already delivered.
- The column, the `CHECK` and the row lock are unchanged.
- The comment in V37 and `SHIP_UNITS_AND_ALLOCATION_V1.md` §2 and §5 change.

### Limitation L1 (accepted, not an inconsistency)

A share that **fails** on a closed carrier is not re-plannable on its own. It returns to the pool
only when the order's last carrier closes out:
- the order closes as `PARTIALLY_DELIVERED` / `DELIVERY_FAILED`;
- the existing reopen puts it back in the pool;
- the reopened order is replanned **in full**, which is the sharp edge already recorded in
  `SHIP_UNITS_AND_ALLOCATION_V1.md` §9.

Re-planning only the failed share needs a consumed-quantity ledger, i.e. a `delivered_*` running
total or a per-assignment close. That **is** a schema change, and it is deliberately not proposed
now.

### Limitation L2 (display only)

While R1 holds, an order with a share on the road still reads `READY_FOR_PLANNING` in the orders
list. That is true, because part of it is waiting for a truck, but it is incomplete. The detail view
should show an execution summary derived from its assignments ("60 in transit on SH-…, 40 to
plan"). This is a read-model addition and needs no new state.

### Known imprecision, unchanged by this proposal

When deliveries are recorded **without quantities**, `OrderFulfillmentAdapter` uses the latest
outcome row. For an order carried by two trips, "A delivered, B rejected" therefore closes as
`DELIVERY_FAILED`, not `PARTIALLY_DELIVERED`. This is the recoverable direction ADR-009 chose,
since a failed order is reopenable. Recording quantities (V45) makes the outcome exact.

## 4. Behaviour of the five scenarios under R1-R3

| # | Scenario | Proposed behaviour |
|---|---|---|
| 1 | A (60) departs with 40 unplanned | A departs. The order stays `READY_FOR_PLANNING` with `allocated = 60` and `pending = 40` |
| 2 | 60 on A + 40 on B, A departs and closes | A departs and the order goes `IN_EXECUTION`. A's close-out is a no-op because B is an open carrier. B departs with no change. B's close-out closes the order from both trips' deliveries |
| 3 | A delivered while 40 unplanned | A's close-out is a no-op because 40 are pending. The order stays `READY_FOR_PLANNING` with `pending = 40`. When the 40 are planned and their trip closes, the order closes |
| 4 | Cancel with 60 allocated | **409**, naming the trip. The planner removes the assignment or cancels the trip first |
| 4b | Edit with 60 allocated | **409**, same message |
| 5a/5b | Remove or cancel a trip with a partial | Unchanged, already correct |

## 5. Where the change lands (for the implementation after approval)

| Change | File |
|---|---|
| R1: no-op for `READY_FOR_PLANNING` holding an allocation | `orders/application/OrderPlanningService.markInExecution` |
| R2: `OrderPlanningPort.closeOut` receives whether the order still has an open carrier; the orders module refuses to close while pending > 0 | `planning/application/OrderExecutionPropagator.closedOut` / `deliveryRecorded`, `orders/application/OrderPlanningService.closeOut`, a new repository query "open carriers of these orders" in `TripOrderAssignmentRepository` |
| R3 (cancel), Phase 0 B | `orders/application/OrderService.cancel` |
| R3 (edit, integration upsert) | `orders/application/OrderService.update`, `orders/application/OrderIntakeService.requireRewritable` |
| Definition of `allocated_*` | this document, `SHIP_UNITS_AND_ALLOCATION_V1.md`, a `COMMENT ON COLUMN` in the next migration that touches the table (applied migrations are immutable) |

The characterisation test changes in the same commit. Its assertions marked `DEFECT` become the
proposed behaviour above, and 5a/5b stay as they are.
