-- ============================================================================
-- V66 - Fiscal documents under a per-country tax regime, credit/debit notes and penalties
--       (ADR-024 part 2 as amended by ADR-027, phases 60 and 60b)
-- ============================================================================
-- A carrier bills with a fiscal document - a factura, a credit note or a debit note - identified by
-- the issuer's tax id and a number in the format of its country, with the VAT broken out and, by
-- the rules of that country, one or more withholdings the shipper keeps and deposits with the tax
-- authority instead of paying them to the carrier: Peru's detraction (SPOT) or retention, Ecuador's
-- income-tax and VAT withholdings. Until now TMS stored one total and nothing else.
--
-- This migration was first written for Peru alone (IGV columns, SUNAT codes, a detraction slot and a
-- retention slot). It was never applied anywhere and was rewritten in place to be country-neutral
-- (ADR-027): the regime is selected by the company's fiscal country, the per-country formats are
-- validated in Java (a CHECK cannot know the country) and the CHECKs here state only what holds in
-- every country.
--
--   tms.company_settings                    fiscal_country, vat_rate, freight_vat_rate - ALL
--                                           NULLABLE: a null resolves to the deployment's property
--                                           tms.finance.tax.<country>.* (D-06). No rate is a literal
--                                           in SQL or Java.
--   tms.company_tax_withholding_setting     per company and withholding kind: enabled, code, rate,
--                                           threshold - each nullable, same rule.
--   tms.carrier_invoice                     fiscal country snapshot, document kind and fiscal code,
--                                           normalised fiscal number, issuer tax id, subtotal/tax
--                                           breakdown, reference to the original (notes).
--   tms.carrier_invoice_withholding         the withholdings of one document, as a list.
--   tms.carrier_invoice_line                penalty reason and reference; a negative amount only for
--                                           PENALTY (NOT VALID: rows written before V66 keep theirs).
--   tms.freight_discrepancy                 PENALTY joins the types.
--
-- Amounts of a note are stored POSITIVE, as every tax authority issues them; the sign comes from the
-- kind. So ck_carrier_invoice_total_nonnegative (V46) stays exactly as it is.
--
-- Every V46/V64 row stays valid: the new columns are nullable or defaulted (document_kind INVOICE).
-- No business rows are seeded. One permission is (settlement.tax:manage, V46 shape).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Fiscal settings per company (D-06: configurable, never fixed)
-- ---------------------------------------------------------------------------
-- fiscal_country selects the regime (PE, EC, anything else = the generic regime). Null falls back to
-- default_country (V34), which every row already has: a company that never opens the setting is
-- taxed by the country it already declared.
ALTER TABLE tms.company_settings
    ADD COLUMN fiscal_country   text,
    ADD COLUMN vat_rate         numeric(5,2),
    ADD COLUMN freight_vat_rate numeric(5,2);

ALTER TABLE tms.company_settings ADD CONSTRAINT ck_company_settings_fiscal_country CHECK (
    fiscal_country IS NULL OR fiscal_country ~ '^[A-Z]{2}$');

ALTER TABLE tms.company_settings ADD CONSTRAINT ck_company_settings_vat_rates CHECK (
    (vat_rate IS NULL OR (vat_rate >= 0 AND vat_rate < 100))
    AND (freight_vat_rate IS NULL OR (freight_vat_rate >= 0 AND freight_vat_rate < 100)));

COMMENT ON COLUMN tms.company_settings.fiscal_country IS
    'ISO 3166-1 alpha-2 country whose tax regime applies to the company (V66, ADR-027). NULL = '
    'default_country. PE and EC have a regime of their own; any other country gets the generic one.';
COMMENT ON COLUMN tms.company_settings.vat_rate IS
    'General VAT percentage (IGV, IVA...) (V66, ADR-027, D-06). NULL = tms.finance.tax.<country>.vat-rate.';
COMMENT ON COLUMN tms.company_settings.freight_vat_rate IS
    'VAT percentage a freight document is previewed with (V66, ADR-027). Differs from vat_rate where '
    'freight is taxed apart - domestic land freight is 0% in Ecuador (LRTI art. 56). NULL = '
    'tms.finance.tax.<country>.freight-vat-rate.';

