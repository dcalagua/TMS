-- ============================================================================
-- V59 - Material physical profile and its snapshot on the order line (ADR-022 sections 1-2)
-- ============================================================================
-- The warehouse (EWM) owns the physical profile of a material (D-02): its measures in loading
-- orientation, how it stacks or nests, and how much weight it bears. TMS keeps a dated copy per
-- company and material_code, written only by the integration API (WAREHOUSE_EXECUTION_V1 7.1), and
-- copies it onto an order line when the line is created or replaced. A later profile change does
-- not rewrite existing lines: the line keeps the block it was planned with.
--
-- No geometry is computed here. LoadGeometryCalculator and the capacity dimensions that consume
-- these columns are ADR-022 sections 3-7 (V62). No rows are seeded.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. tms.material_profile
-- ---------------------------------------------------------------------------
CREATE TABLE tms.material_profile (
    id                      uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id              uuid          NOT NULL,
    -- Byte for byte the code the ERP puts on transport_order_line.material_code: no case folding,
    -- the same rule as sourceSystem. Matching is exact.
    material_code           text          NOT NULL,
    description             text,
    -- The producer of the copy (EWM_EBIM), not a product name.
    source_system           text          NOT NULL,
    -- When the owner changed it. A write older than this one is STALE and changes nothing.
    source_updated_at       timestamptz   NOT NULL,
    -- One unit in its loading orientation.
    length_m                numeric(8,4),
    width_m                 numeric(8,4),
    height_m                numeric(8,4),
    unit_weight_kg          numeric(10,3),
    unit_volume_m3          numeric(10,4),
    -- NULL = not declared. Nothing is estimated from a missing mode (EWM rule 199).
    stack_mode              text,
    -- Units per stack, the meaning of EWM md_item.stack_max_quantity.
    stack_max_quantity      integer,
    -- How much a nested unit adds to the stack. Required for NESTABLE, absent otherwise.
    nest_increment_m        numeric(8,4),
    -- Crush limit of the bottom unit.
    max_top_load_kg         numeric(12,3),
    orientation_locked      boolean       NOT NULL DEFAULT false,
    -- The credential that wrote the current content; NULL only if that credential is gone.
    updated_by_client       uuid,
    version                 bigint        NOT NULL DEFAULT 0,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_material_profile PRIMARY KEY (id),
    CONSTRAINT uq_material_profile_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_material_profile_code UNIQUE (company_id, material_code),
    CONSTRAINT fk_material_profile_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_material_profile_client_company FOREIGN KEY (updated_by_client, company_id)
        REFERENCES tms.integration_client (id, company_id),
    CONSTRAINT ck_material_profile_code CHECK (
        btrim(material_code) <> '' AND length(material_code) <= 64),
    CONSTRAINT ck_material_profile_description CHECK (
        description IS NULL OR length(description) <= 200),
    CONSTRAINT ck_material_profile_source_system CHECK (source_system ~ '^[A-Z0-9_]{2,40}$'),
    CONSTRAINT ck_material_profile_measures_positive CHECK (
        (length_m IS NULL OR length_m > 0) AND (width_m IS NULL OR width_m > 0)
        AND (height_m IS NULL OR height_m > 0)),
    CONSTRAINT ck_material_profile_unit_weight_positive CHECK (unit_weight_kg IS NULL OR unit_weight_kg > 0),
    CONSTRAINT ck_material_profile_unit_volume_positive CHECK (unit_volume_m3 IS NULL OR unit_volume_m3 > 0),
    CONSTRAINT ck_material_profile_stack_mode CHECK (
        stack_mode IS NULL OR stack_mode IN ('NONE', 'STACKABLE', 'NESTABLE')),
    CONSTRAINT ck_material_profile_stack_max_quantity_positive CHECK (
        stack_max_quantity IS NULL OR stack_max_quantity > 0),
    CONSTRAINT ck_material_profile_nest_increment CHECK (
        CASE WHEN stack_mode = 'NESTABLE' THEN nest_increment_m IS NOT NULL AND nest_increment_m > 0
             ELSE nest_increment_m IS NULL END),
    CONSTRAINT ck_material_profile_max_top_load_positive CHECK (max_top_load_kg IS NULL OR max_top_load_kg > 0)
);

CREATE TRIGGER tr_material_profile_set_updated_at
    BEFORE UPDATE ON tms.material_profile
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

COMMENT ON TABLE tms.material_profile IS
    'Dated copy of a material''s physical profile, owned by the warehouse system (ADR-022, D-02). '
    'Written only through /integration/v1/material-profiles; copied onto new order lines. TMS never '
    'corrects it: when it disagrees, it shows it.';

-- Tenant isolation (ADR-005) and least privilege (V50). No DELETE: a profile the warehouse stops
-- sending stays as the last known copy.
ALTER TABLE tms.material_profile ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE ON tms.material_profile TO tms_app;
REVOKE DELETE ON tms.material_profile FROM tms_app;
CREATE POLICY p_tenant_company_scope ON tms.material_profile
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 2. The physical snapshot on the order line
-- ---------------------------------------------------------------------------
--
-- Every column is nullable and every existing line keeps NULL: a line without a block behaves
-- exactly as before. physical_source says where the block came from - the ERP's own line
-- (ORDER_LINE, which wins as a whole, D-02) or the profile (PROFILE, with the copied version).
-- unit_weight_kg / unit_volume_m3 are not part of the block: they keep V10's columns.
ALTER TABLE tms.transport_order_line
    ADD COLUMN unit_length_m            numeric(8,4),
    ADD COLUMN unit_width_m             numeric(8,4),
    ADD COLUMN unit_height_m            numeric(8,4),
    ADD COLUMN stack_mode               text,
    ADD COLUMN stack_max_quantity       integer,
    ADD COLUMN nest_increment_m         numeric(8,4),
    ADD COLUMN max_top_load_kg          numeric(12,3),
    ADD COLUMN orientation_locked       boolean,
    ADD COLUMN physical_source          text,
    ADD COLUMN material_profile_version bigint;

