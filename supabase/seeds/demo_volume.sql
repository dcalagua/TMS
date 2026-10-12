-- TMS by EBIM - VOLUMEN DE MAESTROS, FLOTA Y TARIFAS. NO ES UNA MIGRACION.
--
-- Se apoya en `demo_master_data.sql`: aquel crea el esqueleto legible a mano -las ubicaciones
-- con nombre propio, las rutas del corredor centro, los cuatro transportistas- y este lo lleva a
-- un tamano con el que las pantallas se parecen a las de un cliente: listas que paginan, filtros
-- que sirven de algo y selectores que obligan a buscar en vez de a mirar.
--
-- APLICAR DESPUES de local_dev_seed.sql y demo_master_data.sql.
--
-- NADA ALEATORIO. Ni `random()` ni `CURRENT_DATE`: cada fila se deriva de su indice, asi que dos
-- ejecuciones en dos maquinas producen exactamente la misma base. Un entorno de demostracion que
-- cambia solo convierte "aqui no se ve igual" en algo imposible de diagnosticar.
--
-- COORDENADAS DENTRO DE SU ZONA. Cada punto se desplaza del centro de su zona por una rejilla
-- fija de centesimas de grado, lo que lo deja a unos pocos kilometros: `geo_point` es una columna
-- generada y el estimador geodesico de ADR-010 mide sobre ella, asi que un punto en mitad del
-- Pacifico daria kilometrajes absurdos en cuanto alguien planifique.
--
-- RE-EJECUTABLE: todo va guardado con ON CONFLICT DO NOTHING o NOT EXISTS.

BEGIN;

CREATE TEMP TABLE seed_ctx ON COMMIT DROP AS
SELECT (SELECT id FROM tms.app_user WHERE email = 'admin@demo.local') AS author_id,
       (SELECT c.id FROM tms.company c JOIN tms.organization o ON o.id = c.organization_id
         WHERE o.code = 'DEMO' AND c.code = 'DEMO-LIMA') AS lima_id,
       (SELECT c.id FROM tms.company c JOIN tms.organization o ON o.id = c.organization_id
         WHERE o.code = 'DEMO' AND c.code = 'DEMO-AREQUIPA') AS aqp_id;

-- El centro de cada zona y cuantas tiendas colgar de ella. Es la unica tabla de parametros del
-- fichero: todo lo demas se deriva de aqui.
CREATE TEMP TABLE zone_plan ON COMMIT DROP AS
SELECT v.* FROM (VALUES
    ('DEMO-LIMA',     'Z-NORTE',      'LN', -11.990, -77.060, 12),
    ('DEMO-LIMA',     'Z-SUR',        'LS', -12.170, -76.970, 12),
    ('DEMO-LIMA',     'Z-ESTE',       'LE', -12.040, -76.930, 10),
    ('DEMO-LIMA',     'Z-CENTRO',     'LC', -12.090, -77.020, 10),
    ('DEMO-LIMA',     'Z-CALLAO',     'LK',  -12.040, -77.100, 6),
    ('DEMO-AREQUIPA', 'Z-AQP-NORTE',  'AN', -16.380, -71.560, 7),
    ('DEMO-AREQUIPA', 'Z-AQP-CENTRO', 'AC', -16.390, -71.540, 7)
) AS v(company_code, zone_code, prefix, lat, lon, stores);

-- 1. Tiendas -------------------------------------------------------------------------------
--
-- `service_time_minutes` varia con el indice en vez de ser constante: un tiempo de atencion
-- identico en setenta puntos hace que cualquier estimacion de llegada parezca exacta cuando no
-- lo es, y oculta justo el efecto que ADR-011 quiere que se vea.

INSERT INTO tms.location (company_id, code, name, location_type, address, district, province,
                          department, country, latitude, longitude, time_zone, zone_id,
                          service_time_minutes, geofence_radius_m, created_by, updated_by)
SELECT c.id,
       p.prefix || '-' || lpad(n::text, 2, '0'),
       'Tienda ' || z.name || ' ' || n,
       'STORE',
       'Av. ' || z.name || ' ' || (100 * n)::text,
       z.name,
       CASE WHEN p.company_code = 'DEMO-LIMA' THEN 'Lima' ELSE 'Arequipa' END,
       CASE WHEN p.company_code = 'DEMO-LIMA' THEN 'Lima' ELSE 'Arequipa' END,
       'PE',
       -- Rejilla fija: cinco columnas, y tantas filas como haga falta. +-0,024 grados son unos
       -- 2,7 km, que mantiene cada tienda dentro de su propia zona.
       round((p.lat + (((n - 1) % 5) - 2) * 0.012)::numeric, 6),
       round((p.lon + (((n - 1) / 5) - 1) * 0.018)::numeric, 6),
       'America/Lima',
       z.id,
       20 + (n % 4) * 5,
       60 + (n % 3) * 20,
       s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN zone_plan p
