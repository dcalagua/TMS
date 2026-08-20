-- TMS by EBIM - V18 machine-to-machine integration surface.
--
-- Adds the two tables the inbound integration API needs and the authorization catalogue
-- entries that let an administrator manage them:
--
--   tms.integration_client   a partner credential, scoped to exactly one company
--   tms.integration_request  the integration inbox: one row per accepted delivery
--
-- Reasoning, threat model and the wire contract: docs/integrations/INBOUND_API_V1.md.
--
-- Two rules shape everything below:
--
--   1. A credential belongs to one company and cannot be re-pointed. company_id is never
--      updated by any use case, and the composite foreign key from the inbox makes a request
--      row of company A under a client of company B impossible in the database.
--   2. No secret material is stored. Only a one-way hash of a server-generated 256-bit random
--      secret is persisted, and the secret is never logged, echoed or recoverable after the
--      single response that created it.

-- ---------------------------------------------------------------------------
-- 1. tms.integration_client - the partner credential
-- ---------------------------------------------------------------------------
-- Why a fast hash (SHA-256) rather than bcrypt/argon2:
--
-- The secret is not a human password. It is 32 bytes from a CSPRNG rendered base64url, and the
-- application refuses any other shape. A password KDF's work factor exists to make a dictionary
-- or brute-force search of a *low entropy* input expensive; against 2^256 there is nothing to
-- slow down, so the work factor buys no security here. It does buy a cost: this hash is verified
-- on every inbound call, and an 80ms KDF on a partner posting 10,000 orders a day is a
-- self-inflicted denial of service.
--
-- What actually protects the secret is therefore stated plainly: entropy at generation, a
-- one-way hash at rest, constant-time comparison at verification, TLS in transit, and a rotation
-- path that needs no downtime. secret_algorithm records the scheme per row so a future migration
-- to another one can re-hash lazily instead of invalidating every partner credential at once.
CREATE TABLE tms.integration_client (
    id                         uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id                 uuid        NOT NULL,
    -- The public half of the credential. Globally unique rather than per-company because it is
    -- the lookup key of an as-yet unauthenticated request: at the moment it is read there is no
    -- tenant, so it cannot be qualified by one.
    client_id                  text        NOT NULL,
    name                       text        NOT NULL,
    description                text,
    secret_hash                text        NOT NULL,
    secret_algorithm           text        NOT NULL DEFAULT 'sha256',
    -- The rotation grace window. A rotation issues a new secret immediately and keeps the old
    -- hash accepted until previous_secret_expires_at, so a partner can redeploy without a
    -- coordinated cutover. Both columns are cleared once the window closes.
    previous_secret_hash       text,
    previous_secret_expires_at timestamptz,
    active                     boolean     NOT NULL DEFAULT true,
    -- Written outside the request transaction, best effort. It answers "is this credential still
    -- in use" before someone revokes it; nothing depends on its precision.
    last_used_at               timestamptz,
    secret_rotated_at          timestamptz,
    revoked_at                 timestamptz,
    revoked_by                 uuid,
    created_at                 timestamptz NOT NULL DEFAULT now(),
    updated_at                 timestamptz NOT NULL DEFAULT now(),
    created_by                 uuid,
    updated_by                 uuid,
    CONSTRAINT pk_integration_client PRIMARY KEY (id),
    CONSTRAINT fk_integration_client_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- The target of the inbox's composite foreign key: it is what makes "this request belongs to
    -- the same company as the credential that sent it" a database guarantee, the same pattern
    -- uq_company_id_organization established in V2.
    CONSTRAINT uq_integration_client_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_integration_client_client_id UNIQUE (client_id),
    CONSTRAINT uq_integration_client_company_name UNIQUE (company_id, name),
    -- 'tmsc_' + 22 base64url characters = 128 bits of identifier entropy. The prefix makes a
    -- leaked value recognisable in a log or a support ticket, which is how secret scanners work.
    CONSTRAINT ck_integration_client_client_id_shape CHECK (client_id ~ '^tmsc_[A-Za-z0-9_-]{22}$'),
    CONSTRAINT ck_integration_client_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_integration_client_description_not_blank CHECK (
        description IS NULL OR btrim(description) <> ''),
    CONSTRAINT ck_integration_client_algorithm CHECK (secret_algorithm IN ('sha256')),
    -- Lower-case hex of a SHA-256 digest, exactly like tms.order_import_batch.file_sha256 (V17).
    -- A value of any other shape is a bug in the hashing code, and the database says so rather
    -- than storing it.
    CONSTRAINT ck_integration_client_secret_hash_shape CHECK (secret_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_integration_client_previous_hash_shape CHECK (
        previous_secret_hash IS NULL OR previous_secret_hash ~ '^[0-9a-f]{64}$'),
    -- A previous hash with no expiry would be a second permanent secret; an expiry with no hash
    -- is meaningless. Both or neither.
    CONSTRAINT ck_integration_client_previous_secret_pair CHECK (
        (previous_secret_hash IS NULL) = (previous_secret_expires_at IS NULL)),
    -- A rotation must actually change the secret. Without this, a bug that re-hashed the same
    -- value would leave a "rotated" credential the old secret still opens.
    CONSTRAINT ck_integration_client_previous_hash_differs CHECK (
        previous_secret_hash IS NULL OR previous_secret_hash <> secret_hash),
    -- Revocation is terminal and it is what the authenticator checks first. Letting a revoked row
    -- stay active would make the two flags contradict each other.
    CONSTRAINT ck_integration_client_revoked_is_inactive CHECK (
        revoked_at IS NULL OR active = false),
    CONSTRAINT fk_integration_client_revoked_by FOREIGN KEY (revoked_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_integration_client_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_integration_client_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_integration_client_company ON tms.integration_client (company_id);

COMMENT ON TABLE tms.integration_client IS
    'A partner machine credential for the inbound integration API (V18). Scoped to exactly one '
    'company: the tenant of an integration request comes from the credential, never from a '
    'header, so a partner cannot address another company at all.';
COMMENT ON COLUMN tms.integration_client.client_id IS
    'Public identifier presented as the first half of the bearer token. Not a secret.';
COMMENT ON COLUMN tms.integration_client.secret_hash IS
    'SHA-256 of the 256-bit random secret, lower-case hex. The secret itself exists only in the '
    'single creation/rotation response and is never stored, logged or recoverable.';
COMMENT ON COLUMN tms.integration_client.previous_secret_hash IS
    'The superseded secret, still accepted until previous_secret_expires_at so a rotation needs '
    'no coordinated cutover. Cleared when the window closes.';

CREATE TRIGGER tr_integration_client_set_updated_at
    BEFORE UPDATE ON tms.integration_client
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.integration_client ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 1b. tms.integration_client_scope - what the credential may do
-- ---------------------------------------------------------------------------
-- A child table rather than a text[] column, following tms.location_role (V14) and
-- tms.membership_role (V2). The set is closed and small, so an array would have worked; a child
-- table is chosen anyway because it is the shape this schema already uses for "a row holds
-- several values from a closed vocabulary", it lets the value domain be a CHECK constraint the
-- database enforces, and it keeps the JPA mapping ordinary.
--
-- No company_id: a pure child of tms.integration_client, scoped through its parent in section 3.
--
-- "A credential holds at least one scope" is an application invariant with a single writer
-- (IntegrationClientService), exactly as "a location holds at least one role" is - a CHECK
-- cannot express it across two tables without a trigger, and a trigger for it would be a lot of
-- machinery for a rule that is already a @NotEmpty on the request.
CREATE TABLE tms.integration_client_scope (
    id                    uuid        NOT NULL DEFAULT gen_random_uuid(),
    integration_client_id uuid        NOT NULL,
    scope                 text        NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_integration_client_scope PRIMARY KEY (id),
    -- CASCADE, not RESTRICT: a scope has no meaning without its credential, the same reasoning
    -- tms.location_role follows. Credentials are revoked rather than deleted in practice.
    CONSTRAINT fk_integration_client_scope_client FOREIGN KEY (integration_client_id)
        REFERENCES tms.integration_client (id) ON DELETE CASCADE,
    CONSTRAINT uq_integration_client_scope UNIQUE (integration_client_id, scope),
    CONSTRAINT ck_integration_client_scope_value CHECK (
        scope IN ('integration.location:write', 'integration.order:write'))
);

CREATE INDEX ix_integration_client_scope_client
    ON tms.integration_client_scope (integration_client_id);

COMMENT ON TABLE tms.integration_client_scope IS
    'What one integration credential may do. The value domain is closed and mirrored in Java by '
    'IntegrationScope; adding a scope is a migration plus an enum constant, deliberately, so a '
    'new capability cannot be granted by a typo in a payload.';

ALTER TABLE tms.integration_client_scope ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 2. tms.integration_request - the integration inbox
-- ---------------------------------------------------------------------------
-- One row per accepted delivery, written whatever the outcome and in its own transaction, so a
-- business rollback still leaves a record of what was attempted. Without it, "the partner says
-- they sent it yesterday" is unanswerable.
--
-- What is deliberately NOT here:
--   * no credential material of any kind - not the secret, not a fragment, not the bearer token;
--   * no raw payload by default. payload_snapshot exists but is written only when the deployment
--     opts in (tms.integration.retain-payloads), because an order payload carries customer names
--     and addresses and a debugging convenience is not a reason to duplicate personal data into
--     an append-only table. Retention is documented in docs/integrations/INBOUND_API_V1.md.
--
-- payload_hash is always written and is what makes idempotency honest: the same key replayed
-- with a different body is a client bug, and answering it with the first body would hide it.
CREATE TABLE tms.integration_request (
    id                    uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id            uuid        NOT NULL,
    integration_client_id uuid        NOT NULL,
    -- 'location.upsert', 'location.batch', 'order.upsert', 'order.batch'.
    operation             text        NOT NULL,
    idempotency_key       text,
    external_system       text,
    external_reference    text,
    payload_hash          text        NOT NULL,
    payload_snapshot      text,
    status                text        NOT NULL,
    http_status           integer     NOT NULL,
    item_count            integer     NOT NULL DEFAULT 1,
    succeeded_count       integer     NOT NULL DEFAULT 0,
    failed_count          integer     NOT NULL DEFAULT 0,
    -- The business row a single-object operation produced, so "which order did this request
    -- create" needs no join through external references.
    resource_id           uuid,
    -- The response as it was returned, so replaying an idempotency key answers identically
    -- instead of re-executing. JSON held as text on purpose: it is an opaque snapshot read back
    -- whole and never queried into, so a jsonb column would buy nothing and cost a non-trivial
    -- JPA mapping.
    response_body         text,
    -- Sanitised: a short business summary, never an exception message, a stack frame or SQL.
    error_summary         text,
    correlation_id        text,
    received_at           timestamptz NOT NULL DEFAULT now(),
    completed_at          timestamptz NOT NULL DEFAULT now(),
    duration_ms           integer     NOT NULL DEFAULT 0,
    CONSTRAINT pk_integration_request PRIMARY KEY (id),
    CONSTRAINT fk_integration_request_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_integration_request_client FOREIGN KEY (integration_client_id)
        REFERENCES tms.integration_client (id) ON DELETE RESTRICT,
    -- The cross-tenant guarantee: an inbox row can only name a credential of its own company.
    CONSTRAINT fk_integration_request_client_company FOREIGN KEY (integration_client_id, company_id)
        REFERENCES tms.integration_client (id, company_id),
    CONSTRAINT ck_integration_request_operation_not_blank CHECK (btrim(operation) <> ''),
    CONSTRAINT ck_integration_request_status CHECK (
        status IN ('SUCCEEDED', 'PARTIAL', 'REJECTED', 'FAILED')),
    CONSTRAINT ck_integration_request_payload_hash_shape CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_integration_request_http_status CHECK (http_status BETWEEN 100 AND 599),
    CONSTRAINT ck_integration_request_counts_nonnegative CHECK (
        item_count >= 0 AND succeeded_count >= 0 AND failed_count >= 0),
    CONSTRAINT ck_integration_request_counts_add_up CHECK (
        succeeded_count + failed_count <= item_count),
    CONSTRAINT ck_integration_request_idempotency_key_shape CHECK (
        idempotency_key IS NULL OR idempotency_key ~ '^[A-Za-z0-9._:-]{8,128}$'),
    CONSTRAINT ck_integration_request_completed_after_received CHECK (completed_at >= received_at)
);

-- Idempotency is per (tenant, credential, operation, key). Partial, so the deliveries that carry
-- no key - and rely on the business external reference instead - are not forced through a unique
-- index they would all collide on.
CREATE UNIQUE INDEX uq_integration_request_idempotency
    ON tms.integration_request (company_id, integration_client_id, operation, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_integration_request_company_received
    ON tms.integration_request (company_id, received_at DESC);
CREATE INDEX ix_integration_request_client_received
    ON tms.integration_request (integration_client_id, received_at DESC);
-- "What happened to our reference ABC-1?" - the most common partner support question there is.
CREATE INDEX ix_integration_request_company_external
    ON tms.integration_request (company_id, external_system, external_reference)
    WHERE external_reference IS NOT NULL;

COMMENT ON TABLE tms.integration_request IS
    'The integration inbox (V18): one row per accepted inbound delivery, written in its own '
    'transaction so a rolled-back business change still leaves an audit trail. Holds no '
    'credential material and, by default, no raw payload.';
COMMENT ON COLUMN tms.integration_request.payload_hash IS
    'SHA-256 of the request body. Makes a replayed idempotency key with a changed body '
    'detectable, which is what separates idempotency from "return the old answer regardless".';
COMMENT ON COLUMN tms.integration_request.payload_snapshot IS
    'Raw request body, retained only when the deployment opts in. Carries personal data; see the '
    'retention section of docs/integrations/INBOUND_API_V1.md before enabling it.';
COMMENT ON COLUMN tms.integration_request.response_body IS
    'The response returned for this request, replayed verbatim when the same idempotency key '
    'arrives again with the same payload hash.';
COMMENT ON COLUMN tms.integration_request.error_summary IS
    'Sanitised failure summary. Never an exception message, a stack frame or a SQL fragment - '
    'those belong in the server log under the correlation id.';

ALTER TABLE tms.integration_request ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 3. Tenant Row Level Security (ADR-005)
-- ---------------------------------------------------------------------------
-- V13's ALTER DEFAULT PRIVILEGES already covers tables created later; the explicit grants are
-- repeated for the reason V14 states - a missing grant surfaces as an empty list, not an error.
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.integration_client TO tms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.integration_client_scope TO tms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.integration_request TO tms_app;

-- Both tables carry company_id, so both take the standard tenant policy. Note what this means
-- for the authenticator: resolving a client_id to its company happens *before* any tenant is
-- known, so that read runs on the owner connection exactly as principal resolution does
-- (TenantScopedDataSource only switches role once a company scope exists). Every subsequent
-- statement of an integration request runs as tms_app with the credential's company published,
-- so PostgreSQL refuses a cross-company row even if a query forgot its predicate.
CREATE POLICY p_tenant_company_scope ON tms.integration_client
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

CREATE POLICY p_tenant_company_scope ON tms.integration_request
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- The scope child inherits its tenant through its parent, the same EXISTS shape V14 uses for
-- tms.location_role: a scope row is reachable exactly when its credential is.
CREATE POLICY p_tenant_company_scope ON tms.integration_client_scope
    FOR ALL TO tms_app
    USING (EXISTS (SELECT 1 FROM tms.integration_client c
                    WHERE c.id = integration_client_scope.integration_client_id
                      AND c.company_id = tms.current_company_id()))
    WITH CHECK (EXISTS (SELECT 1 FROM tms.integration_client c
                         WHERE c.id = integration_client_scope.integration_client_id
                           AND c.company_id = tms.current_company_id()));

-- ---------------------------------------------------------------------------
-- 4. Authorization catalogue
-- ---------------------------------------------------------------------------
-- Managing a partner credential is an administrative act, not an operational one: it hands out
-- the ability to write orders into a company without a browser session. So only the two admin
-- roles get it, and PLANNER/VIEWER get nothing here - not even read, because the client list is
-- an inventory of who can write into the tenant.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('integration.client', 'read',
     'View the integration credentials of a company and their inbound request history'),
    ('integration.client', 'manage',
     'Create, rotate and revoke integration credentials');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code IN ('integration.client:read', 'integration.client:manage');
