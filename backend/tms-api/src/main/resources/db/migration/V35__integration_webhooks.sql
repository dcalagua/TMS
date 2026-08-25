-- TMS by EBIM - V35 outbound webhooks: the push half of the outbound integration surface.
--
-- V20 built the transactional outbox (tms.shipment_outbox_event) and said in its own header what
-- it deliberately did NOT build: "a webhook sender ... a partner consumes this table today by
-- polling GET /integration/v1/shipments/events?since=..., not by receiving a push". This is that
-- next step, and nothing about the polling feed changes - a partner that polls keeps polling, a
-- partner that subscribes gets the same events pushed, and the outbox stays the single source of
-- both.
--
-- Contract, signature scheme, retry schedule and the operator's runbook:
-- docs/integrations/WEBHOOKS_V1.md.
--
-- Four rules shape everything below:
--
--   1. The business transaction never waits on a network. Confirming a trip writes an outbox row
--      and one PENDING delivery row per matching subscription - two inserts - and returns. Every
--      HTTP call happens later, in the dispatcher, outside any business transaction. This is the
--      same reason the outbox exists at all, extended one step.
--   2. A delivery is at-least-once and carries an event id, so a receiver can deduplicate. The
--      unique index on (subscription_id, event_id) is what stops TMS itself from ever enqueuing
--      the same event twice for the same subscription.
--   3. Every attempt is recorded. "You never sent it" / "we never received it" is the entire
--      support conversation about webhooks, and tms.webhook_delivery_attempt is the answer to it.
--   4. The signing secret is encrypted at rest, not hashed. This is the one place the integration
--      module cannot follow tms.integration_client's "store only a one-way hash" rule, and the
--      reason is arithmetic rather than preference: an HMAC signature is computed FROM the secret
--      on every send, so a value TMS cannot recover is a value TMS cannot sign with. See the
--      column comment on secret_ciphertext.

