# TMS by EBIM — Verificación final de runtime

Ejecutado: 2026-08-25, 02:03-02:07.
Alcance: demostrar que repositorio y runtime quedaron consistentes tras la remediación.
Sin cambios de código, sin refactor, sin push. Cada número procede del comando citado a su lado.

**Resultado: `FINAL_STATUS=PARTIAL`.**

El runtime está **verde y verificado de extremo a extremo**: el backend arranca, los 5 endpoints
exigidos existen en el OpenAPI vivo, los 24 módulos del sidebar responden 200 y no queda un solo
404 ni 500 inesperado. Lo que impide un PASS no es el runtime sino la suite: 17 tests de backend
en rojo —ninguno defecto de producto, todos demostrados como tales— y **cero** tests de frontend y
cero e2e, porque el commit `00f9386` los eliminó.

### Documentos relacionados

| Fichero | Contenido |
|---|---|
| `TMS_RUNTIME_DIAGNOSIS.md` | El diagnóstico previo a la remediación, con sus secciones 15-20. Es el fichero que antes llevaba este nombre; se renombró al que el encargo de remediación usaba para él, y no se perdió nada. |
| `TMS_REMEDIATION_REPORT.md` | Qué se corrigió y por qué. |
| **este** | El gate, re-medido desde cero sobre el estado actual. |

---

## 1. Git

    branch      dev   (up to date con origin/dev; 0 ahead / 0 behind)
    HEAD        00f93860b7467353be3f99b4dfcbd5d572aa865d
    HEAD msg    refactor(web): la interfaz pasa a MUI y adopta el diseño de la suite EWM

    git status  6 modificados, 3 sin seguimiento. Nada en el índice. Sin commits nuevos.

      M backend/tms-api/src/main/java/com/ebim/tms/planning/infrastructure/TripRepository.java
      M backend/tms-api/src/main/java/com/ebim/tms/shared/config/LocalProfileDatabaseGuard.java
      M backend/tms-api/src/test/java/com/ebim/tms/database/SchemaExposureIntegrationTest.java
      M backend/tms-api/src/test/java/com/ebim/tms/database/TenancyConstraintIntegrationTest.java
      M backend/tms-api/src/test/java/com/ebim/tms/shared/config/LocalProfileDatabaseGuardTest.java
      M backend/tms-api/src/test/resources/application-test.yml
      ? backend/tms-api/src/test/java/com/ebim/tms/architecture/NativeQueryQuotingTest.java
      ? TMS_REMEDIATION_REPORT.md
      ? TMS_RUNTIME_DIAGNOSIS.md

    git diff --stat
      TripRepository.java                    |   2 +-
      LocalProfileDatabaseGuard.java         |  55 ++++++-
      SchemaExposureIntegrationTest.java     |   9 +-
      TenancyConstraintIntegrationTest.java  |  24 ++--
      LocalProfileDatabaseGuardTest.java     | 139 +++++++++++++++++
      application-test.yml                   |  16 +++
      6 files changed, 233 insertions(+), 12 deletions(-)

No se hizo push. No se ejecutó ningún comando destructivo de Git.

De las 233 inserciones, **una sola línea es lógica de producción**: el comentario SQL de
`TripRepository`. El resto es el guard, tests y configuración de test.

`GIT_STATE_REVIEWED=PASS` — el estado se revisó y se reporta íntegro. El PASS es del acto de
revisión, no un aval del contenido del HEAD, que sigue arrastrando las divergencias de §9.

---

## 2. Backend

Comando: `./mvnw -B clean verify` — Maven Wrapper, Java 21.0.12.1 Temurin, Docker 29.7.2 activo
durante toda la ejecución.

    BUILD FAILURE

    Tests run: 1300   Failures: 16   Errors: 2   Skipped: 0
    Passed:    1282

**SKIPPED = 0.** Con Docker disponible, **ninguna suite Testcontainers quedó sin ejecutar**: cero
líneas con `Skipped: [1-9]` y cero activaciones de `DockerAvailability.DISABLED_REASON`. Ninguna
prueba omitida se reporta aquí como aprobada.

