-- TMS by EBIM - CARRIER PORTAL DEMO SEED (ADR-018). NOT A MIGRATION.
--
-- ===========================================================================================
--  DISPOSABLE ENVIRONMENTS ONLY: a local stack, or a demo/QA project holding demo data.
--  NEVER against production.
-- ===========================================================================================
--
-- Master data and people for trying the carrier portal and the document delivery monitor in
-- DEMO-LIMA. Run it AFTER `local_dev_seed.sql`, then `demo_auth_users.sql` (which now also creates
-- the two carrier accounts below), then `scripts/demo/carrier_portal_demo.py`, which builds the
-- trips the way production does - orders and invoices from the ERP, dispatch from the warehouse,
-- all through the integration API - because confirming and dispatching a plan are service
-- transitions, not INSERTs (the reason qas_seed.sql gives).
--
--     psql "postgresql://postgres:postgres@localhost:54322/postgres" \
--          -f supabase/seeds/carrier_portal_demo_seed.sql
--
-- Contents (DEMO-LIMA):
--   * dispatch mode HYBRID, so the warehouse's dispatch applies and a person can still dispatch;
--   * origin CD-LIMA (warehouse code CD01) and three customer destinations;
--   * carriers TRSA and TRSB, three vehicles and three drivers;
--   * transportista.trsa@demo.local and transportista.trsb@demo.local: carrier memberships with the
--     CARRIER_OPERATOR role. Each sees only its own carrier's dispatched trips.
--
-- DEMO-AREQUIPA has its own file, `carrier_portal_demo_seed_arequipa.sql`; this one creates nothing
-- outside DEMO-LIMA. Execution order for both companies: scripts/demo/README.md.
--
-- Re-runnable: every statement is guarded.

BEGIN;

INSERT INTO tms.company_settings (company_id, dispatch_confirmation_mode)
SELECT c.id, 'HYBRID'
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
WHERE c.code = 'DEMO-LIMA'
ON CONFLICT (company_id) DO UPDATE SET dispatch_confirmation_mode = EXCLUDED.dispatch_confirmation_mode;

-- 1. Places ---------------------------------------------------------------------------------

INSERT INTO tms.location (company_id, code, name, location_type, address, district, province,
                          department, country, latitude, longitude, time_zone, service_time_minutes,
                          external_system, external_reference)
SELECT c.id, v.code, v.name, v.location_type, v.address, v.district, v.province, v.department, 'PE',
       v.lat, v.lon, 'America/Lima', v.service_minutes, v.external_system, v.external_reference
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
CROSS JOIN (VALUES
    ('CD-LIMA', 'Centro de Distribucion Lima', 'DISTRIBUTION_CENTER',
     'Av. Argentina 3000', 'Callao', 'Callao', 'Callao', -12.0464, -77.0900, 45, 'EWM', 'CD01'),
    ('CLI-PLAZA-NORTE', 'Tienda Plaza Norte', 'STORE',
     'Av. Alfredo Mendiola 1400', 'Independencia', 'Lima', 'Lima', -12.0067, -77.0590, 30, NULL, NULL),
    ('CLI-SURCO', 'Tienda Surco', 'STORE',
     'Av. Primavera 800', 'Surco', 'Lima', 'Lima', -12.1350, -76.9900, 30, NULL, NULL),
    ('CLI-CALLAO', 'Almacen Cliente Callao', 'WAREHOUSE',
     'Av. Nestor Gambetta 5200', 'Callao', 'Callao', 'Callao', -11.9950, -77.1200, 40, NULL, NULL)
) AS v(code, name, location_type, address, district, province, department, lat, lon, service_minutes,
       external_system, external_reference)
WHERE c.code = 'DEMO-LIMA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.location_role (location_id, role)
SELECT l.id, v.role
FROM tms.location l
JOIN tms.company c ON c.id = l.company_id AND c.code = 'DEMO-LIMA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN (VALUES
    ('CD-LIMA', 'ORIGIN'),
    ('CLI-PLAZA-NORTE', 'DESTINATION'),
    ('CLI-SURCO', 'DESTINATION'),
    ('CLI-CALLAO', 'DESTINATION')
) AS v(code, role) ON v.code = l.code
ON CONFLICT DO NOTHING;

