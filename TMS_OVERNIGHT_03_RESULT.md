# TMS OVERNIGHT JOB 03 RESULT

RESULT:
PASS

STOP_CHAIN:
false

## Objective

Let one order be distributed across several trips without duplicating it. Represent ordered,
allocated, delivered and pending; guarantee `allocated <= ordered`; make the minimal case -
`ORDER 100 units -> TRIP A = 70, TRIP B = 30` - work consistently; show pending quantities in manual
planning; and protect against concurrent over-allocation.

## Initial diagnosis

Working tree clean at `137f711`. The ledger already existed and V11 said so in its own header:
`tms.trip_order_assignment` carries the **allocated** amounts rather than pointing at the order's
totals, "so a future partial/split assignment is a second row with smaller numbers rather than a
schema change"; capacity already sums that table and never the order header; and
`uq_trip_order_assignment_open_whole_order` is a **partial** index deliberately excluding the split
case.

So the job was not to build a ledger. It was to give the ledger a **ceiling** and teach the product
to read it.

## Existing functionality reused

- The assignment table, its allocated columns, and V11's partial unique index - used exactly as V11
  intended, without touching an applied migration.
- `PlanningCapacityService`, which sums assignments and therefore needed **no change at all** for
  splitting to work. That was V11's stated payoff and it held.
- `TripStopPlanner` / `Trip.syncStops`: a split produces one stop per destination as before, because
  stops derive from distinct destinations rather than from assignment rows.
- The order row lock added in JOB 02 (`findByIdAndCompanyIdForUpdate`), reused as the serialisation
  point for allocation.

## Architecture/design

**A ship unit here is a portion of an order's demand in the three measures a vehicle is constrained
by - weight, volume, pallets - and not a physical handling unit with its own identity.** There is no
`ship_unit` table, and that is a decision:

- the planner's question is "how many of these go on this truck", never "which of these hundred
  identical pallets". Minting 100 rows to split 100 pallets 70/30 would be faithful to a larger TMS
  and worse to operate;
- those three measures are the only summable ones. Order lines carry their own `quantity` and
  `uom`, and adding 40 boxes to 3 drums is a number that means nothing - which is why
  `transport_order` has never had a quantity column. A fourth measure invented for splitting would
  be one nothing else in the product uses.

**The invariant is a column because a CHECK cannot span rows.** Two planners each read "nothing
allocated", each conclude there is room for 70 of 100, and each insert - both having passed a
service check truthfully in their own snapshot. The only shape in which `allocated <= ordered` is
expressible to PostgreSQL is a running total on one row.

That is a **materialised invariant, not a cached fact**, and the distinction matters because ADR-009
argued the opposite case a few hours earlier. It argued against storing a derived figure *for
convenience*. Here storing it is the only way to make the rule a guarantee instead of a hope - the
same reason V11's unique index exists at all. Three layers: a readable service refusal, the order's
row lock, and the CHECK beneath.

**Status follows the ledger**, which is what makes a split work without a ninth `OrderStatus`: an
order is `PLANNED` when it is *fully* allocated and stays `READY_FOR_PLANNING` while any of it is
pending. `PLANNED` goes on meaning exactly what it always meant - nothing left for a planner to
place.

## Database migrations

**`V37__order_partial_allocation.sql`**, the next real number. No applied migration touched.

- `allocated_weight_kg` / `allocated_volume_m3` / `allocated_pallets` on `transport_order`;
- `ck_transport_order_not_over_allocated` and `ck_transport_order_allocated_nonnegative`;
- `ck_trip_order_assignment_partial_is_not_empty` - a split row must carry something (a whole-order
  row may legitimately be all zeros; a partial one may not);
- `uq_trip_order_assignment_open_per_trip` - one open row per (trip, order), because two rows on one
  trip are one load and one stop described twice;
- `ix_transport_order_pending_allocation`, partial, for the part-planned pool;
- **a back-fill, unlike V36.** Every existing assignment is a whole-order row, so the correct
  allocated value is derivable from rows that already exist. That is a derivation, not the
  fabrication V36 refused: there, inventing a delivery outcome nobody recorded would have been a
  guess; here the ledger already states the answer.

