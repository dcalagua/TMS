# TMS by EBIM — Limpieza final, puerta de calidad, commit y push

Ejecutado: 2026-08-25, 02:03–03:35.
Rama `dev`. Cuatro commits nuevos. Sin `reset`, sin `clean`, sin `force`, sin ficheros
descartados. Toda base de datos usada fue desechable y local.

**Resultado: todas las puertas en verde.** El backend construye con 1312 pruebas y ni un fallo,
el frontend pasa de cero pruebas a 37 unitarias y 33 e2e, Flyway cuadra V35 contra V35, y los 24
destinos del menú responden 200 sin un solo 404 o 500 inesperado.

En el camino aparecieron **tres defectos reales de producto o de esquema** que llevaban meses
invisibles porque las pruebas que debían encontrarlos nunca llegaron a ejecutarse.

---

## 1. Branch inicial

    dev   (tracking origin/dev, 0 ahead / 0 behind tras `git fetch origin`)

## 2. HEAD inicial

    00f93860b7467353be3f99b4dfcbd5d572aa865d
    refactor(web): la interfaz pasa a MUI y adopta el diseño de la suite EWM

`origin/dev` apuntaba al mismo commit, así que no había conflicto de integración y se continuó.

## 3. Cambios pendientes encontrados

Seis modificados y cuatro sin seguimiento, todos de la remediación previa:

    M  TripRepository.java                      M  SchemaExposureIntegrationTest.java
    M  LocalProfileDatabaseGuard.java           M  TenancyConstraintIntegrationTest.java
    M  LocalProfileDatabaseGuardTest.java       M  application-test.yml
    ?  NativeQueryQuotingTest.java              ?  TMS_FINAL_RUNTIME_VERIFICATION.md
    ?  TMS_REMEDIATION_REPORT.md                ?  TMS_RUNTIME_DIAGNOSIS.md

Nada en el índice, ningún artefacto, ningún fichero temporal, ningún log.

## 4. Clasificación de cambios

| Archivo | Clasificación | Acción | Motivo |
|---|---|---|---|
| `TripRepository.java` | A · remediación válida | commit `3467c64` | Un apóstrofo en un comentario SQL impedía crear el bean y arrancar la API |
| `LocalProfileDatabaseGuard.java` | A · remediación válida | commit `3467c64` | El guard no sabía leer la URL del DataSource y rechazaba todo arranque |
| `LocalProfileDatabaseGuardTest.java` | B · fix de test | commit `3467c64` | Seis casos para el camino real de arranque que los catorce previos no tocaban |
| `NativeQueryQuotingTest.java` | C · infraestructura de testing | commit `3467c64` | Convención que impide que vuelva la comilla sin cerrar, sin depender de Docker |
| `SchemaExposureIntegrationTest.java` | B · fix de test | commit `1f58894` | Listas escritas a mano sin las cinco tablas de V34/V35 |
| `TenancyConstraintIntegrationTest.java` | B · fix de test | commit `1f58894` | Conteos del catálogo obsoletos (33→47, 95→132) |
| `application-test.yml` | C · infraestructura de testing | commit `1f58894` | Tope de pool: contextos cacheados × 10 conexiones agotaban el servidor |
| `V23__location_canonical_unification.sql` | A · corrección de esquema | commit `43b52d1` | Repuntaba filas con las FKs viejas aún activas (§6) |
| `CanonicalLocationConstraintIntegrationTest.java` | B · fix de test | commit `43b52d1` | UUID no hexadecimal y booleano comparado como texto |
| Cinco `*ImportApiIntegrationTest.java` | B · fix de test | commit `1f58894` | Conteos de por vida donde correspondía un delta |
| `LocationApiIntegrationTest.java` | B · fix de test | commit `1f58894` | Exigía rechazar `DESTINATION`, que es un rol válido |
| `PlanningApiIntegrationTest.java` | B · fix de test | commit `1f58894` | No resolvía los stops que V27 exige antes de completar |
| `EndToEndSmokeIntegrationTest.java` | B · fix de test | commit `1f58894` | `value(List)` donde correspondía un `Matchers` |
| `frontend/.../src/test/setup.ts` | C · infraestructura de testing | commit `d6c77e5` | Era un `export {}` de relleno; ahora es el setup que ya declaraba `vite.config.ts` |
| `frontend/.../package.json`, `package-lock.json` | C · infraestructura de testing | commit `d6c77e5` | Testing Library y Playwright, más el script `e2e` que no existía |
| `frontend/.../.gitignore` | C · infraestructura de testing | commit `d6c77e5` | Cubrir `playwright-report/`, `test-results/`, `blob-report/` |
| 4 ficheros `*.test.ts(x)` + fixtures + `e2e/` + `playwright.config.ts` | C · infraestructura de testing | commit `d6c77e5` | La base de regresión que no existía |
| `TMS_RUNTIME_DIAGNOSIS.md`, `TMS_REMEDIATION_REPORT.md`, `TMS_FINAL_RUNTIME_VERIFICATION.md` | D · documentación válida | commit de docs | Registro de las tres fases anteriores |

