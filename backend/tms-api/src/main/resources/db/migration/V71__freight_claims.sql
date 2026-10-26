-- ============================================================================
-- V71 - Freight claims and the incident centre (evaluation item 5, roadmap item 7)
-- ============================================================================
-- Damage, shortage and loss lived in a spreadsheet: a delivery recorded PARTIAL with two chairs
-- refused told TMS that something went wrong, and nothing in TMS could say what the shipper asked
-- the carrier for, who agreed, and whether it was ever recovered. This migration gives that a home:
--
--   tms.freight_claim           a claim against the carrier of a trip, numbered RC-000001, with
--                               its type, the amount claimed, a lifecycle and - once decided - the
--                               amount accepted and how it is recovered
--   tms.freight_claim_line      optionally, what the claim is made of: an order line, a quantity,
--                               an amount. Rewritten whole while the claim is still OPEN
--   tms.freight_claim_evidence  photos and documents behind it, through EvidenceStoragePort
--                               (ADR-006 / ADR-020). Append-only, like delivery_evidence (V28)
--
-- TMS moves no money (D-01). An accepted claim is recovered through concepts settlement already
-- has, never through new ones: a PENALTY line on the carrier's invoice (V66), proposed to the
-- person keying the invoice and applied only when they confirm it, or a CREDIT_NOTE the carrier
-- sends (V66). Closing an accepted claim names the document that recovered it.
--
-- Plus: FREIGHT_CLAIM as a penalty reference type, a composite key on tms.trip_exception so a
-- claim can name the exception it came from, three permissions, and the audit vocabulary. No
-- business rows are seeded.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. The claim number
-- ---------------------------------------------------------------------------
-- Installation-wide, like tms.shipment_number_seq (V19): an opaque counter, never a tenant's
-- data. The number is unique per company by constraint; a company's numbers are increasing and
-- may have gaps, which a reader of RC-000007 after RC-000004 does not need explained.
CREATE SEQUENCE tms.freight_claim_number_seq AS bigint INCREMENT BY 1 START WITH 1 NO CYCLE;

GRANT USAGE, SELECT ON tms.freight_claim_number_seq TO tms_app;

COMMENT ON SEQUENCE tms.freight_claim_number_seq IS
    'Next freight claim number, formatted RC-000001 by FreightClaimService (V71). Installation-wide.';

-- ---------------------------------------------------------------------------
-- 2. A claim can name the exception it came from
-- ---------------------------------------------------------------------------
-- tms.trip_exception (V27) never needed a composite key because nothing referenced it. The claim
-- does, and the composite FK is what keeps that reference inside the company.
ALTER TABLE tms.trip_exception ADD CONSTRAINT uq_trip_exception_id_company UNIQUE (id, company_id);

