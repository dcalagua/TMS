-- TMS by EBIM - V5 completion of the authorization catalogue.
--
-- V3 seeded the permissions the V1 modules needed at the time. Step 03 pins the backend
-- authorization contract (docs/security/AUTHORIZATION_MODEL.md), and that contract requires
-- three capabilities the V3 catalogue could not express:
--
--   * planning as an activity distinct from the trips it produces. `planning.trip:*`
--     already covers reading and building trips; a planner also needs to open the planning
--     board and run/adjust a planning session without that implying trip authorship.
--   * a read-only transport monitor: the operational overview screen that dispatch and
--     customer service use. It is deliberately read-only - it has no `manage` action,
--     exactly like `audit.log`.
--
-- V1..V4 are applied and immutable (ADR-002), so the rows arrive in a new migration rather
-- than by editing V3. Grants for the four system roles are explicit here because V3's
-- CROSS JOIN grants only covered the permissions that existed when it ran.

-- ---------------------------------------------------------------------------
-- New permissions
-- ---------------------------------------------------------------------------
INSERT INTO tms.permission (resource, action, description) VALUES
    ('planning.plan',        'read',   'Open the planning board and view planning runs'),
    ('planning.plan',        'manage', 'Run and adjust planning: build, modify and discard planning runs'),
    ('monitoring.transport', 'read',   'View the transport monitor (read-only operational overview)');

-- monitoring.transport has no `manage` action on purpose: the monitor observes state that
-- other modules own. Acting on what it shows is done through orders or planning, with the
-- permissions of those modules.

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------
-- ORGANIZATION_ADMIN and COMPANY_ADMIN hold the whole catalogue except
-- `iam.organization:manage` for COMPANY_ADMIN, so both receive all three.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code IN ('planning.plan:read', 'planning.plan:manage', 'monitoring.transport:read');

-- PLANNER owns the planning activity and watches its execution.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code = 'PLANNER'
  AND p.code IN ('planning.plan:read', 'planning.plan:manage', 'monitoring.transport:read');

-- VIEWER reads, and never writes: the `manage` action is not granted.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code = 'VIEWER'
  AND p.code IN ('planning.plan:read', 'monitoring.transport:read');