**Ningún archivo cayó en las categorías E (temporal), F (artefacto), G (log), H (secreto) ni
I (sospechoso).** No hubo nada que borrar y nada que dejar en cuarentena.

## 5. Secretos y configuración local descartados

Ninguno entró al commit. Verificado antes de tocar el índice:

    git ls-files | grep -iE '\.env|\.pem$|\.key$|credential|token'
      -> sólo .env.example (marcadores) y ficheros fuente cuyo nombre contiene "secret"
         (WebhookSecretCipher, IntegrationSecrets, SecretRevealDrawer...). Ningún secreto.

    backend/tms-api/.env                      IGNORED
    backend/tms-api/.env.test-user            IGNORED
    backend/tms-api/.env.bak.20260819105358   IGNORED
    frontend/tms-web/.env                     IGNORED
    frontend/tms-web/.env.local               IGNORED

No se leyó ni se imprimió el valor de ninguna variable: sólo nombres de clave y, en un caso, el
host de una URL. Nada trackeado tuvo que reescribirse.

**Advertencia que sigue vigente** (§21): `backend/tms-api/.env` apunta `TMS_DB_URL` a
`aws-0-us-east-1.pooler.supabase.com` con `TMS_FLYWAY_ENABLED=true` y perfil `local`. No se
tocó, no se conectó a ese host y no se migró nada allí.

## 6. Root cause de los tests rojos del backend

Punto de partida: `16 failures + 2 errors`, y una diferencia de conteo sin explicar.

**Ninguno era un fallo de la suite: eran pruebas que nunca se habían ejecutado.** Todas estaban
antes dentro de los 249 errores de contexto, y antes de eso dentro de los 343 saltos por falta de
Docker. Cuatro causas raíz, y una de ellas resultó ser un defecto de esquema real.

### 6.1 · Aislamiento: conteos de por vida sobre una base que nadie reinicia (7 fallos)

Las clases de importación no limpian nada entre tests — su `@BeforeEach` sólo acuña tokens. Siete
aserciones exigían `count(*) FROM ...import_batch == 0`, cierto sólo contra una base virgen. La
línea inmediatamente superior de cada una ya tomaba línea base y comparaba deltas.

Demostrado antes de tocar nada: `previewWritesNothing` y `oneBadRowRejectsTheWholeFile` **pasan
ejecutados en solitario** y fallan en orden de clase; y con el pool devuelto a 10 fallan igual,
lo que descarta el tope de conexiones como causa.

Ahora miden el delta. No es relajar la aserción: "esta operación no escribió ningún lote" es lo
que el test dice en su nombre; "nunca ha existido un lote" es lo que estaba midiendo.

### 6.2 · Aserciones obsoletas respecto al producto (3 fallos)

- `retiredRolesAreRejected` exigía que `roles:["DESTINATION"]` devolviera 400. El enum tiene
  ORIGIN y DESTINATION, y `ck_location_role_role` permite ambos: el test pedía rechazar un rol
  válido. Ahora usa `DC`, de los cinco valores V14 que V23 retiró y el único que no es además un
  `LocationType`.
- `executionLifecycleRecordsActualTimes` recibía 409 al completar. V27 prohíbe cerrar un viaje
  sobre stops que nadie resolvió, y el test es de la era V25. Ahora recorre cada stop
  `arrive → complete`, que es el camino feliz que su nombre promete.
