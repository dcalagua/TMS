-- TMS by EBIM - SEED DEL TENANT DE DEMOSTRACIÓN «PLAPASA (demo)». NO ES UNA MIGRACIÓN.
--
-- ===========================================================================================
--  SOLO PARA QAS O UN ENTORNO LOCAL DESECHABLE. Datos de demostración, reconocibles como tales:
--  la organización y la empresa llevan el prefijo `QAS-` y el nombre «(demo)», los correos son
--  `@plapasa.demo`, y ninguna persona, RUC o placa es de alguien real. Nunca contra producción.
-- ===========================================================================================
--
-- QUÉ ES. El lado transporte de la demo comercial de PLAPASA (fabricante de plásticos, Ecuador,
-- Guayaquil, USD, America/Guayaquil). El EWM de la demo ya tiene a PLAPASA cargado
-- (`WMS-by-EBIM/demo/plapasa/blueprint.json`, sólo lectura): almacén GYE-CD, los clientes
-- CLI-SUP-01 / CLI-FER-01 / CLI-AGR-01 / CLI-UIO-01, el transportista TRLIT y los vehículos CAM-01
-- (GBA-1234) y CAM-02 (GBC-5678). Este seed da de alta lo MISMO del lado del TMS, con los MISMOS
-- códigos, porque lo único que cruza entre los dos productos son referencias de negocio
-- (`docs/integrations/WAREHOUSE_EXECUTION_V1.md` §1): el código de almacén, el código del
-- transportista, la placa, la clave del pedido del ERP.
--
-- POR QUÉ UN SEED Y NO EL CAMINO DE MASTERADMIN. Un tenant real nace por
-- `/internal/platform-provisioning` (ADR-012): organización, empresa, `company_settings` con el
-- país, el administrador PREPROVISIONED, su membresía de organización y el rol
-- ORGANIZATION_ADMIN. Esa API está apagada por defecto y en QAS no hay un MasterAdmin conectado
-- (`docs/environments/QAS.md`), así que este fichero escribe exactamente esas mismas filas, con la
-- misma forma que `PlatformTenantProvisioner`, y agrega los maestros que el aprovisionamiento no
-- crea. Es la convención de `qas_seed.sql`, que es su hermano.
--
-- QUÉ NO HAY ACÁ, A PROPÓSITO:
--   * Pedidos. Los manda el ERP por `POST /integration/v1/orders/batch`
--     (`scripts/demo/plapasa_tms_demo.py`), porque al escribir una línea el backend le copia el
--     perfil físico del material y le calcula la huella (pilas, volumen efectivo, piso, alto -
--     ADR-022, `OrderLinePhysicalResolver` + `TransportOrder.applyLines`). Un INSERT aquí dejaría
--     el pedido sin geometría: 1.000 vasos volverían a contar 1,90 m³, que es justo lo que la demo
--     viene a desmentir.
--   * Perfiles de material. Los escribe el EWM (D-02, interfaz TMS_MATP). Si el enlace con el EWM
--     no está vivo el día de la demo, existe un respaldo aparte y explícito:
--     `plapasa_demo_material_profiles_fallback.sql`.
--   * Configuración fiscal. Con `default_country = 'EC'` la empresa ya está en el régimen de Ecuador
--     (ADR-027): IVA, USD, comprobantes SRI 01/04/05, RUC de 13 dígitos, flete al 0 % y la retención del
--     IR 1 % código 310 encendida; la retención de IVA (70 %) apagada. Son los valores del despliegue
--     (`tms.finance.tax.ec.*`) y alcanzan para la demo, así que el seed NO escribe ningún override
--     fiscal: si PLAPASA necesita otros, van por `PUT /api/v1/settlement/fiscal-settings` o la tarjeta
--     «Régimen fiscal» de Administración › Empresa (runbook, paso 5).
--   * Credenciales de integración y suscripciones de webhook. Su secreto se genera en el servidor y
--     se muestra una sola vez: se crean por la API (runbook, paso 6), nunca en un fichero.
--
-- CONTRASEÑAS: ninguna. Las personas se enlazan con Supabase Auth BUSCÁNDOLAS POR CORREO en
-- `auth.users`. Si una cuenta todavía no existe, su `auth_user_id` queda nulo y esa persona no puede
-- entrar hasta que alguien la cree (runbook, paso 3) y se vuelva a correr este fichero.
--
-- FECHAS: literales. Un seed que se mueve con el reloj da entornos distintos en cada ejecución.
--
-- RE-EJECUTABLE: cada sentencia está guardada (ON CONFLICT / NOT EXISTS). La configuración de la
-- empresa se reafirma (DO UPDATE) en cada corrida; los maestros no se pisan (DO NOTHING), así que
-- un ensayo que edite una ubicación no se deshace al volver a correrlo.
--
--     psql "$QAS_DB_URL" -v ON_ERROR_STOP=1 -f supabase/seeds/plapasa_demo_seed.sql
--
-- Lo valida `PlapasaDemoSeedIntegrationTest` (Testcontainers): Flyway V1..V68 + este seed dos veces.