-- A small table rather than columns, because the list of withholdings is the regime's and grows with
-- every country: columns per kind would turn each new country into a migration of company_settings.
CREATE TABLE tms.company_tax_withholding_setting (
    id                uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id        uuid          NOT NULL,
    -- com.ebim.tms.settlement.domain.tax.WithholdingKind. A country added later restates the list in
    -- its migration, like every vocabulary CHECK of this schema (PersistedEnumConstraintTest).
    kind              text          NOT NULL,
    -- Each nullable: a null is the deployment default for that field (D-06).
    enabled           boolean,
    code              text,
    rate              numeric(5,2),
    threshold_amount  numeric(14,2),
    version           bigint        NOT NULL DEFAULT 0,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_by        uuid,

    CONSTRAINT pk_company_tax_withholding_setting PRIMARY KEY (id),
    CONSTRAINT uq_company_tax_withholding_setting_kind UNIQUE (company_id, kind),
    CONSTRAINT fk_company_tax_withholding_setting_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_company_tax_withholding_setting_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_company_tax_withholding_setting_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,

    CONSTRAINT ck_company_tax_withholding_setting_kind CHECK (kind IN ('PE_DETRACTION', 'PE_RETENTION', 'EC_INCOME_TAX_WITHHOLDING', 'EC_VAT_WITHHOLDING')),
    CONSTRAINT ck_company_tax_withholding_setting_code CHECK (code IS NULL OR code ~ '^[0-9A-Z]{1,10}$'),
    -- Up to and including 100: a VAT withholding may keep all of the VAT (Ecuador, 100%).
    CONSTRAINT ck_company_tax_withholding_setting_rate CHECK (rate IS NULL OR (rate >= 0 AND rate <= 100)),
    CONSTRAINT ck_company_tax_withholding_setting_threshold CHECK (
        threshold_amount IS NULL OR threshold_amount >= 0)
);

COMMENT ON TABLE tms.company_tax_withholding_setting IS
    'A company''s own value for a withholding of its tax regime (V66, ADR-027, D-06). No row, or a null '
    'field, = the deployment default tms.finance.tax.<country>.withholdings.<kind>.*.';

-- ---------------------------------------------------------------------------
-- 2. The document: kind, fiscal identity, breakdown
-- ---------------------------------------------------------------------------
ALTER TABLE tms.carrier_invoice
    -- The regime the document was received under, frozen: a company that later changes its fiscal
    -- country does not relabel, revalidate or recompute what it already received.
    ADD COLUMN fiscal_country          text,
    ADD COLUMN document_kind           text NOT NULL DEFAULT 'INVOICE',
    -- The country's code for the kind (PE 01/07/08, EC SRI 01/04/05); null where the regime has none.
    ADD COLUMN fiscal_document_code    text,
    -- Normalised by the regime: PE F001-123 (no leading zeros), EC 001-001-000000123.
    ADD COLUMN fiscal_document_number  text,
    ADD COLUMN issuer_tax_id           text,
    ADD COLUMN subtotal_amount         numeric(14,2),
    ADD COLUMN tax_rate                numeric(5,2),
    ADD COLUMN tax_amount              numeric(14,2),
    ADD COLUMN referenced_invoice_id   uuid;

ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_document_kind CHECK (
    document_kind IN ('INVOICE', 'CREDIT_NOTE', 'DEBIT_NOTE'));

-- Shapes only. Which number, which code and which tax id are valid is the regime's question and is
-- answered in Java (TaxRegime), where a refusal can say which digit is wrong.
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_fiscal_country CHECK (
    fiscal_country IS NULL OR fiscal_country ~ '^[A-Z]{2}$');
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_fiscal_document_code CHECK (
    fiscal_document_code IS NULL OR fiscal_document_code ~ '^[0-9A-Z]{1,4}$');
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_fiscal_document_number CHECK (
    fiscal_document_number IS NULL
    OR (btrim(fiscal_document_number) = fiscal_document_number AND fiscal_document_number <> ''
        AND length(fiscal_document_number) <= 60));
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_issuer_tax_id CHECK (
    issuer_tax_id IS NULL OR issuer_tax_id ~ '^[0-9A-Za-z-]{1,20}$');
-- A fiscal identity is the number and the issuer together, under a known country, or none of them.
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_fiscal_identity_complete CHECK (
    num_nonnulls(fiscal_document_number, issuer_tax_id) IN (0, 2)
    AND (fiscal_document_number IS NULL OR fiscal_country IS NOT NULL));

