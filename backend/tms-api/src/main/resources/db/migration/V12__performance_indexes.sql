-- =============================================================================
-- V12 - Performance indexes for the planning read paths
--
-- No structural change: this migration adds indexes only. It exists because the
-- step 12 hardening review sized the existing indexes against the design target
-- (10,000+ orders/day, 100-300 vehicles) and found two read paths whose plans
-- degrade linearly with the amount of *history* in the table rather than with the
-- amount of work in front of the planner.
--
-- Applied migrations are immutable (ADR-002), so the indexes are added here
-- instead of being edited into V10/V11.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- The eligible-order pool
-- ---------------------------------------------------------------------------
-- GET /api/v1/planning/eligible-orders is the query a planner runs repeatedly
-- for a whole shift. OrderPlanningService.searchAssignable resolves to:
--
--     WHERE company_id = ? AND status = 'READY_FOR_PLANNING'
--       AND origin_id = ? AND service_date = ?
--     ORDER BY service_date, order_number
--
-- The best index available until now was ix_transport_order_company_status. Its
-- READY_FOR_PLANNING portion is the whole backlog of every origin and every future
-- date, so the planner's one origin/one date question had to scan - and then sort -
-- a set that grows with the backlog rather than with the answer.
--
-- Partial on the status for two reasons: a planned, cancelled or not-yet-ready order
-- can never appear in this result, and an order leaves the index entirely when it is
-- assigned - so the index stays proportional to the work outstanding, not to the
-- 10,000 orders/day flowing through the table. Trailing order_number makes the ORDER BY
-- of the default sort a plain index read rather than a sort node.
CREATE INDEX ix_transport_order_planning_pool
    ON tms.transport_order (company_id, origin_id, service_date, order_number)
    WHERE status = 'READY_FOR_PLANNING';

-- ---------------------------------------------------------------------------
-- The order list page
-- ---------------------------------------------------------------------------
-- GET /api/v1/orders filters on company plus, in the overwhelmingly common case, a
-- status and a service-date range, and sorts by service_date descending by default
-- (OrderService.toPageable). Neither ix_transport_order_company_status nor
-- ix_transport_order_company_service_date can serve both halves: one filters and the
-- other sorts, so PostgreSQL picks one and sorts the remainder.
--
-- Not partial: unlike the pool above, this page is used to look at every status,
-- including the cancelled and already-planned history.
CREATE INDEX ix_transport_order_company_status_service_date
    ON tms.transport_order (company_id, status, service_date DESC);

-- ---------------------------------------------------------------------------
-- Trip lookup by run
-- ---------------------------------------------------------------------------
-- The planning board (PlanningRunService.toDetail) and both run-wide mutations read
-- TripRepository.findByPlanningRunIdOrderByTripNumberAsc. ix_trip_planning_run answers
-- the WHERE but leaves the ORDER BY to a sort; carrying trip_number in the index makes
-- the board read ordered rows directly. Cheap, and the board is the screen a planner
-- keeps open all day.
CREATE INDEX ix_trip_planning_run_number ON tms.trip (planning_run_id, trip_number);

COMMENT ON INDEX tms.ix_transport_order_planning_pool IS
    'Partial index over the READY_FOR_PLANNING backlog only: serves '
    'GET /planning/eligible-orders (company + origin + service date, ordered by order number) '
    'and shrinks as orders are assigned. See docs/performance/PERFORMANCE_BASELINE.md.';

COMMENT ON INDEX tms.ix_transport_order_company_status_service_date IS
    'Serves the order list page: company + status filter with the default service_date DESC sort '
    'in one index read. See docs/performance/PERFORMANCE_BASELINE.md.';

COMMENT ON INDEX tms.ix_trip_planning_run_number IS
    'Serves the planning board: the trips of one run, already ordered by trip number.';