-- 2. Fleet ----------------------------------------------------------------------------------

INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)
SELECT c.id, v.code, v.name, 'RUC', v.ruc
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
CROSS JOIN (VALUES
    ('TRSA', 'Transportes Rapidos del Sur S.A.C.', '20100000019'),
    ('TRSB', 'Logistica Andina S.A.C.', '20100000027')
) AS v(code, name, ruc)
WHERE c.code = 'DEMO-LIMA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3, max_pallets)
SELECT c.id, 'DEMO-FURGON-5T', 'Furgon 5 toneladas', 5000, 30, 12
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
WHERE c.code = 'DEMO-LIMA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.vehicle (company_id, code, license_plate, vehicle_type_id, carrier_id)
SELECT c.id, v.code, v.plate, vt.id, ca.id
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN tms.vehicle_type vt ON vt.company_id = c.id AND vt.code = 'DEMO-FURGON-5T'
CROSS JOIN (VALUES
    ('DEMO-VEH-A1', 'ABC-101', 'TRSA'),
    ('DEMO-VEH-A2', 'ABC-102', 'TRSA'),
    ('DEMO-VEH-B1', 'XYZ-201', 'TRSB')
) AS v(code, plate, carrier_code)
JOIN tms.carrier ca ON ca.company_id = c.id AND ca.code = v.carrier_code
WHERE c.code = 'DEMO-LIMA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.driver (company_id, code, first_name, last_name, document_type, document_number,
                        license_number, carrier_id)
SELECT c.id, v.code, v.first_name, v.last_name, 'DNI', v.dni, v.license, ca.id
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
CROSS JOIN (VALUES
    ('DEMO-COND-A1', 'Juan', 'Perez', '40123456', 'Q40123456', 'TRSA'),
    ('DEMO-COND-A2', 'Carlos', 'Quispe', '40234567', 'Q40234567', 'TRSA'),
    ('DEMO-COND-B1', 'Rosa', 'Huaman', '40345678', 'Q40345678', 'TRSB')
) AS v(code, first_name, last_name, dni, license, carrier_code)
JOIN tms.carrier ca ON ca.company_id = c.id AND ca.code = v.carrier_code
WHERE c.code = 'DEMO-LIMA'
ON CONFLICT (company_id, code) DO NOTHING;

-- 3. Carrier users (ADR-018) ----------------------------------------------------------------
-- A carrier membership: company-scoped, bound to one carrier, CARRIER roles only.

INSERT INTO tms.app_user (email, full_name)
VALUES
    ('transportista.trsa@demo.local', 'Despacho Transportes Rapidos del Sur'),
    ('transportista.trsb@demo.local', 'Despacho Logistica Andina')
ON CONFLICT (email) DO NOTHING;

INSERT INTO tms.membership (app_user_id, organization_id, company_id, carrier_id)
SELECT u.id, c.organization_id, c.id, ca.id
FROM tms.app_user u
JOIN tms.company c ON c.code = 'DEMO-LIMA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN tms.carrier ca ON ca.company_id = c.id
 AND ca.code = CASE u.email WHEN 'transportista.trsa@demo.local' THEN 'TRSA' ELSE 'TRSB' END
WHERE u.email IN ('transportista.trsa@demo.local', 'transportista.trsb@demo.local')
ON CONFLICT DO NOTHING;

INSERT INTO tms.membership_role (membership_id, role_id)
SELECT m.id, r.id
FROM tms.membership m
JOIN tms.app_user u ON u.id = m.app_user_id
JOIN tms.role r ON r.code = 'CARRIER_OPERATOR'
WHERE u.email IN ('transportista.trsa@demo.local', 'transportista.trsb@demo.local')
  AND m.carrier_id IS NOT NULL
ON CONFLICT DO NOTHING;

COMMIT;

-- Verification:
--   SELECT u.email, ca.code AS carrier, r.code AS role
--   FROM tms.membership m
--   JOIN tms.app_user u ON u.id = m.app_user_id
--   JOIN tms.carrier ca ON ca.id = m.carrier_id
--   JOIN tms.membership_role mr ON mr.membership_id = m.id
--   JOIN tms.role r ON r.id = mr.role_id;
