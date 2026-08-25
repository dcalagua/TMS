# TMS by EBIM — Verificación final de runtime

Fecha de ejecución: 2026-08-25
Ejecutor: gate de verificación, sin cambios de código ni refactor.
Alcance: demostrar que repositorio y runtime quedaron consistentes.

**Resultado: `FINAL_STATUS=FAIL`.** La remediación no está cerrada. Hay un defecto que impide
que el backend arranque, y el último commit del repositorio eliminó la totalidad de las pruebas
de frontend y la suite e2e.

Ninguna prueba omitida se reporta como aprobada. Ningún número de este informe procede de un
informe anterior: cada uno es la salida del comando citado a su lado.

---

## 0. Resumen ejecutivo — los tres hallazgos que bloquean el gate

### H1 · El backend no arranca (P0)

`TripRepository.utilizationForRange` lleva una `@Query` nativa que contiene un apóstrofo dentro de
un comentario SQL `--`:

    -- ... keeps the projection's two getters the same type.
                            ^

El analizador de Spring Data no elimina los comentarios `--` antes de contar comillas, así que ese
apóstrofo abre una cadena que nunca se cierra:

    java.lang.IllegalArgumentException: The string <WITH scoped AS (...
    > starts a quoted range at 1496, but never ends it.

Consecuencia en cadena: no se crea `tripRepository` → ni `shipmentPublicationAdapter` →
ni `integrationShipmentService` → ni `integrationShipmentController` → **el contexto de Spring no
levanta**. Verificado en dos arranques independientes contra base limpia: el proceso muere y no
queda nada escuchando en 8080.

Fichero: `backend/tms-api/src/main/java/com/ebim/tms/planning/infrastructure/TripRepository.java`
(comentario dentro del bloque `@Query`, líneas ~344-346).

Esto explica los **249 errores** del suite: son todos los `@SpringBootTest`.

**Por qué no se detectó antes.** El informe `docs/hardening-v4/FINAL_REPORT.md` §13 declara
`1205 run, 0 failures, 0 errors, 343 skipped`, y explica que los 343 saltos son las suites
Testcontainers porque Docker no estaba disponible en aquel host. El defecto estaba ahí, pero
ninguna prueba que levantara el contexto llegó a ejecutarse. En esta sesión Docker sí está
disponible: los 343 saltos pasan a 0, se ejecutan de verdad, y 249 fallan.

### H2 · El último commit eliminó todas las pruebas de frontend (P0)

`00f9386 refactor(web): la interfaz pasa a MUI y adopta el diseño de la suite EWM`
(autor: ChristianEspi, 308 ficheros, +19.094 / −45.622).

    ficheros de test borrados        86
    tests de frontend supervivientes  0
    suite e2e                        borrada por completo (10 .spec.ts + 3 helpers)
    playwright.config.ts             borrado
    @playwright/test                 retirado de package.json
    script npm "e2e"                 no existe

El propio mensaje del commit lo declara como pendiente explícito, no como algo resuelto:
*"Se van 86 ficheros de test... también caen los de pantallas que siguen vivas (login, dashboard,
navegación) y toda la suite e2e junto con @playwright/test. No se pueden restaurar tal cual:
prueban la API anterior. Hay que reescribirlos."*

Comparación con lo que el informe anterior certificaba:

| Gate | hardening-v4 §13 | Ahora |
|---|---|---|
| Frontend unit | 674 passed (75 ficheros) | 0 — `No test files found` |
| E2E | 71 passed, 0 failed | no existe la suite |

### H3 · El entorno local de TMS no está levantado, y sus puertos los ocupa otro proyecto (P1)

`supabase/config.toml` declara `project_id = "tms-by-ebim"` con la API en 54321 y la base en 54322.
Ninguna de las dos pertenece hoy a TMS:

    54321  ->  supabase_kong_comerza
    54322  ->  supabase_db_comerza

Stacks Supabase en marcha: `comerza` y `echange-saas`. **`tms-by-ebim` no está arrancado.**

Esto significa que arrancar el backend con el perfil `local` por defecto apuntaría Flyway a la base
de **otro proyecto**. Es exactamente el accidente que `LocalProfileDatabaseGuard` existe para
impedir. Por eso toda la verificación de runtime de este informe se hizo contra un contenedor
PostGIS desechable propio en el puerto 55440, creado y destruido en esta sesión. No se tocó ninguna
base compartida ni remota.

---

## 1. Git

    branch      dev  (up to date con origin/dev)
    HEAD        00f93860b7467353be3f99b4dfcbd5d572aa865d
    HEAD msg    refactor(web): la interfaz pasa a MUI y adopta el diseño de la suite EWM
    git status  nothing to commit, working tree clean
    git diff --stat          (vacío)
    git diff --cached --stat (vacío)

