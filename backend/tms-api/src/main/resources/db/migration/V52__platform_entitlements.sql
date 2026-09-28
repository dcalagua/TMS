-- ===========================================================================
-- V52 - Commercial entitlements received from EBIM MasterAdmin (ebim.entitlements/v1)
-- ===========================================================================
--
-- MasterAdmin is the canonical source of what a customer CONTRACTED. It sends TMS a COMPLETE,
-- versioned snapshot per tenant to
--
--     PUT /internal/platform-provisioning/tenants/{controlPlaneTenantId}/entitlements
--
-- on the same MasterAdmin security chain as tenant creation (V51, ADR-012), with scopes of its own
-- (tms:entitlements:write / tms:entitlements:read). TMS cannot ask MasterAdmin on every request - if
-- MasterAdmin is down, trucks still leave - so the snapshot is stored HERE, durably, and every
-- commercial decision reads only this local copy (last good snapshot, no expiry). See ADR-017 and
-- docs/platform-provisioning/ENTITLEMENTS.md.
--
-- What TMS acts on today is appActive alone. TMS registers no sellable capability, limit or
-- allowance, and no RBAC permission becomes a paid capability.
--
--   platform_entitlement_applied      the last applied snapshot, one row per MasterAdmin tenant
--   platform_entitlement_audit        append-only trail of every PUT outcome
--   platform_entitlement_jti          single-use jti on the entitlements routes
--   platform_entitlement_mode         LEGACY / SHADOW / DUAL_READ / PRIMARY, product-wide or per tenant
--   platform_entitlement_mode_event   append-only history of mode changes (written by trigger)
--   platform_entitlement_shadow_diff  legacy decision vs snapshot decision, while the snapshot does
--                                     not decide alone
--
-- What this migration does NOT do:
--   * it does not touch V51's tables (INV-1): it reads platform_provisioning_request to resolve the
--     tenant and holds a foreign key to it, nothing else;
--   * it stores no price, amount, currency, credential or token - the snapshot carries none and the
--     receiver refuses one that does;
--   * it seeds ONE row: the product mode SHADOW. In SHADOW the snapshot is stored and compared and
--     decides nothing, so applying this migration changes the behaviour of no tenant.
--
-- Every table is owner-connection only, exactly like V51: tms_app holds no privilege and a deny-all
-- policy. The one thing a company-scoped request needs - may my organization operate? - is answered
-- by tms.commercial_access_current_company() (section 6), which reveals only the caller's own row.

