# Location / Origin / Destination - estado real antes de la unificacion

Diagnostico de Fase 0. Escrito **antes** de tocar codigo, leyendo migraciones aplicadas,
entidades JPA, servicios, repositorios, controladores, pantallas React y tests.

Fecha: 2026-08-20. Rama: `dev`. Ultimo commit: `f0a2bdd`. Working tree limpio al empezar.

---

## Resumen ejecutivo

El modelo canonico **ya existe**: la migracion `V14__masterdata_canonical_location.sql`
introdujo `tms.location` + `tms.location_role` y volco `tms.origin` / `tms.destination` en el.
Lo que falta para cumplir la direccion funcional objetivo son tres cosas concretas:

1. **El vocabulario de roles mezcla clasificacion y uso operacional.** `location_role.role`
   admite `ORIGIN`, `SHIP_TO`, `STORE`, `DC`, `PLANT`, `HUB`, `OTHER`. Los cinco ultimos son
   *tipos*, no usos, y la UI los pinta como checkboxes junto a origen/destino. Es exactamente
   el defecto que el objetivo pide corregir.
2. **`SHIP_TO` deberia llamarse `DESTINATION`** en el contrato de dominio de este producto.
3. **Ningun consumidor apunta todavia a `tms.location`.** Las seis claves foraneas de
   `route`, `route_stop`, `transport_order`, `planning_run` y `trip_stop` siguen apuntando a
   `tms.origin` / `tms.destination`, que siguen siendo tablas fisicas con CRUD propio, pantalla
   propia, endpoints propios y un sincronizador bidireccional
   (`LocationCompatibilityProjector`). Son, hoy, una segunda fuente de verdad.

---

## CURRENT_SCHEMA

Esquema de aplicacion: `tms`. Owner de DDL: Flyway (ADR-002). PostGIS resuelto en tiempo de
migracion (puede vivir en `public` o en `extensions`).

    LATEST_MIGRATION = V22__audit_event.sql
    SIGUIENTE VERSION LIBRE = V23

Migraciones relevantes al dominio:

| Migracion | Aporte |
|---|---|
| `V6__masterdata_origins_zones.sql` | `tms.zone`, `tms.origin` |
| `V7__masterdata_destinations_frequencies.sql` | `tms.destination`, `tms.frequency`, `frequency_weekly_rule`, `frequency_exception` |
| `V8__masterdata_routes.sql` | `tms.route` (`origin_id`), `tms.route_stop` (`destination_id`), y los `UNIQUE (id, company_id)` que habilitan las FK compuestas de tenant |
| `V10__orders.sql` | `tms.transport_order` (`origin_id`, `destination_id`) |
| `V11__planning_manual.sql` | `tms.planning_run` (`origin_id`), `tms.trip`, `tms.trip_stop` (`destination_id`) |
| `V13__tenant_rls_runtime_role_and_policies.sql` | rol `tms_app`, `tms.current_company_id()`, politicas RLS por compania |
| **`V14__masterdata_canonical_location.sql`** | **`tms.location`, `tms.location_role`, backfill, `origin.location_id` / `destination.location_id`, RLS, permisos `masterdata.location:*`** |
| `V15__masterdata_location_frequency.sql` | `tms.location_frequency` - los calendarios de servicio ya cuelgan de `location`, no de `destination` |
| `V18` / `V20` | clientes de integracion, inbox, outbox de shipment |
| `V21__master_data_import_batch.sql` | Import Center (locations incluido) |
| `V22__audit_event.sql` | traza de auditoria de negocio |

---

## CURRENT_LOCATION_MODEL

**SI existe `tms.location`.** **SI existe la entidad Java `Location`**
(`masterdata/domain/Location.java`).

Columnas de `tms.location` (V14):

    id, company_id, code, name, location_type,
    address, address_reference, district, province, department, country,
    time_zone, latitude, longitude,
    geo_point geography(Point,4326) GENERATED ALWAYS ... STORED  (indice GiST)
    zone_id, service_time_minutes,
    external_system, external_reference,
    active,
    created_at, updated_at, created_by, updated_by

Restricciones destacadas:

- `uq_location_company_code (company_id, code)` - codigo unico por compania.
- `uq_location_id_company (id, company_id)` - blanco de las FK compuestas de tenant.
- `uq_location_external (company_id, external_system, external_reference) WHERE external_reference IS NOT NULL`
  - **la clave de idempotencia por tenant ya existe**.
- `ck_location_external_pair_complete` - referencia externa sin sistema es invalida.
- `ck_location_coordinates_pair` - latitud y longitud ambas o ninguna.
- `fk_location_zone_company (zone_id, company_id)` - zona de otra compania imposible.

Contra el contrato de la Fase 1 del objetivo, el unico campo de la lista que **no** existe es
`version` (bloqueo optimista). `tms.transport_order` si lo tiene; `tms.location` no. No es un
hueco de correctitud - las escrituras de maestro son de baja concurrencia y el servicio ya
resuelve la carrera de codigo duplicado por indice unico - pero queda anotado.

`LocationType` (Java y `ck_location_type`) es la **union** de los enums heredados:

    WAREHOUSE, DISTRIBUTION_CENTER, PLANT, HUB, OTHER,
    CUSTOMER, STORE, BRANCH, DELIVERY_POINT

Coincide con el vocabulario conceptual pedido en Fase 1. No hace falta inventar tipos.

---

## CURRENT_ORIGIN_MODEL

`tms.origin` **sigue siendo una tabla fisica** (V6), con:

- entidad `masterdata/domain/Origin.java`, enum `OriginType`
  (`WAREHOUSE, DISTRIBUTION_CENTER, PLANT, HUB, OTHER`);
- `OriginRepository`, `OriginSpecifications`;
- `OriginService`, `OriginRequest`, `OriginFilter`, `OriginView`;
- `OriginController` sobre `/masterdata/origins` (CRUD completo);
- `OriginLookupAdapter` implementando `OriginLookupPort` (el puerto que usan `orders`,
  `planning` y los imports para resolver un origen sin cruzar el limite de modulo);
- pantalla React `OriginsPage` + `OriginFormDrawer` + `originsApi.ts`, ruta
  `/masters/origins`;
- columna anadida en V14: `origin.location_id` (nullable, FK simple + FK compuesta de tenant,
  indice unico parcial `uq_origin_location`).

Forma mas pobre que la de destino: sin `district/province/department/country`, sin `zone_id`,
sin `service_time_minutes`, sin `address_reference`.

---

## CURRENT_DESTINATION_MODEL

`tms.destination` **sigue siendo una tabla fisica** (V7), con la misma pila completa:
`Destination`, `DestinationType` (`CUSTOMER, STORE, BRANCH, HUB, DISTRIBUTION_CENTER,
DELIVERY_POINT`), `DestinationRepository`, `DestinationSpecifications`, `DestinationService`,
`DestinationController` sobre `/masterdata/destinations`, `DestinationLookupAdapter`,
`DestinationsPage` + `DestinationFormDrawer` + `destinationsApi.ts`, ruta
`/masters/destinations`, y `destination.location_id` anadida en V14.

Forma mas rica en direccion y zona, pero **sin `time_zone`**.

---

## CURRENT_RELATIONSHIP

    tms.location  (canonica, V14)
        ^                    ^
        | location_id        | location_id
        |                    |
    tms.origin          tms.destination      <-- proyecciones de compatibilidad

`LocationCompatibilityProjector` mantiene la relacion **en las dos direcciones**, dentro de la
transaccion del llamante:

- **descendente** (canonica -> legacy): cada escritura de `/masterdata/locations` crea,
  actualiza o desactiva la fila `origin` y/o `destination` segun tenga los roles `ORIGIN` /
  `SHIP_TO`. Es lossy: pierde zona y tiempo de servicio en el origen, zona horaria en el
  destino, y estrecha `location_type` a cada enum heredado;
- **ascendente** (legacy -> canonica): cada escritura de `/masterdata/origins` o
  `/masterdata/destinations` actualiza tambien su location enlazada.

Es decir: **hoy hay dos caminos de escritura y tres tablas describiendo un lugar.**
El ADR lo reconoce explicitamente como deuda D-1 / D-2.

### SI existe backfill desde Origin/Destination