No se hizo push. No se ejecutó ningún comando destructivo de Git.

El árbol está limpio, pero **limpio no es consistente**: el HEAD limpio es precisamente el commit
que introdujo H2.

### Divergencias declaradas respecto a CLAUDE.md, aún sin ADR

Las tres las reconoce el propio mensaje del commit como pendientes de decisión:

1. **MUI como librería principal.** CLAUDE.md dice *"Avoid MUI as the primary library"* y fija
   Bootstrap + SweetAlert2. El `package.json` actual trae `@mui/material` y `@mui/icons-material`,
   y ya no trae ni `bootstrap` ni `sweetalert2`. Requiere ADR propio.
2. **`render.yaml` roto.** La línea 61 declara `dockerfilePath: ./frontend/tms-web/Dockerfile`,
   y ese Dockerfile fue borrado por este mismo commit. El servicio `tms-web` de Render no puede
   construir. O se retira en favor de Amplify, o el fichero vuelve.
3. **i18n.** Se sustituyen i18next y 38 JSON por un diccionario en `lib/i18n.ts`, y con ellos
   desaparecen las 42 pruebas de paridad de idiomas que el informe anterior contabilizaba.

---

## 2. Backend

Comando: `./mvnw -B clean verify` (Maven Wrapper, Java 21.0.12.1 Temurin, Docker 29.7.2 activo).

    BUILD FAILURE

    Tests run: 1286   Failures: 3   Errors: 249   Skipped: 0
    Passed:    1034

**SKIPPED = 0.** Docker estuvo disponible durante toda la ejecución, así que ninguna suite
Testcontainers quedó sin ejecutar. Ninguna prueba omitida se cuenta como aprobada.

La compilación y el empaquetado sí funcionan: `./mvnw -B -DskipTests package` termina en 0 y
produce `target/tms-api-0.1.0-SNAPSHOT.jar` (89 MB). El defecto es de contexto en runtime, no de
compilación.

### Las 3 failures (aserciones desactualizadas tras V34/V35)

| Test | Detalle |
|---|---|
| `SchemaExposureIntegrationTest.rowLevelSecurityIsEnabledEverywhere:123` | Tablas inesperadas sin RLS: `company_settings`, `webhook_delivery`, `webhook_delivery_attempt`, `webhook_subscription`, `webhook_subscription_event` |
| `SchemaExposureIntegrationTest.businessTablesCarryTheTenantPolicy:157` | Las mismas cinco tablas carecen de política de tenant: *"a company-scoped table without a tenant policy is readable across tenants by the runtime role"* |
| `TenancyConstraintIntegrationTest.referenceDataIsPresent:203` | `expected: 33L but was: 47L` — el catálogo creció con V34/V35 y la aserción no se actualizó |

Las dos primeras no son sólo ruido de aserción: describen tablas de negocio multiempresa
(`company_settings` y las cuatro de webhooks, introducidas en V34/V35) que no llevan la política
de tenant que ADR-005 exige como defensa en profundidad.

### Los 249 errores

Todos comparten la única causa raíz H1. Clases afectadas (18):

    ApplicationDatabaseStartupIntegrationTest   LocationApiIntegrationTest
    CarrierImportApiIntegrationTest             LocationFrequencyApiIntegrationTest
    EndToEndSmokeIntegrationTest                LocationImportApiIntegrationTest
    FleetApiIntegrationTest                     OrderApiIntegrationTest
    FrequencyApiIntegrationTest                 OrderImportApiIntegrationTest
    IdentityResolutionIntegrationTest           PlanningApiIntegrationTest
    RouteApiIntegrationTest                     SchemaExposureIntegrationTest
    TenancyConstraintIntegrationTest            VehicleImportApiIntegrationTest
    VehicleTypeImportApiIntegrationTest         ZoneApiIntegrationTest

### Atención especial solicitada

| Suite | Estado |
|---|---|
| `FlywayMigrationIntegrationTest` | **PASSED** — 4 tests, 0 fallos |
| `LocationApiIntegrationTest` | **ERROR** — contexto no levanta (H1) |
| `PlanningApiIntegrationTest` | **ERROR** — contexto no levanta (H1) |
| RLS / tenancy | **PARCIAL** — `TenantRlsIsolationIntegrationTest`, `IntegrationTenancyIsolationIntegrationTest` y `ShipmentOutboxTenancyIsolationIntegrationTest` **PASSED**; `SchemaExposureIntegrationTest` **2 FAILED**; `TenancyConstraintIntegrationTest` **1 FAILED** |
| Constraints de base de datos | **PASSED** — `MasterDataConstraintIntegrationTest` (6), `OrderConstraintIntegrationTest` (23), `PlanningConstraintIntegrationTest` (29), `FleetConstraintIntegrationTest`, `CanonicalLocationConstraintIntegrationTest` y el resto de la familia, sin fallos |

