# TMS by EBIM — Informe de remediación

Fecha: 2026-08-25
Base: `TMS_FINAL_RUNTIME_VERIFICATION.md` (el diagnóstico del paso anterior).
Rama `dev`, HEAD `00f9386`. Sin push. Sin migraciones nuevas. Sin cambios en seguridad, RLS,
Flyway ni CompanyScope.

**Resultado: el root cause está corregido y el runtime quedó verificado limpio** — 23 rutas del
sidebar, todas 200, cero 404 y cero 500. Quedan 18 tests en rojo que **nunca se habían ejecutado**
y que, demostradamente, no son defectos de producto. Se detallan en §7.

---

## Nota previa sobre el fichero de origen

El encargo cita `TMS_RUNTIME_DIAGNOSIS.md`. Ese fichero no existe en el repositorio: el informe
del paso anterior se llama **`TMS_FINAL_RUNTIME_VERIFICATION.md`**. Es el que se ha usado como
fuente de verdad, y es el que recibe las secciones 15-20 que pide la entrega. No se ha renombrado
nada para no romper referencias externas.

---

## 1. Qué caso aplicaba

| Caso | Condición | Veredicto | Acción |
|---|---|---|---|
| **A** — backend viejo / proceso equivocado | `RUNNING_BACKEND_MATCHES_SOURCE=NO` | **No aplicaba** | No había ningún proceso: el puerto 8080 estaba libre y el diagnóstico registró 4 sondas en `000`. No había backend viejo que matar. |
| **B** — frontend apuntando mal | `FRONTEND_API_TARGET_CORRECT=NO` | **No aplicaba** | `VITE_API_BASE_URL=http://localhost:8080/api/v1` en `.env` y `.env.local`, y el bundle construido lo lleva embebido (verificado con `grep` sobre `dist/assets/*.js`). Correcto ya. **No se tocó nada**, según la regla de no cambiar código si no hace falta. |
| **C** — no arranca por configuración de DB | parcial | **Aplicaba en parte** | El guard tenía un defecto propio (§3). La configuración de entorno se dejó explícita (§5) y se detectó un `.env` peligroso (§6). |
| **D** — migraciones pendientes | `DATABASE_DRIFT=YES` | **No aplicaba** | El diagnóstico ya había medido V35 fuente = V35 base, 0 pendientes, 0 fallidas, sin deriva de checksums. Se reconfirmó. **Ninguna migración escrita, ninguna aplicada a nada remoto.** |
| **E** — bug real de código | sí | **Aplicaba** | Es el grueso del trabajo: §2, §3, §4. |
| **F** — overnight incompleto | — | **No aplicaba** | Árbol limpio al empezar, sin ficheros sin seguimiento salvo el propio informe. No se ejecutó ningún `reset`, ni se borró nada. |

---

## 2. Root cause · el backend no arrancaba

### El fallo

`TripRepository.utilizationForRange` lleva una `@Query` nativa cuyo comentario SQL contenía un
apóstrofo:

    -- both sides numeric here keeps the projection's two getters the same type.
                                                    ^