## Backend changes

- `OrderAmounts` (new, shared) - the three measures as one value, `BigDecimal` throughout, comparing
  with `compareTo` and never `equals`.
- `OrderAllocation` (new, shared) - ordered + allocated, with `pending()` derived.
- `TransportOrder` - allocated fields, `allocate`, `releaseAllocation`, and `closeOut` now also
  consumes the allocation (the V36 interlock).
- `OrderPlanningPort` - `markPlanned`/`releaseFromPlanning` **replaced** by `allocate` /
  `releaseAllocation` / `allocationsOf`. The difference is the whole of the feature: a planner no
  longer asserts "this order is planned", they say "this much of it is on this trip".
- `OrderPlanningService` - both under the row lock, with a refusal naming what is left.
- `PlannableOrder` - carries `allocated`, with `pending()` and `isPartiallyAllocated()` derived, so
  the board gets pending without a second query per row.
- `EligibleOrderView` - pending figures and a `partiallyAllocated` flag.
- `AssignOrderRequest` - optional amounts; omitting them means "everything still pending", which is
  why every pre-existing caller works unchanged.
- `TripService.assignOrder` - partial-aware, allocating **before** inserting so a racing planner is
  stopped before there is a row to clean up; `moveOrder` carries the row's own share and leaves the
  ledger untouched.
- `TripAssignmentService` - `open` takes amounts and computes `whole_order`; `closeAndRelease`
  returns *that row's* amounts, not the whole order.
- `CapacityLoad.of(OrderAmounts)`.

## Frontend changes

- `planningApi.ts` - pending fields, `partiallyAllocated`, optional amounts on the assign request.
- `SplitAssignDrawer.tsx` (new) - a compact drawer prefilled with **pending**, not the total.
  Prefilling the total would offer by default the one number the server rejects.
- `EligibleOrdersPanel.tsx` - shows pending rather than totals, marks a split explicitly with the
  order's total beside it, and adds a *Repartir* action.
- i18n ES + EN parity for every new string.

## Security and tenant isolation

Every new path is company-scoped. `allocate` and `releaseAllocation` take `companyId` and load
through the company-predicated locking finder. `allocationsOf` uses the existing
`findByIdInAndCompanyId`, so an id from another tenant is simply not found rather than filtered
after loading. No new endpoint and no new authority: splitting is `planning.trip:manage`, the same
as assigning, because it is the same act.

`fk_trip_order_assignment_order_company` (V11) still pins both references to one company, so a split
cannot cross a tenant line at the database level either.

## Audit / observability

`ASSIGN_ORDER` now carries `partial: true` and the remaining pending figures when the assignment
does not finish the order. "Part of this order went somewhere else" is the fact somebody
reconciling a short delivery starts from, and it belongs in the trail rather than only in the
ledger. No new action was minted: a split is an assignment.

## Tests executed

Backend:
PASS: `./mvnw -B clean test` - **1409 tests, 0 failures, 0 errors** (was 1389; **+20**).
New: `OrderAmountsTest` (11, incl. the trailing-zeros trap), seven split tests in
`PlanningApiIntegrationTest`, two DB constraint tests.
FAIL: none.

Frontend:
PASS: `npm run typecheck` clean; `npm run lint` 0 errors, 17 pre-existing warnings; `npm test`
**47 tests** (was 42), 0 failures; `npm run build` succeeds.
FAIL: none.

Integration:
PASS, over real HTTP against real PostgreSQL:
- **the brief's case**: a 100-pallet order, 70 onto trip A (`wholeOrder: false`, order stays
  `READY_FOR_PLANNING`, board shows 30 pending and flags the split), the remaining 30 onto trip B by
  assigning with no amounts, order becomes `PLANNED`, leaves the pool, **two assignment rows and
  exactly one order row**;
