-- ============================================================================
-- V68 - Cost to serve: allocating a trip's cost to its orders (ADR-026, phase 70)
-- ============================================================================
-- TMS knows what a trip cost (tms.trip_cost, V30), what a carrier billed for it (V46/V66) and what
-- the trip carried (tms.trip_order_assignment, V11/V37/V62), but not what an order cost. That is
-- debt D10 of the capability map, and the question finance asks first: what does it cost to serve
-- this customer, this destination, this route?
--
--   tms.company_settings.cost_allocation_basis  the base, per company (null = the deployment default)
--   tms.trip_cost_allocation_run                one allocation of one trip, versioned, never rewritten
--   tms.trip_cost_allocation                    one row per assignment of that run, append-only
--
-- Decision D-08: the base is configurable and there is no margin - TMS has no sales tariff, so it
-- does not invent revenue. ADR-026 section 6: an order whose base is unknown receives nothing; the
-- cost is split among the measurable ones and the rest is counted.
--
-- No business rows are seeded. Two permissions are (V46 shape), and the audit vocabulary grows.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. The base, per company
-- ---------------------------------------------------------------------------
-- Nullable: null is "the deployment's tms.finance.cost-allocation.default-basis" (WEIGHT), so a
-- company that never opens the setting follows the deployment and not a value frozen here.
ALTER TABLE tms.company_settings
    ADD COLUMN cost_allocation_basis text;

ALTER TABLE tms.company_settings ADD CONSTRAINT ck_company_settings_cost_allocation_basis
    CHECK (cost_allocation_basis IS NULL OR cost_allocation_basis IN (
        'WEIGHT', 'EFFECTIVE_VOLUME', 'FLOOR_AREA', 'PALLETS', 'STOPS', 'EQUAL'));

COMMENT ON COLUMN tms.company_settings.cost_allocation_basis IS
    'How a trip''s cost is split between its orders (V68, ADR-026, D-08). Null = the deployment '
    'default (tms.finance.cost-allocation.default-basis).';