Lo que sí queda demostrado: **el esquema es sólido**. Las suites que hablan directamente con
PostgreSQL por JDBC —migraciones y constraints— pasan enteras. Lo que no levanta es la capa de
aplicación.

---

## 3. Frontend

Los comandos se ejecutaron **después de `npm ci`**. Sin él, `npm run typecheck` fallaba con
`TS2688: Cannot find type definition file for 'google.maps'`: `node_modules` era del 19 de agosto
y el `package.json` del 25, sin `@mui` ni `@types/google.maps` instalados. El árbol de dependencias
instalado estaba obsoleto respecto al commit HEAD. `npm ci` respeta el lockfile y no modificó
ningún fuente.

| Comando | Resultado | Detalle |
|---|---|---|
| `npm run typecheck` | **PASS** (exit 0) | `tsc -b` sin diagnósticos |
| `npm run lint` | **PASS** (exit 0) | `oxlint`, 17 warnings, 0 errores |
| `npm test` | **FAIL** (exit 1) | `No test files found` — 0 ficheros, 0 tests |
| `npm run build` | **PASS** (exit 0) | `tsc -b && vite build`, 1,23 s |
| `npm run e2e` | **NO EXISTE** | el script no está en `package.json` |

Todos los errores quedan explicados:

- **typecheck**: el fallo inicial era del entorno (instalación obsoleta), no del código. Resuelto
  con `npm ci` y documentado arriba. No se tocó código.
- **lint**: 17 warnings, ningún error. Reparto: 12 `only-export-components`, 4
  `incompatible-library` y 1 `set-state-in-effect` (`AppLayout.tsx:73`). El informe
  anterior registraba 13 warnings; el aumento viene del refactor a MUI. `oxlint` termina en 0.
- **test**: no es un fallo de aserción sino la ausencia total de pruebas — H2. `vitest` sale con 1
  al no encontrar ficheros bajo `src/**/*.{test,spec}.{ts,tsx}`.
- **build**: correcto. Aviso no bloqueante de tamaño: `index-CsUpBfKf.js` 1.110 kB
  (338 kB gzip) y `ReportsPage-DAUm7HCn.js` 381 kB (110 kB gzip), por encima del umbral de 500 kB.
- **e2e**: no ejecutable. El script, la configuración y la dependencia fueron eliminados por H2.

---

## 4. Flyway

Verificado en runtime contra el contenedor desechable (PostGIS 17-3.5, puerto 55440), primero
migrando desde vacío y después reiniciando para forzar la validación de checksums.

    LATEST_SOURCE_MIGRATION = V35   (V35__integration_webhooks.sql)
    LATEST_DB_MIGRATION     = V35
    PENDING_MIGRATIONS      = 0
    FAILED_MIGRATIONS       = 0
    CHECKSUM DRIFT          = ninguno

Evidencia:

    Ficheros fuente en db/migration:  35   (V1 … V35, sin huecos)
    flyway_schema_history:            36 filas = 35 migraciones + 1 fila de creación de esquema
    filas con success = false:         0

Primer arranque:

    Successfully applied 35 migrations to schema "tms", now at version v35

Segundo arranque, contra la base ya migrada:

    Successfully validated 36 migrations (execution time 00:00.037s)
    Current version of schema "tms": 35
    Schema "tms" is up to date. No migration necessary.

**Esta sección coincide exactamente con lo pedido.** Es el único apartado del gate que pasa
íntegro, y confirma que V1-V35 se replican de cero sin intervención — la certificación que el
informe anterior había dejado como `BLOCKED_ENVIRONMENT` por falta de Docker.

### Defecto adicional detectado: `LocalProfileDatabaseGuard` (P1)

Con el perfil `local` y Flyway activado, el arranque se rechaza **siempre**, incluso apuntando a
`localhost`:

    Refusing to run Flyway against '' on the 'local' profile.

El guard obtiene la URL con `flyway.getConfiguration().getUrl()`
(`LocalProfileDatabaseGuard.java:86-89`). Cuando Spring Boot construye Flyway a partir del
`DataSource` de la aplicación —el camino normal, y el que usa `application-local.yml`, que no
define `spring.flyway.url`— ese `getUrl()` devuelve `null`, el guard lo normaliza a `""`, y `""`
no está en `LOCAL_HOSTS`. Falla cerrado: nunca reconoce un host local.