Compilación y empaquetado funcionan: `-DskipTests package` termina en 0. El BUILD FAILURE proviene
exclusivamente de `maven-surefire-plugin` por fallos de test, no de compilación.

### Contraste con XML de surefire

Los dos recuentos difieren y se dan ambos en lugar de elegir el más favorable:

| Fuente | Tests | Failures | Errors | Skipped | Passed |
|---|---|---|---|---|---|
| Resumen de consola de Maven | 1300 | 16 | 2 | 0 | 1282 |
| Agregado de los 117 `TEST-*.xml` | 1293 | 16 | 1 | 0 | 1276 |

La diferencia procede de la contabilidad de reintentos: hubo **1 rerun**
(`CanonicalLocationConstraintIntegrationTest`, `Run 1: PSQL ERROR` → `Run 2: PASS`), que la consola
suma como ejecución y error adicionales mientras el XML de esa clase queda en
`failures=0 errors=0`. Los 7 tests restantes de diferencia no se han podido reconciliar y se
declaran como tal en vez de repartirlos. La línea del veredicto usa el resumen de Maven por ser la
salida propia del comando.

**Clases en rojo: 8 de 117.**

| Clase | Tests | Failures | Errors |
|---|---|---|---|
| `EndToEndSmokeIntegrationTest` | 13 | 6 | 1 |
| `LocationImportApiIntegrationTest` | 10 | 3 | 0 |
| `VehicleImportApiIntegrationTest` | 12 | 2 | 0 |
| `CarrierImportApiIntegrationTest` | 8 | 1 | 0 |
| `LocationApiIntegrationTest` | 18 | 1 | 0 |
| `OrderImportApiIntegrationTest` | 17 | 1 | 0 |
| `PlanningApiIntegrationTest` | 56 | 1 | 0 |
| `VehicleTypeImportApiIntegrationTest` | 8 | 1 | 0 |

### Atención especial solicitada

| Suite | Tests | Estado |
|---|---|---|
| `FlywayMigrationIntegrationTest` | 4 | **PASSED** |
| `LocationApiIntegrationTest` | 18 | **FAILED** — 1 de 18 (`retiredRolesAreRejected`) |
| `PlanningApiIntegrationTest` | 56 | **FAILED** — 1 de 56 (`executionLifecycleRecordsActualTimes`) |
| **RLS / tenancy** | | |
| `TenantRlsIsolationIntegrationTest` | 5 | **PASSED** |
| `IntegrationTenancyIsolationIntegrationTest` | 16 | **PASSED** |
| `ShipmentOutboxTenancyIsolationIntegrationTest` | 6 | **PASSED** |
| `SchemaExposureIntegrationTest` | 10 | **PASSED** (antes 2 fallos) |
| `TenancyConstraintIntegrationTest` | 10 | **PASSED** (antes 1 fallo) |
| **Constraints de base de datos** | | |
| `OrderConstraintIntegrationTest` | 23 | **PASSED** |
| `PlanningConstraintIntegrationTest` | 29 | **PASSED** |
| `FleetConstraintIntegrationTest` | 18 | **PASSED** |
| `MasterDataRouteConstraintIntegrationTest` | 15 | **PASSED** |
| `MasterDataDestinationFrequencyConstraintIntegrationTest` | 11 | **PASSED** |
| `MasterDataLocationFrequencyConstraintIntegrationTest` | 9 | **PASSED** |
| `MasterDataConstraintIntegrationTest` | 6 | **PASSED** |
| `CanonicalLocationConstraintIntegrationTest` | 12 | **PASSED con 1 reintento** (inestable) |
| `NativeQueryQuotingTest` (nuevo) | 1 | **PASSED** |

**Toda la familia RLS/tenancy y toda la familia de constraints están en verde.** Es el resultado
que más importa: el esquema y su defensa en profundidad se sostienen.

### Por qué los 17 rojos no son defectos de producto

Se demostró, no se supuso. Las tres pruebas están en `TMS_REMEDIATION_REPORT.md` §7:

1. **No es el tope de pool.** `OrderImportApiIntegrationTest` con el pool devuelto a 10 falla igual.
2. **Es aislamiento entre tests.** `previewWritesNothing` y `oneBadRowRejectsTheWholeFile`
   **pasan ejecutados en solitario** y fallan en orden de clase. La aserción culpable es un
   `count(*)` global, sin filtro de empresa ni línea base, justo debajo de otra que sí los lleva.
3. **El producto responde bien donde el test dice que no.** El `POST` que
   `EndToEndSmokeIntegrationTest.createMasterdata` ve como `null` devuelve `201` con
   `roles:["ORIGIN"]` contra el backend real. Ese test es `@TestMethodOrder`, así que 6 de las 16
   failures son una sola raíz en cascada.

A ello se suma un UUID mal formado en la fixture de `CanonicalLocationConstraintIntegrationTest`
(`...0000000000r1`; `r` no es hexadecimal), causa del único reintento.

Ninguno se ha corregido en este gate: la instrucción era no tocar código.

---

## 3. Frontend

| Comando | Exit | Resultado | Detalle |
|---|---|---|---|
| `npm run typecheck` | 0 | **PASS** | `tsc -b`, sin diagnósticos |
| `npm run lint` | 0 | **PASS** | `oxlint`: 17 warnings, **0 errores** |
| `npm test` | 1 | **FAIL** | `No test files found` — 0 ficheros, 0 tests |
| `npm run build` | 0 | **PASS** | `tsc -b && vite build`, 951 ms |
| `npm run e2e` | 1 | **NO EXISTE** | `npm error Missing script: "e2e"` |

Todos los errores quedan explicados:

- **`npm test`** no falla por una aserción sino por la ausencia total de pruebas. El commit
  `00f9386` eliminó 86 ficheros de test; `vitest` sale con 1 al no encontrar nada bajo
  `src/**/*.{test,spec}.{ts,tsx}`. No es un fallo enmascarable: **0 ejecutados, 0 pasados**.
- **`npm run e2e`** no es ejecutable. El script, `playwright.config.ts` y `@playwright/test`
  fueron eliminados por el mismo commit. **0/0/0**, y no se cuenta como PASS.
- **lint**: 17 warnings, ninguno error. Reparto medido: 12 `only-export-components`,
  4 `incompatible-library`, 1 `set-state-in-effect` (`AppLayout.tsx:73`). `oxlint` termina en 0.
- **build**: correcto. Aviso no bloqueante de tamaño: `index-*.js` 1.110 kB (338 kB gzip) y
  `ReportsPage-*.js` 381 kB (110 kB gzip), por encima del umbral de 500 kB.

El bundle recién construido lleva embebido `http://localhost:8080/api/v1`, que es el backend
levantado para este gate. Verificado con `grep` sobre `dist/assets/*.js`.

---

## 4. Flyway

Verificado en runtime contra la base del backend levantado, y revalidado en el arranque de este
gate.

    LATEST_SOURCE_MIGRATION = V35     (V35__integration_webhooks.sql)
    LATEST_DB_MIGRATION     = V35
    PENDING_MIGRATIONS      = 0
    FAILED_MIGRATIONS       = 0
    CHECKSUM DRIFT          = ninguno

Evidencia:

    ficheros fuente en db/migration    35     (V1 … V35, sin huecos)
    filas en flyway_schema_history     36     = 35 migraciones + 1 fila de creación de esquema
    filas con success = false           0
    source 35  vs  applied 35  ->  pending 0

Del arranque de este gate:

    Successfully validated 36 migrations (execution time 00:00.037s)
    Current version of schema "tms": 35
    Schema "tms" is up to date. No migration necessary.

**Coincide exactamente con lo exigido.** No se escribió ni se modificó ninguna migración durante la
remediación ni durante este gate: el número de migraciones añadidas es 0.

---

## 5. Runtime

