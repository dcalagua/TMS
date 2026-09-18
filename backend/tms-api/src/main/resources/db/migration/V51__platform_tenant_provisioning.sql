-- ===========================================================================
-- V51 - Control-plane tenant provisioning (EBIM MasterAdmin)
-- ===========================================================================
--
-- MasterAdmin is the control plane: it owns products, tenants, subscriptions and
-- licences. TMS keeps owning its database, its tenant model, its users and its
-- rules. The only thing crossing the boundary is a signed request, so the only
-- thing this migration adds is (a) the mapping that says which TMS organization
-- and company a control-plane tenant became, and (b) the record of the call that
-- made it, which is also its audit trail.
--
-- Two tables and not one, on purpose:
--
--   * platform_tenant is the durable fact. It exists only when provisioning
--     succeeded, and its uniqueness is what makes "provision this tenant twice"
--     impossible even when two callers use different idempotency keys, or arrive
--     at the same instant.
--   * platform_provisioning is the request log. One row per idempotency key,
--     holding what was asked, what was answered and who asked - including the
--     M2M subject and the token's jti. It is deliberately NOT tms.audit_event:
--     that table is company-scoped and AuditRecorder needs a CompanyScope, and
--     at the moment an organization is created there is no company to scope to.
--
-- Both are written on the owner connection, like Flyway and principal
-- resolution, because provisioning runs before any company scope exists. RLS is
-- enabled with the p_backend_managed policy the identity tables use (V13): the
-- rows are managed by the backend rather than filtered by tenant, and no
-- company-scoped code path reads them.
--
-- No tenant is seeded here. This migration creates structure and nothing else.

-- ---------------------------------------------------------------------------
-- 1. The mapping: control-plane tenant -> TMS organization + company
-- ---------------------------------------------------------------------------
CREATE TABLE tms.platform_tenant (
    id                      uuid        NOT NULL DEFAULT gen_random_uuid(),
    -- The identifier MasterAdmin knows the tenant by. Never assumed equal to any
    -- TMS id: the mapping is explicit precisely so the two id spaces can stay
    -- independent, and so a control plane that renumbers cannot silently point
    -- at somebody else's data.
    control_plane_tenant_id uuid        NOT NULL,
    organization_id         uuid        NOT NULL,
    company_id              uuid        NOT NULL,
    -- The preprovisioned administrator, when one was requested. Nullable because
    -- the admin block is optional in the contract, not because it may dangle.
    admin_app_user_id       uuid,
    deployment_mode         text        NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_platform_tenant PRIMARY KEY (id),
    -- The three that make double provisioning structurally impossible: one row
    -- per control-plane tenant, and an organization or a company can belong to
    -- at most one of them.
    CONSTRAINT uq_platform_tenant_control_plane UNIQUE (control_plane_tenant_id),
    CONSTRAINT uq_platform_tenant_organization UNIQUE (organization_id),
    CONSTRAINT uq_platform_tenant_company UNIQUE (company_id),
    CONSTRAINT fk_platform_tenant_organization FOREIGN KEY (organization_id)
        REFERENCES tms.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_platform_tenant_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- The same composite-foreign-key idiom membership uses: the company named
    -- here must belong to the organization named here, as a database guarantee.
    CONSTRAINT fk_platform_tenant_company_in_organization FOREIGN KEY (company_id, organization_id)
        REFERENCES tms.company (id, organization_id),
    CONSTRAINT fk_platform_tenant_admin FOREIGN KEY (admin_app_user_id)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    -- V1 provisions shared-instance tenants only. A dedicated deployment is a
    -- different conversation about infrastructure, not a value this column can
    -- absorb quietly, so the CHECK names what is supported today.
    CONSTRAINT ck_platform_tenant_deployment_mode CHECK (deployment_mode IN ('SHARED'))
);

COMMENT ON TABLE tms.platform_tenant IS
    'Mapping from an EBIM MasterAdmin tenant to the TMS organization and company it provisioned. '
    'Written only by platform provisioning, on the owner connection.';

