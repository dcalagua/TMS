# ADR-009 - The order lifecycle carries the execution states

**Status:** Accepted - 2026-08-28
**Migration:** V36
**Revises:** the reasoning recorded on `shared.reference.OrderFulfillmentStatus`, which argued that
delivery outcomes must not become `OrderStatus` values. That argument is upheld for the delivery
*fact* and narrowed - see "What this does not change".

## Context

`OrderStatus` carried four values: `NOT_READY`, `READY_FOR_PLANNING`, `PLANNED`, `CANCELLED`. That
was the deliberate minimum of the step 09 brief, written when nothing downstream of planning
existed, and `docs/domain/ORDER_LIFECYCLE_V1.md` says so explicitly.

Everything downstream now exists. V25 gave trips an execution lifecycle, V27 gave stops one, V28
gave every order at every stop a commercial outcome, and `OrderFulfillmentStatus` derives "what
happened at the dock" from those rows on read. The order itself learned none of it, and
`TripExecutionService.complete` carried a comment saying so: *"Leaves the orders it carried
PLANNED. There is no delivery status for them to move to."*

That left three real operational holes, not cosmetic ones:

1. **An order on a departed vehicle read `PLANNED`** - indistinguishable from one sitting in a
   draft trip for next Tuesday. A dispatcher could not tell scheduled from moving.
2. **An order whose delivery was refused stayed `PLANNED` forever.** It was not plannable, so it
   never returned to the pool; not cancellable, because `OrderService.cancel` refuses a planned
   order; not deliverable, because its trip was closed. **A customer waiting for a redelivery was
   invisible to the system that owed it.** This is a functional defect, not a missing feature.
3. **An order delivered in full stayed `PLANNED` too**, so "what is still outstanding" could only
   be answered by joining to the delivery rows.

## The tension this ADR has to resolve

`OrderFulfillmentStatus` carries a well-argued objection to exactly what this ADR does. In its own
words: a stored copy of the delivery fact "would be a second copy of that fact, kept in step by
whoever remembered to - and the day the two disagreed there would be no way to say which was
right."

That objection is correct and it is not answered by disagreeing with it.

## Decision

**Add four states - `IN_EXECUTION`, `DELIVERED`, `PARTIALLY_DELIVERED`, `DELIVERY_FAILED` - and keep
`OrderFulfillmentStatus` exactly as it is.**

The objection is answered on two grounds.

**These states are not the delivery fact; they are its lifecycle consequence.** The two enums answer
different questions. `OrderFulfillmentStatus` answers *what happened at the dock* - live,
per-stop, correctable. `OrderStatus` answers *what may be done with this order next*. Three of the
four new states cannot be derived from delivery rows at all: an order on a departed vehicle has no
delivery row yet; an order with nothing recorded is not the same as one nobody planned; and "this
is back in the pool for a second attempt" is a decision a person made, not a fact a row states.

**Drift is prevented structurally, not by discipline.** The status is recomputed from the delivery
rows inside the same transaction as every change to them, at all three moments a delivery row can
change:

| Moment | Caller | Effect |
|---|---|---|
| The vehicle leaves | `TripExecutionService.dispatch` | every order on the trip → `IN_EXECUTION` |
| The shipment is closed out | `TripExecutionService.complete` | each order → its outcome, read from the rows |
| A delivery is recorded or corrected afterwards | `TripDeliveryService.record` | that order → its outcome, re-read |

The third row is the one that matters. The recording window stays open after completion on purpose -
the signed notes come back at 18:40 - so without it an order closed out as failed at 18:00 would
still read failed after the note proving delivery was keyed forty minutes later. With it, there is
no moment at which the rows and the status can disagree, because nothing changes one without the
other in the same transaction.

## Where each rule lives

The module boundary is preserved and runs in the direction it already ran. Planning owns
`tms.order_delivery` and reports a **fact**; orders owns `OrderStatus` and decides what the fact
**means**:

    TripExecutionService  ──▶  OrderExecutionPropagator  ──▶  OrderPlanningPort.closeOut(fulfilment)
    (planning)                 (planning)                     (implemented in orders)

`OrderPlanningService.closureFor` is the whole mapping, and it is exhaustive over
`OrderFulfillmentStatus` with no default, so a new fulfilment value cannot silently fall through.

The transition table itself is in `orders.domain.OrderStatus`, unit-testable without a database,
asserted again by `TransportOrder` as a last line of defense, with V36's CHECK constraint beneath
both - the same three-layer shape `TripStatus` uses.

## Consequences

- **A failed delivery is recoverable.** `OrderService.reopenForPlanning` moves a shortfall back to
  `READY_FOR_PLANNING` for a second attempt. It is explicit, permissioned and audited
  (`ORDER_REOPENED`) with the status it was reopened from, because a redelivery costs a truck and
  a system that silently re-queued every refusal would plan work nobody agreed to pay for.
- **`DELIVERED` is not terminal.** The three outcomes are mutually reachable, because a delivery
  record is corrected in place. Only `CANCELLED` is terminal.
- **An order in execution cannot be cancelled or edited.** The goods are on a moving vehicle, so
  "this order did not happen" has stopped being true - the same reasoning that denies a departed
  trip a move to `CANCELLED`.
- **An order with nothing recorded closes as `DELIVERY_FAILED`.** That is the honest reading of
  "the trip is over and we cannot show the customer got it", and it is the safe one: failed is
  reopenable and delivered is not, so the mistake this makes is the recoverable one, and a note
  keyed late corrects it.
- **The KPI planned-rate did not move.** `OrderBacklogTotals` now counts the committed states
  separately and derives `planned()` from them, so an order that departed is still an order a
  planner put on a truck.
- **No back-fill.** Existing rows keep their state. Inventing a delivery outcome for a shipment
  nobody recorded one against would be exactly the comfortable lie this schema refuses elsewhere.

## What this does not change

- `OrderFulfillmentStatus` stays derived, stays per-stop, stays the live view, and stays the thing
  the order detail screen shows beside the status. It was not replaced and it was not stored.
- No attempt counter. "How many times have we tried" is answerable from the delivery rows.
- No partial quantities. `PARTIALLY_DELIVERED` says something is still owed; *how much* is a ship
  unit question, and ship units do not exist yet (JOB 03). Putting a delivered-quantity column on
  the order would place the allocation ledger in the wrong table.