BEGIN;

-- 1. Tenencia (la misma forma que PlatformTenantProvisioner) -------------------------------------

INSERT INTO tms.organization (code, name)
VALUES ('QAS-PLAPASA', 'PLAPASA (demo)')
ON CONFLICT (code) DO NOTHING;

-- RUC de la empresa: 0999999999001, el mismo que el blueprint del EWM usa para la planta
-- (PLANTA-GYE). Es un RUC de sociedad privada VÁLIDO por construcción (ver el cálculo en §4,
-- transportista) y evidentemente ficticio: nueve nueves.
--   0*4 + 9*3 + 9*2 + 9*7 + 9*6 + 9*5 + 9*4 + 9*3 + 9*2 = 288 ; 288 mod 11 = 2 ; 11 - 2 = 9  ✓
INSERT INTO tms.company (organization_id, code, name, tax_identifier, time_zone)
SELECT o.id, 'QAS-PLAPASA', 'PLAPASA (demo)', '0999999999001', 'America/Guayaquil'
FROM tms.organization o
WHERE o.code = 'QAS-PLAPASA'
ON CONFLICT (organization_id, code) DO NOTHING;

-- Configuración de la empresa. Se REAFIRMA en cada corrida: es lo que hace que la demo cuente los
-- números del guion (`docs/demo/PLAPASA_TMS_DEMO.md`).
--   default_country 'EC'          el país, y con él el régimen fiscal de Ecuador (ver la cabecera).
--   prefijos OT- / VJ-            «orden de transporte», «viaje». Los números SH-/TO- del QAS
--                                 peruano no se confunden con los de esta empresa.
--   dispatch_confirmation_mode    HYBRID: el despacho del almacén (SLS) despacha el viaje, y una
--                                 persona todavía puede despacharlo a mano (ADR-013).
--   reference_load_height_m 2.40  la altura con la que se apilan los pedidos al entrar (ADR-022 §4).
--                                 Se fija aquí y no se deja al valor del despliegue, para que
--                                 «10 pilas de 1,363 m» no dependa de una variable de Render.
--   load_floor_fill_factor 0.85   cuánto del piso de una caja se puede usar de verdad.
--   cost_allocation_basis         EFFECTIVE_VOLUME: el costo de un viaje se reparte por lo que cada
--                                 pedido OCUPA (ADR-026). Para plásticos huecos es la base honesta:
--                                 por peso, los vasos no pagarían casi nada del camión que llenan.
INSERT INTO tms.company_settings (company_id, default_country, order_number_prefix, shipment_number_prefix,
                                  dispatch_confirmation_mode, reference_load_height_m, load_floor_fill_factor,
                                  cost_allocation_basis)
SELECT c.id, 'EC', 'OT-', 'VJ-', 'HYBRID', 2.40, 0.85, 'EFFECTIVE_VOLUME'
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
WHERE c.code = 'QAS-PLAPASA'
ON CONFLICT (company_id) DO UPDATE SET
    default_country            = EXCLUDED.default_country,
    order_number_prefix        = EXCLUDED.order_number_prefix,
    shipment_number_prefix     = EXCLUDED.shipment_number_prefix,
    dispatch_confirmation_mode = EXCLUDED.dispatch_confirmation_mode,
    reference_load_height_m    = EXCLUDED.reference_load_height_m,
    load_floor_fill_factor     = EXCLUDED.load_floor_fill_factor,
    cost_allocation_basis      = EXCLUDED.cost_allocation_basis;