Su prueba `LocalProfileDatabaseGuardTest` pasa porque ejercita el análisis de host con URLs
literales, no el camino de integración en el que la URL llega vacía.

Efecto práctico: el perfil `local` no puede arrancar sin `TMS_ALLOW_REMOTE_DB=true`, que es
justamente la vía de escape pensada para lo contrario. Para esta verificación se usó ese override
de forma consciente y segura: la base era el contenedor desechable en `localhost:55440` creado en
esta sesión.

---

## 5. Runtime

**No verificable. El backend no arranca (H1).**

Sondas HTTP tras dos arranques completos contra base limpia y migrada:

    GET /api/v1/system/info    ->  000  (conexión rechazada)
    GET /v3/api-docs           ->  000  (conexión rechazada)
    GET /actuator/health       ->  000  (conexión rechazada)

`000` es fallo de conexión, no una respuesta HTTP: el proceso Java termina durante la
inicialización del contexto y no queda nada escuchando en 8080. Secuencia observada en el log:
Flyway migra las 35 versiones correctamente → falla `tripRepository` → `Error starting
ApplicationContext` → el proceso muere.

### Presencia de los endpoints exigidos

Como `/v3/api-docs` es inalcanzable, la presencia se comprobó **estáticamente** sobre los
controladores. Es una comprobación de existencia en el código, no de servicio en runtime, y así se
reporta. `${tms.api.base-path}` = `/api/v1` (`application.yml:95`).

| Endpoint | Declarado | Controlador |
|---|---|---|
| `GET /api/v1/masterdata/locations` | **SÍ** | `LocationController` |
| `GET /api/v1/monitoring/control-tower` | **SÍ** | `ControlTowerController` |
| `GET /api/v1/monitoring/control-tower/trips` | **SÍ** | `ControlTowerController` |
| `GET /api/v1/reporting/kpis` | **SÍ** | `ReportingController` |
| `GET /api/v1/reporting/kpis/export` | **SÍ** | `ReportingController` |
| `GET /api/v1/system/info` | **SÍ** | `SystemInfoController` |

**Los cinco endpoints existen.** Ninguno es un endpoint inexistente. Lo que falta es un servidor
capaz de servirlos.

### Paridad de rutas frontend ↔ backend (estática)

    Rutas declaradas en controladores:      175  (140 rutas únicas tras normalizar)
    Rutas invocadas desde el frontend:      127

Cruce completo: **ningún camino del frontend carece de ruta en el backend**. Los 8 aparentes
huecos del primer cruce se resolvieron uno a uno como artefactos de extracción, no como defectos:

- `/planning`, `/planning/{id}`, `/reporting`, `/integrations` — rutas de React Router, no llamadas API.
- `/planning/TripDriverDrawer` — ruta de `import`, no API.
- `/masterdata/origins` — aparece sólo dentro de comentarios que documentan que **no** existe
  (`originsApi.ts:7`: *"There is no `/masterdata/origins` endpoint anymore"*); un origen es una
  `tms.location`. Intencional y documentado.
- `/orders/import/preview` — existe: `OrderImportController` (`@RequestMapping(".../orders/import")`
  + `@PostMapping(path = "/preview")`).
- `/planning/trips/{id}/deliveries/{id}/evidence` — existe: `TripDeliveryController`
  (`@RequestMapping(".../planning/trips/{tripId}")` + `@PostMapping(path = "/deliveries/{deliveryId}/evidence")`).

Los dos últimos usan la forma `@PostMapping(path = "…")` que mi primera extracción no capturaba.

`OPENAPI_ROUTE_PARITY` se marca **FAIL** de todos modos: lo pedido era contrastar contra
`/v3/api-docs` servido, y eso no se pudo hacer. La paridad estática pasa; la de runtime queda sin
demostrar.

---

## 6. Smoke funcional del sidebar

**No ejecutable. FAIL por bloqueo, no por defecto de pantalla.**

Tres precondiciones independientes lo impiden, y las tres tendrían que resolverse antes de repetir
este apartado:

1. **Backend caído** (H1) — toda petición principal de todo módulo fallaría.
2. **Sin proveedor de autenticación** (H3) — `tms-by-ebim` no está levantado; sin JWKS no hay
   login, y todas las pantallas viven tras `ProtectedRoute` + `RequireCompany`.
3. **Sin driver de navegador** (H2) — Playwright fue eliminado, así que no hay forma instrumentada
   de capturar `console error` y `network error` por módulo, que es justamente lo que pide el gate.

