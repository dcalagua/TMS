-- ===========================================================================
-- V58 - Operational lifecycle received from EBIM MasterAdmin (lifecycle.v1, ADR-021)
-- ===========================================================================
--
-- MasterAdmin suspends a tenant (non-payment, manual, terminated) and reactivates it when the debt is
-- settled. It tells TMS through a versioned, declarative instruction of its own:
--
--     PUT /internal/platform-provisioning/tenants/{controlPlaneTenantId}/lifecycle
--
-- on the MasterAdmin security chain (ADR-012), with scopes of its own (tms:lifecycle:write / read).
-- It is NOT the entitlements channel: under D-14 ruling 1, appActive=false withdraws the commercial
-- surface and never stops operation. An operational suspension is a separate policy, and this is it.
--
-- What SUSPENDED means in TMS (enforced in Java by TenantSuspensionFilter, the single filter both
-- tenant chains carry): writes of the organization (any method other than GET/HEAD/OPTIONS, from the
-- web, the carrier portal or a partner credential) are refused with 423 tenant-suspended; reads and
-- exports go on; nothing is deleted; login and /api/v1/me keep working. ACTIVE resumes everything.
--
--   platform_tenant_lifecycle         the state applied per MasterAdmin tenant (one row; none = ACTIVE)
--   platform_tenant_lifecycle_audit   append-only trail of every PUT outcome
--
-- Deliberately NOT reused: tms.organization.active / tms.company.active. Those remove the company
-- from the caller's scope altogether (no reading, no /me entry), which is not what a suspension is.
--
-- Same exposure as V51/V56: owner connection only. tms_app holds no grant and a deny-all policy; the
-- two things a request needs - "is my organization suspended?" and the banner state for /me - are
-- answered by SECURITY DEFINER functions (section 4).