JOIN tms.company c ON c.code = p.company_code
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN tms.zone z ON z.company_id = c.id AND z.code = p.zone_code
CROSS JOIN LATERAL generate_series(1, p.stores) AS n
ON CONFLICT (company_id, code) DO NOTHING;

-- Todas son destinos. Ninguna es origen: un origen es un centro de distribucion, y los tres que
-- hay ya existen.
INSERT INTO tms.location_role (location_id, role)
SELECT l.id, 'DESTINATION'
FROM tms.location l
JOIN tms.company c ON c.id = l.company_id
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN zone_plan p ON p.company_code = c.code
WHERE l.code LIKE p.prefix || '-%'
ON CONFLICT (location_id, role) DO NOTHING;

-- La frecuencia que sirve a cada tienda, repartida para que los filtros tengan con que filtrar.
INSERT INTO tms.location_frequency (company_id, location_id, frequency_id, effective_from,
                                    created_by, updated_by)
SELECT l.company_id, l.id, f.id, DATE '2026-01-01', s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN tms.location l
JOIN tms.company c ON c.id = l.company_id
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN zone_plan p ON p.company_code = c.code AND l.code LIKE p.prefix || '-%'
JOIN tms.frequency f ON f.company_id = l.company_id
   AND f.code = CASE (right(l.code, 2)::int % 3) WHEN 0 THEN 'F-LUNVIE' WHEN 1 THEN 'F-DIARIO' ELSE 'F-LMV' END
WHERE NOT EXISTS (
    SELECT 1 FROM tms.location_frequency lf WHERE lf.location_id = l.id AND lf.frequency_id = f.id);

-- 2. Transportistas -------------------------------------------------------------------------

INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value,
                         contact_name, phone, email, created_by, updated_by)
SELECT c.id,
       'C-' || p.prefix || lpad(n::text, 2, '0'),
       v.nombre || ' ' || p.prefix || lpad(n::text, 2, '0') || ' S.A.C.',
       'RUC',
       (20200000000 + (abs(hashtext(p.prefix || n::text)) % 900000))::text,
       v.contacto,
       '+51 ' || (900000000 + abs(hashtext(p.prefix || n::text)) % 99999999)::text,
       lower('operaciones+' || p.prefix || n::text || '@transportes.demo'),
       s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN (VALUES ('DEMO-LIMA', 'LIM', 8), ('DEMO-AREQUIPA', 'AQP', 4)) AS p(company_code, prefix, howmany)
JOIN tms.company c ON c.code = p.company_code
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
CROSS JOIN LATERAL generate_series(1, p.howmany) AS n
CROSS JOIN LATERAL (VALUES (
    (ARRAY['Transportes Andinos','Carga Segura','Rutas del Sur','Logistica Integral',
           'TransPeru','Via Rapida','Flota Unida','Camino Real'])[1 + (n - 1) % 8],
    (ARRAY['Ana Torres','Luis Vega','Marta Rios','Pedro Luna',
           'Sofia Campos','Jorge Pena','Elena Soto','Raul Diaz'])[1 + (n - 1) % 8]
)) AS v(nombre, contacto)
ON CONFLICT (company_id, code) DO NOTHING;

-- 3. Tipos de vehiculo ------------------------------------------------------------------------

INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3, max_pallets,
                              length_m, width_m, height_m, body_type, temperature_controlled,
                              min_temperature_celsius, max_temperature_celsius, axles,
                              created_by, updated_by)
SELECT c.id, v.code, v.name, v.kg, v.m3, v.pallets, v.len, 2.4, v.hei, v.body,
       v.cold, v.tmin, v.tmax, v.axles, s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
CROSS JOIN (VALUES
    ('VT-FURGON-5T',    'Furgon 5 toneladas',        5000, 24,  10,  5.5, 2.3, 'DRY_VAN',      false, NULL::numeric, NULL::numeric, 2),
    ('VT-REFRIG-12T',   'Refrigerado 12 toneladas', 12000, 45,  22,  8.0, 2.6, 'REFRIGERATED', true,  -20, 6, 3),
    ('VT-PLATAFORMA-15T','Plataforma 15 toneladas', 15000, 50,  26,  9.0, 2.6, 'FLATBED',      false, NULL, NULL, 3),
    ('VT-CISTERNA-20T', 'Cisterna 20 toneladas',    20000, 24,   0, 10.0, 2.7, 'TANKER',       false, NULL, NULL, 4)
) AS v(code, name, kg, m3, pallets, len, hei, body, cold, tmin, tmax, axles)
ON CONFLICT (company_id, code) DO NOTHING;

