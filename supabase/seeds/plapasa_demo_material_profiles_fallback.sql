-- TMS by EBIM - RESPALDO DE PERFILES DE MATERIAL PARA LA DEMO PLAPASA. NO ES UNA MIGRACIÓN.
--
-- ===========================================================================================
--  SÓLO SI EL ENLACE CON EL EWM NO ESTÁ VIVO EL DÍA DE LA DEMO.
--
--  El dueño del perfil físico de un material es el almacén (ADR-022, D-02): el EWM lo manda por
--  `POST /integration/v1/material-profiles/batch` (interfaz TMS_MATP, botón «Reenviar perfiles al
--  TMS»), y ése es el camino que la demo cuenta. Este fichero existe para un único caso: que el
--  EWM desplegado todavía no tenga la v1.1 (EWM V56) o que el conector no esté configurado, y haya
--  que mostrar igual el efecto del anidado. Correrlo cuando el enlace funciona no hace daño, pero
--  tampoco hace falta.
-- ===========================================================================================
--
-- Cómo se distingue del de verdad: `source_system = 'PLAPASA_DEMO_SEED'` (la pantalla Maestros ›
-- Perfiles de material lo muestra como origen) y `source_updated_at = 2026-10-01`, una fecha
-- anterior a cualquier envío real del EWM. Así:
--   * si el EWM manda después, su `sourceUpdatedAt` es más nuevo y la copia se ACTUALIZA
--     (no queda STALE), y desde ahí el origen dice EWM_EBIM;
--   * si el EWM ya mandó antes, este fichero no toca nada (ON CONFLICT DO NOTHING).
-- No reemplaza a la API: no escribe auditoría ni `updated_by_client`. Es un respaldo de demo.
--
-- Orden: DESPUÉS de `plapasa_demo_seed.sql` y ANTES de mandar los pedidos
-- (`scripts/demo/plapasa_tms_demo.py`): un pedido copia el perfil al entrar, y un perfil que llega
-- después no reescribe pedidos ya creados (snapshot, ADR-022 §2 regla 4).
--
--     psql "$QAS_DB_URL" -v ON_ERROR_STOP=1 -f supabase/seeds/plapasa_demo_material_profiles_fallback.sql
--
-- VALORES. Los MISMOS que siembra el EWM de la demo, copiados de su blueprint
-- (`WMS-by-EBIM/demo/plapasa/blueprint.json`, sólo lectura), los veinticinco materiales del catálogo:
--   medidas y volumen unitario   `catalog[]` (plapasa.com, 2026-10-14), de cm a m;
--   modo, anidado, carga encima  `physicalProfile.profiles` (V56 del EWM), anidado de cm a m;
--   unidades por pila            `production.stacking.stackMaxQuantity` (V54 del EWM).
-- Todos son SUPUESTOS de demo del lado EWM, a medir en planta (DISCOVERY del EWM). Dos decisiones del
-- EWM que conviene saber contar: las SILLAS van NESTABLE (una silla monobloque se encaja en la de
-- abajo: STACKABLE daría pilas de 9 m) y lo que el EWM no declara queda SIN modo (no es NONE): esas
-- líneas quedan «sin medir» y se CUENTAN, nunca se estiman (regla 199 del EWM).
-- El peso unitario va NULL: el sitio de PLAPASA no lo publica y el EWM no lo inventa. El peso de los
-- pedidos lo trae el ERP en la línea (y con él se aplica la carga máxima encima).
--
-- Si el EWM terminó sembrando otros valores, ganan los del EWM: correr «Reenviar perfiles al TMS».

BEGIN;

INSERT INTO tms.material_profile (company_id, material_code, description, source_system, source_updated_at,
                                  length_m, width_m, height_m, unit_volume_m3, stack_mode, stack_max_quantity,
                                  nest_increment_m, max_top_load_kg)
SELECT c.id, v.code, v.description, 'PLAPASA_DEMO_SEED', TIMESTAMPTZ '2026-10-01 00:00:00-05:00',
       v.l, v.w, v.h, v.m3, v.mode, v.max_per_stack, v.nest, v.top_load
