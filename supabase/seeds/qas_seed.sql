-- TMS by EBIM - SEED DEL ENTORNO QAS. NO ES UNA MIGRACIÓN.
--
-- ===========================================================================================
--  SOLO PARA QAS. Datos de prueba, reconocibles como tales: todo lleva el prefijo `QAS-`.
--  Nunca contra producción, y nunca con datos de un cliente real.
-- ===========================================================================================
--
-- Vive fuera de `db/migration` a propósito: las migraciones canónicas de Flyway contienen
-- esquema y datos de contrato (roles y permisos). Un tenant, sus usuarios y su flota son datos,
-- y los datos de demostración no pueden llegar a una base de producción por el historial de
-- migraciones. Es la misma regla que sigue `local_dev_seed.sql`, y la razón de que este fichero
-- sea su hermano y no una V36.
--
-- POR QUÉ ES DISTINTO DEL SEED LOCAL. `local_dev_seed.sql` crea la organización `DEMO` y para ahí:
-- basta para arrancar la aplicación y entrar. QAS necesita algo más - una muestra coherente que
-- permita abrir las veintiuna pantallas del menú y ver una tabla con filas, no una lista vacía.
-- Por eso este seed añade maestros, flota, tarifas, pedidos y un plan.
--
-- CONTRASEÑAS: ninguna. Las cuentas viven en `auth.users`, las gestiona Supabase Auth, y este
-- fichero sólo las *enlaza* buscándolas por correo. Si una cuenta no existe todavía, su fila de
-- `app_user` queda con `auth_user_id` nulo y la persona no puede entrar hasta que alguien la
-- cree en Studio; eso es visible y corregible, y es preferible a que este fichero fije una
-- credencial.
--
-- RE-EJECUTABLE: cada sentencia está guardada con ON CONFLICT DO NOTHING, así que correrlo dos
-- veces no duplica nada ni falla.

BEGIN;

-- 1. Tenencia ------------------------------------------------------------------------------

INSERT INTO tms.organization (code, name)
VALUES ('QAS', 'TMS QAS - entorno de calidad')
ON CONFLICT (code) DO NOTHING;

INSERT INTO tms.company (organization_id, code, name, time_zone)
SELECT o.id, v.code, v.name, v.time_zone
FROM tms.organization o
CROSS JOIN (VALUES
    ('QAS-LIMA',      'QAS Logistica Lima',      'America/Lima'),
    ('QAS-AREQUIPA',  'QAS Logistica Arequipa',  'America/Lima')
) AS v(code, name, time_zone)
WHERE o.code = 'QAS'
ON CONFLICT (organization_id, code) DO NOTHING;

-- 2. Personas ------------------------------------------------------------------------------
--
-- Los tres correos son los que ya existen en `auth.users` de este proyecto. Se conservan al pie
-- de la letra: cambiarlos obligaría a crear cuentas nuevas y, con ellas, a inventar contraseñas.

INSERT INTO tms.app_user (email, full_name)
VALUES
    ('admin@demo.local',        'QAS Administrador de organizacion'),
    ('planner.lima@demo.local', 'QAS Planificador Lima'),
    ('viewer@demo.local',       'QAS Consulta')
ON CONFLICT (email) DO NOTHING;

-- El enlace con Supabase Auth, por correo y no por id escrito a mano: así el seed sirve igual en
-- un proyecto QAS recreado, donde los ids serían otros.
--
-- `PrincipalResolutionService` resuelve al llamante estrictamente por `auth_user_id`. Sin esta
-- sentencia, Supabase emite un token válido, el login parece funcionar, y después *todas* las
-- llamadas fallan como "autenticado pero no aprovisionado".
UPDATE tms.app_user u
SET auth_user_id = a.id
FROM auth.users a
WHERE a.email = u.email
  AND u.auth_user_id IS DISTINCT FROM a.id;