Backend levantado desde el árbol de trabajo actual (`sha256:75e043f42317b420b3502e09…`, PID 86873),
sobre PostGIS 17-3.5 desechable en `localhost:55440`. Frontend reconstruido y servido en 4173.

    GET /api/v1/system/info    ->  200
      {"application":"TMS by EBIM","version":"0.1.0-SNAPSHOT","status":"UP","profiles":["local"]}

    GET /v3/api-docs           ->  200
      OpenAPI 3.1.0 · "TMS by EBIM API" · 151 caminos

### Presencia real de los cinco endpoints, en el documento vivo

| Endpoint | Métodos declarados |
|---|---|
| `/api/v1/masterdata/locations` | `get`, `post` |
| `/api/v1/monitoring/control-tower` | `get` |
| `/api/v1/monitoring/control-tower/trips` | `get` |
| `/api/v1/reporting/kpis` | `get` |
| `/api/v1/reporting/kpis/export` | `get` |

**5 de 5 presentes.** No es una comprobación estática sobre el código como en el diagnóstico
anterior: es el OpenAPI que sirve el proceso en marcha. `OPENAPI_ROUTE_PARITY=PASS`.

### El contraste que da valor a los 200

Un endpoint que de verdad no existe devuelve 404, con la petición **autenticada**:

    GET /api/v1/no-such-endpoint  ->  404
    {"type":"urn:tms:problem:resource-not-found","code":"resource-not-found",
     "title":"Resource not found","status":404, ...}

Es decir: la API **sí** sabe devolver 404. Por eso los 24 × 200 de §6 significan que esos
endpoints existen y funcionan, y no que algo esté tragándose los errores. Esta sonda fue
deliberada y es el único 404 provocado en toda la sesión.

---

## 6. Smoke funcional del sidebar

Barrido de los **24 destinos** alcanzables desde el menú, cada uno con su ruta de SPA y su petición
principal. Para llamar a la API con seguridad **activa** se usa un emisor OIDC local (par RSA,
JWKS publicado, token RS256 firmado) y la identidad `admin@demo.local` (ORGANIZATION_ADMIN) del
seed `local_dev_seed.sql`. **Nada se desactivó**: siguen validándose firma, emisor, audiencia,
expiración, resolución de principal, `X-Company-Id`, capabilities y RLS.

| Módulo | URL | Carga inicial | Request principal | HTTP | Consola | Red | V |
|---|---|---|---|---|---|---|---|
| Inicio | `/` | 200 | `/api/v1/system/info` | 200 | n/d | sin error | **PASS** |
| **Torre de control** | `/control-tower` | 200 | `/api/v1/monitoring/control-tower?date=…` | 200 | n/d | sin error | **PASS** |
| **Torre de control** | `/control-tower` | 200 | `/api/v1/monitoring/control-tower/trips?date=…` | 200 | n/d | sin error | **PASS** |
| **Reportes y KPIs** | `/reporting` | 200 | `/api/v1/reporting/kpis?from=…&to=…` | 200 | n/d | sin error | **PASS** |
| **Reportes y KPIs** | `/reporting` | 200 | `/api/v1/reporting/kpis/export?from=…&to=…` | 200 | n/d | sin error | **PASS** |
| Pedidos | `/orders` | 200 | `/api/v1/orders` | 200 | n/d | sin error | **PASS** |
| Planificación | `/planning` | 200 | `/api/v1/planning/runs` | 200 | n/d | sin error | **PASS** |
| Viajes | `/trips` | 200 | `/api/v1/planning/trips` | 200 | n/d | sin error | **PASS** |
| **Ubicaciones** | `/masters/locations` | 200 | `/api/v1/masterdata/locations` | 200 | n/d | sin error | **PASS** |
| Orígenes | `/masters/origins` | 200 | `/api/v1/masterdata/locations?role=ORIGIN` | 200 | n/d | sin error | **PASS** |
| Destinos | `/masters/destinations` | 200 | `/api/v1/masterdata/locations?role=DESTINATION` | 200 | n/d | sin error | **PASS** |
| Zonas | `/masters/zones` | 200 | `/api/v1/masterdata/zones` | 200 | n/d | sin error | **PASS** |
| Frecuencias | `/masters/frequencies` | 200 | `/api/v1/masterdata/frequencies` | 200 | n/d | sin error | **PASS** |
| Rutas | `/masters/routes` | 200 | `/api/v1/masterdata/routes` | 200 | n/d | sin error | **PASS** |
| Transportistas | `/fleet/carriers` | 200 | `/api/v1/fleet/carriers` | 200 | n/d | sin error | **PASS** |
| Tipos de vehículo | `/fleet/vehicle-types` | 200 | `/api/v1/fleet/vehicle-types` | 200 | n/d | sin error | **PASS** |
| Vehículos | `/fleet/vehicles` | 200 | `/api/v1/fleet/vehicles` | 200 | n/d | sin error | **PASS** |
| Conductores | `/fleet/drivers` | 200 | `/api/v1/fleet/drivers` | 200 | n/d | sin error | **PASS** |
| Tarifarios | `/rates/rate-cards` | 200 | `/api/v1/rates/rate-cards` | 200 | n/d | sin error | **PASS** |
| Empresa | `/settings/company` | 200 | `/api/v1/admin/companies/current` | 200 | n/d | sin error | **PASS** |
| Usuarios y accesos | `/settings/users` | 200 | `/api/v1/admin/users` | 200 | n/d | sin error | **PASS** |
| Integraciones | `/settings/integrations` | 200 | `/api/v1/integration-clients` | 200 | n/d | sin error | **PASS** |
| Auditoría | `/security/audit` | 200 | `/api/v1/audit-events` | 200 | n/d | sin error | **PASS** |
| Cuenta | `/account` | 200 | `/api/v1/companies/current` | 200 | n/d | sin error | **PASS** |

    PASS 24 / 24     FAIL 0     API 404: 0     API 5xx: 0

