-- ===========================================================================
-- V37 - An order can be split across several trips
-- ===========================================================================
--
-- Until now an order went onto exactly one trip, whole. A 100-pallet order that did not fit one
-- vehicle had no representation at all: the planner either found a bigger truck or typed a second
-- order, and the second order is a lie - it duplicates a customer's demand so that the system can
-- count it, and every figure downstream (backlog, KPIs, invoicing) then counts it twice.
--
-- V11 planned for this and said so in its own header, which is why this migration is small:
--
--   * tms.trip_order_assignment already carries the *allocated* amounts rather than pointing at
--     the order's totals, "so a future partial/split assignment is a second row with smaller
--     numbers rather than a schema change";
--   * capacity already sums that table and never the order header, so splitting changes no
--     capacity code at all;
--   * uq_trip_order_assignment_open_whole_order is a *partial* index on
--     (status = 'ACTIVE' AND whole_order), deliberately excluding the split case.
--
-- So the ledger exists. What is missing is the ceiling on it.
--
-- ---------------------------------------------------------------------------
-- 1. The running total, and why it lives on the order row
-- ---------------------------------------------------------------------------
--
-- The invariant is ALLOCATED <= ORDERED, and it has to survive two planners allocating the same
-- order in the same instant. A service-level check cannot do that: both transactions read the same
-- snapshot, both see room, both insert, and the order ends up 120% allocated with two perfectly
-- valid-looking rows.
--
-- A CHECK constraint can do it, but a CHECK cannot span rows - it cannot sum a ledger. The only
-- shape in which "allocated never exceeds ordered" is expressible to PostgreSQL is a running total
-- on a single row, which is what these three columns are.
--
-- That is a materialised invariant, not a cached fact, and the distinction is the whole
-- justification. ADR-009 argued a few hours ago that a derived figure should not be stored beside
-- the rows it comes from. It still should not - when the reason for storing it is convenience.
-- Here the reason is that storing it is the only way to make the rule a database guarantee rather
-- than a hope, which is the same reason uq_trip_order_assignment_open_whole_order exists at all.
--
-- Every write goes through OrderPlanningService under the order's row lock, so the ledger and this
-- total move together; ShipUnitAllocationConsistencyIntegrationTest recomputes one from the other
-- and asserts they agree.
ALTER TABLE tms.transport_order
    ADD COLUMN allocated_weight_kg numeric(14,3) NOT NULL DEFAULT 0,
    ADD COLUMN allocated_volume_m3 numeric(14,4) NOT NULL DEFAULT 0,
    ADD COLUMN allocated_pallets   numeric(12,2) NOT NULL DEFAULT 0;

-- The two halves of the rule, as two constraints so a violation says which one broke.
ALTER TABLE tms.transport_order
    ADD CONSTRAINT ck_transport_order_allocated_nonnegative CHECK (
        allocated_weight_kg >= 0 AND allocated_volume_m3 >= 0 AND allocated_pallets >= 0),
    -- THE invariant. An order cannot have more of itself on trucks than the customer ordered.
    ADD CONSTRAINT ck_transport_order_not_over_allocated CHECK (
        allocated_weight_kg <= total_weight_kg
        AND allocated_volume_m3 <= total_volume_m3
        AND allocated_pallets <= total_pallets);

COMMENT ON COLUMN tms.transport_order.allocated_weight_kg IS
    'The part of total_weight_kg currently committed to trips that have not closed out (V37). '
    'Maintained by OrderPlanningService under the order row lock, in the same transaction as every '
    'tms.trip_order_assignment write. Returns to zero at trip close-out: what was on the truck is '
    'by then either delivered or owed again, and neither is waiting on a shipment. '
    'ck_transport_order_not_over_allocated is what makes over-allocation impossible rather than '
    'merely refused - a CHECK cannot sum a ledger, so the running total has to live on one row.';

-- The pending pool a planner draws from. Partial because it is the hot read of the planning board
-- and it is a small live set inside a table that keeps every order the company ever took.
CREATE INDEX ix_transport_order_pending_allocation
    ON tms.transport_order (company_id, service_date)
    WHERE status = 'READY_FOR_PLANNING'
      AND (allocated_weight_kg > 0 OR allocated_volume_m3 > 0 OR allocated_pallets > 0);

COMMENT ON INDEX tms.ix_transport_order_pending_allocation IS
    'Part-planned orders (V37): ready to plan and already carrying an allocation, which is the set '
    'a planner needs to see distinctly - work that is half done reads differently from work nobody '
    'has started.';