- `createMasterdata` leía `$.roles` como null. **La respuesta era correcta**: un `POST` contra la
  API en marcha devuelve `roles:["ORIGIN"]`. Fallaba la forma de la aserción — `value(List)`
  compara contra el resultado crudo del path en vez de casar elemento a elemento. Con
  `Matchers.contains`, el escenario ordenado de trece pasos pasa entero: sus otras cinco fallas
  eran ésta en cascada.

### 6.3 · Fixture malformada (1 fallo, intermitente)

`multipleRolesAccepted` construía el CSV con `String.join(",", fields)` mientras la columna de
roles vale `ORIGIN,DESTINATION` — la grafía que documenta `LocationImportTemplate`. Esa coma es
un separador como cualquier otro: la fila ganaba un campo en silencio y la ubicación salía con un
rol. La fixture ahora entrecomilla los campos que contienen el delimitador, que es RFC 4180 y lo
que el lector ya espera.

### 6.4 · Un defecto de esquema real en V23 (el que estaba escondido debajo)

Al corregir un UUID no hexadecimal de la fixture de backfill (`...0000000000r1`; `r` no es hex),
el fallo cambió de forma en lugar de desaparecer:

    ERROR: insert or update on table "transport_order" violates foreign key
    constraint "fk_transport_order_destination"

V23 mueve `route`, `route_stop`, `transport_order`, `planning_run` y `trip_stop` de
`tms.origin`/`tms.destination` a `tms.location`. En las cinco secciones escribía los ids nuevos
**antes** de cambiar las claves foráneas. Las viejas siguen apuntando a las tablas heredadas, así
que escribir un id de `tms.location` con ellas activas se rechaza de plano.

No se veía porque no puede verse en una instalación nueva: V1–V35 corren contra tablas vacías,
los `UPDATE` no tocan filas y no se comprueba nada. Hace falta una actualización con datos, y en
concreto el caso para el que existe esta migración: un origen y un destino heredados que son el
mismo lugar físico, fusionados en una ubicación cuyo id no coincide con ninguno de los dos.

El literal malformado abortaba el lote de la fixture, así que la fila que dispara el fallo nunca
llegaba a insertarse. Un defecto tapaba al otro.

**Corregido con autorización explícita antes de hacerlo.** Cada sección ahora suelta sus
constraints, repunta, y añade las nuevas. Los trece tests de backfill se ejecutan por primera vez
y pasan.

## 7. Backend tests — antes

    ./mvnw -B clean verify        BUILD FAILURE

    Tests run: 1300   Failures: 16   Errors: 2   Skipped: 0
    Clases en rojo: 8 de 117
    1 rerun (CanonicalLocation): Run 1 ERROR, Run 2 PASS

Y bajo ese rerun, doce pruebas de la clase anidada `Backfill` **que no llegaban a ejecutarse**:
su `@BeforeAll` moría en V23 y surefire lo contabilizaba como flake.

## 8. Backend tests — después

    ./mvnw -B clean verify        BUILD SUCCESS

    Tests run: 1312   Failures: 0   Errors: 0   Skipped: 0
    Clases: 117, ninguna en rojo
    Reruns: 0
    Líneas con `Skipped: [1-9]`: 0

Doce pruebas más que antes, y son precisamente las de `Backfill` que el defecto de V23 impedía
arrancar. **Skipped = 0 de verdad**: Docker estuvo disponible y ninguna suite Testcontainers
quedó sin ejecutar.

Suites bajo vigilancia especial, todas en verde: `FlywayMigrationIntegrationTest` (4),
`LocationApiIntegrationTest` (18), `PlanningApiIntegrationTest` (56),
`CanonicalLocationConstraintIntegrationTest` (13 + 11), `TenantRlsIsolationIntegrationTest` (5),
`IntegrationTenancyIsolationIntegrationTest` (16), `ShipmentOutboxTenancyIsolationIntegrationTest`
(6), `SchemaExposureIntegrationTest` (10), `TenancyConstraintIntegrationTest` (10) y las siete
familias de constraints.

## 9. Explicación Surefire / Failsafe / reruns