`V14` seccion 4: `FULL OUTER JOIN` de `origin` y `destination` por `(company_id, code)`.

- solo origen -> una location con rol `ORIGIN`;
- solo destino -> una location con rol `SHIP_TO`;
- **ambos -> UNA location con los dos roles** (el caso del CD duplicado).

Ademas garantiza, para toda fila pre-V14:

    location.id == origin.id           cuando el grupo tiene origen
    location.id == destination.id      en caso contrario

y deja `origin.location_id` / `destination.location_id` poblados. **Esto es lo que hace barato
el repunte de las FK: para los datos que ya existian, el `UPDATE` no cambia ningun valor.**

---

## LEGACY_DEPENDENCIES

Modulos que todavia dependen de los ids fisicos de `origin` / `destination`:

| Tabla | Columna | FK actual | Migracion | Constraint |
|---|---|---|---|---|
| `tms.route` | `origin_id` NOT NULL | `tms.origin` | V8 | `fk_route_origin`, `fk_route_origin_company` |
| `tms.route_stop` | `destination_id` NOT NULL | `tms.destination` | V8 | `fk_route_stop_destination`, `fk_route_stop_destination_company` |
| `tms.transport_order` | `origin_id` NOT NULL | `tms.origin` | V10 | `fk_transport_order_origin`, `fk_transport_order_origin_company` |
| `tms.transport_order` | `destination_id` NOT NULL | `tms.destination` | V10 | `fk_transport_order_destination`, `fk_transport_order_destination_company` |
| `tms.planning_run` | `origin_id` NOT NULL | `tms.origin` | V11 | `fk_planning_run_origin`, `fk_planning_run_origin_company` |
| `tms.trip_stop` | `destination_id` NOT NULL | `tms.destination` | V11 | `fk_trip_stop_destination`, `fk_trip_stop_destination_company` |

Indices y unicidades que dependen de esas columnas y que el repunte debe respetar:

- `uq_route_stop_route_destination (route_id, destination_id)`
- `uq_trip_stop_trip_destination (trip_id, destination_id)` DEFERRABLE
- `uq_planning_run_company_origin_date (company_id, origin_id, planning_date)` (parcial)
- `ix_transport_order_company_origin_service_date` (V12)

**El repunte es inyectivo**: `uq_origin_location` y `uq_destination_location` garantizan como
maximo una fila legacy por location, asi que ninguna de esas unicidades puede colisionar por
efecto del cambio de id.

En Java, ningun consumidor mapea `Origin`/`Destination` como asociacion JPA: `Route`,
`RouteStop`, `TransportOrder`, `PlanningRun` y `TripStop` guardan `UUID` planos. La resolucion
pasa por dos puertos en `shared/reference`:

    OriginLookupPort       <- OriginLookupAdapter       <- OriginRepository
    DestinationLookupPort  <- DestinationLookupAdapter  <- DestinationRepository

usados por `OrderService`, `OrderIntakeService`, `OrderImportService`, `PlanningRunService`,
`TripViewAssembler`. **Esos dos adaptadores son la costura**: reapuntarlos a
`LocationRepository` filtrando por rol migra ordenes, planning e imports de una sola vez.
`RouteService` es la excepcion: usa `OriginRepository`/`DestinationRepository` directamente por
estar en el mismo modulo.

---

## TENANCY_MODEL

Compania como ambito de tenant (ADR-003). `CompanyScope` resuelto en servidor; ningun servicio
acepta un `companyId` crudo del cliente. Cada finder lleva el predicado de compania **dentro de
la consulta**. En base de datos, cada referencia entre tablas de negocio lleva ademas una FK
**compuesta** `(referencia_id, company_id) -> destino (id, company_id)`, que es lo que hace
imposible una referencia cruzada entre companias. `tms.location` ya expone
`uq_location_id_company` para poder ser blanco de esas FK compuestas.

---

## RLS_MODEL