Los tres módulos de control obligatorio —**Torre de control, Reportes y KPIs, Ubicaciones**— pasan,
y no se limitó el barrido a ellos: están los 21 destinos del menú más `/account` y las dos segundas
llamadas de Torre y Reportes.

Las listas vacías se cuentan como PASS, según lo acordado. Torre de control devuelve además un
`summary` poblado y Reportes un agregado con `days: 25`; el export entrega CSV con cabecera.

### Sobre la columna "consola": `n/d`, no PASS

El barrido es por API con `curl` y por HTTP contra el servidor de preview. **No hay navegador.**
Playwright y la suite e2e fueron eliminados por `00f9386`, así que en este repositorio no existe
hoy forma instrumentada de capturar `console error` por pantalla. Marcarlas como PASS sería
inventarlo; se marcan `n/d` y esto es lo que sí se verificó del lado del navegador:

    GET /  /control-tower  /reporting  /masters/locations  /ruta-que-no-existe   ->  200
      (fallback SPA correcto: el 404 de negocio lo pinta NotFoundPage, no el servidor)

    Preflight CORS OPTIONS desde http://localhost:4173                            ->  200
      Access-Control-Allow-Origin:  http://localhost:4173
      Access-Control-Allow-Methods: GET,POST,PUT,PATCH,DELETE,OPTIONS
      Access-Control-Allow-Headers: authorization, x-company-id

`SIDEBAR_SMOKE=PASS` cubre ruta, petición principal, status y errores de red. La captura de consola
queda pendiente de que exista un driver de navegador, y así consta en §9.

---

## 7. Búsqueda de errores escondidos

Barrido sobre los 7 logs de este gate: `gate-mvn.log` (2.960 líneas), `gate-backend.log`,
`g-typecheck.log`, `g-lint.log`, `g-test.log`, `g-build.log`, `g-e2e.log` — **457.007 bytes**.

| Patrón | Coincidencias | Todas explicadas |
|---|---|---|
| `404` | 7 | 5 son correlation-ids que contienen "404" (`766f4404-…`, `628f6137-0162-4404-…`); 2 son la misma aserción `EndToEndSmokeIntegrationTest.anotherCompanyCannotRead: Status expected:<404> but was:<400>` — un test que **espera** un 404 y recibe 400 |
| `500` | 11 | 8 correlation-ids/timestamps (`…-4500-…`, `…:12.500`); 1 dato de fixture (`"unitWeightKg":500`); 1 aviso de build `Some chunks are larger than 500 kB`; 1 timestamp de log |
| `resource-not-found` | 0 | — |
| `internal-error` | 0 | — |
| `Failed to fetch` | 0 | — |
| `CORS` | 0 | — |
| `Unhandled` | 0 | — |
| `TypeError` | 0 | — |
| `console.error` | 0 | — |

