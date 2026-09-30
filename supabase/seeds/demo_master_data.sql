-- TMS by EBIM - MAESTROS DE LA ORGANIZACION DEMO. NO ES UNA MIGRACION.
--
-- Hermano de `local_dev_seed.sql`, que crea la tenencia (organizacion DEMO, sus dos companias,
-- los tres usuarios y sus membresias) y para ahi. Este fichero llena los maestros: zonas,
-- ubicaciones con sus roles, frecuencias, rutas, transportistas, tipos de vehiculo, vehiculos
-- y conductores. Requiere que `local_dev_seed.sql` se haya aplicado antes.
--
-- POR QUE NO HAY TABLA DE ORIGENES NI DE DESTINOS. Desde V23 un origen no es un registro
-- propio: es una `tms.location` que tiene el rol ORIGIN, y un destino es la misma fila con el
-- rol DESTINATION (`OriginLookupAdapter`, `DestinationLookupAdapter`). Las tablas `tms.origin`
-- y `tms.destination` sobreviven en el esquema pero ninguna entidad JPA las mapea, asi que
-- sembrarlas llenaria la base sin cambiar una sola pantalla. Por eso `T-SURCO` lleva los dos
-- roles: es el caso que da sentido al modelo - una tienda recibe el reparto y origina la
-- devolucion, con una direccion y un par de coordenadas.
--
-- GEOGRAFIA REAL. Las coordenadas son de los distritos que nombran. Importa mas de lo que
-- parece: `tms.location.geo_point` es una columna generada `geography(Point,4326)`, el
-- estimador geodesico de ADR-010 calcula distancias sobre ella, y con coordenadas inventadas
-- las rutas saldrian con kilometrajes absurdos.
--
-- FECHAS FIJAS, NUNCA CURRENT_DATE. Un seed que se mueve con el reloj da un entorno distinto
-- en cada ejecucion, y entonces "aqui no se ve igual" deja de ser diagnosticable.
--
-- RE-EJECUTABLE: cada sentencia esta guardada. Correrlo dos veces no duplica ni falla.

BEGIN;

-- Autoria de los maestros. Las columnas created_by/updated_by apuntan a tms.app_user, y
-- dejarlas nulas haria que la pantalla de auditoria mostrara filas sin responsable.
CREATE TEMP TABLE seed_ctx ON COMMIT DROP AS
SELECT (SELECT id FROM tms.app_user WHERE email = 'admin@demo.local') AS author_id,
       (SELECT c.id FROM tms.company c JOIN tms.organization o ON o.id = c.organization_id
         WHERE o.code = 'DEMO' AND c.code = 'DEMO-LIMA') AS lima_id,
       (SELECT c.id FROM tms.company c JOIN tms.organization o ON o.id = c.organization_id
         WHERE o.code = 'DEMO' AND c.code = 'DEMO-AREQUIPA') AS aqp_id;

-- 1. Zonas ----------------------------------------------------------------------------------

INSERT INTO tms.zone (company_id, code, name, description, created_by, updated_by)
SELECT v.company_id, v.code, v.name, v.description, s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN LATERAL (VALUES
    (s.lima_id, 'Z-NORTE',     'Lima Norte',       'Los Olivos, Independencia, Comas'),
    (s.lima_id, 'Z-SUR',       'Lima Sur',         'Villa Maria del Triunfo, Chorrillos, Lurin'),
    (s.lima_id, 'Z-ESTE',      'Lima Este',        'Ate, La Molina, Santa Anita'),
    (s.lima_id, 'Z-CENTRO',    'Lima Centro',      'Cercado, San Isidro, Miraflores, Surco'),
    (s.lima_id, 'Z-CALLAO',    'Callao',           'Puerto y zona industrial'),
    (s.aqp_id,  'Z-AQP-NORTE', 'Arequipa Norte',   'Cayma, Cerro Colorado'),
    (s.aqp_id,  'Z-AQP-CENTRO','Arequipa Centro',  'Cercado y Yanahuara')
) AS v(company_id, code, name, description)
ON CONFLICT (company_id, code) DO NOTHING;

