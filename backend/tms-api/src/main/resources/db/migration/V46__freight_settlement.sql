-- ===========================================================================
-- V46 - Freight audit and settlement
-- ===========================================================================
--
-- The largest capability gap the enterprise readiness assessment named, and the one most likely to
-- be misread: docs/architecture/TMS_CURRENT_CAPABILITY_MAP.md row 16 pointed at "JOB 11", and JOB 11
-- was titled Settlement but delivered PROPOSAL PRICING. Nothing here existed before this migration.
--
-- ---------------------------------------------------------------------------
-- 1. The boundary: TMS validates, ERP pays
-- ---------------------------------------------------------------------------
--
-- There is no ledger here, no payment, no bank detail and no accounting period. TMS receives a
-- carrier's invoice, compares it with what the shipment was priced at and what it actually cost,
-- surfaces the difference, has a human approve or reject it, and hands the approved obligation to
-- whoever pays. That last step is an EXPORT, not a payment.
--
-- V30 already said this in a comment on tms.trip_cost.actual_reference: "TMS is not an
-- accounts-payable system and must not pretend to validate somebody else's document numbering."
-- That remains true. What changes is that the invoice becomes a document TMS can reason about
-- rather than a free-text reference.
--
-- ---------------------------------------------------------------------------
-- 2. What this migration does NOT duplicate
-- ---------------------------------------------------------------------------
--
-- tms.trip_cost (V30/V39) already holds both figures matching needs:
--
--   estimated_amount + the rate card snapshot  -> EXPECTED
--   actual_amount    + actual_reference        -> ACTUAL
--
-- Settlement READS those and never writes them. Two owners of "what this shipment cost" is exactly
-- how two numbers come to disagree, and V30's close/reopen already governs when that figure may
-- change. So the three-way comparison is: expected and actual from tms.trip_cost, invoiced from
-- here.

-- ---------------------------------------------------------------------------
-- 3. The invoice
-- ---------------------------------------------------------------------------
CREATE TABLE tms.carrier_invoice (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid          NOT NULL,
    carrier_id          uuid          NOT NULL,
    -- The carrier's own number, exactly as printed. Free text of a constrained shape and never
    -- validated against a format: every carrier numbers differently and a CHECK enumerating them
    -- would make onboarding a customer's existing partner a migration (the same reasoning V9 gives
    -- for carrier.tax_id_type).
    invoice_number      text          NOT NULL,
    invoice_date        date          NOT NULL,
    -- ISO-4217, and never converted. Two invoices in different currencies do not add up, and this
    -- product invents no FX rate - the rule V30 set for rate cards and JOB 11 kept for proposal
    -- pricing.
    currency            char(3)       NOT NULL,
    total_amount        numeric(14,2) NOT NULL,
    status              text          NOT NULL DEFAULT 'RECEIVED',
    -- Where it came from, when a partner posted it rather than a person keying it.
    external_reference  text,
    received_at         timestamptz   NOT NULL DEFAULT now(),
    notes               text,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_by          uuid,
    -- Optimistic lock. Two people approving the same invoice at the same second is the concurrency
    -- case this table has to survive, and a version column is how every other aggregate here does
    -- it (tms.planning_run, tms.trip).
    version             bigint        NOT NULL DEFAULT 0,

    CONSTRAINT pk_carrier_invoice PRIMARY KEY (id),
    CONSTRAINT uq_carrier_invoice_id_company UNIQUE (id, company_id),
    -- THE duplicate-invoice guard. One carrier cannot bill the same number twice into one company,
    -- which is the single most common freight-audit fraud and the most common honest mistake.
    -- Per company AND per carrier: two carriers may legitimately both have an "INV-001", and two
    -- companies in one installation are different businesses entirely.
    CONSTRAINT uq_carrier_invoice_number UNIQUE (company_id, carrier_id, invoice_number),

    CONSTRAINT fk_carrier_invoice_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_carrier_invoice_carrier FOREIGN KEY (carrier_id)
        REFERENCES tms.carrier (id) ON DELETE RESTRICT,
    -- Rule 6: an invoice of company A can never name a carrier of company B, whatever a service
    -- forgets to check.
    CONSTRAINT fk_carrier_invoice_carrier_company FOREIGN KEY (carrier_id, company_id)
        REFERENCES tms.carrier (id, company_id),
    CONSTRAINT fk_carrier_invoice_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,

    CONSTRAINT ck_carrier_invoice_status CHECK (status IN (
        'RECEIVED', 'MATCHING', 'MATCHED', 'DISCREPANCY', 'UNDER_REVIEW',
        'APPROVED', 'REJECTED', 'EXPORTED')),
    CONSTRAINT ck_carrier_invoice_number_not_blank CHECK (btrim(invoice_number) <> ''),
    CONSTRAINT ck_carrier_invoice_currency CHECK (currency ~ '^[A-Z]{3}$'),
    -- Non-negative rather than positive: a zero-value invoice is unusual and legitimate (a
    -- corrected document, a goodwill run), and refusing it would force somebody to invent a penny.
    CONSTRAINT ck_carrier_invoice_total_nonnegative CHECK (total_amount >= 0),
    CONSTRAINT ck_carrier_invoice_notes_not_blank CHECK (notes IS NULL OR btrim(notes) <> '')
);