Lo único que sí se pudo demostrar del frontend es que **el bundle construido se sirve**
(`npx vite preview --port 4173`):

    GET /                        200
    GET /assets/index-…js        200
    GET /control-tower           200   (fallback SPA correcto)

Eso prueba que el artefacto de build es servible y que el enrutado SPA responde. **No prueba
ninguna carga de datos**, que es el objeto real de este apartado.

### Inventario de módulos pendientes de smoke (21 entradas)

Extraído de `src/shared/ui/navConfig.tsx` y contrastado con la tabla de rutas de `src/App.tsx`.
Cada entrada del sidebar tiene ruta declarada; ninguna es un enlace muerto.

| # | Módulo | URL | Capability | Estado |
|---|---|---|---|---|
| 1 | Inicio | `/` | — | BLOQUEADO |
| 2 | **Torre de control** | `/control-tower` | `TRANSPORT_MONITOR_VIEW` | **BLOQUEADO** |
| 3 | **Reportes y KPIs** | `/reporting` | `TRANSPORT_MONITOR_VIEW` | **BLOQUEADO** |
| 4 | Pedidos | `/orders` | `ORDERS_VIEW` | BLOQUEADO |
| 5 | Planificación | `/planning` | `PLANNING_VIEW` | BLOQUEADO |
| 6 | Viajes | `/trips` | `TRIPS_VIEW` | BLOQUEADO |
| 7 | **Ubicaciones** | `/masters/locations` | `MASTER_DATA_VIEW` | **BLOQUEADO** |
| 8 | Orígenes | `/masters/origins` | `MASTER_DATA_VIEW` | BLOQUEADO |
| 9 | Destinos | `/masters/destinations` | `MASTER_DATA_VIEW` | BLOQUEADO |
| 10 | Zonas | `/masters/zones` | `MASTER_DATA_VIEW` | BLOQUEADO |
| 11 | Frecuencias | `/masters/frequencies` | `MASTER_DATA_VIEW` | BLOQUEADO |
| 12 | Rutas | `/masters/routes` | `MASTER_DATA_VIEW` | BLOQUEADO |
| 13 | Transportistas | `/fleet/carriers` | `FLEET_VIEW` | BLOQUEADO |
| 14 | Tipos de vehículo | `/fleet/vehicle-types` | `FLEET_VIEW` | BLOQUEADO |
| 15 | Vehículos | `/fleet/vehicles` | `FLEET_VIEW` | BLOQUEADO |
| 16 | Conductores | `/fleet/drivers` | `FLEET_VIEW` | BLOQUEADO |
| 17 | Tarifarios | `/rates/rate-cards` | `RATES_VIEW` | BLOQUEADO |
| 18 | Empresa | `/settings/company` | `IAM_VIEW` | BLOQUEADO |
| 19 | Usuarios y accesos | `/settings/users` | `IAM_VIEW` | BLOQUEADO |
| 20 | Integraciones | `/settings/integrations` | `INTEGRATION_VIEW` | BLOQUEADO |
| 21 | Auditoría | `/security/audit` | `AUDIT_VIEW` | BLOQUEADO |

Rutas adicionales con parámetro, fuera del sidebar: `/planning/:runId`, `/trips/:tripId`,
`/account`, `/login`, `*` → `NotFoundPage`.

Los tres módulos de control obligatorio —Torre de control, Reportes y KPIs, Ubicaciones— quedan
sin verificar. Sus endpoints existen (§5) y sus suites de API (`LocationApiIntegrationTest`,
`PlanningApiIntegrationTest`) están en ERROR por H1, no por un defecto propio.

---

## 7. Búsqueda de errores escondidos

Barrido sobre los registros de esta sesión: `mvn-verify.log` (85.853 líneas), `backend-run*.log`,
`fe-typecheck.log`, `fe-lint.log`, `fe-test.log`, `fe-build.log`, `fe-preview.log`.

| Patrón | Ocurrencias | Lectura |
|---|---|---|
| `404` | 5 | **Ninguna es HTTP.** 3 son timestamps (`…:31.404`), 1 un `Stopping service [Tomcat]`, y 1 un `Status 404: no matching manifest for linux/arm64/v8` de Docker al resolver imagen — transitorio: Testcontainers se recuperó y las suites de base de datos arrancaron y pasaron |
| `500` | 5 | **Ninguna es HTTP.** 3 timestamps (`…:34.500`), 1 tiempo transcurrido (`1.500 s`), y el aviso de build `Some chunks are larger than 500 kB` |
| `resource-not-found` | 0 | — |
| `internal-error` | 0 | — |
| `Failed to fetch` | 0 | No hubo cliente ejecutándose contra el API |
| `CORS` | 0 | No se alcanzó a negociar |
| `Unhandled` | 0 | — |
| `TypeError` | 0 | — |
| `console.error` | 0 | Sin driver de navegador, no hay consola que capturar |