-- ---------------------------------------------------------------------------
-- 2. A partial allocation has to be for something
-- ---------------------------------------------------------------------------
--
-- A whole-order row may legitimately be all zeros: an order whose weight, volume and pallet count
-- are all unknown is plannable (V10 defaults the totals to zero and the completeness check refuses
-- it elsewhere, not here). A *partial* row of all zeros is different - it claims to carry part of
-- an order while carrying nothing, occupies a stop, and makes the ledger say an order is
-- part-planned when nothing was planned.
ALTER TABLE tms.trip_order_assignment
    ADD CONSTRAINT ck_trip_order_assignment_partial_is_not_empty CHECK (
        whole_order
        OR assigned_weight_kg > 0
        OR assigned_volume_m3 > 0
        OR assigned_pallets > 0);

COMMENT ON COLUMN tms.trip_order_assignment.whole_order IS
    'Whether this row carries the entire order (V11, in use since V37). A split writes false and is '
    'therefore outside uq_trip_order_assignment_open_whole_order, which is exactly what V11 wrote '
    'that index as partial for. A false row must carry something: see '
    'ck_trip_order_assignment_partial_is_not_empty.';

-- ---------------------------------------------------------------------------
-- 3. One row per order per trip
-- ---------------------------------------------------------------------------
--
-- Splitting means one order on several *trips*. It does not mean one order on the same trip twice:
-- two rows on one trip are one load, one stop and one delivery record, and keeping them apart
-- would give the stop list two entries to reconcile and the delivery table an ambiguous parent.
-- A planner who wants more of an order on a trip changes that trip's allocation.
--
-- The whole-order case was already covered installation-wide by V11's index; this covers the split
-- case, per trip.
CREATE UNIQUE INDEX uq_trip_order_assignment_open_per_trip
    ON tms.trip_order_assignment (trip_id, order_id)
    WHERE status = 'ACTIVE';

COMMENT ON INDEX tms.uq_trip_order_assignment_open_per_trip IS
    'One open assignment per (trip, order) (V37). A split puts an order on several trips, never on '
    'the same trip twice - that would be one load and one stop described by two rows.';

-- ---------------------------------------------------------------------------
-- 4. No back-fill, and none is needed
-- ---------------------------------------------------------------------------
--
-- Every existing assignment is a whole-order row, so the correct allocated_* value for every order
-- that has one is its own totals - which is what this statement writes. Orders with no open
-- assignment keep the column default of zero. This is a derivation from rows that already exist,
-- not a guess: unlike V36, where inventing a delivery outcome nobody recorded would have been a
-- fabrication, here the ledger already says the answer and the column is only learning to hold it.
UPDATE tms.transport_order o
SET allocated_weight_kg = a.assigned_weight_kg,
    allocated_volume_m3 = a.assigned_volume_m3,
    allocated_pallets   = a.assigned_pallets
FROM tms.trip_order_assignment a
WHERE a.order_id = o.id
  AND a.company_id = o.company_id
  AND a.status = 'ACTIVE'
  AND a.whole_order
  -- Orders whose trip has already closed out hold no allocation any more (see the column comment),
  -- and neither do cancelled ones.
  AND o.status IN ('READY_FOR_PLANNING', 'PLANNED', 'IN_EXECUTION');

-- ---------------------------------------------------------------------------
-- Deliberately NOT here
-- ---------------------------------------------------------------------------
--
--   * No ship_unit table. A "ship unit" in this product is a portion of an order's demand in the
--     three measures a vehicle is constrained by, and that portion already has a row -
--     tms.trip_order_assignment. Minting 100 rows to split 100 pallets 70/30 would be faithful to
--     a bigger TMS and worse to operate: the planner's question is "how many of these go on this
--     truck", not "which of these hundred identical pallets".
--   * No delivered quantities. tms.order_delivery (V28) records an *outcome* per order per stop,
--     not an amount, and giving it amounts is a change to what a delivery means rather than an
--     addition to this ledger. "delivered is within what was allocated" holds structurally today -
--     a delivery can only be recorded against an order the trip is actually carrying - and a
--     numeric delivered-versus-allocated ledger waits for a requirement that needs it.
--   * No automatic splitting. Which 30 of the 100 go on the second truck is a planner's decision;
--     an engine proposing it is JOB 05's, and it will propose through this same ledger.