-- ---------------------------------------------------------------------------
-- 1. The last applied snapshot (durable last-good)
-- ---------------------------------------------------------------------------
CREATE TABLE tms.platform_entitlement_applied (
    control_plane_tenant_id uuid        NOT NULL,
    organization_id         uuid        NOT NULL,
    product_code            text        NOT NULL,
    -- The whole document as received and verified by checksum.
    snapshot                jsonb       NOT NULL,
    snapshot_version        bigint      NOT NULL,
    checksum                text        NOT NULL,
    status                  text        NOT NULL,
    app_active              boolean     NOT NULL,
    -- Codes TMS does not know: stored, reported, never granted.
    unknown_capabilities    text[]      NOT NULL DEFAULT '{}',
    applied_at              timestamptz NOT NULL,
    correlation_id          text,
    m2m_subject             text,
    m2m_jti                 text,
    CONSTRAINT pk_platform_entitlement_applied PRIMARY KEY (control_plane_tenant_id),
    CONSTRAINT uq_platform_entitlement_applied_organization UNIQUE (organization_id),
    CONSTRAINT fk_platform_entitlement_applied_provisioning FOREIGN KEY (control_plane_tenant_id)
        REFERENCES tms.platform_provisioning_request (control_plane_tenant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_platform_entitlement_applied_organization FOREIGN KEY (organization_id)
        REFERENCES tms.organization (id) ON DELETE RESTRICT,
    CONSTRAINT ck_platform_entitlement_applied_version CHECK (snapshot_version >= 1),
    CONSTRAINT ck_platform_entitlement_applied_checksum CHECK (checksum ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_platform_entitlement_applied_status CHECK (status IN ('APPLIED', 'APPLIED_WITH_WARNINGS')),
    CONSTRAINT ck_platform_entitlement_applied_product CHECK (product_code = 'tms')
);

COMMENT ON TABLE tms.platform_entitlement_applied IS
    'Last ebim.entitlements/v1 snapshot applied per MasterAdmin tenant (V52). The only commercial '
    'state TMS enforces from; read locally, never fetched. Owner connection only.';

-- ---------------------------------------------------------------------------
-- 2. Append-only audit of every PUT outcome
-- ---------------------------------------------------------------------------
CREATE TABLE tms.platform_entitlement_audit (
    id                      uuid        NOT NULL DEFAULT gen_random_uuid(),
    occurred_at             timestamptz NOT NULL DEFAULT now(),
    -- No foreign key: a refused call may concern a tenant that is not provisioned here.
    control_plane_tenant_id uuid,
    snapshot_version        bigint,
    checksum                text,
    outcome                 text        NOT NULL,
    correlation_id          text,
    m2m_subject             text,
    m2m_jti                 text,
    detail                  jsonb       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT pk_platform_entitlement_audit PRIMARY KEY (id),
    CONSTRAINT ck_platform_entitlement_audit_outcome CHECK (outcome IN (
        'APPLIED', 'APPLIED_WITH_WARNINGS', 'REPLAYED', 'STALE_SNAPSHOT', 'VERSION_CONFLICT'))
);

CREATE INDEX ix_platform_entitlement_audit_tenant
    ON tms.platform_entitlement_audit (control_plane_tenant_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 3. Single-use jti (CCP spec 8.1). Tenant creation keeps its own rule (V51, INV-1).
-- ---------------------------------------------------------------------------
CREATE TABLE tms.platform_entitlement_jti (
    issuer      text        NOT NULL,
    jti         text        NOT NULL,
    expires_at  timestamptz NOT NULL,
    seen_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_platform_entitlement_jti PRIMARY KEY (issuer, jti)
);

CREATE INDEX ix_platform_entitlement_jti_expires_at ON tms.platform_entitlement_jti (expires_at);

-- ---------------------------------------------------------------------------
-- 4. Enforcement mode (CCP spec 15.1): product-wide, optionally per MasterAdmin tenant
-- ---------------------------------------------------------------------------
CREATE TABLE tms.platform_entitlement_mode (
    scope_key   text        NOT NULL,
    mode        text        NOT NULL,
    reason      text        NOT NULL,
    updated_by  text        NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_platform_entitlement_mode PRIMARY KEY (scope_key),
    CONSTRAINT ck_platform_entitlement_mode_mode CHECK (mode IN ('LEGACY', 'SHADOW', 'DUAL_READ', 'PRIMARY')),
    CONSTRAINT ck_platform_entitlement_mode_scope CHECK (
        scope_key = 'PRODUCT'
        OR scope_key ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_platform_entitlement_mode_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT ck_platform_entitlement_mode_updated_by CHECK (length(btrim(updated_by)) > 0)
);

CREATE TABLE tms.platform_entitlement_mode_event (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    scope_key   text        NOT NULL,
    from_mode   text,
    to_mode     text        NOT NULL,
    reason      text        NOT NULL,
    updated_by  text        NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_platform_entitlement_mode_event PRIMARY KEY (id)
);

-- ---------------------------------------------------------------------------
-- 5. Shadow differences
-- ---------------------------------------------------------------------------
CREATE TABLE tms.platform_entitlement_shadow_diff (
    id                      uuid        NOT NULL DEFAULT gen_random_uuid(),
    control_plane_tenant_id uuid        NOT NULL,
    organization_id         uuid        NOT NULL,
    kind                    text        NOT NULL,
    mode                    text        NOT NULL,
    legacy_decision         boolean     NOT NULL,
    snapshot_decision       boolean     NOT NULL,
    snapshot_version        bigint      NOT NULL,
    detected_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_platform_entitlement_shadow_diff PRIMARY KEY (id),
    CONSTRAINT ck_platform_entitlement_shadow_diff_kind CHECK (kind IN ('APP_ACTIVE')),
    CONSTRAINT ck_platform_entitlement_shadow_diff_mode CHECK (mode IN ('SHADOW', 'DUAL_READ')),
    CONSTRAINT ck_platform_entitlement_shadow_diff_differs CHECK (legacy_decision <> snapshot_decision)
);

CREATE INDEX ix_platform_entitlement_shadow_diff_tenant
    ON tms.platform_entitlement_shadow_diff (control_plane_tenant_id, detected_at DESC);

-- ---------------------------------------------------------------------------
-- 6. May the current company's organization operate? (for the runtime role)
-- ---------------------------------------------------------------------------
-- The commercial access filter runs after the company scope is bound, so its connection is already
-- tms_app, which can read none of the tables above. SECURITY DEFINER is the narrow way through: no
-- parameter, keyed on tms.current_company_id() - the company this transaction is scoped to - so the
-- runtime role learns the three facts of its OWN organization and of no other. The decision itself
-- stays in Java (CommercialAccess). Unscoped (NULL company) it returns no row.
CREATE FUNCTION tms.commercial_access_current_company()
    RETURNS TABLE (under_control_plane boolean, enforcement_mode text, app_active boolean)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT r.control_plane_tenant_id IS NOT NULL,
           COALESCE(tenant_mode.mode, product_mode.mode, 'LEGACY'),
           a.app_active
      FROM tms.company c
      LEFT JOIN tms.platform_provisioning_request r ON r.organization_id = c.organization_id
      LEFT JOIN tms.platform_entitlement_applied a ON a.control_plane_tenant_id = r.control_plane_tenant_id
      LEFT JOIN tms.platform_entitlement_mode tenant_mode
             ON tenant_mode.scope_key = r.control_plane_tenant_id::text
      LEFT JOIN tms.platform_entitlement_mode product_mode ON product_mode.scope_key = 'PRODUCT'
     WHERE c.id = tms.current_company_id()
$$;

COMMENT ON FUNCTION tms.commercial_access_current_company() IS
    'Commercial access facts (under control plane, mode, appActive) of the organization of the '
    'company the transaction is scoped to. SECURITY DEFINER, no parameter: tms_app sees its own '
    'organization only (V52).';

REVOKE ALL ON FUNCTION tms.commercial_access_current_company() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tms.commercial_access_current_company() TO tms_app;

-- ---------------------------------------------------------------------------
-- 7. Mode changes: one step at a time, always recorded
-- ---------------------------------------------------------------------------
-- A mode is changed by an operator (after GATE C, never by the API). The rule "one step forwards or
-- back" and the history are enforced here so that they bind a hand-written UPDATE as much as code.
CREATE FUNCTION tms.platform_entitlement_mode_guard()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    ladder constant text[] := ARRAY['LEGACY', 'SHADOW', 'DUAL_READ', 'PRIMARY'];
    previous text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'tms.platform_entitlement_mode rows are stepped back, never deleted'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.scope_key <> 'PRODUCT' THEN
        -- A tenant override starts from the mode that tenant has today: the product's.
        SELECT m.mode INTO previous FROM tms.platform_entitlement_mode m WHERE m.scope_key = 'PRODUCT';
        previous := COALESCE(previous, 'LEGACY');
        IF abs(array_position(ladder, NEW.mode) - array_position(ladder, previous)) > 1 THEN
            RAISE EXCEPTION 'entitlement mode moves one step at a time: % -> % is not allowed', previous, NEW.mode
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        previous := OLD.mode;
        IF NEW.scope_key <> OLD.scope_key THEN
            RAISE EXCEPTION 'tms.platform_entitlement_mode.scope_key is immutable'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.mode <> OLD.mode
           AND abs(array_position(ladder, NEW.mode) - array_position(ladder, OLD.mode)) <> 1 THEN
            RAISE EXCEPTION 'entitlement mode moves one step at a time: % -> % is not allowed', OLD.mode, NEW.mode
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    INSERT INTO tms.platform_entitlement_mode_event (scope_key, from_mode, to_mode, reason, updated_by)
    VALUES (NEW.scope_key, previous, NEW.mode, NEW.reason, NEW.updated_by);
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION tms.platform_entitlement_mode_guard() FROM PUBLIC;

CREATE TRIGGER tr_platform_entitlement_mode_guard
    BEFORE INSERT OR UPDATE OR DELETE ON tms.platform_entitlement_mode
    FOR EACH ROW EXECUTE FUNCTION tms.platform_entitlement_mode_guard();

-- The seed: SHADOW for the product. Stored and compared, deciding nothing.
INSERT INTO tms.platform_entitlement_mode (scope_key, mode, reason, updated_by)
VALUES ('PRODUCT', 'SHADOW', 'CCP phase 12: the snapshot is stored and compared; it decides nothing', 'V52');

-- ---------------------------------------------------------------------------
-- 8. Append-only trails, enforced for the owner too
-- ---------------------------------------------------------------------------
CREATE FUNCTION tms.platform_entitlement_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'tms.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

REVOKE ALL ON FUNCTION tms.platform_entitlement_append_only() FROM PUBLIC;

CREATE TRIGGER tr_platform_entitlement_audit_append_only
    BEFORE UPDATE OR DELETE ON tms.platform_entitlement_audit
    FOR EACH ROW EXECUTE FUNCTION tms.platform_entitlement_append_only();

CREATE TRIGGER tr_platform_entitlement_mode_event_append_only
    BEFORE UPDATE OR DELETE ON tms.platform_entitlement_mode_event
    FOR EACH ROW EXECUTE FUNCTION tms.platform_entitlement_append_only();

CREATE TRIGGER tr_platform_entitlement_shadow_diff_append_only
    BEFORE UPDATE OR DELETE ON tms.platform_entitlement_shadow_diff
    FOR EACH ROW EXECUTE FUNCTION tms.platform_entitlement_append_only();

-- ---------------------------------------------------------------------------
-- 9. Exposure: owner only (same posture as V51)
-- ---------------------------------------------------------------------------
REVOKE ALL ON tms.platform_entitlement_applied FROM tms_app;
REVOKE ALL ON tms.platform_entitlement_audit FROM tms_app;
REVOKE ALL ON tms.platform_entitlement_jti FROM tms_app;
REVOKE ALL ON tms.platform_entitlement_mode FROM tms_app;
REVOKE ALL ON tms.platform_entitlement_mode_event FROM tms_app;
REVOKE ALL ON tms.platform_entitlement_shadow_diff FROM tms_app;

ALTER TABLE tms.platform_entitlement_applied ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.platform_entitlement_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.platform_entitlement_jti ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.platform_entitlement_mode ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.platform_entitlement_mode_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.platform_entitlement_shadow_diff ENABLE ROW LEVEL SECURITY;

CREATE POLICY p_platform_entitlement_no_runtime_access ON tms.platform_entitlement_applied
    FOR ALL TO tms_app USING (false) WITH CHECK (false);
CREATE POLICY p_platform_entitlement_no_runtime_access ON tms.platform_entitlement_audit
    FOR ALL TO tms_app USING (false) WITH CHECK (false);
CREATE POLICY p_platform_entitlement_no_runtime_access ON tms.platform_entitlement_jti
    FOR ALL TO tms_app USING (false) WITH CHECK (false);
CREATE POLICY p_platform_entitlement_no_runtime_access ON tms.platform_entitlement_mode
    FOR ALL TO tms_app USING (false) WITH CHECK (false);
CREATE POLICY p_platform_entitlement_no_runtime_access ON tms.platform_entitlement_mode_event
    FOR ALL TO tms_app USING (false) WITH CHECK (false);
CREATE POLICY p_platform_entitlement_no_runtime_access ON tms.platform_entitlement_shadow_diff
    FOR ALL TO tms_app USING (false) WITH CHECK (false);

-- The Supabase API roles never had schema access (V4); repeated so the statement stands on its own
-- if a platform recreates the roles.
DO $$
DECLARE
    api_role text;
    entitlement_table text;
BEGIN
    FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated', 'service_role']
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
            FOREACH entitlement_table IN ARRAY ARRAY['platform_entitlement_applied', 'platform_entitlement_audit',
                    'platform_entitlement_jti', 'platform_entitlement_mode', 'platform_entitlement_mode_event',
                    'platform_entitlement_shadow_diff']
            LOOP
                EXECUTE format('REVOKE ALL ON tms.%I FROM %I', entitlement_table, api_role);
            END LOOP;
            EXECUTE format('REVOKE ALL ON FUNCTION tms.commercial_access_current_company() FROM %I', api_role);
        END IF;
    END LOOP;
END;
$$;