-- 2. Personas --------------------------------------------------------------------------------
--
-- Sólo `@plapasa.demo` (gobernanza de la suite: nunca correos ni nombres reales del cliente). Son
-- los mismos correos que el EWM de la demo usa para su administrador, para que Edu/Dennis no tengan
-- que recordar otro; viven en otro proyecto de Supabase Auth, así que son otras cuentas.

INSERT INTO tms.app_user (email, full_name)
VALUES
    ('admin@plapasa.demo',               'Administración PLAPASA (demo)'),
    ('planner@plapasa.demo',             'Planificación de transporte PLAPASA (demo)'),
    ('finanzas@plapasa.demo',            'Finanzas PLAPASA (demo)'),
    ('transportista.trlit@plapasa.demo', 'Despacho Transportes del Litoral (demo)')
ON CONFLICT (email) DO NOTHING;

-- El enlace con Supabase Auth, por correo. `PrincipalResolutionService` resuelve al llamante sólo
-- por `auth_user_id`: sin esta sentencia el login funciona y todas las llamadas responden
-- «autenticado pero no aprovisionado».
UPDATE tms.app_user u
SET auth_user_id = a.id
FROM auth.users a
WHERE a.email = u.email
  AND u.email LIKE '%@plapasa.demo'
  AND u.auth_user_id IS DISTINCT FROM a.id;

-- Administración: de toda la organización (company_id NULL), como la deja MasterAdmin.
INSERT INTO tms.membership (app_user_id, organization_id, company_id)
SELECT u.id, o.id, NULL
FROM tms.app_user u
CROSS JOIN tms.organization o
WHERE u.email = 'admin@plapasa.demo' AND o.code = 'QAS-PLAPASA'
ON CONFLICT DO NOTHING;

-- Planificación y finanzas: de la empresa. Finanzas es COMPANY_ADMIN porque aprobar una factura de
-- flete es de un administrador, y no del que la registró (cuatro ojos, SettlementService): en la
-- demo admin@ recibe la factura de TRLIT y finanzas@ la aprueba.
INSERT INTO tms.membership (app_user_id, organization_id, company_id)
SELECT u.id, c.organization_id, c.id
FROM tms.app_user u
JOIN tms.company c ON c.code = 'QAS-PLAPASA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
WHERE u.email IN ('planner@plapasa.demo', 'finanzas@plapasa.demo')
ON CONFLICT DO NOTHING;

INSERT INTO tms.membership_role (membership_id, role_id)
SELECT m.id, r.id
FROM tms.membership m
JOIN tms.app_user u ON u.id = m.app_user_id
JOIN tms.organization o ON o.id = m.organization_id AND o.code = 'QAS-PLAPASA'
JOIN tms.role r ON r.code = CASE u.email
        WHEN 'admin@plapasa.demo'   THEN 'ORGANIZATION_ADMIN'
        WHEN 'planner@plapasa.demo' THEN 'PLANNER'
        WHEN 'finanzas@plapasa.demo' THEN 'COMPANY_ADMIN'
    END
WHERE u.email IN ('admin@plapasa.demo', 'planner@plapasa.demo', 'finanzas@plapasa.demo')
  AND m.carrier_id IS NULL
ON CONFLICT DO NOTHING;

-- (La membresía del transportista va en §4, después de que exista TRLIT.)

-- 3. Lugares, zonas, frecuencia y rutas ---------------------------------------------------------
--
-- Los códigos, nombres y direcciones son los del blueprint del EWM, al pie de la letra: son los
-- `locationCode` que viajan en el plan y los que el EWM muestra. Ecuador no tiene departamentos ni
-- distritos: se usa department = provincia, province = cantón, district = parroquia (la jerarquía
-- administrativa del país, de mayor a menor, en las tres columnas que el modelo ya tiene).
-- Coordenadas: aproximadas, de la dirección, para el mapa y el estimador geodésico (ADR-010).
--
-- GYE-CD lleva `external_reference = 'GYE-CD'`: es el código del almacén en el EWM, el
-- `warehouseCode` que manda el despacho y el `originExternalReference` del plan.

