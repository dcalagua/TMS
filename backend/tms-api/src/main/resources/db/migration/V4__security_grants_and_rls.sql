-- TMS by EBIM - V4 database-level access control.
--
-- Strategy in one line: the `tms` schema has no HTTP surface and no privileges for anyone
-- but the application role, and RLS is enabled everywhere so that any future exposure
-- fails closed instead of open. Full reasoning: docs/security/RLS_STRATEGY.md.
--
-- This migration never weakens security to make tests simpler, and it never adds a
-- permissive "allow all authenticated" policy to look compliant. A table that is
-- deliberately backend-only gets its exposure decision documented instead.

-- ---------------------------------------------------------------------------
-- 1. Remove the implicit privileges PostgreSQL hands to PUBLIC
-- ---------------------------------------------------------------------------
-- Tables never get default PUBLIC privileges, but functions do (EXECUTE), and PUBLIC
-- includes every Supabase role. The revokes below are unconditional because PUBLIC always
-- exists, which keeps the statement portable between a plain PostgreSQL test container and
-- a Supabase project.
REVOKE ALL ON SCHEMA tms FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA tms FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA tms FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA tms FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA tms REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- 2. Deny the Supabase API roles explicitly
-- ---------------------------------------------------------------------------
-- `anon`, `authenticated` and `service_role` exist only on a Supabase platform, so the
-- block is guarded: the same migration file must apply cleanly to the disposable
-- PostgreSQL used by the integration tests.
--
-- These roles already have no privileges on a freshly created schema. The revokes are
-- belt and braces against a platform default, a template database or an operator granting
-- something later, and they make the intent auditable in the schema itself.
DO $$
DECLARE
    api_role text;
BEGIN
    FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated', 'service_role']
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
            EXECUTE format('REVOKE ALL ON SCHEMA tms FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA tms FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA tms FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA tms FROM %I', api_role);
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA tms REVOKE ALL ON TABLES FROM %I', api_role);
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA tms REVOKE ALL ON SEQUENCES FROM %I', api_role);
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA tms REVOKE EXECUTE ON FUNCTIONS FROM %I', api_role);
            RAISE NOTICE 'tms: revoked all privileges on schema tms from role %', api_role;
        END IF;
    END LOOP;
END;
$$;

-- ---------------------------------------------------------------------------
-- 3. Enable Row Level Security on every application table
-- ---------------------------------------------------------------------------
-- RLS is enabled with NO policies. For any role that is not the table owner this is a
-- complete deny - the correct answer while the only supported access path is the Spring
-- Boot backend. Adding `anon`/`authenticated` policies now would be pretending to
-- authorize a path that V1 does not open.
--
-- FORCE ROW LEVEL SECURITY is deliberately NOT set: the backend connects as the role that
-- owns these tables and must keep working. Authorization for that connection is Spring
-- Boot's job (architecture section 4.2) - RLS is the second line of defense, not the first.
-- Making the backend a non-owner role is a deployment hardening option documented in
-- docs/security/RLS_STRATEGY.md; it requires policies or BYPASSRLS and a new migration.
ALTER TABLE tms.app_user        ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.organization    ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.company         ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.role            ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.permission      ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.role_permission ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.membership      ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.membership_role ENABLE ROW LEVEL SECURITY;

COMMENT ON SCHEMA tms IS
    'TMS by EBIM application schema. Owned by Flyway (ADR-002). Backend-only: not listed in '
    'supabase/config.toml [api].schemas, no privileges for anon/authenticated/service_role, '
    'RLS enabled with no policies so any accidental exposure denies by default.';