**Ninguna de las 18 coincidencias de `404`/`500` es un código de respuesta HTTP.** Se revisaron una
a una, no por conteo agregado.

> Nota de método: el primer barrido devolvió 0 en todos los patrones. Era falso — zsh no divide en
> palabras una variable sin comillas, así que `cat $LOGS` recibía un único nombre inexistente. Se
> repitió con array y los conteos reales son los de arriba. Queda anotado porque un "limpio" por
> comando mal escrito es exactamente el error que esta sección existe para impedir.

    UNEXPECTED_404 = 0        (0 en runtime; el único 404 provocado fue la sonda deliberada de §5)
    UNEXPECTED_500 = 0        (0 en runtime y 0 en toda la sesión)

No queda ningún 404 de endpoint inexistente. Los 24 destinos del sidebar resuelven contra rutas
declaradas en el OpenAPI vivo, y la sonda de contraste demuestra que un camino ausente sí produce
`resource-not-found`.

---

## 8. Higiene del entorno

- Ninguna base compartida ni remota fue modificada. Todo contra `tms-verify-db`, contenedor
  PostGIS 17-3.5 desechable creado para esta verificación.
- `supabase_db_comerza` y `supabase_db_echange-saas` intactos, pese a ocupar los puertos 54321/54322
  que el perfil `local` de TMS espera.
- `TMS_ALLOW_REMOTE_DB` **no está fijado en ningún fichero** y no se usó en este gate: el arranque
  se hizo sin él, que es la prueba de que el guard reconoce `localhost` por sí solo.
- Sin `push`, sin comandos destructivos de Git, sin tocar código.
- Sin secretos leídos ni impresos.

Servicios en marcha al cierre, por si se quieren inspeccionar:

    backend        java -jar …/tms-api-running.jar     PID 86873   :8080
    frontend       vite preview                                     :4173
    base           docker tms-verify-db                             :55440
    emisor OIDC    python http.server (JWKS)                        :55450

---

## 9. Lo que impide un PASS

Tres bloques, ninguno de runtime, todos ya caracterizados:

1. **17 tests de backend en rojo** (§2), en 8 clases de 117. Demostrado que son defectos de
   aislamiento entre tests y no de producto. Necesitan su propia pasada, sobre tests.
2. **0 tests de frontend y 0 e2e** (§3). `npm test` sale con 1 por `No test files found`;
   `npm run e2e` no existe. Mientras siga así, la captura de errores de consola de §6 no es posible.
3. **Divergencias de arquitectura sin resolver**, heredadas del commit `00f9386`: MUI como
   librería principal sin ADR pese a que CLAUDE.md lo desaconseja; `render.yaml:61` apuntando a un
   `Dockerfile` que ese commit borró; y `tms-by-ebim` sin levantar, con sus puertos ocupados por
   otro proyecto.

Y una advertencia que sigue vigente del diagnóstico: **`backend/tms-api/.env` apunta
`TMS_DB_URL` a `aws-0-us-east-1.pooler.supabase.com`** con `TMS_FLYWAY_ENABLED=true` y perfil
`local`. No se tocó, no se conectó a ese host y no se migró nada allí. Con el guard ya corregido,
un arranque distraído se detiene nombrando el host en vez de crear el esquema en una base ajena.
Conviene ponerlo en cuarentena.

---

## Veredicto