ADR-005. Rol de ejecucion no-owner `tms_app`; `tms.current_company_id()` lee el ajuste de la
transaccion. `tms.location` tiene politica `p_tenant_company_scope` `FOR ALL` con `USING` y
`WITH CHECK` sobre `company_id`. `tms.location_role` no tiene `company_id` propio: su politica
se resuelve **a traves del padre** (`EXISTS (SELECT 1 FROM tms.location ...)`), el mismo patron
de `frequency_weekly_rule`. `tms.origin` y `tms.destination` tienen sus propias politicas
desde V13.

Permisos de autorizacion: V14 sembro `masterdata.location:read` y `masterdata.location:manage`
y los concedio a `ORGANIZATION_ADMIN` / `COMPANY_ADMIN`, y solo `:read` a `PLANNER` / `VIEWER`.
Los permisos `masterdata.origin:*` y `masterdata.destination:*` de V3 siguen existiendo.

---

## Tests existentes

| Suite | Que cubre | Estado en esta maquina |
|---|---|---|
| `LocationModelTest` | roles como diff, activacion | Ejecuta (sin BD) |
| `LocationEligibilityEvaluatorTest` | elegibilidad por calendario | Ejecuta |
| `LocationCompatibilityProjectorTest` | proyeccion canonica <-> legacy | Ejecuta |
| `LocationImportValidatorTest`, `LocationImportTemplateTest` | import masivo | Ejecuta |
| `LocationApiIntegrationTest`, `LocationImportApiIntegrationTest`, `LocationFrequencyApiIntegrationTest` | API completa | **Skip** |
| `CanonicalLocationConstraintIntegrationTest`, `MasterDataConstraintIntegrationTest`, `MasterDataRouteConstraintIntegrationTest`, `OrderConstraintIntegrationTest`, `PlanningConstraintIntegrationTest` | constraints y RLS | **Skip** |
| `EndToEndSmokeIntegrationTest` | flujo completo | **Skip** |
| Frontend: `LocationsPage.test.tsx`, `LocationFormDrawer.test.tsx`, `OriginsPage.test.tsx`, `DestinationsPage.test.tsx`, `LocationPickerMap.test.tsx`, `navigation.test.tsx` | pantallas ES/EN | Ejecutan |

**Bloqueo de entorno E-1 (heredado):** el demonio Docker no esta disponible, el backend WSL no
tiene distribucion y el PostgreSQL nativo no tiene PostGIS. Toda prueba con Testcontainers
**se salta**, no falla. Cualquier afirmacion sobre SQL en este trabajo es revisable, no
probada en ejecucion.

---

## Google Maps / PostGIS / Integraciones / Import - estado

- **PostGIS**: `tms.location.geo_point` es `geography(Point,4326)` generada e indexada con
  GiST. `latitude`/`longitude` numericos siguen siendo el contrato de la API y de JPA.
- **Google Maps**: `shared/maps/LocationPickerMap.tsx` ya existe y esta integrado en el drawer
  de ubicacion (commit `79a8eb8`). Requiere validar clave por variable de entorno y fallback
  manual.
- **Integraciones**: `IntegrationLocationController` + `IntegrationLocationService` ya ofrecen
  upsert idempotente por `(external_system, external_reference)` contra `tms.location`.
- **Import Center**: `LocationImportService` / `LocationImportValidator` / `LocationImportTemplate`
  ya soportan plantilla, preview y aplicacion para ubicaciones. La columna `roles` acepta hoy
  los siete valores del enum heredado.

---

## MIGRATION_REQUIRED

    MIGRATION_REQUIRED = YES   -> V23

Alcance minimo necesario para cerrar el dominio:

1. Reducir `location_role.role` a **`ORIGIN`** y **`DESTINATION`**, renombrando `SHIP_TO` y
   eliminando los valores de clasificacion (que ya viven en `location_type`).
2. Reapuntar las seis claves foraneas a `tms.location`, conservando la FK compuesta de tenant.
3. Dejar `tms.origin` y `tms.destination` sin ningun lector ni escritor de aplicacion.

No se dropean las tablas heredadas en este paso: la regla del propio encargo es no eliminar
legacy hasta que la migracion de datos se pueda demostrar en verde, y en esta maquina no se
puede ejecutar. Se retiran los permisos de escritura del rol `tms_app` sobre ellas, que es la
forma verificable de decir "esto ya no es fuente de verdad" sin destruir el respaldo.