-- ---------------------------------------------------------------------------
-- 3. The claim
-- ---------------------------------------------------------------------------
CREATE TABLE tms.freight_claim (
    id                   uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id           uuid          NOT NULL,
    claim_number         text          NOT NULL,
    -- Always the trip's carrier, copied when the claim is opened: a claim is against whoever ran
    -- the shipment, and an own-fleet trip (no carrier) has nobody to claim from.
    carrier_id           uuid          NOT NULL,
    trip_id              uuid          NOT NULL,
    -- What the claim is about, when it is narrower than the trip. All optional: a delay is the
    -- trip's, a broken chair is an order's, a PARTIAL delivery is one order at one stop.
    order_id             uuid,
    order_delivery_id    uuid,
    trip_exception_id    uuid,
    claim_type           text          NOT NULL,
    status               text          NOT NULL DEFAULT 'OPEN',
    -- When the damage, shortage or delay happened, as the person opening the claim states it.
    incident_date        date          NOT NULL,
    description          text          NOT NULL,
    claimed_amount       numeric(14,2) NOT NULL,
    -- TMS invents no exchange rate: a claim is in one currency, and so is what recovers it.
    currency             char(3)       NOT NULL,
    -- The decision. resolution survives CLOSED, which is why it is not folded into status.
    resolution           text,
    accepted_amount      numeric(14,2),
    recovery_method      text,
    decision_reason      text,
    decided_by           uuid,
    decided_at           timestamptz,
    opened_by            uuid          NOT NULL,
    opened_at            timestamptz   NOT NULL DEFAULT now(),
    submitted_by         uuid,
    submitted_at         timestamptz,
    closed_by            uuid,
    closed_at            timestamptz,
    close_notes          text,
    -- The carrier document that recovered an accepted claim: the invoice carrying the PENALTY
    -- line that references it, or the CREDIT_NOTE the carrier sent. Composite, so it is this
    -- company's and this carrier's.
    settlement_invoice_id uuid,
    cancellation_reason  text,
    version              bigint        NOT NULL DEFAULT 0,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_freight_claim PRIMARY KEY (id),
    CONSTRAINT uq_freight_claim_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_freight_claim_number UNIQUE (company_id, claim_number),

    CONSTRAINT fk_freight_claim_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_claim_carrier FOREIGN KEY (carrier_id, company_id)
        REFERENCES tms.carrier (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_claim_trip FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_claim_order FOREIGN KEY (order_id, company_id)
        REFERENCES tms.transport_order (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_claim_order_delivery FOREIGN KEY (order_delivery_id, company_id)
        REFERENCES tms.order_delivery (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_claim_trip_exception FOREIGN KEY (trip_exception_id, company_id)
        REFERENCES tms.trip_exception (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_claim_settlement_invoice FOREIGN KEY (settlement_invoice_id, company_id, carrier_id)
        REFERENCES tms.carrier_invoice (id, company_id, carrier_id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_claim_opened_by FOREIGN KEY (opened_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_claim_submitted_by FOREIGN KEY (submitted_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_claim_decided_by FOREIGN KEY (decided_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_claim_closed_by FOREIGN KEY (closed_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,

    CONSTRAINT ck_freight_claim_number CHECK (claim_number ~ '^RC-[0-9]{6,}$'),
    CONSTRAINT ck_freight_claim_type CHECK (claim_type IN (
        'DAMAGE', 'SHORTAGE', 'LOSS', 'DELAY', 'WRONG_DELIVERY', 'OTHER')),
    CONSTRAINT ck_freight_claim_status CHECK (status IN (
        'OPEN', 'UNDER_REVIEW', 'ACCEPTED', 'PARTIALLY_ACCEPTED', 'REJECTED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_freight_claim_resolution CHECK (
        resolution IS NULL OR resolution IN ('ACCEPTED', 'PARTIALLY_ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_freight_claim_recovery_method CHECK (
        recovery_method IS NULL OR recovery_method IN ('PENALTY', 'CREDIT_NOTE')),
    CONSTRAINT ck_freight_claim_description CHECK (
        btrim(description) <> '' AND length(description) <= 2000),
    CONSTRAINT ck_freight_claim_claimed_amount CHECK (claimed_amount > 0),
    CONSTRAINT ck_freight_claim_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_freight_claim_text_lengths CHECK (
        (decision_reason IS NULL OR (btrim(decision_reason) <> '' AND length(decision_reason) <= 500))
        AND (close_notes IS NULL OR length(close_notes) <= 500)
        AND (cancellation_reason IS NULL OR (btrim(cancellation_reason) <> '' AND length(cancellation_reason) <= 500))),

    -- Undecided, decided, or closed after a decision: the decision columns move together, and
    -- status names the decision until the claim is closed.
    CONSTRAINT ck_freight_claim_lifecycle CHECK (
        (status IN ('OPEN', 'UNDER_REVIEW', 'CANCELLED')
            AND resolution IS NULL AND accepted_amount IS NULL AND recovery_method IS NULL
            AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('ACCEPTED', 'PARTIALLY_ACCEPTED', 'REJECTED')
            AND resolution = status AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
        OR (status = 'CLOSED'
            AND resolution IS NOT NULL AND decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    -- What each decision means. Accepting money is four-eyes: whoever opened the claim cannot
    -- accept it, in whole or in part - the rule settlement applies to approving an invoice (V46)
    -- and confirming a payment (V67), held here in the database as there. Rejecting moves
    -- nothing, and says why.
    CONSTRAINT ck_freight_claim_resolution_terms CHECK (
        resolution IS NULL
        OR (resolution = 'ACCEPTED' AND accepted_amount = claimed_amount
            AND recovery_method IS NOT NULL AND decided_by <> opened_by)
        OR (resolution = 'PARTIALLY_ACCEPTED' AND accepted_amount > 0 AND accepted_amount < claimed_amount
            AND recovery_method IS NOT NULL AND decided_by <> opened_by AND decision_reason IS NOT NULL)
        OR (resolution = 'REJECTED' AND accepted_amount IS NULL AND recovery_method IS NULL
            AND decision_reason IS NOT NULL)),
    -- Closed says who and when; an accepted claim is closed by the document that recovered it,
    -- a rejected one by nothing.
    CONSTRAINT ck_freight_claim_closure CHECK (
        ((status = 'CLOSED') = (closed_at IS NOT NULL))
        AND ((closed_at IS NULL) = (closed_by IS NULL))
        AND (close_notes IS NULL OR status = 'CLOSED')
        AND (settlement_invoice_id IS NULL OR status = 'CLOSED')
        AND (status <> 'CLOSED'
             OR (resolution = 'REJECTED' AND settlement_invoice_id IS NULL)
             OR (resolution IN ('ACCEPTED', 'PARTIALLY_ACCEPTED') AND settlement_invoice_id IS NOT NULL))),
    CONSTRAINT ck_freight_claim_cancellation CHECK (
        (status = 'CANCELLED') = (cancellation_reason IS NOT NULL)),
    CONSTRAINT ck_freight_claim_submission CHECK ((submitted_at IS NULL) = (submitted_by IS NULL))
);

-- The claims list, newest first, by company and status; and the two narrower readers.
CREATE INDEX ix_freight_claim_company_status ON tms.freight_claim (company_id, status, opened_at DESC);
CREATE INDEX ix_freight_claim_company_carrier ON tms.freight_claim (company_id, carrier_id, opened_at DESC);
CREATE INDEX ix_freight_claim_company_trip ON tms.freight_claim (company_id, trip_id);

COMMENT ON TABLE tms.freight_claim IS
    'A claim against the carrier of a trip for damage, shortage, loss, delay or a wrong delivery '
    '(V71). OPEN -> UNDER_REVIEW -> ACCEPTED / PARTIALLY_ACCEPTED / REJECTED -> CLOSED, or CANCELLED. '
    'Accepting is four-eyes. Recovered as a PENALTY line on the carrier''s invoice or a CREDIT_NOTE '
    'the carrier sends; TMS moves no money (D-01).';
COMMENT ON COLUMN tms.freight_claim.settlement_invoice_id IS
    'The carrier document that recovered an accepted claim: the invoice with the PENALTY line that '
    'references it (penalty_reference_type FREIGHT_CLAIM), or the carrier''s CREDIT_NOTE.';

-- ---------------------------------------------------------------------------
-- 4. What a claim is made of
-- ---------------------------------------------------------------------------
CREATE TABLE tms.freight_claim_line (
    id                   uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id           uuid          NOT NULL,
    freight_claim_id     uuid          NOT NULL,
    line_number          integer       NOT NULL,
    -- The order line the line is about, when there is one. order_id is the claim's own order and
    -- the composite FK proves the line belongs to it - same company, right goods.
    order_id             uuid,
    order_line_id        uuid,
    description          text          NOT NULL,
    quantity             numeric(12,3),
    uom                  text,
    amount               numeric(14,2) NOT NULL,
    created_at           timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_freight_claim_line PRIMARY KEY (id),
    CONSTRAINT uq_freight_claim_line_number UNIQUE (freight_claim_id, line_number),

    CONSTRAINT fk_freight_claim_line_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_claim_line_claim FOREIGN KEY (freight_claim_id, company_id)
        REFERENCES tms.freight_claim (id, company_id) ON DELETE CASCADE,
    CONSTRAINT fk_freight_claim_line_order FOREIGN KEY (order_id, company_id)
        REFERENCES tms.transport_order (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_claim_line_order_line FOREIGN KEY (order_line_id, order_id)
        REFERENCES tms.transport_order_line (id, order_id) ON DELETE RESTRICT,

    CONSTRAINT ck_freight_claim_line_number CHECK (line_number > 0),
    CONSTRAINT ck_freight_claim_line_order_line CHECK (order_line_id IS NULL OR order_id IS NOT NULL),
    CONSTRAINT ck_freight_claim_line_description CHECK (
        btrim(description) <> '' AND length(description) <= 500),
    CONSTRAINT ck_freight_claim_line_quantity CHECK (quantity IS NULL OR quantity > 0),
    CONSTRAINT ck_freight_claim_line_uom CHECK (
        (uom IS NULL OR (btrim(uom) <> '' AND length(uom) <= 20)) AND (uom IS NULL OR quantity IS NOT NULL)),
    CONSTRAINT ck_freight_claim_line_amount CHECK (amount >= 0)
);

CREATE INDEX ix_freight_claim_line_claim ON tms.freight_claim_line (freight_claim_id, line_number);

COMMENT ON TABLE tms.freight_claim_line IS
    'What a freight claim is made of (V71). When a claim has lines, its claimed amount is their sum. '
    'Rewritten whole while the claim is OPEN; frozen from submission on.';

-- ---------------------------------------------------------------------------
-- 5. The evidence behind a claim - append-only
-- ---------------------------------------------------------------------------
-- Metadata only, the shape of tms.delivery_evidence (V28): the bytes live behind
-- EvidenceStoragePort, never in a column and never behind a public URL (ADR-006, ADR-020).
CREATE TABLE tms.freight_claim_evidence (
    id                   uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id           uuid          NOT NULL,
    freight_claim_id     uuid          NOT NULL,
    storage_key          text          NOT NULL,
    content_type         text          NOT NULL,
    size_bytes           bigint        NOT NULL,
    checksum_sha256      text          NOT NULL,
    original_filename    text,
    description          text,
    uploaded_by          uuid          NOT NULL,
    created_at           timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_freight_claim_evidence PRIMARY KEY (id),
    CONSTRAINT uq_freight_claim_evidence_key UNIQUE (storage_key),

    CONSTRAINT fk_freight_claim_evidence_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_claim_evidence_claim FOREIGN KEY (freight_claim_id, company_id)
        REFERENCES tms.freight_claim (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_freight_claim_evidence_uploaded_by FOREIGN KEY (uploaded_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,

    CONSTRAINT ck_freight_claim_evidence_size CHECK (size_bytes > 0),
    CONSTRAINT ck_freight_claim_evidence_checksum CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_freight_claim_evidence_filename CHECK (
        original_filename IS NULL OR length(original_filename) <= 255),
    CONSTRAINT ck_freight_claim_evidence_description CHECK (
        description IS NULL OR (btrim(description) <> '' AND length(description) <= 500))
);

CREATE INDEX ix_freight_claim_evidence_claim ON tms.freight_claim_evidence (freight_claim_id, created_at);

COMMENT ON TABLE tms.freight_claim_evidence IS
    'Photos and documents behind a freight claim (V71). Metadata only; the bytes are behind '
    'EvidenceStoragePort. Append-only by grant: a wrong photo is answered by uploading the right one.';

-- ---------------------------------------------------------------------------
-- 6. A penalty line can name the claim it recovers
-- ---------------------------------------------------------------------------
-- V66 listed what a penalty line deducts for; a freight claim is the fifth, and the reference id
-- is the claim's. The whole list, restated.
ALTER TABLE tms.carrier_invoice_line DROP CONSTRAINT ck_carrier_invoice_line_penalty_reference_type;
ALTER TABLE tms.carrier_invoice_line ADD CONSTRAINT ck_carrier_invoice_line_penalty_reference_type CHECK (
    penalty_reference_type IN ('TRIP_EXCEPTION', 'DELIVERY_FAILED', 'LATE_ARRIVAL', 'OTHER',
                               -- V71.
                               'FREIGHT_CLAIM'));

-- What "has this claim already been applied to an invoice?" reads: a handful of rows at most.
CREATE INDEX ix_carrier_invoice_line_penalty_claim
    ON tms.carrier_invoice_line (penalty_reference_id)
    WHERE penalty_reference_type = 'FREIGHT_CLAIM';

-- ---------------------------------------------------------------------------
-- 7. Tenant isolation (ADR-005) and least privilege (V50)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.freight_claim ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.freight_claim_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.freight_claim_evidence ENABLE ROW LEVEL SECURITY;

-- V13's default privileges attach all four verbs at CREATE TABLE; the REVOKEs are what withhold.
-- A claim is cancelled or closed, never deleted.
GRANT SELECT, INSERT, UPDATE ON tms.freight_claim TO tms_app;
REVOKE DELETE ON tms.freight_claim FROM tms_app;
-- Lines are rewritten whole while the claim is OPEN, so they take the full grant.
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.freight_claim_line TO tms_app;
-- Evidence is never edited or removed.
GRANT SELECT, INSERT ON tms.freight_claim_evidence TO tms_app;
REVOKE UPDATE, DELETE ON tms.freight_claim_evidence FROM tms_app;

CREATE POLICY p_tenant_company_scope ON tms.freight_claim
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.freight_claim_line
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.freight_claim_evidence
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 8. The carrier boundary (ADR-018, V57): a claim is the shipper's internal file
-- ---------------------------------------------------------------------------
-- RESTRICTIVE, so ANDed with p_tenant_company_scope. The carrier portal does not see claims - what
-- the shipper is asking for, and the evidence it is building, is not shared with the carrier until
-- somebody decides to - so a carrier transaction sees none of the three tables. An office
-- transaction (NULL tms.current_carrier_id()) is left alone.
DO $$
DECLARE
    internal text;
BEGIN
    FOREACH internal IN ARRAY ARRAY['freight_claim', 'freight_claim_line', 'freight_claim_evidence']
    LOOP
        EXECUTE format(
            'CREATE POLICY p_carrier_scope ON tms.%I AS RESTRICTIVE FOR ALL TO tms_app
                 USING (tms.current_carrier_id() IS NULL)
                 WITH CHECK (tms.current_carrier_id() IS NULL)', internal);
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------------
-- 9. Permissions (V46 shape)
-- ---------------------------------------------------------------------------
INSERT INTO tms.permission (resource, action, description) VALUES
    ('settlement.claim', 'read',   'View freight claims, their evidence and the incident centre'),
    ('settlement.claim', 'manage', 'Open, edit, submit for review, cancel and close a freight claim'),
    ('settlement.claim', 'decide', 'Accept, partially accept or reject a freight claim somebody else opened');

-- Reading is for the four office roles. Opening a claim is a planner's as much as an
-- administrator's - the dispatcher is who knows the chairs arrived broken. Deciding what the
-- carrier owes is an administrator's, the line V46 draws for approving an invoice; and four eyes
-- are enforced on the people, not on the roles.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN', 'PLANNER', 'VIEWER')
  AND p.code = 'settlement.claim:read';

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN', 'PLANNER')
  AND p.code = 'settlement.claim:manage';

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code = 'settlement.claim:decide';

-- ---------------------------------------------------------------------------
-- 10. Audit vocabulary (the whole list, restated)
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
    'COST_ALLOCATED',
    -- V71 (freight claims).
    'CLAIM_OPENED', 'CLAIM_UPDATED', 'CLAIM_SUBMITTED', 'CLAIM_ACCEPTED', 'CLAIM_PARTIALLY_ACCEPTED',
    'CLAIM_REJECTED', 'CLAIM_CLOSED', 'CLAIM_CANCELLED', 'CLAIM_EVIDENCE_ADDED'));

-- Generated from com.ebim.tms.shared.audit.AuditAggregateType (V48's rule), plus FREIGHT_CLAIM.
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_aggregate_type;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_aggregate_type CHECK (aggregate_type IN (
    'APPOINTMENT',
    'APP_USER',
    'CARRIER',
    'CARRIER_INVOICE',
    'COMPANY',
    'DRIVER',
    'FREIGHT_ACCRUAL',
    'FREIGHT_CLAIM',
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
