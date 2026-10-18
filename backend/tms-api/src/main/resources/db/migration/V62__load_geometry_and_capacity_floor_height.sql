-- ============================================================================
-- V62 - Load geometry: stackable and nestable goods in capacity (ADR-022 sections 3-7, D-05)
-- ============================================================================
-- A line with a physical block (V59) is described by footprint: stacks, stack height, effective
-- volume and floor area (shared.geometry.LoadGeometryCalculator). The order carries the planning
-- volume capacity counts (effective where measured, naive elsewhere), the floor area and the
-- tallest stack. Capacity gains two dimensions, floor and height, through the V37 ledger and the
-- V25 trip snapshot.
--
-- Every existing row behaves exactly as before: planning_volume_m3 is backfilled with
-- total_volume_m3, floor areas are zero, heights null, and a vehicle type without dimensions has
-- no floor or height limit. No rows are seeded; no new table, permission or audit action.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. tms.transport_order_line - the geometry of one line
-- ---------------------------------------------------------------------------
ALTER TABLE tms.transport_order_line
    ADD COLUMN geometry_status      text,
    ADD COLUMN geometry_reason      text,
    ADD COLUMN stacks               integer,
    ADD COLUMN stack_height_m       numeric(9,3),
    ADD COLUMN effective_volume_m3  numeric(14,4),
    ADD COLUMN floor_area_m2        numeric(12,4);

ALTER TABLE tms.transport_order_line
    ADD CONSTRAINT ck_transport_order_line_geometry_status CHECK (
        geometry_status IS NULL OR geometry_status IN ('MEASURED', 'UNMEASURED', 'DOES_NOT_FIT')),
    -- Figures exist exactly when the status says they were computed; an unmeasured line keeps only
    -- its reason (nothing is estimated, EWM rule 199).
    ADD CONSTRAINT ck_transport_order_line_geometry_figures CHECK (
        (geometry_status IN ('MEASURED', 'DOES_NOT_FIT')
            AND stacks IS NOT NULL AND stack_height_m IS NOT NULL
            AND effective_volume_m3 IS NOT NULL AND floor_area_m2 IS NOT NULL)
        OR (COALESCE(geometry_status, 'UNMEASURED') = 'UNMEASURED'
            AND stacks IS NULL AND stack_height_m IS NULL
            AND effective_volume_m3 IS NULL AND floor_area_m2 IS NULL)),
    ADD CONSTRAINT ck_transport_order_line_geometry_reason CHECK (
        (geometry_status IS NULL AND geometry_reason IS NULL)
        OR (geometry_status = 'MEASURED' AND geometry_reason IS NULL)
        OR (geometry_status IN ('UNMEASURED', 'DOES_NOT_FIT') AND geometry_reason ~ '^[A-Z_]{3,40}$')),
    ADD CONSTRAINT ck_transport_order_line_geometry_positive CHECK (
        (stacks IS NULL OR stacks > 0)
        AND (stack_height_m IS NULL OR stack_height_m > 0)
        AND (effective_volume_m3 IS NULL OR effective_volume_m3 >= 0)
        AND (floor_area_m2 IS NULL OR floor_area_m2 >= 0));

COMMENT ON COLUMN tms.transport_order_line.geometry_status IS
    'Load geometry of the line (V62, ADR-022 section 3): MEASURED, UNMEASURED (block incomplete, '
    'reason says what is missing, declared volume kept) or DOES_NOT_FIT (one unit taller than the '
    'reference height; described as one unit per stack). Null when the line has no physical block.';
COMMENT ON COLUMN tms.transport_order_line.effective_volume_m3 IS
    'The cubic metres the line''s stacks really occupy, last stack exact (V62). What capacity counts '
    'for this line instead of line_volume_m3 (kept as the declared, naive figure).';

-- ---------------------------------------------------------------------------
-- 2. tms.transport_order - the order's geometry and the planning volume
-- ---------------------------------------------------------------------------
ALTER TABLE tms.transport_order
    ADD COLUMN planning_volume_m3          numeric(14,4),
    ADD COLUMN floor_area_m2               numeric(12,4) NOT NULL DEFAULT 0,
    ADD COLUMN max_stack_height_m          numeric(9,3),
    ADD COLUMN max_unit_height_m           numeric(9,3),
    ADD COLUMN geometry_unmeasured_lines   integer       NOT NULL DEFAULT 0,
    ADD COLUMN geometry_reference_height_m numeric(5,2),
    ADD COLUMN allocated_floor_area_m2     numeric(12,4) NOT NULL DEFAULT 0;