FROM tms.company c
JOIN tms.organization o ON o.id = c.organization_id AND o.code = 'QAS-PLAPASA'
CROSS JOIN (VALUES
    -- código        descripción                                L       A       H       m³/u     modo         pila  anidado  carga encima (kg)
    ('PLP-121',     'Balde 20 l',                              0.3500, 0.3500, 0.3200, 0.0392,  'NESTABLE',  25,   0.0300, NULL),
    ('PLP-111',     'Balde 10 l',                              0.2700, 0.2700, 0.2450, 0.01786, 'NESTABLE',  30,   0.0250, NULL),
    ('PLP-123',     'Surtidor de Agua 20 l',                   0.3550, 0.3550, 0.3600, 0.04537, NULL,        NULL, NULL,   NULL),
    ('PLP-311',     'Tacho Americano C/T 72 l',                0.5350, 0.5350, 0.5450, 0.15599, 'NESTABLE',  NULL, 0.0400, NULL),
    ('PLP-332',     'Papelero Griego Mediano 7 l',             0.2300, 0.2300, 0.2550, 0.01349, 'NESTABLE',  NULL, 0.0150, NULL),
    ('PLP-571',     'Vaso Party Grande 0,8 l',                 0.1050, 0.1050, 0.1750, 0.00193, 'NESTABLE',  100,  0.0120, NULL),
    ('PLP-563',     'Jarra Gigante 4 l C/T',                   0.1900, 0.1900, 0.2350, 0.00848, NULL,        20,   NULL,   NULL),
    ('PLP-532',     'Tomatodo Sporty 1 l Tapa Pull Push',      0.1000, 0.1000, 0.2200, 0.0022,  NULL,        NULL, NULL,   NULL),
    ('PLP-204',     'Lavacara de 55 cm',                       0.5500, 0.5500, 0.1500, 0.04537, 'NESTABLE',  30,   0.0200, NULL),
    ('PLP-700',     'Silla Costabela',                         0.6200, 0.5950, 0.9100, 0.3357,  'NESTABLE',  NULL, 0.0600, NULL),
    ('PLP-704',     'Silla Roma Sin Brazo',                    0.4900, 0.4650, 0.8650, 0.19709, 'NESTABLE',  NULL, 0.0600, NULL),
    ('PLP-803',     'Banco Portobelo',                         0.3550, 0.3550, 0.4600, 0.05797, NULL,        NULL, NULL,   NULL),
    ('PLP-1100-5',  'Cómoda Rattan Expreso 5 pisos',           0.5400, 0.4600, 1.0350, 0.25709, NULL,        NULL, NULL,   NULL),
    ('PLP-127NE',   'Pro Rack',                                0.4500, 0.9100, 1.8500, 0.75757, NULL,        NULL, NULL,   NULL),
    ('PLP-1001',    'Silla Bambini',                           0.3400, 0.3150, 0.5800, 0.06212, 'NESTABLE',  NULL, 0.0400, NULL),
    ('PLP-1222',    'Utility Box Heavy Duty #3 48 l',          0.5600, 0.4150, 0.3600, 0.08366, 'STACKABLE', NULL, NULL,   60.000),
    ('PLP-N105',    'Balde Eco 15 l',                          0.3050, 0.3050, 0.2800, 0.02605, 'NESTABLE',  30,   0.0250, NULL),
    ('PLP-1243',    'Cesto Spirella M 7 l',                    0.3150, 0.2100, 0.1450, 0.00959, 'NESTABLE',  24,   0.0150, NULL),
    ('PLP-1601',    'Gaveta Agro Alta',                        0.5250, 0.3500, 0.2900, 0.05329, 'STACKABLE', 10,   NULL,   150.000),
    ('PLP-1608',    'Gaveta Industrial Calada',                0.5950, 0.3900, 0.3050, 0.07078, 'STACKABLE', 12,   NULL,   200.000),
    ('PLP-1623',    'Gaveta Logix',                            0.6130, 0.4180, 0.3250, 0.08328, 'STACKABLE', NULL, NULL,   200.000),
    ('PLP-1618',    'Gaveta Smart Plegable Grande',            0.6000, 0.4000, 0.2550, 0.0612,  'STACKABLE', NULL, NULL,   120.000),
    ('PLP-1405',    'Jaba 24 botellas Alta 500 cc',            0.4700, 0.3100, 0.3150, 0.0459,  'STACKABLE', 10,   NULL,   120.000),
    ('PLP-323',     'Tacho Forte C/T Plana 106 l',             0.6250, 0.4950, 0.7800, 0.24131, 'NESTABLE',  NULL, 0.0500, NULL),
    ('PLP-1633-AZ', 'Pallet Plástico 1200x1000 Azul',          1.2000, 1.0000, 0.1500, 0.18,    'STACKABLE', NULL, NULL,   1000.000)
) AS v(code, description, l, w, h, m3, mode, max_per_stack, nest, top_load)
WHERE c.code = 'QAS-PLAPASA'
ON CONFLICT (company_id, material_code) DO NOTHING;

COMMIT;