-- ---------------------------------------------------------------------------
-- 1. tms.webhook_subscription - where a tenant's events are pushed
-- ---------------------------------------------------------------------------
-- Company-scoped like every other tenant object, and deliberately NOT tied to an
-- tms.integration_client. A subscription is an endpoint the customer owns; a credential is how a
-- partner authenticates INTO TMS. Coupling them would mean a partner had to hold an inbound
-- credential to receive a push, which is backwards - the receiving system may have no reason to
-- ever call TMS at all.
CREATE TABLE tms.webhook_subscription (
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id           uuid        NOT NULL,
    name                 text        NOT NULL,
    description          text,
    -- The absolute URL each event is POSTed to. Validated in the application as well
    -- (WebhookTargetPolicy): https by default, and never an address inside the deployment's own
    -- network, because an administrator who can type a URL that the server then fetches is the
    -- definition of a server-side request forgery hole. The CHECK here is the coarse half - the
    -- shape - and the policy is the half that knows what a private address is.
    target_url           text        NOT NULL,
    -- AES-256-GCM ciphertext of the signing secret, base64. NOT a hash: see the header note 4.
    secret_ciphertext    text        NOT NULL,
    secret_algorithm     text        NOT NULL DEFAULT 'aes-gcm-256',
    -- The last four characters of the secret, so a screen can say which secret is configured
    -- without revealing it - the same "enough to recognise, not enough to use" idea as the last
    -- four digits of a card. Four characters of a 43-character base64url value leaves 216 bits
    -- unknown.
    secret_hint          text        NOT NULL,
    secret_rotated_at    timestamptz,
    active               boolean     NOT NULL DEFAULT true,
    -- Why the endpoint was switched off, when TMS switched it off rather than a person. A dead
    -- endpoint that keeps being retried forever is a self-inflicted outbound flood, so the
    -- dispatcher suspends a subscription after too many consecutive failures and says so here.
    -- An administrator reactivates it once they have fixed their side.
    suspended_reason     text,
    -- Reset to zero by every delivered attempt. The counter, not a failure rate, because the
    -- question it answers is "is this endpoint down right now", not "how reliable has it been".
    consecutive_failures integer     NOT NULL DEFAULT 0,
    last_success_at      timestamptz,
    last_failure_at      timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    created_by           uuid,
    updated_by           uuid,
    CONSTRAINT pk_webhook_subscription PRIMARY KEY (id),
    CONSTRAINT fk_webhook_subscription_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- The target of the delivery table's composite foreign key: it is what makes "this delivery
    -- belongs to the same company as its subscription" a database guarantee rather than an
    -- application convention, the same pattern V18 uses between integration_request and
    -- integration_client.
    CONSTRAINT uq_webhook_subscription_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_webhook_subscription_company_name UNIQUE (company_id, name),
    CONSTRAINT ck_webhook_subscription_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_webhook_subscription_description_not_blank CHECK (
        description IS NULL OR btrim(description) <> ''),
    CONSTRAINT ck_webhook_subscription_target_url_shape CHECK (
        target_url ~ '^https?://[^[:space:]]+$' AND length(target_url) <= 2048),
    CONSTRAINT ck_webhook_subscription_algorithm CHECK (secret_algorithm IN ('aes-gcm-256')),
    CONSTRAINT ck_webhook_subscription_secret_hint_shape CHECK (secret_hint ~ '^[A-Za-z0-9_-]{4}$'),
    CONSTRAINT ck_webhook_subscription_failures_nonnegative CHECK (consecutive_failures >= 0),
    -- A reason is what distinguishes "an administrator turned this off" from "TMS turned it off
    -- because the endpoint stopped answering", and it must not survive reactivation - otherwise a
    -- working subscription carries a stale explanation of an outage that is over.
    CONSTRAINT ck_webhook_subscription_suspended_reason CHECK (
        suspended_reason IS NULL OR active = false),
    CONSTRAINT fk_webhook_subscription_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_webhook_subscription_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_webhook_subscription_company ON tms.webhook_subscription (company_id);

COMMENT ON TABLE tms.webhook_subscription IS
    'Where one tenant wants its published events pushed (V35). The push half of the outbound '
    'integration surface V20 built the outbox for; polling GET /integration/v1/shipments/events '
    'remains supported and unchanged. See docs/integrations/WEBHOOKS_V1.md.';
COMMENT ON COLUMN tms.webhook_subscription.secret_ciphertext IS
    'The signing secret, encrypted with AES-256-GCM under the deployment key '
    'tms.integration.webhooks.secret-key - NOT hashed, unlike integration_client.secret_hash. An '
    'HMAC signature is computed from the secret on every send, so a one-way hash would leave TMS '
    'unable to sign. The consequences are stated rather than hidden: a database dump plus the '
    'deployment key yields the secrets, which is why the key belongs in the secret store and not '
    'beside the connection string. Rotating a subscription secret is one click and is the '
    'documented response to a suspected key exposure.';
COMMENT ON COLUMN tms.webhook_subscription.suspended_reason IS
    'Set when the DISPATCHER deactivated the subscription after repeated failures, never when a '
    'person did. Cleared on reactivation.';

CREATE TRIGGER tr_webhook_subscription_set_updated_at
    BEFORE UPDATE ON tms.webhook_subscription
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.webhook_subscription ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 1b. tms.webhook_subscription_event - which events this endpoint wants
-- ---------------------------------------------------------------------------
-- A child table rather than a text[] column, for the reasons V18 gives for
-- tms.integration_client_scope: it is the shape this schema already uses for "a row holds several
-- values from a closed vocabulary", the value domain becomes a CHECK the database enforces, and
-- the JPA mapping stays ordinary.
--
-- The vocabulary is exactly planning.domain.ShipmentEventType, which is also
-- ck_shipment_outbox_event_type as widened by V25, V28 and V31. It is repeated here rather than
-- referenced because the two are not the same list for the same reason: that one constrains what
-- TMS may PUBLISH, this one constrains what a customer may SUBSCRIBE to. They happen to coincide
-- today, and WebhookEventTypeTest asserts they still do on every build, so a value added to one
-- and forgotten in the other fails a test instead of silently delivering nothing.
CREATE TABLE tms.webhook_subscription_event (
    id                      uuid        NOT NULL DEFAULT gen_random_uuid(),
    webhook_subscription_id uuid        NOT NULL,
    event_type              text        NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_webhook_subscription_event PRIMARY KEY (id),
    -- CASCADE, not RESTRICT: a selected event has no meaning without its subscription, the same
    -- reasoning tms.integration_client_scope follows.
    CONSTRAINT fk_webhook_subscription_event_subscription FOREIGN KEY (webhook_subscription_id)
        REFERENCES tms.webhook_subscription (id) ON DELETE CASCADE,
    CONSTRAINT uq_webhook_subscription_event UNIQUE (webhook_subscription_id, event_type),
    CONSTRAINT ck_webhook_subscription_event_type CHECK (event_type IN (
        'SHIPMENT_CONFIRMED', 'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED',
        'SHIPMENT_CANCELLED', 'SHIPMENT_CHANGED', 'DELIVERY_RESULT_RECORDED',
        'TENDER_SENT', 'TENDER_ACCEPTED', 'TENDER_REJECTED', 'TENDER_EXPIRED', 'TENDER_CANCELLED'))
);

CREATE INDEX ix_webhook_subscription_event_subscription
    ON tms.webhook_subscription_event (webhook_subscription_id);

COMMENT ON TABLE tms.webhook_subscription_event IS
    'Which published events one subscription wants. A subscription with no rows receives nothing, '
    'which is why the application requires at least one - "subscribe to everything" is spelled by '
    'selecting every type, never by selecting none.';

ALTER TABLE tms.webhook_subscription_event ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 2. tms.webhook_delivery - one event owed to one subscription
-- ---------------------------------------------------------------------------
-- Written in the SAME transaction as the outbox row it mirrors, which is what makes "the trip was
-- confirmed but nobody was told" impossible: if the confirmation rolls back, so does the delivery.
-- What is NOT in that transaction is the HTTP call.
--
-- event_id is deliberately a plain uuid and NOT a foreign key to tms.shipment_outbox_event, even
-- though every row today comes from one. Two reasons: the delivery table is meant to outlive the
-- shipment event family - a future event about, say, a rate card would use these same four columns
-- - and the outbox is another module's table, which a foreign key would weld this one to forever.
-- The uniqueness that matters is enforced here regardless.
CREATE TABLE tms.webhook_delivery (
    id                      uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id              uuid        NOT NULL,
    webhook_subscription_id uuid        NOT NULL,
    -- The id of the published fact, as the receiver sees it in the body and in X-TMS-Event-Id.
    -- This is the value a partner deduplicates on, so it is the outbox row's own id and never
    -- regenerated on retry.
    event_id                uuid        NOT NULL,
    event_type              text        NOT NULL,
    occurred_at             timestamptz NOT NULL,
    -- The rendered body, byte for byte, as every attempt will send it. Stored rather than rebuilt
    -- so that a retry three hours later is identical to the first try - which is what makes the
    -- signature verifiable and the receiver's deduplication honest. Text and not jsonb for the
    -- reason integration_request.response_body is text (V18): it is an opaque snapshot read back
    -- whole and never queried into, so jsonb would buy nothing and cost a non-trivial JPA mapping.
    payload                 text        NOT NULL,
    status                  text        NOT NULL DEFAULT 'PENDING',
    attempt_count           integer     NOT NULL DEFAULT 0,
    -- When the dispatcher may next pick this row up. Set to now() at creation and pushed forward
    -- by the backoff after each retryable failure; this column plus the partial index below IS
    -- the queue.
    next_attempt_at         timestamptz NOT NULL DEFAULT now(),
    last_attempt_at         timestamptz,
    completed_at            timestamptz,
    last_status_code        integer,
    -- Sanitised, and short: a status line or an I/O failure class. Never a response body, which
    -- may carry anything the receiver felt like echoing back, including our own payload.
    last_error              text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_webhook_delivery PRIMARY KEY (id),
    CONSTRAINT fk_webhook_delivery_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_webhook_delivery_subscription FOREIGN KEY (webhook_subscription_id)
        REFERENCES tms.webhook_subscription (id) ON DELETE CASCADE,
    -- The cross-tenant guarantee: a delivery can only name a subscription of its own company.
    CONSTRAINT fk_webhook_delivery_subscription_company FOREIGN KEY (webhook_subscription_id, company_id)
        REFERENCES tms.webhook_subscription (id, company_id),
    -- Idempotency, at the only layer that can guarantee it: one event reaches one subscription
    -- once. A fan-out that ran twice - a retried business transaction, a bug, an operator replaying
    -- something - collides here instead of double-delivering.
    CONSTRAINT uq_webhook_delivery_subscription_event UNIQUE (webhook_subscription_id, event_id),
    CONSTRAINT ck_webhook_delivery_status CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED')),
    CONSTRAINT ck_webhook_delivery_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_webhook_delivery_status_code CHECK (
        last_status_code IS NULL OR last_status_code BETWEEN 100 AND 599),
    -- A terminal row has a completion time and a pending one does not. Without this, a status
    -- written without its timestamp would make "how long did this take to land" unanswerable for
    -- exactly the deliveries somebody is asking about.
    CONSTRAINT ck_webhook_delivery_completed_pairs CHECK (
        (status = 'PENDING') = (completed_at IS NULL)),
    CONSTRAINT ck_webhook_delivery_payload_not_blank CHECK (btrim(payload) <> '')
);

-- THE queue index. Partial on PENDING because the table is overwhelmingly terminal rows after a
-- week, and the dispatcher's only question is "what is due now": ordering by next_attempt_at
-- inside the partial index means the claim query never reads a processed row at all.
CREATE INDEX ix_webhook_delivery_due
    ON tms.webhook_delivery (next_attempt_at, id)
    WHERE status = 'PENDING';

-- The administration screen: this company's deliveries, newest first, optionally for one endpoint.
CREATE INDEX ix_webhook_delivery_company_created
    ON tms.webhook_delivery (company_id, created_at DESC);
CREATE INDEX ix_webhook_delivery_subscription_created
    ON tms.webhook_delivery (webhook_subscription_id, created_at DESC);

COMMENT ON TABLE tms.webhook_delivery IS
    'One published event owed to one subscription (V35). Created in the same transaction as the '
    'outbox row it mirrors; delivered later by the dispatcher, never inside a business '
    'transaction. At-least-once: the receiver deduplicates on event_id.';
COMMENT ON COLUMN tms.webhook_delivery.payload IS
    'The exact bytes every attempt sends. Frozen at creation so a retry is byte-identical to the '
    'first try, which is what keeps the signature verifiable across attempts.';
COMMENT ON COLUMN tms.webhook_delivery.status IS
    'PENDING until a 2xx (PROCESSED) or until the retry schedule is exhausted or the receiver '
    'answers something no retry can fix (FAILED). FAILED is not final for an operator: '
    'POST /api/v1/webhooks/deliveries/{id}/retry puts the row back in the queue.';

ALTER TABLE tms.webhook_delivery ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 3. tms.webhook_delivery_attempt - what happened, every single time
-- ---------------------------------------------------------------------------
-- Append-only, one row per HTTP call. tms.webhook_delivery already carries the LAST outcome; this
-- carries all of them, because the support question is never "did the last attempt fail" but "what
-- exactly did you send, when, and what did we answer" - and the honest answer to that has to
-- include the four attempts that timed out before the one that worked.
CREATE TABLE tms.webhook_delivery_attempt (
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid        NOT NULL,
    webhook_delivery_id uuid        NOT NULL,
    attempt_number      integer     NOT NULL,
    attempted_at        timestamptz NOT NULL DEFAULT now(),
    duration_ms         integer     NOT NULL DEFAULT 0,
    -- Null when the call never produced a response at all: DNS failure, connection refused,
    -- timeout. That difference is the first thing an integrator needs and the last thing a single
    -- "failed" flag can express.
    status_code         integer,
    outcome             text        NOT NULL,
    error               text,
    CONSTRAINT pk_webhook_delivery_attempt PRIMARY KEY (id),
    CONSTRAINT fk_webhook_delivery_attempt_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_webhook_delivery_attempt_delivery FOREIGN KEY (webhook_delivery_id)
        REFERENCES tms.webhook_delivery (id) ON DELETE CASCADE,
    CONSTRAINT uq_webhook_delivery_attempt_number UNIQUE (webhook_delivery_id, attempt_number),
    CONSTRAINT ck_webhook_delivery_attempt_number CHECK (attempt_number >= 1),
    CONSTRAINT ck_webhook_delivery_attempt_duration CHECK (duration_ms >= 0),
    CONSTRAINT ck_webhook_delivery_attempt_status_code CHECK (
        status_code IS NULL OR status_code BETWEEN 100 AND 599),
    -- DELIVERED: 2xx. RETRYABLE_FAILURE: a timeout, a 5xx, a 429 - the receiver may yet recover.
    -- PERMANENT_FAILURE: a 4xx that says the request itself is wrong, or a 410 Gone, where
    -- retrying the same bytes is guaranteed to fail again and is therefore just noise on somebody
    -- else's server.
    CONSTRAINT ck_webhook_delivery_attempt_outcome CHECK (
        outcome IN ('DELIVERED', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE'))
);

CREATE INDEX ix_webhook_delivery_attempt_delivery
    ON tms.webhook_delivery_attempt (webhook_delivery_id, attempt_number);

COMMENT ON TABLE tms.webhook_delivery_attempt IS
    'Every HTTP call the dispatcher made, in order, with what came back (V35). Append-only: this '
    'is the record a "you never sent it" conversation is settled from.';

ALTER TABLE tms.webhook_delivery_attempt ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 4. Tenant Row Level Security (ADR-005)
-- ---------------------------------------------------------------------------
-- V13's ALTER DEFAULT PRIVILEGES already covers tables created later; the explicit grants are
-- repeated for the reason V14 states - a missing grant surfaces as an empty list, not an error.
--
-- Read this together with TenantScopedDataSource: the DISPATCHER runs on a background thread with
-- no security context and therefore no company scope, so its connection is never switched to
-- tms_app and these policies do not apply to it. That is deliberate and is the only way a
-- cross-company queue can be drained by one worker. It is also why the dispatcher's own queries
-- carry their company predicate explicitly - it is running as the owner, so nothing else will.
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.webhook_subscription TO tms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.webhook_subscription_event TO tms_app;
-- No DELETE on either table below: a delivery and its attempts are the record of what TMS told an
-- outside system, and the application never edits that record away. The UPDATE is for the two
-- things an administrator legitimately does to a delivery - retry it now, and nothing else.
-- Retention is an operations concern, exactly as it is for tms.integration_request.
GRANT SELECT, INSERT, UPDATE ON tms.webhook_delivery TO tms_app;
GRANT SELECT, INSERT ON tms.webhook_delivery_attempt TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.webhook_subscription
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

CREATE POLICY p_tenant_company_scope ON tms.webhook_delivery
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

CREATE POLICY p_tenant_company_scope ON tms.webhook_delivery_attempt
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- The event child inherits its tenant through its parent, the same EXISTS shape V14 uses for
-- tms.location_role and V18 for tms.integration_client_scope.
CREATE POLICY p_tenant_company_scope ON tms.webhook_subscription_event
    FOR ALL TO tms_app
    USING (EXISTS (SELECT 1 FROM tms.webhook_subscription s
                    WHERE s.id = webhook_subscription_event.webhook_subscription_id
                      AND s.company_id = tms.current_company_id()))
    WITH CHECK (EXISTS (SELECT 1 FROM tms.webhook_subscription s
                         WHERE s.id = webhook_subscription_event.webhook_subscription_id
                           AND s.company_id = tms.current_company_id()));

-- ---------------------------------------------------------------------------
-- 5. Authorization catalogue
-- ---------------------------------------------------------------------------
-- Its own pair rather than reusing integration.client:read/manage, and the reason is that the two
-- objects fail in opposite directions. A credential is a way IN: mismanaging one lets somebody
-- write orders into this company. A subscription is a way OUT: mismanaging one sends this
-- company's shipment numbers to an address of the administrator's choosing. Both are
-- administrative, both go to the same two roles today - but a deployment that later wants an
-- integrations operator who may configure endpoints and may not mint credentials can express that
-- without a migration, and a permission that cannot express a real distinction is one that gets
-- granted by accident.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('integration.webhook', 'read',
     'View the webhook subscriptions of a company and their delivery history'),
    ('integration.webhook', 'manage',
     'Create, edit, rotate the secret of, suspend and retry webhook subscriptions');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code IN ('integration.webhook:read', 'integration.webhook:manage');

-- ---------------------------------------------------------------------------
-- 6. Audit vocabulary
-- ---------------------------------------------------------------------------
-- Creating an endpoint, pointing it somewhere else and rotating its secret are all acts that
-- change where this company's operational data goes, so they are audited as their own aggregate
-- rather than folded into INTEGRATION_CLIENT. The actions themselves are the existing ones -
-- CREATE, UPDATE, ACTIVATE, DEACTIVATE and CREDENTIAL_ROTATE - because a webhook secret rotation
-- is a credential rotation in every sense that matters to whoever reads the trail.
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_aggregate_type;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_aggregate_type CHECK (aggregate_type IN (
    'LOCATION', 'CARRIER', 'VEHICLE', 'DRIVER', 'TRANSPORT_ORDER', 'TRIP', 'PLANNING_RUN',
    'INTEGRATION_CLIENT', 'MASTER_DATA_IMPORT_BATCH', 'ORDER_IMPORT_BATCH', 'SHIPMENT',
    'RATE_CARD', 'TRIP_COST',
    'COMPANY', 'APP_USER', 'MEMBERSHIP',
    'WEBHOOK_SUBSCRIPTION'));