-- Every order predating V62 has no measured line, so what capacity counts is what it always did.
-- The updated_at trigger is held off for the statement (the V52 pattern): stamping every order
-- "updated now" would push all of them back through the order feed (V61, ?updatedSince=), which is
-- ordered by updated_at, for a change that is not a change to the order.
ALTER TABLE tms.transport_order DISABLE TRIGGER tr_transport_order_set_updated_at;
UPDATE tms.transport_order SET planning_volume_m3 = total_volume_m3;
ALTER TABLE tms.transport_order ENABLE TRIGGER tr_transport_order_set_updated_at;

-- A writer that predates V62 (a fixture, a script) names no planning volume: it is the total, the
-- same rule as the backfill. The application always writes it explicitly. A BEFORE trigger runs
-- before the NOT NULL check, so the column can be NOT NULL without a default that would lie.
CREATE FUNCTION tms.transport_order_default_planning_volume()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.planning_volume_m3 IS NULL THEN
        NEW.planning_volume_m3 := NEW.total_volume_m3;
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION tms.transport_order_default_planning_volume() FROM PUBLIC;

CREATE TRIGGER tr_transport_order_default_planning_volume
    BEFORE INSERT ON tms.transport_order
    FOR EACH ROW EXECUTE FUNCTION tms.transport_order_default_planning_volume();

ALTER TABLE tms.transport_order ALTER COLUMN planning_volume_m3 SET NOT NULL;

ALTER TABLE tms.transport_order
    ADD CONSTRAINT ck_transport_order_geometry_nonnegative CHECK (
        planning_volume_m3 >= 0 AND floor_area_m2 >= 0 AND allocated_floor_area_m2 >= 0
        AND geometry_unmeasured_lines >= 0
        AND (max_stack_height_m IS NULL OR max_stack_height_m > 0)
        AND (max_unit_height_m IS NULL OR max_unit_height_m > 0)
        AND (geometry_reference_height_m IS NULL OR geometry_reference_height_m > 0));

-- V37's ledger guard, restated: volume is now measured against what capacity counts (the planning
-- volume), and the floor joins the measures. Existing rows satisfy it because planning = total and
-- both floors are zero.
ALTER TABLE tms.transport_order DROP CONSTRAINT ck_transport_order_not_over_allocated;
ALTER TABLE tms.transport_order
    ADD CONSTRAINT ck_transport_order_not_over_allocated CHECK (
        allocated_weight_kg <= total_weight_kg
        AND allocated_volume_m3 <= planning_volume_m3
        AND allocated_pallets <= total_pallets
        AND allocated_floor_area_m2 <= floor_area_m2);

COMMENT ON COLUMN tms.transport_order.planning_volume_m3 IS
    'What capacity counts (V62, ADR-022 section 4): SUM(effective_volume_m3) of lines with geometry '
    'figures + SUM(line volume) of the rest; equals total_volume_m3 when no line is measured. '
    'total_volume_m3 stays the declared (naive) figure for audit.';
COMMENT ON COLUMN tms.transport_order.max_stack_height_m IS
    'The tallest stack over the lines with geometry figures (V62). Never summed: a load is as tall as '
    'its tallest stack.';
COMMENT ON COLUMN tms.transport_order.max_unit_height_m IS
    'The tallest single unit over the lines with geometry figures (V62): a vehicle lower than this '
    'cannot take the order at all (ITEM_TALLER_THAN_VEHICLE), however it is re-stacked.';
COMMENT ON COLUMN tms.transport_order.geometry_reference_height_m IS
    'The usable height H the lines were computed with: company_settings.reference_load_height_m, '
    'else the property tms.planning.geometry.default-reference-height-m (V62).';
COMMENT ON COLUMN tms.transport_order.allocated_floor_area_m2 IS
    'The part of floor_area_m2 on trips that have not closed out (V62, the V37 ledger''s fourth '
    'measure).';

