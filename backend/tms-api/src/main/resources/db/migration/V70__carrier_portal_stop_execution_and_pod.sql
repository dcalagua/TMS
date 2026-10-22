-- ============================================================================
-- V70 - Stop execution and proof of delivery from the carrier portal (ADR-018 addendum)
-- ============================================================================
-- Until now the carrier portal (V57) only reported documents; arrival, service, the delivery
-- result per order, its evidence and the incidents of a stop were typed by the office. This
-- migration lets a CARRIER_OPERATOR do the same on their own dispatched trips, through the SAME
-- domain services the office uses (TripStopExecutionService, TripDeliveryService,
-- DeliveryEvidenceService, TripExceptionService) - there is no second state machine.
--
-- 1. Two permissions for CARRIER_OPERATOR: portal.stop:execute and portal.delivery:report.
-- 2. CARRIER_PORTAL as a source of tms.transport_event and tms.order_delivery, so the office's
--    history says "carrier (portal)" rather than "operator". A carrier source is always a person.
-- 3. tms.portal_action_receipt: the Idempotency-Key of every portal execution action, unique per
--    (company, user, key), so a phone that replays its offline queue records nothing twice.
-- 4. RESTRICTIVE carrier policies on the execution tables the portal now reads and writes:
--    order_delivery, order_delivery_line, delivery_evidence, transport_event, trip_exception and
--    the new receipt table. A carrier transaction sees and writes only rows of its own carrier's
--    trips; every office transaction (NULL tms.current_carrier_id()) is unaffected.
--
-- Nothing is backfilled and no tenant data is touched.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Permissions
-- ---------------------------------------------------------------------------
INSERT INTO tms.permission (resource, action, description) VALUES
    ('portal.stop', 'execute',
     'Carrier portal: record arrival, service start, completion or failure of a stop of my trip, '
     'and report an incident on it'),
    ('portal.delivery', 'report',
     'Carrier portal: record the delivery result of an order at a stop of my trip, with its evidence');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code = 'CARRIER_OPERATOR'
  AND p.code IN ('portal.stop:execute', 'portal.delivery:report');

