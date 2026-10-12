-- ============================================================================
-- V57 - Carrier portal identity and document delivery tracking (ADR-018)
-- ============================================================================
-- 1. tms.membership.carrier_id: a carrier membership acts for one carrier of its company.
-- 2. Role level CARRIER and the CARRIER_OPERATOR role; four permissions.
-- 3. tms.document_delivery and its orders, events and evidence: one row per document on a trip,
--    reported by the carrier and closed by the office when the signed copy is back.
-- 4. tms.current_carrier_id() and RESTRICTIVE policies: a carrier transaction sees only the rows of
--    its own carrier's trips. Every other transaction is unaffected.
-- 5. Backfill for trips whose warehouse dispatch arrived before this migration.
--
-- Nothing here moves an order, a stop or a trip (ADR-015, ADR-018 section 4).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Carrier membership
-- ---------------------------------------------------------------------------
ALTER TABLE tms.membership ADD COLUMN carrier_id uuid;

-- The carrier belongs to the membership's company (MATCH SIMPLE: no carrier, no check).
ALTER TABLE tms.membership ADD CONSTRAINT fk_membership_carrier_company
    FOREIGN KEY (carrier_id, company_id) REFERENCES tms.carrier (id, company_id) ON DELETE RESTRICT;
-- A carrier membership is company-scoped: an organization-wide one would reach companies whose
-- carriers it does not belong to.
ALTER TABLE tms.membership ADD CONSTRAINT ck_membership_carrier_requires_company
    CHECK (carrier_id IS NULL OR company_id IS NOT NULL);

CREATE INDEX ix_membership_carrier ON tms.membership (carrier_id) WHERE carrier_id IS NOT NULL;

COMMENT ON COLUMN tms.membership.carrier_id IS
    'ADR-018: set, the membership acts for this carrier of its company, holds CARRIER roles only and '
    'sees only that carrier''s trips (Java and tms.current_carrier_id() policies). Null: ordinary.';

-- ---------------------------------------------------------------------------
-- 2. Role level, role and permissions
-- ---------------------------------------------------------------------------
ALTER TABLE tms.role DROP CONSTRAINT ck_role_scope_level;
ALTER TABLE tms.role ADD CONSTRAINT ck_role_scope_level
    CHECK (scope_level IN ('ORGANIZATION', 'COMPANY', 'CARRIER'));

INSERT INTO tms.role (code, name, description, scope_level, system_managed) VALUES
    ('CARRIER_OPERATOR', 'Carrier operator',
     'A user of a carrier company: sees the dispatched trips assigned to that carrier and reports '
     'the delivery of their documents. Nothing else.',
     'CARRIER', true);

INSERT INTO tms.permission (resource, action, description) VALUES
    ('portal.trip', 'read', 'Carrier portal: read the dispatched trips assigned to my carrier'),
    ('portal.document', 'report', 'Carrier portal: report the delivery of a document on my trip'),
    ('planning.document_delivery', 'read', 'Read the document delivery monitor'),
    ('planning.document_delivery', 'return', 'Confirm that a document''s signed copy is back at the office');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code = 'CARRIER_OPERATOR'
  AND p.code IN ('portal.trip:read', 'portal.document:report');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE p.code = 'planning.document_delivery:read'
  AND r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN', 'PLANNER', 'VIEWER');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE p.code = 'planning.document_delivery:return'
  AND r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN', 'PLANNER');

