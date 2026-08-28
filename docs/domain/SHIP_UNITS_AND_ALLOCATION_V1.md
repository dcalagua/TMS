# TMS by EBIM - ship units and partial allocation (V1)

Owner: `com.ebim.tms.orders` (the ledger's ceiling) and `com.ebim.tms.planning` (the ledger itself).
Schema: `V11__planning_manual.sql` and `V37__order_partial_allocation.sql`.

## 1. What a ship unit is in this product

**A portion of an order's demand, expressed in the three measures a vehicle is constrained by:
weight, volume and pallets.**

It is not a physical handling unit with its own identity. There is no `ship_unit` table and no row
per pallet, and that is a decision rather than an omission:

- The planner's question is *"how many of these go on this truck"*, never *"which of these hundred
  identical pallets"*. Minting 100 rows to split 100 pallets 70/30 would be faithful to a larger TMS
  and worse to operate.
- The three measures are the only ones that are **summable**. An order's lines each carry their own
  `quantity` and `uom`, and adding 40 boxes to 3 drums gives a number that means nothing - which is
  why `transport_order` has never had a quantity column. A fourth measure invented for splitting
  would be a fourth measure nothing else in the product uses.
- The row already exists. `tms.trip_order_assignment` has carried *allocated* amounts, not a pointer
  to the order's totals, since V11 - which said in its own header that this was so "a future
  partial/split assignment is a second row with smaller numbers rather than a schema change".

So a split is: **one order, two assignment rows, two trips, one ledger.** The order is never
duplicated.

## 2. The four figures

| Figure | Where it lives | Meaning |
|---|---|---|
| **Ordered** | `transport_order.total_*` | everything the customer asked for |
| **Allocated** | `transport_order.allocated_*` | the part on trips that have not closed out |
| **Pending** | derived: ordered − allocated | what a planner may still place |
| **Delivered** | `tms.order_delivery` (V28) | what happened at the dock, per order per stop |

## 3. The invariant, and why it is a column

    ALLOCATED <= ORDERED

Two planners splitting the same order in the same instant each read "nothing allocated", each
conclude there is room for 70 of the 100, and each insert. A service-level check cannot stop that:
both transactions passed it, in their own snapshots, truthfully.

A `CHECK` constraint can - but **a CHECK cannot span rows**. It cannot sum a ledger. The only shape
in which "allocated never exceeds ordered" is expressible to PostgreSQL is a running total on a
single row, which is exactly what `allocated_weight_kg`, `allocated_volume_m3` and
`allocated_pallets` are.

This is a **materialised invariant, not a cached fact.** ADR-009 argued that a derived figure should
not be stored beside the rows it comes from, and that still holds when the reason for storing it is
convenience. Here the reason is that storing it is the only way to make the rule a database
guarantee rather than a hope - the same reason `uq_trip_order_assignment_open_whole_order` exists.

Three layers, as everywhere else in this schema:

1. `TripService.assignOrder` refuses with a sentence naming what is left.
2. `OrderPlanningService.allocate` takes the **order's row lock** before reading the ledger, so a
   racing planner reads the winner's total rather than the pre-race one.
3. `ck_transport_order_not_over_allocated` refuses it in the database, for any caller that ever
   reaches the table another way.

`PlanningApiIntegrationTest.concurrentSplitsCannotOverAllocate` runs two real HTTP assignments in
parallel and asserts exactly one succeeds. `OrderConstraintIntegrationTest` asserts the constraint
directly, one pallet and one gram over.

## 4. Status follows the ledger

This is what makes a split work without a ninth `OrderStatus`:

| Ledger | Status |
|---|---|
| nothing allocated | `READY_FOR_PLANNING` |
| **part** allocated | `READY_FOR_PLANNING` - the rest is genuinely still plannable |
| fully allocated | `PLANNED` |

`PLANNED` goes on meaning exactly what it always meant: *there is nothing left for a planner to
place*. An order that is 70% loaded is not that, so it stays in the pool - and the board shows its
**pending** figure rather than its total, which is what stops the planner loading 100 more pallets
onto the second truck.

An order whose weight, volume and pallets are all unknown is fully allocated by an allocation of
nothing, so assigning it still makes it `PLANNED`. V1's behaviour, unchanged.

## 5. What each operation does to the ledger

| Operation | Ledger | Status |
|---|---|---|
| assign, no amounts given | allocate **everything still pending** | `PLANNED` if that finishes it |
| assign with amounts | allocate that slice | unchanged if anything is left |
| remove from a trip | release **that row's amounts**, not the whole order | back to `READY_FOR_PLANNING` if it was full |
| move between trips | **unchanged** - the same amount, a different truck | unchanged; must not flicker |
| trip closed out | allocation returns to zero | `DELIVERED` / `PARTIALLY_DELIVERED` / `DELIVERY_FAILED` |

The move row is worth reading twice. Releasing and re-allocating would take the order through
`READY_FOR_PLANNING` and back, which is visible to anything watching the status and is not what
happened. So a move closes one row and opens another, and never touches the ledger.

The close-out row is the interlock with V36: what was on the truck is by then either delivered or
owed again, and neither is "waiting on a shipment". Without it, an order reopened for a second
attempt would be unplannable, with its whole demand still booked onto a trip that finished.

## 6. Rules the database enforces

| Constraint | Rule |
|---|---|
| `ck_transport_order_not_over_allocated` | allocated ≤ ordered, per measure |
| `ck_transport_order_allocated_nonnegative` | allocated ≥ 0 |
| `ck_trip_order_assignment_partial_is_not_empty` | a split row must carry something |
| `uq_trip_order_assignment_open_whole_order` (V11) | at most one open whole-order row per order |
| `uq_trip_order_assignment_open_per_trip` (V37) | at most one open row per (trip, order) |

The last one is why "the same order twice on one trip" is refused: two rows on one trip are one
load, one stop and one delivery record described twice.

## 7. Scale, and the bug it would have caused

Comparisons use `BigDecimal.compareTo`, never `equals`. `30.00` and `30` are the same quantity of
pallets and two different objects, and the column returns the column's scale while the request
arrives at the request's. A ledger comparing with `equals` would refuse **the assignment that
exactly finishes an order** - the one case a planner notices immediately.
`OrderAmountsTest.trailingZerosDoNotBreakAnExactFill` pins it.

## 8. In the UI

The eligible-orders panel shows **pending** figures, marks a part-allocated order explicitly
("Repartido"), and offers a *Repartir* drawer prefilled with what is left rather than with the
order's total. Prefilling the total would offer, by default, the one number the server rejects.

## 9. Not here

- **No delivered quantities.** `tms.order_delivery` records an *outcome* per order per stop, not an
  amount. "Delivered is within what was allocated" holds structurally - a delivery can only be
  recorded against an order the trip is actually carrying - but a numeric delivered-versus-allocated
  ledger is a change to what a delivery *means*, and it waits for a requirement that needs it. The
  practical consequence: an order reopened after a partial delivery is replanned **in full**, and
  the planner adjusts. Named here because it is the sharp edge of this release.
- **No automatic splitting.** Which 30 of the 100 go on the second truck is a planner's decision.
  An engine proposing it is JOB 05's, and it will propose through this same ledger.
- **No split by line.** An order's lines are not individually allocatable. If a requirement needs
  "these two lines on that truck", that is a ship-unit-with-identity model and a different design.