-- 2. Ubicaciones ----------------------------------------------------------------------------
--
-- `service_time_minutes` es el tiempo de atencion en el punto, y ADR-011 lo usa como uno de los
-- terminos de la estimacion de llegada. Un centro de distribucion carga mas despacio que una
-- tienda, y por eso no son todos iguales.
--
-- `geofence_radius_m` solo informa (ADR-011): dibuja un circulo, y jamas mueve un ciclo de vida.
-- La deteccion automatica de llegada sigue diferida.

INSERT INTO tms.location (company_id, code, name, location_type, address, district, province,
                          department, country, latitude, longitude, time_zone, zone_id,
                          service_time_minutes, geofence_radius_m, created_by, updated_by)
SELECT v.company_id, v.code, v.name, v.location_type, v.address, v.district, v.province,
       v.department, 'PE', v.lat, v.lon, v.tz, z.id, v.service_minutes, v.geofence,
       s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN LATERAL (VALUES
    -- Lima: dos centros de distribucion
    (s.lima_id, 'CD-CALLAO', 'CD Callao', 'DISTRIBUTION_CENTER', 'Av. Argentina 3093',
     'Callao', 'Callao', 'Callao', -12.043200, -77.106300, 'America/Lima', 'Z-CALLAO', 45, 200),
    (s.lima_id, 'CD-LURIN', 'CD Lurin', 'DISTRIBUTION_CENTER', 'Antigua Panamericana Sur Km 32',
     'Lurin', 'Lima', 'Lima', -12.274500, -76.881000, 'America/Lima', 'Z-SUR', 40, 250),
    -- Lima: tiendas
    (s.lima_id, 'T-MIRAFLORES', 'Tienda Miraflores', 'STORE', 'Av. Jose Larco 500',
     'Miraflores', 'Lima', 'Lima', -12.121900, -77.029700, 'America/Lima', 'Z-CENTRO', 25, 75),
    (s.lima_id, 'T-SAN-ISIDRO', 'Tienda San Isidro', 'STORE', 'Av. Javier Prado Este 1200',
     'San Isidro', 'Lima', 'Lima', -12.092800, -77.033400, 'America/Lima', 'Z-CENTRO', 25, 75),
    (s.lima_id, 'T-SURCO', 'Tienda Surco', 'STORE', 'Av. Primavera 800',
     'Santiago de Surco', 'Lima', 'Lima', -12.110900, -76.976400, 'America/Lima', 'Z-CENTRO', 30, 80),
    (s.lima_id, 'T-SAN-BORJA', 'Tienda San Borja', 'STORE', 'Av. Aviacion 2400',
     'San Borja', 'Lima', 'Lima', -12.090600, -77.000300, 'America/Lima', 'Z-CENTRO', 25, 75),
    (s.lima_id, 'T-LA-MOLINA', 'Tienda La Molina', 'STORE', 'Av. Raul Ferrero 1200',
     'La Molina', 'Lima', 'Lima', -12.079000, -76.935000, 'America/Lima', 'Z-ESTE', 30, 90),
    (s.lima_id, 'T-ATE', 'Tienda Ate', 'STORE', 'Carretera Central Km 6',
     'Ate', 'Lima', 'Lima', -12.040000, -76.930000, 'America/Lima', 'Z-ESTE', 35, 100),
    (s.lima_id, 'T-LOS-OLIVOS', 'Tienda Los Olivos', 'STORE', 'Av. Alfredo Mendiola 3400',
     'Los Olivos', 'Lima', 'Lima', -11.986000, -77.073000, 'America/Lima', 'Z-NORTE', 30, 85),
    (s.lima_id, 'T-INDEPENDENCIA', 'Tienda Independencia', 'STORE', 'Av. Tupac Amaru 1000',
     'Independencia', 'Lima', 'Lima', -11.990000, -77.056000, 'America/Lima', 'Z-NORTE', 30, 85),
    (s.lima_id, 'T-VMT', 'Tienda Villa Maria', 'STORE', 'Av. Pachacutec 1500',
     'Villa Maria del Triunfo', 'Lima', 'Lima', -12.160000, -76.940000, 'America/Lima', 'Z-SUR', 30, 90),
    (s.lima_id, 'T-CHORRILLOS', 'Tienda Chorrillos', 'STORE', 'Av. Huaylas 500',
     'Chorrillos', 'Lima', 'Lima', -12.172000, -77.020000, 'America/Lima', 'Z-SUR', 30, 85),
    (s.lima_id, 'T-CERCADO', 'Tienda Cercado', 'STORE', 'Av. Abancay 500',
     'Lima', 'Lima', 'Lima', -12.051000, -77.028000, 'America/Lima', 'Z-CENTRO', 35, 70),
    -- Arequipa
    (s.aqp_id, 'CD-AQP', 'CD Arequipa', 'DISTRIBUTION_CENTER', 'Parque Industrial Mz. A Lt. 12',
     'Arequipa', 'Arequipa', 'Arequipa', -16.390000, -71.535000, 'America/Lima', 'Z-AQP-CENTRO', 40, 180),
    (s.aqp_id, 'T-CAYMA', 'Tienda Cayma', 'STORE', 'Av. Ejercito 1000',
     'Cayma', 'Arequipa', 'Arequipa', -16.383000, -71.556000, 'America/Lima', 'Z-AQP-NORTE', 25, 75),
    (s.aqp_id, 'T-CERRO-COLORADO', 'Tienda Cerro Colorado', 'STORE', 'Av. Aviacion 500',
     'Cerro Colorado', 'Arequipa', 'Arequipa', -16.369000, -71.573000, 'America/Lima', 'Z-AQP-NORTE', 25, 75),
    (s.aqp_id, 'T-YANAHUARA', 'Tienda Yanahuara', 'STORE', 'Av. Emmel 300',
     'Yanahuara', 'Arequipa', 'Arequipa', -16.391000, -71.546000, 'America/Lima', 'Z-AQP-CENTRO', 25, 70)
) AS v(company_id, code, name, location_type, address, district, province, department,
       lat, lon, tz, zone_code, service_minutes, geofence)
