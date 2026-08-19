-- TMS by EBIM - V3 authorization reference data.
--
-- Roles and permissions are part of the schema contract: Java code, tests and the future
-- admin UI refer to these codes, so they belong in the migration history exactly like the
-- tables that hold them (ADR-002, "reference/seed data that is part of the schema contract").
--
-- This file contains NO organization, company, user, membership or credential. Demo and
-- local fixtures live in supabase/seeds/local_dev_seed.sql and in test code only.

-- ---------------------------------------------------------------------------
-- Permissions: (resource, action). `code` is generated as "resource:action".
-- ---------------------------------------------------------------------------
-- The catalogue covers the V1 modules of the architecture of record and nothing beyond
-- them. Later modules add their own permissions in their own migrations.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('iam.organization',       'read',   'View organizations'),
    ('iam.organization',       'manage', 'Create, update and deactivate organizations'),
    ('iam.company',            'read',   'View companies'),
    ('iam.company',            'manage', 'Create, update and deactivate companies'),
    ('iam.user',               'read',   'View user profiles'),
    ('iam.user',               'manage', 'Invite, update and deactivate user profiles'),
    ('iam.membership',         'read',   'View memberships and their roles'),
    ('iam.membership',         'manage', 'Grant, change and revoke memberships and roles'),
    ('masterdata.origin',      'read',   'View origins'),
    ('masterdata.origin',      'manage', 'Create, update and deactivate origins'),
    ('masterdata.zone',        'read',   'View zones'),
    ('masterdata.zone',        'manage', 'Create, update and deactivate zones'),
    ('masterdata.destination', 'read',   'View destinations'),
    ('masterdata.destination', 'manage', 'Create, update and deactivate destinations'),
    ('masterdata.frequency',   'read',   'View frequencies'),
    ('masterdata.frequency',   'manage', 'Create, update and deactivate frequencies'),
    ('masterdata.route',       'read',   'View routes'),
    ('masterdata.route',       'manage', 'Create, update and deactivate routes'),
    ('fleet.carrier',          'read',   'View carriers'),
    ('fleet.carrier',          'manage', 'Create, update and deactivate carriers'),
    ('fleet.vehicle_type',     'read',   'View vehicle types and their default capacities'),
    ('fleet.vehicle_type',     'manage', 'Create, update and deactivate vehicle types'),
    ('fleet.vehicle',          'read',   'View vehicles'),
    ('fleet.vehicle',          'manage', 'Create, update and deactivate vehicles'),
    ('orders.order',           'read',   'View transport orders'),
    ('orders.order',           'manage', 'Create, update and cancel transport orders'),
    ('planning.trip',          'read',   'View trips and their assignments'),
    ('planning.trip',          'manage', 'Create trips and assign orders to them'),
    ('audit.log',              'read',   'Read the audit trail');

-- audit.log has no `manage` permission on purpose: the audit trail is append-only and no
-- role may edit or delete it.

-- ---------------------------------------------------------------------------
-- Roles
-- ---------------------------------------------------------------------------
INSERT INTO tms.role (code, name, description, scope_level, system_managed) VALUES
    ('ORGANIZATION_ADMIN', 'Organization administrator',
     'Full control of one organization: its companies, users, memberships and operational data.',
     'ORGANIZATION', true),
    ('COMPANY_ADMIN', 'Company administrator',
     'Full control of one company: master data, fleet, orders, planning, and the memberships of that company.',
     'COMPANY', true),
    ('PLANNER', 'Planner',
     'Reads master data and fleet, manages orders and builds trips for one company.',
     'COMPANY', true),
    ('VIEWER', 'Viewer',
     'Read-only access to the operational data of one company.',
     'COMPANY', true);

-- ---------------------------------------------------------------------------
-- Role to permission grants
-- ---------------------------------------------------------------------------
-- ORGANIZATION_ADMIN: everything in its own organization. Tenant isolation is not a
-- permission - it is enforced by the membership scope resolved server-side (ADR-003).
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code = 'ORGANIZATION_ADMIN';

-- COMPANY_ADMIN: everything except changing the organization itself.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code = 'COMPANY_ADMIN'
  AND p.code <> 'iam.organization:manage';

-- PLANNER: reads the operational catalogue, owns orders and trips, sees no IAM
-- administration beyond the company it works in.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code = 'PLANNER'
  AND p.code IN (
      'iam.company:read',
      'masterdata.origin:read',
      'masterdata.zone:read',
      'masterdata.destination:read',
      'masterdata.frequency:read',
      'masterdata.route:read',
      'fleet.carrier:read',
      'fleet.vehicle_type:read',
      'fleet.vehicle:read',
      'orders.order:read',
      'orders.order:manage',
      'planning.trip:read',
      'planning.trip:manage'
  );

-- VIEWER: the PLANNER catalogue without any write capability.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code = 'VIEWER'
  AND p.action = 'read'
  AND p.resource IN (
      'iam.company',
      'masterdata.origin',
      'masterdata.zone',
      'masterdata.destination',
      'masterdata.frequency',
      'masterdata.route',
      'fleet.carrier',
      'fleet.vehicle_type',
      'fleet.vehicle',
      'orders.order',
      'planning.trip'
  );
