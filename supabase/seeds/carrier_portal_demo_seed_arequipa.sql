-- TMS by EBIM - CARRIER PORTAL DEMO SEED, DEMO-AREQUIPA (ADR-018). NOT A MIGRATION.
--
-- ===========================================================================================
--  DISPOSABLE ENVIRONMENTS ONLY: a local stack, or a demo/QA project holding demo data.
--  NEVER against production.
-- ===========================================================================================
--
-- The Arequipa twin of `carrier_portal_demo_seed.sql`, which keeps populating DEMO-LIMA unchanged.
-- A separate file rather than a second half of that one, so the Lima demo stays byte for byte what
-- it was and each company can be loaded, re-loaded or left out on its own.
--
-- DEMO-LIMA and DEMO-AREQUIPA are two COMPANIES of organization DEMO (local_dev_seed.sql). Company is
-- the operational tenant (ADR-003): every master below - places, carriers, vehicle types, vehicles,
-- drivers - is a row of DEMO-AREQUIPA, filtered by the tenant RLS of ADR-005, and nothing is shared
-- with DEMO-LIMA. That is why:
--   * TRSA appears here again: the same transport company (same RUC) that serves Lima is registered
--     a second time, as a separate row of DEMO-AREQUIPA (uq_carrier_company_tax_id is per company);
--   * DEMO-FURGON-5T is declared again: a vehicle type is a company master too;
--   * transportista.trsa@demo.local gets a SECOND carrier membership, in DEMO-AREQUIPA, bound to the
--     Arequipa TRSA row. One membership per (user, company) - uq_membership_user_organization_company -
--     and the carrier must belong to the membership's company - fk_membership_carrier_company,
--     ck_membership_carrier_requires_company (V57). The portal lets that user switch company.
--
-- Order on a fresh environment (see scripts/demo/README.md):
--     1. local_dev_seed.sql
--     2. carrier_portal_demo_seed.sql            (Lima)
--     3. this file                               (Arequipa)
--     4. demo_auth_users.sql -v demo_password=...  (creates and links every demo account)
--     5. TMS_COMPANY_CODE=ALL scripts/demo/carrier_portal_demo.py
--
--     psql "postgresql://postgres:postgres@localhost:54322/postgres" \
--          -f supabase/seeds/carrier_portal_demo_seed_arequipa.sql
--
-- Contents (DEMO-AREQUIPA):
--   * dispatch mode HYBRID, as in Lima;
--   * origin CD-AREQUIPA (warehouse code CD02) and three customer destinations;
--   * carriers TRAQ (Arequipa only) and TRSA (also in Lima, separate row), three vehicles, three drivers;
--   * transportista.traq@demo.local: carrier membership for TRAQ;
--   * transportista.trsa@demo.local: second carrier membership, for TRSA of DEMO-AREQUIPA;
--   * planner.arequipa@demo.local: PLANNER in DEMO-AREQUIPA.
--
-- Re-runnable: every statement is guarded. Depends only on local_dev_seed.sql.

BEGIN;

INSERT INTO tms.company_settings (company_id, dispatch_confirmation_mode)
SELECT c.id, 'HYBRID'
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
WHERE c.code = 'DEMO-AREQUIPA'
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
    ('CD-AREQUIPA', 'Centro de Distribucion Arequipa', 'DISTRIBUTION_CENTER',
     'Calle Jacinto Ibanez 315, Parque Industrial', 'Jose Luis Bustamante y Rivero', 'Arequipa', 'Arequipa',
     -16.4237, -71.5196, 45, 'EWM', 'CD02'),
    ('CLI-CAYMA', 'Tienda Cayma', 'STORE',
     'Av. Ejercito 793', 'Cayma', 'Arequipa', 'Arequipa', -16.3925, -71.5455, 30, NULL, NULL),
    ('CLI-PAUCARPATA', 'Tienda Paucarpata', 'STORE',
     'Av. Kennedy 2010', 'Paucarpata', 'Arequipa', 'Arequipa', -16.4290, -71.5050, 30, NULL, NULL),
    ('CLI-CERRO-COLORADO', 'Almacen Cliente Cerro Colorado', 'WAREHOUSE',
     'Av. Aviacion 602', 'Cerro Colorado', 'Arequipa', 'Arequipa', -16.3600, -71.5650, 40, NULL, NULL)
) AS v(code, name, location_type, address, district, province, department, lat, lon, service_minutes,
       external_system, external_reference)
WHERE c.code = 'DEMO-AREQUIPA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.location_role (location_id, role)
SELECT l.id, v.role
FROM tms.location l
JOIN tms.company c ON c.id = l.company_id AND c.code = 'DEMO-AREQUIPA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN (VALUES
    ('CD-AREQUIPA', 'ORIGIN'),
    ('CLI-CAYMA', 'DESTINATION'),
    ('CLI-PAUCARPATA', 'DESTINATION'),
    ('CLI-CERRO-COLORADO', 'DESTINATION')
) AS v(code, role) ON v.code = l.code
ON CONFLICT DO NOTHING;

-- 2. Fleet ----------------------------------------------------------------------------------
-- TRSA keeps its Lima code, name and RUC: it is the same legal entity, registered in a second
-- company. TRAQ works only in Arequipa (RUC with a valid SUNAT check digit).

INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)
SELECT c.id, v.code, v.name, 'RUC', v.ruc
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
CROSS JOIN (VALUES
    ('TRAQ', 'Transportes del Misti S.A.C.', '20456123784'),
    ('TRSA', 'Transportes Rapidos del Sur S.A.C.', '20100000019')
) AS v(code, name, ruc)
WHERE c.code = 'DEMO-AREQUIPA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3, max_pallets)
SELECT c.id, 'DEMO-FURGON-5T', 'Furgon 5 toneladas', 5000, 30, 12
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
WHERE c.code = 'DEMO-AREQUIPA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.vehicle (company_id, code, license_plate, vehicle_type_id, carrier_id)
SELECT c.id, v.code, v.plate, vt.id, ca.id
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN tms.vehicle_type vt ON vt.company_id = c.id AND vt.code = 'DEMO-FURGON-5T'
CROSS JOIN (VALUES
    ('DEMO-VEH-Q1', 'VAQ-301', 'TRAQ'),
    ('DEMO-VEH-Q2', 'VAQ-302', 'TRAQ'),
    ('DEMO-VEH-S1', 'VAS-401', 'TRSA')
) AS v(code, plate, carrier_code)
JOIN tms.carrier ca ON ca.company_id = c.id AND ca.code = v.carrier_code
WHERE c.code = 'DEMO-AREQUIPA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.driver (company_id, code, first_name, last_name, document_type, document_number,
                        license_number, carrier_id)
SELECT c.id, v.code, v.first_name, v.last_name, 'DNI', v.dni, v.license, ca.id
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
CROSS JOIN (VALUES
    ('DEMO-COND-Q1', 'Luis', 'Mamani', '29123456', 'V29123456', 'TRAQ'),
    ('DEMO-COND-Q2', 'Maria', 'Condori', '29234567', 'V29234567', 'TRAQ'),
    ('DEMO-COND-S1', 'Pedro', 'Apaza', '29345678', 'V29345678', 'TRSA')
) AS v(code, first_name, last_name, dni, license, carrier_code)
JOIN tms.carrier ca ON ca.company_id = c.id AND ca.code = v.carrier_code
WHERE c.code = 'DEMO-AREQUIPA'
ON CONFLICT (company_id, code) DO NOTHING;

-- 3. People ---------------------------------------------------------------------------------
-- transportista.trsa@demo.local is also created by the Lima seed; ON CONFLICT keeps whichever
-- row came first, so the two files can run in either order.

INSERT INTO tms.app_user (email, full_name)
VALUES
    ('transportista.traq@demo.local', 'Despacho Transportes del Misti'),
    ('transportista.trsa@demo.local', 'Despacho Transportes Rapidos del Sur'),
    ('planner.arequipa@demo.local',   'Demo Planner Arequipa')
ON CONFLICT (email) DO NOTHING;

-- Carrier memberships (ADR-018): company-scoped, bound to a carrier of THIS company.
INSERT INTO tms.membership (app_user_id, organization_id, company_id, carrier_id)
SELECT u.id, c.organization_id, c.id, ca.id
FROM tms.app_user u
JOIN tms.company c ON c.code = 'DEMO-AREQUIPA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN tms.carrier ca ON ca.company_id = c.id
 AND ca.code = CASE u.email WHEN 'transportista.traq@demo.local' THEN 'TRAQ' ELSE 'TRSA' END
WHERE u.email IN ('transportista.traq@demo.local', 'transportista.trsa@demo.local')
ON CONFLICT DO NOTHING;

-- Ordinary company membership for the Arequipa planner.
INSERT INTO tms.membership (app_user_id, organization_id, company_id)
SELECT u.id, c.organization_id, c.id
FROM tms.app_user u
JOIN tms.company c ON c.code = 'DEMO-AREQUIPA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
WHERE u.email = 'planner.arequipa@demo.local'
ON CONFLICT DO NOTHING;

INSERT INTO tms.membership_role (membership_id, role_id)
SELECT m.id, r.id
FROM tms.membership m
JOIN tms.app_user u ON u.id = m.app_user_id
JOIN tms.company c ON c.id = m.company_id AND c.code = 'DEMO-AREQUIPA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN tms.role r ON r.code = CASE WHEN m.carrier_id IS NOT NULL THEN 'CARRIER_OPERATOR' ELSE 'PLANNER' END
WHERE u.email IN ('transportista.traq@demo.local', 'transportista.trsa@demo.local',
                  'planner.arequipa@demo.local')
ON CONFLICT DO NOTHING;

COMMIT;

-- Verification:
--   SELECT u.email, c.code AS company, coalesce(ca.code, '-') AS carrier, r.code AS role
--   FROM tms.membership m
--   JOIN tms.app_user u ON u.id = m.app_user_id
--   JOIN tms.company c ON c.id = m.company_id
--   LEFT JOIN tms.carrier ca ON ca.id = m.carrier_id
--   JOIN tms.membership_role mr ON mr.membership_id = m.id
--   JOIN tms.role r ON r.id = mr.role_id
--   ORDER BY 1, 2;