**Ni los ceros ni los cincos acreditan limpieza.** No hubo sesión HTTP alguna: las 4 sondas
realizadas devolvieron `000` (conexión rechazada), que no es ni 404 ni 500 sino la constatación de
que no había servicio. Las 10 coincidencias de `404`/`500` se revisaron una a una y ninguna es un
código de respuesta HTTP; se detallan arriba.

Queda por tanto explícito: **no hay ningún 404 de endpoint inexistente**, porque los seis endpoints
auditados existen en el código (§5) y la paridad estática frontend↔backend no deja huecos. Lo que
no puede afirmarse es que no aparecerían errores en runtime — eso sólo lo dirá repetir §5 y §6 con
el backend arriba.

Errores reales encontrados en esta sesión, todos ya recogidos: H1 (contexto Spring), las 3 failures
de §2, H2 (0 tests de frontend), el defecto de `LocalProfileDatabaseGuard` (§4), `render.yaml`
apuntando a un Dockerfile borrado (§1), y `1 high severity vulnerability` reportada por `npm ci`.

---

## 8. Higiene del entorno de verificación

- Ninguna base compartida ni remota fue modificada. Toda la verificación de base de datos se hizo
  contra `tms-verify-db`, contenedor PostGIS 17-3.5 desechable en el puerto 55440, creado y
  eliminado en esta sesión.
- Las bases de `comerza` y `echange-saas` no fueron tocadas, pese a ocupar los puertos que el
  perfil `local` de TMS espera.
- No se ejecutó `git push` ni ningún comando destructivo de Git.
- No se modificó ningún fichero fuente. El único cambio en disco fuera de este informe es
  `frontend/tms-web/node_modules`, reconstruido con `npm ci` desde el lockfile ya versionado, y
  `backend/tms-api/target`, artefactos de build. Ninguno está versionado.
- No se leyeron ni se imprimieron valores de `.env`; sólo se listaron nombres de clave.

---

## 9. Qué haría falta para cerrar este gate

Por orden de dependencia:

1. **H1** — quitar el apóstrofo del comentario SQL en `TripRepository` (o eliminar el comentario
   `--` del interior de la `@Query`). Es de una línea, y desbloquea los 249 errores, §5 y §6.
2. **Las 3 failures de §2** — dar política de tenant a `company_settings` y a las cuatro tablas de
   webhooks conforme a ADR-005, y actualizar el recuento del catálogo a 47.
3. **H2** — reescribir las pruebas de frontend y la suite e2e contra la nueva API de componentes,
   y devolver el script `e2e` a `package.json`.
4. **`LocalProfileDatabaseGuard`** — leer la URL del `DataSource` cuando `getUrl()` sea nula, y
   cubrirlo con una prueba que ejercite ese camino.
5. **H3** — levantar `tms-by-ebim` en puertos que no colisionen con `comerza`.
6. **`render.yaml`** — retirar el servicio `tms-web` o restaurar su Dockerfile.
7. **ADR de MUI** — o revertir a Bootstrap, conforme a CLAUDE.md.

Hecho eso, §5 y §6 pueden ejecutarse por primera vez de verdad.

---

## Veredicto

```text
GIT_STATE_REVIEWED=PASS
BACKEND_BUILD=FAIL
BACKEND_TESTS=1286/1034/252/0
FRONTEND_TYPECHECK=PASS
FRONTEND_LINT=PASS
FRONTEND_TESTS=0/0/0
FRONTEND_BUILD=PASS
E2E=0/0/0
FLYWAY_SOURCE=V35
FLYWAY_DATABASE=V35
FLYWAY_PENDING=0
OPENAPI_ROUTE_PARITY=FAIL
CONTROL_TOWER=FAIL
REPORTING_KPIS=FAIL
LOCATIONS=FAIL
SIDEBAR_SMOKE=FAIL
UNEXPECTED_404=0
UNEXPECTED_500=0
FINAL_STATUS=FAIL
```

Notas sobre las cifras, para que ninguna se lea mejor de lo que es:

- `BACKEND_TESTS=1286/1034/252/0` — los 252 fallidos son 3 failures + 249 errors. **Skipped = 0**:
  Docker estuvo disponible y ninguna suite Testcontainers quedó sin ejecutar.
- `FRONTEND_TESTS=0/0/0` y `E2E=0/0/0` — cero ejecutados porque no existe ninguna prueba, no
  porque todas pasaran. `npm test` sale con 1.
