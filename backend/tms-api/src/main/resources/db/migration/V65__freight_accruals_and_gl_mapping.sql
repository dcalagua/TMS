-- ============================================================================
-- V65 - Freight accruals and GL account mapping (ADR-023 section 1, phase 55)
-- ============================================================================
-- A carrier's service is an expense the day it is rendered, and the invoice arrives weeks later.
-- Until now TMS knew both figures (tms.trip_cost) and the invoice (V46) but nothing about the
-- obligation in between. A freight accrual is that obligation: born when the trip is dispatched
-- (or delivered, per company - D-07), adjusted when the actual cost is closed, consumed when the
-- invoice is approved, reversed by a person when the service turns out not to be billable.
--
-- The boundary of V46 holds: TMS moves no money and keeps no ledger (D-01). The accrual is a
-- figure TMS can defend and an export the ERP books; the general ledger stays the ERP's.
--
--   tms.company_settings.accrual_trigger  DISPATCHED (default, the decision) | DELIVERED
--   tms.freight_accrual                   one per trip and currency, running totals
--   tms.freight_accrual_entry             the movements, append-only by trigger AND by grant
--   tms.gl_account_mapping                which account each concept is booked to, per scope
--
-- No business rows are seeded. Three permissions are (V46 shape), and the audit vocabulary grows.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. When an accrual is born, per company (D-07)
-- ---------------------------------------------------------------------------
-- Same shape and reasoning as V52's dispatch_confirmation_mode: the default is the decision, so a
-- company that never opens the setting accrues at dispatch, and a missing settings row reads the
-- same (CompanySettings.defaults()).
ALTER TABLE tms.company_settings
    ADD COLUMN accrual_trigger text NOT NULL DEFAULT 'DISPATCHED';

ALTER TABLE tms.company_settings ADD CONSTRAINT ck_company_settings_accrual_trigger
    CHECK (accrual_trigger IN ('DISPATCHED', 'DELIVERED'));

COMMENT ON COLUMN tms.company_settings.accrual_trigger IS
    'When a freight accrual is born (V65, ADR-023, D-07): DISPATCHED = the trip left '
    '(actual_departure_at), DELIVERED = the trip reached COMPLETED (actual_completion_at).';

-- ---------------------------------------------------------------------------
-- 2. The accrual
-- ---------------------------------------------------------------------------
CREATE TABLE tms.freight_accrual (
    id                uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id        uuid          NOT NULL,
    trip_id           uuid          NOT NULL,
    -- Always set today: own-fleet trips are not freight payables and are never accrued. Nullable so
    -- that a later decision to accrue own-fleet cost is a service change, not a migration.
    carrier_id        uuid,
    currency          char(3)       NOT NULL,
    status            text          NOT NULL DEFAULT 'OPEN',
    -- What the current balance rests on: the estimate, or the closed actual cost.
    basis             text          NOT NULL,
    -- The accounting date: the trip's departure (or completion) as a calendar day in the company's
    -- own time zone. A trip leaving Lima at 21:00 on the 31st is a cost of that month, not the next.
    service_date      date          NOT NULL,
    -- Running totals, written only by the service in the same transaction as the entry that moves
    -- them. The entries are the truth: SUM(entry.amount) = accrued - consumed - reversed, and a test
    -- proves it.
    accrued_amount    numeric(14,2) NOT NULL DEFAULT 0,
    consumed_amount   numeric(14,2) NOT NULL DEFAULT 0,
    reversed_amount   numeric(14,2) NOT NULL DEFAULT 0,
    version           bigint        NOT NULL DEFAULT 0,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_freight_accrual PRIMARY KEY (id),
    CONSTRAINT uq_freight_accrual_id_company UNIQUE (id, company_id),
    -- THE idempotency of the birth: a second sweep, or a second node, finds the row and stops.
    CONSTRAINT uq_freight_accrual_trip_currency UNIQUE (trip_id, currency),

    CONSTRAINT fk_freight_accrual_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_accrual_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_accrual_carrier_company FOREIGN KEY (carrier_id, company_id)
        REFERENCES tms.carrier (id, company_id) ON DELETE RESTRICT,

    CONSTRAINT ck_freight_accrual_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_freight_accrual_status CHECK (status IN ('OPEN', 'PARTIALLY_SETTLED', 'SETTLED', 'REVERSED')),
    CONSTRAINT ck_freight_accrual_basis CHECK (basis IN ('ESTIMATED', 'ACTUAL')),
    CONSTRAINT ck_freight_accrual_amounts CHECK (
        accrued_amount >= 0 AND consumed_amount >= 0 AND reversed_amount >= 0)
);