-- ---------------------------------------------------------------------------
-- 2. The request log, which is also the audit trail
-- ---------------------------------------------------------------------------
CREATE TABLE tms.platform_provisioning (
    id                      uuid        NOT NULL DEFAULT gen_random_uuid(),
    control_plane_tenant_id uuid        NOT NULL,
    -- One row per key. A retry after a failure updates this row rather than
    -- inserting a second one, so a transient failure never turns the key into a
    -- tombstone the caller can no longer use.
    idempotency_key         text        NOT NULL,
    -- SHA-256 over the canonical re-serialisation of the request, the same shape
    -- tms.integration_request uses. It is what lets "same key, different body"
    -- be refused instead of silently accepted.
    payload_hash            text        NOT NULL,
    -- Off by default. The request carries the tenant's administrator email and
    -- registration data; retaining it is a decision an operator makes
    -- (tms.platform.provisioning.retain-request-snapshot), not a default.
    request_snapshot        text,
    status                  text        NOT NULL,
    http_status             integer     NOT NULL,
    platform_tenant_id      uuid,
    organization_id         uuid,
    company_id              uuid,
    admin_app_user_id       uuid,
    admin_status            text,
    deployment_mode         text,
    -- The response exactly as it was returned, so replaying a key answers
    -- identically instead of re-executing. Text and not jsonb: it is read back
    -- whole and never queried into.
    response_body           text,
    error_code              text,
    -- Sanitised: a short business summary, never an exception message, a stack
    -- frame or SQL.
    error_summary           text,
    correlation_id          text        NOT NULL,
    -- Who asked, from the verified token. subject and jti are the token's own
    -- identity; actor_id and actor_role are what MasterAdmin says about the
    -- human behind it and are recorded for audit only - they authorise nothing.
    m2m_subject             text        NOT NULL,
    m2m_jti                 text        NOT NULL,
    actor_id                text,
    actor_role              text,
    received_at             timestamptz NOT NULL DEFAULT now(),
    completed_at            timestamptz NOT NULL DEFAULT now(),
    duration_ms             integer     NOT NULL DEFAULT 0,
    CONSTRAINT pk_platform_provisioning PRIMARY KEY (id),
    CONSTRAINT uq_platform_provisioning_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_platform_provisioning_tenant FOREIGN KEY (platform_tenant_id)
        REFERENCES tms.platform_tenant (id) ON DELETE RESTRICT,
    CONSTRAINT ck_platform_provisioning_status CHECK (status IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_platform_provisioning_admin_status CHECK (
        admin_status IS NULL OR admin_status IN ('PREPROVISIONED', 'LINKED', 'NOT_REQUESTED')),
    CONSTRAINT ck_platform_provisioning_deployment_mode CHECK (
        deployment_mode IS NULL OR deployment_mode IN ('SHARED')),
    CONSTRAINT ck_platform_provisioning_payload_hash_shape CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_platform_provisioning_http_status CHECK (http_status BETWEEN 100 AND 599),
    CONSTRAINT ck_platform_provisioning_duration_nonnegative CHECK (duration_ms >= 0),
    CONSTRAINT ck_platform_provisioning_idempotency_key_shape CHECK (
        btrim(idempotency_key) <> '' AND length(idempotency_key) <= 255),
    CONSTRAINT ck_platform_provisioning_correlation_not_blank CHECK (btrim(correlation_id) <> ''),
    CONSTRAINT ck_platform_provisioning_subject_not_blank CHECK (btrim(m2m_subject) <> ''),
    CONSTRAINT ck_platform_provisioning_jti_not_blank CHECK (btrim(m2m_jti) <> ''),
    -- A succeeded row names what it produced; a failed row names why. Neither
    -- shape can be written the other way round by accident.
    CONSTRAINT ck_platform_provisioning_succeeded_is_complete CHECK (
        status <> 'SUCCEEDED' OR (platform_tenant_id IS NOT NULL
            AND organization_id IS NOT NULL AND company_id IS NOT NULL
            AND response_body IS NOT NULL)),
    CONSTRAINT ck_platform_provisioning_failed_has_code CHECK (
        status <> 'FAILED' OR error_code IS NOT NULL)
);

COMMENT ON TABLE tms.platform_provisioning IS
    'One row per provisioning idempotency key: what MasterAdmin asked, what TMS answered, and the '
    'verified token identity that asked it. This is the audit trail for provisioning.';

-- Answering GET /internal/platform/v1/tenants/{controlPlaneTenantId} reads by the
-- control-plane id; the mapping is unique on it, and the log is not.
CREATE INDEX ix_platform_provisioning_control_plane
    ON tms.platform_provisioning (control_plane_tenant_id, received_at DESC);

-- ---------------------------------------------------------------------------
-- 3. Row level security
-- ---------------------------------------------------------------------------
-- Enabled on both, with the identity-table policy from V13: these rows are
-- backend-managed rather than company-filtered. They are written on the owner
-- connection, and RLS does not apply to the owner anyway (no table in this
-- schema is FORCE'd, by decision) - so the policy is the honest statement that
-- nothing here is filtered by tenant, not a control that would be doing work.
ALTER TABLE tms.platform_tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.platform_provisioning ENABLE ROW LEVEL SECURITY;

CREATE POLICY p_backend_managed ON tms.platform_tenant
    FOR ALL TO tms_app USING (true) WITH CHECK (true);

CREATE POLICY p_backend_managed ON tms.platform_provisioning
    FOR ALL TO tms_app USING (true) WITH CHECK (true);

-- The runtime role gets nothing here, and that is the whole posture of these two
-- tables. Provisioning runs before any company exists, so it never enters
-- tms_app and always writes on the owner connection; no company-scoped request
-- reads these rows. Leaving the four verbs V13's ALTER DEFAULT PRIVILEGES grants
-- at CREATE TABLE time would mean a control-plane table - which by its nature
-- lists every tenant - was readable by the role every tenant's requests run as.
-- A REVOKE is required rather than merely omitting a GRANT, because the default
-- privileges have already granted them by the time this line runs.
REVOKE ALL PRIVILEGES ON tms.platform_tenant FROM tms_app;
REVOKE ALL PRIVILEGES ON tms.platform_provisioning FROM tms_app;
