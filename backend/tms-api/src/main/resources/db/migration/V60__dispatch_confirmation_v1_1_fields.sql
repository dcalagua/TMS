-- ============================================================================
-- V60 - DISPATCH_CONFIRMED v1.1 fields (WAREHOUSE_EXECUTION_V1 §7.3, ADR-025, ADR-022)
-- ============================================================================
-- Contract v1.1 lets the warehouse say two more things about what physically left:
--
-- 1. tms.external_dispatch.vehicle_trailer_plate: the trailer, when there is one. TMS has no
--    trailer master, so the plate is stored and shown and never compared: VEHICLE_MISMATCH keeps
--    comparing vehicle_license_plate (the tractor / truck) only.
-- 2. tms.external_dispatch_order.stacks / effective_volume_m3 / loading_meters / geometry_reason:
--    the load geometry the warehouse computed for each order with the footprint rules of ADR-022
--    section 3. NULL when the warehouse could not compute it, with geometry_reason saying why
--    (e.g. NEST_INCREMENT_MISSING).
--
-- Stored only (phase 30). Comparing them with the plan as GEOMETRY_VARIANCE (INFO, never a
-- MISMATCH) is phase 45. Every column is nullable and every existing row keeps NULL: a v1 document
-- carried none of them, and NULL means "not said", never zero. No backfill, no rows.
--
-- Same column conventions as V53: text with a length CHECK rather than varchar, so the limit is a
-- named constraint the integration validation mirrors (@Size). Both tables keep their V53 grants
-- (SELECT, INSERT; external_dispatch also UPDATE for supersession) and their RLS policies; a new
-- column inherits them, so no grant changes here.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. The trailer plate on the dispatch document
-- ---------------------------------------------------------------------------
ALTER TABLE tms.external_dispatch
    ADD COLUMN vehicle_trailer_plate text;

ALTER TABLE tms.external_dispatch
    ADD CONSTRAINT ck_external_dispatch_trailer_plate CHECK (
        vehicle_trailer_plate IS NULL
        OR (btrim(vehicle_trailer_plate) <> '' AND length(vehicle_trailer_plate) <= 20));

COMMENT ON COLUMN tms.external_dispatch.vehicle_trailer_plate IS
    'The trailer that left, as the warehouse stated it (contract v1.1, §7.3). Stored and shown; TMS '
    'has no trailer master and never compares it. VEHICLE_MISMATCH compares vehicle_license_plate '
    '(the tractor / truck) only. NULL when the document named no trailer.';

-- ---------------------------------------------------------------------------
-- 2. The load geometry per dispatched order
-- ---------------------------------------------------------------------------
ALTER TABLE tms.external_dispatch_order
    ADD COLUMN stacks               integer,
    ADD COLUMN effective_volume_m3  numeric(14,4),
    ADD COLUMN loading_meters       numeric(10,3),
    ADD COLUMN geometry_reason      text;

ALTER TABLE tms.external_dispatch_order
    ADD CONSTRAINT ck_external_dispatch_order_geometry_nonnegative CHECK (
        coalesce(stacks, 0) >= 0 AND coalesce(effective_volume_m3, 0) >= 0
        AND coalesce(loading_meters, 0) >= 0),
    ADD CONSTRAINT ck_external_dispatch_order_geometry_reason CHECK (
        geometry_reason IS NULL OR (btrim(geometry_reason) <> '' AND length(geometry_reason) <= 60));

COMMENT ON COLUMN tms.external_dispatch_order.stacks IS
    'Stacks the warehouse built for this order (contract v1.1, ADR-022 section 3). NULL = not said '
    '(a v1 document, or an incomplete profile, see geometry_reason) - never zero for unknown.';
COMMENT ON COLUMN tms.external_dispatch_order.effective_volume_m3 IS
    'Volume the order occupies once stacked, in m3, as the warehouse computed it (contract v1.1). '
    'Not volume_m3, which is the declared goods volume.';
COMMENT ON COLUMN tms.external_dispatch_order.loading_meters IS
    'Loading meters the order takes on the deck, as the warehouse computed it (contract v1.1).';
COMMENT ON COLUMN tms.external_dispatch_order.geometry_reason IS
    'Why the warehouse could not compute the geometry (e.g. NEST_INCREMENT_MISSING), stored verbatim. '
    'Stored only until phase 45 compares geometry with the plan as GEOMETRY_VARIANCE (INFO).';