INSERT INTO tms.zone (company_id, code, name, description)
SELECT c.id, v.code, v.name, v.description
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
CROSS JOIN (VALUES
    ('ZN-GYE',        'Guayaquil urbano',  'Clientes dentro del cantón Guayaquil'),
    ('ZN-LOS-RIOS',   'Los Ríos',          'Babahoyo y la zona agrícola de Los Ríos'),
    ('ZN-SIERRA-NTE', 'Sierra norte',      'Quito y Pichincha')
) AS v(code, name, description)
WHERE c.code = 'QAS-PLAPASA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.location (company_id, code, name, location_type, address, address_reference, district,
                          province, department, country, latitude, longitude, time_zone, service_time_minutes,
                          zone_id, external_system, external_reference)
SELECT c.id, v.code, v.name, v.location_type, v.address, v.address_reference, v.district, v.province,
       v.department, 'EC', v.lat, v.lon, 'America/Guayaquil', v.service_minutes, z.id,
       v.external_system, v.external_reference
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
CROSS JOIN (VALUES
    ('GYE-CD', 'PLAPASA · Planta de inyección y soplado (CD Guayaquil)', 'PLANT',
     'Km 14,5 Vía a Daule', 'Parque industrial, junto a la planta de inyección', 'Pascuales', 'Guayaquil',
     'Guayas', -2.0812, -79.9547, 45, NULL, 'EWM', 'GYE-CD'),
    ('CLI-SUP-01', 'Supermercados Costa Azul S.A. (demo)', 'STORE',
     'Av. Francisco de Orellana 234', 'Kennedy Norte, andén de proveedores', 'Tarqui', 'Guayaquil',
     'Guayas', -2.1664, -79.8958, 40, 'ZN-GYE', NULL, NULL),
    ('CLI-FER-01', 'Ferretería El Constructor Cía. Ltda. (demo)', 'CUSTOMER',
     'Av. Quito y Sucre', 'Centro, ingreso por la calle Sucre', 'Rocafuerte', 'Guayaquil',
     'Guayas', -2.1952, -79.8868, 30, 'ZN-GYE', NULL, NULL),
    ('CLI-AGR-01', 'Agroexportadora Río Babahoyo S.A. (demo)', 'CUSTOMER',
     'Km 3 vía a Montalvo', 'Empacadora, portón 2', 'Clemente Baquerizo', 'Babahoyo',
     'Los Ríos', -1.8051, -79.5062, 60, 'ZN-LOS-RIOS', NULL, NULL),
    ('CLI-UIO-01', 'Distribuidora Andina del Norte (demo)', 'CUSTOMER',
     'Av. Galo Plaza Lasso N45-12', 'Bodega posterior', 'Kennedy', 'Quito',
     'Pichincha', -0.1530, -78.4740, 45, 'ZN-SIERRA-NTE', NULL, NULL)
) AS v(code, name, location_type, address, address_reference, district, province, department, lat, lon,
       service_minutes, zone_code, external_system, external_reference)
LEFT JOIN tms.zone z ON z.company_id = c.id AND z.code = v.zone_code
WHERE c.code = 'QAS-PLAPASA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.location_role (location_id, role)
SELECT l.id, v.role
FROM tms.location l
JOIN tms.company c ON c.id = l.company_id AND c.code = 'QAS-PLAPASA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
JOIN (VALUES
    ('GYE-CD',     'ORIGIN'),
    ('CLI-SUP-01', 'DESTINATION'),
    ('CLI-FER-01', 'DESTINATION'),
    ('CLI-AGR-01', 'DESTINATION'),
    ('CLI-UIO-01', 'DESTINATION')
) AS v(code, role) ON v.code = l.code
ON CONFLICT DO NOTHING;

