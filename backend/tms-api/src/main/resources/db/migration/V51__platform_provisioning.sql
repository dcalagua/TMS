-- ===========================================================================
-- V51 - Tenant provisioning requested by EBIM MasterAdmin (GENERIC contract v1)
-- ===========================================================================
--
-- MasterAdmin is the EBIM control plane. When a customer contracts TMS there, it calls
-- POST /internal/platform-provisioning/tenants with an asymmetric (ES256) machine token and the
-- suite-wide GENERIC body. TMS answers by creating, in ONE transaction, the rows a tenant needs
-- to exist here:
--
--     tms.organization    <- the tenant boundary (ADR-003); code = MasterAdmin tenantCode
--     tms.company         <- MasterAdmin company, or a default one when MasterAdmin sends none
--     tms.company_settings (only when MasterAdmin states a country)
--     tms.app_user        <- adminEmail; reused when that person already has a TMS profile
--     tms.membership      <- organization-wide (company_id NULL) ...
--     tms.membership_role <- ... holding ORGANIZATION_ADMIN
--
-- plus the two tables below. The Supabase Auth account is NOT created and app_user.auth_user_id is
-- NOT set: the administrator is PREPROVISIONED, exactly like a person added by qas_seed.sql or by
-- the user administration screen before they first sign in.
--
-- See docs/platform-provisioning/MASTERADMIN_GENERIC_CONTRACT.md and ADR-012.
--
-- ---------------------------------------------------------------------------
-- Why neither table is company-scoped
-- ---------------------------------------------------------------------------
--
-- Both are written before the company they describe exists, by a caller that is not a member of
-- any company - the same situation as the identity tables in V13 section 5. They are read and
-- written only by the backend's owning connection (the machine chain never carries a CompanyScope,
-- so TenantScopedDataSource never switches to tms_app for it). tms_app is given NO privilege and a
-- deny-everything policy: nothing a company-scoped request does can reach them.