-- THE fiscal uniqueness: one issuer cannot issue the same kind and number twice. Beside
-- uq_carrier_invoice_number (V46), not instead of it: a document without a fiscal identity is still
-- guarded by its number. The country is in the key because a tax id is unique within one.
CREATE UNIQUE INDEX uq_carrier_invoice_fiscal_document
    ON tms.carrier_invoice (company_id, fiscal_country, issuer_tax_id, document_kind, fiscal_document_number)
    WHERE fiscal_document_number IS NOT NULL;

-- Either no breakdown (every pre-V66 row, and a document outside the fiscal currency that brought
-- none) or all three, adding up to the total within one cent of rounding.
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_tax_breakdown CHECK (
    (subtotal_amount IS NULL AND tax_rate IS NULL AND tax_amount IS NULL)
    OR (subtotal_amount IS NOT NULL AND tax_rate IS NOT NULL AND tax_amount IS NOT NULL
        AND subtotal_amount >= 0 AND tax_amount >= 0 AND tax_rate >= 0 AND tax_rate < 100
        AND abs(subtotal_amount + tax_amount - total_amount) <= 0.01));

-- ---------------------------------------------------------------------------
-- 3. Credit and debit notes reference the original, of the same carrier
-- ---------------------------------------------------------------------------
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT uq_carrier_invoice_id_company_carrier
    UNIQUE (id, company_id, carrier_id);

ALTER TABLE tms.carrier_invoice ADD CONSTRAINT fk_carrier_invoice_referenced
    FOREIGN KEY (referenced_invoice_id, company_id, carrier_id)
    REFERENCES tms.carrier_invoice (id, company_id, carrier_id) ON DELETE RESTRICT;

-- A note references an invoice; an invoice references nothing; nothing references itself.
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_reference CHECK (
    (document_kind IN ('CREDIT_NOTE', 'DEBIT_NOTE')) = (referenced_invoice_id IS NOT NULL)
    AND (referenced_invoice_id IS NULL OR referenced_invoice_id <> id));

CREATE INDEX ix_carrier_invoice_referenced
    ON tms.carrier_invoice (referenced_invoice_id) WHERE referenced_invoice_id IS NOT NULL;

COMMENT ON COLUMN tms.carrier_invoice.document_kind IS
    'INVOICE, CREDIT_NOTE or DEBIT_NOTE (V66, ADR-027). Amounts are stored positive; a credit note '
    'subtracts from its referenced invoice, a debit note adds. The country''s code is fiscal_document_code.';
COMMENT ON COLUMN tms.carrier_invoice.subtotal_amount IS
    'Net of VAT (V66). What the shipments are compared against and what an accrual is consumed by: VAT '
    'is fiscal credit, not expense. NULL on pre-V66 documents.';
COMMENT ON COLUMN tms.carrier_invoice.fiscal_country IS
    'The tax regime the document was received under (V66, ADR-027), frozen at receipt.';