-- Lunes a sábado, sin hora de corte ni anticipación: la demo no tiene que pelear con un corte que
-- dependa de la hora a la que alguien corrió el script (ADR-014: sin corte = fin del día).
INSERT INTO tms.frequency (company_id, code, name, description)
SELECT c.id, 'FRQ-LUN-SAB', 'Lunes a sábado', 'Reparto de lunes a sábado desde la planta'
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
WHERE c.code = 'QAS-PLAPASA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.frequency_weekly_rule (frequency_id, day_of_week, enabled)
SELECT f.id, d.day, true
FROM tms.frequency f
JOIN tms.company c ON c.id = f.company_id AND c.code = 'QAS-PLAPASA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
CROSS JOIN (VALUES (1),(2),(3),(4),(5),(6)) AS d(day)
WHERE f.code = 'FRQ-LUN-SAB'
ON CONFLICT DO NOTHING;

-- Cuatro rutas, una por corredor, y cada cliente en UNA sola (si estuviera en dos, su pedido
-- quedaría BLOQUEADO por ROUTE_AMBIGUOUS; si no estuviera en ninguna, por ROUTE_NOT_FOUND).
-- RUTA-GYE-NORTE es el nombre que el EWM ya usa en sus cargas al supermercado.
INSERT INTO tms.route (company_id, code, name, origin_id, zone_id, frequency_id, reference_distance_km,
                       reference_duration_minutes)
SELECT c.id, v.code, v.name, l.id, z.id, f.id, v.km, v.minutes
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
JOIN tms.location l ON l.company_id = c.id AND l.code = 'GYE-CD'
JOIN tms.frequency f ON f.company_id = c.id AND f.code = 'FRQ-LUN-SAB'
CROSS JOIN (VALUES
    ('RUTA-GYE-NORTE',  'Planta -> Kennedy Norte (supermercados)', 'ZN-GYE',        18, 40),
    ('RUTA-GYE-SUR',    'Planta -> Centro y sur de Guayaquil',      'ZN-GYE',        24, 55),
    ('RUTA-LOS-RIOS',   'Planta -> Babahoyo (agroexportación)',     'ZN-LOS-RIOS',   82, 110),
    ('RUTA-SIERRA-NTE', 'Planta -> Quito (larga distancia)',        'ZN-SIERRA-NTE', 420, 540)
) AS v(code, name, zone_code, km, minutes)
JOIN tms.zone z ON z.company_id = c.id AND z.code = v.zone_code
WHERE c.code = 'QAS-PLAPASA'
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.route_stop (route_id, company_id, destination_id, sequence)
SELECT r.id, r.company_id, l.id, v.seq
FROM tms.route r
JOIN tms.company c ON c.id = r.company_id AND c.code = 'QAS-PLAPASA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
JOIN (VALUES
    ('RUTA-GYE-NORTE',  'CLI-SUP-01', 1),
    ('RUTA-GYE-SUR',    'CLI-FER-01', 1),
    ('RUTA-LOS-RIOS',   'CLI-AGR-01', 1),
    ('RUTA-SIERRA-NTE', 'CLI-UIO-01', 1)
) AS v(route_code, destination_code, seq) ON v.route_code = r.code
JOIN tms.location l ON l.company_id = r.company_id AND l.code = v.destination_code
  -- NOT EXISTS y no ON CONFLICT: `uq_route_stop_route_sequence` es DEFERRABLE (ver qas_seed.sql).
WHERE NOT EXISTS (
    SELECT 1 FROM tms.route_stop rs
    WHERE rs.route_id = r.id AND (rs.sequence = v.seq OR rs.destination_id = l.id));

