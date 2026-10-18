-- ============================================================================
-- V69 - Logistics documents, international catalogue (ADR-028, amends ADR-015)
-- ============================================================================
-- V55 recorded the documents an ERP issued with a Peru-only catalogue. The first customer outside
-- Peru (PLAPASA, Ecuador) moves goods under an SRI "guia de remision" (comprobante 06), whose data
-- the operation needs to see: the clave de acceso, the transport dates, the carrier and plate, the
-- departure address and each destinatario. This migration makes the model international and is
-- strictly additive:
--
--   1. document_type gains the generic REMISSION_GUIDE. GRE_REMITENTE and GRE_TRANSPORTISTA stay as
--      Peru-specific aliases (REMISSION_GUIDE + PE + SUNAT 09 / 31) and keep working unchanged.
--   2. Nullable fiscal columns on tms.logistics_document. No existing row is touched or backfilled.
--   3. tms.logistics_document_recipient: the destinatarios of a guide, rewritten whole on a re-send.
--
-- TMS never issues, signs, sends or validates a document before a tax authority (ADR-028). The
-- CHECKs below validate SHAPE only (lengths, ISO codes, the 49-digit clave de acceso of Ecuador);
-- external_status is still stored and never interpreted.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. The catalogue: one copy of the CHECK, recreated with the generic type
-- ---------------------------------------------------------------------------
ALTER TABLE tms.logistics_document DROP CONSTRAINT ck_logistics_document_type;
ALTER TABLE tms.logistics_document ADD CONSTRAINT ck_logistics_document_type CHECK (document_type IN (
    'INVOICE', 'DELIVERY_NOTE', 'GRE_REMITENTE', 'GRE_TRANSPORTISTA', 'OTHER',
    -- V69 (ADR-028): any country's remission guide; the country and the authority's code say which.
    'REMISSION_GUIDE'));

-- ---------------------------------------------------------------------------
-- 2. Fiscal identity and transport data of the document, all optional
-- ---------------------------------------------------------------------------
ALTER TABLE tms.logistics_document
    -- ISO 3166-1 alpha-2 of the tax authority the document was issued under (EC, PE, ...).
    ADD COLUMN fiscal_country        text,
    -- The authority's own code for the document class: '06' SRI guia de remision, '01' factura;
    -- '09' SUNAT GRE remitente, '31' GRE transportista. As sent, never mapped to anything.
    ADD COLUMN fiscal_document_code  text,
    -- The authority's identifier of the authorised document: the SRI clave de acceso (49 digits),
    -- the SUNAT hash/CDR reference. Shown and copied, never verified with the authority.
    ADD COLUMN authorization_number  text,
    ADD COLUMN transport_start_date  date,
    ADD COLUMN transport_end_date    date,
    ADD COLUMN carrier_tax_id        text,
    ADD COLUMN carrier_name          text,
    ADD COLUMN vehicle_plate         text,
    ADD COLUMN origin_address        text,
    ADD COLUMN transfer_reason       text;

ALTER TABLE tms.logistics_document
    ADD CONSTRAINT ck_logistics_document_fiscal_country CHECK (
        fiscal_country IS NULL OR fiscal_country ~ '^[A-Z]{2}$'),
    ADD CONSTRAINT ck_logistics_document_fiscal_code CHECK (
        fiscal_document_code IS NULL OR fiscal_document_code ~ '^[0-9A-Z]{1,4}$'),
    ADD CONSTRAINT ck_logistics_document_authorization CHECK (
        authorization_number IS NULL OR authorization_number ~ '^\S{1,64}$'),
    -- Ecuador: the clave de acceso is exactly 49 digits.
    ADD CONSTRAINT ck_logistics_document_ec_authorization CHECK (
        fiscal_country IS DISTINCT FROM 'EC' OR authorization_number IS NULL
        OR authorization_number ~ '^[0-9]{49}$'),
    -- Ecuador: an SRI comprobante is numbered establecimiento-puntoEmision-secuencial.
    ADD CONSTRAINT ck_logistics_document_ec_number CHECK (
        fiscal_country IS DISTINCT FROM 'EC'
        OR (document_type <> 'REMISSION_GUIDE' AND fiscal_document_code IS NULL)
        OR document_number ~ '^[0-9]{3}-[0-9]{3}-[0-9]{9}$'),
    -- The GRE types are SUNAT's: an alias of REMISSION_GUIDE + PE, never another country.
    ADD CONSTRAINT ck_logistics_document_gre_is_peru CHECK (
        document_type NOT IN ('GRE_REMITENTE', 'GRE_TRANSPORTISTA')
        OR fiscal_country IS NULL OR fiscal_country = 'PE'),
    ADD CONSTRAINT ck_logistics_document_transport_dates CHECK (
        transport_start_date IS NULL OR transport_end_date IS NULL
        OR transport_end_date >= transport_start_date),
    ADD CONSTRAINT ck_logistics_document_transport_texts CHECK (
        coalesce(length(carrier_tax_id), 0) <= 20 AND coalesce(length(carrier_name), 0) <= 300
        AND coalesce(length(vehicle_plate), 0) <= 20 AND coalesce(length(origin_address), 0) <= 300
        AND coalesce(length(transfer_reason), 0) <= 300);

