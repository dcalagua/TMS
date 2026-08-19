-- TMS by EBIM - LOCAL DEVELOPMENT AUTH USERS. NOT A MIGRATION. LOCAL STACK ONLY.
--
-- ===========================================================================================
--  DO NOT RUN THIS AGAINST A HOSTED OR SHARED DATABASE.
--
--  It writes accounts whose password is committed to this repository in plain text. That is
--  acceptable for a disposable local stack listening on 127.0.0.1 and nowhere else. On a
--  hosted project the same statements publish three known-password accounts to the internet.
--  For a deployment, create users through the Supabase Admin API or Studio instead, and then
--  run only the final section of this file to link them.
-- ===========================================================================================
--
-- Companion to `local_dev_seed.sql`, which creates the organization, companies, app users,
-- memberships and roles. That file leaves `tms.app_user.auth_user_id` NULL. This one creates
-- the matching Supabase Auth accounts and fills that column in.
--
-- Filling it in is not optional. `PrincipalResolutionService` resolves the caller strictly by
-- `WHERE auth_user_id = :authUserId` (JdbcIdentityRepository) - there is no fallback by email
-- and no auto-provisioning at first login. With the column left NULL, Supabase issues a valid
-- token, sign-in appears to succeed, and then every single API call fails as "authenticated
-- but not provisioned". The header comment in `local_dev_seed.sql` still suggests otherwise;
-- it predates the implementation.
--
-- Order:
--     1. supabase start
--     2. backend migrations (Flyway, via the application or `mvnw flyway:migrate`)
--     3. psql -f supabase/seeds/local_dev_seed.sql
--     4. psql -f supabase/seeds/local_dev_auth_users.sql      <- this file
--
-- Apply with:
--     psql "postgresql://postgres:postgres@localhost:54322/postgres" \
--          -f supabase/seeds/local_dev_auth_users.sql
--
-- Re-runnable: every statement is guarded, and the password is re-applied on each run so the
-- file stays the single source of truth for what these accounts accept.
--
-- Accounts created (password for all three: Demo2026!):
--     admin@demo.local          ORGANIZATION_ADMIN, organization-wide
--     planner.lima@demo.local   PLANNER,  scoped to DEMO-LIMA
--     viewer@demo.local         VIEWER,   scoped to DEMO-LIMA

BEGIN;

-- pgcrypto lives in the `extensions` schema on a Supabase stack. Adding it to the search path
-- keeps `crypt()` and `gen_salt()` resolvable without hard-coding the schema, which differs
-- between a Supabase stack and a plain PostgreSQL one.
SET LOCAL search_path = public, extensions, pg_catalog;

-- Fixed UUIDs rather than gen_random_uuid(): re-running the file must not orphan the previous
-- accounts, and the values are readable in a JWT `sub` claim while debugging.
CREATE TEMP TABLE demo_auth_user (
    id uuid PRIMARY KEY,
    email text NOT NULL,
    full_name text NOT NULL
) ON COMMIT DROP;

INSERT INTO demo_auth_user (id, email, full_name) VALUES
    ('00000000-0000-4000-a000-000000000001', 'admin@demo.local',        'Demo Organization Admin'),
    ('00000000-0000-4000-a000-000000000002', 'planner.lima@demo.local', 'Demo Planner Lima'),
    ('00000000-0000-4000-a000-000000000003', 'viewer@demo.local',       'Demo Viewer');

-- ---------------------------------------------------------------------------------------------
-- 1. auth.users
-- ---------------------------------------------------------------------------------------------
-- `email_confirmed_at` is set so sign-in works without going through the mail flow: the local
-- stack captures mail in Inbucket, and a confirmation round trip adds nothing to a seed.
INSERT INTO auth.users (
    instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
    raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
    confirmation_token, recovery_token, email_change_token_new, email_change
)
SELECT
    '00000000-0000-0000-0000-000000000000',
    d.id,
    'authenticated',
    'authenticated',
    d.email,
    crypt('Demo2026!', gen_salt('bf')),
    now(),
    jsonb_build_object('provider', 'email', 'providers', jsonb_build_array('email')),
    jsonb_build_object('full_name', d.full_name),
    now(),
    now(),
    '', '', '', ''
FROM demo_auth_user d
ON CONFLICT (id) DO UPDATE SET
    encrypted_password = EXCLUDED.encrypted_password,
    email_confirmed_at = COALESCE(auth.users.email_confirmed_at, EXCLUDED.email_confirmed_at),
    raw_user_meta_data = EXCLUDED.raw_user_meta_data,
    updated_at = now();

-- ---------------------------------------------------------------------------------------------
-- 2. auth.identities
-- ---------------------------------------------------------------------------------------------
-- GoTrue looks the password login up through the identity row, not only through auth.users.
-- Without this, the account exists but "Invalid login credentials" comes back on every attempt.
-- `provider_id` equals the user id for the email provider.
INSERT INTO auth.identities (
    provider_id, user_id, identity_data, provider, last_sign_in_at, created_at, updated_at
)
SELECT
    d.id::text,
    d.id,
    jsonb_build_object('sub', d.id::text, 'email', d.email, 'email_verified', true),
    'email',
    NULL,
    now(),
    now()
FROM demo_auth_user d
ON CONFLICT (provider_id, provider) DO UPDATE SET
    identity_data = EXCLUDED.identity_data,
    updated_at = now();

-- ---------------------------------------------------------------------------------------------
-- 3. Link them to the TMS identities
-- ---------------------------------------------------------------------------------------------
-- The step that makes the API work at all. Run this section on its own after creating users
-- through Studio or the Admin API on a hosted project - it matches on email and needs nothing
-- from the two sections above.
UPDATE tms.app_user u
SET auth_user_id = a.id,
    updated_at = now()
FROM auth.users a
WHERE lower(a.email) = u.email
  AND u.auth_user_id IS DISTINCT FROM a.id;

COMMIT;

-- Verification - every row must show a non-null auth_user_id and at least one role:
--   SELECT u.email,
--          u.auth_user_id IS NOT NULL AS linked,
--          coalesce(c.code, '<organization-wide>') AS company,
--          r.code AS role
--   FROM tms.app_user u
--   LEFT JOIN tms.membership m ON m.app_user_id = u.id
--   LEFT JOIN tms.company c ON c.id = m.company_id
--   LEFT JOIN tms.membership_role mr ON mr.membership_id = m.id
--   LEFT JOIN tms.role r ON r.id = mr.role_id
--   WHERE u.email LIKE '%@demo.local'
--   ORDER BY 1, 3;
