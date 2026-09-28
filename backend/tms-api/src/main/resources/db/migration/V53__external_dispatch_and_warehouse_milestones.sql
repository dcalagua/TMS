-- ============================================================================
-- V53 - A warehouse system's dispatch and milestones (ADR-013, WAREHOUSE_EXECUTION_V1 §4)
-- ============================================================================
-- 1. tms.external_dispatch: one row per dispatch document (an EWM SLS) and revision, stored whole
--    (raw body, hash) whatever TMS then does with it. Keyed by the document, not by the trip, so a
--    trip can hold N documents later; contract v1 promises 1 trip = 1 active load = 1 document.
-- 2. tms.external_dispatch_order: the per-order summary the reconciliation reads. No table per
--    SKU, lot, serial or handling unit: that detail stays in the raw document.
-- 3. tms.warehouse_milestone: LOADING_STARTED / LOAD_READY / LOAD_CANCELLED as received, keyed by
--    the sender's eventId for idempotency. The trip's timeline gets a transport_event beside it.
-- 4. The two integration scopes, and the four warehouse transport_event types.
--
-- The plan is never rewritten by any of this: no assignment, quantity, vehicle or driver on the
-- trip changes. Variance is derived when read, never stored. No foreign key reaches any table of
-- the warehouse system; the only shared values are business references.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. tms.external_dispatch
-- ---------------------------------------------------------------------------
CREATE TABLE tms.external_dispatch (
    id                          uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id                  uuid          NOT NULL,
    -- Null when transportReference matched no trip of the company (RECORDED_UNMATCHED).
    trip_id                     uuid,
    integration_client_id       uuid          NOT NULL,
    -- The inbox row is written after the work completes; these two are how the document and its
    -- tms.integration_request row are found from each other.
    idempotency_key             text,
    correlation_id              text,
    -- The PRODUCER of the dispatch (EWM by EBIM: EWM_EBIM), not an ERP namespace.
    source_system               text          NOT NULL,
    dispatch_reference          text          NOT NULL,
    revision                    integer       NOT NULL,
    transport_reference         text          NOT NULL,
    load_reference              text,
    warehouse_code              text,
    actual_dispatch_at          timestamptz   NOT NULL,
    carrier_code                text,
    carrier_name                text,
    vehicle_license_plate       text,
    vehicle_type                text,
    driver_name                 text,
    driver_document_number      text,
    seal_number                 text,
    transport_document_number   text,
    total_handling_units        integer,
    total_weight_kg             numeric(14,3),
    total_volume_m3             numeric(14,4),
    outcome                     text          NOT NULL,
    verification_status         text          NOT NULL,
    -- [{code, severity, orderReference?, lineNumber?, planned?, dispatched?, uom?, detail?}] as JSON
    -- text, written whole and read whole - the reason V18 and V35 chose text over jsonb.
    discrepancies               text          NOT NULL DEFAULT '[]',
    payload_hash                text          NOT NULL,
    raw_payload                 text          NOT NULL,
    received_at                 timestamptz   NOT NULL DEFAULT now(),
    superseded_at               timestamptz,
    superseded_by               uuid,
    version                     bigint        NOT NULL DEFAULT 0,
    created_at                  timestamptz   NOT NULL DEFAULT now(),
    updated_at                  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_external_dispatch PRIMARY KEY (id),
    CONSTRAINT uq_external_dispatch_id_company UNIQUE (id, company_id),
    CONSTRAINT fk_external_dispatch_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_external_dispatch_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),
    CONSTRAINT fk_external_dispatch_client FOREIGN KEY (integration_client_id)
        REFERENCES tms.integration_client (id) ON DELETE RESTRICT,
    CONSTRAINT fk_external_dispatch_client_company FOREIGN KEY (integration_client_id, company_id)
        REFERENCES tms.integration_client (id, company_id),
    CONSTRAINT fk_external_dispatch_superseded_by FOREIGN KEY (superseded_by, company_id)
        REFERENCES tms.external_dispatch (id, company_id),
    -- Business idempotency (ADR-013 section 6): one row per document revision.
    CONSTRAINT uq_external_dispatch_revision
        UNIQUE (company_id, source_system, dispatch_reference, revision),
    CONSTRAINT ck_external_dispatch_source_system CHECK (source_system ~ '^[A-Z0-9_]{2,40}$'),
    CONSTRAINT ck_external_dispatch_reference_not_blank CHECK (
        btrim(dispatch_reference) <> '' AND length(dispatch_reference) <= 80),
    CONSTRAINT ck_external_dispatch_transport_reference_not_blank CHECK (
        btrim(transport_reference) <> '' AND length(transport_reference) <= 80),
    CONSTRAINT ck_external_dispatch_revision_positive CHECK (revision >= 1),
    CONSTRAINT ck_external_dispatch_outcome CHECK (outcome IN (
        'APPLIED', 'RECONCILED', 'UNAPPLIED', 'RECORDED_UNMATCHED')),
    CONSTRAINT ck_external_dispatch_verification CHECK (verification_status IN (
        'UNVERIFIED', 'MATCHED', 'MISMATCH', 'OVERRIDDEN')),
    -- An unmatched document has no trip, and a matched one always does.
    CONSTRAINT ck_external_dispatch_unmatched_has_no_trip CHECK (
        (outcome = 'RECORDED_UNMATCHED') = (trip_id IS NULL)),
    -- A successor implies a supersession. Not a biconditional: within the one transaction that
    -- receives a correction, the old revision stops being current before the new row exists.
    CONSTRAINT ck_external_dispatch_superseded_by_requires_at CHECK (
        superseded_by IS NULL OR superseded_at IS NOT NULL),
    CONSTRAINT ck_external_dispatch_payload_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    -- 2 MB is the endpoint's body limit; the column refuses anything the endpoint would not take.
    CONSTRAINT ck_external_dispatch_raw_payload_size CHECK (octet_length(raw_payload) <= 2097152),
    CONSTRAINT ck_external_dispatch_discrepancies_array CHECK (jsonb_typeof(discrepancies::jsonb) = 'array'),
    CONSTRAINT ck_external_dispatch_totals_nonnegative CHECK (
        coalesce(total_handling_units, 0) >= 0 AND coalesce(total_weight_kg, 0) >= 0
        AND coalesce(total_volume_m3, 0) >= 0)
);