- `UNEXPECTED_404=0` / `UNEXPECTED_500=0` — cero por ausencia de tráfico HTTP, no por corrección
  demostrada. Las 4 sondas devolvieron `000`, conexión rechazada.
- `FLYWAY_*` — único bloque que satisface literalmente lo pedido, verificado en runtime contra base
  limpia y revalidado contra base migrada.
- `GIT_STATE_REVIEWED=PASS` — el estado se revisó y se reporta por completo; el PASS es del acto de
  revisión, no un aval del contenido de HEAD.

`FINAL_STATUS=FAIL`, y no `PARTIAL`, porque los dos pilares del gate —que el runtime levante y que
exista una suite que lo respalde— están ambos caídos, no degradados.

---
---

# Remediación (2026-08-25)

Secciones 15-20, añadidas tras aplicar las correcciones. El detalle completo, con la evidencia de
cada paso, está en `TMS_REMEDIATION_REPORT.md`. Este bloque cierra el diagnóstico de arriba.

> Nota: el encargo de remediación citaba `TMS_RUNTIME_DIAGNOSIS.md`. Ese fichero no existe; el
> diagnóstico es **este**, y es el que recibe estas secciones.

## 15. Fix aplicado

**Causa raíz (H1) — el backend no arrancaba.** Un apóstrofo dentro de un comentario `--` de la
`@Query` nativa de `TripRepository.utilizationForRange` abría un literal que nada cerraba. Spring
Data no podía crear el bean, y con él caía el contexto entero. Se reescribió el comentario sin el
apóstrofo. **El SQL no se tocó.** Antes del cambio se escribió `NativeQueryQuotingTest`, que falló
señalando exactamente ese método y esa línea, y que ahora protege todas las `@Query` del
repositorio contra la misma clase de error sin depender de Docker.

**`LocalProfileDatabaseGuard` — estaba ciego.** Leía sólo `flyway.getConfiguration().getUrl()`,
que es `null` cuando Spring Boot construye Flyway desde el `DataSource` — el camino de todos los
perfiles de este repositorio. Refusaba cada arranque contra `''` sin poder nombrar el host. Ahora
resuelve la URL en tres pasos: `spring.flyway.url`, luego `HikariDataSource.getJdbcUrl()` (sin
abrir conexión: un guard no debe iniciar sesión en el host que va a rechazar), y sólo como último
recurso los metadatos de una conexión. Sigue siendo *fail-closed*. **No se debilitó: pasó a
funcionar**, y el override sigue existiendo y cubierto por test.

**Tres aserciones obsoletas.** Al leer el mensaje completo resultó que las 5 tablas de V34/V35
aparecían como *"unexpected"*: la base **sí** tenía RLS y política, y era la lista escrita a mano
del test la que estaba vieja. Se corrigieron las listas y los conteos del catálogo de autorización
(33→47 permisos, 95→132 grants), comprobados contra la base antes de escribirlos. **Por eso no se
escribió ninguna migración**: el esquema estaba correcto.

**Agotamiento de conexiones**, destapado al arrancar los contextos por primera vez: se acotó el
pool del perfil de test a 2 (`maximum-pool-size`), porque Spring cachea cada contexto durante toda
la ejecución y el total era contextos × 10 contra un `max_connections` de 100.

## 16. Archivos modificados

    M backend/tms-api/src/main/java/com/ebim/tms/planning/infrastructure/TripRepository.java   (1 línea)
    M backend/tms-api/src/main/java/com/ebim/tms/shared/config/LocalProfileDatabaseGuard.java
    M backend/tms-api/src/test/java/com/ebim/tms/database/SchemaExposureIntegrationTest.java
    M backend/tms-api/src/test/java/com/ebim/tms/database/TenancyConstraintIntegrationTest.java
    M backend/tms-api/src/test/java/com/ebim/tms/shared/config/LocalProfileDatabaseGuardTest.java
    A backend/tms-api/src/test/java/com/ebim/tms/architecture/NativeQueryQuotingTest.java
    M backend/tms-api/src/test/resources/application-test.yml

    6 modificados + 1 nuevo, 233 inserciones, 12 supresiones.
    Lógica de producción cambiada: 1 línea (un comentario). El resto es el guard, tests y config de test.

Sin cambios en frontend: su `VITE_API_BASE_URL` ya era correcto y la regla era no tocar lo que no
hace falta. Sin cambios en migraciones, seguridad, RLS ni CompanyScope.

