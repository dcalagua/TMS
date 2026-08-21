-- TMS by EBIM - V22 business audit trail (Job 13).
--
-- One append-only event per important business action: a location activated, a carrier or
-- vehicle changed, an order created/updated/cancelled, a trip assigned/moved/confirmed/
-- cancelled, an integration credential issued/rotated/revoked, a master-data or order import
-- executed, a shipment confirmed for outbound delivery. Not Event Sourcing: this table is a
-- read/report trail alongside the normal transactional tables, never the source of truth those
-- tables are rebuilt from.
--
-- Written by com.ebim.tms.audit.application.AuditEventRecorder, the only implementation of
-- com.ebim.tms.shared.audit.AuditRecorder - the "explicit API" ModuleBoundaryTest requires for
-- one business module to reach another, the same shape OriginLookupPort/OriginLookupAdapter
-- already established for masterdata. Every other business module depends on the shared port
-- only, never on this module's table or entity.

CREATE TABLE tms.audit_event (
    id                    uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id            uuid          NOT NULL,
    actor_app_user_id     uuid,
    actor_email           text,
    actor_machine_label   text,
    aggregate_type        text          NOT NULL,
    aggregate_id          uuid          NOT NULL,
    action                text          NOT NULL,
    occurred_at           timestamptz   NOT NULL DEFAULT now(),
    correlation_id        text,
    metadata              text,
    CONSTRAINT pk_audit_event PRIMARY KEY (id),
    CONSTRAINT fk_audit_event_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_audit_event_actor_app_user FOREIGN KEY (actor_app_user_id)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    -- Exactly one of "a person" or "a machine" - never both, never neither. Mirrors
    -- com.ebim.tms.shared.audit.AuditActor's own contract (person: appUserId+email, no
    -- machineActorLabel; machine: machineActorLabel, neither of the other two), so a row that
    -- fails this check means the recorder itself has a bug, not that the data is merely unusual.
    CONSTRAINT ck_audit_event_actor_xor CHECK (
        (actor_app_user_id IS NOT NULL) <> (actor_machine_label IS NOT NULL)),
    CONSTRAINT ck_audit_event_aggregate_type CHECK (aggregate_type IN (
        'LOCATION', 'CARRIER', 'VEHICLE', 'TRANSPORT_ORDER', 'TRIP', 'PLANNING_RUN',
        'INTEGRATION_CLIENT', 'MASTER_DATA_IMPORT_BATCH', 'ORDER_IMPORT_BATCH', 'SHIPMENT')),
    CONSTRAINT ck_audit_event_action CHECK (action IN (
        'CREATE', 'UPDATE', 'ACTIVATE', 'DEACTIVATE', 'ASSIGN_ORDER', 'REMOVE_ORDER', 'MOVE_ORDER',
        'VEHICLE_CHANGE', 'CONFIRM', 'CANCEL', 'CREDENTIAL_CREATE', 'CREDENTIAL_ROTATE',
        'CREDENTIAL_REVOKE', 'IMPORT_EXECUTED', 'SHIPMENT_CONFIRMED')),
    -- Same 4000-character ceiling AuditEventRecorder truncates to before it ever reaches this
    -- column - belt and suspenders against a metadata map nobody meant to be this large, not a
    -- limit anyone is expected to hit.
    CONSTRAINT ck_audit_event_metadata_length CHECK (length(metadata) <= 4000)
);

CREATE INDEX ix_audit_event_company_occurred
    ON tms.audit_event (company_id, occurred_at DESC);

CREATE INDEX ix_audit_event_company_aggregate
    ON tms.audit_event (company_id, aggregate_type, aggregate_id, occurred_at DESC);

COMMENT ON TABLE tms.audit_event IS
    'One append-only business audit record: who did what to which aggregate, when, under which '
    'correlation id. Written once by AuditEventRecorder, in the same transaction as the change '
    'it describes, and never updated or deleted (see the grants below) - an audit record that '
    'can be edited answers a different question from the one it was written for.';

COMMENT ON COLUMN tms.audit_event.metadata IS
    'Compact JSON, business fields only (a code, a status, a reason) - never a secret and never '
    'the row-level detail already in the table the aggregate itself lives in.';

ALTER TABLE tms.audit_event ENABLE ROW LEVEL SECURITY;

-- Deliberately not the four-verb grant every other business table carries (ADR-005): this is
-- the first table in the schema where UPDATE and DELETE are withheld from tms_app on purpose,
-- so "append-only" is a database fact and not merely a convention no service ever violates -
-- exactly what com.ebim.tms.audit's package-info has promised since before this migration
-- existed. Postgres 16's ALTER DEFAULT PRIVILEGES (V13) already granted the usual four verbs at
-- CREATE TABLE time above; the REVOKE below is what actually withholds the two that matter.
GRANT SELECT, INSERT ON tms.audit_event TO tms_app;
REVOKE UPDATE, DELETE ON tms.audit_event FROM tms_app;

-- Split into FOR SELECT / FOR INSERT rather than the usual single FOR ALL policy (V13): there is
-- no UPDATE/DELETE grant left for a FOR ALL policy to guard, and writing only the two operations
-- that can actually happen says so on the page instead of relying on the grants above being read
-- first.
CREATE POLICY p_tenant_company_scope_select ON tms.audit_event
    FOR SELECT TO tms_app
    USING (company_id = tms.current_company_id());

CREATE POLICY p_tenant_company_scope_insert ON tms.audit_event
    FOR INSERT TO tms_app
    WITH CHECK (company_id = tms.current_company_id());
