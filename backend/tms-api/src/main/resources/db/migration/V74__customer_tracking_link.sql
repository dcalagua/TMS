-- ============================================================================
-- V74 - Customer tracking link (ADR-029, backlog item 6)
-- ============================================================================
-- "Where is my order?" arrives by WhatsApp. An office user generates a link for ONE order; the
-- customer opens it on a phone without a session and sees a minimal projection: status timeline,
-- arrival window, carrier, masked plate and proof-of-delivery summary.
--
--   tms.tracking_link                            one shareable link of one order, stored hashed
--   tms.company_settings.tracking_plate_display  how the public page shows the plate (MASKED/HIDDEN)
--
-- The raw token is never stored: token_hash is the hex SHA-256 of 32 random bytes (ADR-029 T2).
-- The public read goes through the backend's own security chain - this table is in the tms schema,
-- which the Supabase Data API does not expose, and nothing here is granted to anon/authenticated.
--
-- No business rows are seeded. One permission is (V54 shape), and the audit vocabulary grows.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. The plate on the public page, per company
-- ---------------------------------------------------------------------------
-- MASKED (default): the last three characters only. HIDDEN: not shown. There is deliberately no
-- FULL - a full plate with a time and a place is more than "where is my order" needs.
ALTER TABLE tms.company_settings
    ADD COLUMN tracking_plate_display text NOT NULL DEFAULT 'MASKED';

ALTER TABLE tms.company_settings ADD CONSTRAINT ck_company_settings_tracking_plate_display
    CHECK (tracking_plate_display IN ('MASKED', 'HIDDEN'));

