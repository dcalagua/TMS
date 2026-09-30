# TMS by EBIM - split orders meet execution

**Status:** Approved 2026-09-27 (R1, R2, R3) and **implemented**.
**Specification:** `SplitOrderExecutionTest` (13 scenarios, two of them repeated races).
**Builds on:** `SHIP_UNITS_AND_ALLOCATION_V1.md` (V37), ADR-009 (V36), `TRIP_EXECUTION_V1.md` (V25)
**Schema change:** none. One change of *definition* for `allocated_*` (below).

## 1. The rule

> Dispatching a partial allocation must never stop the rest of the order from staying plannable.

No new `OrderStatus`, no new `TripStatus`, and no `READY_FOR_PLANNING -> IN_EXECUTION` shortcut.
That shortcut would make a part-planned order `IN_EXECUTION`, and since `allocate` and the
eligible-orders search both require `READY_FOR_PLANNING`, its remainder would become unplannable.

## 2. The three rules

### R1 - the status follows the plan while anything is still to be placed

- An order enters `IN_EXECUTION` **only from `PLANNED`**, i.e. when nothing of it is left to place.
- A trip that departs with part of a `READY_FOR_PLANNING` order departs normally. That share is on
  the road; the order stays `READY_FOR_PLANNING`, its departed share stays allocated, and its
  remainder stays visible and plannable. (`OrderPlanningService.markInExecution` is a no-op for a
  `READY_FOR_PLANNING` order holding an allocation; it still refuses `NOT_READY`, `CANCELLED`, and
  a `READY_FOR_PLANNING` order with nothing allocated.)
- When the remainder is planned in full, `allocate` makes the order `PLANNED`, and the next
  departure of any of its trips moves it to `IN_EXECUTION`.
- **The mirror image:** a share that has *not* departed and comes off its trip (removed from a draft
  trip, or its trip cancelled) returns to the pool even when the order is already `IN_EXECUTION`
  because another share left. The order goes back to `READY_FOR_PLANNING`
  (`IN_EXECUTION -> READY_FOR_PLANNING`, taken only by `TransportOrder.releaseAllocation`), the
  departed share stays allocated, and the released share is pending again. Without this, cancelling
  trip B after trip A left would make B's 40 disappear.

### R2 - an order closes out with its last carrier, not its first

`OrderExecutionPropagator.closedOut(trip)` and `deliveryRecorded(trip, order)` close an order out
only when:

- no *other* trip in `DRAFT`, `CONFIRMED`, `READY_FOR_DISPATCH` or `IN_TRANSIT` holds an `ACTIVE`
  assignment of it (an **open carrier**); and
- nothing of it is pending. A `READY_FOR_PLANNING` order cannot reach an outcome in the transition
  table, so the orders module leaves it alone.

Otherwise the call changes nothing. The last carrier's close-out reads the fulfilment of **every**
attempt (`OrderFulfillmentAdapter` sums recorded quantities across trips, V45), maps it through
`OrderPlanningService.closureFor`, and only then consumes the allocation.

**Concurrency.** The orders are locked *before* their other carriers are read
(`OrderPlanningPort.lockForExecution`, in id order). Two trips of one order completing at the same
instant therefore serialise: the second waits for the first to commit and then sees it finished.
Without the lock each would see the other still on the road and neither would close the order.
The lock also *refreshes* an order the transaction had already loaded
(`TransportOrderLocking.lockAndRefresh`); before that, 10 races in 12 between "dispatch trip A" and
"plan the remainder on trip B" lost the departure with a spurious 409.

### R3 - an allocation pins the order's content

While any allocation is held (`allocated_*` non-zero):

- **cancel** is refused (409, "unassign it from its trip first");
- **edit** is refused (409, "has part of it on a trip"). An edit would reset the order to
  `NOT_READY` and change the demand the share was cut from;
- the **integration upsert** is refused when the payload changes anything (it goes through
  `OrderService.update`). A payload that is **really unchanged** is still answered `UNCHANGED`, with
  the version, status and release untouched.

The way out is to remove the assignment or cancel the trip. A share already on the road can be
neither: that order is simply not cancellable, which is what `IN_EXECUTION` already meant.

## 3. The definition that changed