-- ---------------------------------------------------------------------------
-- 2. CARRIER_PORTAL as an event and delivery source
-- ---------------------------------------------------------------------------
-- V27 refused a DRIVER_APP value because no such source existed ("a source value with no source is
-- a promise the schema cannot keep"). The portal now is one: a person of the carrier, acting from
-- the field. Like OPERATOR it is always a person, so the "is a person" checks are widened with it.
ALTER TABLE tms.transport_event DROP CONSTRAINT ck_transport_event_source;
ALTER TABLE tms.transport_event ADD CONSTRAINT ck_transport_event_source
    CHECK (source IN ('OPERATOR', 'SYSTEM', 'INTEGRATION', 'CARRIER_PORTAL'));
ALTER TABLE tms.transport_event DROP CONSTRAINT ck_transport_event_operator_is_person;
ALTER TABLE tms.transport_event ADD CONSTRAINT ck_transport_event_operator_is_person
    CHECK (source NOT IN ('OPERATOR', 'CARRIER_PORTAL') OR actor_app_user_id IS NOT NULL);

ALTER TABLE tms.order_delivery DROP CONSTRAINT ck_order_delivery_source;
ALTER TABLE tms.order_delivery ADD CONSTRAINT ck_order_delivery_source
    CHECK (source IN ('OPERATOR', 'SYSTEM', 'INTEGRATION', 'CARRIER_PORTAL'));
ALTER TABLE tms.order_delivery DROP CONSTRAINT ck_order_delivery_operator_is_person;
ALTER TABLE tms.order_delivery ADD CONSTRAINT ck_order_delivery_operator_is_person
    CHECK (source NOT IN ('OPERATOR', 'CARRIER_PORTAL') OR actor_app_user_id IS NOT NULL);

COMMENT ON COLUMN tms.transport_event.source IS
    'OPERATOR (a person in the office UI), SYSTEM, INTEGRATION (a partner over the M2M API) or '
    'CARRIER_PORTAL (a carrier user in the portal, V70).';
COMMENT ON COLUMN tms.order_delivery.source IS
    'Who recorded the result last: OPERATOR, SYSTEM, INTEGRATION or CARRIER_PORTAL (V70).';

-- ---------------------------------------------------------------------------
-- 3. tms.portal_action_receipt - idempotency of portal execution actions
-- ---------------------------------------------------------------------------
-- The document report keeps its key on its own event (V57). Stop transitions, deliveries and
-- incidents have no row of their own to carry one - a delivery is overwritten in place, a stop is a
-- column on tms.trip_stop - so the key lives here, written in the same transaction as the action it
-- answers for. A replay with the same key and the same target is answered with the current state
-- and changes nothing; the same key on another target is a 409.
CREATE TABLE tms.portal_action_receipt (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id      uuid        NOT NULL,
    trip_id         uuid        NOT NULL,
    -- What the action addressed. A stop action names its stop; a delivery its stop and order; an
    -- incident its stop when it has one.
    trip_stop_id    uuid,
    order_id        uuid,
    action          text        NOT NULL,
    app_user_id     uuid        NOT NULL,
    idempotency_key text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_portal_action_receipt PRIMARY KEY (id),
    CONSTRAINT uq_portal_action_receipt_key UNIQUE (company_id, app_user_id, idempotency_key),
    CONSTRAINT fk_portal_action_receipt_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_portal_action_receipt_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_portal_action_receipt_stop_company FOREIGN KEY (trip_stop_id, company_id)
        REFERENCES tms.trip_stop (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_portal_action_receipt_order_company FOREIGN KEY (order_id, company_id)
        REFERENCES tms.transport_order (id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_portal_action_receipt_user FOREIGN KEY (app_user_id)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_portal_action_receipt_action CHECK (action IN (
        'STOP_ARRIVE', 'STOP_START_SERVICE', 'STOP_COMPLETE', 'STOP_FAIL', 'DELIVERY_RECORD',
        'INCIDENT_REPORT')),
    CONSTRAINT ck_portal_action_receipt_key_length CHECK (
        char_length(idempotency_key) BETWEEN 1 AND 128),
    CONSTRAINT ck_portal_action_receipt_target CHECK (
        CASE
            WHEN action IN ('STOP_ARRIVE', 'STOP_START_SERVICE', 'STOP_COMPLETE', 'STOP_FAIL')
                THEN trip_stop_id IS NOT NULL AND order_id IS NULL
            WHEN action = 'DELIVERY_RECORD' THEN trip_stop_id IS NOT NULL AND order_id IS NOT NULL
            ELSE order_id IS NULL
        END)
);

CREATE INDEX ix_portal_action_receipt_trip ON tms.portal_action_receipt (company_id, trip_id);

COMMENT ON TABLE tms.portal_action_receipt IS
    'The Idempotency-Key of each carrier-portal execution action (V70): unique per company, user and '
    'key, written with the action. Append-only.';

ALTER TABLE tms.portal_action_receipt ENABLE ROW LEVEL SECURITY;

GRANT SELECT, INSERT ON tms.portal_action_receipt TO tms_app;
REVOKE UPDATE, DELETE ON tms.portal_action_receipt FROM tms_app;

CREATE POLICY p_tenant_company_scope_select ON tms.portal_action_receipt
    FOR SELECT TO tms_app
    USING (company_id = tms.current_company_id());

CREATE POLICY p_tenant_company_scope_insert ON tms.portal_action_receipt
    FOR INSERT TO tms_app
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 4. The carrier boundary on the execution tables (V57's pattern)
-- ---------------------------------------------------------------------------
-- RESTRICTIVE, so ANDed with the tenant policies: these can only narrow. Each table defers to
-- tms.trip, whose own carrier policy is the predicate, because a sub-select on an RLS table is
-- filtered by that table's policies too.
DO $$
DECLARE
    child text;
BEGIN
    FOREACH child IN ARRAY ARRAY['order_delivery', 'transport_event', 'trip_exception', 'portal_action_receipt']
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

-- The children of a delivery defer to the delivery, which defers to the trip.
DO $$
DECLARE
    child text;
BEGIN
    FOREACH child IN ARRAY ARRAY['order_delivery_line', 'delivery_evidence']
    LOOP
        EXECUTE format(
            'CREATE POLICY p_carrier_scope ON tms.%I AS RESTRICTIVE FOR ALL TO tms_app
                 USING (tms.current_carrier_id() IS NULL
                        OR EXISTS (SELECT 1 FROM tms.order_delivery d WHERE d.id = order_delivery_id))
                 WITH CHECK (tms.current_carrier_id() IS NULL
                        OR EXISTS (SELECT 1 FROM tms.order_delivery d WHERE d.id = order_delivery_id))', child);
    END LOOP;
END
$$;