-- 3b. Muelles de la planta (citas de muelle, V41) ---------------------------------------------
--
-- Tres muelles en GYE-CD para que «Citas de muelle» tenga dónde reservar el día de la demo. Una
-- fila por muelle porque cada uno atiende UN vehículo a la vez (V41 §1). Horario de lunes a sábado,
-- 07:00-19:00 en hora LOCAL de la planta (America/Guayaquil), guardado en minutos desde la
-- medianoche local (420 y 1140), igual que lo escribe `PUT /appointments/resources/{id}/calendar`.
-- El domingo no tiene fila: ese día el muelle está CERRADO (una puerta sin ninguna fila de horario
-- se lee como abierta siempre; con horario, el día que falta se lee como cerrado).
-- Son de demostración: PLAPASA tiene que confirmar cuántas puertas y qué horario tiene la planta.
INSERT INTO tms.location_resource (company_id, location_id, code, name, resource_type, default_slot_minutes)
SELECT c.id, l.id, v.code, v.name, 'DOCK', v.slot
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
JOIN tms.location l ON l.company_id = c.id AND l.code = 'GYE-CD'
CROSS JOIN (VALUES
    ('MUE-01', 'Muelle 1 · despacho de producto terminado', 60),
    ('MUE-02', 'Muelle 2 · despacho de producto terminado', 60),
    ('MUE-03', 'Muelle 3 · recepción de materia prima',     90)
) AS v(code, name, slot)
WHERE c.code = 'QAS-PLAPASA'
ON CONFLICT (location_id, code) DO NOTHING;

INSERT INTO tms.resource_calendar (company_id, resource_id, day_of_week, opens_at_minutes, closes_at_minutes)
SELECT r.company_id, r.id, d.day, 420, 1140
FROM tms.location_resource r
JOIN tms.location l ON l.id = r.location_id AND l.code = 'GYE-CD'
JOIN tms.company c ON c.id = r.company_id AND c.code = 'QAS-PLAPASA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
CROSS JOIN (VALUES (1),(2),(3),(4),(5),(6)) AS d(day)
WHERE r.code IN ('MUE-01', 'MUE-02', 'MUE-03')
ON CONFLICT (resource_id, day_of_week) DO NOTHING;

-- 4. Flota -----------------------------------------------------------------------------------
--
-- TRLIT, Transportes del Litoral S.A. (demo): el mismo código que en el EWM. El despacho del
-- almacén compara el CÓDIGO del transportista con el del viaje (CARRIER_MISMATCH), no el RUC.
--
-- RUC de sociedad privada ecuatoriana, 13 dígitos: provincia 09 (Guayas), tercer dígito 9
-- (sociedad privada), secuencia, dígito verificador módulo 11 en la posición 10, y 001 (matriz).
--   primeros 9 dígitos  0  9  9  0  0  0  0  0  3
--   coeficientes        4  3  2  7  6  5  4  3  2
--   productos           0 27 18  0  0  0  0  0  6   -> suma 51
--   51 mod 11 = 7  ->  verificador = 11 - 7 = 4      (residuo 0 -> 0; 10 sería inválido)
--   RUC = 099000003 · 4 · 001 = 0990000034001
-- Nota: el blueprint del EWM le pone a TRLIT el taxId 0990000000301, que NO pasa este control
-- (suma 45, residuo 1, verificador 10 = inválido). No se copia: se reporta como punto abierto.
INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value, contact_name, phone,
                         email, payment_terms_days)
SELECT c.id, 'TRLIT', 'Transportes del Litoral S.A. (demo)', 'RUC', '0990000034001',
       'Despacho TRLIT (demo)', '+593 4 000 0000', 'despacho@trlit.demo', 30
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
WHERE c.code = 'QAS-PLAPASA'
ON CONFLICT (company_id, code) DO NOTHING;

-- Tipos de vehículo con medidas INTERIORES utilizables (largo x ancho x alto de la caja), que es lo
-- que la geometría de carga lee (ADR-022 §5): piso = L x A x factor, alto = H. El volumen máximo es
-- L x A x H. El factor de piso es del tipo, no de la empresa, porque una plataforma de tráiler se
-- aprovecha mejor que una caja corta con rueda de auxilio adentro.
--   NHR-35          camioneta tipo NHR, la «van» de la demo: 4,30 x 2,00 x 2,00 = 17,2 m³
--   FURGON-5T       el furgón de 5 t del EWM (CAM-02): 6,0 x 2,4 x 2,4
--   FURGON-5T-BAJO  la MISMA caja con techo BAJO (2,20 m), para mostrar el RE-APILADO por vehículo:
--                   el TMS baja las pilas a 2,20 m (25 sillas encajadas -> 22, 2,17 m) y, con el mismo
--                   piso que el FURGON-5T, lo que rechaza lo rechaza por la causa real (las gavetas
--                   re-apiladas más bajas piden 13,09 m² de piso y la caja tiene 12,24)
--   CAMION-10T      el camión de 10 t (el EWM lo rotula «CAMION 10T»): 6,2 x 2,4 x 2,4
--   TRAILER-12M     semirremolque de 12 m, sin vehículo dado de alta: está para cotizar
INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3, max_pallets, length_m,
                              width_m, height_m, body_type, axles, load_floor_fill_factor)
