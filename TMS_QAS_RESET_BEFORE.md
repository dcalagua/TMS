# TMS by EBIM — Estado del proyecto Supabase antes del reset de QAS

Capturado: **2026-08-25 15:39:14 UTC** (`SELECT now()` sobre la propia base).
Sólo consultas `SELECT`. Ningún secreto registrado aquí.

Este documento existe para dejar constancia de **qué se eliminó**, no para poder recuperarlo. El
propietario autorizó expresamente el borrado: `DATA_PRESERVATION_REQUIRED=NO`.

---

## Proyecto afectado

    Supabase project name : tms-by-ebim
    project ref           : ocxmsluzegpkezkpcqjj
    DB host               : db.ocxmsluzegpkezkpcqjj.supabase.co
    región                : us-east-1
    PostgreSQL            : 17.6.1.155
    estado                : ACTIVE_HEALTHY
    creado                : 2026-08-19

    current_database()    : postgres
    current_user          : postgres

**Es el único proyecto TMS de la organización.** Los otros ocho proyectos
(`comerza`, `eChange`, `eSupplier`, `EWM by EBIM`, `eExpense`, `GMAO`, `bbp-scribe`, `PCG-PRD`)
tienen refs distintos y **no se tocan**.

## Esquemas presentes

    auth, extensions, graphql, graphql_public, public, realtime, storage, tms, vault

De estos, **sólo `tms` pertenece a la aplicación**. El resto son de la plataforma Supabase y
quedan intactos.

## Estado Flyway antes del reset

    LATEST_MIGRATION   = V23
    APPLIED (con versión) = 23   (V1…V23, más la fila 0 de creación de esquema)
    FAILED             = 0
    última instalación = 2026-08-20 23:32:26

    V23 : version=23, checksum=196196275, installed_on=2026-08-20 23:32:26, success=true

Historial completo (`installed_rank | version | description | checksum | success`):

    0  | (null) | << Flyway Schema Creation >>              | (null)      | true
    1  | 1  | baseline schema extensions and helpers        | 501999165   | true
    2  | 2  | identity and tenancy                          | -1285245218 | true
    3  | 3  | iam reference data                            | 1330495845  | true
    4  | 4  | security grants and rls                       | -1423081066 | true
    5  | 5  | authorization catalogue completion            | -1247752360 | true
    6  | 6  | masterdata origins zones                      | 2019588220  | true
    7  | 7  | masterdata destinations frequencies           | -1993095227 | true
    8  | 8  | masterdata routes                             | 1105598691  | true
    9  | 9  | fleet masters                                 | 1946317140  | true
    10 | 10 | orders                                        | -2121633770 | true
    11 | 11 | planning manual                               | -1268241111 | true
    12 | 12 | performance indexes                           | -1337207178 | true
    13 | 13 | tenant rls runtime role and policies          | -524259899  | true
    14 | 14 | masterdata canonical location                 | 1434730108  | true
    15 | 15 | masterdata location frequency                 | 1876108307  | true
    16 | 16 | fleet external reference and double booking   | 661327672   | true
    17 | 17 | orders declared totals                        | 1665043557  | true
    18 | 18 | integration clients and inbox                 | -83805734   | true
    19 | 19 | planning shipment v2                          | -1454481527 | true
    20 | 20 | shipment outbox and outbound scope            | 592143850   | true
    21 | 21 | master data import batch                      | 1912631272  | true
    22 | 22 | audit event                                   | 979569066   | true
    23 | 23 | location canonical unification                | 196196275   | true   <-- el conflicto

**El motivo del reset**: ese `196196275` es el checksum de la V23 *antigua*. La V23 corregida en
`dev` calcula `-194785114`. Con `validate-on-migrate: true`, un despliegue sobre esta base abortaba
en `validate` antes de llegar a V24.

## Tablas del esquema `tms` — 36

    app_user, audit_event, carrier, company, destination, flyway_schema_history, frequency,
    frequency_exception, frequency_weekly_rule, import_batch, integration_client,
    integration_client_scope, integration_request, location, location_frequency, location_role,
    membership, membership_role, order_import_batch, organization, origin, permission,
    planning_run, role, role_permission, route, route_stop, shipment_outbox_event,
    transport_order, transport_order_line, trip, trip_order_assignment, trip_stop, vehicle,
    vehicle_type, zone

Son 36 y no las ~41 de V35 justamente porque la base se quedó en V23: faltan `driver`,
`trip_exception`, `order_delivery`, `tracking_position`, `rate_card`, `trip_cost`,
`trip_cost_component`, `trip_tender`, `notification`, `company_settings`, `delivery_evidence`,
`transport_event` y las cuatro de webhooks.

## Datos que se eliminan

| Tabla | Filas |
|---|---|
| organization | 1 |
| company | 2 |
| app_user | 3 |
| membership | 3 |
| role | 4 |
| permission | 36 |
| location | 1 |
| origin | 1 |
| carrier | 1 |
| destination, zone, route, vehicle, transport_order, trip, audit_event | 0 |

Es el seed de demostración de `supabase/seeds/local_dev_seed.sql`, no datos de negocio: cero
pedidos, cero viajes, cero eventos de auditoría. El catálogo de permisos está en 36 porque
corresponde a V23; el código actual declara 47.

## Cuentas de Auth — se conservan

`auth.users` **no se toca**: es un esquema de la plataforma, no de la aplicación, y sus tres
cuentas de demostración siguen siendo válidas. El reset afecta sólo al esquema `tms`, donde vive
`app_user.auth_user_id`, así que el seed posterior vuelve a enlazarlas por correo.

    841d51b5-dba7-4b96-853e-b14bb9719503  admin@demo.local          confirmada  2026-08-19
    00000000-0000-4000-a000-000000000002  planner.lima@demo.local   confirmada  2026-08-19
    00000000-0000-4000-a000-000000000003  viewer@demo.local         confirmada  2026-08-19

Conservarlas es lo que permite reconstruir el entorno **sin conocer ni fijar ninguna contraseña**.

## Verificación del objetivo destructivo

    1. Nombre del proyecto es TMS             tms-by-ebim                      OK
    2. Ref coincide con el del preflight      ocxmsluzegpkezkpcqjj             OK
    3. La base tiene V23 con checksum 196196275                                OK
    4. No es comerza / eChange / eSupplier / EWM  (refs distintos)             OK

    DESTRUCTIVE_TARGET_VERIFIED = YES