-- ---------------------------------------------------------------------------
-- 2. tms.tracking_link
-- ---------------------------------------------------------------------------
CREATE TABLE tms.tracking_link (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id      uuid        NOT NULL,
    order_id        uuid        NOT NULL,
    -- Lower-case hex SHA-256 of the raw token. Unique: two links can never share a token, and the
    -- public lookup is one index probe on this column.
    token_hash      text        NOT NULL,
    -- The hard cap, fixed at creation (tms.tracking-links.max-lifetime). The soft end - seven days
    -- after the order closes - is computed at read time from the order and its deliveries, so a
    -- correction to a delivery moves it without rewriting this row.
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid        NOT NULL,
    -- Revocation is all-or-nothing and one-way (see the guard below).
    revoked_at      timestamptz,
    revoked_by      uuid,
    revoke_reason   text,
    -- Telemetry for the office ("did the customer open it?"), never a security decision.
    last_viewed_at  timestamptz,
    view_count      bigint      NOT NULL DEFAULT 0,
    version         bigint      NOT NULL DEFAULT 0,

    CONSTRAINT pk_tracking_link PRIMARY KEY (id),
    CONSTRAINT uq_tracking_link_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_tracking_link_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_tracking_link_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- Composite, so a link of one company can never point at another company's order (ADR-003).
    CONSTRAINT fk_tracking_link_order_company FOREIGN KEY (order_id, company_id)
        REFERENCES tms.transport_order (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_tracking_link_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tracking_link_revoked_by FOREIGN KEY (revoked_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,

    CONSTRAINT ck_tracking_link_token_hash_shape CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tracking_link_expiry_after_creation CHECK (expires_at > created_at),
    CONSTRAINT ck_tracking_link_revocation CHECK (
        (revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND revoke_reason IS NOT NULL
            AND btrim(revoke_reason) <> '' AND char_length(revoke_reason) <= 500)),
    CONSTRAINT ck_tracking_link_revoked_after_creation CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CONSTRAINT ck_tracking_link_view_count CHECK (view_count >= 0)
);

-- "Which links does this order have" (the drawer) and "how many are live" (the per-order cap).
CREATE INDEX ix_tracking_link_order ON tms.tracking_link (company_id, order_id, created_at DESC);

COMMENT ON TABLE tms.tracking_link IS
    'A shareable customer tracking link of one transport order (V74, ADR-029). Only the SHA-256 of '
    'the token is stored. Expires at expires_at or seven days after the order closes, whichever is '
    'first; revocable, one-way. Read anonymously only through the backend public chain.';
COMMENT ON COLUMN tms.tracking_link.token_hash IS
    'Lower-case hex SHA-256 of the 256-bit random token. The raw token is shown once, at creation.';

-- ---------------------------------------------------------------------------
-- 3. Identity is immutable and revocation is one-way - against the owner too
-- ---------------------------------------------------------------------------
-- The grants below already narrow what tms_app may UPDATE; this guard holds for a hand-run script
-- or a scheduled job on the owner connection as well (V68's shape).
CREATE FUNCTION tms.tracking_link_guard()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'tms.% rows are revoked, never deleted', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.company_id IS DISTINCT FROM OLD.company_id
            OR NEW.order_id IS DISTINCT FROM OLD.order_id
            OR NEW.token_hash IS DISTINCT FROM OLD.token_hash
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'tms.%: the identity of a tracking link is immutable', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.revoked_at IS NOT NULL AND (
            NEW.revoked_at IS DISTINCT FROM OLD.revoked_at
            OR NEW.revoked_by IS DISTINCT FROM OLD.revoked_by
            OR NEW.revoke_reason IS DISTINCT FROM OLD.revoke_reason) THEN
        RAISE EXCEPTION 'tms.%: a revoked link stays revoked', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION tms.tracking_link_guard() FROM PUBLIC;

CREATE TRIGGER tr_tracking_link_guard
    BEFORE UPDATE OR DELETE ON tms.tracking_link
    FOR EACH ROW EXECUTE FUNCTION tms.tracking_link_guard();

-- ---------------------------------------------------------------------------
-- 4. Tenant isolation (ADR-005), the carrier boundary (ADR-018) and least privilege (V50)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.tracking_link ENABLE ROW LEVEL SECURITY;

-- V13's default privileges attach all four verbs at CREATE TABLE; the REVOKEs are what withhold.
REVOKE UPDATE, DELETE ON tms.tracking_link FROM tms_app;
GRANT SELECT, INSERT ON tms.tracking_link TO tms_app;
-- Only revocation and the view counter change after creation (plus the optimistic-lock version).
GRANT UPDATE (revoked_at, revoked_by, revoke_reason, last_viewed_at, view_count, version)
    ON tms.tracking_link TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.tracking_link
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- A carrier-portal transaction sees none of the shipper's customer links.
CREATE POLICY p_carrier_scope ON tms.tracking_link AS RESTRICTIVE
    FOR ALL TO tms_app
    USING (tms.current_carrier_id() IS NULL)
    WITH CHECK (tms.current_carrier_id() IS NULL);

-- ---------------------------------------------------------------------------
-- 5. Permission (V54 shape)
-- ---------------------------------------------------------------------------
-- Its own authority: handing an anonymous party a view of an order is a customer-facing decision,
-- and a VIEWER who may read every order must not be able to publish one.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('orders.tracking_link', 'manage', 'Generate, list and revoke customer tracking links of an order');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN', 'PLANNER')
  AND p.code = 'orders.tracking_link:manage';

-- ---------------------------------------------------------------------------
-- 6. Audit vocabulary (the whole list, restated)
-- ---------------------------------------------------------------------------
-- Generating and revoking are audited on the order (aggregate TRANSPORT_ORDER, already allowed);
-- the event carries the link id and its expiry, never the token.
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
    'MATERIAL_PROFILE_CREATED', 'MATERIAL_PROFILE_UPDATED',
    'TOLERANCE_POLICY_CHANGED',
    'ACCRUAL_CREATED', 'ACCRUAL_ADJUSTED', 'ACCRUAL_CONSUMED', 'ACCRUAL_REVERSED',
    'ACCRUAL_JOURNAL_EXPORTED', 'GL_MAPPING_CHANGED',
    'CREDIT_NOTE_RECEIVED', 'DEBIT_NOTE_RECEIVED', 'PENALTY_ACCEPTED', 'RETENTION_OVERRIDDEN',
    'PAYABLE_SCHEDULED', 'PAYABLE_HOLD_PLACED', 'PAYABLE_HOLD_RELEASED',
    'PAYMENT_REQUESTED', 'PAYMENT_REQUEST_REJECTED', 'PAYMENT_RECORDED',
    'COST_ALLOCATED',
    -- V74, ADR-029. When V70-V73 land first, their actions join this list at integration.
    'TRACKING_LINK_CREATED', 'TRACKING_LINK_REVOKED'));