`transport_order.allocated_*` meant *"the part on trips that have not closed out"*. It now means
**"the part committed to trips since the order last entered the pool"**: open trips, plus finished
trips whose order has not closed out yet.

- `pending = ordered − allocated` keeps meaning "what a planner may still place", and never offers
  again something that is on the road or already delivered.
- The column, its `CHECK`s and the row lock are unchanged.

## 4. The ten approved scenarios

| # | Scenario | Behaviour | Test |
|---|---|---|---|
| 1 | 100 total, A = 60, 40 unplanned, A departs | A `IN_TRANSIT`; order `READY_FOR_PLANNING`, allocated 60, pending 40, in the eligible pool, and plannable onto B | `firstTripOfAPartlyPlannedOrderLeaves…` |
| 2 | 100 total, A = 60, B = 40, A departs | order `IN_EXECUTION`, allocated 100 | `aFullySplitOrderClosesOnlyWithItsLastCarrier` |
| 3 | A delivered and closed while B has not left | order stays `IN_EXECUTION`, allocated 100 | same |
| 4 | A delivered with 40 never planned | order `READY_FOR_PLANNING`, pending 40, eligible; B takes the 40 and closes it `DELIVERED` | `deliveringOneTripWhileTheRestIsUnplannedKeepsTheRest` |
| 5 | cancel with a partial allocation | 409, before and after the share departs | `aPartlyPlannedOrderCannotBeCancelled…`, `aPartlyDepartedOrderCannotBeCancelled` |
| 6 | edit with a partial allocation | 409; allowed once unassigned. Integration: changed 409, unchanged `UNCHANGED` | `aPartlyPlannedOrderCannotBeEdited…`, `OrderUpsertReleaseIntegrationTest` |
| 7 | remove / cancel a trip with a partial allocation | exactly that share returns, including after the other share has departed (`IN_EXECUTION -> READY_FOR_PLANNING`) | 7a, 7b, 7c |
| 8 | last trip closes | outcome from both trips' deliveries (60 + 40 = `DELIVERED`) | scenario 2 |
| 9 | refusal on one of two trips | `PARTIALLY_DELIVERED`, reopenable; per-trip and order-wide delivery ceilings | 9, 9b |
| 10 | concurrency | both carriers completing at once close the order once; dispatch racing the remainder's planning both succeed with an exact ledger | 10a (×3), 10b (×5) |

## 5. Defects found and fixed while certifying this

1. **The delivery ceiling counted other trips' deliveries.** `TripDeliveryService.requireWithinAllocation`
   summed every trip's delivered quantities against *this* trip's share, so the second half of a split
   order recording quantities was refused (409). It now applies two ceilings: this trip's share
   against this trip's deliveries, and the order's demand against all deliveries (so 70 on Monday
   plus 40 on Tuesday of a 100 order is still refused).
2. **A reopened order could not be planned whole again.** Its finished trip's `ACTIVE` whole-order
   row kept `uq_trip_order_assignment_open_whole_order`'s slot, so ADR-009's second attempt was
   refused ("already assigned to a trip"). A whole row on a `COMPLETED` trip is now recognised as a
   past attempt: it no longer blocks, and the new attempt is stored as a share
   (`whole_order = false`). The V37 ledger (row lock + over-allocation check) still serialises
   planners racing for it.
3. **Dispatch lost to a concurrent planner** (R2, concurrency, above).

## 6. Limitations (accepted)

- **L1.** A share that fails on a closed carrier is not re-plannable on its own. It returns to the
  pool only when the order's last carrier closes out, through the existing reopen, and the reopened
  order is replanned **in full** (`SHIP_UNITS_AND_ALLOCATION_V1.md` §9). Re-planning only the failed
  share needs a consumed-quantity ledger, which is a schema change and is not made now.
- **L2 (display).** While R1 holds, an order with a share on the road reads `READY_FOR_PLANNING` in
  the orders list. That is true but incomplete; the detail view should show an execution summary
  derived from its assignments. Read-model work, no new state.
- **Outcome-only recording.** When deliveries are recorded without quantities,
  `OrderFulfillmentAdapter` uses the latest outcome row, so "A delivered, B rejected" closes as
  `DELIVERY_FAILED`, not `PARTIALLY_DELIVERED`. That is the recoverable direction (ADR-009). Recording
  quantities makes it exact.
