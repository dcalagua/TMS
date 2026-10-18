-- ============================================================================
-- V66 - Peruvian taxes, CPE identity, credit/debit notes and penalties (ADR-024 part 2, phase 60)
-- ============================================================================
-- A Peruvian carrier bills with an electronic payment voucher (CPE): a factura (01), a nota de
-- credito (07) or a nota de debito (08), identified by the issuer's RUC, a series and a
-- correlative, with the IGV broken out and, above a threshold, a detraction (SPOT) the shipper
-- deposits instead of paying it to the carrier. Until now TMS stored one total and nothing else.
--
--   tms.company_settings  fiscal settings per company, ALL NULLABLE: a null resolves to the
--                         deployment's property (tms.finance.pe.*, decision D-06). No rate is a
--                         literal in SQL or Java.
--   tms.carrier_invoice   breakdown, CPE identity, reference to the original (07/08), detraction,
--                         retention.
--   tms.carrier_invoice_line  penalty reason and reference; a negative amount only for PENALTY.
--   tms.freight_discrepancy   PENALTY joins the types: a deduction the approver must accept.
--
-- Amounts of a note are stored POSITIVE, as SUNAT issues them; the sign comes from the type. So
-- ck_carrier_invoice_total_nonnegative (V46) stays exactly as it is - no credit note needs a
-- negative total (ADR-024 part 2, item 5).
--
-- Every V46/V64 row stays valid: the new columns are nullable or defaulted (document_type '01').
-- No business rows are seeded and no permission is added: notes and penalties travel through the
-- V46 permissions (settlement.invoice:manage|match|approve|export).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Fiscal settings per company (D-06: configurable, never fixed)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.company_settings
    ADD COLUMN igv_rate                    numeric(5,2),
    ADD COLUMN detraction_enabled          boolean,
    ADD COLUMN detraction_code             text,
    ADD COLUMN detraction_rate             numeric(5,2),
    ADD COLUMN detraction_threshold_amount numeric(14,2),
    ADD COLUMN retention_agent             boolean,
    ADD COLUMN retention_rate              numeric(5,2);

ALTER TABLE tms.company_settings ADD CONSTRAINT ck_company_settings_fiscal_rates CHECK (
    (igv_rate IS NULL OR (igv_rate >= 0 AND igv_rate < 100))
    AND (detraction_rate IS NULL OR (detraction_rate >= 0 AND detraction_rate < 100))
    AND (retention_rate IS NULL OR (retention_rate >= 0 AND retention_rate < 100))
    AND (detraction_threshold_amount IS NULL OR detraction_threshold_amount >= 0));

-- The SUNAT annex code of the good or service (027 = transport of goods). Three digits.
ALTER TABLE tms.company_settings ADD CONSTRAINT ck_company_settings_detraction_code CHECK (
    detraction_code IS NULL OR detraction_code ~ '^[0-9]{3}$');

COMMENT ON COLUMN tms.company_settings.igv_rate IS
    'IGV percentage (V66, ADR-024, D-06). NULL = tms.finance.pe.igv-rate.';
COMMENT ON COLUMN tms.company_settings.detraction_enabled IS
    'Whether detraction (SPOT) is computed on PEN documents (V66). NULL = tms.finance.pe.detraction-enabled.';
COMMENT ON COLUMN tms.company_settings.detraction_threshold_amount IS
    'Detraction applies to totals STRICTLY ABOVE this amount, in PEN (V66). NULL = '
    'tms.finance.pe.detraction-threshold-pen.';
COMMENT ON COLUMN tms.company_settings.retention_agent IS
    'Whether the company is a retention agent (V66). Retention never applies where detraction does. '
    'NULL = tms.finance.pe.retention-agent.';

-- ---------------------------------------------------------------------------
-- 2. The document: type, CPE identity, breakdown
-- ---------------------------------------------------------------------------
ALTER TABLE tms.carrier_invoice
    ADD COLUMN document_type          text NOT NULL DEFAULT '01',
    ADD COLUMN series                 text,
    ADD COLUMN correlative            text,
    ADD COLUMN issuer_ruc             text,
    ADD COLUMN subtotal_amount        numeric(14,2),
    ADD COLUMN igv_rate               numeric(5,2),
    ADD COLUMN igv_amount             numeric(14,2),
    ADD COLUMN referenced_invoice_id  uuid,
    ADD COLUMN detraction_code        text,
    ADD COLUMN detraction_rate        numeric(5,2),
    ADD COLUMN detraction_amount      numeric(14,2),
    ADD COLUMN detraction_constancy_number text,
    ADD COLUMN detraction_constancy_date   date,
    ADD COLUMN retention_rate         numeric(5,2),
    ADD COLUMN retention_amount       numeric(14,2),
    ADD COLUMN retention_override_reason text;

ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_document_type CHECK (
    document_type IN ('01', '07', '08'));