- the stored running total equals the ledger recomputed from the assignment rows;
- over-allocation refused with both trucks having room - the *order* is the constraint;
- an exact fill with `300.000 / 3.0000 / 30.00` succeeds (the scale trap);
- removing one half returns only that half;
- the same order twice on one trip refused;
- a moved split carries its own share and leaves the ledger unchanged;
- **two concurrent 70-pallet splits of a 100-pallet order: exactly one succeeds**, run as two real
  parallel HTTP requests behind a latch.
FAIL: none.

E2E:
PASS: 33 passed, 7 skipped.
FAIL: none.

## Environment blocked gates

**None.** Docker running; all Testcontainers classes executed. No remote environment contacted.

## Issues discovered

1. **Maven's incremental compilation silently used stale classes twice**, once reporting BUILD
   SUCCESS while `TripService` still called a port method I had just deleted. It is not a project
   defect, but it makes "it compiles" an unreliable statement.
2. `PlannableOrder` gaining a component broke three test fixtures - only visible after a clean
   build, for the same reason.
3. MUI 9 removed `inputProps`; the numeric fields needed `slotProps.htmlInput`.
4. The scale trap: comparing `BigDecimal` with `equals` would have refused precisely the assignment
   that exactly finishes an order.

## Issues fixed

1. Every verification build in this job used `clean`. The result reported above is from
   `./mvnw -B clean test`.
2. Fixtures updated; the clean build is what proved they were the only three.
3. Converted, and typecheck is clean.
4. `OrderAmounts` compares with `compareTo` throughout, and `OrderAmountsTest` plus an integration
   test with deliberately mismatched scales pin it.

## Remaining risks

- **Delivered quantities do not exist.** `tms.order_delivery` records an outcome per order per stop,
  not an amount. "Delivered is within what was allocated" holds structurally - a delivery can only
  be recorded against an order the trip carries - but there is no numeric delivered-versus-allocated
  ledger. The practical consequence, stated plainly in the domain doc: **an order reopened after a
  partial delivery is replanned in full**, and the planner adjusts. Giving deliveries amounts is a
  change to what a delivery *means*, which is a separable job and needs a requirement behind it.
- The planning engine still plans whole orders. It cannot propose a split; a planner does that by
  hand. JOB 05 can propose through this same ledger.
- No split by line. If a requirement ever needs "these two lines on that truck", that is a
  ship-unit-with-identity model and a different design.

## Main files changed

    backend/.../db/migration/V37__order_partial_allocation.sql          new
    backend/.../shared/reference/OrderAmounts.java                      new
    backend/.../shared/reference/OrderAllocation.java                   new
    backend/.../shared/reference/OrderPlanningPort.java                 allocate / releaseAllocation / allocationsOf
    backend/.../shared/reference/PlannableOrder.java                    carries allocated; pending() derived
    backend/.../orders/domain/TransportOrder.java                       ledger + status follows it
    backend/.../orders/application/OrderPlanningService.java            row lock, readable refusal
    backend/.../planning/application/TripService.java                   partial assign, split-aware move
    backend/.../planning/application/TripAssignmentService.java         amounts in, that row's amounts out
    backend/.../planning/application/AssignOrderRequest.java            optional amounts
    backend/.../planning/application/EligibleOrderView.java             pending + partiallyAllocated
    backend/.../planning/domain/TripOrderAssignment.java                whole_order is now really used
    frontend/.../pages/planning/SplitAssignDrawer.tsx                   new
    frontend/.../pages/planning/EligibleOrdersPanel.tsx                 pending figures, split action
    docs/domain/SHIP_UNITS_AND_ALLOCATION_V1.md                         new

## Local commit

One local commit. No push.

## Recommended next job

**JOB 04 - Routing Matrix + Distance + Travel Time.** It is the right next step because it is what
everything after it needs and nothing so far has: the only distance in the system is
`route.reference_distance_km`, a static master-data column. Planning V2 (JOB 05) cannot score a
proposal on kilometres or duration without it, rating per-km (JOB 06) currently has nothing
trustworthy to multiply, and ETA (JOB 10) is not expressible at all. It is also self-contained -
a port, a deterministic local implementation, a cache and metrics - so it lands without disturbing
the ledger this job just built.

The next migration will be **V38**.