-- ---------------------------------------------------------------------------
-- 4. Withholdings: computed by TMS, never paid by it
-- ---------------------------------------------------------------------------
-- A list rather than slots: Peru has a detraction OR a retention, Ecuador an income-tax withholding
-- AND sometimes a VAT withholding on the same document, and the next country brings its own.
CREATE TABLE tms.carrier_invoice_withholding (
    id                   uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id           uuid          NOT NULL,
    carrier_invoice_id   uuid          NOT NULL,
    kind                 text          NOT NULL,
    -- The authority's code: SPOT annex 027, SRI 310...; null where the kind has none.
    code                 text,
    -- What the rate applies to: the document TOTAL, its SUBTOTAL, or its VAT amount.
    base                 text          NOT NULL,
    base_amount          numeric(14,2) NOT NULL,
    rate                 numeric(5,2)  NOT NULL,
    -- What TMS computed, kept beside what applies so an override never hides the computation.
    computed_amount      numeric(14,2) NOT NULL,
    amount               numeric(14,2) NOT NULL,
    override_reason      text,
    -- The proof: the detraction deposit's constancy, Ecuador's comprobante de retención. Recorded
    -- when the ERP or a person reports it, after the document was received.
    certificate_number   text,
    certificate_date     date,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_carrier_invoice_withholding PRIMARY KEY (id),
    CONSTRAINT uq_carrier_invoice_withholding_kind UNIQUE (carrier_invoice_id, kind),
    CONSTRAINT fk_carrier_invoice_withholding_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_carrier_invoice_withholding_invoice FOREIGN KEY (carrier_invoice_id, company_id)
        REFERENCES tms.carrier_invoice (id, company_id) ON DELETE RESTRICT,

    CONSTRAINT ck_carrier_invoice_withholding_kind CHECK (kind IN ('PE_DETRACTION', 'PE_RETENTION', 'EC_INCOME_TAX_WITHHOLDING', 'EC_VAT_WITHHOLDING')),
    CONSTRAINT ck_carrier_invoice_withholding_code CHECK (code IS NULL OR code ~ '^[0-9A-Z]{1,10}$'),
    CONSTRAINT ck_carrier_invoice_withholding_base CHECK (base IN ('TOTAL', 'SUBTOTAL', 'VAT')),
    CONSTRAINT ck_carrier_invoice_withholding_amounts CHECK (
        base_amount >= 0 AND computed_amount >= 0 AND amount >= 0
        AND rate >= 0 AND rate <= 100
        -- Nothing withholds more than its base.
        AND computed_amount <= base_amount AND amount <= base_amount),
    -- An override says why, and only an override carries a reason.
    CONSTRAINT ck_carrier_invoice_withholding_override CHECK (
        (override_reason IS NULL AND amount = computed_amount)
        OR (override_reason IS NOT NULL AND btrim(override_reason) <> '' AND length(override_reason) <= 500)),
    CONSTRAINT ck_carrier_invoice_withholding_certificate CHECK (
        (certificate_number IS NULL OR (btrim(certificate_number) <> '' AND length(certificate_number) <= 60))
        AND (certificate_date IS NULL OR certificate_number IS NOT NULL))
);

CREATE INDEX ix_carrier_invoice_withholding_invoice ON tms.carrier_invoice_withholding (carrier_invoice_id);

COMMENT ON TABLE tms.carrier_invoice_withholding IS
    'The withholdings of one carrier document (V66, ADR-027): computed by TMS from the company''s tax '
    'regime at receipt, overridable with a reason where the kind allows it, never paid by TMS. The '
    'figures are frozen once written (trigger); only the certificate number and date may be recorded later.';

-- Update-protected: a withholding is computed once, at receipt. What can still change is the proof
-- that it was deposited. The grant below withholds DELETE from tms_app; this trigger also holds the
-- figures against the owner, which is the role a hand-run script would use.
CREATE FUNCTION tms.carrier_invoice_withholding_protect()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NEW.company_id IS DISTINCT FROM OLD.company_id
       OR NEW.carrier_invoice_id IS DISTINCT FROM OLD.carrier_invoice_id
       OR NEW.kind IS DISTINCT FROM OLD.kind
       OR NEW.code IS DISTINCT FROM OLD.code
       OR NEW.base IS DISTINCT FROM OLD.base
       OR NEW.base_amount IS DISTINCT FROM OLD.base_amount
       OR NEW.rate IS DISTINCT FROM OLD.rate
       OR NEW.computed_amount IS DISTINCT FROM OLD.computed_amount
       OR NEW.amount IS DISTINCT FROM OLD.amount
       OR NEW.override_reason IS DISTINCT FROM OLD.override_reason THEN
        RAISE EXCEPTION 'tms.%: a withholding''s figures are frozen; only its certificate may change', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION tms.carrier_invoice_withholding_protect() FROM PUBLIC;

CREATE TRIGGER tr_carrier_invoice_withholding_protect
    BEFORE UPDATE ON tms.carrier_invoice_withholding
    FOR EACH ROW EXECUTE FUNCTION tms.carrier_invoice_withholding_protect();

-- ---------------------------------------------------------------------------
-- 5. Penalties on lines
-- ---------------------------------------------------------------------------
ALTER TABLE tms.carrier_invoice_line
    ADD COLUMN penalty_reason          text,
    ADD COLUMN penalty_reference_type  text,
    ADD COLUMN penalty_reference_id    uuid;