CREATE INDEX ix_freight_accrual_company_status ON tms.freight_accrual (company_id, status);
CREATE INDEX ix_freight_accrual_company_service_date ON tms.freight_accrual (company_id, service_date);
CREATE INDEX ix_freight_accrual_carrier ON tms.freight_accrual (company_id, carrier_id);

COMMENT ON TABLE tms.freight_accrual IS
    'A freight obligation between the service and the invoice (V65, ADR-023). One per trip and '
    'currency. Running totals mirror the append-only movements in tms.freight_accrual_entry.';

-- ---------------------------------------------------------------------------
-- 3. The movements - append-only
-- ---------------------------------------------------------------------------
CREATE TABLE tms.freight_accrual_entry (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid          NOT NULL,
    accrual_id          uuid          NOT NULL,
    entry_type          text          NOT NULL,
    -- Signed: what the movement does to the open balance.
    amount              numeric(14,2) NOT NULL,
    -- First day of the accounting month the movement belongs to (the company's calendar).
    period              date          NOT NULL,
    source_type         text          NOT NULL,
    -- The trip id, the closing instant of the trip cost, the invoice id or a fresh id for a person's act.
    source_key          text          NOT NULL,
    carrier_invoice_id  uuid,
    reason              text,
    -- Null for the sweep: the system is not a person and does not pretend to be one.
    created_by          uuid,
    created_at          timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_freight_accrual_entry PRIMARY KEY (id),
    -- THE idempotency of every automatic movement: a repeat is a unique violation, treated as done.
    CONSTRAINT uq_freight_accrual_entry_source UNIQUE (accrual_id, entry_type, source_type, source_key),

    CONSTRAINT fk_freight_accrual_entry_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_accrual_entry_accrual_company FOREIGN KEY (accrual_id, company_id)
        REFERENCES tms.freight_accrual (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_accrual_entry_invoice_company FOREIGN KEY (carrier_invoice_id, company_id)
        REFERENCES tms.carrier_invoice (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_accrual_entry_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,

    CONSTRAINT ck_freight_accrual_entry_type CHECK (entry_type IN ('ACCRUE', 'ADJUST', 'CONSUME', 'REVERSE')),
    CONSTRAINT ck_freight_accrual_entry_sign CHECK (
        (entry_type = 'ACCRUE'  AND amount > 0) OR
        (entry_type = 'CONSUME' AND amount < 0) OR
        (entry_type = 'REVERSE' AND amount < 0) OR
        (entry_type = 'ADJUST'  AND amount <> 0)),
    CONSTRAINT ck_freight_accrual_entry_period CHECK (period = date_trunc('month', period)::date),
    CONSTRAINT ck_freight_accrual_entry_source_type CHECK (source_type IN (
        'TRIP_DISPATCHED', 'TRIP_COMPLETED', 'TRIP_COST_ACTUAL', 'INVOICE_APPROVED', 'MANUAL')),
    CONSTRAINT ck_freight_accrual_entry_source_key CHECK (btrim(source_key) <> ''),
    CONSTRAINT ck_freight_accrual_entry_consume_names_invoice CHECK (
        entry_type <> 'CONSUME' OR carrier_invoice_id IS NOT NULL),
    -- A person's act and a reversal say why; the sweep's movements explain themselves by source.
    CONSTRAINT ck_freight_accrual_entry_reason CHECK (
        (source_type <> 'MANUAL' AND entry_type <> 'REVERSE')
        OR (reason IS NOT NULL AND btrim(reason) <> '')),
    CONSTRAINT ck_freight_accrual_entry_reason_length CHECK (reason IS NULL OR length(reason) <= 500)
);

CREATE INDEX ix_freight_accrual_entry_accrual ON tms.freight_accrual_entry (accrual_id, created_at);
CREATE INDEX ix_freight_accrual_entry_company_period ON tms.freight_accrual_entry (company_id, period);
CREATE INDEX ix_freight_accrual_entry_invoice ON tms.freight_accrual_entry (carrier_invoice_id)
    WHERE carrier_invoice_id IS NOT NULL;

COMMENT ON TABLE tms.freight_accrual_entry IS
    'The movements of a freight accrual (V65, ADR-023): ACCRUE, ADJUST, CONSUME, REVERSE. Append-only '
    'by trigger and by grant: a booked movement is corrected by another movement, never edited.';

-- Same function shape as V56's tms.platform_entitlement_append_only(). The grant already withholds
-- UPDATE and DELETE from tms_app; the trigger also holds against the owner, which is the role a
-- scheduled job or a hand-run script would use.
CREATE FUNCTION tms.freight_accrual_entry_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'tms.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

REVOKE ALL ON FUNCTION tms.freight_accrual_entry_append_only() FROM PUBLIC;

CREATE TRIGGER tr_freight_accrual_entry_append_only
    BEFORE UPDATE OR DELETE ON tms.freight_accrual_entry
    FOR EACH ROW EXECUTE FUNCTION tms.freight_accrual_entry_append_only();

-- ---------------------------------------------------------------------------
-- 4. GL account mapping
-- ---------------------------------------------------------------------------
-- Which account each concept is booked to. TMS never invents one: without a mapping, the journal
-- export warns and leaves the account empty. The most specific scope wins (ROUTE > ORIGIN > COMPANY).
-- scope_id names a route or an origin location; it is polymorphic, so the service checks it exists
-- in the company rather than a foreign key.
CREATE TABLE tms.gl_account_mapping (
    id                uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id        uuid        NOT NULL,
    concept           text        NOT NULL,
    -- Which withholding a WITHHOLDING_PAYABLE row books (ADR-027): PE_DETRACTION, EC_VAT_WITHHOLDING...
    -- Null on every other concept, and on a WITHHOLDING_PAYABLE row that books every kind without its
    -- own mapping. The kinds are com.ebim.tms.settlement.domain.tax.WithholdingKind; a country added
    -- later restates the list, like every vocabulary CHECK of this schema.
    withholding_kind  text,
    account_code      text        NOT NULL,
    cost_center_code  text,
    scope_type        text        NOT NULL DEFAULT 'COMPANY',
    scope_id          uuid,
    active            boolean     NOT NULL DEFAULT true,
    version           bigint      NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_by        uuid,

    CONSTRAINT pk_gl_account_mapping PRIMARY KEY (id),
    CONSTRAINT uq_gl_account_mapping_id_company UNIQUE (id, company_id),
    CONSTRAINT fk_gl_account_mapping_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_gl_account_mapping_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_gl_account_mapping_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,

    -- Country-neutral (ADR-027): the VAT credit, whatever the country calls it (IGV, IVA), and one
    -- withholding concept qualified by its kind rather than one concept per country's tax.
    CONSTRAINT ck_gl_account_mapping_concept CHECK (concept IN (
        'FREIGHT_EXPENSE', 'FREIGHT_ACCRUAL', 'VAT_CREDIT', 'WITHHOLDING_PAYABLE', 'CARRIER_PAYABLE')),
    CONSTRAINT ck_gl_account_mapping_withholding_kind CHECK (
        withholding_kind IS NULL OR withholding_kind IN (
            'PE_DETRACTION', 'PE_RETENTION', 'EC_INCOME_TAX_WITHHOLDING', 'EC_VAT_WITHHOLDING')),
    -- Only the withholding concept is qualified by a kind.
    CONSTRAINT ck_gl_account_mapping_withholding_concept CHECK (
        withholding_kind IS NULL OR concept = 'WITHHOLDING_PAYABLE'),
    CONSTRAINT ck_gl_account_mapping_account CHECK (
        btrim(account_code) <> '' AND length(account_code) <= 40),
    CONSTRAINT ck_gl_account_mapping_cost_center CHECK (
        cost_center_code IS NULL OR (btrim(cost_center_code) <> '' AND length(cost_center_code) <= 40)),
    CONSTRAINT ck_gl_account_mapping_scope_type CHECK (scope_type IN ('COMPANY', 'ORIGIN', 'ROUTE')),
    CONSTRAINT ck_gl_account_mapping_scope_id CHECK (
        (scope_type = 'COMPANY') = (scope_id IS NULL))
);

-- One active mapping per (company, concept, withholding kind, scope). A COMPANY row has a null
-- scope_id and most rows a null withholding_kind, so the index reads both through a constant: two
-- NULLs are distinct to a plain UNIQUE.
CREATE UNIQUE INDEX uq_gl_account_mapping_active_scope
    ON tms.gl_account_mapping (company_id, concept, coalesce(withholding_kind, ''), scope_type,
        coalesce(scope_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE active;

CREATE INDEX ix_gl_account_mapping_company ON tms.gl_account_mapping (company_id, active);

COMMENT ON TABLE tms.gl_account_mapping IS
    'Account each accounting concept is booked to in the ERP (V65, ADR-023). Most specific scope wins '
    '(ROUTE > ORIGIN > COMPANY). No mapping = the export warns; TMS never invents an account.';
COMMENT ON COLUMN tms.gl_account_mapping.withholding_kind IS
    'The withholding a WITHHOLDING_PAYABLE mapping books (ADR-027); null = every kind without its own '
    'mapping. Null on every other concept.';

-- ---------------------------------------------------------------------------
-- 4b. What the sweep reads
-- ---------------------------------------------------------------------------
-- The sweep pages a company's trips by the instant of their departure (or completion). Partial, so
-- the index holds executed trips only.
CREATE INDEX IF NOT EXISTS ix_trip_company_departed
    ON tms.trip (company_id, actual_departure_at, id) WHERE actual_departure_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_trip_company_completed
    ON tms.trip (company_id, actual_completion_at, id) WHERE actual_completion_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 5. Tenant isolation (ADR-005) and least privilege (V50)
-- ---------------------------------------------------------------------------
-- V13's default privileges attach all four verbs at CREATE TABLE; the REVOKEs are what withhold.
ALTER TABLE tms.freight_accrual ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.freight_accrual_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.gl_account_mapping ENABLE ROW LEVEL SECURITY;

-- The accrual's totals and status move; the row is never deleted.
GRANT SELECT, INSERT, UPDATE ON tms.freight_accrual TO tms_app;
REVOKE DELETE ON tms.freight_accrual FROM tms_app;
-- A movement is booked once.
GRANT SELECT, INSERT ON tms.freight_accrual_entry TO tms_app;
REVOKE UPDATE, DELETE ON tms.freight_accrual_entry FROM tms_app;
-- A mapping is retired (active = false), not deleted: the export of a past period names it.
GRANT SELECT, INSERT, UPDATE ON tms.gl_account_mapping TO tms_app;
REVOKE DELETE ON tms.gl_account_mapping FROM tms_app;

CREATE POLICY p_tenant_company_scope ON tms.freight_accrual
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.freight_accrual_entry
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.gl_account_mapping
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 6. Permissions (V46 shape)
-- ---------------------------------------------------------------------------
INSERT INTO tms.permission (resource, action, description) VALUES
    ('settlement.accrual', 'read',   'View freight accruals, their movements, aging and unaccrued trips'),
    ('settlement.accrual', 'export', 'Export the accrual journal of a period for the ERP'),
    ('settlement.accrual', 'manage', 'Reverse or close accruals, run the sweep and edit the GL account mapping');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN', 'PLANNER', 'VIEWER')
  AND p.code = 'settlement.accrual:read';

-- Exporting to accounting and reversing an obligation are administrators' authority, the same line
-- V46 draws for settlement.invoice:approve|export.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code IN ('settlement.accrual:export', 'settlement.accrual:manage');

-- ---------------------------------------------------------------------------
-- 7. Audit vocabulary (the whole lists, restated)
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
    'ACCRUAL_JOURNAL_EXPORTED', 'GL_MAPPING_CHANGED'));

-- Generated from com.ebim.tms.shared.audit.AuditAggregateType (V48's rule), plus FREIGHT_ACCRUAL and
-- GL_ACCOUNT_MAPPING.
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_aggregate_type;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_aggregate_type CHECK (aggregate_type IN (
    'APPOINTMENT',
    'APP_USER',
    'CARRIER',
    'CARRIER_INVOICE',
    'COMPANY',
    'DRIVER',
    'FREIGHT_ACCRUAL',
    'GL_ACCOUNT_MAPPING',
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
    'TOLERANCE_POLICY',
    'TRANSPORT_ORDER',
    'TRIP',
    'TRIP_COST',
    'VEHICLE',
    'WEBHOOK_SUBSCRIPTION'
));