-- At most one current (not superseded) revision per document.
CREATE UNIQUE INDEX uq_external_dispatch_current
    ON tms.external_dispatch (company_id, source_system, dispatch_reference)
    WHERE superseded_at IS NULL;
CREATE INDEX ix_external_dispatch_trip ON tms.external_dispatch (trip_id) WHERE trip_id IS NOT NULL;
CREATE INDEX ix_external_dispatch_company_received ON tms.external_dispatch (company_id, received_at DESC);
CREATE INDEX ix_external_dispatch_transport_reference
    ON tms.external_dispatch (company_id, transport_reference);

CREATE TRIGGER tr_external_dispatch_set_updated_at
    BEFORE UPDATE ON tms.external_dispatch
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

COMMENT ON TABLE tms.external_dispatch IS
    'A warehouse system''s dispatch document (ADR-013): what physically left, stored whole and '
    'reconciled against the plan without overwriting it. One row per (source_system, '
    'dispatch_reference, revision); a higher revision supersedes the current one.';
COMMENT ON COLUMN tms.external_dispatch.source_system IS
    'The producer of the dispatch (EWM by EBIM publishes EWM_EBIM). Not the ERP namespace of the '
    'orders, which is external_dispatch_order.external_source.';
COMMENT ON COLUMN tms.external_dispatch.outcome IS
    'APPLIED: this document dispatched the trip. RECONCILED: compared only (trip already departed, '
    'or MANUAL mode). UNAPPLIED: it would dispatch, but a database invariant or the trip''s state '
    'needs a person. RECORDED_UNMATCHED: transportReference unknown in this company.';
COMMENT ON COLUMN tms.external_dispatch.raw_payload IS
    'The request body exactly as received (not re-serialised), up to 2 MB. SKU, lot, serial and '
    'handling-unit detail live here and nowhere else in TMS.';