-- ---------------------------------------------------------------------------
-- 2. The run: one allocation of one trip
-- ---------------------------------------------------------------------------
CREATE TABLE tms.trip_cost_allocation_run (
    id                       uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id               uuid          NOT NULL,
    trip_id                  uuid          NOT NULL,
    -- 1, 2, ... per trip. Recalculating inserts the next version; it never rewrites this one.
    version                  integer       NOT NULL,
    basis                    text          NOT NULL,
    -- Which figure was split: the approved invoices of the trip (net of IGV, ADR-024), the closed
    -- actual, or the estimate - in that order of preference.
    cost_source              text          NOT NULL,
    currency                 char(3)       NOT NULL,
    cost_amount              numeric(14,2) NOT NULL,
    -- What reached an order: cost_amount when any order was measurable, 0 when none was.
    allocated_amount         numeric(14,2) NOT NULL,
    order_count              integer       NOT NULL,
    unallocated_order_count  integer       NOT NULL,
    -- Why some (or all) orders received nothing. Null when every order was measured.
    unallocated_reason       text,
    -- The calendar day the cost belongs to: the departure in the company's zone, else the planning
    -- date. What the cost-to-serve report filters on.
    service_date             date          NOT NULL,
    -- Copied from the trip at computing time, so the report reads this module's tables only.
    shipment_number          text,
    carrier_id               uuid,
    route_id                 uuid,
    route_label              text,
    computed_at              timestamptz   NOT NULL DEFAULT now(),
    -- Null for the sweep: the system is not a person and does not pretend to be one.
    computed_by              uuid,
    trigger                  text          NOT NULL,
    -- Set once, when the next version is inserted. The only column that ever changes.
    superseded_at            timestamptz,

    CONSTRAINT pk_trip_cost_allocation_run PRIMARY KEY (id),
    CONSTRAINT uq_trip_cost_allocation_run_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_trip_cost_allocation_run_version UNIQUE (trip_id, version),

    CONSTRAINT fk_trip_cost_allocation_run_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_cost_allocation_run_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_cost_allocation_run_computed_by FOREIGN KEY (computed_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,

    CONSTRAINT ck_trip_cost_allocation_run_version CHECK (version >= 1),
    CONSTRAINT ck_trip_cost_allocation_run_basis CHECK (basis IN (
        'WEIGHT', 'EFFECTIVE_VOLUME', 'FLOOR_AREA', 'PALLETS', 'STOPS', 'EQUAL')),
    CONSTRAINT ck_trip_cost_allocation_run_cost_source CHECK (cost_source IN ('INVOICED', 'ACTUAL', 'ESTIMATED')),
    CONSTRAINT ck_trip_cost_allocation_run_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_trip_cost_allocation_run_amounts CHECK (
        cost_amount >= 0 AND allocated_amount >= 0 AND allocated_amount <= cost_amount),
    -- Either the whole cost reached the measurable orders, or nothing did (no measurable order).
    CONSTRAINT ck_trip_cost_allocation_run_all_or_nothing CHECK (
        allocated_amount = cost_amount OR allocated_amount = 0),
    CONSTRAINT ck_trip_cost_allocation_run_counts CHECK (
        order_count >= 0 AND unallocated_order_count >= 0 AND unallocated_order_count <= order_count),
    CONSTRAINT ck_trip_cost_allocation_run_reason CHECK (
        unallocated_reason IS NULL OR unallocated_reason IN ('NO_BASIS', 'NO_MEASURABLE_ORDER', 'NO_ORDERS')),
    CONSTRAINT ck_trip_cost_allocation_run_reason_needed CHECK (
        (unallocated_order_count = 0 AND order_count > 0) = (unallocated_reason IS NULL)),
    CONSTRAINT ck_trip_cost_allocation_run_trigger CHECK (trigger IN (
        'TRIP_EXECUTED', 'TRIP_COST_CLOSED', 'INVOICE_APPROVED', 'MANUAL'))
);

-- THE invariant of versioning: exactly one current run per trip. Two nodes recalculating at once
-- both try to supersede and insert; one of them fails here (or on the version) and retries nothing.
CREATE UNIQUE INDEX uq_trip_cost_allocation_run_current
    ON tms.trip_cost_allocation_run (trip_id) WHERE superseded_at IS NULL;

CREATE INDEX ix_trip_cost_allocation_run_company_date
    ON tms.trip_cost_allocation_run (company_id, service_date) WHERE superseded_at IS NULL;

COMMENT ON TABLE tms.trip_cost_allocation_run IS
    'One allocation of one trip''s cost to its orders (V68, ADR-026): basis, cost source, version. '
    'Recalculating inserts version + 1 and supersedes this one; nothing else ever changes.';

-- ---------------------------------------------------------------------------
-- 3. The rows: what each order received
-- ---------------------------------------------------------------------------
CREATE TABLE tms.trip_cost_allocation (
    id                 uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id         uuid          NOT NULL,
    run_id             uuid          NOT NULL,
    trip_id            uuid          NOT NULL,
    order_id           uuid          NOT NULL,
    assignment_id      uuid          NOT NULL,
    -- What the order was, copied at computing time, so the report groups without reading orders.
    order_number       text          NOT NULL,
    customer_label     text,
    destination_id     uuid,
    destination_label  text,
    -- What the trip carried for this order (the ledger row), the measures the report divides by.
    weight_kg          numeric(14,3) NOT NULL,
    volume_m3          numeric(14,4) NOT NULL,
    pallets            numeric(12,2) NOT NULL,
    floor_area_m2      numeric(12,4) NOT NULL,
    -- The measure of the run's basis for this row; null when the row could not be measured.
    basis_quantity     numeric(18,6),
    share              numeric(9,6),
    allocated_amount   numeric(14,2),
    reason             text,

    CONSTRAINT pk_trip_cost_allocation PRIMARY KEY (id),
    CONSTRAINT uq_trip_cost_allocation_run_assignment UNIQUE (run_id, assignment_id),

    CONSTRAINT fk_trip_cost_allocation_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_cost_allocation_run_company FOREIGN KEY (run_id, company_id)
        REFERENCES tms.trip_cost_allocation_run (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_cost_allocation_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_cost_allocation_order_company FOREIGN KEY (order_id, company_id)
        REFERENCES tms.transport_order (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_cost_allocation_assignment FOREIGN KEY (assignment_id)
        REFERENCES tms.trip_order_assignment (id) ON DELETE RESTRICT,

    CONSTRAINT ck_trip_cost_allocation_measures CHECK (
        weight_kg >= 0 AND volume_m3 >= 0 AND pallets >= 0 AND floor_area_m2 >= 0),
    CONSTRAINT ck_trip_cost_allocation_share CHECK (share IS NULL OR (share >= 0 AND share <= 1)),
    CONSTRAINT ck_trip_cost_allocation_amount CHECK (allocated_amount IS NULL OR allocated_amount >= 0),
    -- A row either received its part, or says why it received nothing: no invented allocation.
    CONSTRAINT ck_trip_cost_allocation_received_or_reason CHECK (
        (allocated_amount IS NOT NULL AND share IS NOT NULL AND basis_quantity IS NOT NULL AND reason IS NULL)
        OR (allocated_amount IS NULL AND share IS NULL AND reason IS NOT NULL)),
    CONSTRAINT ck_trip_cost_allocation_reason CHECK (reason IS NULL OR reason IN ('NO_BASIS'))
);

CREATE INDEX ix_trip_cost_allocation_run ON tms.trip_cost_allocation (run_id);
CREATE INDEX ix_trip_cost_allocation_order ON tms.trip_cost_allocation (company_id, order_id);

COMMENT ON TABLE tms.trip_cost_allocation IS
    'What each order of a trip received from one allocation run (V68, ADR-026). A row without a '
    'measurable basis receives nothing and says NO_BASIS. Append-only by trigger and by grant.';

-- ---------------------------------------------------------------------------
-- 4. Append-only
-- ---------------------------------------------------------------------------
-- The rows: never updated, never deleted (V65's function shape).
CREATE FUNCTION tms.trip_cost_allocation_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'tms.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

REVOKE ALL ON FUNCTION tms.trip_cost_allocation_append_only() FROM PUBLIC;

CREATE TRIGGER tr_trip_cost_allocation_append_only
    BEFORE UPDATE OR DELETE ON tms.trip_cost_allocation
    FOR EACH ROW EXECUTE FUNCTION tms.trip_cost_allocation_append_only();

-- The run: never deleted, and updated only to be superseded, once. ADR-026 section 4 left the
-- mechanism to the implementation; this is it: a column-level grant (superseded_at only) for the
-- runtime role, and a guard that holds against the owner too - a scheduled job or a hand-run script.
CREATE FUNCTION tms.trip_cost_allocation_run_guard()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'tms.% is append-only: DELETE is not allowed', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.superseded_at IS NOT NULL OR NEW.superseded_at IS NULL
            OR (to_jsonb(NEW) - 'superseded_at') IS DISTINCT FROM (to_jsonb(OLD) - 'superseded_at') THEN
        RAISE EXCEPTION 'tms.% is append-only: a run is only ever superseded, once', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION tms.trip_cost_allocation_run_guard() FROM PUBLIC;

CREATE TRIGGER tr_trip_cost_allocation_run_guard
    BEFORE UPDATE OR DELETE ON tms.trip_cost_allocation_run
    FOR EACH ROW EXECUTE FUNCTION tms.trip_cost_allocation_run_guard();

-- ---------------------------------------------------------------------------
-- 5. Tenant isolation (ADR-005) and least privilege (V50)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.trip_cost_allocation_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.trip_cost_allocation ENABLE ROW LEVEL SECURITY;

-- V13's default privileges attach all four verbs at CREATE TABLE; the REVOKEs are what withhold.
REVOKE UPDATE, DELETE ON tms.trip_cost_allocation_run FROM tms_app;
GRANT SELECT, INSERT ON tms.trip_cost_allocation_run TO tms_app;
GRANT UPDATE (superseded_at) ON tms.trip_cost_allocation_run TO tms_app;
REVOKE UPDATE, DELETE ON tms.trip_cost_allocation FROM tms_app;
GRANT SELECT, INSERT ON tms.trip_cost_allocation TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.trip_cost_allocation_run
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.trip_cost_allocation
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 6. Permissions (V46 shape)
-- ---------------------------------------------------------------------------
INSERT INTO tms.permission (resource, action, description) VALUES
    ('settlement.cost_allocation', 'read',   'View the cost to serve: what each order, customer, destination and route cost'),
    ('settlement.cost_allocation', 'manage', 'Recalculate the allocation of a trip''s cost to its orders');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN', 'PLANNER', 'VIEWER')
  AND p.code = 'settlement.cost_allocation:read';

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code = 'settlement.cost_allocation:manage';

-- ---------------------------------------------------------------------------
-- 7. Audit vocabulary (the whole list, restated)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
    'CREATE', 'UPDATE', 'ACTIVATE', 'DEACTIVATE', 'ASSIGN_ORDER', 'REMOVE_ORDER', 'MOVE_ORDER',
    'VEHICLE_CHANGE', 'DRIVER_CHANGE', 'CONFIRM', 'CANCEL', 'CREDENTIAL_CREATE',
    'CREDENTIAL_ROTATE', 'CREDENTIAL_REVOKE', 'AUTO_PLAN', 'IMPORT_EXECUTED', 'SHIPMENT_CONFIRMED',
    'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED', 'SHIPMENT_CANCELLED',
    'DELIVERY_RESULT_RECORDED', 'COST_ESTIMATED', 'COST_ACTUAL_RECORDED', 'COST_CLOSED',
    'COST_REOPENED',
    'TENDER_SENT', 'TENDER_ACCEPTED', 'TENDER_REJECTED', 'TENDER_EXPIRED', 'TENDER_CANCELLED',
    'ROLES_CHANGED', 'ORDER_REOPENED',
    'WATERFALL_STARTED', 'WATERFALL_ENDED',
    'APPOINTMENT_BOOKED', 'APPOINTMENT_RESCHEDULED', 'APPOINTMENT_CANCELLED', 'APPOINTMENT_NO_SHOW',
    'RESOURCE_BLOCKED', 'RESOURCE_RELEASED',
    'INVOICE_RECEIVED', 'INVOICE_MATCHED', 'INVOICE_APPROVED', 'INVOICE_REJECTED',
    'INVOICE_EXPORTED',
    'DISPATCH_OVERRIDDEN',
    'ORDER_RELEASED', 'ORDER_HOLD_PLACED', 'ORDER_HOLD_RELEASED',
    -- V59 (ADR-022).
    'MATERIAL_PROFILE_CREATED', 'MATERIAL_PROFILE_UPDATED',
    -- V64 (ADR-024 part 1).
    'TOLERANCE_POLICY_CHANGED',
    -- V65 (ADR-023 section 1).
    'ACCRUAL_CREATED', 'ACCRUAL_ADJUSTED', 'ACCRUAL_CONSUMED', 'ACCRUAL_REVERSED',
    'ACCRUAL_JOURNAL_EXPORTED', 'GL_MAPPING_CHANGED',
    -- V66 (ADR-024 part 2).
    'CREDIT_NOTE_RECEIVED', 'DEBIT_NOTE_RECEIVED', 'PENALTY_ACCEPTED', 'RETENTION_OVERRIDDEN',
    -- V67 (ADR-023 section 2).
    'PAYABLE_SCHEDULED', 'PAYABLE_HOLD_PLACED', 'PAYABLE_HOLD_RELEASED',
    'PAYMENT_REQUESTED', 'PAYMENT_REQUEST_REJECTED', 'PAYMENT_RECORDED',
    -- V68 (ADR-026).
    'COST_ALLOCATED'));

-- ---------------------------------------------------------------------------
-- 8. The carrier boundary for the finance internals (ADR-018, V57; post-review fix)
-- ---------------------------------------------------------------------------
-- V67 hid the office's settlement workflow from a carrier transaction (match, discrepancies, export,
-- payment requests). Accruals, their movements, the GL mapping (V65) and the cost-to-serve runs and
-- lines (this migration) are the same kind of internal figure and were left out: a carrier session
-- reaching them would see what the shipper accrues and what each customer costs. RESTRICTIVE, so
-- ANDed with p_tenant_company_scope; an office transaction (NULL tms.current_carrier_id()) is left
-- alone. Created here, after V65, because V68 is the last finance migration of the phase.
DO $$
DECLARE
    internal text;
BEGIN
    FOREACH internal IN ARRAY ARRAY['freight_accrual', 'freight_accrual_entry', 'gl_account_mapping',
                                    'trip_cost_allocation_run', 'trip_cost_allocation']
    LOOP
        EXECUTE format(
            'CREATE POLICY p_carrier_scope ON tms.%I AS RESTRICTIVE FOR ALL TO tms_app
                 USING (tms.current_carrier_id() IS NULL)
                 WITH CHECK (tms.current_carrier_id() IS NULL)', internal);
    END LOOP;
END
$$;
