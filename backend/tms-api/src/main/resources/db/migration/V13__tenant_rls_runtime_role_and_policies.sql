-- TMS by EBIM - V13 tenant Row Level Security.
--
-- Supersedes the "RLS enabled, no policies" posture of V4 for business data. Reasoning and
-- the rejected alternatives: docs/architecture/ADR-005-tenant-rls-runtime-role.md and
-- docs/security/RLS_STRATEGY.md.
--
-- Strategy in one line: the backend keeps its owner connection, but every request-scoped
-- transaction drops into the non-owner role `tms_app` with the caller's company published in
-- a session setting, so PostgreSQL itself refuses a cross-company row even if a repository
-- query ever forgets its WHERE clause.
--
-- Why a role instead of FORCE ROW LEVEL SECURITY on the owner:
--   * Flyway runs as the owner. Forcing RLS would subject every future data migration to the
--     policies, which is a trap rather than a control.
--   * Integration tests seed business rows as the owner. Forcing RLS would break them into
--     "set the session variable first" ceremony that tests nothing.
--   * `tms_app` is NOLOGIN and has no password, so this adds no credential anywhere. It is
--     reachable only through SET ROLE from a connection that already authenticated.

-- ---------------------------------------------------------------------------
-- 1. The runtime role
-- ---------------------------------------------------------------------------
-- NOLOGIN on purpose: nothing connects *as* tms_app. The application connects as the schema
-- owner exactly as before and then calls `SET LOCAL ROLE tms_app` for the duration of the
-- transaction. That keeps role creation credential-free and therefore safe in a versioned
-- migration, which is what docs/security/RLS_STRATEGY.md section 4 requires.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tms_app') THEN
        CREATE ROLE tms_app NOLOGIN;
        RAISE NOTICE 'tms: created runtime role tms_app';
    END IF;
END;
$$;

-- The grant is what actually makes SET ROLE possible, and it is easy to think it is implied.
-- It is not: since PostgreSQL 16 the creator of a role receives ADMIN OPTION but *not* the
-- SET option, so without this line the backend fails at runtime with
-- "permission denied to set role tms_app" while every migration still applies cleanly.
--
-- It is invisible on a superuser connection - a superuser may SET ROLE to anything - which is
-- exactly why a Testcontainers run can pass while a real Supabase project, where `postgres` is
-- not a superuser, cannot serve a single company-scoped request. SchemaExposureIntegrationTest
-- asserts the SET option explicitly so the difference cannot hide again.
--
-- CURRENT_USER is the role applying the migration, which in this architecture is also the role
-- the backend connects as (docs/security/RLS_STRATEGY.md section 4). A deployment that splits
-- the two must grant tms_app to the runtime role as well.
DO $$
BEGIN
    EXECUTE format('GRANT tms_app TO %I WITH SET TRUE', CURRENT_USER);
END;
$$;

GRANT USAGE ON SCHEMA tms TO tms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA tms TO tms_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA tms TO tms_app;

-- Flyway owns future objects too; without this a table added in V14 would be invisible to the
-- runtime role and the failure would surface as an empty list, not as an error.
ALTER DEFAULT PRIVILEGES IN SCHEMA tms
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tms_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA tms
    GRANT USAGE, SELECT ON SEQUENCES TO tms_app;

-- The privilege grant is not the control - the policies below are. A grant without a policy
-- on an RLS-enabled table still reads zero rows.

-- ---------------------------------------------------------------------------
-- 2. The tenant of the current transaction
-- ---------------------------------------------------------------------------
-- `tms.company_id` is set by the backend with SET LOCAL, so it is scoped to the transaction
-- and cannot leak to the next user of a pooled connection.
--
-- The `true` second argument makes current_setting return NULL instead of raising when the
-- variable was never set. NULL is the correct "no tenant selected" value: every policy below
-- compares with `=`, and `company_id = NULL` is never true, so an unscoped transaction reads
-- and writes nothing. Fails closed, which is the whole point.
CREATE FUNCTION tms.current_company_id() RETURNS uuid
    LANGUAGE sql
    STABLE
    -- Not SECURITY DEFINER: it reads a session setting and needs no privilege of its own.
    -- An empty search_path keeps it independent of the caller's.
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT NULLIF(current_setting('tms.company_id', true), '')::uuid
$$;

COMMENT ON FUNCTION tms.current_company_id() IS
    'The company the current transaction is scoped to, published by the backend with '
    'SET LOCAL tms.company_id. NULL when unscoped, which denies every tenant policy.';

REVOKE ALL ON FUNCTION tms.current_company_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tms.current_company_id() TO tms_app;

