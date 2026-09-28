-- ============================================================================
-- V55 - Logistics documents beside the order (ADR-015)
-- ============================================================================
-- An order travels with paperwork: invoice, delivery note, GRE remitente, GRE transportista. This
-- records the documents an ERP issued and which orders each covers, many to many. It does not
-- replace order_delivery - physical fulfilment stays derived from it (ADR-009) - and nothing here
-- moves an order, a trip or a delivery. Document delivery tracking is ADR-015 section 5: proposed,
-- not implemented, because it needs legal and fiscal decisions.
-- ============================================================================

CREATE TABLE tms.logistics_document (
    id                      uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id              uuid          NOT NULL,
    -- The ERP namespace the document comes from, same rules as transport_order.external_source.
    source_system           text          NOT NULL,
    document_type           text          NOT NULL,
    -- As issued (series-correlative, e.g. F001-00001234 or T001-0000045). Never parsed.
    document_number         text          NOT NULL,
    issue_date              date,
    issuer_tax_id           text,
    recipient_tax_id        text,
    recipient_name          text,
    -- Informational only: TMS does not bill.
    amount                  numeric(14,2),
    currency                text,
    -- The ERP's own reading (ISSUED, VOIDED, ...), stored for reference and never interpreted.
    external_status         text,
    integration_client_id   uuid,
    received_at             timestamptz   NOT NULL DEFAULT now(),
    version                 bigint        NOT NULL DEFAULT 0,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_logistics_document PRIMARY KEY (id),
    CONSTRAINT uq_logistics_document_id_company UNIQUE (id, company_id),
    CONSTRAINT fk_logistics_document_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_logistics_document_client_company FOREIGN KEY (integration_client_id, company_id)
        REFERENCES tms.integration_client (id, company_id),
    CONSTRAINT uq_logistics_document_identity
        UNIQUE (company_id, source_system, document_type, document_number),
    CONSTRAINT ck_logistics_document_source_system CHECK (source_system ~ '^[A-Z0-9_]{2,40}$'),
    CONSTRAINT ck_logistics_document_type CHECK (document_type IN (
        'INVOICE', 'DELIVERY_NOTE', 'GRE_REMITENTE', 'GRE_TRANSPORTISTA', 'OTHER')),
    CONSTRAINT ck_logistics_document_number CHECK (
        btrim(document_number) <> '' AND length(document_number) <= 60),
    CONSTRAINT ck_logistics_document_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_logistics_document_amount_pair CHECK ((amount IS NULL) = (currency IS NULL)),
    CONSTRAINT ck_logistics_document_amount_nonnegative CHECK (amount IS NULL OR amount >= 0),
    CONSTRAINT ck_logistics_document_external_status CHECK (
        external_status IS NULL OR length(external_status) <= 40),
    CONSTRAINT ck_logistics_document_texts CHECK (
        coalesce(length(issuer_tax_id), 0) <= 20 AND coalesce(length(recipient_tax_id), 0) <= 20
        AND coalesce(length(recipient_name), 0) <= 200)
);

CREATE TRIGGER tr_logistics_document_set_updated_at
    BEFORE UPDATE ON tms.logistics_document
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

CREATE TABLE tms.logistics_document_order (
    document_id     uuid          NOT NULL,
    order_id        uuid          NOT NULL,
    company_id      uuid          NOT NULL,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_logistics_document_order PRIMARY KEY (document_id, order_id),
    CONSTRAINT fk_logistics_document_order_document FOREIGN KEY (document_id, company_id)
        REFERENCES tms.logistics_document (id, company_id) ON DELETE CASCADE,
    CONSTRAINT fk_logistics_document_order_order FOREIGN KEY (order_id, company_id)
        REFERENCES tms.transport_order (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_logistics_document_order_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT
);

CREATE INDEX ix_logistics_document_order_order ON tms.logistics_document_order (order_id);

COMMENT ON TABLE tms.logistics_document IS
    'A document an ERP issued for one or more orders (ADR-015): invoice, delivery note, GRE. Linked '
    'to orders many to many through logistics_document_order. Informational: it never moves an '
    'order, a trip or a delivery.';

-- Tenant isolation (ADR-005) and least privilege (V50). The link table is rewritten on a re-send,
-- so it keeps DELETE; a document itself is never deleted.
ALTER TABLE tms.logistics_document ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.logistics_document_order ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE ON tms.logistics_document TO tms_app;
REVOKE DELETE ON tms.logistics_document FROM tms_app;
GRANT SELECT, INSERT, DELETE ON tms.logistics_document_order TO tms_app;
REVOKE UPDATE ON tms.logistics_document_order FROM tms_app;
CREATE POLICY p_tenant_company_scope ON tms.logistics_document
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.logistics_document_order
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

ALTER TABLE tms.integration_client_scope DROP CONSTRAINT ck_integration_client_scope_value;
ALTER TABLE tms.integration_client_scope ADD CONSTRAINT ck_integration_client_scope_value CHECK (
    scope IN ('integration.location:write', 'integration.order:write', 'integration.shipment:read',
              'integration.tracking:write', 'integration.tender:respond',
              'integration.dispatch:write', 'integration.warehouse-milestone:write',
              -- V55 (ADR-015).
              'integration.document:write'));