-- ---------------------------------------------------------------------------
-- 3. Document delivery tracking
-- ---------------------------------------------------------------------------
CREATE TABLE tms.document_delivery (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid          NOT NULL,
    trip_id             uuid          NOT NULL,
    document_id         uuid          NOT NULL,
    -- Snapshot of the document's identity. Both are part of ADR-015's unique key and never change,
    -- so the monitor filters and searches here without reading the orders module's tables.
    document_type       text          NOT NULL,
    document_number     text          NOT NULL,
    status              text          NOT NULL DEFAULT 'PENDING',
    reason_code         text,
    remark              text,
    -- When it happened, as the carrier reports it; recorded_at is when TMS was told.
    reported_at         timestamptz,
    recorded_at         timestamptz,
    recorded_by         uuid,
    -- The actor's e-mail at the time, as tms.transport_event keeps it.
    recorded_by_label   text,
    returned_at         timestamptz,
    returned_by         uuid,
    returned_by_label   text,
    return_remark       text,
    version             bigint        NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_document_delivery PRIMARY KEY (id),
    CONSTRAINT uq_document_delivery_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_document_delivery_trip_document UNIQUE (trip_id, document_id),
    CONSTRAINT fk_document_delivery_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_document_delivery_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),
    CONSTRAINT fk_document_delivery_document_company FOREIGN KEY (document_id, company_id)
        REFERENCES tms.logistics_document (id, company_id),
    CONSTRAINT fk_document_delivery_recorded_by FOREIGN KEY (recorded_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_document_delivery_returned_by FOREIGN KEY (returned_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_document_delivery_status CHECK (status IN (
        'PENDING', 'DELIVERED', 'REJECTED', 'NOT_DELIVERED')),
    CONSTRAINT ck_document_delivery_reason CHECK (reason_code IS NULL OR reason_code IN (
        'CUSTOMER_ABSENT', 'CUSTOMER_REFUSED', 'CUSTOMER_CLOSED', 'ADDRESS_NOT_FOUND',
        'DOCUMENT_ERROR', 'GOODS_REJECTED', 'OTHER')),
    -- A negative outcome always says why; a delivery and a pending document never carry a reason.
    CONSTRAINT ck_document_delivery_reason_by_status CHECK (
        (status IN ('REJECTED', 'NOT_DELIVERED')) = (reason_code IS NOT NULL)),
    -- PENDING is "nobody reported anything": no report fields at all.
    CONSTRAINT ck_document_delivery_pending_is_empty CHECK (
        status <> 'PENDING' OR (reported_at IS NULL AND recorded_at IS NULL AND recorded_by IS NULL
                                AND remark IS NULL)),
    CONSTRAINT ck_document_delivery_reported_complete CHECK (
        status = 'PENDING' OR (reported_at IS NOT NULL AND recorded_at IS NOT NULL)),
    -- The office closes a document that was reported, never a pending one.
    CONSTRAINT ck_document_delivery_return_requires_report CHECK (
        returned_at IS NULL OR status <> 'PENDING'),
    CONSTRAINT ck_document_delivery_return_complete CHECK (
        (returned_at IS NULL) = (returned_by IS NULL)),
    CONSTRAINT ck_document_delivery_texts CHECK (
        coalesce(length(remark), 0) <= 500 AND coalesce(length(return_remark), 0) <= 500
        AND coalesce(length(recorded_by_label), 0) <= 254 AND coalesce(length(returned_by_label), 0) <= 254
        AND length(document_number) <= 60)
);

CREATE INDEX ix_document_delivery_company_status ON tms.document_delivery (company_id, status);
CREATE INDEX ix_document_delivery_document ON tms.document_delivery (document_id);
CREATE INDEX ix_document_delivery_awaiting_return ON tms.document_delivery (company_id, recorded_at)
    WHERE status <> 'PENDING' AND returned_at IS NULL;

CREATE TRIGGER tr_document_delivery_set_updated_at
    BEFORE UPDATE ON tms.document_delivery
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

COMMENT ON TABLE tms.document_delivery IS
    'ADR-018: one logistics document on one trip, from PENDING to what the carrier reported '
    '(DELIVERED / REJECTED / NOT_DELIVERED) and to the office''s confirmation that the signed copy '
    'is back. Informative: it never moves an order, a stop or a trip.';

CREATE TABLE tms.document_delivery_order (
    document_delivery_id  uuid        NOT NULL,
    order_id              uuid        NOT NULL,
    company_id            uuid        NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_document_delivery_order PRIMARY KEY (document_delivery_id, order_id),
    CONSTRAINT fk_document_delivery_order_delivery FOREIGN KEY (document_delivery_id, company_id)
        REFERENCES tms.document_delivery (id, company_id) ON DELETE CASCADE,
    CONSTRAINT fk_document_delivery_order_order FOREIGN KEY (order_id, company_id)
        REFERENCES tms.transport_order (id, company_id) ON DELETE RESTRICT
);

CREATE INDEX ix_document_delivery_order_order ON tms.document_delivery_order (order_id);

COMMENT ON TABLE tms.document_delivery_order IS
    'The orders of the trip that a tracked document covers (ADR-018).';

CREATE TABLE tms.document_delivery_event (
    id                    uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id            uuid          NOT NULL,
    document_delivery_id  uuid          NOT NULL,
    event_type            text          NOT NULL,
    status                text,
    reason_code           text,
    remark                text,
    reported_at           timestamptz,
    actor_id              uuid          NOT NULL,
    actor_label           text,
    recorded_at           timestamptz   NOT NULL DEFAULT now(),
    -- The portal's Idempotency-Key (ADR-018 section 5). Null for office actions.
    idempotency_key       text,
    CONSTRAINT pk_document_delivery_event PRIMARY KEY (id),
    CONSTRAINT uq_document_delivery_event_id_company UNIQUE (id, company_id),
    CONSTRAINT fk_document_delivery_event_delivery FOREIGN KEY (document_delivery_id, company_id)
        REFERENCES tms.document_delivery (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_document_delivery_event_actor FOREIGN KEY (actor_id)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_document_delivery_event_type CHECK (event_type IN ('REPORTED', 'RETURNED')),
    -- The values of planning.domain.DocumentDeliveryStatus, as PersistedEnumConstraintTest requires;
    -- the next constraint is what keeps PENDING out, because nobody reports "nothing happened".
    CONSTRAINT ck_document_delivery_event_status CHECK (
        status IS NULL OR status IN ('PENDING', 'DELIVERED', 'REJECTED', 'NOT_DELIVERED')),
    CONSTRAINT ck_document_delivery_event_status_not_pending CHECK (status IS DISTINCT FROM 'PENDING'),
    CONSTRAINT ck_document_delivery_event_reason CHECK (reason_code IS NULL OR reason_code IN (
        'CUSTOMER_ABSENT', 'CUSTOMER_REFUSED', 'CUSTOMER_CLOSED', 'ADDRESS_NOT_FOUND',
        'DOCUMENT_ERROR', 'GOODS_REJECTED', 'OTHER')),
    -- A report always says what happened; a return confirmation never does.
    CONSTRAINT ck_document_delivery_event_status_by_type CHECK (
        (event_type = 'REPORTED') = (status IS NOT NULL)),
    CONSTRAINT ck_document_delivery_event_idempotency_key CHECK (
        idempotency_key IS NULL OR (btrim(idempotency_key) <> '' AND length(idempotency_key) <= 128)),
    CONSTRAINT ck_document_delivery_event_texts CHECK (
        coalesce(length(remark), 0) <= 500 AND coalesce(length(actor_label), 0) <= 254)
);

CREATE UNIQUE INDEX uq_document_delivery_event_idempotency
    ON tms.document_delivery_event (company_id, actor_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_document_delivery_event_delivery
    ON tms.document_delivery_event (document_delivery_id, recorded_at);

COMMENT ON TABLE tms.document_delivery_event IS
    'Append-only history of a tracked document (ADR-018): every report and correction by the '
    'carrier, and the office''s return confirmation.';

CREATE TABLE tms.document_delivery_evidence (
    id                    uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id            uuid        NOT NULL,
    document_delivery_id  uuid        NOT NULL,
    event_id              uuid        NOT NULL,
    evidence_type         text        NOT NULL,
    -- Same rules as tms.delivery_evidence (V28, ADR-006): an opaque key into a private store.
    storage_key           text        NOT NULL,
    content_type          text        NOT NULL,
    size_bytes            bigint      NOT NULL,
    checksum_sha256       char(64)    NOT NULL,
    original_filename     text,
    captured_at           timestamptz,
    uploaded_at           timestamptz NOT NULL DEFAULT now(),
    uploaded_by           uuid        NOT NULL,
    CONSTRAINT pk_document_delivery_evidence PRIMARY KEY (id),
    CONSTRAINT uq_document_delivery_evidence_company_key UNIQUE (company_id, storage_key),
    CONSTRAINT fk_document_delivery_evidence_delivery FOREIGN KEY (document_delivery_id, company_id)
        REFERENCES tms.document_delivery (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_document_delivery_evidence_event FOREIGN KEY (event_id, company_id)
        REFERENCES tms.document_delivery_event (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_document_delivery_evidence_uploaded_by FOREIGN KEY (uploaded_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    -- A captured signature image, not a digital signature in any legal sense (as V28 says).
    CONSTRAINT ck_document_delivery_evidence_type CHECK (evidence_type IN ('PHOTO', 'SIGNATURE')),
    CONSTRAINT ck_document_delivery_evidence_size CHECK (size_bytes > 0 AND size_bytes <= 26214400),
    CONSTRAINT ck_document_delivery_evidence_checksum CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_document_delivery_evidence_filename CHECK (
        original_filename IS NULL OR length(original_filename) <= 255)
);

CREATE INDEX ix_document_delivery_evidence_delivery ON tms.document_delivery_evidence (document_delivery_id);

COMMENT ON TABLE tms.document_delivery_evidence IS
    'Photos and signatures behind a document report (ADR-018), stored through EvidenceStoragePort '
    '(ADR-006). Never deleted.';

-- Tenant isolation (ADR-005) and least privilege (V50). V13's default privileges attach all four
-- verbs at CREATE TABLE; the REVOKEs are what withhold.
ALTER TABLE tms.document_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.document_delivery_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.document_delivery_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.document_delivery_evidence ENABLE ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE ON tms.document_delivery TO tms_app;
REVOKE DELETE ON tms.document_delivery FROM tms_app;
GRANT SELECT, INSERT ON tms.document_delivery_order TO tms_app;
REVOKE UPDATE, DELETE ON tms.document_delivery_order FROM tms_app;
GRANT SELECT, INSERT ON tms.document_delivery_event TO tms_app;
REVOKE UPDATE, DELETE ON tms.document_delivery_event FROM tms_app;
GRANT SELECT, INSERT ON tms.document_delivery_evidence TO tms_app;
REVOKE UPDATE, DELETE ON tms.document_delivery_evidence FROM tms_app;

CREATE POLICY p_tenant_company_scope ON tms.document_delivery
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.document_delivery_order
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.document_delivery_event
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.document_delivery_evidence
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 4. The carrier boundary
-- ---------------------------------------------------------------------------
CREATE FUNCTION tms.current_carrier_id() RETURNS uuid
    LANGUAGE sql
    STABLE
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT NULLIF(current_setting('tms.carrier_id', true), '')::uuid
$$;

COMMENT ON FUNCTION tms.current_carrier_id() IS
    'The carrier a carrier-portal transaction acts for, published by the backend beside '
    'tms.company_id (ADR-018). NULL for every other transaction, which the carrier policies allow.';

REVOKE ALL ON FUNCTION tms.current_carrier_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tms.current_carrier_id() TO tms_app;

-- RESTRICTIVE: ANDed with p_tenant_company_scope, so these can only narrow what a transaction sees.
-- Each child table defers to tms.trip, whose own policy is the carrier predicate, because a
-- sub-select on an RLS table is filtered by that table's policies too.
CREATE POLICY p_carrier_scope ON tms.trip AS RESTRICTIVE
    FOR ALL TO tms_app
    USING (tms.current_carrier_id() IS NULL OR carrier_id = tms.current_carrier_id())
    WITH CHECK (tms.current_carrier_id() IS NULL OR carrier_id = tms.current_carrier_id());

DO $$
DECLARE
    child text;
BEGIN
    FOREACH child IN ARRAY ARRAY['trip_stop', 'trip_order_assignment', 'external_dispatch',
                                 'document_delivery']
    LOOP
        EXECUTE format(
            'CREATE POLICY p_carrier_scope ON tms.%I AS RESTRICTIVE FOR ALL TO tms_app
                 USING (tms.current_carrier_id() IS NULL
                        OR EXISTS (SELECT 1 FROM tms.trip t WHERE t.id = trip_id))
                 WITH CHECK (tms.current_carrier_id() IS NULL
                        OR EXISTS (SELECT 1 FROM tms.trip t WHERE t.id = trip_id))', child);
    END LOOP;
END
$$;

CREATE POLICY p_carrier_scope ON tms.document_delivery_order AS RESTRICTIVE
    FOR ALL TO tms_app
    USING (tms.current_carrier_id() IS NULL
           OR EXISTS (SELECT 1 FROM tms.document_delivery d WHERE d.id = document_delivery_id))
    WITH CHECK (tms.current_carrier_id() IS NULL
           OR EXISTS (SELECT 1 FROM tms.document_delivery d WHERE d.id = document_delivery_id));
CREATE POLICY p_carrier_scope ON tms.document_delivery_event AS RESTRICTIVE
    FOR ALL TO tms_app
    USING (tms.current_carrier_id() IS NULL
           OR EXISTS (SELECT 1 FROM tms.document_delivery d WHERE d.id = document_delivery_id))
    WITH CHECK (tms.current_carrier_id() IS NULL
           OR EXISTS (SELECT 1 FROM tms.document_delivery d WHERE d.id = document_delivery_id));
CREATE POLICY p_carrier_scope ON tms.document_delivery_evidence AS RESTRICTIVE
    FOR ALL TO tms_app
    USING (tms.current_carrier_id() IS NULL
           OR EXISTS (SELECT 1 FROM tms.document_delivery d WHERE d.id = document_delivery_id))
    WITH CHECK (tms.current_carrier_id() IS NULL
           OR EXISTS (SELECT 1 FROM tms.document_delivery d WHERE d.id = document_delivery_id));

-- Orders and their documents: reachable through an active assignment on a visible trip.
CREATE POLICY p_carrier_scope ON tms.transport_order AS RESTRICTIVE
    FOR ALL TO tms_app
    USING (tms.current_carrier_id() IS NULL
           OR EXISTS (SELECT 1 FROM tms.trip_order_assignment a
                      WHERE a.order_id = transport_order.id AND a.status = 'ACTIVE'))
    WITH CHECK (tms.current_carrier_id() IS NULL
           OR EXISTS (SELECT 1 FROM tms.trip_order_assignment a
                      WHERE a.order_id = transport_order.id AND a.status = 'ACTIVE'));
CREATE POLICY p_carrier_scope ON tms.logistics_document_order AS RESTRICTIVE
    FOR ALL TO tms_app
    USING (tms.current_carrier_id() IS NULL
           OR EXISTS (SELECT 1 FROM tms.trip_order_assignment a
                      WHERE a.order_id = logistics_document_order.order_id AND a.status = 'ACTIVE'))
    WITH CHECK (tms.current_carrier_id() IS NULL
           OR EXISTS (SELECT 1 FROM tms.trip_order_assignment a
                      WHERE a.order_id = logistics_document_order.order_id AND a.status = 'ACTIVE'));
CREATE POLICY p_carrier_scope ON tms.logistics_document AS RESTRICTIVE
    FOR ALL TO tms_app
    USING (tms.current_carrier_id() IS NULL
           OR EXISTS (SELECT 1 FROM tms.logistics_document_order lo
                      WHERE lo.document_id = logistics_document.id))
    WITH CHECK (tms.current_carrier_id() IS NULL
           OR EXISTS (SELECT 1 FROM tms.logistics_document_order lo
                      WHERE lo.document_id = logistics_document.id));

-- ---------------------------------------------------------------------------
-- 5. Backfill: trips a warehouse dispatch already reached (ADR-018 section 3)
-- ---------------------------------------------------------------------------
-- Flyway runs as the schema owner, so RLS does not apply here.
INSERT INTO tms.document_delivery (company_id, trip_id, document_id, document_type, document_number)
SELECT DISTINCT t.company_id, t.id, d.id, d.document_type, d.document_number
FROM tms.trip t
JOIN tms.trip_order_assignment a
  ON a.trip_id = t.id AND a.company_id = t.company_id AND a.status = 'ACTIVE'
JOIN tms.logistics_document_order lo
  ON lo.order_id = a.order_id AND lo.company_id = t.company_id
JOIN tms.logistics_document d
  ON d.id = lo.document_id AND d.company_id = t.company_id
WHERE t.status NOT IN ('DRAFT', 'CANCELLED')
  AND EXISTS (SELECT 1 FROM tms.external_dispatch x
              WHERE x.trip_id = t.id AND x.company_id = t.company_id AND x.superseded_at IS NULL)
ON CONFLICT (trip_id, document_id) DO NOTHING;

INSERT INTO tms.document_delivery_order (document_delivery_id, order_id, company_id)
SELECT DISTINCT dd.id, a.order_id, dd.company_id
FROM tms.document_delivery dd
JOIN tms.trip_order_assignment a
  ON a.trip_id = dd.trip_id AND a.company_id = dd.company_id AND a.status = 'ACTIVE'
JOIN tms.logistics_document_order lo
  ON lo.order_id = a.order_id AND lo.document_id = dd.document_id AND lo.company_id = dd.company_id
ON CONFLICT DO NOTHING;