CREATE INDEX ix_carrier_invoice_company_status ON tms.carrier_invoice (company_id, status);
CREATE INDEX ix_carrier_invoice_carrier ON tms.carrier_invoice (carrier_id);

COMMENT ON TABLE tms.carrier_invoice IS
    'What a carrier says it is owed (V46). TMS validates it and exports the approved obligation; '
    'the ERP pays. There is no ledger, no payment and no accounting period here.';

-- ---------------------------------------------------------------------------
-- 4. The lines
-- ---------------------------------------------------------------------------
--
-- A line may name a trip and may not. An accessorial billed against no particular shipment - a
-- monthly surcharge, a demurrage claim - is a real line, and requiring a trip would force somebody
-- to attach it to an arbitrary one. What a line without a trip cannot do is match, and that is
-- exactly what UNMATCHED_TRIP records.
CREATE TABLE tms.carrier_invoice_line (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid          NOT NULL,
    carrier_invoice_id  uuid          NOT NULL,
    line_number         integer       NOT NULL,
    -- The shipment this line bills for, when it names one.
    trip_id             uuid,
    description         text          NOT NULL,
    quantity            numeric(12,3),
    unit_amount         numeric(14,4),
    line_amount         numeric(14,2) NOT NULL,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_carrier_invoice_line PRIMARY KEY (id),
    CONSTRAINT uq_carrier_invoice_line_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_carrier_invoice_line_number UNIQUE (carrier_invoice_id, line_number),

    CONSTRAINT fk_carrier_invoice_line_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_carrier_invoice_line_invoice FOREIGN KEY (carrier_invoice_id)
        REFERENCES tms.carrier_invoice (id) ON DELETE CASCADE,
    CONSTRAINT fk_carrier_invoice_line_invoice_company FOREIGN KEY (carrier_invoice_id, company_id)
        REFERENCES tms.carrier_invoice (id, company_id),
    CONSTRAINT fk_carrier_invoice_line_trip FOREIGN KEY (trip_id)
        REFERENCES tms.trip (id) ON DELETE RESTRICT,
    -- An invoice of company A cannot bill a trip of company B.
    CONSTRAINT fk_carrier_invoice_line_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),

    CONSTRAINT ck_carrier_invoice_line_description CHECK (btrim(description) <> ''),
    CONSTRAINT ck_carrier_invoice_line_number CHECK (line_number >= 1),
    -- Quantity and unit amount travel together or not at all: an amount per unit with no unit count
    -- is not a partial record, it is an unanswerable one.
    CONSTRAINT ck_carrier_invoice_line_unit_block CHECK (
        num_nonnulls(quantity, unit_amount) IN (0, 2)),
    CONSTRAINT ck_carrier_invoice_line_quantities_nonnegative CHECK (
        COALESCE(quantity, 0) >= 0 AND COALESCE(unit_amount, 0) >= 0)
);

CREATE INDEX ix_carrier_invoice_line_invoice ON tms.carrier_invoice_line (carrier_invoice_id);
CREATE INDEX ix_carrier_invoice_line_trip ON tms.carrier_invoice_line (trip_id)
    WHERE trip_id IS NOT NULL;

-- Deliberately NOT a CHECK that the lines sum to total_amount. A carrier's document is what it is:
-- rounding, a header-level discount or a line TMS did not receive all make the two disagree
-- legitimately, and refusing the row would make an unrepresentable invoice out of a real one. The
-- difference is a DISCREPANCY to surface, not an insert to reject.