-- ---------------------------------------------------------------------------
-- 2. tms.external_dispatch_order
-- ---------------------------------------------------------------------------
CREATE TABLE tms.external_dispatch_order (
    id                      uuid          NOT NULL DEFAULT gen_random_uuid(),
    external_dispatch_id    uuid          NOT NULL,
    company_id              uuid          NOT NULL,
    -- Null when (external_source, external_reference) matched no order of the company.
    order_id                uuid,
    -- The ERP namespace and key, verbatim; never normalised into a TMS value.
    external_source         text          NOT NULL,
    external_reference      text          NOT NULL,
    warehouse_order_number  text,
    handling_units          integer,
    weight_kg               numeric(14,3),
    volume_m3               numeric(14,4),
    -- The warehouse's own reading (SHIPPED, PARTIALLY_SHIPPED), stored for reference.
    status                  text,
    match_result            text          NOT NULL,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_external_dispatch_order PRIMARY KEY (id),
    CONSTRAINT fk_external_dispatch_order_dispatch FOREIGN KEY (external_dispatch_id, company_id)
        REFERENCES tms.external_dispatch (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_external_dispatch_order_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_external_dispatch_order_order FOREIGN KEY (order_id, company_id)
        REFERENCES tms.transport_order (id, company_id),
    CONSTRAINT uq_external_dispatch_order_key
        UNIQUE (external_dispatch_id, external_source, external_reference),
    CONSTRAINT ck_external_dispatch_order_source CHECK (external_source ~ '^[A-Z0-9_]{2,40}$'),
    CONSTRAINT ck_external_dispatch_order_reference CHECK (
        btrim(external_reference) <> '' AND length(external_reference) <= 120),
    CONSTRAINT ck_external_dispatch_order_status_length CHECK (status IS NULL OR length(status) <= 40),
    CONSTRAINT ck_external_dispatch_order_amounts CHECK (
        coalesce(handling_units, 0) >= 0 AND coalesce(weight_kg, 0) >= 0 AND coalesce(volume_m3, 0) >= 0),
    -- MATCHED: on the trip and agrees. VARIANCE: on the trip, quantities differ. UNCOMPARABLE: on
    -- the trip, quantities cannot be compared. EXTRA: a known order that is not on the trip.
    -- UNKNOWN: no order of the company has this key.
    CONSTRAINT ck_external_dispatch_order_match CHECK (match_result IN (
        'MATCHED', 'VARIANCE', 'UNCOMPARABLE', 'EXTRA', 'UNKNOWN')),
    CONSTRAINT ck_external_dispatch_order_unknown_has_no_order CHECK (
        (match_result = 'UNKNOWN') = (order_id IS NULL))
);

CREATE INDEX ix_external_dispatch_order_dispatch ON tms.external_dispatch_order (external_dispatch_id);
CREATE INDEX ix_external_dispatch_order_order ON tms.external_dispatch_order (order_id) WHERE order_id IS NOT NULL;

COMMENT ON TABLE tms.external_dispatch_order IS
    'The per-order summary of an external dispatch document, for reconciliation and the trip '
    'workspace. Written once with its document and never updated.';

-- ---------------------------------------------------------------------------
-- 3. tms.warehouse_milestone
-- ---------------------------------------------------------------------------
CREATE TABLE tms.warehouse_milestone (
    id                      uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id              uuid          NOT NULL,
    integration_client_id   uuid          NOT NULL,
    source_system           text          NOT NULL,
    event_id                text          NOT NULL,
    milestone_type          text          NOT NULL,
    transport_reference     text          NOT NULL,
    -- Null when transportReference matched no trip; the milestone is still kept.
    trip_id                 uuid,
    load_reference          text,
    warehouse_code          text,
    occurred_at             timestamptz   NOT NULL,
    received_at             timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_warehouse_milestone PRIMARY KEY (id),
    CONSTRAINT fk_warehouse_milestone_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_warehouse_milestone_client_company FOREIGN KEY (integration_client_id, company_id)
        REFERENCES tms.integration_client (id, company_id),
    CONSTRAINT fk_warehouse_milestone_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),
    -- Idempotency by the sender's event id (WAREHOUSE_EXECUTION_V1 §4.2).
    CONSTRAINT uq_warehouse_milestone_event UNIQUE (company_id, source_system, event_id),
    CONSTRAINT ck_warehouse_milestone_type CHECK (milestone_type IN (
        'LOADING_STARTED', 'LOAD_READY', 'LOAD_CANCELLED')),
    CONSTRAINT ck_warehouse_milestone_source_system CHECK (source_system ~ '^[A-Z0-9_]{2,40}$'),
    CONSTRAINT ck_warehouse_milestone_event_id CHECK (btrim(event_id) <> '' AND length(event_id) <= 128),
    CONSTRAINT ck_warehouse_milestone_transport_reference CHECK (
        btrim(transport_reference) <> '' AND length(transport_reference) <= 80)
);

