-- TMS identity for the local E2E run: the same shape the Spring integration tests seed
-- (WarehouseExecutionApiIntegrationTest). Fictitious data, disposable database only.
-- auth_user_id is the `sub` the harness mints its RS256 tokens with (lib/jwt_tool.py).
INSERT INTO tms.organization (id, code, name)
VALUES ('0e2e0000-0000-4000-8000-000000000001', 'E2E-ORG', 'E2E Organization') ON CONFLICT DO NOTHING;
INSERT INTO tms.company (id, organization_id, code, name, time_zone)
VALUES ('0e2e0000-0000-4000-8000-000000000002', '0e2e0000-0000-4000-8000-000000000001', 'E2E-PE', 'E2E Peru',
        'America/Lima') ON CONFLICT DO NOTHING;
INSERT INTO tms.app_user (auth_user_id, email, full_name)
VALUES ('0e2e0000-0000-4000-8000-0000000000a1', 'e2e-admin@example.test', 'E2E Admin') ON CONFLICT DO NOTHING;
INSERT INTO tms.membership (app_user_id, organization_id, company_id)
SELECT u.id, '0e2e0000-0000-4000-8000-000000000001', '0e2e0000-0000-4000-8000-000000000002'
  FROM tms.app_user u
 WHERE u.auth_user_id = '0e2e0000-0000-4000-8000-0000000000a1'
   AND NOT EXISTS (SELECT 1 FROM tms.membership m WHERE m.app_user_id = u.id);
INSERT INTO tms.membership_role (membership_id, role_id)
SELECT m.id, r.id
  FROM tms.membership m
  JOIN tms.app_user u ON u.id = m.app_user_id AND u.auth_user_id = '0e2e0000-0000-4000-8000-0000000000a1'
  JOIN tms.role r ON r.code = 'COMPANY_ADMIN'
ON CONFLICT DO NOTHING;
-- The scenarios switch the mode through PUT /api/v1/admin/companies/current; this is the start.
INSERT INTO tms.company_settings (company_id, dispatch_confirmation_mode)
VALUES ('0e2e0000-0000-4000-8000-000000000002', 'EXTERNAL_REQUIRED')
ON CONFLICT (company_id) DO UPDATE SET dispatch_confirmation_mode = EXCLUDED.dispatch_confirmation_mode;