LEFT JOIN tms.zone z ON z.company_id = v.company_id AND z.code = v.zone_code
ON CONFLICT (company_id, code) DO NOTHING;

-- 3. Roles de ubicacion ----------------------------------------------------------------------
--
-- Lo que convierte una ubicacion en origen o en destino seleccionable. Sin esta tabla las
-- pantallas de Origenes y Destinos salen vacias aunque haya ubicaciones.

INSERT INTO tms.location_role (location_id, role)
SELECT l.id, v.role
FROM tms.location l
JOIN tms.company c ON c.id = l.company_id
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN (VALUES
    ('CD-CALLAO', 'ORIGIN'),
    ('CD-LURIN', 'ORIGIN'),
    ('CD-AQP', 'ORIGIN'),
    -- Surco envia y recibe: destino del reparto, origen de la devolucion.
    ('T-SURCO', 'ORIGIN'),
    ('T-SURCO', 'DESTINATION'),
    ('T-MIRAFLORES', 'DESTINATION'),
    ('T-SAN-ISIDRO', 'DESTINATION'),
    ('T-SAN-BORJA', 'DESTINATION'),
    ('T-LA-MOLINA', 'DESTINATION'),
    ('T-ATE', 'DESTINATION'),
    ('T-LOS-OLIVOS', 'DESTINATION'),
    ('T-INDEPENDENCIA', 'DESTINATION'),
    ('T-VMT', 'DESTINATION'),
    ('T-CHORRILLOS', 'DESTINATION'),
    ('T-CERCADO', 'DESTINATION'),
    ('T-CAYMA', 'DESTINATION'),
    ('T-CERRO-COLORADO', 'DESTINATION'),
    ('T-YANAHUARA', 'DESTINATION')
) AS v(code, role) ON v.code = l.code
ON CONFLICT (location_id, role) DO NOTHING;

