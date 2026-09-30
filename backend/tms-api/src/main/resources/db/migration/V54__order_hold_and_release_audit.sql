-- ===========================================================================
-- V54 - Order holds, and an audited release (ADR-014)
-- ===========================================================================
--
-- ADR-014 puts "Scheduling and Release" inside the model TMS already has. Releasing an order IS the
-- existing transition NOT_READY -> READY_FOR_PLANNING; eligibility (ELIGIBLE / WARNING / BLOCKED),
-- the release deadline and the resolved route are derived on read and never stored. This migration
-- therefore adds exactly three things:
--
--   1. tms.order_hold - 0..N holds per order, separate from its status;
--   2. the audit actions ORDER_RELEASED, ORDER_HOLD_PLACED, ORDER_HOLD_RELEASED;
--   3. the permission orders.hold:manage.
--
-- transport_order, OrderStatus, the Planning Run and the planning engines are unchanged. In
-- particular transport_order gains no route_id: a route edited in the master data is picked up at
-- the next read, and a stored copy would disagree with it the moment it changed.

-- ---------------------------------------------------------------------------
-- 1. tms.order_hold
-- ---------------------------------------------------------------------------
--
-- A hold never changes the order's status. While it is active and blocking, the order cannot be
-- released and is not a planning candidate (OrderPlanningService.searchAssignable, the automatic
-- planning snapshot, and TripService.assignOrder through the allocation ledger). A hold placed on an
-- order that is already on a trip does NOT unplan it: it raises the Control Tower advisory
-- ORDER_HOLD_ON_COMMITTED_TRIP and blocks the trip's dispatch until it is lifted or the trip is
-- cancelled and replanned.
--
-- Lifted, never deleted: who stopped an order, why, and who let it go again is exactly what a
-- customer disputing a late delivery asks for. tms_app holds no DELETE.
CREATE TABLE tms.order_hold (
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid        NOT NULL,
    order_id            uuid        NOT NULL,
    hold_type           text        NOT NULL,
    -- A short machine code the placing party chose ("CREDIT_LIMIT", "NO_STOCK"), optional; the
    -- free text below is what a person reads.
    reason_code         text,
    reason              text        NOT NULL,
    -- OPERATOR: a person placed it in the TMS UI. INTEGRATION: a credential placed it over the M2M
    -- API. The actor columns say the same thing (ck_order_hold_actor), so neither can drift.
    source              text        NOT NULL DEFAULT 'OPERATOR',
    -- A non-blocking hold is a note that travels with the order and stops nothing.
    blocking            boolean     NOT NULL DEFAULT true,
    created_by          uuid,
    created_by_client   uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    released_at         timestamptz,
    released_by         uuid,
    released_by_client  uuid,
    release_reason      text,
    version             bigint      NOT NULL DEFAULT 0,
    updated_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_order_hold PRIMARY KEY (id),
    CONSTRAINT uq_order_hold_id_company UNIQUE (id, company_id),
    CONSTRAINT fk_order_hold_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- Composite, so a hold of one company can never point at another company's order (ADR-003).
    CONSTRAINT fk_order_hold_order_company FOREIGN KEY (order_id, company_id)
        REFERENCES tms.transport_order (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_hold_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_hold_created_by_client FOREIGN KEY (created_by_client)
        REFERENCES tms.integration_client (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_hold_created_by_client_company FOREIGN KEY (created_by_client, company_id)
        REFERENCES tms.integration_client (id, company_id),
    CONSTRAINT fk_order_hold_released_by FOREIGN KEY (released_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_hold_released_by_client FOREIGN KEY (released_by_client)
        REFERENCES tms.integration_client (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_hold_released_by_client_company FOREIGN KEY (released_by_client, company_id)
        REFERENCES tms.integration_client (id, company_id),

    CONSTRAINT ck_order_hold_type CHECK (hold_type IN (
        'COMMERCIAL', 'INVENTORY', 'ADDRESS', 'CUSTOMER', 'TRANSPORT', 'INTEGRATION', 'MANUAL', 'OTHER')),
    CONSTRAINT ck_order_hold_source CHECK (source IN ('OPERATOR', 'INTEGRATION')),
    CONSTRAINT ck_order_hold_reason_code_shape CHECK (
        reason_code IS NULL OR reason_code ~ '^[A-Z0-9][A-Z0-9_.-]{0,63}$'),
    CONSTRAINT ck_order_hold_reason_length CHECK (
        btrim(reason) <> '' AND char_length(reason) <= 500),
    -- Exactly one actor placed it, and the source names which kind - the V31 tender rule.
    CONSTRAINT ck_order_hold_actor CHECK (
        CASE source
            WHEN 'OPERATOR'    THEN created_by IS NOT NULL AND created_by_client IS NULL
            WHEN 'INTEGRATION' THEN created_by IS NULL     AND created_by_client IS NOT NULL
        END),
    -- Lifted is all-or-nothing: a time, exactly one actor, and a reason; or none of them.
    CONSTRAINT ck_order_hold_release_actor CHECK (
        (released_at IS NULL AND released_by IS NULL AND released_by_client IS NULL AND release_reason IS NULL)
        OR (released_at IS NOT NULL AND release_reason IS NOT NULL
            AND (released_by IS NULL) <> (released_by_client IS NULL))),
    CONSTRAINT ck_order_hold_release_reason_length CHECK (
        release_reason IS NULL OR (btrim(release_reason) <> '' AND char_length(release_reason) <= 500)),
    CONSTRAINT ck_order_hold_release_after_creation CHECK (released_at IS NULL OR released_at >= created_at)
);

-- The two questions every reader asks: "which holds does this order have" and "which of these
-- orders has an active blocking hold" - the second for a board of two hundred orders, and for the
-- NOT EXISTS the eligible-orders search adds.
CREATE INDEX ix_order_hold_order ON tms.order_hold (order_id, created_at DESC);
CREATE INDEX ix_order_hold_active_blocking ON tms.order_hold (company_id, order_id)
    WHERE released_at IS NULL AND blocking;

CREATE TRIGGER tr_order_hold_set_updated_at
    BEFORE UPDATE ON tms.order_hold
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

COMMENT ON TABLE tms.order_hold IS
    'Holds on a transport order (V54, ADR-014). Separate from the order status: an active blocking '
    'hold stops release and planning, and on an order already on a trip it blocks dispatch and '
    'raises ORDER_HOLD_ON_COMMITTED_TRIP rather than unplanning anything. Lifted, never deleted.';
COMMENT ON COLUMN tms.order_hold.blocking IS
    'true (the default): the hold stops release, planning and dispatch while it is active. false: '
    'a note that travels with the order and stops nothing.';

-- ---------------------------------------------------------------------------
-- 2. Tenant isolation (ADR-005) and least privilege (V50)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.order_hold ENABLE ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE ON tms.order_hold TO tms_app;
-- V13's default privileges attached DELETE at CREATE TABLE time; a shorter GRANT withholds nothing.
REVOKE DELETE ON tms.order_hold FROM tms_app;

CREATE POLICY p_tenant_company_scope ON tms.order_hold
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 3. Permission
-- ---------------------------------------------------------------------------
--
-- Placing and lifting a hold is its own authority: stopping an order for credit is a commercial
-- decision that the person who types orders may not hold. Reading holds needs only
-- orders.order:read, and releasing an order keeps orders.order:manage, which mark-ready has always
-- required.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('orders.hold', 'manage', 'Place and lift holds on transport orders');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN', 'PLANNER')
  AND p.code = 'orders.hold:manage';

-- ---------------------------------------------------------------------------
-- 4. Audit vocabulary
-- ---------------------------------------------------------------------------
--
-- Mirrors com.ebim.tms.shared.audit.AuditAction exactly (AuditVocabularyMigrationTest). The release
-- was not audited before ADR-014; ORDER_RELEASED records the eligibility, the warning codes and the
-- override reason that allowed it.
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
    -- V52 (ADR-013 section 4), kept: this list replaces the whole CHECK.
    'DISPATCH_OVERRIDDEN',
    -- V54 (ADR-014).
    'ORDER_RELEASED', 'ORDER_HOLD_PLACED', 'ORDER_HOLD_RELEASED'));