-- ---------------------------------------------------------------------------
-- 3. Tenant policies on company-scoped business tables
-- ---------------------------------------------------------------------------
-- One policy per table, FOR ALL, with both USING (rows the transaction may read, update or
-- delete) and WITH CHECK (rows it may write). A USING-only policy would let a caller insert a
-- row into another company and then be unable to see it - a silent write leak.
--
-- Granted TO tms_app only. `anon`, `authenticated` and `service_role` gain nothing here: they
-- still have no grant and no policy, so V4's deny-by-default for the Data API is untouched.
DO $$
DECLARE
    scoped_table text;
BEGIN
    FOREACH scoped_table IN ARRAY ARRAY[
        'origin', 'zone', 'destination', 'frequency', 'route', 'route_stop',
        'carrier', 'vehicle_type', 'vehicle', 'transport_order',
        'planning_run', 'trip', 'trip_stop', 'trip_order_assignment']
    LOOP
        EXECUTE format(
            'CREATE POLICY p_tenant_company_scope ON tms.%I
                 FOR ALL TO tms_app
                 USING (company_id = tms.current_company_id())
                 WITH CHECK (company_id = tms.current_company_id())',
            scoped_table);
    END LOOP;
END;
$$;

-- ---------------------------------------------------------------------------
-- 4. Child tables that inherit the tenant through their parent
-- ---------------------------------------------------------------------------
-- These three carry no company_id of their own; the column would be denormalised state that
-- could drift from the parent. They are scoped by an EXISTS against the parent, which is
-- itself policed, so a child row is reachable exactly when its parent is.
CREATE POLICY p_tenant_company_scope ON tms.frequency_weekly_rule
    FOR ALL TO tms_app
    USING (EXISTS (SELECT 1 FROM tms.frequency f
                    WHERE f.id = frequency_weekly_rule.frequency_id
                      AND f.company_id = tms.current_company_id()))
    WITH CHECK (EXISTS (SELECT 1 FROM tms.frequency f
                         WHERE f.id = frequency_weekly_rule.frequency_id
                           AND f.company_id = tms.current_company_id()));

CREATE POLICY p_tenant_company_scope ON tms.frequency_exception
    FOR ALL TO tms_app
    USING (EXISTS (SELECT 1 FROM tms.frequency f
                    WHERE f.id = frequency_exception.frequency_id
                      AND f.company_id = tms.current_company_id()))
    WITH CHECK (EXISTS (SELECT 1 FROM tms.frequency f
                         WHERE f.id = frequency_exception.frequency_id
                           AND f.company_id = tms.current_company_id()));

CREATE POLICY p_tenant_company_scope ON tms.transport_order_line
    FOR ALL TO tms_app
    USING (EXISTS (SELECT 1 FROM tms.transport_order o
                    WHERE o.id = transport_order_line.order_id
                      AND o.company_id = tms.current_company_id()))
    WITH CHECK (EXISTS (SELECT 1 FROM tms.transport_order o
                         WHERE o.id = transport_order_line.order_id
                           AND o.company_id = tms.current_company_id()));

-- ---------------------------------------------------------------------------
-- 5. Identity and authorization catalogue: readable, deliberately not tenant-filtered
-- ---------------------------------------------------------------------------
-- These tables cannot carry a tenant policy keyed on tms.company_id, because they are read
-- *before* a company exists: PrincipalResolutionService resolves auth user -> app_user ->
-- membership in order to decide which companies the caller may select at all. A policy
-- requiring the company would make authentication impossible - the classic chicken and egg.
--
-- So the policy is explicit and honest: tms_app may work with them, and the tenant rule for
-- this data stays where it already is - Spring Boot, which filters by the resolved
-- memberships (ADR-003). This is not "allow all authenticated" theatre: the grant is to a
-- role that only exists behind an authenticated backend connection, and the Supabase API
-- roles remain denied by having neither privilege nor policy.
DO $$
DECLARE
    identity_table text;
BEGIN
    FOREACH identity_table IN ARRAY ARRAY[
        'app_user', 'organization', 'company', 'membership', 'membership_role',
        'role', 'permission', 'role_permission']
    LOOP
        EXECUTE format(
            'CREATE POLICY p_backend_managed ON tms.%I FOR ALL TO tms_app
                 USING (true) WITH CHECK (true)',
            identity_table);
    END LOOP;
END;
$$;

COMMENT ON SCHEMA tms IS
    'TMS by EBIM application schema. Owned by Flyway (ADR-002). Backend-only: not listed in '
    'supabase/config.toml [api].schemas, no privileges for anon/authenticated/service_role. '
    'RLS is enabled on every table; business tables carry tenant policies for the non-owner '
    'runtime role tms_app, keyed on tms.current_company_id() (ADR-005).';