-- ---------------------------------------------------------------------------
-- 3. tms.trip_order_assignment - the ledger carries floor and height
-- ---------------------------------------------------------------------------
ALTER TABLE tms.trip_order_assignment
    ADD COLUMN assigned_floor_area_m2  numeric(12,4) NOT NULL DEFAULT 0,
    ADD COLUMN assigned_stack_height_m numeric(9,3);

ALTER TABLE tms.trip_order_assignment DROP CONSTRAINT ck_trip_order_assignment_amounts_nonnegative;
ALTER TABLE tms.trip_order_assignment
    ADD CONSTRAINT ck_trip_order_assignment_amounts_nonnegative CHECK (
        assigned_weight_kg >= 0 AND assigned_volume_m3 >= 0 AND assigned_pallets >= 0
        AND assigned_floor_area_m2 >= 0
        AND (assigned_stack_height_m IS NULL OR assigned_stack_height_m > 0));

COMMENT ON COLUMN tms.trip_order_assignment.assigned_stack_height_m IS
    'The tallest stack this allocation brings onto the trip (V62). A slice is still made of the '
    'order''s stacks, so it carries the order''s max_stack_height_m. Capacity takes the MAX, never a sum.';

-- ---------------------------------------------------------------------------
-- 4. tms.trip - the snapshot frozen at confirmation gains floor, height and width
-- ---------------------------------------------------------------------------
ALTER TABLE tms.trip
    ADD COLUMN snapshot_max_floor_area_m2  numeric(10,3),
    ADD COLUMN snapshot_max_stack_height_m numeric(6,3),
    ADD COLUMN snapshot_load_width_m       numeric(6,3);

ALTER TABLE tms.trip
    ADD CONSTRAINT ck_trip_snapshot_geometry_positive CHECK (
        (snapshot_max_floor_area_m2 IS NULL OR snapshot_max_floor_area_m2 > 0)
        AND (snapshot_max_stack_height_m IS NULL OR snapshot_max_stack_height_m > 0)
        AND (snapshot_load_width_m IS NULL OR snapshot_load_width_m > 0));

-- V25's guard, restated to cover the new snapshot columns. ck_trip_confirmed_is_complete is not
-- widened: a vehicle type without dimensions still confirms (null = unlimited).
ALTER TABLE tms.trip DROP CONSTRAINT ck_trip_draft_has_no_snapshot;
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_draft_has_no_snapshot CHECK (
    status <> 'DRAFT' OR (
        snapshot_max_weight_kg IS NULL
        AND snapshot_max_volume_m3 IS NULL
        AND snapshot_max_pallets IS NULL
        AND snapshot_max_floor_area_m2 IS NULL
        AND snapshot_max_stack_height_m IS NULL
        AND snapshot_load_width_m IS NULL
        AND capacity_snapshot_at IS NULL));

-- ---------------------------------------------------------------------------
-- 5. Floor fill factor and reference height - configurable, never fixed
-- ---------------------------------------------------------------------------
ALTER TABLE tms.vehicle_type
    ADD COLUMN load_floor_fill_factor numeric(4,3);
ALTER TABLE tms.vehicle_type
    ADD CONSTRAINT ck_vehicle_type_load_floor_fill_factor CHECK (
        load_floor_fill_factor IS NULL OR (load_floor_fill_factor > 0 AND load_floor_fill_factor <= 1));

COMMENT ON COLUMN tms.vehicle_type.load_floor_fill_factor IS
    'Share of length_m x width_m a load can really cover (V62). Null = the company''s '
    'company_settings.load_floor_fill_factor, else the property '
    'tms.planning.geometry.default-floor-fill-factor.';

ALTER TABLE tms.company_settings
    ADD COLUMN reference_load_height_m numeric(5,2),
    ADD COLUMN load_floor_fill_factor  numeric(4,3);
ALTER TABLE tms.company_settings
    ADD CONSTRAINT ck_company_settings_reference_load_height CHECK (
        reference_load_height_m IS NULL OR reference_load_height_m > 0),
    ADD CONSTRAINT ck_company_settings_load_floor_fill_factor CHECK (
        load_floor_fill_factor IS NULL OR (load_floor_fill_factor > 0 AND load_floor_fill_factor <= 1));

COMMENT ON COLUMN tms.company_settings.reference_load_height_m IS
    'The usable height H order lines are stacked against when they are written (V62). Null = the '
    'property tms.planning.geometry.default-reference-height-m.';