-- 4. Frecuencias ----------------------------------------------------------------------------
--
-- `cutoff_time` es la hora limite para que un pedido entre en el reparto de ese dia, y
-- `lead_time_days` cuantos dias antes hay que pedirlo. Se llenan a proposito: una frecuencia
-- sin corte ni anticipacion no distingue un reparto diario de uno semanal.

INSERT INTO tms.frequency (company_id, code, name, description, created_by, updated_by)
SELECT v.company_id, v.code, v.name, v.description, s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN LATERAL (VALUES
    (s.lima_id, 'F-DIARIO',  'Diario',                     'Reparto los siete dias'),
    (s.lima_id, 'F-LUNVIE',  'Lunes a viernes',            'Reparto en dias laborables'),
    (s.lima_id, 'F-LMV',     'Lunes, miercoles y viernes', 'Reparto en dias alternos'),
    (s.lima_id, 'F-SABADO',  'Solo sabados',               'Reposicion de fin de semana'),
    (s.aqp_id,  'F-LUNVIE',  'Lunes a viernes',            'Reparto en dias laborables')
) AS v(company_id, code, name, description)
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO tms.frequency_weekly_rule (frequency_id, day_of_week, enabled, cutoff_time,
                                       lead_time_days, created_by, updated_by)
SELECT f.id, v.day, true, v.cutoff, v.lead, s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN tms.frequency f
JOIN tms.company c ON c.id = f.company_id
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN (VALUES
    ('F-DIARIO', 1, TIME '16:00', 1), ('F-DIARIO', 2, TIME '16:00', 1),
    ('F-DIARIO', 3, TIME '16:00', 1), ('F-DIARIO', 4, TIME '16:00', 1),
    ('F-DIARIO', 5, TIME '16:00', 1), ('F-DIARIO', 6, TIME '12:00', 1),
    ('F-DIARIO', 7, TIME '12:00', 2),
    ('F-LUNVIE', 1, TIME '17:00', 1), ('F-LUNVIE', 2, TIME '17:00', 1),
    ('F-LUNVIE', 3, TIME '17:00', 1), ('F-LUNVIE', 4, TIME '17:00', 1),
    ('F-LUNVIE', 5, TIME '17:00', 1),
    ('F-LMV', 1, TIME '15:00', 2), ('F-LMV', 3, TIME '15:00', 2), ('F-LMV', 5, TIME '15:00', 2),
    ('F-SABADO', 6, TIME '11:00', 3)
) AS v(code, day, cutoff, lead) ON v.code = f.code
ON CONFLICT (frequency_id, day_of_week) DO NOTHING;

-- Que frecuencia sirve a cada tienda. `effective_from` fija desde cuando rige; sin fecha de
-- fin, porque son los acuerdos vigentes.
INSERT INTO tms.location_frequency (company_id, location_id, frequency_id, effective_from,
                                    created_by, updated_by)
SELECT l.company_id, l.id, f.id, DATE '2026-01-01', s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN (VALUES
    ('T-MIRAFLORES', 'F-DIARIO'),
    ('T-SAN-ISIDRO', 'F-DIARIO'),
    ('T-SURCO', 'F-LUNVIE'),
    ('T-SAN-BORJA', 'F-LUNVIE'),
    ('T-LA-MOLINA', 'F-LMV'),
    ('T-ATE', 'F-LMV'),
    ('T-LOS-OLIVOS', 'F-LMV'),
    ('T-INDEPENDENCIA', 'F-LMV'),
    ('T-VMT', 'F-LUNVIE'),
    ('T-CHORRILLOS', 'F-LUNVIE'),
    ('T-CERCADO', 'F-SABADO'),
    ('T-CAYMA', 'F-LUNVIE'),
    ('T-CERRO-COLORADO', 'F-LUNVIE'),
    ('T-YANAHUARA', 'F-LUNVIE')
) AS v(location_code, frequency_code)
JOIN tms.location l ON l.code = v.location_code
JOIN tms.company c ON c.id = l.company_id
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN tms.frequency f ON f.company_id = l.company_id AND f.code = v.frequency_code
-- Sin restriccion unica declarada en la tabla, asi que la idempotencia se guarda a mano.
WHERE NOT EXISTS (
    SELECT 1 FROM tms.location_frequency lf
    WHERE lf.location_id = l.id AND lf.frequency_id = f.id);