COMMENT ON COLUMN tms.logistics_document.fiscal_country IS
    'ISO 3166-1 alpha-2 of the issuing tax authority (ADR-028). NULL on documents received before V69.';
COMMENT ON COLUMN tms.logistics_document.authorization_number IS
    'SRI clave de acceso (49 digits) / SUNAT reference, as the ERP sent it. TMS never validates it '
    'with the authority (ADR-028).';

-- ---------------------------------------------------------------------------
-- 3. The destinatarios of a guide
-- ---------------------------------------------------------------------------
CREATE TABLE tms.logistics_document_recipient (
    id                                     uuid          NOT NULL DEFAULT gen_random_uuid(),
    document_id                            uuid          NOT NULL,
    company_id                             uuid          NOT NULL,
    -- Order as the ERP sent them, from 1.
    sequence                               smallint      NOT NULL,
    tax_id                                 text,
    name                                   text,
    destination_address                    text,
    transfer_reason                        text,
    -- SRI codEstabDestino and ruta, when the ERP sends them.
    destination_establishment_code         text,
    route                                  text,
    -- The sales document the movement is supported by (SRI codDocSustento / numDocSustento ...).
    related_document_code                  text,
    related_document_number                text,
    related_document_authorization_number  text,
    related_document_date                  date,
    -- The guide's lines for this recipient, as received: [{code, additionalCode, description,
    -- quantity, unit}]. Display-only - never reconciled against the order lines.
    items                                  jsonb         NOT NULL DEFAULT '[]'::jsonb,
    created_at                             timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_logistics_document_recipient PRIMARY KEY (id),
    CONSTRAINT uq_logistics_document_recipient_sequence UNIQUE (document_id, sequence),
    CONSTRAINT fk_logistics_document_recipient_document FOREIGN KEY (document_id, company_id)
        REFERENCES tms.logistics_document (id, company_id) ON DELETE CASCADE,
    CONSTRAINT fk_logistics_document_recipient_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT ck_logistics_document_recipient_sequence CHECK (sequence BETWEEN 1 AND 500),
    CONSTRAINT ck_logistics_document_recipient_items CHECK (
        jsonb_typeof(items) = 'array' AND jsonb_array_length(items) <= 500),
    CONSTRAINT ck_logistics_document_recipient_texts CHECK (
        coalesce(length(tax_id), 0) <= 20 AND coalesce(length(name), 0) <= 300
        AND coalesce(length(destination_address), 0) <= 300 AND coalesce(length(transfer_reason), 0) <= 300
        AND coalesce(length(destination_establishment_code), 0) <= 10 AND coalesce(length(route), 0) <= 300
        AND coalesce(length(related_document_code), 0) <= 4
        AND coalesce(length(related_document_number), 0) <= 60
        AND coalesce(length(related_document_authorization_number), 0) <= 64)
);

COMMENT ON TABLE tms.logistics_document_recipient IS
    'The destinatarios of a logistics document (ADR-028): who receives the goods, where, why, under '
    'which sales document and with which lines. Rewritten whole on a re-send; informational only.';

-- Tenant isolation (ADR-005), least privilege (V50) and the carrier boundary (V57). Rewritten whole
-- on a re-send, like logistics_document_order: INSERT and DELETE, never UPDATE.
ALTER TABLE tms.logistics_document_recipient ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, DELETE ON tms.logistics_document_recipient TO tms_app;
REVOKE UPDATE ON tms.logistics_document_recipient FROM tms_app;
CREATE POLICY p_tenant_company_scope ON tms.logistics_document_recipient
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
-- A carrier sees a guide's recipients exactly when it sees the guide (V57's policy on the parent).
CREATE POLICY p_carrier_scope ON tms.logistics_document_recipient AS RESTRICTIVE
    FOR ALL TO tms_app
    USING (tms.current_carrier_id() IS NULL
           OR EXISTS (SELECT 1 FROM tms.logistics_document d WHERE d.id = document_id))
    WITH CHECK (tms.current_carrier_id() IS NULL
           OR EXISTS (SELECT 1 FROM tms.logistics_document d WHERE d.id = document_id));