SELECT c.id, v.code, v.name, v.kg, v.m3, v.pallets, v.l, v.w, v.h, 'DRY_VAN', v.axles, v.fill
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
CROSS JOIN (VALUES
    ('NHR-35',         'Camioneta tipo NHR 3,5 t (caja 4,3 x 2,0 x 2,0 m)',   3500, 17.20,  6,  4.30, 2.00, 2.00, 2, 0.85),
    ('FURGON-5T',      'Furgón 5 t (caja 6,0 x 2,4 x 2,4 m)',                 5000, 34.56, 10,  6.00, 2.40, 2.40, 2, 0.85),
    ('FURGON-5T-BAJO', 'Furgón 5 t caja baja (6,0 x 2,4 x 2,2 m)',            5000, 31.68, 10,  6.00, 2.40, 2.20, 2, 0.85),
    ('CAMION-10T',     'Camión 10 t (caja 6,2 x 2,4 x 2,4 m)',               10000, 35.71, 12,  6.20, 2.40, 2.40, 2, 0.85),
    ('TRAILER-12M',    'Tráiler 12 m (semirremolque 12,0 x 2,45 x 2,6 m)',   28000, 76.44, 24, 12.00, 2.45, 2.60, 5, 0.90)
) AS v(code, name, kg, m3, pallets, l, w, h, axles, fill)
WHERE c.code = 'QAS-PLAPASA'
ON CONFLICT (company_id, code) DO NOTHING;

-- Placas con el formato ecuatoriano (tres letras, la primera G de Guayas, y cuatro dígitos). CAM-01
-- y CAM-02 son las del EWM, al pie de la letra: el despacho compara la placa del tracto
-- (VEHICLE_MISMATCH, sin espacios ni guiones). CAM-03 (la camioneta) y CAM-04 (la caja baja) el EWM
-- no los tiene dados de alta: los conoce por el plan, que trae la placa.
INSERT INTO tms.vehicle (company_id, code, license_plate, vehicle_type_id, carrier_id)
SELECT c.id, v.code, v.plate, vt.id, ca.id
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
JOIN tms.carrier ca ON ca.company_id = c.id AND ca.code = 'TRLIT'
CROSS JOIN (VALUES
    ('CAM-01', 'GBA-1234', 'CAMION-10T'),
    ('CAM-02', 'GBC-5678', 'FURGON-5T'),
    ('CAM-03', 'GSC-2468', 'NHR-35'),
    ('CAM-04', 'GSD-1357', 'FURGON-5T-BAJO')
) AS v(code, plate, type_code)
JOIN tms.vehicle_type vt ON vt.company_id = c.id AND vt.code = v.type_code
WHERE c.code = 'QAS-PLAPASA'
ON CONFLICT (company_id, code) DO NOTHING;

-- Conductores. Nombres evidentemente ficticios, como «Conductor demo 1» del EWM. Cédula ecuatoriana
-- de 10 dígitos (provincia 09, tercer dígito < 6, verificador módulo 10 con coeficientes
-- 2,1,2,1,2,1,2,1,2); en Ecuador el número de licencia es el de la cédula. Licencia tipo E para
-- camiones pesados, C para la camioneta.
--   091234567 -> 0+9+2+2+6+4+1+6+5 = 35 -> 10-5 = 5 -> 0912345675
--   092000001 -> 0+9+4+0+0+0+0+0+2 = 15 -> 10-5 = 5 -> 0920000015
--   093000002 -> 0+9+6+0+0+0+0+0+4 = 19 -> 10-9 = 1 -> 0930000021
INSERT INTO tms.driver (company_id, code, first_name, last_name, document_type, document_number, phone,
                        license_number, license_category, license_expires_on, carrier_id)