-- 5. Rutas -----------------------------------------------------------------------------------
--
-- Distancia y duracion de referencia son el valor comercial acordado, no una medicion: ADR-010
-- deja el calculo real detras de `RoutingPort`, y una estimacion sigue siendo una estimacion
-- aunque este cacheada. Aqui sirven de contraste con lo que calcule el estimador geodesico.
--
-- Los kilometrajes guardan un factor de 1,5 a 1,8 sobre la distancia geodesica que PostGIS
-- calcula entre las mismas coordenadas - la proporcion habitual entre la linea recta y la via
-- publica en trama urbana. No es un adorno: un maestro cuyo kilometraje contradice su propia
-- geometria envenena cualquier comparacion posterior contra el estimador de ADR-010, y entonces
-- deja de servir para probar nada.

INSERT INTO tms.route (company_id, code, name, origin_id, zone_id, frequency_id,
                       reference_distance_km, reference_duration_minutes, created_by, updated_by)
SELECT l.company_id, v.code, v.name, l.id, z.id, f.id, v.km, v.minutes, s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN (VALUES
    ('R-CENTRO', 'CD Callao - Cercado - San Isidro - Miraflores', 'CD-CALLAO', 'Z-CENTRO',    'F-LUNVIE', 27.0, 95),
    ('R-NORTE',  'CD Callao - Los Olivos - Independencia',        'CD-CALLAO', 'Z-NORTE',     'F-LMV',    15.5, 60),
    ('R-ESTE',   'CD Callao - Ate - La Molina - San Borja',       'CD-CALLAO', 'Z-ESTE',      'F-LMV',    52.0, 210),
    ('R-SUR',    'CD Lurin - Villa Maria - Chorrillos - Surco',   'CD-LURIN',  'Z-SUR',       'F-LUNVIE', 47.5, 195),
    ('R-AQP',    'CD Arequipa - Cayma - Cerro Colorado',          'CD-AQP',    'Z-AQP-NORTE', 'F-LUNVIE',  8.5, 30)
) AS v(code, name, origin_code, zone_code, frequency_code, km, minutes)
JOIN tms.location l ON l.code = v.origin_code
JOIN tms.company c ON c.id = l.company_id
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
LEFT JOIN tms.zone z ON z.company_id = l.company_id AND z.code = v.zone_code
LEFT JOIN tms.frequency f ON f.company_id = l.company_id AND f.code = v.frequency_code
ON CONFLICT (company_id, code) DO NOTHING;

-- El orden de las paradas. Guardado por NOT EXISTS y no por ON CONFLICT:
-- `uq_route_stop_route_sequence` es DEFERRABLE INITIALLY DEFERRED - lo tiene que ser, porque
-- reordenar paradas pasa por estados intermedios con secuencias repetidas - y PostgreSQL no
-- admite una restriccion diferible como arbitro de ON CONFLICT.
INSERT INTO tms.route_stop (route_id, company_id, destination_id, sequence,
                            service_time_override_minutes, created_by, updated_by)