La diferencia entre el resumen de consola y el agregado de los XML queda resuelta, no estimada.

    Consola de Maven                      1312 tests
    Suma del atributo `tests` en 117 XML  1305 tests
    Suma de elementos <testcase>          1312 tests

El desajuste es de **un solo fichero**: `TEST-…FleetApiIntegrationTest.xml` declara `tests="21"`
en el atributo del `<testsuite>` pero contiene **28** elementos `<testcase>`. Esos 7 son la
diferencia exacta. Esa clase tiene cuatro clases `@Nested` (`carriers`, `drivers`,
`vehicle types`, `vehicles`) y el atributo agregado sólo cuenta parte de ellas; los
`<testcase>` están todos.

    CONTEO CANÓNICO = 1312 ejecutados / 1312 pasados / 0 fallos / 0 errores / 0 omitidos

Es el número que respaldan tanto la consola como el recuento de casos reales, y el que se usa en
el bloque final. No hay `failsafe-reports`: el proyecto ejecuta todo con surefire, unitarias e
integración juntas. Reruns en la ejecución final: **0**, así que ninguna cifra depende de un
segundo intento.

En la ejecución anterior sí hubo 1 rerun, y era importante: escondía un `@BeforeAll` roto detrás
de un "flake". Que ahora sean cero es parte del resultado, no un detalle.

## 10. Testing de frontend agregado

`vite.config.ts` ya declaraba un bloque `test` completo apuntando a `./src/test/setup.ts`, y ese
fichero era un `export {}` con un comentario diciendo que el port de tests quedaba fuera de
alcance. La infraestructura estaba a medias; se completó en vez de rehacerse.

Instalado: `@testing-library/react`, `@testing-library/jest-dom`, `@testing-library/user-event`.

**37 pruebas en 4 ficheros**, elegidas para detectar regresiones reales:

| Fichero | Pruebas | Qué protege |
|---|---|---|
| `httpClient.test.ts` | 14 | Construcción de la petición (URL contra la base, query que omite nulos en vez de mandarlos vacíos, `X-Company-Id`, bearer, `Content-Type` sólo con cuerpo) y traducción RFC 9457 a `ApiError` con status/code/correlationId/errores de campo, más el único refresh-y-reintento que no debe entrar en bucle |
| `problemMessages.test.ts` | 9 | Que la copy ramifica por `code` y **nunca** filtra `detail`. Una prueba afirma que un nombre de constraint de PostgreSQL no puede llegar a la pantalla de un planificador |
| `criticalEndpoints.test.ts` | 10 | Las rutas literales de Ubicaciones, Torre de control y Reportes/KPIs, y que Orígenes y Destinos son la misma colección filtrada por `role` |
| `ReportsPage.test.tsx` | 4 | Las tres ramas de una pantalla crítica: loading, success y error. La fixture de éxito es **una respuesta real capturada de la API en marcha**, no escrita a mano |

Cubre los cinco mínimos pedidos: construcción de endpoint, render de los tres estados, traducción
de Problem Details, una página crítica y la utilidad de API más crítica.

No se usó `--passWithNoTests` en ningún sitio.

## 11. E2E agregado

Playwright no estaba: `00f9386` se llevó `playwright.config.ts`, las diez specs y
`@playwright/test`. Se incorporó una suite mínima con `npm run e2e` disponible.

Contra el **bundle construido** y no contra el servidor de desarrollo — la misma decisión que
tomó `a785eb6` antes de que la suite se perdiera, y por la misma razón: `vite dev` transforma cada
módulo la primera vez que se pide y una suite que visita veintiuna pantallas lo hace rehacer el
grafo una y otra vez. Puerto propio (4183) para no chocar con un `preview` abierto a mano.

**33 pruebas en 3 specs:**

    login.spec.ts          3   La pantalla se sirve y pide correo y contraseña; no sale a la red
                               con el formulario vacío; la marca es visible
    navigation.spec.ts    29   Las 21 rutas del menú las sirve el servidor y no un 404 (lo que
                               hace que una recarga profunda funcione); las 7 obligatorias
                               redirigen al login sin sesión; una ruta inexistente no rompe nada
    sidebar-smoke.spec.ts  1   Las 21 pantallas abiertas una tras otra en la misma sesión de
                               navegador, afirmando cero errores de consola y cero peticiones
                               caídas acumuladas