```text
GIT_STATE_REVIEWED=PASS
BACKEND_BUILD=FAIL
BACKEND_TESTS=1300/1282/18/0
FRONTEND_TYPECHECK=PASS
FRONTEND_LINT=PASS
FRONTEND_TESTS=0/0/0
FRONTEND_BUILD=PASS
E2E=0/0/0
FLYWAY_SOURCE=V35
FLYWAY_DATABASE=V35
FLYWAY_PENDING=0
OPENAPI_ROUTE_PARITY=PASS
CONTROL_TOWER=PASS
REPORTING_KPIS=PASS
LOCATIONS=PASS
SIDEBAR_SMOKE=PASS
UNEXPECTED_404=0
UNEXPECTED_500=0
FINAL_STATUS=PARTIAL
```

Notas, para que ninguna cifra se lea mejor de lo que es:

- `BACKEND_TESTS=1300/1282/18/0` es el resumen de consola de Maven. **Skipped = 0** de verdad:
  Docker estuvo disponible y ninguna suite Testcontainers quedó sin ejecutar. El agregado de los
  XML da `1293/1276/17/0`; ambos constan en §2 y la diferencia se explica allí, incluido el tramo
  que no se ha podido reconciliar.
- `BACKEND_BUILD=FAIL` es por fallos de test, no de compilación: el empaquetado termina en 0 y el
  jar resultante es el que sirve todo el runtime verificado en §5 y §6.
- `FRONTEND_TESTS=0/0/0` y `E2E=0/0/0` son cero por **inexistencia**, no por éxito. Ambos comandos
  salen con 1.
- `UNEXPECTED_404=0` y `UNEXPECTED_500=0` sí son ceros con tráfico detrás, a diferencia del gate
  anterior: hubo 24 peticiones autenticadas con respuesta, más una sonda de contraste que devuelve
  404 correctamente.
- `SIDEBAR_SMOKE=PASS` cubre ruta, petición principal, status y errores de red de los 24 destinos.
  La columna de consola queda `n/d` por falta de driver de navegador y no se contabiliza como PASS.

`FINAL_STATUS=PARTIAL`, no `PASS`, porque la suite del repositorio no está verde y el frontend no
tiene ninguna prueba. Y no `FAIL`, porque lo que el gate anterior no pudo ni ejecutar hoy está
medido y en verde: el backend arranca, Flyway cuadra V35 contra V35, los cinco endpoints existen
en el OpenAPI vivo, los 24 módulos responden 200 y no queda un solo 404 o 500 sin explicar.

---
---

# Actualización tras la limpieza final (2026-08-25, 03:35)

Las secciones anteriores son el gate tal y como se midió a las 02:07, con
`FINAL_STATUS=PARTIAL`. Las tres razones que impedían el PASS quedaron cerradas después. El
detalle completo está en `TMS_FINAL_CLEANUP_AND_PUSH.md`; aquí van sólo las cifras nuevas.

| Gate | Antes (02:07) | Ahora (03:35) |
|---|---|---|
| Backend build | BUILD FAILURE | **BUILD SUCCESS** |
| Backend tests | 1300 run / 16 F / 2 E / 0 skip | **1312 run / 0 F / 0 E / 0 skip** |
| Reruns de surefire | 1 (tapaba un `@BeforeAll` roto) | **0** |
| Frontend tests | 0 (`No test files found`) | **37 / 37** |
| E2E | script inexistente | **33 / 33** |
| Typecheck · Lint · Build | PASS · PASS · PASS | PASS · PASS · PASS |
| Flyway | V35 = V35, 0 pendientes | V35 = V35, 0 pendientes |
| OpenAPI parity | PASS (151 caminos) | PASS (151 caminos) |
| Sidebar smoke | 24/24 | **24/24** |
| 404 / 500 inesperados | 0 / 0 | **0 / 0** |

Los 12 tests de más no son nuevos: son los de la clase anidada `Backfill` que un defecto real de
la migración V23 impedía arrancar, y que ahora se ejecutan por primera vez. La diferencia de
conteo que esta versión del informe dejó sin reconciliar también quedó resuelta: son 7 elementos
`<testcase>` que el atributo `tests` de un único XML (`FleetApiIntegrationTest`, con cuatro
clases `@Nested`) no cuenta. El conteo canónico es **1312**.

    FINAL_STATUS: PARTIAL  ->  PASS
