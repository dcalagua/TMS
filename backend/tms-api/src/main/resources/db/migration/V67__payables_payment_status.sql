-- ============================================================================
-- V67 - Payable payment status, due dates and the carrier's invoices (ADR-023 section 2, phase 65)
-- ============================================================================
-- After the export (V46) an approved invoice was out of sight: the ERP paid it, or did not, and TMS
-- could not say which. This migration lets TMS RECORD what the ERP reports - or what a person keys,
-- with a second person confirming - without moving any money (D-01): no bank account, no bank file,
-- no ledger.
--
--   tms.carrier.payment_terms_days                 per carrier (nullable: falls back)
--   tms.company_settings.default_payment_terms_days per company (nullable: falls back to the
--                                                   property tms.settlement.default-payment-terms-days)
--   tms.carrier_invoice                            due_date (frozen at approval), paid_amount,
--                                                  on_hold_reason, scheduled_payment_date and the
--                                                  statuses SCHEDULED, PARTIALLY_PAID, PAID, ON_HOLD
--   tms.carrier_payment                            a payment as reported, append-only
--   tms.carrier_payment_request                    a person's payment awaiting a second person
--
-- Plus: the carrier portal sees its own invoices (RESTRICTIVE policies keyed on
-- tms.current_carrier_id(), V57), the business outbox (V61) carries invoice events, one integration
-- scope, three permissions and the audit vocabulary. No business rows are seeded.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Payment terms
-- ---------------------------------------------------------------------------
ALTER TABLE tms.carrier
    ADD COLUMN payment_terms_days integer;

ALTER TABLE tms.carrier ADD CONSTRAINT ck_carrier_payment_terms_days
    CHECK (payment_terms_days IS NULL OR payment_terms_days BETWEEN 0 AND 365);

COMMENT ON COLUMN tms.carrier.payment_terms_days IS
    'Days from the invoice date to its due date for this carrier (V67, ADR-023). Null: the company '
    'default, else the property tms.settlement.default-payment-terms-days (D-06).';

ALTER TABLE tms.company_settings
    ADD COLUMN default_payment_terms_days integer;

ALTER TABLE tms.company_settings ADD CONSTRAINT ck_company_settings_default_payment_terms_days
    CHECK (default_payment_terms_days IS NULL OR default_payment_terms_days BETWEEN 0 AND 365);

COMMENT ON COLUMN tms.company_settings.default_payment_terms_days IS
    'Payment terms of a carrier without its own (V67, ADR-023). Null: the property default.';