INSERT INTO tms.membership (app_user_id, organization_id, company_id)
SELECT u.id, o.id, NULL
FROM tms.app_user u
CROSS JOIN tms.organization o
WHERE u.email = 'admin@demo.local' AND o.code = 'QAS'
ON CONFLICT DO NOTHING;

INSERT INTO tms.membership (app_user_id, organization_id, company_id)
SELECT u.id, c.organization_id, c.id
FROM tms.app_user u
JOIN tms.company c ON c.code = 'QAS-LIMA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
WHERE u.email IN ('planner.lima@demo.local', 'viewer@demo.local')
ON CONFLICT DO NOTHING;

INSERT INTO tms.membership_role (membership_id, role_id)
SELECT m.id, r.id
FROM tms.membership m
JOIN tms.app_user u ON u.id = m.app_user_id
JOIN tms.organization o ON o.id = m.organization_id AND o.code = 'QAS'
JOIN tms.role r ON r.code = CASE u.email
        WHEN 'admin@demo.local'        THEN 'ORGANIZATION_ADMIN'
        WHEN 'planner.lima@demo.local' THEN 'PLANNER'
        WHEN 'viewer@demo.local'       THEN 'VIEWER'
    END
WHERE u.email IN ('admin@demo.local', 'planner.lima@demo.local', 'viewer@demo.local')
ON CONFLICT DO NOTHING;

-- 3. Maestros ------------------------------------------------------------------------------

INSERT INTO tms.zone (company_id, code, name)
SELECT c.id, v.code, v.name
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
CROSS JOIN (VALUES
    ('QAS-NORTE', 'Lima Norte'),
    ('QAS-SUR',   'Lima Sur')
) AS v(code, name)
WHERE c.code = 'QAS-LIMA'
ON CONFLICT (company_id, code) DO NOTHING;

-- Cuatro lugares físicos. `QAS-MIXTO` lleva los dos roles a propósito: es el caso que da sentido
-- al modelo canónico - una tienda es el destino del reparto y el origen de la devolución, con una
-- sola dirección y un solo par de coordenadas.
INSERT INTO tms.location (company_id, code, name, location_type, address, district, province,
                          department, country, latitude, longitude, time_zone, service_time_minutes)
SELECT c.id, v.code, v.name, v.location_type, v.address, v.district, v.province,
       v.department, 'PE', v.lat, v.lon, 'America/Lima', v.service_minutes
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
CROSS JOIN (VALUES
    ('QAS-CD-LIMA',   'QAS Centro de Distribucion Lima', 'DISTRIBUTION_CENTER',
     'Av. Argentina 3000', 'Callao', 'Callao', 'Callao', -12.0464, -77.0900, 45),
    ('QAS-TIENDA-01', 'QAS Tienda Miraflores', 'STORE',
     'Av. Larco 500', 'Miraflores', 'Lima', 'Lima', -12.1200, -77.0300, 30),
    ('QAS-TIENDA-02', 'QAS Tienda San Isidro', 'STORE',
     'Av. Javier Prado 1200', 'San Isidro', 'Lima', 'Lima', -12.0930, -77.0350, 25),
    ('QAS-MIXTO',     'QAS Tienda Surco (envia y recibe)', 'STORE',
     'Av. Primavera 800', 'Surco', 'Lima', 'Lima', -12.1350, -76.9900, 35)
) AS v(code, name, location_type, address, district, province, department, lat, lon, service_minutes)
WHERE c.code = 'QAS-LIMA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.location_role (location_id, role)
SELECT l.id, v.role
FROM tms.location l
JOIN tms.company c ON c.id = l.company_id AND c.code = 'QAS-LIMA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
JOIN (VALUES
    ('QAS-CD-LIMA',   'ORIGIN'),
    ('QAS-TIENDA-01', 'DESTINATION'),
    ('QAS-TIENDA-02', 'DESTINATION'),
    ('QAS-MIXTO',     'ORIGIN'),
    ('QAS-MIXTO',     'DESTINATION')
) AS v(code, role) ON v.code = l.code
ON CONFLICT DO NOTHING;