**Sin credenciales en el repositorio.** La suite cubre lo alcanzable sin sesión; los flujos
autenticados esperan a que el entorno de prueba ofrezca un proveedor de identidad, y así queda
anotado en §21.

## 12. Flyway status

    LATEST_SOURCE_MIGRATION = V35     (35 ficheros, V1…V35, sin huecos)
    LATEST_DB_MIGRATION     = V35
    PENDING_MIGRATIONS      = 0
    FAILED_MIGRATIONS       = 0
    MIGRACIONES NUEVAS      = 0
    MIGRACIONES MODIFICADAS = 1       (V23, autorizada; §6.4)

Verificado migrando desde cero contra un PostGIS 17-3.5 desechable creado para esto. **Ninguna
migración se aplicó a ninguna base remota**, ni se intentó.

Nota sobre el checksum: al cambiar V23 cambia su checksum. Como ninguna base persistente lo
tiene aplicado, no hay deriva que reparar. Una instalación que ya hubiera corrido V23 fallaría la
validación y necesitaría un `repair` deliberado — está en §21.

## 13. Runtime smoke

Backend recompilado desde el árbol actual (`sha256:6171a3b8000b4f43…`) sobre una base recreada de
cero, y frontend reconstruido. Para llamar con la seguridad **activa** se usa un emisor OIDC local
(par RSA propio, JWKS publicado, token RS256 firmado) y la identidad `admin@demo.local` del seed
`local_dev_seed.sql`: siguen validándose firma, emisor, audiencia, expiración, resolución de
principal, `X-Company-Id`, capabilities y RLS.

    GET /api/v1/system/info    200
    GET /v3/api-docs           200      151 caminos

    /api/v1/masterdata/locations             PRESENT
    /api/v1/monitoring/control-tower         PRESENT
    /api/v1/monitoring/control-tower/trips   PRESENT
    /api/v1/reporting/kpis                   PRESENT
    /api/v1/reporting/kpis/export            PRESENT

    SIDEBAR: 24/24 en 200      404 inesperados: 0      500: 0

Barrido de errores escondidos sobre los siete logs de esta fase (381.801 bytes): 62 coincidencias
de `404` y 9 de `500`, revisadas una a una — 41 son correlation-ids del log de Maven, 21 son
títulos de mis propias pruebas e2e ("…no un 404"), y de los `500`: correlation-ids y el aviso de
build `Some chunks are larger than 500 kB`. **Ninguna es un código de respuesta HTTP.**
`resource-not-found`, `internal-error`, `Failed to fetch`, `CORS`, `Unhandled`, `TypeError` y
`console.error`: 0 en todos.

## 14. Archivos modificados

20 modificados y 11 nuevos, en cuatro commits.

    Backend, código de producción (3)
      TripRepository.java                              1 línea (un comentario)
      LocalProfileDatabaseGuard.java                   +55
      V23__location_canonical_unification.sql          reordenado, 5 secciones

    Backend, pruebas (12 modificados + 1 nuevo)
      NativeQueryQuotingTest.java                      NUEVO
      LocalProfileDatabaseGuardTest.java               +139
      CanonicalLocation / SchemaExposure / Tenancy / LocationApi / LocationImport /
      OrderImport / CarrierImport / VehicleImport / VehicleTypeImport / PlanningApi /
      EndToEndSmoke                                    fixes puntuales
      application-test.yml                             tope de pool

    Frontend (4 modificados + 10 nuevos)
      package.json, package-lock.json, .gitignore, src/test/setup.ts
      httpClient.test.ts, problemMessages.test.ts, criticalEndpoints.test.ts,
      ReportsPage.test.tsx, test/fixtures/kpiReport.ts
      playwright.config.ts, e2e/login.spec.ts, e2e/navigation.spec.ts,
      e2e/sidebar-smoke.spec.ts, e2e/support/modules.ts, e2e/support/console.ts

    Documentación (4)
      TMS_RUNTIME_DIAGNOSIS.md, TMS_REMEDIATION_REPORT.md,
      TMS_FINAL_RUNTIME_VERIFICATION.md, TMS_FINAL_CLEANUP_AND_PUSH.md

