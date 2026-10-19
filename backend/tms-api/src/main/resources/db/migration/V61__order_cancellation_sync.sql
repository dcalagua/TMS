-- ============================================================================
-- V61 - Order cancellation both ways with the warehouse (ADR-025 section 4, D-03)
-- ============================================================================
-- TMS -> warehouse: a cancelled transport order is published as ORDER_CANCELLED through a
-- resource-agnostic business outbox (tms.business_outbox_event), fanned out to webhooks exactly as
-- shipment events are (V35). The warehouse reads the order back with the new scope
-- integration.order:read.
--
-- Warehouse -> TMS: POST /integration/v1/warehouse-order-events records what the warehouse did to its
-- order (tms.warehouse_order_event) and places a hold (V54) - never a cancellation. A person decides.
--
-- No rows are seeded. No new audit action: the effect is audited with ORDER_HOLD_PLACED (V54) and
-- CANCEL (V10); the receipt itself is this table, as warehouse_milestone (V53) is.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. tms.business_outbox_event
-- ---------------------------------------------------------------------------
--
-- The outbox for facts that are not about a trip: tms.shipment_outbox_event.trip_id is NOT NULL
-- (V20), and an order (or later a carrier invoice, ADR-023) has no trip. Written in the same
-- transaction as the business change, so a rolled-back cancellation takes its event down with it.
-- Its id is the event id a webhook receiver deduplicates on.
CREATE TABLE tms.business_outbox_event (
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid        NOT NULL,
    resource_type       text        NOT NULL,
    resource_id         uuid        NOT NULL,
    -- The human-facing identifier (an order number), what the webhook carries as resource.reference.
    resource_reference  text        NOT NULL,
    event_type          text        NOT NULL,
    occurred_at         timestamptz NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_business_outbox_event PRIMARY KEY (id),
    CONSTRAINT fk_business_outbox_event_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- Single-column, so the enum guard sees the vocabulary. CARRIER_INVOICE arrives with ADR-023.
    CONSTRAINT ck_business_outbox_event_resource_type CHECK (resource_type IN ('ORDER')),
    CONSTRAINT ck_business_outbox_event_event_type CHECK (event_type IN ('ORDER_CANCELLED')),
    CONSTRAINT ck_business_outbox_event_reference CHECK (btrim(resource_reference) <> '')
);

CREATE INDEX ix_business_outbox_event_resource
    ON tms.business_outbox_event (company_id, resource_type, resource_id, occurred_at);

COMMENT ON TABLE tms.business_outbox_event IS
    'Transactional outbox for published facts that are not about a trip (V61, ADR-025 section 4): '
    'ORDER_CANCELLED today. Written with the business change; fanned out to webhook_delivery (V35). '
    'Append-only.';