## 17. Servicios reiniciados

    tms-verify-db   contenedor PostGIS 17-3.5 desechable, localhost:55440, creado para esto
    tms-api         recompilado desde el árbol actual (sha256:75e043f42317b420…), PID 86231
                    arranque: "Started TmsApiApplication" — sin TMS_ALLOW_REMOTE_DB
    tms-web         reconstruido (npm run build) y servido con vite preview en 4173
    emisor OIDC     JWKS local en 127.0.0.1:55450 para poder llamar la API con seguridad ACTIVA

No se detuvo ningún proceso ajeno. `supabase_db_comerza` y `supabase_db_echange-saas` quedaron
intactos.

## 18. Migraciones aplicadas

    Migraciones NUEVAS escritas:      0
    Migraciones MODIFICADAS:          0
    Aplicadas a base remota:          0   (sin autorización; no procede)

    LATEST_SOURCE_MIGRATION = V35     LATEST_DB_MIGRATION = V35
    PENDING = 0                       FAILED = 0            checksum drift = ninguno

Las V1-V35 se aplicaron desde cero al contenedor desechable como parte de la verificación, y se
revalidaron en un segundo arranque (`Successfully validated 36 migrations`). No había deriva que
remediar: `DATABASE_DRIFT` era `NO` ya en el diagnóstico.

## 19. Verificación posterior

    Backend arranca                    SÍ    (no arrancaba)
    GET /api/v1/system/info            200   (era 000, conexión rechazada)
    GET /v3/api-docs                   200   151 caminos
    Los 5 endpoints exigidos           PRESENT en el OpenAPI vivo
    Sidebar por API                    23/23 en 200
    UNEXPECTED_404                     0
    UNEXPECTED_500                     0
    POST /masterdata/locations         201, roles:["ORIGIN"]  (escritura, no sólo lectura)
    Preflight CORS desde :4173         200

    Backend tests   1286 run / 3F / 249E / 0 skipped   ->   1300 run / 16F / 2E / 0 skipped
                    pasados 1034                       ->   pasados 1282
                    "too many clients" 18 ocurrencias  ->   0

Verdes ya, y antes en rojo: `SchemaExposureIntegrationTest` 10/10,
`TenancyConstraintIntegrationTest` 10/10, `LocalProfileDatabaseGuardTest` 18/18,
`NativeQueryQuotingTest` 1/1. `FlywayMigrationIntegrationTest` sigue 4/4.

## 20. Problemas restantes

**18 tests de backend en rojo, ninguno defecto de producto.** Todos estaban antes dentro de los
249 errores de contexto, y antes de eso dentro de los 343 saltos por falta de Docker: es la
primera vez que se ejecutan. Se demostró, no se supuso:

- No los causa el tope de pool: `OrderImportApiIntegrationTest` con el pool de vuelta a 10 falla igual.
- Son aislamiento entre tests: `previewWritesNothing` y `oneBadRowRejectsTheWholeFile` **pasan en
  solitario** y fallan en orden de clase. La aserción culpable es un `count(*)` global, sin filtro
  de empresa ni línea base, a diferencia de la línea inmediatamente anterior, que sí los tiene.
- El producto responde bien donde el test dice que no: el `POST` que `EndToEndSmoke` ve como `null`
  devuelve `201` con `roles:["ORIGIN"]` contra el backend real. Ese test es ordenado, así que 6 de
  las 16 failures son una sola raíz en cascada.
- `CanonicalLocationConstraintIntegrationTest` lleva un UUID mal formado en su fixture
  (`...0000000000r1`; `r` no es hexadecimal).

Quedan sin corregir a propósito: el encargo pedía implementar sólo las causas CONFIRMADAS, y
ninguna de éstas lo estaba porque eran invisibles. Es una segunda pasada, sobre tests y no sobre
producto.

**0 tests de frontend y 0 e2e** (H2 del diagnóstico, sin cambios). `npm test` sale con 1 por
`No test files found`; `npm run e2e` no existe. Mientras siga así no hay forma instrumentada de
capturar errores de consola por pantalla, y el smoke de navegador no puede ejecutarse.

**`backend/tms-api/.env` apunta a una base Supabase remota** (`aws-0-us-east-1.pooler.supabase.com`)
con `TMS_FLYWAY_ENABLED=true` y perfil `local`. Es el accidente que el javadoc del guard dice haber
sufrido dos veces; está de vuelta por tercera. No se tocó, no se conectó a ese host y no se migró
nada allí. Con el guard corregido el arranque ahora se detiene nombrando el host. **Conviene ponerlo
en cuarentena.**

**Divergencias de arquitectura del diagnóstico, sin cambios**: MUI sin ADR, `render.yaml` apuntando
a un Dockerfile borrado, y `tms-by-ebim` sin levantar con sus puertos ocupados por `comerza`.