-- ---------------------------------------------------------------------------
-- 1. platform_provisioning_request - one row per tenant MasterAdmin provisioned here
-- ---------------------------------------------------------------------------
CREATE TABLE tms.platform_provisioning_request (
    id                      uuid        NOT NULL DEFAULT gen_random_uuid(),
    -- Idempotency: the key MasterAdmin keeps identical across retries, and the SHA-256 of the
    -- normalized functional content of the request. Neither the key, nor the correlation id, nor
    -- the token, nor MasterAdmin's requestId are part of the hash - otherwise two identical
    -- retries would hash differently and every retry would be a conflict.
    idempotency_key         text        NOT NULL,
    request_hash            text        NOT NULL,
    -- The MasterAdmin tenant (masterAdmin.tenantId). One TMS tenant per MasterAdmin tenant.
    control_plane_tenant_id uuid        NOT NULL,
    organization_id         uuid        NOT NULL,
    -- Not a tenant filter column: it records which company this provisioning created. See the
    -- COMPANY_COLUMN_WITHOUT_TENANT_POLICY entry in SchemaExposureIntegrationTest.
    company_id              uuid        NOT NULL,
    admin_app_user_id       uuid        NOT NULL,
    admin_membership_id     uuid        NOT NULL,
    -- true when adminEmail already had a TMS profile and it was reused instead of created.
    admin_profile_reused    boolean     NOT NULL,
    status                  text        NOT NULL DEFAULT 'ACTIVE',
    -- Informational copies of the contract, kept for support and audit. TMS makes no decision
    -- on any of them (see the contract document, section "IGNORED").
    product_code            text        NOT NULL,
    contract_version        text        NOT NULL,
    tenant_code             text        NOT NULL,
    environment             text,
    tenant_type             text,
    deployment_mode         text,
    plan_code               text,
    -- Who asked. m2m_jti identifies the token that authorized the call; the token itself, the
    -- Authorization header and any key material are never stored.
    correlation_id          text,
    m2m_subject             text        NOT NULL,
    m2m_jti                 text        NOT NULL,
    -- As SIGNED by MasterAdmin in the token. Audit only, never authorization.
    actor_id                text,
    actor_role              text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_platform_provisioning_request PRIMARY KEY (id),
    CONSTRAINT uq_platform_provisioning_request_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uq_platform_provisioning_request_control_plane_tenant UNIQUE (control_plane_tenant_id),
    CONSTRAINT uq_platform_provisioning_request_organization UNIQUE (organization_id),
    CONSTRAINT fk_platform_provisioning_request_organization FOREIGN KEY (organization_id)
        REFERENCES tms.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_platform_provisioning_request_company_in_organization FOREIGN KEY (company_id, organization_id)
        REFERENCES tms.company (id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_platform_provisioning_request_admin_app_user FOREIGN KEY (admin_app_user_id)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_platform_provisioning_request_admin_membership FOREIGN KEY (admin_membership_id)
        REFERENCES tms.membership (id) ON DELETE RESTRICT,
    CONSTRAINT ck_platform_provisioning_request_idempotency_key_shape
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{8,200}$'),
    CONSTRAINT ck_platform_provisioning_request_hash_shape CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    -- Only completed provisionings are persisted: a failure rolls the whole transaction back.
    CONSTRAINT ck_platform_provisioning_request_status CHECK (status IN ('ACTIVE')),
    CONSTRAINT ck_platform_provisioning_request_tenant_code_shape
        CHECK (tenant_code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$')
);

COMMENT ON TABLE tms.platform_provisioning_request IS
    'Tenants created by EBIM MasterAdmin through /internal/platform-provisioning (V51). The only '
    'place where the MasterAdmin tenant id and the TMS organization/company ids appear together. '
    'Write-once: never updated or deleted. Not company-scoped; owner connection only.';
COMMENT ON COLUMN tms.platform_provisioning_request.control_plane_tenant_id IS
    'MasterAdmin masterAdmin.tenantId. Not a TMS id and never used as one.';
COMMENT ON COLUMN tms.platform_provisioning_request.request_hash IS
    'SHA-256 (hex) of the normalized functional request. Excludes the idempotency key, the '
    'correlation id, MasterAdmin requestId and the token.';

-- ---------------------------------------------------------------------------
-- 2. platform_provisioning_audit - every call, accepted or not
-- ---------------------------------------------------------------------------
CREATE TABLE tms.platform_provisioning_audit (
    id                      uuid        NOT NULL DEFAULT gen_random_uuid(),
    occurred_at             timestamptz NOT NULL DEFAULT now(),
    operation               text        NOT NULL,
    result                  text        NOT NULL,
    http_status             integer     NOT NULL,
    error_code              text,
    control_plane_tenant_id uuid,
    idempotency_key         text,
    -- No foreign key on purpose: a rejected or failed call is audited in its own transaction,
    -- after the provisioning transaction rolled back, so there may be no row to point at.
    provisioning_id         uuid,
    correlation_id          text,
    m2m_subject             text,
    m2m_jti                 text,
    actor_id                text,
    actor_role              text,
    CONSTRAINT pk_platform_provisioning_audit PRIMARY KEY (id),
    CONSTRAINT ck_platform_provisioning_audit_operation
        CHECK (operation IN ('CREATE_TENANT', 'GET_TENANT_STATUS')),
    CONSTRAINT ck_platform_provisioning_audit_result
        CHECK (result IN ('CREATED', 'REPLAYED', 'FOUND', 'REJECTED', 'CONFLICT', 'ERROR')),
    CONSTRAINT ck_platform_provisioning_audit_http_status CHECK (http_status BETWEEN 100 AND 599)
);

CREATE INDEX ix_platform_provisioning_audit_control_plane_tenant
    ON tms.platform_provisioning_audit (control_plane_tenant_id, occurred_at DESC);

COMMENT ON TABLE tms.platform_provisioning_audit IS
    'Append-only trail of every MasterAdmin provisioning call (V51): created, replayed, found, '
    'rejected, conflicting or failed. Never stores the token, the Authorization header or the body.';

-- ---------------------------------------------------------------------------
-- 3. Write-once, enforced by the database
-- ---------------------------------------------------------------------------
-- A REVOKE cannot do this here: the only role that writes these tables is the owner, and an owner
-- keeps every privilege. A trigger binds the owner too.
CREATE FUNCTION tms.platform_provisioning_write_once()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'tms.% is write-once: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

REVOKE ALL ON FUNCTION tms.platform_provisioning_write_once() FROM PUBLIC;

CREATE TRIGGER tr_platform_provisioning_request_write_once
    BEFORE UPDATE OR DELETE ON tms.platform_provisioning_request
    FOR EACH ROW EXECUTE FUNCTION tms.platform_provisioning_write_once();

CREATE TRIGGER tr_platform_provisioning_audit_write_once
    BEFORE UPDATE OR DELETE ON tms.platform_provisioning_audit
    FOR EACH ROW EXECUTE FUNCTION tms.platform_provisioning_write_once();

-- ---------------------------------------------------------------------------
-- 4. Exposure: owner only
-- ---------------------------------------------------------------------------
-- V13's ALTER DEFAULT PRIVILEGES granted tms_app the four verbs when the tables were created
-- above; they are taken back here. The policy is a deny-all for tms_app so that RLS is not
-- "enabled with no policy" (SchemaExposureIntegrationTest) and so that even a future grant by
-- mistake would still read zero rows.
REVOKE ALL ON tms.platform_provisioning_request FROM tms_app;
REVOKE ALL ON tms.platform_provisioning_audit FROM tms_app;

ALTER TABLE tms.platform_provisioning_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.platform_provisioning_audit ENABLE ROW LEVEL SECURITY;

CREATE POLICY p_platform_provisioning_no_runtime_access ON tms.platform_provisioning_request
    FOR ALL TO tms_app USING (false) WITH CHECK (false);
CREATE POLICY p_platform_provisioning_no_runtime_access ON tms.platform_provisioning_audit
    FOR ALL TO tms_app USING (false) WITH CHECK (false);

-- The Supabase API roles never had schema access (V4); repeated for these two tables so the
-- statement stands on its own if a platform recreates the roles.
DO $$
DECLARE
    api_role text;
BEGIN
    FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated', 'service_role']
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
            EXECUTE format('REVOKE ALL ON tms.platform_provisioning_request FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON tms.platform_provisioning_audit FROM %I', api_role);
        END IF;
    END LOOP;
END;
$$;
