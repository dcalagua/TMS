-- ============================================================================
-- V52 - Dispatch confirmation mode, and a dispatch a credential can make (ADR-013)
-- ============================================================================
-- 1. tms.company_settings.dispatch_confirmation_mode: MANUAL (default) | EXTERNAL_REQUIRED |
--    HYBRID. MANUAL keeps every existing company exactly as it was.
-- 2. tms.trip.dispatch_source + dispatched_by_client, and ready_by_client: a departure (and the
--    ready step a warehouse dispatch takes on the way) is made either by a person or by an
--    integration credential, and the database says which. The V25 pairs that insisted on a person
--    are replaced by exclusive-ors, exactly as V31 did for a tender's answer.
-- 3. planning.trip:dispatch-override, granted to ORGANIZATION_ADMIN and COMPANY_ADMIN only.
-- 4. The redefinition of transport_order.allocated_* approved with the split-order rules.
--
-- Nothing here relaxes an invariant. Every departed trip still names exactly one actor, and V42's
-- ck_trip_departed_carrier_matches_vehicle is untouched: an external dispatch it would refuse is
-- recorded UNAPPLIED, never forced through (ADR-013 section 3).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Dispatch confirmation mode
-- ---------------------------------------------------------------------------
ALTER TABLE tms.company_settings
    ADD COLUMN dispatch_confirmation_mode text NOT NULL DEFAULT 'MANUAL';
ALTER TABLE tms.company_settings ADD CONSTRAINT ck_company_settings_dispatch_confirmation_mode
    CHECK (dispatch_confirmation_mode IN ('MANUAL', 'EXTERNAL_REQUIRED', 'HYBRID'));

COMMENT ON COLUMN tms.company_settings.dispatch_confirmation_mode IS
    'Who may dispatch a trip (ADR-013). MANUAL: a person, as always; a warehouse system''s dispatch '
    'is recorded and reconciled and never moves the trip. EXTERNAL_REQUIRED: the warehouse system '
    'dispatches; a person needs planning.trip:dispatch-override and a reason. HYBRID: whichever '
    'arrives first dispatches, the second only reconciles. A company without a settings row reads '
    'as MANUAL.';

-- ---------------------------------------------------------------------------
-- 2. Machine-attributable dispatch and ready
-- ---------------------------------------------------------------------------
ALTER TABLE tms.trip ADD COLUMN dispatch_source      text;
ALTER TABLE tms.trip ADD COLUMN dispatched_by_client uuid;
ALTER TABLE tms.trip ADD COLUMN ready_by_client      uuid;

-- The credential must belong to the trip's own company: the composite key is the tenant guarantee,
-- the plain one keeps ON DELETE RESTRICT explicit - the same pair V31 declares for a tender.
ALTER TABLE tms.trip ADD CONSTRAINT fk_trip_dispatched_by_client FOREIGN KEY (dispatched_by_client)
    REFERENCES tms.integration_client (id) ON DELETE RESTRICT;
ALTER TABLE tms.trip ADD CONSTRAINT fk_trip_dispatched_by_client_company
    FOREIGN KEY (dispatched_by_client, company_id) REFERENCES tms.integration_client (id, company_id);
ALTER TABLE tms.trip ADD CONSTRAINT fk_trip_ready_by_client FOREIGN KEY (ready_by_client)
    REFERENCES tms.integration_client (id) ON DELETE RESTRICT;
ALTER TABLE tms.trip ADD CONSTRAINT fk_trip_ready_by_client_company
    FOREIGN KEY (ready_by_client, company_id) REFERENCES tms.integration_client (id, company_id);

-- Backfill before the constraints that need it. Every trip that departed so far was dispatched by
-- the person in dispatched_by. The updated_at trigger is held off for the statement: stamping
-- every historical trip "updated now" would push all of them back through the shipment
-- reconciliation feed (?updatedSince=), which is ordered by updated_at, for a change that is not a
-- change to the shipment.
ALTER TABLE tms.trip DISABLE TRIGGER tr_trip_set_updated_at;
UPDATE tms.trip SET dispatch_source = 'OPERATOR' WHERE dispatched_by IS NOT NULL;
ALTER TABLE tms.trip ENABLE TRIGGER tr_trip_set_updated_at;

ALTER TABLE tms.trip DROP CONSTRAINT ck_trip_dispatched_actor_pair;
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_dispatch_source
    CHECK (dispatch_source IS NULL OR dispatch_source IN ('OPERATOR', 'INTEGRATION', 'OPERATOR_OVERRIDE'));
-- A departure has a source and a source has a departure.
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_dispatch_source_pair
    CHECK ((actual_departure_at IS NULL) = (dispatch_source IS NULL));