-- ---------------------------------------------------------------------------
-- 5. Tolerance
-- ---------------------------------------------------------------------------
--
-- One policy per company, optionally narrowed to one carrier. Both bounds are optional and are
-- applied as "within EITHER" rather than both: a 3% tolerance on a 40-unit invoice is pennies, and
-- an absolute floor is what stops trivial rounding becoming a discrepancy queue nobody reads.
CREATE TABLE tms.tolerance_policy (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid          NOT NULL,
    -- NULL means "every carrier without a policy of their own". A carrier-specific row outranks it.
    carrier_id          uuid,
    absolute_amount     numeric(14,2),
    percentage          numeric(6,3),
    currency            char(3),
    active              boolean       NOT NULL DEFAULT true,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_by          uuid,

    CONSTRAINT pk_tolerance_policy PRIMARY KEY (id),
    CONSTRAINT uq_tolerance_policy_id_company UNIQUE (id, company_id),
    CONSTRAINT fk_tolerance_policy_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tolerance_policy_carrier FOREIGN KEY (carrier_id)
        REFERENCES tms.carrier (id) ON DELETE CASCADE,
    CONSTRAINT fk_tolerance_policy_carrier_company FOREIGN KEY (carrier_id, company_id)
        REFERENCES tms.carrier (id, company_id),

    -- At least one bound, or the policy says nothing.
    CONSTRAINT ck_tolerance_policy_has_a_bound CHECK (
        absolute_amount IS NOT NULL OR percentage IS NOT NULL),
    CONSTRAINT ck_tolerance_policy_absolute CHECK (
        absolute_amount IS NULL OR absolute_amount >= 0),
    CONSTRAINT ck_tolerance_policy_percentage CHECK (
        percentage IS NULL OR (percentage >= 0 AND percentage <= 100)),
    -- An absolute bound is a sum of money and needs its currency; a percentage does not.
    CONSTRAINT ck_tolerance_policy_absolute_currency CHECK (
        absolute_amount IS NULL OR currency IS NOT NULL),
    CONSTRAINT ck_tolerance_policy_currency_shape CHECK (
        currency IS NULL OR currency ~ '^[A-Z]{3}$')
);

-- One active policy per scope. Two active company-wide policies would make "what is the tolerance"
-- a question with two answers and a tie-break rule to choose between them.
CREATE UNIQUE INDEX uq_tolerance_policy_active_company
    ON tms.tolerance_policy (company_id) WHERE carrier_id IS NULL AND active;
CREATE UNIQUE INDEX uq_tolerance_policy_active_carrier
    ON tms.tolerance_policy (company_id, carrier_id) WHERE carrier_id IS NOT NULL AND active;

-- ---------------------------------------------------------------------------
-- 6. The match
-- ---------------------------------------------------------------------------
--
-- One row per invoice: the three-way comparison, frozen at the moment it was computed. Stored
-- rather than recomputed on read for the reason V30 stored cost lines and V43 stored the stop ETA -
-- a figure somebody approved against must stay reproducible after the master data behind it moved.
CREATE TABLE tms.freight_match (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid          NOT NULL,
    carrier_invoice_id  uuid          NOT NULL,
    status              text          NOT NULL,
    -- NULL is NOT zero, and this is the rule the whole comparison rests on - the same rule V45
    -- established for delivered quantities. A shipment whose cost was never estimated has no
    -- expected figure, and treating that as 0.00 would report the entire invoice as an overcharge.
    expected_amount     numeric(14,2),
    actual_amount       numeric(14,2),
    invoiced_amount     numeric(14,2) NOT NULL,
    -- invoiced - expected. NULL exactly when expected is NULL, for the same reason.
    difference_amount   numeric(14,2),
    currency            char(3)       NOT NULL,
    -- What was compared against, snapshotted: a tolerance widened next month must not restate why
    -- this invoice matched.
    tolerance_absolute  numeric(14,2),
    tolerance_percentage numeric(6,3),
    matched_trip_count  integer       NOT NULL DEFAULT 0,
    unmatched_line_count integer      NOT NULL DEFAULT 0,
    computed_at         timestamptz   NOT NULL DEFAULT now(),
    computed_by         uuid,

    CONSTRAINT pk_freight_match PRIMARY KEY (id),
    CONSTRAINT uq_freight_match_id_company UNIQUE (id, company_id),
    -- One current match per invoice. Re-matching overwrites; the history of what was claimed lives
    -- in the audit trail, exactly as V28 argued for delivery corrections.
    CONSTRAINT uq_freight_match_invoice UNIQUE (carrier_invoice_id),
    CONSTRAINT fk_freight_match_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_match_invoice FOREIGN KEY (carrier_invoice_id)
        REFERENCES tms.carrier_invoice (id) ON DELETE CASCADE,
    CONSTRAINT fk_freight_match_invoice_company FOREIGN KEY (carrier_invoice_id, company_id)
        REFERENCES tms.carrier_invoice (id, company_id),
    CONSTRAINT ck_freight_match_status CHECK (status IN ('MATCHED', 'DISCREPANCY', 'UNMATCHABLE')),
    CONSTRAINT ck_freight_match_currency CHECK (currency ~ '^[A-Z]{3}$'),
    -- The difference exists exactly when there is an expected figure to subtract from.
    CONSTRAINT ck_freight_match_difference_pairs_expected CHECK (
        (expected_amount IS NULL) = (difference_amount IS NULL)),
    -- Unknown must never be reported as matched. This is the constraint that makes UNMATCHABLE
    -- meaningful rather than decorative.
    CONSTRAINT ck_freight_match_unknown_is_not_matched CHECK (
        expected_amount IS NOT NULL OR status <> 'MATCHED')
);