## 15. Commits creados

Cuatro, cada uno coherente por sí solo. No se reescribió historia, no hubo squash ni amend.

## 16. Commit hashes

    3467c648c026304eb3a973a5faceca3419cb5a97  fix(tms): the API could not start, and the guard
                                              that should have said so was blind
    43b52d198481b4280511028d2c355522c40c428f  fix(db): V23 repointed rows while the old foreign
                                              keys were still in force
    1f588949a1ddda0411197ec3b6e026f8cb6f3dc8  test(api): repair the integration tests that had
                                              never actually run
    d6c77e58885a844f8e437a22025809c885baa37b  test(web): a regression and e2e baseline for a
                                              frontend that had neither
    __DOCS_HASH__  docs(tms): record the verification, remediation and cleanup

## 17. Resultado del push

__PUSH_RESULT__

## 18. HEAD local

    __LOCAL_HEAD__

## 19. HEAD remoto

    __REMOTE_HEAD__

## 20. Archivos NO commiteados deliberadamente

| Archivo | Por qué no |
|---|---|
| `backend/tms-api/.env`, `.env.test-user`, `.env.bak.20260819105358` | Configuración local con credenciales. Ignorados por `.gitignore` y así deben seguir |
| `frontend/tms-web/.env`, `.env.local` | Ídem |
| `backend/tms-api/target/`, `frontend/tms-web/dist/` | Artefactos de build |
| `frontend/tms-web/node_modules/` | Dependencias |
| `playwright-report/`, `test-results/`, `blob-report/` | Artefactos de Playwright. Se añadieron al `.gitignore` en esta sesión |
| Todo lo del scratchpad (logs, jar de ejecución, JWKS, tokens) | Material de verificación, fuera del repositorio por diseño |
| `tms-overnight-pack/` | Sin seguimiento desde antes de esta sesión. **No se tocó**: CLAUDE.md dice expresamente que no se stagee |

Nada se borró. `git clean` no se usó.

## 21. Riesgos y residuos

**1 · `backend/tms-api/.env` apunta a una base Supabase remota.** `TMS_DB_URL` va a
`aws-0-us-east-1.pooler.supabase.com` con `TMS_FLYWAY_ENABLED=true` y perfil `local`. Es el
accidente que el javadoc del guard dice haber sufrido dos veces; está de vuelta por tercera. Con
el guard ya corregido, un arranque distraído se detiene nombrando el host en lugar de crear el
esquema allí. **Conviene ponerlo en cuarentena.**

**2 · El checksum de V23 cambió.** Ninguna base persistente lo tiene aplicado, así que no hay
deriva aquí. Una instalación que ya hubiera corrido V23 fallaría la validación de Flyway y
necesitaría un `repair` deliberado. Antes de desplegar contra cualquier entorno con historia,
comprobar `flyway_schema_history`.

**3 · El e2e no cubre flujos autenticados.** No hay credenciales en el repositorio ni proveedor
de identidad en el entorno de prueba, así que la suite cubre login, enrutado, guards y smoke de
consola sin sesión. Cubrir Torre de control o Reportes *con datos* pide un proveedor de identidad
de prueba; es el siguiente paso natural de esta base.

**4 · Divergencias de arquitectura heredadas de `00f9386`, sin resolver.** MUI como librería
principal pese a que CLAUDE.md lo desaconseja y sin ADR que lo justifique; `render.yaml:61`
apuntando a un `Dockerfile` que ese commit borró, de modo que el servicio `tms-web` de Render no
puede construir. Ninguna se tocó: quedan fuera del alcance de una limpieza.

**5 · `tms-by-ebim` no está levantado** y sus puertos (54321/54322) los ocupa el proyecto
`comerza`. Por eso toda la verificación usó un contenedor desechable propio y un emisor OIDC
local. Ambos proyectos ajenos quedaron intactos.

**6 · 17 warnings de `oxlint`**, ninguno error: 12 `only-export-components`, 4
`incompatible-library`, 1 `set-state-in-effect` (`AppLayout.tsx:73`). Y el bundle avisa de dos
chunks por encima de 500 kB (`index` 1.110 kB, `ReportsPage` 381 kB). Ninguno bloquea.