SELECT r.id, r.company_id, l.id, v.seq, v.override, s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN (VALUES
    ('R-CENTRO', 'T-CERCADO',        1, NULL::integer),
    ('R-CENTRO', 'T-SAN-ISIDRO',     2, NULL),
    ('R-CENTRO', 'T-MIRAFLORES',     3, NULL),
    ('R-NORTE',  'T-LOS-OLIVOS',     1, NULL),
    ('R-NORTE',  'T-INDEPENDENCIA',  2, NULL),
    -- Ate descarga a pie de carretera y tarda mas que su tiempo estandar.
    ('R-ESTE',   'T-ATE',            1, 45),
    ('R-ESTE',   'T-LA-MOLINA',      2, NULL),
    ('R-ESTE',   'T-SAN-BORJA',      3, NULL),
    ('R-SUR',    'T-VMT',            1, NULL),
    ('R-SUR',    'T-CHORRILLOS',     2, NULL),
    ('R-SUR',    'T-SURCO',          3, NULL),
    ('R-AQP',    'T-CAYMA',          1, NULL),
    ('R-AQP',    'T-CERRO-COLORADO', 2, NULL)
) AS v(route_code, destination_code, seq, override)
JOIN tms.route r ON r.code = v.route_code
JOIN tms.company c ON c.id = r.company_id
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
JOIN tms.location l ON l.company_id = r.company_id AND l.code = v.destination_code
WHERE NOT EXISTS (
    SELECT 1 FROM tms.route_stop rs
    WHERE rs.route_id = r.id AND rs.sequence = v.seq);

-- 6. Transportistas --------------------------------------------------------------------------
--
-- `ck_carrier_email_normalized` exige minusculas y `ck_carrier_tax_id_value_normalized`
-- mayusculas: el esquema normaliza, no se limita a validar.

INSERT INTO tms.carrier (company_id, code, business_name, tax_id_type, tax_id_value,
                         contact_name, phone, email, created_by, updated_by)
SELECT v.company_id, v.code, v.business_name, 'RUC', v.ruc, v.contact, v.phone, v.email,
       s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN LATERAL (VALUES
    (s.lima_id, 'C-PACIFICO', 'Transportes del Pacifico S.A.C.', '20100000011',
     'Rosa Ramirez', '+51 1 555 1001', 'operaciones@pacifico.demo'),
    (s.lima_id, 'C-ANDINA', 'Andina Cargo E.I.R.L.', '20100000022',
     'Luis Mendoza', '+51 1 555 1002', 'despacho@andinacargo.demo'),
    (s.lima_id, 'C-EXPRESS', 'Log Express Peru S.A.', '20100000033',
     'Carmen Diaz', '+51 1 555 1003', 'trafico@logexpress.demo'),
    (s.aqp_id, 'C-MISTI', 'Transportes Misti S.R.L.', '20100000044',
     'Jorge Paredes', '+51 54 555 2001', 'operaciones@misti.demo')
) AS v(company_id, code, business_name, ruc, contact, phone, email)
ON CONFLICT (company_id, code) DO NOTHING;

-- 7. Tipos de vehiculo -----------------------------------------------------------------------
--
-- `ck_vehicle_type_temperature_requires_controlled` impide poner un rango de temperatura a un
-- vehiculo que no es refrigerado: solo el REFRIG-8T lo lleva.

INSERT INTO tms.vehicle_type (company_id, code, name, max_weight_kg, max_volume_m3, max_pallets,
                              length_m, width_m, height_m, body_type, temperature_controlled,
                              min_temperature_celsius, max_temperature_celsius, axles,
                              created_by, updated_by)
SELECT v.company_id, v.code, v.name, v.kg, v.m3, v.pallets, v.len, v.wid, v.hei, v.body,
       v.cold, v.tmin, v.tmax, v.axles, s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN LATERAL (VALUES
    (s.lima_id, 'VT-FURGON-3T', 'Furgon 3 toneladas', 3000, 18, 6, 4.2, 2.0, 2.1,
     'DRY_VAN', false, NULL::numeric, NULL::numeric, 2),
    (s.lima_id, 'VT-CAMION-10T', 'Camion 10 toneladas', 10000, 40, 20, 7.2, 2.4, 2.6,
     'DRY_VAN', false, NULL, NULL, 2),
    (s.lima_id, 'VT-REFRIG-8T', 'Refrigerado 8 toneladas', 8000, 32, 16, 6.5, 2.4, 2.5,
     'REFRIGERATED', true, -18, 4, 2),
    (s.lima_id, 'VT-TRAILER-30T', 'Trailer 30 toneladas', 30000, 90, 33, 13.6, 2.5, 2.7,
     'CONTAINER', false, NULL, NULL, 5),
    (s.aqp_id, 'VT-CAMION-10T', 'Camion 10 toneladas', 10000, 40, 20, 7.2, 2.4, 2.6,
     'DRY_VAN', false, NULL, NULL, 2),
    (s.aqp_id, 'VT-FURGON-3T', 'Furgon 3 toneladas', 3000, 18, 6, 4.2, 2.0, 2.1,
     'DRY_VAN', false, NULL, NULL, 2)
) AS v(company_id, code, name, kg, m3, pallets, len, wid, hei, body, cold, tmin, tmax, axles)
ON CONFLICT (company_id, code) DO NOTHING;