INSERT INTO tms.frequency (company_id, code, name, description)
SELECT c.id, 'QAS-LUNVIE', 'Lunes a viernes', 'Reparto en dias laborables'
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
WHERE c.code = 'QAS-LIMA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.frequency_weekly_rule (frequency_id, day_of_week, enabled)
SELECT f.id, d.day, true
FROM tms.frequency f
JOIN tms.company c ON c.id = f.company_id AND c.code = 'QAS-LIMA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
CROSS JOIN (VALUES (1),(2),(3),(4),(5)) AS d(day)
WHERE f.code = 'QAS-LUNVIE'
ON CONFLICT DO NOTHING;

INSERT INTO tms.route (company_id, code, name, origin_id)
SELECT c.id, 'QAS-RUTA-NORTE', 'CD Lima -> Miraflores -> San Isidro', l.id
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
JOIN tms.location l ON l.company_id = c.id AND l.code = 'QAS-CD-LIMA'
WHERE c.code = 'QAS-LIMA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.route_stop (route_id, company_id, destination_id, sequence)
SELECT r.id, r.company_id, l.id, v.seq
FROM tms.route r
JOIN tms.company c ON c.id = r.company_id AND c.code = 'QAS-LIMA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
JOIN (VALUES ('QAS-TIENDA-01', 1), ('QAS-TIENDA-02', 2)) AS v(code, seq) ON true
JOIN tms.location l ON l.company_id = r.company_id AND l.code = v.code
WHERE r.code = 'QAS-RUTA-NORTE'
  -- Guarda por NOT EXISTS y no por ON CONFLICT: `uq_route_stop_route_sequence` es DEFERRABLE
  -- INITIALLY DEFERRED - lo tiene que ser, porque reordenar paradas pasa por estados
  -- intermedios con secuencias repetidas - y PostgreSQL no admite una restricción diferible
  -- como árbitro de ON CONFLICT.
  AND NOT EXISTS (
      SELECT 1 FROM tms.route_stop rs
      WHERE rs.route_id = r.id AND rs.sequence = v.seq);

-- 4. Flota ---------------------------------------------------------------------------------

INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value)
SELECT c.id, 'QAS-TRANS-01', 'QAS Transportes del Pacifico S.A.C.', 'RUC', '20100000001'
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
WHERE c.code = 'QAS-LIMA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3, max_pallets)
SELECT c.id, 'QAS-CAMION-10T', 'Camion 10 toneladas', 10000, 40, 20
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
WHERE c.code = 'QAS-LIMA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.vehicle (company_id, code, license_plate, vehicle_type_id, carrier_id)
SELECT c.id, 'QAS-VEH-01', 'QAS-101', vt.id, ca.id
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
JOIN tms.vehicle_type vt ON vt.company_id = c.id AND vt.code = 'QAS-CAMION-10T'
JOIN tms.carrier ca ON ca.company_id = c.id AND ca.code = 'QAS-TRANS-01'
WHERE c.code = 'QAS-LIMA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.driver (company_id, code, first_name, last_name, document_type, document_number,
                        license_number, carrier_id)
SELECT c.id, 'QAS-COND-01', 'Ana', 'Quispe', 'DNI', '40000001', 'Q40000001', ca.id
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
JOIN tms.carrier ca ON ca.company_id = c.id AND ca.code = 'QAS-TRANS-01'
WHERE c.code = 'QAS-LIMA'
ON CONFLICT (company_id, code) DO NOTHING;

-- 5. Comercial -----------------------------------------------------------------------------

-- `ck_rate_card_has_a_component` exige que una tarifa cobre por algo: base, km, kg, m3 o pallet.
-- Una tarifa sin ningun componente no es una tarifa a medias, es una que no sabe facturar.
INSERT INTO tms.rate_card (company_id, code, name, carrier_id, scope, currency, valid_from,
                           base_amount, amount_per_km)