CREATE INDEX ix_warehouse_milestone_trip ON tms.warehouse_milestone (trip_id, occurred_at)
    WHERE trip_id IS NOT NULL;

COMMENT ON TABLE tms.warehouse_milestone IS
    'A warehouse milestone as received (ADR-013 section 6). Informative: it never moves a trip, an '
    'order or any other lifecycle (ADR-007''s rule applied to the warehouse).';

-- ---------------------------------------------------------------------------
-- 4. Tenant isolation (ADR-005) and least privilege (V50)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.external_dispatch ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.external_dispatch_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.warehouse_milestone ENABLE ROW LEVEL SECURITY;

-- A received document is a record of a fact: never deleted. UPDATE only to supersede it.
-- V13's default privileges attach all four verbs at CREATE TABLE; the REVOKE is what withholds.
GRANT SELECT, INSERT, UPDATE ON tms.external_dispatch TO tms_app;
REVOKE DELETE ON tms.external_dispatch FROM tms_app;
GRANT SELECT, INSERT ON tms.external_dispatch_order TO tms_app;
REVOKE UPDATE, DELETE ON tms.external_dispatch_order FROM tms_app;
GRANT SELECT, INSERT ON tms.warehouse_milestone TO tms_app;
REVOKE UPDATE, DELETE ON tms.warehouse_milestone FROM tms_app;

CREATE POLICY p_tenant_company_scope ON tms.external_dispatch
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.external_dispatch_order
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.warehouse_milestone
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 5. Scopes and timeline types
-- ---------------------------------------------------------------------------
ALTER TABLE tms.integration_client_scope DROP CONSTRAINT ck_integration_client_scope_value;
ALTER TABLE tms.integration_client_scope ADD CONSTRAINT ck_integration_client_scope_value CHECK (
    scope IN ('integration.location:write', 'integration.order:write', 'integration.shipment:read',
              'integration.tracking:write', 'integration.tender:respond',
              -- V53 (WAREHOUSE_EXECUTION_V1 §4).
              'integration.dispatch:write', 'integration.warehouse-milestone:write'));

ALTER TABLE tms.transport_event DROP CONSTRAINT ck_transport_event_type;
ALTER TABLE tms.transport_event ADD CONSTRAINT ck_transport_event_type CHECK (event_type IN (
    'TRIP_CONFIRMED', 'TRIP_READY', 'TRIP_DISPATCHED', 'TRIP_COMPLETED', 'TRIP_CANCELLED',
    'ARRIVED_AT_STOP', 'SERVICE_STARTED', 'STOP_COMPLETED', 'STOP_SKIPPED', 'STOP_FAILED',
    'DELIVERY_RECORDED',
    'TENDER_SENT', 'TENDER_ACCEPTED', 'TENDER_REJECTED', 'TENDER_EXPIRED', 'TENDER_CANCELLED',
    'EXCEPTION_REPORTED', 'EXCEPTION_RESOLVED',
    -- V53: what the warehouse reported. Informative; none moves a lifecycle.
    'WAREHOUSE_LOADING_STARTED', 'WAREHOUSE_LOAD_READY', 'WAREHOUSE_LOAD_CANCELLED',
    'WAREHOUSE_DISPATCH_CONFIRMED'));

ALTER TABLE tms.transport_event DROP CONSTRAINT ck_transport_event_stop_scope;
ALTER TABLE tms.transport_event ADD CONSTRAINT ck_transport_event_stop_scope CHECK (
    CASE
        WHEN event_type IN ('ARRIVED_AT_STOP', 'SERVICE_STARTED', 'STOP_COMPLETED',
                            'STOP_SKIPPED', 'STOP_FAILED', 'DELIVERY_RECORDED')
            THEN trip_stop_id IS NOT NULL
        WHEN event_type IN ('TRIP_CONFIRMED', 'TRIP_READY', 'TRIP_DISPATCHED', 'TRIP_COMPLETED',
                            'TRIP_CANCELLED',
                            'TENDER_SENT', 'TENDER_ACCEPTED', 'TENDER_REJECTED', 'TENDER_EXPIRED',
                            'TENDER_CANCELLED',
                            'WAREHOUSE_LOADING_STARTED', 'WAREHOUSE_LOAD_READY',
                            'WAREHOUSE_LOAD_CANCELLED', 'WAREHOUSE_DISPATCH_CONFIRMED')
            THEN trip_stop_id IS NULL
        ELSE true
    END);