-- 8. Vehiculos -------------------------------------------------------------------------------
--
-- `carrier_id` nulo significa flota propia, y es el caso que da sentido a
-- `own_fleet_cost_profile` y al permiso `costing.own_fleet`. Las placas siguen
-- `^[A-Z0-9-]{4,12}$`, el formato peruano.

INSERT INTO tms.vehicle (company_id, code, license_plate, vehicle_type_id, carrier_id,
                         availability_status, max_weight_override_kg, created_by, updated_by)
SELECT vt.company_id, v.code, v.plate, vt.id, ca.id, v.status, v.override, s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN (VALUES
    ('V-001', 'B7A-701', 'VT-CAMION-10T',  'C-PACIFICO', 'DEMO-LIMA',     'AVAILABLE',      NULL::numeric),
    ('V-002', 'B7A-702', 'VT-CAMION-10T',  'C-PACIFICO', 'DEMO-LIMA',     'AVAILABLE',      NULL),
    -- Restringido por mantenimiento de suspension: carga menos de lo que su tipo permite.
    ('V-003', 'C2B-810', 'VT-CAMION-10T',  'C-ANDINA',   'DEMO-LIMA',     'AVAILABLE',      8500),
    ('V-004', 'C2B-811', 'VT-REFRIG-8T',   'C-ANDINA',   'DEMO-LIMA',     'AVAILABLE',      NULL),
    ('V-005', 'D3C-920', 'VT-FURGON-3T',   'C-EXPRESS',  'DEMO-LIMA',     'AVAILABLE',      NULL),
    ('V-006', 'D3C-921', 'VT-FURGON-3T',   'C-EXPRESS',  'DEMO-LIMA',     'IN_MAINTENANCE', NULL),
    ('V-007', 'E4D-330', 'VT-TRAILER-30T', 'C-PACIFICO', 'DEMO-LIMA',     'OUT_OF_SERVICE', NULL),
    -- Flota propia: sin transportista.
    ('V-008', 'F5E-100', 'VT-CAMION-10T',  NULL,         'DEMO-LIMA',     'AVAILABLE',      NULL),
    ('V-009', 'F5E-101', 'VT-FURGON-3T',   NULL,         'DEMO-LIMA',     'AVAILABLE',      NULL),
    ('V-010', 'V8G-400', 'VT-CAMION-10T',  'C-MISTI',    'DEMO-AREQUIPA', 'AVAILABLE',      NULL),
    ('V-011', 'V8G-401', 'VT-FURGON-3T',   'C-MISTI',    'DEMO-AREQUIPA', 'AVAILABLE',      NULL)
) AS v(code, plate, type_code, carrier_code, company_code, status, override)
JOIN tms.company c ON c.code = v.company_code
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
-- El tipo se busca dentro de la compania del vehiculo: VT-CAMION-10T existe en las dos, y sin
-- este predicado un vehiculo de Arequipa podria colgar del tipo de Lima.
JOIN tms.vehicle_type vt ON vt.company_id = c.id AND vt.code = v.type_code
LEFT JOIN tms.carrier ca ON ca.company_id = c.id AND ca.code = v.carrier_code
ON CONFLICT (company_id, code) DO NOTHING;

