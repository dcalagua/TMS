-- TMS by EBIM - V20 outbound shipment integration: the transactional outbox behind job 08's
-- ShipmentPlan V1 contract, and the credential scope that reads it.
--
-- Design and the wire contract: docs/integrations/OUTBOUND_SHIPMENT_V1.md.
--
-- ---------------------------------------------------------------------------
-- 1. tms.shipment_outbox_event - the transactional outbox
-- ---------------------------------------------------------------------------
-- One row per fact an external system may care about, written in the SAME transaction as the
-- trip state change it records - the defining property of the outbox pattern and the opposite of
-- tms.integration_request (V18), which is written in its OWN transaction on purpose. Here the row
-- IS the state change's evidence: if PlanningRunService.confirmTrip rolls back, the event must
-- roll back with it, or a partner could be told a shipment was confirmed that TMS itself does not
-- believe happened. Nothing in this table is ever written outside the transaction that produced
-- the fact it describes.
--
-- What this migration deliberately does NOT build: a webhook sender. CLAUDE.md's job 08 brief
-- allows implementing the outbox alone and documenting delivery as a next step rather than faking
-- a retry/backoff/HMAC system this migration has no room for. A partner consumes this table today
-- by polling GET /integration/v1/shipments/events?since=..., not by receiving a push.
CREATE TABLE tms.shipment_outbox_event (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id      uuid        NOT NULL,
    trip_id         uuid        NOT NULL,
    -- Denormalized from tms.trip.shipment_number at write time. The event must still name the
    -- shipment after a hypothetical future trip edit, and copying the stable identifier costs
    -- nothing extra to join for - the same reasoning tms.trip.planning_date (V16) documents.
    shipment_number text        NOT NULL,
    -- 'SHIPMENT_CONFIRMED' is the only value any application code writes today.
    -- 'SHIPMENT_CANCELLED' and 'SHIPMENT_CHANGED' are accepted by the schema for the day a
    -- business rule allows either (see the column comment) rather than needing a second migration
    -- to widen a CHECK that was written too narrowly.
    event_type      text        NOT NULL,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_shipment_outbox_event PRIMARY KEY (id),
    CONSTRAINT fk_shipment_outbox_event_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shipment_outbox_event_trip FOREIGN KEY (trip_id)
        REFERENCES tms.trip (id) ON DELETE RESTRICT,
    -- The tenant guarantee: an event can only name a trip of its own company, structurally -
    -- the same composite-FK idiom V18's integration_request uses against integration_client.
    CONSTRAINT fk_shipment_outbox_event_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),
    CONSTRAINT ck_shipment_outbox_event_type CHECK (
        event_type IN ('SHIPMENT_CONFIRMED', 'SHIPMENT_CHANGED', 'SHIPMENT_CANCELLED')),
    CONSTRAINT ck_shipment_outbox_event_shipment_number_not_blank CHECK (btrim(shipment_number) <> '')
);

-- The consumption pattern is always "everything for my company since my last watermark" -
-- GET /integration/v1/shipments/events?since=... - never a lookup by id.
CREATE INDEX ix_shipment_outbox_event_company_occurred
    ON tms.shipment_outbox_event (company_id, occurred_at, id);

COMMENT ON TABLE tms.shipment_outbox_event IS
    'The transactional outbox behind the outbound Shipment integration (job 08): one row per '
    'publishable trip-state change, written in the same transaction as the change itself. '
    'Consumed by polling, not by a push - see docs/integrations/OUTBOUND_SHIPMENT_V1.md.';
COMMENT ON COLUMN tms.shipment_outbox_event.event_type IS
    'SHIPMENT_CONFIRMED is the only value any code writes today (PlanningRunService.confirmTrip). '
    'SHIPMENT_CHANGED has no source: a confirmed trip is locked against every mutation '
    '(planning.domain.TripStatus), so nothing in TMS can currently produce a change to publish. '
    'SHIPMENT_CANCELLED has no source either: TripService.cancel/PlanningRunService.cancel only '
    'ever cancel a DRAFT trip, which was never published in the first place. Both values are '
    'accepted by the schema so the day either business rule changes, emitting the event is an '
    'application change, not a migration.';

ALTER TABLE tms.shipment_outbox_event ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 2. Tenant Row Level Security (ADR-005)
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT ON tms.shipment_outbox_event TO tms_app;

-- No UPDATE/DELETE grant: an outbox row is a fact about what happened and is never edited or
-- removed by the application, the same "written once" shape tms.integration_request follows for
-- inbound. Retention/archival is an operations concern, not an application code path.
CREATE POLICY p_tenant_company_scope ON tms.shipment_outbox_event
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 3. The outbound read scope
-- ---------------------------------------------------------------------------
-- V18's integration_client_scope.scope CHECK is closed by design ("adding a capability takes a
-- migration plus an enum constant, deliberately"); this is that migration for the new
-- integration.shipment:read scope IntegrationScope gains alongside it.
ALTER TABLE tms.integration_client_scope DROP CONSTRAINT ck_integration_client_scope_value;
ALTER TABLE tms.integration_client_scope ADD CONSTRAINT ck_integration_client_scope_value CHECK (
    scope IN ('integration.location:write', 'integration.order:write', 'integration.shipment:read'));

COMMENT ON COLUMN tms.integration_client_scope.scope IS
    'What one integration credential may do. integration.shipment:read (V20) is read-only and '
    'grants no write anywhere - a partner reading confirmed shipments cannot also be handed the '
    'ability to write locations or orders by the same credential; a partner needing both is '
    'issued a credential with both scopes explicitly.';