-- 4. Vehiculos ---------------------------------------------------------------------------------
--
-- Uno de cada diez queda fuera de circulacion -mantenimiento o baja-, porque una flota en la que
-- todo esta disponible no se parece a ninguna flota, y esconde el unico caso en el que la
-- planificacion tiene que elegir otra unidad.

INSERT INTO tms.vehicle (company_id, code, license_plate, vehicle_type_id, carrier_id,
                         availability_status, created_by, updated_by)
SELECT ca.company_id,
       'V-' || p.prefix || lpad(n::text, 3, '0'),
       -- Formato peruano, y unico por empresa: tres caracteres, guion, tres digitos.
       chr(65 + (n % 26)) || chr(65 + ((n / 26) % 26)) || ((n % 9) + 1)::text
           || '-' || lpad(((n * 7) % 1000)::text, 3, '0'),
       vt.id, ca.id,
       CASE WHEN n % 10 = 0 THEN 'IN_MAINTENANCE'
            WHEN n % 37 = 0 THEN 'OUT_OF_SERVICE'
            ELSE 'AVAILABLE' END,
       s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN (VALUES ('DEMO-LIMA', 'L', 96), ('DEMO-AREQUIPA', 'A', 28)) AS p(company_code, prefix, howmany)
JOIN tms.company c ON c.code = p.company_code
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
CROSS JOIN LATERAL generate_series(1, p.howmany) AS n
-- Se reparten entre los transportistas de su empresa, por orden de codigo.
CROSS JOIN LATERAL (
    SELECT x.id, x.company_id FROM tms.carrier x
    WHERE x.company_id = c.id ORDER BY x.code OFFSET (n % (SELECT count(*) FROM tms.carrier y WHERE y.company_id = c.id)) LIMIT 1
) ca
CROSS JOIN LATERAL (
    SELECT y.id FROM tms.vehicle_type y
    WHERE y.company_id = c.id ORDER BY y.code OFFSET (n % (SELECT count(*) FROM tms.vehicle_type z WHERE z.company_id = c.id)) LIMIT 1
) vt
ON CONFLICT (company_id, code) DO NOTHING;

-- 5. Conductores --------------------------------------------------------------------------------
--
-- Mas conductores que vehiculos, que es lo normal: una unidad que trabaja dos turnos necesita dos
-- personas. Y cada uno con el transportista de su vehiculo, porque TMS exige que coincidan.

INSERT INTO tms.driver (company_id, code, first_name, last_name, document_type, document_number,
                        phone, license_number, license_category, license_expires_on, carrier_id,
                        created_by, updated_by)
SELECT ca.company_id,
       'D-' || p.prefix || lpad(n::text, 3, '0'),
       (ARRAY['Carlos','Maria','Jose','Rosa','Miguel','Ana','Luis','Carmen','Pedro','Lucia',
              'Jorge','Elena','Raul','Sofia','Hugo','Nora'])[1 + (n - 1) % 16],
       (ARRAY['Quispe','Mamani','Huaman','Flores','Condori','Vargas','Rojas','Castillo',
              'Apaza','Choque','Ramos','Paredes','Ccama','Nina','Soto','Vega'])[1 + (n - 1) % 16]
         || ' ' ||
       (ARRAY['Torres','Diaz','Luna','Rios','Pena','Campos','Leon','Cruz'])[1 + (n - 1) % 8],
       'DNI',
       (41000000 + n + CASE WHEN p.prefix = 'A' THEN 500000 ELSE 0 END)::text,
       '+51 9' || lpad((10000000 + n * 37)::text, 8, '0'),
       'L' || (41000000 + n + CASE WHEN p.prefix = 'A' THEN 500000 ELSE 0 END)::text,
       (ARRAY['A-IIA','A-IIB','A-IIIA','A-IIIB'])[1 + (n - 1) % 4],
       DATE '2028-01-31' + (n % 365),
       ca.id,
       s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN (VALUES ('DEMO-LIMA', 'L', 110), ('DEMO-AREQUIPA', 'A', 34)) AS p(company_code, prefix, howmany)
JOIN tms.company c ON c.code = p.company_code
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
CROSS JOIN LATERAL generate_series(1, p.howmany) AS n
CROSS JOIN LATERAL (
    SELECT x.id, x.company_id FROM tms.carrier x
    WHERE x.company_id = c.id ORDER BY x.code OFFSET (n % (SELECT count(*) FROM tms.carrier y WHERE y.company_id = c.id)) LIMIT 1
) ca
ON CONFLICT (company_id, code) DO NOTHING;