-- The source and the actor say the same thing, so neither can drift from the other: a person
-- dispatched (with or without an override) and is named, or a credential dispatched and is named,
-- or the trip has not departed and nobody is.
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_dispatch_actor CHECK (
    CASE dispatch_source
        WHEN 'OPERATOR'          THEN dispatched_by IS NOT NULL AND dispatched_by_client IS NULL
        WHEN 'OPERATOR_OVERRIDE' THEN dispatched_by IS NOT NULL AND dispatched_by_client IS NULL
        WHEN 'INTEGRATION'       THEN dispatched_by IS NULL     AND dispatched_by_client IS NOT NULL
        ELSE dispatched_by IS NULL AND dispatched_by_client IS NULL
    END);

-- The ready step: exactly one actor once it has happened, none before. Only an external dispatch
-- arriving for a CONFIRMED trip makes a credential the actor of the ready step (ADR-013 section 3:
-- two legal transitions in one transaction, CONFIRMED -> IN_TRANSIT stays illegal).
ALTER TABLE tms.trip DROP CONSTRAINT ck_trip_ready_actor_pair;
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_ready_actor CHECK (
    CASE
        WHEN ready_at IS NULL THEN ready_by IS NULL AND ready_by_client IS NULL
        ELSE (ready_by IS NULL) <> (ready_by_client IS NULL)
    END);

COMMENT ON COLUMN tms.trip.dispatch_source IS
    'How the trip departed (ADR-013): OPERATOR (a person), OPERATOR_OVERRIDE (a person overriding a '
    'company in EXTERNAL_REQUIRED, with a reason in the audit and the timeline) or INTEGRATION (a '
    'warehouse system''s dispatch document, credential in dispatched_by_client). Null until the '
    'trip departs. Names no product: a third-party WMS is INTEGRATION too.';
COMMENT ON COLUMN tms.trip.dispatched_by_client IS
    'The integration credential that dispatched the trip when dispatch_source = INTEGRATION; '
    'exactly one of dispatched_by / dispatched_by_client is set once the trip has departed.';
COMMENT ON COLUMN tms.trip.ready_by_client IS
    'The integration credential that took the ready step, which only happens when a warehouse '
    'dispatch arrives for a CONFIRMED trip; exactly one of ready_by / ready_by_client is set once '
    'the trip is ready.';

-- ---------------------------------------------------------------------------
-- 3. planning.trip:dispatch-override
-- ---------------------------------------------------------------------------
-- Its own permission and not a widening of planning.trip:execute: dispatching past the warehouse
-- is a supervisor's decision, which is exactly why PLANNER does not get it. An integration
-- credential never holds a permission at all (its authorities are scopes), so no grant here can
-- reach one.
-- The approved name has a hyphen, which V2's action shape did not allow. The shape is widened by
-- exactly that: a hyphen only between lower-case words, the same 3-32 length. It is a format rule
-- for a code a person reads, not a security invariant, and every existing action still matches.
ALTER TABLE tms.permission DROP CONSTRAINT ck_permission_action_shape;
ALTER TABLE tms.permission ADD CONSTRAINT ck_permission_action_shape CHECK (
    action ~ '^[a-z][a-z_]*(-[a-z][a-z_]*)*$' AND length(action) BETWEEN 3 AND 32);

INSERT INTO tms.permission (resource, action, description) VALUES
    ('planning.trip', 'dispatch-override',
     'Dispatch a trip by hand, with a reason, when the company requires the warehouse to confirm it');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE p.code = 'planning.trip:dispatch-override'
  AND r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN');

-- ---------------------------------------------------------------------------
-- 4. The approved meaning of allocated_* (docs/domain/SPLIT_ORDER_EXECUTION.md)
-- ---------------------------------------------------------------------------
COMMENT ON COLUMN tms.transport_order.allocated_weight_kg IS
    'Kilograms committed to trips since the order last entered the pool: open trips, plus finished '
    'trips whose order has not closed out yet (split-order rule R2). ordered - allocated is what a '
    'planner may still place. Consumed when the order''s last carrier closes it out.';
COMMENT ON COLUMN tms.transport_order.allocated_volume_m3 IS
    'Cubic metres committed to trips since the order last entered the pool; see allocated_weight_kg.';
COMMENT ON COLUMN tms.transport_order.allocated_pallets IS
    'Pallets committed to trips since the order last entered the pool; see allocated_weight_kg.';

-- ---------------------------------------------------------------------------
-- 5. DISPATCH_OVERRIDDEN in the audit trail
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
    -- V52 (ADR-013 section 4).
    'DISPATCH_OVERRIDDEN'));