-- ---------------------------------------------------------------------------
-- 1. The applied state (durable; the last instruction keeps deciding if MasterAdmin is down)
-- ---------------------------------------------------------------------------
CREATE TABLE tms.platform_tenant_lifecycle (
    control_plane_tenant_id uuid        NOT NULL,
    organization_id         uuid        NOT NULL,
    product_code            text        NOT NULL,
    state                   text        NOT NULL,
    reason                  text,
    applied_version         bigint      NOT NULL,
    applied_at              timestamptz NOT NULL,
    -- Informative: TMS applies on receipt (contract section 3).
    effective_at            timestamptz,
    idempotency_key         text,
    correlation_id          text,
    m2m_subject             text,
    m2m_jti                 text,
    CONSTRAINT pk_platform_tenant_lifecycle PRIMARY KEY (control_plane_tenant_id),
    CONSTRAINT uq_platform_tenant_lifecycle_organization UNIQUE (organization_id),
    CONSTRAINT fk_platform_tenant_lifecycle_provisioning FOREIGN KEY (control_plane_tenant_id)
        REFERENCES tms.platform_provisioning_request (control_plane_tenant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_platform_tenant_lifecycle_organization FOREIGN KEY (organization_id)
        REFERENCES tms.organization (id) ON DELETE RESTRICT,
    CONSTRAINT ck_platform_tenant_lifecycle_product CHECK (product_code = 'tms'),
    CONSTRAINT ck_platform_tenant_lifecycle_state CHECK (state IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_platform_tenant_lifecycle_reason CHECK (
        (state = 'ACTIVE' AND reason IS NULL)
        -- IS NOT NULL is not redundant: NULL IN (...) is NULL, and a NULL check passes.
        OR (state = 'SUSPENDED' AND reason IS NOT NULL AND reason IN ('NON_PAYMENT', 'MANUAL', 'TERMINATED'))),
    CONSTRAINT ck_platform_tenant_lifecycle_version CHECK (applied_version >= 1),
    CONSTRAINT ck_platform_tenant_lifecycle_text CHECK (
        coalesce(length(idempotency_key), 0) <= 200 AND coalesce(length(correlation_id), 0) <= 200)
);

COMMENT ON TABLE tms.platform_tenant_lifecycle IS
    'Operational state (lifecycle.v1) applied per MasterAdmin tenant (V58, ADR-021). SUSPENDED refuses '
    'writes in TenantSuspensionFilter; reads go on. No row = ACTIVE. Owner connection only.';

-- ---------------------------------------------------------------------------
-- 2. Append-only audit of every PUT outcome
-- ---------------------------------------------------------------------------
CREATE TABLE tms.platform_tenant_lifecycle_audit (
    id                      uuid        NOT NULL DEFAULT gen_random_uuid(),
    occurred_at             timestamptz NOT NULL DEFAULT now(),
    control_plane_tenant_id uuid        NOT NULL,
    requested_version       bigint      NOT NULL,
    requested_state         text        NOT NULL,
    requested_reason        text,
    outcome                 text        NOT NULL,
    applied_version         bigint,
    idempotency_key         text,
    correlation_id          text,
    m2m_subject             text,
    m2m_jti                 text,
    CONSTRAINT pk_platform_tenant_lifecycle_audit PRIMARY KEY (id),
    CONSTRAINT ck_platform_tenant_lifecycle_audit_outcome CHECK (outcome IN (
        'APPLIED', 'REPLAYED', 'STALE_LIFECYCLE', 'VERSION_CONFLICT')),
    CONSTRAINT ck_platform_tenant_lifecycle_audit_state CHECK (requested_state IN ('ACTIVE', 'SUSPENDED'))
);

CREATE INDEX ix_platform_tenant_lifecycle_audit_tenant
    ON tms.platform_tenant_lifecycle_audit (control_plane_tenant_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 3. Guards that bind a hand-written statement as much as the code
-- ---------------------------------------------------------------------------
-- The version never goes back, and a row is never deleted: deleting it would silently reactivate
-- a suspended tenant. Reactivation is an ACTIVE instruction with a higher version, recorded.
CREATE FUNCTION tms.platform_tenant_lifecycle_guard()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'tms.platform_tenant_lifecycle rows are reactivated with ACTIVE, never deleted'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.control_plane_tenant_id <> OLD.control_plane_tenant_id
       OR NEW.organization_id <> OLD.organization_id THEN
        RAISE EXCEPTION 'tms.platform_tenant_lifecycle tenant keys are immutable'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.applied_version < OLD.applied_version THEN
        RAISE EXCEPTION 'lifecycle version moves forward only: % -> %', OLD.applied_version, NEW.applied_version
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION tms.platform_tenant_lifecycle_guard() FROM PUBLIC;

CREATE TRIGGER tr_platform_tenant_lifecycle_guard
    BEFORE UPDATE OR DELETE ON tms.platform_tenant_lifecycle
    FOR EACH ROW EXECUTE FUNCTION tms.platform_tenant_lifecycle_guard();

-- V56's append-only guard, reused: it names the table it fires on.
CREATE TRIGGER tr_platform_tenant_lifecycle_audit_append_only
    BEFORE UPDATE OR DELETE ON tms.platform_tenant_lifecycle_audit
    FOR EACH ROW EXECUTE FUNCTION tms.platform_entitlement_append_only();

-- ---------------------------------------------------------------------------
-- 4. Reads for the runtime role
-- ---------------------------------------------------------------------------
-- 4a. Is the organization of the company this transaction is scoped to suspended? Called by
-- TenantSuspensionFilter on every company-scoped write, on tms_app. No parameter, keyed on
-- tms.current_company_id(), like V56's commercial_access_current_company(): the runtime role learns
-- the state of its OWN organization and of no other. Unscoped or never set: no row (= ACTIVE).
CREATE FUNCTION tms.tenant_lifecycle_current_company()
    RETURNS TABLE (state text, reason text, applied_at timestamptz, applied_version bigint)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT l.state, l.reason, l.applied_at, l.applied_version
      FROM tms.company c
      JOIN tms.platform_tenant_lifecycle l ON l.organization_id = c.organization_id
     WHERE c.id = tms.current_company_id()
$$;

COMMENT ON FUNCTION tms.tenant_lifecycle_current_company() IS
    'Operational state (lifecycle.v1) of the organization of the company the transaction is scoped to. '
    'SECURITY DEFINER, no parameter: tms_app sees its own organization only (V58).';

-- 4b. The banner state for /api/v1/me, which lists every company of the caller and may run scoped
-- (tms_app) or unscoped (owner). Returns only state, reason and time - no tenant data - and Java
-- passes only the organizations of the caller's own memberships.
CREATE FUNCTION tms.tenant_lifecycle_of_organizations(p_organization_ids uuid[])
    RETURNS TABLE (organization_id uuid, state text, reason text, applied_at timestamptz)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT l.organization_id, l.state, l.reason, l.applied_at
      FROM tms.platform_tenant_lifecycle l
     WHERE l.organization_id = ANY (p_organization_ids)
$$;

COMMENT ON FUNCTION tms.tenant_lifecycle_of_organizations(uuid[]) IS
    'Operational state (lifecycle.v1) of the given organizations, for the /me banner. State only; '
    'the caller passes its own organizations (V58).';

REVOKE ALL ON FUNCTION tms.tenant_lifecycle_current_company() FROM PUBLIC;
REVOKE ALL ON FUNCTION tms.tenant_lifecycle_of_organizations(uuid[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tms.tenant_lifecycle_current_company() TO tms_app;
GRANT EXECUTE ON FUNCTION tms.tenant_lifecycle_of_organizations(uuid[]) TO tms_app;

-- ---------------------------------------------------------------------------
-- 5. Exposure: owner only (same posture as V51 and V56)
-- ---------------------------------------------------------------------------
REVOKE ALL ON tms.platform_tenant_lifecycle FROM tms_app;
REVOKE ALL ON tms.platform_tenant_lifecycle_audit FROM tms_app;

ALTER TABLE tms.platform_tenant_lifecycle ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.platform_tenant_lifecycle_audit ENABLE ROW LEVEL SECURITY;

CREATE POLICY p_platform_tenant_lifecycle_no_runtime_access ON tms.platform_tenant_lifecycle
    FOR ALL TO tms_app USING (false) WITH CHECK (false);
CREATE POLICY p_platform_tenant_lifecycle_no_runtime_access ON tms.platform_tenant_lifecycle_audit
    FOR ALL TO tms_app USING (false) WITH CHECK (false);

-- The Supabase API roles never had schema access (V4); repeated so the statement stands on its own
-- if a platform recreates the roles.
DO $$
DECLARE
    api_role text;
    lifecycle_table text;
BEGIN
    FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated', 'service_role']
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
            FOREACH lifecycle_table IN ARRAY ARRAY['platform_tenant_lifecycle', 'platform_tenant_lifecycle_audit']
            LOOP
                EXECUTE format('REVOKE ALL ON tms.%I FROM %I', lifecycle_table, api_role);
            END LOOP;
            EXECUTE format('REVOKE ALL ON FUNCTION tms.tenant_lifecycle_current_company() FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON FUNCTION tms.tenant_lifecycle_of_organizations(uuid[]) FROM %I',
                    api_role);
        END IF;
    END LOOP;
END;
$$;