-- ---------------------------------------------------------------------------
-- 2. tms.warehouse_order_event
-- ---------------------------------------------------------------------------
--
-- What the warehouse did to its order, as received (WAREHOUSE_EXECUTION_V1 7.5). Kept whatever
-- TMS did with it - an unknown order is still a fact somebody will ask about.
CREATE TABLE tms.warehouse_order_event (
    id                      uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id              uuid        NOT NULL,
    integration_client_id   uuid        NOT NULL,
    source_system           text        NOT NULL,
    event_id                text        NOT NULL,
    event_type              text        NOT NULL,
    -- The ERP order key (contract 2.2), byte for byte.
    external_source         text,
    external_reference      text        NOT NULL,
    warehouse_order_number  text,
    warehouse_code          text,
    reason                  text,
    occurred_at             timestamptz NOT NULL,
    -- Null when the key matched no TMS order (UNKNOWN_ORDER).
    order_id                uuid,
    -- The hold this event placed, when it placed one.
    hold_id                 uuid,
    outcome                 text        NOT NULL,
    -- The event as received (lines included), for the person who has to reconcile it.
    payload                 text        NOT NULL,
    received_at             timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_warehouse_order_event PRIMARY KEY (id),
    CONSTRAINT fk_warehouse_order_event_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_warehouse_order_event_client_company FOREIGN KEY (integration_client_id, company_id)
        REFERENCES tms.integration_client (id, company_id),
    CONSTRAINT fk_warehouse_order_event_order_company FOREIGN KEY (order_id, company_id)
        REFERENCES tms.transport_order (id, company_id),
    CONSTRAINT fk_warehouse_order_event_hold_company FOREIGN KEY (hold_id, company_id)
        REFERENCES tms.order_hold (id, company_id),
    -- Idempotency by the sender's event id (DUPLICATE on repeat).
    CONSTRAINT uq_warehouse_order_event UNIQUE (company_id, source_system, event_id),
    CONSTRAINT ck_warehouse_order_event_type CHECK (event_type IN ('ORDER_CANCELLED', 'ORDER_SHORT_CLOSED')),
    CONSTRAINT ck_warehouse_order_event_outcome CHECK (outcome IN (
        'HOLD_PLACED', 'ALREADY_HELD', 'NOTED', 'ORDER_CLOSED', 'UNKNOWN_ORDER')),
    CONSTRAINT ck_warehouse_order_event_source_system CHECK (source_system ~ '^[A-Z0-9_]{2,40}$'),
    CONSTRAINT ck_warehouse_order_event_event_id CHECK (btrim(event_id) <> '' AND length(event_id) <= 128),
    CONSTRAINT ck_warehouse_order_event_reference CHECK (
        btrim(external_reference) <> '' AND length(external_reference) <= 80),
    -- A hold only where the outcome says one was placed; an order wherever one was found.
    CONSTRAINT ck_warehouse_order_event_hold CHECK (
        (outcome IN ('HOLD_PLACED', 'NOTED')) = (hold_id IS NOT NULL)),
    CONSTRAINT ck_warehouse_order_event_order CHECK ((outcome = 'UNKNOWN_ORDER') = (order_id IS NULL))
);

CREATE INDEX ix_warehouse_order_event_order ON tms.warehouse_order_event (order_id, occurred_at)
    WHERE order_id IS NOT NULL;

COMMENT ON TABLE tms.warehouse_order_event IS
    'A warehouse order event as received (V61, ADR-025 section 4): the warehouse cancelled its order '
    'or closed it short. Its effect is at most a hold on the TMS order; TMS never cancels by itself (D-03).';

-- ---------------------------------------------------------------------------
-- 3. Tenant isolation (ADR-005) and least privilege (V50)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.business_outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.warehouse_order_event ENABLE ROW LEVEL SECURITY;

-- Both are records of facts: written once, never changed, never deleted. V13's default privileges
-- attach all four verbs at CREATE TABLE; the REVOKE is what withholds.
GRANT SELECT, INSERT ON tms.business_outbox_event TO tms_app;
REVOKE UPDATE, DELETE ON tms.business_outbox_event FROM tms_app;
GRANT SELECT, INSERT ON tms.warehouse_order_event TO tms_app;
REVOKE UPDATE, DELETE ON tms.warehouse_order_event FROM tms_app;

CREATE POLICY p_tenant_company_scope ON tms.business_outbox_event
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.warehouse_order_event
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 4. The order feed (GET /integration/v1/orders?status=&updatedSince=)
-- ---------------------------------------------------------------------------
-- Paged by (updated_at, id) inside one company and status, like the shipment feed.
CREATE INDEX ix_transport_order_company_status_updated
    ON tms.transport_order (company_id, status, updated_at, id);

-- ---------------------------------------------------------------------------
-- 5. Webhook vocabulary (the whole list, restated)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.webhook_subscription_event DROP CONSTRAINT ck_webhook_subscription_event_type;
ALTER TABLE tms.webhook_subscription_event ADD CONSTRAINT ck_webhook_subscription_event_type CHECK (
    event_type IN (
        'SHIPMENT_CONFIRMED', 'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED',
        'SHIPMENT_CANCELLED', 'SHIPMENT_CHANGED', 'DELIVERY_RESULT_RECORDED',
        'TENDER_SENT', 'TENDER_ACCEPTED', 'TENDER_REJECTED', 'TENDER_EXPIRED', 'TENDER_CANCELLED',
        -- V61 (ADR-025 section 4).
        'ORDER_CANCELLED'));

-- ---------------------------------------------------------------------------
-- 6. Integration scopes (V55 shape: the whole list, restated)
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
              'integration.order:read', 'integration.warehouse-order-event:write'));