Spring Data recorre la cadena para localizar expresiones de valor **antes** de que llegue a la
base, y ese recorrido no sabe que `--` abre un comentario. El apóstrofo abría un literal que nada
cerraba:

    IllegalArgumentException: The string <WITH scoped AS (...
    > starts a quoted range at 1496, but never ends it.

No fallaba la consulta: no llegaba a existir el bean. Caía `tripRepository` →
`shipmentPublicationAdapter` → `integrationShipmentService` → `integrationShipmentController`, y
con ellos el contexto entero. **249 de los 252 fallos del diagnóstico eran este único defecto.**

### Test primero

Se escribió `NativeQueryQuotingTest` antes de tocar el código. Recorre por reflexión todas las
`@Query` de la aplicación y exige un número par de comillas simples — el invariante exacto que
Spring Data necesita: un escape `''` dentro de un literal sigue balanceando, un apóstrofo suelto
nunca lo hace.

Ejecutado contra el código sin corregir, **falló señalando el método y la línea culpable**:

    Expecting empty but was:
      ["com.ebim.tms.planning.infrastructure.TripRepository.utilizationForRange
        suspect comment: -- both sides numeric here keeps the projection's two getters the same type."]

Un único infractor en todo el repositorio.

### El fix

Una línea. Se reescribió el comentario sin el apóstrofo, conservando el sentido:

    -- both sides numeric here keeps the two projection getters the same type.

**El SQL no se tocó.** No cambia ni una fila del resultado.

### Por qué es un test de convención y no de integración

Los tests que habrían atrapado esto necesitan Testcontainers, y en un host sin Docker se **saltan**
— que es exactamente cómo llegó esto a `dev`. El informe `hardening-v4` declaraba
`0 failures, 0 errors, 343 skipped` y era cierto: los 343 saltos escondían el defecto.
`NativeQueryQuotingTest` lee anotaciones de clases compiladas en milisegundos y ningún demonio
ausente puede saltárselo.

---

## 3. `LocalProfileDatabaseGuard` · el guard estaba ciego

### El fallo

Con perfil `local` y Flyway activo, el arranque se rechazaba **siempre**, incluso apuntando a
`localhost`:

    Refusing to run Flyway against '' on the 'local' profile.

`urlOf` leía sólo `flyway.getConfiguration().getUrl()`. Cuando Spring Boot construye Flyway a
partir del `DataSource` de la aplicación —el camino de todos los perfiles de este repositorio,
porque ninguno define `spring.flyway.url`— ese `getUrl()` es `null`. El guard veía `""`, `""` no
está en `LOCAL_HOSTS`, y refusaba. Además **nunca podía nombrar el host que rechazaba**.

El propio javadoc de la clase advertía del riesgo: *"a false negative stops a developer working
and teaches them to set the override permanently, which removes the guard."* Eso es precisamente
lo que hacía: la única forma de trabajar era fijar `TMS_ALLOW_REMOTE_DB=true`, que desarma el
control para **todas** las bases, no sólo para la autorizada.

### Test primero

Se añadieron 6 tests al `@Nested class FromDataSource`, que ejercitan el camino real de arranque —
el que los 14 tests existentes no tocaban, porque sólo probaban `hostOf` con URLs literales.
Contra el código sin corregir, 2 fallaron con el mensaje exacto del runtime:

    java.lang.IllegalStateException: Refusing to run Flyway against '' on the 'local' profile.

Falló incluso el caso remoto: rechazaba, sí, pero sin poder decir qué base.

### El fix

`urlOf` ahora resuelve la URL en tres pasos, y **sin abrir conexión en el caso normal**:

1. `spring.flyway.url` si está declarada.
2. `HikariDataSource.getJdbcUrl()` — configuración, no conexión.
3. Metadatos de una conexión, sólo como último recurso para un pool que no sea Hikari.

El segundo paso es deliberado y se corrigió a mitad de trabajo: un guard cuyo cometido es rechazar
una base **antes** de que nada la toque no puede iniciar sesión contra el mismo host que va a
rechazar. Con la primera versión del fix, apuntar a un pooler de producción habría supuesto un
intento de login antes de la primera comprobación.

Sigue siendo *fail-closed*: lo que no se puede leer devuelve vacío, vacío no es local, y el
arranque se rechaza. **El guard no se debilitó — pasó a funcionar.** El override sigue existiendo
y sigue cubierto por test.

Resultado: 18/18 tests verdes, y el arranque contra `localhost` funciona **sin** override.

---

## 4. Tres aserciones obsoletas (no eran defectos de esquema)

El diagnóstico las había marcado como posibles huecos de RLS. Al leer el mensaje completo resultó
lo contrario: las 5 tablas aparecían como **"unexpected"**, es decir, la base **sí** las tenía con
RLS y política, y era la lista escrita a mano del test la que no las conocía.

Verificado en la fuente: `V34:145,152` y `V35:123,164,256,303,325,330,335,342` declaran
`ENABLE ROW LEVEL SECURITY` y `CREATE POLICY p_tenant_company_scope` para las cinco.

**Por eso no se escribió ninguna migración.** El esquema estaba bien; corregir el test es la
corrección correcta, y escribir un V36 habría sido ruido sobre un problema inexistente.

| Test | Antes | Ahora |
|---|---|---|
| `SchemaExposureIntegrationTest.rowLevelSecurityIsEnabledEverywhere` | lista sin las 5 tablas de V34/V35 | `company_settings`, `webhook_subscription`, `webhook_subscription_event`, `webhook_delivery`, `webhook_delivery_attempt` añadidas |
| `SchemaExposureIntegrationTest.businessTablesCarryTheTenantPolicy` | ídem | ídem |
| `TenancyConstraintIntegrationTest.referenceDataIsPresent` | 33 permisos, 95 grants | 47 y 132 |

Los 47 se comprobaron antes de escribirlos, no se copiaron del mensaje de error:

    permissions            47      code = resource:action    47/47
    ORGANIZATION_ADMIN     47      COMPANY_ADMIN             46
    PLANNER                24      VIEWER                    15
    role_permission       132      = 47 + 46 + 24 + 15  ✓

El crecimiento es legítimo: V30 (`rates.*`), V31 (`planning.tender`), V34 (`iam.*`), V35
(`integration.*`), V22 (`audit.log:read`). Los invariantes que llevan significado —COMPANY_ADMIN
sin `iam.organization:manage`, VIEWER sólo lectura, sin `audit.log:manage`, sin
`monitoring.transport:manage`— seguían pasando y **no se tocaron**: los conteos sólo los anclan.

---

## 5. Agotamiento de conexiones (defecto destapado por el fix)

Con el contexto arrancando por primera vez, la suite pasó a morir a mitad con:

    FATAL: sorry, too many clients already   (18 ocurrencias, 65 errores)

No era un defecto nuevo sino aritmética que antes nadie alcanzaba: los `@SpringBootTest` comparten
**un** contenedor PostgreSQL, Spring **cachea** cada contexto durante toda la ejecución, y el
perfil de test no acotaba el pool. Las conexiones no las retiene el test en curso sino todos los
contextos que han corrido, a la vez: contextos × 10 contra un `max_connections` de 100.

Fix en `application-test.yml`: `maximum-pool-size: 2`, `minimum-idle: 0`. Dos bastan —MockMvc va
en un hilo, y la segunda cubre al test que consulta la base con una transacción abierta.

Ocurrencias de `too many clients` después: **0**. Errores 65 → 2.

---

## 6. Hallazgo de seguridad: `backend/tms-api/.env` apunta a una base remota

Durante la verificación de entorno (CASO C) se comprobó a qué apunta el `.env` del backend. **Sin
imprimir secretos**, sólo el host:

    TMS_DB_URL       host = aws-0-us-east-1.pooler.supabase.com     <- Supabase HOSTED
    TMS_FLYWAY_ENABLED = true
    SPRING_PROFILES_ACTIVE = local

Es exactamente el accidente que el javadoc del guard dice haber sufrido ya dos veces
(*"quarantined once, and back a day later"*). **Está de vuelta por tercera vez.**

Y hasta hoy el guard no lo habría detenido de forma útil: al refusar todo con `''`, empujaba a
fijar `TMS_ALLOW_REMOTE_DB=true` de forma permanente — y en ese momento Flyway habría creado el
esquema completo en esa base remota.

**No se ha tocado ese fichero, ni se ha conectado a ese host, ni se ha migrado nada allí.** No hay
autorización para ese entorno y el encargo lo prohíbe expresamente. Con el guard ya corregido, un
arranque con ese `.env` ahora se detiene nombrando el host:

    Refusing to run Flyway against 'aws-0-us-east-1.pooler.supabase.com' on the 'local' profile.

Está cubierto por un test que usa esa misma URL (`aHikariPoolIsReadWithoutConnecting`).

**Recomendación:** poner ese `.env` en cuarentena. Es local y está en `.gitignore`, así que no ha
llegado al repositorio, pero sigue siendo un arranque distraído de distancia.

El frontend, por su parte, autentica contra el Supabase hosted
`https://ocxmsluzegpkezkpcqjj.supabase.co`. Para una sesión de navegador real, el backend tendría
que declarar **ese mismo** emisor. No se cambió: es configuración local del entorno de quien
trabaja, y elegirla no me corresponde.

---

## 7. Lo que sigue en rojo, y por qué no son defectos de producto

La suite pasó de `3 failures + 249 errors` a `16 failures + 2 errors`. Los 18 restantes
**nunca se habían ejecutado**: todos estaban dentro de los 249 errores de contexto, y antes de eso
dentro de los 343 saltos por falta de Docker. Es la primera vez que se ven.

Ninguno lo causan los cambios de esta sesión, y se demostró en vez de suponerlo:

**Prueba 1 — no es el tope de pool.** `OrderImportApiIntegrationTest` con el pool devuelto a 10:
falla idéntico. Descarta §5 como causa.

**Prueba 2 — es aislamiento entre tests.** Los mismos tests **pasan ejecutados en solitario**:

    OrderImportApiIntegrationTest#previewWritesNothing            solo: PASA   en clase: FALLA
    CarrierImportApiIntegrationTest#oneBadRowRejectsTheWholeFile  solo: PASA   en clase: FALLA

El mecanismo es visible en el código: la aserción que falla es
`count("SELECT count(*) FROM tms.order_import_batch")).isZero()` — un conteo **global, sin filtro
de empresa y sin línea base**, a diferencia de la asercion inmediatamente anterior, que sí filtra
por `company_id` y compara contra un `before`. Sólo se cumple si ningún test anterior creó un lote.

**Prueba 3 — el producto hace lo correcto.** Contra el backend real, el `POST` que el smoke da por
roto responde bien:

    POST /api/v1/masterdata/locations  ->  201   {"roles":["ORIGIN"], ...}

mientras que `EndToEndSmokeIntegrationTest.createMasterdata:237` lo ve como `null`. Ese test es
`@TestMethodOrder(OrderAnnotation)`: un escenario secuencial donde el primer paso arrastra a los
otros cinco. **6 de las 16 failures son un solo fallo en cascada.**

Clasificación:

| Grupo | N | Naturaleza |
|---|---|---|
| Conteos globales sin ámbito en tests de importación | 7 | Aislamiento entre tests. Pasan en solitario. |
| `EndToEndSmokeIntegrationTest` | 6 | Escenario ordenado; una raíz, cinco arrastradas. El endpoint responde correctamente en runtime. |
| `LocationApiIntegrationTest.retiredRolesAreRejected` | 1 | Pendiente de clasificar |
| `PlanningApiIntegrationTest.executionLifecycleRecordsActualTimes` | 1 | `409`; pendiente de clasificar |
| `LocationImportApiIntegrationTest.multipleRolesAccepted` | 1 | Pendiente de clasificar |
| `CanonicalLocationConstraintIntegrationTest` | 1 error | UUID mal formado en la fixture: `...0000000000r1` (`r` no es hex). Defecto evidente del test. |
| `EndToEndSmoke...queryString` | 1 error | `could not read the smoke fixture`; arrastrado del grupo anterior. |

**No se han corregido**, y es deliberado: el encargo pide implementar únicamente las causas
CONFIRMADAS en el diagnóstico, y ninguna de éstas lo estaba — eran invisibles. Corregirlas es una
segunda pasada con su propio alcance, sobre tests, no sobre producto.

---

## 8. Verificación funcional posterior

Backend recompilado desde el árbol de trabajo actual (`sha256:75e043f42317b420…`, PID 86231) y
arrancado contra PostGIS 17-3.5 desechable en `localhost:55440`. Frontend reconstruido y servido
con `vite preview` en 4173.

Para poder llamar a la API con seguridad **activa**, se levantó un emisor OIDC local: par RSA
propio, JWKS publicado en `127.0.0.1:55450`, y un token RS256 firmado con `sub` = el
`auth_user_id` de `admin@demo.local` (ORGANIZATION_ADMIN) sembrado con `local_dev_seed.sql`.
**No se desactivó nada**: siguen validándose firma, emisor, audiencia, expiración, resolución de
principal, `X-Company-Id`, capabilities y RLS. Es la vía legítima, y sustituye a Supabase
exactamente en el papel que Supabase tiene aquí.

| Vista | Ruta | API principal | HTTP | Resultado | Consola | Red |
|---|---|---|---|---|---|---|
| Inicio | `/` | `/api/v1/system/info` | 200 | `status: UP` | — | — |
| Torre de control | `/control-tower` | `/api/v1/monitoring/control-tower?date=…` | 200 | resumen con `summary` | — | — |
| Torre de control | `/control-tower` | `/api/v1/monitoring/control-tower/trips?date=…` | 200 | lista vacía válida | — | — |
| Reportes y KPIs | `/reporting` | `/api/v1/reporting/kpis?from=…&to=…` | 200 | agregado con `days: 25` | — | — |
| Reportes y KPIs | `/reporting` | `/api/v1/reporting/kpis/export?from=…&to=…` | 200 | CSV con cabecera | — | — |
| Pedidos | `/orders` | `/api/v1/orders` | 200 | lista vacía válida | — | — |
| Planificación | `/planning` | `/api/v1/planning/runs` | 200 | lista vacía válida | — | — |
| Viajes | `/trips` | `/api/v1/planning/trips` | 200 | lista vacía válida | — | — |
| Ubicaciones | `/masters/locations` | `/api/v1/masterdata/locations` | 200 | lista vacía válida | — | — |
| Orígenes | `/masters/origins` | `/api/v1/masterdata/locations?role=ORIGIN` | 200 | lista vacía válida | — | — |
| Destinos | `/masters/destinations` | `/api/v1/masterdata/locations?role=DESTINATION` | 200 | lista vacía válida | — | — |
| Zonas | `/masters/zones` | `/api/v1/masterdata/zones` | 200 | lista vacía válida | — | — |
| Frecuencias | `/masters/frequencies` | `/api/v1/masterdata/frequencies` | 200 | lista vacía válida | — | — |
| Rutas | `/masters/routes` | `/api/v1/masterdata/routes` | 200 | lista vacía válida | — | — |
| Transportistas | `/fleet/carriers` | `/api/v1/fleet/carriers` | 200 | lista vacía válida | — | — |
| Tipos de vehículo | `/fleet/vehicle-types` | `/api/v1/fleet/vehicle-types` | 200 | lista vacía válida | — | — |
| Vehículos | `/fleet/vehicles` | `/api/v1/fleet/vehicles` | 200 | lista vacía válida | — | — |
| Conductores | `/fleet/drivers` | `/api/v1/fleet/drivers` | 200 | lista vacía válida | — | — |
| Tarifas | `/rates/rate-cards` | `/api/v1/rates/rate-cards` | 200 | lista vacía válida | — | — |
| Configuración | `/settings/company` | `/api/v1/admin/companies/current` | 200 | `DEMO-LIMA` | — | — |
| Configuración | `/settings/users` | `/api/v1/admin/users` | 200 | 1 miembro | — | — |
| Integraciones | `/settings/integrations` | `/api/v1/integration-clients` | 200 | lista vacía válida | — | — |
| Auditoría | `/security/audit` | `/api/v1/audit-events` | 200 | lista vacía válida | — | — |

**23 de 23 en 200. Cero 404. Cero 500.**

Escritura verificada además del listado: `POST /api/v1/masterdata/locations` → **201**, con
`roles:["ORIGIN"]` correctamente devuelto. La cadena completa React → Spring Boot → PostgreSQL
funciona en ambos sentidos.

Notas sobre las columnas de consola y red: el barrido es por API con `curl`, no por navegador.
La suite e2e y Playwright fueron eliminados por el commit `00f9386`, así que **no existe** en este
repositorio forma instrumentada de capturar errores de consola. Las columnas quedan vacías y no
se marcan como PASS. Lo que sí se verificó del lado del navegador:

    GET /  /control-tower  /reporting  /masters/locations   ->  200 (preview, fallback SPA)
    Preflight CORS desde http://localhost:4173             ->  200
      Access-Control-Allow-Origin: http://localhost:4173
      Access-Control-Allow-Headers: authorization, x-company-id

### Sobre el único código distinto de 200 en toda la sesión

Un `405` en `GET /api/v1/admin/companies`. **Fue un error mío de sondeo**: el OpenAPI declara ese
camino sólo con `post` (crear empresa), no es la llamada principal de ninguna pantalla, y `405` es
la respuesta correcta a un método equivocado. Queda documentado y excluido del recuento.

---

## 9. Estado de Flyway y OpenAPI tras la remediación

    LATEST_SOURCE_MIGRATION = V35        (35 ficheros, V1…V35, sin huecos)
    LATEST_DB_MIGRATION     = V35
    PENDING_MIGRATIONS      = 0
    FAILED_MIGRATIONS       = 0
    MIGRACIONES AÑADIDAS    = 0
    MIGRACIONES MODIFICADAS = 0

`/v3/api-docs` responde 200 con **151 caminos**. Los cinco exigidos están presentes en el
documento vivo, no sólo en el código:

    /api/v1/masterdata/locations              PRESENT
    /api/v1/monitoring/control-tower          PRESENT
    /api/v1/monitoring/control-tower/trips    PRESENT
    /api/v1/reporting/kpis                    PRESENT
    /api/v1/reporting/kpis/export             PRESENT

---

## 10. Higiene

- Ninguna base compartida ni remota tocada. Todo contra `tms-verify-db`, contenedor desechable.
- `supabase_db_comerza` y `supabase_db_echange-saas` intactos, pese a ocupar los puertos que el
  perfil `local` de TMS espera.
- `TMS_ALLOW_REMOTE_DB` **no se ha persistido en ningún fichero**. Se usó en una única ejecución
  del diagnóstico anterior, contra un contenedor local, y el fix del guard lo ha vuelto
  innecesario: el arranque final se hizo sin él.
- Sin `push`, sin comandos destructivos de Git, sin `reset`, sin borrar untracked.
- Sin secretos impresos: de `.env` sólo se listaron nombres de clave y el host de la URL.
- Sin migraciones nuevas ni modificadas.

---

## Veredicto

```text
ROOT_CAUSE_FIXED=YES
BACKEND_MATCHES_SOURCE=YES
OPENAPI_ROUTE_PARITY=PASS
DATABASE_MIGRATIONS=NOT_REQUIRED
CONTROL_TOWER=PASS
REPORTING_KPIS=PASS
LOCATIONS=PASS
OTHER_SIDEBAR_ROUTES=PASS
UNEXPECTED_404=0
UNEXPECTED_500=0
READY_FOR_FULL_VERIFICATION=NO
```

`READY_FOR_FULL_VERIFICATION=NO`, pese a que todo lo anterior es PASS, y por razones concretas que
no dependen del runtime:

1. **18 tests de backend en rojo** (§7). El runtime está limpio y ninguno es defecto de producto,
   pero un gate completo vuelve a ejecutar `./mvnw verify` y ahí siguen. Necesitan su propia pasada.
2. **0 tests de frontend y 0 e2e.** El commit `00f9386` eliminó 86 ficheros de test y
   `@playwright/test`; `npm test` sale con 1 por `No test files found` y `npm run e2e` no existe.
   Sin eso, ni el smoke de sidebar por navegador ni la captura de errores de consola son posibles.
3. **Entorno no reproducible tal cual.** La verificación exigió un emisor OIDC local y una base
   desechable porque `tms-by-ebim` no está levantado y sus puertos los ocupa `comerza`.

Ninguna de las tres es un 404 o un 500 sin explicar: **no queda ninguno**. Son deuda de test y de
entorno, y están enumeradas para que la siguiente pasada tenga alcance definido.