-- 6. Rutas ----------------------------------------------------------------------------------------
--
-- Una por zona, desde el centro de distribucion de su empresa, con tres paradas de esa zona.

INSERT INTO tms.route (company_id, code, name, origin_id, zone_id, frequency_id,
                       reference_distance_km, reference_duration_minutes, created_by, updated_by)
SELECT c.id, 'R-' || p.prefix, 'Reparto ' || z.name, cd.id, z.id, f.id,
       -- Coherente con la geografia: se mide la distancia real del centro de la zona al CD y se
       -- le aplica el mismo factor de via publica que usa el seed base.
       round((ST_Distance(cd.geo_point, ST_SetSRID(ST_MakePoint(p.lon, p.lat), 4326)::geography)
              / 1000 * 1.7)::numeric, 1),
       greatest(30, round(ST_Distance(cd.geo_point, ST_SetSRID(ST_MakePoint(p.lon, p.lat), 4326)::geography)
              / 1000 * 1.7 / 16 * 60)::int),
       s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN zone_plan p
JOIN tms.company c ON c.code = p.company_code
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN tms.zone z ON z.company_id = c.id AND z.code = p.zone_code
JOIN tms.location cd ON cd.company_id = c.id
   AND cd.code = CASE WHEN p.company_code = 'DEMO-LIMA' THEN 'CD-CALLAO' ELSE 'CD-AQP' END
LEFT JOIN tms.frequency f ON f.company_id = c.id AND f.code = 'F-LUNVIE'
ON CONFLICT (company_id, code) DO NOTHING;

-- Tres paradas por ruta. NOT EXISTS y no ON CONFLICT: `uq_route_stop_route_sequence` es
-- DEFERRABLE y PostgreSQL no admite una restriccion diferible como arbitro de ON CONFLICT.
INSERT INTO tms.route_stop (route_id, company_id, destination_id, sequence, created_by, updated_by)
SELECT r.id, r.company_id, d.id, d.seq, s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN zone_plan p
JOIN tms.company c ON c.code = p.company_code
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN tms.route r ON r.company_id = c.id AND r.code = 'R-' || p.prefix
CROSS JOIN LATERAL (
    SELECT l.id, row_number() OVER (ORDER BY l.code) AS seq
    FROM tms.location l
    WHERE l.company_id = c.id AND l.code LIKE p.prefix || '-%'
    ORDER BY l.code LIMIT 3
) d
WHERE NOT EXISTS (SELECT 1 FROM tms.route_stop rs WHERE rs.route_id = r.id AND rs.sequence = d.seq);

-- 7. Tarifarios ------------------------------------------------------------------------------------
--
-- Uno de ambito CARRIER por transportista nuevo: es la red que garantiza que cualquier viaje suyo
-- se pueda valorar. Los importes varian con el codigo para que comparar tarifas tenga sentido.

INSERT INTO tms.rate_card (company_id, code, name, carrier_id, scope, currency, valid_from,
                           base_amount, amount_per_km, amount_per_stop, fuel_surcharge_percent,
                           minimum_amount, created_by, updated_by)
SELECT ca.company_id, 'TAR-' || ca.code, 'Tarifa base ' || ca.business_name, ca.id,
       'CARRIER', 'PEN', DATE '2026-01-01',
       120 + (abs(hashtext(ca.code)) % 9) * 10,
       2.00 + (abs(hashtext(ca.code)) % 8) * 0.15,
       15 + (abs(hashtext(ca.code)) % 4) * 5,
       6.0 + (abs(hashtext(ca.code)) % 5),
       160 + (abs(hashtext(ca.code)) % 10) * 15,
       s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN tms.carrier ca
JOIN tms.company c ON c.id = ca.company_id
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
WHERE NOT EXISTS (
    SELECT 1 FROM tms.rate_card rc WHERE rc.company_id = ca.company_id AND rc.carrier_id = ca.id
      AND rc.scope = 'CARRIER')
ON CONFLICT (company_id, code) DO NOTHING;

-- 8. Puertas de las tiendas nuevas -------------------------------------------------------------------
--
-- Misma regla que el seed base: una tienda recibe por una puerta, media hora por cita.

INSERT INTO tms.location_resource (company_id, location_id, code, name, resource_type,
                                   default_slot_minutes, created_by, updated_by)
SELECT l.company_id, l.id, 'P-01', 'Puerta de recepcion', 'DOOR', 30, s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN tms.location l
JOIN tms.company c ON c.id = l.company_id
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
WHERE l.location_type = 'STORE'
ON CONFLICT (location_id, code) DO NOTHING;

COMMIT;
