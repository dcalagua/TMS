-- TMS by EBIM - V16 fleet hardening (Step 08 follow-up): an integration-facing external
-- reference on carrier/vehicle, and the vehicle double-booking invariant.
--
-- Two independent, small changes:
--   1. carrier.external_reference / vehicle.external_reference - a free-text pointer back to
--      whatever external fleet/ERP system this row was synced from, the same shape as
--      tms.transport_order.external_reference (V10) but without its external_source sibling:
--      fleet masters are not written by an inbound idempotency flow yet, so there is no "who
--      sent this" to pair it with - just "what do they call it".
--   2. tms.trip.planning_date, denormalized from tms.planning_run (rule 7, the same idiom
--      tms.trip.company_id already uses for the same reason: trip must carry a composite-FK-free
--      value locally to build the partial unique index below) plus the index itself: at most one
--      non-cancelled trip per vehicle per company per planning date. tms.trip has no reliable
--      planned *arrival* (only an optional planned_departure_at - see V11), so an interval
--      reservation is not a fact the schema can check; a one-active-trip-per-vehicle-per-day rule
--      is the simplest invariant the data actually supports, matching the step brief's "if the
--      domain only has planning date, a documented one-active-trip-per-vehicle-per-planning-day
--      rule is acceptable." TripService pre-checks the same rule with a caller-facing message;
--      this index is the concurrency backstop, the same relationship
--      uq_trip_order_assignment_open_whole_order (V11) has with its own service-level check.

-- ---------------------------------------------------------------------------
-- tms.carrier.external_reference
-- ---------------------------------------------------------------------------
ALTER TABLE tms.carrier ADD COLUMN external_reference text;

ALTER TABLE tms.carrier ADD CONSTRAINT ck_carrier_external_reference_not_blank
    CHECK (external_reference IS NULL OR btrim(external_reference) <> '');

COMMENT ON COLUMN tms.carrier.external_reference IS
    'Optional free-text pointer back to this carrier''s record in an external fleet/ERP system. '
    'Not normalized (case/format vary by source system) and not unique - integration matching is '
    'the connector''s job, not a database constraint.';

-- ---------------------------------------------------------------------------
-- tms.vehicle.external_reference
-- ---------------------------------------------------------------------------
ALTER TABLE tms.vehicle ADD COLUMN external_reference text;

ALTER TABLE tms.vehicle ADD CONSTRAINT ck_vehicle_external_reference_not_blank
    CHECK (external_reference IS NULL OR btrim(external_reference) <> '');

COMMENT ON COLUMN tms.vehicle.external_reference IS
    'Optional free-text pointer back to this vehicle''s record in an external fleet/ERP system - '
    'see tms.carrier.external_reference for the same reasoning.';

-- ---------------------------------------------------------------------------
-- tms.trip.planning_date and the double-booking invariant
-- ---------------------------------------------------------------------------
ALTER TABLE tms.trip ADD COLUMN planning_date date;

UPDATE tms.trip t
SET planning_date = pr.planning_date
FROM tms.planning_run pr
WHERE pr.id = t.planning_run_id;

ALTER TABLE tms.trip ALTER COLUMN planning_date SET NOT NULL;

COMMENT ON COLUMN tms.trip.planning_date IS
    'Denormalized from tms.planning_run.planning_date at trip creation (rule 7): a run''s '
    'planning_date never changes after creation (no PlanningRun method mutates it), so this copy '
    'can never drift. Exists so uq_trip_vehicle_active_planning_date below can be a plain column '
    'index instead of a query against another table.';

-- At most one non-cancelled trip per vehicle per company per planning date. Partial on
-- (status <> 'CANCELLED' AND vehicle_id IS NOT NULL): a cancelled trip releases its vehicle for
-- re-planning the same day, and a trip with no vehicle yet reserves nothing. Both DRAFT and
-- CONFIRMED count as "active" - a draft trip already represents a planner's intent to run that
-- vehicle that day, not merely a possibility.
CREATE UNIQUE INDEX uq_trip_vehicle_active_planning_date
    ON tms.trip (company_id, vehicle_id, planning_date)
    WHERE status <> 'CANCELLED' AND vehicle_id IS NOT NULL;

COMMENT ON INDEX tms.uq_trip_vehicle_active_planning_date IS
    'One active (DRAFT or CONFIRMED) trip per vehicle per planning date. The concurrency backstop '
    'for TripService''s own pre-check, the same relationship '
    'uq_trip_order_assignment_open_whole_order (V11) has with its service-level check.';