-- Series: F + 3 alphanumerics (electronic factura and its notes), E001 (issued from SUNAT's
-- portal) or 4 digits (a contingency paper series). Correlative: up to 8 digits, stored without
-- leading zeros so 00000123 and 123 are one document. RUC: 11 digits with a valid prefix; the check
-- digit (modulo 11) is verified in Java (RucValidator), where a refusal can say which digit.
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_series CHECK (
    series IS NULL OR series ~ '^(F[A-Z0-9]{3}|E001|[0-9]{4})$');
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_correlative CHECK (
    correlative IS NULL OR correlative ~ '^[1-9][0-9]{0,7}$');
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_issuer_ruc CHECK (
    issuer_ruc IS NULL OR issuer_ruc ~ '^(10|15|17|20)[0-9]{9}$');
-- A CPE identity is the three together or none of them.
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_cpe_complete CHECK (
    num_nonnulls(series, correlative, issuer_ruc) IN (0, 3));

-- THE CPE uniqueness: one issuer cannot issue the same type, series and correlative twice. Beside
-- uq_carrier_invoice_number (V46), not instead of it: a document without a CPE identity is still
-- guarded by its number.
CREATE UNIQUE INDEX uq_carrier_invoice_cpe
    ON tms.carrier_invoice (company_id, issuer_ruc, document_type, series, correlative)
    WHERE series IS NOT NULL;

-- Either no breakdown (every pre-V66 row, and a non-PEN document that brought none) or all three,
-- adding up to the total within one cent of rounding.
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_tax_breakdown CHECK (
    (subtotal_amount IS NULL AND igv_rate IS NULL AND igv_amount IS NULL)
    OR (subtotal_amount IS NOT NULL AND igv_rate IS NOT NULL AND igv_amount IS NOT NULL
        AND subtotal_amount >= 0 AND igv_amount >= 0 AND igv_rate >= 0 AND igv_rate < 100
        AND abs(subtotal_amount + igv_amount - total_amount) <= 0.01));

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
    (document_type IN ('07', '08')) = (referenced_invoice_id IS NOT NULL)
    AND (referenced_invoice_id IS NULL OR referenced_invoice_id <> id));

CREATE INDEX ix_carrier_invoice_referenced
    ON tms.carrier_invoice (referenced_invoice_id) WHERE referenced_invoice_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 4. Detraction (SPOT) and retention: computed, never paid
-- ---------------------------------------------------------------------------
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_detraction CHECK (
    num_nonnulls(detraction_code, detraction_rate, detraction_amount) IN (0, 3)
    AND (detraction_code IS NULL OR detraction_code ~ '^[0-9]{3}$')
    AND (detraction_amount IS NULL OR detraction_amount >= 0)
    AND (detraction_rate IS NULL OR (detraction_rate >= 0 AND detraction_rate < 100))
    -- The constancy is the deposit's proof; it exists only for a detraction that was computed.
    AND (detraction_amount IS NOT NULL OR (detraction_constancy_number IS NULL
                                           AND detraction_constancy_date IS NULL))
    AND (detraction_constancy_number IS NULL OR btrim(detraction_constancy_number) <> ''));

ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_retention CHECK (
    num_nonnulls(retention_rate, retention_amount) IN (0, 2)
    AND (retention_amount IS NULL OR retention_amount >= 0)
    AND (retention_rate IS NULL OR (retention_rate >= 0 AND retention_rate < 100))
    AND (retention_override_reason IS NULL OR (retention_amount IS NOT NULL
                                               AND btrim(retention_override_reason) <> '')));

-- The two are exclusive: a document subject to detraction is not subject to retention.
ALTER TABLE tms.carrier_invoice ADD CONSTRAINT ck_carrier_invoice_detraction_or_retention CHECK (
    detraction_amount IS NULL OR retention_amount IS NULL);

COMMENT ON COLUMN tms.carrier_invoice.document_type IS
    'SUNAT document type (V66, ADR-024): 01 factura, 07 nota de credito, 08 nota de debito. Amounts '
    'are stored positive; a 07 subtracts from its referenced invoice, an 08 adds.';
COMMENT ON COLUMN tms.carrier_invoice.subtotal_amount IS
    'Valor de venta, net of IGV (V66). What the shipments are compared against and what an accrual '
    'is consumed by: IGV is fiscal credit, not expense. NULL on pre-V66 documents.';
COMMENT ON COLUMN tms.carrier_invoice.detraction_amount IS
    'Detraction computed by TMS (V66): round(total x rate, 0) HALF_UP when the company computes it, '
    'the currency is PEN and the total is strictly above the threshold. TMS computes, never pays.';

-- ---------------------------------------------------------------------------
-- 5. Penalties on lines
-- ---------------------------------------------------------------------------
ALTER TABLE tms.carrier_invoice_line
    ADD COLUMN penalty_reason          text,
    ADD COLUMN penalty_reference_type  text,
    ADD COLUMN penalty_reference_id    uuid;

-- A negative amount is a deduction, and only a PENALTY line is one. A PENALTY line is negative and
-- says why.
ALTER TABLE tms.carrier_invoice_line ADD CONSTRAINT ck_carrier_invoice_line_sign CHECK (
    (charge_code IS DISTINCT FROM 'PENALTY' AND line_amount >= 0)
    OR (charge_code = 'PENALTY' AND line_amount < 0
        AND penalty_reason IS NOT NULL AND btrim(penalty_reason) <> ''));

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
    'CREDIT_NOTE_RECEIVED', 'DEBIT_NOTE_RECEIVED', 'PENALTY_ACCEPTED', 'RETENTION_OVERRIDDEN'));
