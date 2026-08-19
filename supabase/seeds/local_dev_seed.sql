-- TMS by EBIM - LOCAL DEVELOPMENT SEED. NOT A MIGRATION.
--
-- This file is deliberately outside backend/tms-api/src/main/resources/db/migration:
-- canonical Flyway migrations contain schema and schema-contract reference data only
-- (roles and permissions). Tenants, users and memberships are data, and demo data must
-- never reach a production database through the migration history.
--
-- It is also NOT wired into `supabase db reset` ([db.seed] enabled = false in config.toml),
-- because the schema is created by Flyway, not by the Supabase CLI. Apply it by hand, after
-- the migrations have run against your local stack:
--
--     psql "postgresql://postgres:postgres@localhost:54322/postgres" \
--          -f supabase/seeds/local_dev_seed.sql
--
-- Contents: one organization, two companies, three users and their memberships. No
-- passwords, no keys, no production-like identifiers.
--
-- This file leaves auth_user_id NULL. Something has to fill it in before the API works:
-- PrincipalResolutionService resolves the caller strictly by `WHERE auth_user_id = :authUserId`
-- (JdbcIdentityRepository), with no fallback by email and no auto-provisioning at first login.
-- Left NULL, Supabase still issues a valid token and sign-in appears to succeed, and then every
-- API call fails as "authenticated but not provisioned".
--
-- Run `demo_auth_users.sql` after this file: it creates the missing Supabase Auth accounts and
-- links them. Its section 3 also stands alone if the accounts were made in Studio instead.
--
-- Re-runnable: every statement is guarded with ON CONFLICT DO NOTHING.

BEGIN;

INSERT INTO tms.organization (code, name)
VALUES ('DEMO', 'Demo Organization (local development)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO tms.company (organization_id, code, name, time_zone)
SELECT o.id, v.code, v.name, v.time_zone
FROM tms.organization o
CROSS JOIN (VALUES
    ('DEMO-LIMA',    'Demo Logistica Lima',    'America/Lima'),
    ('DEMO-AREQUIPA', 'Demo Logistica Arequipa', 'America/Lima')
) AS v(code, name, time_zone)
WHERE o.code = 'DEMO'
ON CONFLICT (organization_id, code) DO NOTHING;

INSERT INTO tms.app_user (email, full_name)
VALUES
    ('admin@demo.local',      'Demo Organization Admin'),
    ('planner.lima@demo.local', 'Demo Planner Lima'),
    ('viewer@demo.local',     'Demo Viewer')
ON CONFLICT (email) DO NOTHING;

-- Organization-wide membership for the administrator (company_id NULL).
INSERT INTO tms.membership (app_user_id, organization_id, company_id)
SELECT u.id, o.id, NULL
FROM tms.app_user u
CROSS JOIN tms.organization o
WHERE u.email = 'admin@demo.local' AND o.code = 'DEMO'
ON CONFLICT DO NOTHING;

-- Company-scoped memberships.
INSERT INTO tms.membership (app_user_id, organization_id, company_id)
SELECT u.id, c.organization_id, c.id
FROM tms.app_user u
JOIN tms.company c ON c.code = 'DEMO-LIMA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
WHERE u.email IN ('planner.lima@demo.local', 'viewer@demo.local')
ON CONFLICT DO NOTHING;

-- Roles: administrator organization-wide, planner and viewer inside DEMO-LIMA.
INSERT INTO tms.membership_role (membership_id, role_id)
SELECT m.id, r.id
FROM tms.membership m
JOIN tms.app_user u ON u.id = m.app_user_id
JOIN tms.role r ON r.code = CASE u.email
        WHEN 'admin@demo.local' THEN 'ORGANIZATION_ADMIN'
        WHEN 'planner.lima@demo.local' THEN 'PLANNER'
        WHEN 'viewer@demo.local' THEN 'VIEWER'
    END
WHERE u.email IN ('admin@demo.local', 'planner.lima@demo.local', 'viewer@demo.local')
ON CONFLICT DO NOTHING;

COMMIT;

-- Verification:
--   SELECT u.email, o.code AS organization, coalesce(c.code, '<organization-wide>') AS company, r.code AS role
--   FROM tms.membership m
--   JOIN tms.app_user u ON u.id = m.app_user_id
--   JOIN tms.organization o ON o.id = m.organization_id
--   LEFT JOIN tms.company c ON c.id = m.company_id
--   LEFT JOIN tms.membership_role mr ON mr.membership_id = m.id
--   LEFT JOIN tms.role r ON r.id = mr.role_id
--   ORDER BY 1, 3;