ALTER TABLE tms.transport_order_line
    ADD CONSTRAINT ck_transport_order_line_measures_positive CHECK (
        (unit_length_m IS NULL OR unit_length_m > 0) AND (unit_width_m IS NULL OR unit_width_m > 0)
        AND (unit_height_m IS NULL OR unit_height_m > 0)),
    ADD CONSTRAINT ck_transport_order_line_stack_mode CHECK (
        stack_mode IS NULL OR stack_mode IN ('NONE', 'STACKABLE', 'NESTABLE')),
    ADD CONSTRAINT ck_transport_order_line_stack_max_quantity_positive CHECK (
        stack_max_quantity IS NULL OR stack_max_quantity > 0),
    ADD CONSTRAINT ck_transport_order_line_nest_increment CHECK (
        CASE WHEN stack_mode = 'NESTABLE' THEN nest_increment_m IS NOT NULL AND nest_increment_m > 0
             ELSE nest_increment_m IS NULL END),
    ADD CONSTRAINT ck_transport_order_line_max_top_load_positive CHECK (
        max_top_load_kg IS NULL OR max_top_load_kg > 0),
    -- Single-column, so the enum guard (PersistedEnumConstraintTest) sees the vocabulary.
    ADD CONSTRAINT ck_transport_order_line_physical_source_value CHECK (
        physical_source IS NULL OR physical_source IN ('PROFILE', 'ORDER_LINE')),
    -- A source iff a block: any measure, mode, stack size, nest increment or top load. The
    -- orientation flag travels with a block and is never stored on its own.
    ADD CONSTRAINT ck_transport_order_line_physical_source CHECK (
        CASE WHEN physical_source IS NULL THEN
                 unit_length_m IS NULL AND unit_width_m IS NULL AND unit_height_m IS NULL
                 AND stack_mode IS NULL AND stack_max_quantity IS NULL AND nest_increment_m IS NULL
                 AND max_top_load_kg IS NULL AND orientation_locked IS NULL
                 AND material_profile_version IS NULL
             ELSE physical_source IN ('PROFILE', 'ORDER_LINE')
                 AND orientation_locked IS NOT NULL
                 AND (unit_length_m IS NOT NULL OR unit_width_m IS NOT NULL OR unit_height_m IS NOT NULL
                      OR stack_mode IS NOT NULL OR stack_max_quantity IS NOT NULL
                      OR nest_increment_m IS NOT NULL OR max_top_load_kg IS NOT NULL)
        END),
    -- The copied version exists exactly when the block came from the profile.
    ADD CONSTRAINT ck_transport_order_line_profile_version CHECK (
        (physical_source = 'PROFILE') = (material_profile_version IS NOT NULL)
        OR physical_source IS NULL);

COMMENT ON COLUMN tms.transport_order_line.physical_source IS
    'Where the physical block came from: ORDER_LINE (the ERP sent it on the line and it wins as a '
    'whole, D-02) or PROFILE (copied from tms.material_profile at material_profile_version). NULL '
    'when the line has no block. A later profile change never rewrites it (ADR-022 section 2).';

-- ---------------------------------------------------------------------------
-- 3. Integration scope (V55 shape: the whole list, restated)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.integration_client_scope DROP CONSTRAINT ck_integration_client_scope_value;
ALTER TABLE tms.integration_client_scope ADD CONSTRAINT ck_integration_client_scope_value CHECK (
    scope IN ('integration.location:write', 'integration.order:write', 'integration.shipment:read',
              'integration.tracking:write', 'integration.tender:respond',
              'integration.dispatch:write', 'integration.warehouse-milestone:write',
              'integration.document:write',
              -- V59 (ADR-022).
              'integration.material:write'));

-- ---------------------------------------------------------------------------
-- 4. Permission (V46 shape)
-- ---------------------------------------------------------------------------
-- Read only. There is no manage permission because there is no human write path: the warehouse
-- owns the profile and TMS shows it.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('masterdata.material_profile', 'read', 'View the physical profiles of materials synchronised from the warehouse');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE p.code = 'masterdata.material_profile:read'
  AND r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN', 'PLANNER', 'VIEWER');

-- ---------------------------------------------------------------------------
-- 5. Audit vocabulary (the whole lists, restated)
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
    'MATERIAL_PROFILE_CREATED', 'MATERIAL_PROFILE_UPDATED'));

-- Generated from com.ebim.tms.shared.audit.AuditAggregateType (V48's rule), plus MATERIAL_PROFILE.
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_aggregate_type;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_aggregate_type CHECK (aggregate_type IN (
    'APPOINTMENT',
    'APP_USER',
    'CARRIER',
    'CARRIER_INVOICE',
    'COMPANY',
    'DRIVER',
    'INTEGRATION_CLIENT',
    'LOCATION',
    'LOCATION_RESOURCE',
    'MASTER_DATA_IMPORT_BATCH',
    'MATERIAL_PROFILE',
    'MEMBERSHIP',
    'ORDER_IMPORT_BATCH',
    'OWN_FLEET_COST_PROFILE',
    'PLANNING_RUN',
    'RATE_CARD',
    'SHIPMENT',
    'TRANSPORT_ORDER',
    'TRIP',
    'TRIP_COST',
    'VEHICLE',
    'WEBHOOK_SUBSCRIPTION'
));