-- 9. Conductores -----------------------------------------------------------------------------
--
-- Las categorias son las peruanas reales: A-IIIB para camion pesado, A-IIA para furgon ligero.
-- `license_expires_on` es futura en todos, porque un conductor con licencia vencida no es un
-- dato de ejemplo util - es un caso de excepcion, y merece crearse a mano cuando se pruebe.

INSERT INTO tms.driver (company_id, code, first_name, last_name, document_type, document_number,
                        phone, license_number, license_category, license_expires_on, carrier_id,
                        created_by, updated_by)
SELECT ca.company_id, v.code, v.first_name, v.last_name, 'DNI', v.dni, v.phone,
       v.license, v.category, v.expires, ca.id, s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN (VALUES
    ('D-001', 'Ana',    'Quispe Mamani', '40000001', '+51 987 000 001', 'Q40000001', 'A-IIIB', DATE '2028-03-31', 'C-PACIFICO'),
    ('D-002', 'Carlos', 'Huaman Rojas',  '40000002', '+51 987 000 002', 'H40000002', 'A-IIIA', DATE '2027-11-30', 'C-PACIFICO'),
    ('D-003', 'Miguel', 'Torres Vega',   '40000003', '+51 987 000 003', 'T40000003', 'A-IIIB', DATE '2029-06-30', 'C-ANDINA'),
    ('D-004', 'Lucia',  'Flores Chavez', '40000004', '+51 987 000 004', 'F40000004', 'A-IIA',  DATE '2027-08-31', 'C-ANDINA'),
    ('D-005', 'Jose',   'Ramos Ponce',   '40000005', '+51 987 000 005', 'R40000005', 'A-IIB',  DATE '2028-01-31', 'C-EXPRESS'),
    ('D-006', 'Elena',  'Vargas Soto',   '40000006', '+51 987 000 006', 'V40000006', 'A-IIIA', DATE '2028-09-30', 'C-EXPRESS'),
    ('D-007', 'Pedro',  'Ccama Apaza',   '40000007', '+51 987 000 007', 'C40000007', 'A-IIIB', DATE '2029-02-28', 'C-MISTI'),
    ('D-008', 'Rosa',   'Choque Nina',   '40000008', '+51 987 000 008', 'N40000008', 'A-IIA',  DATE '2027-12-31', 'C-MISTI')
) AS v(code, first_name, last_name, dni, phone, license, category, expires, carrier_code)
JOIN tms.carrier ca ON ca.code = v.carrier_code
JOIN tms.company c ON c.id = ca.company_id
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'DEMO'
ON CONFLICT (company_id, code) DO NOTHING;

-- Conductores de flota propia, sin transportista.
--
-- No son un adorno de completitud. TMS exige que el conductor y el vehiculo trabajen para el
-- mismo transportista - "Driver X works for a different carrier than the vehicle planned on this
-- trip" - y un vehiculo propio tiene `carrier_id` nulo. Sin conductores propios, V-008 y V-009
-- eran flota que no se podia tripular: aparecian en la lista, se dejaban asignar a un viaje, y el
-- viaje no podia salir. Lo descubrio una planificacion real, no una revision del fichero.
INSERT INTO tms.driver (company_id, code, first_name, last_name, document_type, document_number,
                        phone, license_number, license_category, license_expires_on, carrier_id,
                        created_by, updated_by)
SELECT s.lima_id, v.code, v.first_name, v.last_name, 'DNI', v.dni, v.phone,
       v.license, v.category, v.expires, NULL, s.author_id, s.author_id
FROM seed_ctx s
CROSS JOIN (VALUES
    ('D-009', 'Marco',  'Espinoza Leon', '40000009', '+51 987 000 009', 'E40000009', 'A-IIIA', DATE '2028-05-31'),
    ('D-010', 'Silvia', 'Castro Rojas',  '40000010', '+51 987 000 010', 'K40000010', 'A-IIA',  DATE '2029-01-31')
) AS v(code, first_name, last_name, dni, phone, license, category, expires)
ON CONFLICT (company_id, code) DO NOTHING;

COMMIT;
