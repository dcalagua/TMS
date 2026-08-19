-- TMS by EBIM - DEMO AUTH ACCOUNTS. NOT A MIGRATION.
--
-- ===========================================================================================
--  DISPOSABLE ENVIRONMENTS ONLY: a local stack, or a demo/QA project holding demo data.
--  NEVER against production.
--
--  The password is supplied on the command line, never stored in this file, so the repository
--  does not carry a working credential for any hosted environment.
-- ===========================================================================================
--
-- Companion to `local_dev_seed.sql`, which creates the organization, companies, app users,
-- memberships and roles. That file leaves `tms.app_user.auth_user_id` NULL. This one creates
-- the missing Supabase Auth accounts and fills that column in.
--
-- Filling it in is not optional. `PrincipalResolutionService` resolves the caller strictly by
-- `WHERE auth_user_id = :authUserId` (JdbcIdentityRepository) - there is no fallback by email
-- and no auto-provisioning at first login. With the column left NULL, Supabase issues a valid
-- token, sign-in appears to succeed, and then every single API call fails as "authenticated
-- but not provisioned". The header comment in `local_dev_seed.sql` still suggests otherwise;
-- it predates the implementation.
--
-- Additive by design: an account that already exists is left exactly as it is, password
-- included. That is what makes the file safe to run against an environment somebody else is
-- already signing in to. To rotate an existing account's password, do it deliberately:
--
--     UPDATE auth.users
--        SET encrypted_password = extensions.crypt('<new>', extensions.gen_salt('bf')),
--            updated_at = now()
--      WHERE email = '<address>';
--
-- Apply with:
--     psql "<connection string>" -v demo_password='<password>' -f supabase/seeds/demo_auth_users.sql
--
-- Against a local stack the connection string is:
--     postgresql://postgres:postgres@localhost:54322/postgres
--
-- Order on a fresh environment:
--     1. supabase start          (local only)
--     2. Flyway migrations
--     3. psql -f supabase/seeds/local_dev_seed.sql
--     4. this file
--
-- Accounts, matching local_dev_seed.sql:
--     admin@demo.local          ORGANIZATION_ADMIN, organization-wide
--     planner.lima@demo.local   PLANNER,  scoped to DEMO-LIMA
--     viewer@demo.local         VIEWER,   scoped to DEMO-LIMA

\if :{?demo_password}
\else
\echo 'ERROR: pass the password explicitly, e.g. -v demo_password=''...'''
\quit 1
\endif

BEGIN;

-- pgcrypto lives in the `extensions` schema on a Supabase stack. Adding it to the search path
-- keeps `crypt()` and `gen_salt()` resolvable without hard-coding the schema, which differs
-- between a Supabase stack and a plain PostgreSQL one.
SET LOCAL search_path = public, extensions, pg_catalog;

-- Fixed UUIDs rather than gen_random_uuid(): re-running must not orphan the previous accounts,
-- and the values are readable in a JWT `sub` claim while debugging. They are only used for
-- accounts this file actually creates.
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
-- 1. auth.users - only the ones that do not exist yet
-- ---------------------------------------------------------------------------------------------
-- `email_confirmed_at` is set so sign-in works without a mail round trip, which adds nothing
-- to a seeded demo account.
--
-- The NOT EXISTS guard is on email, not on id: GoTrue's own unique index is on the address, so
-- an ON CONFLICT (id) clause would not catch an account that was created through Studio or the
-- Admin API and therefore carries a different id.
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
    crypt(:'demo_password', gen_salt('bf')),
    now(),
    jsonb_build_object('provider', 'email', 'providers', jsonb_build_array('email')),
    jsonb_build_object('full_name', d.full_name),
    now(),
    now(),
    '', '', '', ''
FROM demo_auth_user d
WHERE NOT EXISTS (
    SELECT 1 FROM auth.users a WHERE lower(a.email) = d.email
);

-- ---------------------------------------------------------------------------------------------
-- 2. auth.identities
-- ---------------------------------------------------------------------------------------------
-- GoTrue looks the password login up through the identity row, not only through auth.users.
-- Without this the account exists but every attempt answers "Invalid login credentials".
-- `provider_id` equals the user id for the email provider.
--
-- Driven from auth.users rather than from the temp table, so an account created elsewhere that
-- is somehow missing its identity row gets one too.
INSERT INTO auth.identities (
    provider_id, user_id, identity_data, provider, last_sign_in_at, created_at, updated_at
)
SELECT
    a.id::text,
    a.id,
    jsonb_build_object('sub', a.id::text, 'email', a.email, 'email_verified', true),
    'email',
    NULL,
    now(),
    now()
FROM auth.users a
JOIN demo_auth_user d ON d.email = lower(a.email)
WHERE NOT EXISTS (
    SELECT 1 FROM auth.identities i WHERE i.user_id = a.id AND i.provider = 'email'
);

-- ---------------------------------------------------------------------------------------------
-- 3. Link them to the TMS identities
-- ---------------------------------------------------------------------------------------------
-- The step that makes the API work at all. Safe to run on its own after creating users through
-- Studio or the Admin API: it matches on email and needs nothing from the sections above.
UPDATE tms.app_user u
SET auth_user_id = a.id,
    updated_at = now()
FROM auth.users a
WHERE lower(a.email) = u.email
  AND u.auth_user_id IS DISTINCT FROM a.id;

COMMIT;

-- Verification - every row must show linked = t and at least one role:
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