SELECT c.id, v.code, v.first_name, v.last_name, 'CEDULA', v.ci, v.phone, v.ci, v.category, v.expires, ca.id
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
JOIN tms.carrier ca ON ca.company_id = c.id AND ca.code = 'TRLIT'
CROSS JOIN (VALUES
    ('COND-01', 'Conductor', 'Demo Uno',  '0912345675', '+593 99 000 0001', 'E', DATE '2028-03-31'),
    ('COND-02', 'Conductor', 'Demo Dos',  '0920000015', '+593 99 000 0002', 'E', DATE '2027-11-30'),
    ('COND-03', 'Conductor', 'Demo Tres', '0930000021', '+593 99 000 0003', 'C', DATE '2029-06-30')
) AS v(code, first_name, last_name, ci, phone, category, expires)
WHERE c.code = 'QAS-PLAPASA'
ON CONFLICT (company_id, code) DO NOTHING;

-- El usuario del portal del transportista (ADR-018): una membresía de la empresa atada a TRLIT, con
-- sólo el rol CARRIER_OPERATOR. Ve los viajes de TRLIT recién cuando el almacén los despacha.
INSERT INTO tms.membership (app_user_id, organization_id, company_id, carrier_id)
SELECT u.id, c.organization_id, c.id, ca.id
FROM tms.app_user u
JOIN tms.company c ON c.code = 'QAS-PLAPASA'
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
JOIN tms.carrier ca ON ca.company_id = c.id AND ca.code = 'TRLIT'
WHERE u.email = 'transportista.trlit@plapasa.demo'
ON CONFLICT DO NOTHING;

INSERT INTO tms.membership_role (membership_id, role_id)
SELECT m.id, r.id
FROM tms.membership m
JOIN tms.app_user u ON u.id = m.app_user_id
JOIN tms.organization o ON o.id = m.organization_id AND o.code = 'QAS-PLAPASA'
JOIN tms.role r ON r.code = 'CARRIER_OPERATOR'
WHERE u.email = 'transportista.trlit@plapasa.demo'
  AND m.carrier_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- 5. Comercial ---------------------------------------------------------------------------------
--
-- Tarifa de TRLIT en dólares: un cargo fijo por viaje, un valor por kilómetro, uno por parada y uno
-- por METRO LINEAL de caja usado (V63): lo que el camión gasta de largo, que es lo que de verdad
-- cobra un transportista por plásticos voluminosos y livianos. Valores de demostración (SUPUESTO):
-- PLAPASA tiene que darnos los suyos.
INSERT INTO tms.rate_card (company_id, code, name, carrier_id, scope, currency, valid_from, base_amount,
                           amount_per_km, amount_per_stop, amount_per_loading_meter, minimum_amount)
SELECT c.id, 'TRLIT-2026-USD', 'TRLIT 2026 · Guayaquil y provincias (USD, demo)', ca.id, 'CARRIER', 'USD',
       DATE '2026-01-01', 45.00, 0.85, 5.00, 6.50, 60.00
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
JOIN tms.carrier ca ON ca.company_id = c.id AND ca.code = 'TRLIT'
WHERE c.code = 'QAS-PLAPASA'
ON CONFLICT (company_id, code) DO NOTHING;

COMMIT;

-- Comprobación rápida (sólo lectura):
--   SELECT c.code, s.default_country, c.time_zone, s.dispatch_confirmation_mode,
--          (SELECT count(*) FROM tms.location l WHERE l.company_id = c.id) AS ubicaciones,
--          (SELECT count(*) FROM tms.vehicle v WHERE v.company_id = c.id)  AS vehiculos
--   FROM tms.company c JOIN tms.company_settings s ON s.company_id = c.id
--   WHERE c.code = 'QAS-PLAPASA';
--
--   SELECT email, auth_user_id IS NOT NULL AS puede_entrar FROM tms.app_user
--   WHERE email LIKE '%@plapasa.demo' ORDER BY email;