-- ---------------------------------------------------------------------------
-- 2. The invoice after the export
-- ---------------------------------------------------------------------------
ALTER TABLE tms.carrier_invoice
    -- Set at approval from the terms then in force, and never recomputed: a carrier whose terms
    -- change next month does not move the due date of what was already authorised.
    ADD COLUMN due_date               date,
    ADD COLUMN payment_terms_days     integer,
    -- Sum of tms.carrier_payment.amount for this invoice, written in the same transaction as the
    -- payment that moves it. The payments are the truth; a test proves the two agree.
    ADD COLUMN paid_amount            numeric(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN on_hold_reason         text,
    -- The date the ERP (or a person) says the payment is planned for. Informative.
    ADD COLUMN scheduled_payment_date date;

ALTER TABLE tms.carrier_invoice DROP CONSTRAINT ck_carrier_invoice_status;
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_status CHECK (status IN (
    'RECEIVED', 'MATCHING', 'MATCHED', 'DISCREPANCY', 'UNDER_REVIEW',
    'APPROVED', 'REJECTED', 'EXPORTED',
    -- V67 (ADR-023 section 2).
    'SCHEDULED', 'PARTIALLY_PAID', 'PAID', 'ON_HOLD'));

ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_paid_amount
    CHECK (paid_amount >= 0);
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_payment_terms_days
    CHECK (payment_terms_days IS NULL OR payment_terms_days BETWEEN 0 AND 365);
-- A due date and its terms travel together.
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_due_date_terms
    CHECK ((due_date IS NULL) = (payment_terms_days IS NULL));
-- A hold says why, and only a held invoice carries a reason.
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_on_hold_reason
    CHECK ((status = 'ON_HOLD') = (on_hold_reason IS NOT NULL)
           AND (on_hold_reason IS NULL OR (btrim(on_hold_reason) <> '' AND length(on_hold_reason) <= 500)));
-- A credit or debit note adjusts its invoice's balance and is never paid on its own.
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_payment_only_on_invoice
    CHECK (document_kind = 'INVOICE'
           OR (status NOT IN ('SCHEDULED', 'PARTIALLY_PAID', 'PAID', 'ON_HOLD') AND paid_amount = 0));
-- Nothing is paid before the export.
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_paid_after_export
    CHECK (paid_amount = 0 OR status IN ('PARTIALLY_PAID', 'PAID', 'ON_HOLD'));

-- What the aging and the due list read: authorised invoices of a company by due date.
CREATE INDEX ix_carrier_invoice_company_due
    ON tms.carrier_invoice (company_id, due_date, id)
    WHERE status IN ('APPROVED', 'EXPORTED', 'SCHEDULED', 'PARTIALLY_PAID', 'ON_HOLD');

COMMENT ON COLUMN tms.carrier_invoice.due_date IS
    'invoice_date + payment terms, set at approval and frozen (V67, ADR-023). Null before approval '
    'and on invoices approved before V67.';
COMMENT ON COLUMN tms.carrier_invoice.paid_amount IS
    'What has been paid to the carrier, as reported (V67). Mirrors SUM(tms.carrier_payment.amount).';

-- ---------------------------------------------------------------------------
-- 3. The payment, as reported - append-only
-- ---------------------------------------------------------------------------
CREATE TABLE tms.carrier_payment (
    id                           uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id                   uuid          NOT NULL,
    carrier_invoice_id           uuid          NOT NULL,
    -- Copied from the invoice (composite FK), so the carrier policy reads one column.
    carrier_id                   uuid          NOT NULL,
    -- The ERP's own reference (a transfer, a batch, a cheque number), byte for byte.
    payment_reference            text          NOT NULL,
    paid_on                      date          NOT NULL,
    -- Paid to the carrier. The withholdings (Peru's detraction or retention, Ecuador's income-tax and
    -- VAT withholdings...) are deposited elsewhere and are not part of it.
    amount                       numeric(14,2) NOT NULL,
    -- Must be the invoice's: TMS invents no exchange rate.
    currency                     char(3)       NOT NULL,
    -- The withholdings as the ERP or the person reported them with this payment, kept whole:
    -- [{"kind", "amount", "certificateNumber", "certificateDate"}] (ADR-027). Informative - the
    -- balance is computed from the document's own withholdings - and the certificate of each is
    -- recorded on tms.carrier_invoice_withholding. A snapshot of a report, not a ledger: never summed
    -- and never queried into, so text holding JSON - the reason V18, V35 and V53 chose text.
    reported_withholdings        text          NOT NULL DEFAULT '[]',
    source                       text          NOT NULL,
    -- The ERP's credential, when the ERP reported it.
    recorded_by_client           uuid,
    -- The maker and the checker, when two people keyed it.
    recorded_by                  uuid,
    confirmed_by                 uuid,
    payment_request_id           uuid,
    created_at                   timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_carrier_payment PRIMARY KEY (id),
    CONSTRAINT uq_carrier_payment_id_company UNIQUE (id, company_id),
    -- THE business idempotency: one reference is one payment of one invoice.
    CONSTRAINT uq_carrier_payment_reference UNIQUE (company_id, carrier_invoice_id, payment_reference),

    CONSTRAINT fk_carrier_payment_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_carrier_payment_invoice FOREIGN KEY (carrier_invoice_id, company_id, carrier_id)
        REFERENCES tms.carrier_invoice (id, company_id, carrier_id) ON DELETE RESTRICT,
    CONSTRAINT fk_carrier_payment_client_company FOREIGN KEY (recorded_by_client, company_id)
        REFERENCES tms.integration_client (id, company_id),
    CONSTRAINT fk_carrier_payment_recorded_by FOREIGN KEY (recorded_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_carrier_payment_confirmed_by FOREIGN KEY (confirmed_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,

    CONSTRAINT ck_carrier_payment_reference CHECK (
        btrim(payment_reference) <> '' AND length(payment_reference) <= 80),
    CONSTRAINT ck_carrier_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_carrier_payment_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_carrier_payment_reported_withholdings CHECK (jsonb_typeof(reported_withholdings::jsonb) = 'array'),
    CONSTRAINT ck_carrier_payment_source CHECK (source IN ('INTEGRATION', 'OPERATOR')),
    -- A machine or two people, never one person alone and never a mixture.
    CONSTRAINT ck_carrier_payment_attribution CHECK (
        (source = 'INTEGRATION' AND recorded_by_client IS NOT NULL
             AND recorded_by IS NULL AND confirmed_by IS NULL AND payment_request_id IS NULL)
        OR (source = 'OPERATOR' AND recorded_by_client IS NULL
             AND recorded_by IS NOT NULL AND confirmed_by IS NOT NULL AND recorded_by <> confirmed_by
             AND payment_request_id IS NOT NULL))
);

CREATE INDEX ix_carrier_payment_invoice ON tms.carrier_payment (carrier_invoice_id, paid_on);
CREATE INDEX ix_carrier_payment_company_paid_on ON tms.carrier_payment (company_id, paid_on);

COMMENT ON TABLE tms.carrier_payment IS
    'A payment of a carrier invoice as the ERP reported it or two people keyed it (V67, ADR-023 '
    'section 2). TMS moves no money (D-01). Append-only by trigger and by grant.';

-- Same shape as V65's tms.freight_accrual_entry_append_only(): the grant withholds UPDATE and
-- DELETE from tms_app; the trigger also holds against the owner.
CREATE FUNCTION tms.carrier_payment_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'tms.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

REVOKE ALL ON FUNCTION tms.carrier_payment_append_only() FROM PUBLIC;

CREATE TRIGGER tr_carrier_payment_append_only
    BEFORE UPDATE OR DELETE ON tms.carrier_payment
    FOR EACH ROW EXECUTE FUNCTION tms.carrier_payment_append_only();

-- ---------------------------------------------------------------------------
-- 4. A person's payment, awaiting a second person
-- ---------------------------------------------------------------------------
CREATE TABLE tms.carrier_payment_request (
    id                           uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id                   uuid          NOT NULL,
    carrier_invoice_id           uuid          NOT NULL,
    carrier_id                   uuid          NOT NULL,
    payment_reference            text          NOT NULL,
    paid_on                      date          NOT NULL,
    amount                       numeric(14,2) NOT NULL,
    currency                     char(3)       NOT NULL,
    -- Same shape as tms.carrier_payment.reported_withholdings, copied to the payment on confirmation.
    reported_withholdings        text          NOT NULL DEFAULT '[]',
    status                       text          NOT NULL DEFAULT 'PENDING',
    requested_by                 uuid          NOT NULL,
    requested_at                 timestamptz   NOT NULL DEFAULT now(),
    decided_by                   uuid,
    decided_at                   timestamptz,
    decision_comment             text,
    carrier_payment_id           uuid,
    version                      bigint        NOT NULL DEFAULT 0,
    created_at                   timestamptz   NOT NULL DEFAULT now(),
    updated_at                   timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_carrier_payment_request PRIMARY KEY (id),
    CONSTRAINT uq_carrier_payment_request_id_company UNIQUE (id, company_id),

    CONSTRAINT fk_carrier_payment_request_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_carrier_payment_request_invoice FOREIGN KEY (carrier_invoice_id, company_id, carrier_id)
        REFERENCES tms.carrier_invoice (id, company_id, carrier_id) ON DELETE RESTRICT,
    CONSTRAINT fk_carrier_payment_request_payment FOREIGN KEY (carrier_payment_id, company_id)
        REFERENCES tms.carrier_payment (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_carrier_payment_request_requested_by FOREIGN KEY (requested_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_carrier_payment_request_decided_by FOREIGN KEY (decided_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,

    CONSTRAINT ck_carrier_payment_request_reference CHECK (
        btrim(payment_reference) <> '' AND length(payment_reference) <= 80),
    CONSTRAINT ck_carrier_payment_request_amount CHECK (amount > 0),
    CONSTRAINT ck_carrier_payment_request_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_carrier_payment_request_reported_withholdings CHECK (
        jsonb_typeof(reported_withholdings::jsonb) = 'array'),
    CONSTRAINT ck_carrier_payment_request_status CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED')),
    -- Decided means somebody else decided, when, and - for a refusal - why; confirmed names the payment.
    CONSTRAINT ck_carrier_payment_request_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL AND carrier_payment_id IS NULL)
        OR (status = 'CONFIRMED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decided_by <> requested_by AND carrier_payment_id IS NOT NULL)
        OR (status = 'REJECTED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND carrier_payment_id IS NULL AND decision_comment IS NOT NULL AND btrim(decision_comment) <> '')),
    CONSTRAINT ck_carrier_payment_request_comment_length CHECK (
        decision_comment IS NULL OR length(decision_comment) <= 500)
);

-- One live request per reference: a double click keys one payment, not two.
CREATE UNIQUE INDEX uq_carrier_payment_request_pending_reference
    ON tms.carrier_payment_request (company_id, carrier_invoice_id, payment_reference)
    WHERE status = 'PENDING';
CREATE INDEX ix_carrier_payment_request_company_status
    ON tms.carrier_payment_request (company_id, status, requested_at);
CREATE INDEX ix_carrier_payment_request_invoice
    ON tms.carrier_payment_request (carrier_invoice_id, requested_at);

COMMENT ON TABLE tms.carrier_payment_request IS
    'A payment keyed by a person, which a different person confirms (inserting tms.carrier_payment) '
    'or rejects with a reason (V67, ADR-023 section 2, four eyes).';

-- ---------------------------------------------------------------------------
-- 5. Tenant isolation (ADR-005) and least privilege (V50)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.carrier_payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.carrier_payment_request ENABLE ROW LEVEL SECURITY;

-- A payment is booked once. V13's default privileges attach all four verbs; the REVOKE withholds.
GRANT SELECT, INSERT ON tms.carrier_payment TO tms_app;
REVOKE UPDATE, DELETE ON tms.carrier_payment FROM tms_app;
-- A request is decided, never deleted.
GRANT SELECT, INSERT, UPDATE ON tms.carrier_payment_request TO tms_app;
REVOKE DELETE ON tms.carrier_payment_request FROM tms_app;

CREATE POLICY p_tenant_company_scope ON tms.carrier_payment
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.carrier_payment_request
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 6. The carrier boundary (ADR-018, V57): a carrier sees its own invoices and payments only
-- ---------------------------------------------------------------------------
-- RESTRICTIVE: ANDed with p_tenant_company_scope. NULL tms.current_carrier_id() is every office
-- transaction, which these policies leave alone.
CREATE POLICY p_carrier_scope ON tms.carrier_invoice AS RESTRICTIVE
    FOR ALL TO tms_app
    USING (tms.current_carrier_id() IS NULL OR carrier_id = tms.current_carrier_id())
    WITH CHECK (tms.current_carrier_id() IS NULL OR carrier_id = tms.current_carrier_id());
CREATE POLICY p_carrier_scope ON tms.carrier_payment AS RESTRICTIVE
    FOR ALL TO tms_app
    USING (tms.current_carrier_id() IS NULL OR carrier_id = tms.current_carrier_id())
    WITH CHECK (tms.current_carrier_id() IS NULL OR carrier_id = tms.current_carrier_id());

-- Children defer to the invoice, whose own policy is the carrier predicate.
DO $$
DECLARE
    child text;
BEGIN
    FOREACH child IN ARRAY ARRAY['carrier_invoice_line', 'carrier_invoice_withholding', 'settlement_approval']
    LOOP
        EXECUTE format(
            'CREATE POLICY p_carrier_scope ON tms.%I AS RESTRICTIVE FOR ALL TO tms_app
                 USING (tms.current_carrier_id() IS NULL
                        OR EXISTS (SELECT 1 FROM tms.carrier_invoice i WHERE i.id = carrier_invoice_id))
                 WITH CHECK (tms.current_carrier_id() IS NULL
                        OR EXISTS (SELECT 1 FROM tms.carrier_invoice i WHERE i.id = carrier_invoice_id))', child);
    END LOOP;
END
$$;

-- The audit's internals and the office's own workflow are never a carrier's: the match, the
-- differences, the export and a person's pending payment are invisible to a carrier transaction.
DO $$
DECLARE
    internal text;
BEGIN
    FOREACH internal IN ARRAY ARRAY['freight_match', 'freight_discrepancy', 'payable_export',
                                    'carrier_payment_request']
    LOOP
        EXECUTE format(
            'CREATE POLICY p_carrier_scope ON tms.%I AS RESTRICTIVE FOR ALL TO tms_app
                 USING (tms.current_carrier_id() IS NULL)
                 WITH CHECK (tms.current_carrier_id() IS NULL)', internal);
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------------
-- 7. Business outbox (V61): invoice events
-- ---------------------------------------------------------------------------
ALTER TABLE tms.business_outbox_event DROP CONSTRAINT ck_business_outbox_event_resource_type;
ALTER TABLE tms.business_outbox_event ADD CONSTRAINT ck_business_outbox_event_resource_type
    CHECK (resource_type IN ('ORDER', 'CARRIER_INVOICE'));
ALTER TABLE tms.business_outbox_event DROP CONSTRAINT ck_business_outbox_event_event_type;
ALTER TABLE tms.business_outbox_event ADD CONSTRAINT ck_business_outbox_event_event_type
    CHECK (event_type IN ('ORDER_CANCELLED', 'INVOICE_APPROVED', 'PAYABLE_EXPORTED', 'PAYABLE_PAID'));

-- ---------------------------------------------------------------------------
-- 8. Webhook vocabulary (the whole list, restated)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.webhook_subscription_event DROP CONSTRAINT ck_webhook_subscription_event_type;
ALTER TABLE tms.webhook_subscription_event ADD CONSTRAINT ck_webhook_subscription_event_type CHECK (
    event_type IN (
        'SHIPMENT_CONFIRMED', 'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED',
        'SHIPMENT_CANCELLED', 'SHIPMENT_CHANGED', 'DELIVERY_RESULT_RECORDED',
        'TENDER_SENT', 'TENDER_ACCEPTED', 'TENDER_REJECTED', 'TENDER_EXPIRED', 'TENDER_CANCELLED',
        -- V61 (ADR-025 section 4).
        'ORDER_CANCELLED',
        -- V67 (ADR-023 section 2).
        'INVOICE_APPROVED', 'PAYABLE_EXPORTED', 'PAYABLE_PAID'));

-- ---------------------------------------------------------------------------
-- 9. Integration scopes (V55 shape: the whole list, restated)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.integration_client_scope DROP CONSTRAINT ck_integration_client_scope_value;
ALTER TABLE tms.integration_client_scope ADD CONSTRAINT ck_integration_client_scope_value CHECK (
    scope IN ('integration.location:write', 'integration.order:write', 'integration.shipment:read',
              'integration.tracking:write', 'integration.tender:respond',
              'integration.dispatch:write', 'integration.warehouse-milestone:write',
              'integration.document:write',
              -- V59 (ADR-022).
              'integration.material:write',
              -- V61 (ADR-025 section 4).
              'integration.order:read', 'integration.warehouse-order-event:write',
              -- V67 (ADR-023 section 2).
              'integration.payable-payment:write'));

-- ---------------------------------------------------------------------------
-- 10. Permissions (V46 / V57 shape)
-- ---------------------------------------------------------------------------
INSERT INTO tms.permission (resource, action, description) VALUES
    ('settlement.payment', 'record',
     'Key a payment for confirmation, schedule a payable and place or release a hold'),
    ('settlement.payment', 'confirm',
     'Confirm or reject a payment keyed by somebody else'),
    ('portal.invoice', 'read',
     'Carrier portal: read my carrier''s invoices, their due dates and what was paid');

-- Recording and confirming a payment are administrators' authority, the line V46 draws for
-- approval and export; the maker/checker rule is enforced on the people, not on the roles.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code IN ('settlement.payment:record', 'settlement.payment:confirm');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code = 'CARRIER_OPERATOR'
  AND p.code = 'portal.invoice:read';

-- ---------------------------------------------------------------------------
-- 11. Audit vocabulary (the whole list, restated)
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
    'PAYMENT_REQUESTED', 'PAYMENT_REQUEST_REJECTED', 'PAYMENT_RECORDED'));