-- A negative amount is a deduction, and only a PENALTY line is one. A PENALTY line is negative and
-- says why.
--
-- NOT VALID (ADR-027 section 6): V46 accepted a negative line of any kind - a carrier's discount
-- keyed as its own line - and a database that recorded one before V66 would otherwise refuse this
-- migration. Existing rows keep what they say; every row written from V66 on is checked. A discount
-- is no longer keyed as a negative line: the electronic documents of Peru and Ecuador carry it as an
-- attribute of the line and print the line net, which is what the line amount records.
ALTER TABLE tms.carrier_invoice_line ADD CONSTRAINT ck_carrier_invoice_line_sign CHECK (
    (charge_code IS DISTINCT FROM 'PENALTY' AND line_amount >= 0)
    OR (charge_code = 'PENALTY' AND line_amount < 0
        AND penalty_reason IS NOT NULL AND btrim(penalty_reason) <> '')) NOT VALID;

ALTER TABLE tms.carrier_invoice_line ADD CONSTRAINT ck_carrier_invoice_line_penalty_reference_type CHECK (
    penalty_reference_type IN ('TRIP_EXCEPTION', 'DELIVERY_FAILED', 'LATE_ARRIVAL', 'OTHER'));

ALTER TABLE tms.carrier_invoice_line ADD CONSTRAINT ck_carrier_invoice_line_penalty_reference CHECK (
    (penalty_reference_id IS NULL OR penalty_reference_type IS NOT NULL)
    AND ((penalty_reason IS NULL AND penalty_reference_type IS NULL)
         OR charge_code = 'PENALTY')
    AND (penalty_reason IS NULL OR length(penalty_reason) <= 500));

-- ---------------------------------------------------------------------------
-- 6. PENALTY joins the discrepancy types
-- ---------------------------------------------------------------------------
-- A penalty is not a difference against the shipment's cost, but it is something the approver must
-- accept on its own line before the document can be approved; the discrepancy workflow is exactly
-- that ("listed as its own item in the approval view", ADR-024 part 2, item 6).
ALTER TABLE tms.freight_discrepancy DROP CONSTRAINT ck_freight_discrepancy_type;
ALTER TABLE tms.freight_discrepancy ADD CONSTRAINT ck_freight_discrepancy_type CHECK (type IN (
    'TOTAL_AMOUNT', 'LINE_AMOUNT', 'UNMATCHED_TRIP', 'DUPLICATE_INVOICE',
    'CURRENCY_MISMATCH', 'MISSING_EXPECTED_COST', 'PENALTY'));

-- ---------------------------------------------------------------------------
-- 7. Tenant isolation (ADR-005) and least privilege (V50)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.company_tax_withholding_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.carrier_invoice_withholding ENABLE ROW LEVEL SECURITY;

-- A setting is configuration: a PUT replaces the company's overrides, so a kind it no longer
-- overrides is deleted. The audit event (UPDATE on COMPANY) records what changed.
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.company_tax_withholding_setting TO tms_app;
-- A withholding is written with its document and never deleted; its certificate is updated.
GRANT SELECT, INSERT, UPDATE ON tms.carrier_invoice_withholding TO tms_app;
REVOKE DELETE ON tms.carrier_invoice_withholding FROM tms_app;

CREATE POLICY p_tenant_company_scope ON tms.company_tax_withholding_setting
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.carrier_invoice_withholding
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 8. Permission (V46 shape)
-- ---------------------------------------------------------------------------
INSERT INTO tms.permission (resource, action, description) VALUES
    ('settlement.tax', 'manage',
     'Edit the company''s fiscal regime: fiscal country, VAT rates and withholdings');

-- Administrators' authority, the line V46 draws for approval and export: the regime decides what
-- the shipper withholds from every carrier.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code = 'settlement.tax:manage';

-- ---------------------------------------------------------------------------
-- 9. Audit vocabulary (the whole list, restated)
-- ---------------------------------------------------------------------------
-- RETENTION_OVERRIDDEN is any withholding overridden (the kind travels in the details): "retención"
-- is what both Peru and Ecuador call a withholding. A change of the fiscal regime is UPDATE on the
-- COMPANY aggregate, like every other company setting (V68 restates this list after this migration).
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
    -- V66 (ADR-024 part 2, ADR-027).
    'CREDIT_NOTE_RECEIVED', 'DEBIT_NOTE_RECEIVED', 'PENALTY_ACCEPTED', 'RETENTION_OVERRIDDEN'));