SELECT c.id, 'QAS-TARIFA-01', 'Tarifa base QAS Pacifico', ca.id, 'CARRIER', 'PEN', DATE '2026-01-01',
       150.00, 2.50
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
JOIN tms.carrier ca ON ca.company_id = c.id AND ca.code = 'QAS-TRANS-01'
WHERE c.code = 'QAS-LIMA'
ON CONFLICT (company_id, code) DO NOTHING;

-- 6. Operación -----------------------------------------------------------------------------
--
-- Tres pedidos listos para planificar. Las fechas son fijas y no `CURRENT_DATE`: un seed que se
-- mueve con el reloj hace que dos ejecuciones den entornos distintos, y entonces "en QAS no se ve
-- igual" deja de ser diagnosticable.

INSERT INTO tms.transport_order (company_id, order_number, origin_id, destination_id, service_date, status)
SELECT c.id, v.order_number, org_loc.id, dst.id, v.service_date, 'READY_FOR_PLANNING'
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
JOIN tms.location org_loc ON org_loc.company_id = c.id AND org_loc.code = 'QAS-CD-LIMA'
CROSS JOIN (VALUES
    ('QAS-ORD-0001', 'QAS-TIENDA-01', DATE '2026-08-26'),
    ('QAS-ORD-0002', 'QAS-TIENDA-02', DATE '2026-08-26'),
    ('QAS-ORD-0003', 'QAS-MIXTO',     DATE '2026-08-27')
) AS v(order_number, destination_code, service_date)
JOIN tms.location dst ON dst.company_id = c.id AND dst.code = v.destination_code
WHERE c.code = 'QAS-LIMA'
ON CONFLICT (order_number) DO NOTHING;   -- uq_transport_order_number es global, no por empresa

INSERT INTO tms.transport_order_line (order_id, line_number, material_code, material_description,
                                      quantity, uom, unit_weight_kg, unit_volume_m3)
SELECT t.id, 1, 'QAS-SKU-001', 'Caja de prueba QAS', 10, 'CJA', 12.5, 0.05
FROM tms.transport_order t
JOIN tms.company c ON c.id = t.company_id AND c.code = 'QAS-LIMA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
WHERE t.order_number IN ('QAS-ORD-0001', 'QAS-ORD-0002', 'QAS-ORD-0003')
  -- Misma razón que en route_stop: `uq_transport_order_line_order_line_number` es diferible.
  AND NOT EXISTS (
      SELECT 1 FROM tms.transport_order_line tl
      WHERE tl.order_id = t.id AND tl.line_number = 1);

-- Un plan en borrador con un viaje, para que Planificación y Viajes tengan algo que mostrar. Se
-- deja en DRAFT deliberadamente: confirmar un plan es una transición con reglas propias
-- (capacidad, ventana de salida, asignaciones) que pertenece al servicio, no a un INSERT.
INSERT INTO tms.planning_run (company_id, plan_number, origin_id, planning_date, status)
SELECT c.id, 'QAS-PLAN-0001', l.id, DATE '2026-08-26', 'DRAFT'
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
JOIN tms.location l ON l.company_id = c.id AND l.code = 'QAS-CD-LIMA'
WHERE c.code = 'QAS-LIMA'
ON CONFLICT (plan_number) DO NOTHING;    -- uq_planning_run_number es global

INSERT INTO tms.trip (company_id, planning_run_id, trip_number, planning_date, status, vehicle_id)
SELECT c.id, pr.id, 1, pr.planning_date, 'DRAFT', v.id   -- trip_number es un entero por plan
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS'
JOIN tms.planning_run pr ON pr.company_id = c.id AND pr.plan_number = 'QAS-PLAN-0001'
JOIN tms.vehicle v ON v.company_id = c.id AND v.code = 'QAS-VEH-01'
WHERE c.code = 'QAS-LIMA'
ON CONFLICT (planning_run_id, trip_number) DO NOTHING;   -- uq_trip_run_number

COMMIT;