COMMENT ON COLUMN tms.freight_match.expected_amount IS
    'What TMS priced the matched shipments at (V46). NULL means NO EXPECTED FIGURE EXISTS - never '
    'zero. A shipment nobody estimated cannot be compared, and reading that as 0.00 would report '
    'the whole invoice as an overcharge. ck_freight_match_unknown_is_not_matched refuses to call '
    'such an invoice MATCHED.';

-- ---------------------------------------------------------------------------
-- 7. Discrepancies
-- ---------------------------------------------------------------------------
CREATE TABLE tms.freight_discrepancy (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid          NOT NULL,
    carrier_invoice_id  uuid          NOT NULL,
    -- The line it is about, when it is about one. Header-level differences name none.
    invoice_line_id     uuid,
    type                text          NOT NULL,
    expected_amount     numeric(14,2),
    invoiced_amount     numeric(14,2),
    difference_amount   numeric(14,2),
    currency            char(3),
    -- The sentence a freight auditor reads. Composed server-side from the figures above so the
    -- explanation and the numbers cannot drift.
    detail              text          NOT NULL,
    status              text          NOT NULL DEFAULT 'OPEN',
    resolution_notes    text,
    resolved_at         timestamptz,
    resolved_by         uuid,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_freight_discrepancy PRIMARY KEY (id),
    CONSTRAINT uq_freight_discrepancy_id_company UNIQUE (id, company_id),
    CONSTRAINT fk_freight_discrepancy_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_discrepancy_invoice FOREIGN KEY (carrier_invoice_id)
        REFERENCES tms.carrier_invoice (id) ON DELETE CASCADE,
    CONSTRAINT fk_freight_discrepancy_invoice_company FOREIGN KEY (carrier_invoice_id, company_id)
        REFERENCES tms.carrier_invoice (id, company_id),
    CONSTRAINT fk_freight_discrepancy_line FOREIGN KEY (invoice_line_id)
        REFERENCES tms.carrier_invoice_line (id) ON DELETE CASCADE,
    CONSTRAINT fk_freight_discrepancy_resolved_by FOREIGN KEY (resolved_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,

    -- Six types, and no more until somebody needs to count a seventh. A catalogue of fifty codes
    -- nobody populates is worse than six that are always accurate.
    CONSTRAINT ck_freight_discrepancy_type CHECK (type IN (
        'TOTAL_AMOUNT', 'LINE_AMOUNT', 'UNMATCHED_TRIP', 'DUPLICATE_INVOICE',
        'CURRENCY_MISMATCH', 'MISSING_EXPECTED_COST')),
    CONSTRAINT ck_freight_discrepancy_status CHECK (status IN ('OPEN', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_freight_discrepancy_detail_not_blank CHECK (btrim(detail) <> ''),
    CONSTRAINT ck_freight_discrepancy_resolution_pair CHECK (
        (resolved_at IS NULL) = (resolved_by IS NULL)),
    CONSTRAINT ck_freight_discrepancy_open_is_unresolved CHECK (
        status <> 'OPEN' OR resolved_at IS NULL)
);

CREATE INDEX ix_freight_discrepancy_invoice ON tms.freight_discrepancy (carrier_invoice_id);
CREATE INDEX ix_freight_discrepancy_open
    ON tms.freight_discrepancy (company_id, status) WHERE status = 'OPEN';

-- ---------------------------------------------------------------------------
-- 8. Approval
-- ---------------------------------------------------------------------------
--
-- Append-only: an approval reversed is a second row, never an edit. Somebody authorised an
-- obligation and the record of that decision is the whole point of the table.
CREATE TABLE tms.settlement_approval (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid          NOT NULL,
    carrier_invoice_id  uuid          NOT NULL,
    decision            text          NOT NULL,
    -- NOT NULL, and a real person. A machine cannot approve an expenditure: requireAppUserId
    -- refuses machines by design (JOB 07, debt D4) and this column makes that structural rather
    -- than a service check. No fake system actor exists to satisfy it.
    decided_by          uuid          NOT NULL,
    decided_at          timestamptz   NOT NULL DEFAULT now(),
    -- Required on a rejection: refusing to pay without saying why is not a decision a carrier can
    -- answer. Optional on an approval, where the figures already speak.
    comment             text,

    CONSTRAINT pk_settlement_approval PRIMARY KEY (id),
    CONSTRAINT uq_settlement_approval_id_company UNIQUE (id, company_id),
    CONSTRAINT fk_settlement_approval_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_settlement_approval_invoice FOREIGN KEY (carrier_invoice_id)
        REFERENCES tms.carrier_invoice (id) ON DELETE CASCADE,
    CONSTRAINT fk_settlement_approval_invoice_company FOREIGN KEY (carrier_invoice_id, company_id)
        REFERENCES tms.carrier_invoice (id, company_id),
    CONSTRAINT fk_settlement_approval_decided_by FOREIGN KEY (decided_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_settlement_approval_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_settlement_approval_rejection_explains CHECK (
        decision <> 'REJECTED' OR (comment IS NOT NULL AND btrim(comment) <> ''))
);

CREATE INDEX ix_settlement_approval_invoice ON tms.settlement_approval (carrier_invoice_id);

-- ---------------------------------------------------------------------------
-- 9. Payable export
-- ---------------------------------------------------------------------------
--
-- The hand-off to whoever pays. One row per invoice, ever: two clicks on Export must not create two
-- obligations, and a unique constraint is what makes that a fact rather than a hope.
CREATE TABLE tms.payable_export (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid          NOT NULL,
    carrier_invoice_id  uuid          NOT NULL,
    -- The idempotency key a downstream system deduplicates on, and the reason a retried export is
    -- the same obligation rather than a second one.
    export_reference    text          NOT NULL,
    format              text          NOT NULL,
    -- The artifact as it was handed over. Stored so "what exactly did we send to accounting" is
    -- answerable a year later without regenerating it from data that has since moved.
    payload             text          NOT NULL,
    exported_at         timestamptz   NOT NULL DEFAULT now(),
    exported_by         uuid          NOT NULL,

    CONSTRAINT pk_payable_export PRIMARY KEY (id),
    CONSTRAINT uq_payable_export_id_company UNIQUE (id, company_id),
    -- One export per invoice. THE idempotency guarantee of this module.
    CONSTRAINT uq_payable_export_invoice UNIQUE (carrier_invoice_id),
    CONSTRAINT uq_payable_export_reference UNIQUE (company_id, export_reference),
    CONSTRAINT fk_payable_export_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payable_export_invoice FOREIGN KEY (carrier_invoice_id)
        REFERENCES tms.carrier_invoice (id) ON DELETE CASCADE,
    CONSTRAINT fk_payable_export_invoice_company FOREIGN KEY (carrier_invoice_id, company_id)
        REFERENCES tms.carrier_invoice (id, company_id),
    CONSTRAINT fk_payable_export_exported_by FOREIGN KEY (exported_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_payable_export_format CHECK (format IN ('JSON', 'CSV')),
    CONSTRAINT ck_payable_export_reference_not_blank CHECK (btrim(export_reference) <> '')
);

-- ---------------------------------------------------------------------------
-- 10. Tenant isolation (ADR-005)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.carrier_invoice ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.carrier_invoice_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.tolerance_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.freight_match ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.freight_discrepancy ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.settlement_approval ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.payable_export ENABLE ROW LEVEL SECURITY;

-- An invoice and its lines are edited while they are being audited, so both take UPDATE and DELETE
-- - a line keyed wrong is removed before anybody approves anything.
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.carrier_invoice TO tms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.carrier_invoice_line TO tms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.tolerance_policy TO tms_app;
-- A match is recomputed, so it is replaced; a discrepancy is resolved, so it is updated.
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.freight_match TO tms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.freight_discrepancy TO tms_app;
-- No UPDATE and no DELETE: an approval and an export are decisions somebody made, and the record of
-- a decision that can be edited is not a record. A reversal is a new approval row.
GRANT SELECT, INSERT ON tms.settlement_approval TO tms_app;
GRANT SELECT, INSERT ON tms.payable_export TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.carrier_invoice
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.carrier_invoice_line
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.tolerance_policy
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.freight_match
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.freight_discrepancy
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.settlement_approval
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.payable_export
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 11. Audit
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
    -- V46. Approval and export are the two an auditor searches for by name.
    'INVOICE_RECEIVED', 'INVOICE_MATCHED', 'INVOICE_APPROVED', 'INVOICE_REJECTED',
    'INVOICE_EXPORTED'));

ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_aggregate_type;
-- Generated from com.ebim.tms.shared.audit.AuditAggregateType rather than transcribed. The first
-- version of this migration was written from memory and both omitted values the enum has
-- (MASTER_DATA_IMPORT_BATCH, ORDER_IMPORT_BATCH, SHIPMENT) and invented several it does not.
-- AuditVocabularyMigrationTest caught it, which is exactly why that test exists.
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_aggregate_type CHECK (aggregate_type IN (
    'LOCATION', 'CARRIER', 'VEHICLE', 'DRIVER',
    'TRANSPORT_ORDER', 'TRIP', 'PLANNING_RUN', 'INTEGRATION_CLIENT',
    'MASTER_DATA_IMPORT_BATCH', 'ORDER_IMPORT_BATCH', 'SHIPMENT', 'RATE_CARD',
    'TRIP_COST', 'COMPANY', 'APP_USER', 'MEMBERSHIP',
    'WEBHOOK_SUBSCRIPTION', 'LOCATION_RESOURCE', 'APPOINTMENT', 'CARRIER_INVOICE'));

-- ---------------------------------------------------------------------------
-- 11b. Permissions
-- ---------------------------------------------------------------------------
--
-- Six, and the split is the whole point. Recording an invoice, deciding it is payable and handing
-- it to accounting are three different authorities. An installation will want them in different
-- hands, and a single settlement:manage would let whoever keys an invoice approve their own -
-- which is the oldest control failure in accounts payable.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('settlement.invoice',   'read',    'View carrier invoices, their matches and their differences'),
    ('settlement.invoice',   'manage',  'Record and correct carrier invoices'),
    ('settlement.invoice',   'match',   'Compare an invoice with expected cost and work its differences'),
    ('settlement.invoice',   'approve', 'Authorise or refuse the expenditure'),
    ('settlement.invoice',   'export',  'Hand an approved obligation to accounting'),
    ('settlement.tolerance', 'manage',  'Configure how far an invoice may differ before review');

-- Reading and working the audit queue: administrators and planners. A planner is who knows whether
-- a shipment ran the way the invoice says.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code IN ('settlement.invoice:read', 'settlement.invoice:manage', 'settlement.invoice:match',
                 'settlement.tolerance:manage');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code = 'PLANNER'
  AND p.code IN ('settlement.invoice:read', 'settlement.invoice:match');

-- A viewer sees the queue and no figures beyond it - reading an invoice is already a commercial
-- disclosure, and this is the same line RATES draws.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code = 'VIEWER'
  AND p.code = 'settlement.invoice:read';

-- Approving an expenditure and exporting it go to administrators ONLY, and deliberately not to
-- PLANNER. Committing money is not a dispatcher's authority, and the person who works the audit
-- queue should not be the person who signs off their own conclusions.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code IN ('settlement.invoice:approve', 'settlement.invoice:export');

-- ---------------------------------------------------------------------------
-- 12. Deliberately NOT here
-- ---------------------------------------------------------------------------
--
--   * No ledger, no payment, no bank details, no accounting period. TMS validates and exports;
--     the ERP pays. Section 1.
--   * No writes to tms.trip_cost. Two owners of "what this shipment cost" is how two numbers come
--     to disagree, and V30's close/reopen already governs when that figure may change.
--   * No CHECK that lines sum to the header. A carrier's document is what it is; the difference is
--     a discrepancy to surface, not an insert to reject. Section 4.
--   * No automatic approval, at any tolerance. Matching decides whether a human needs to look;
--     it never decides to pay. An expenditure is authorised by a person, and settlement_approval
--     .decided_by is NOT NULL because of it.
--   * No currency conversion. Two currencies do not add up and this product invents no FX rate.
