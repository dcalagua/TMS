# TMS by EBIM — Reconstrucción de QAS y preparación del despliegue

Ejecutado: 2026-08-25, 15:30–16:20 UTC.

**Resultado: `PARTIAL`.** El trabajo de base de datos y de código está terminado y verificado:
QAS se reconstruyó entera desde cero, el conflicto de checksum de V23 **desapareció**, y las seis
puertas de calidad están verdes. Lo que no pude hacer es **crear el PR, mergearlo y observar el
despliegue**: este entorno no tiene credencial de API de GitHub ni acceso a Render/Amplify. Me
detuve ahí en lugar de buscar un atajo.

---

## 1. Autorización de reset

El propietario autorizó expresamente borrar la información del proyecto Supabase de TMS y
reconstruirla:

    DATA_PRESERVATION_REQUIRED   = NO
    FULL_DATABASE_RESET_AUTHORIZED = YES

La autorización se aplicó **sólo** a TMS. Los otros ocho proyectos de la organización —`comerza`,
`eChange`, `eSupplier`, `EWM by EBIM`, `eExpense`, `GMAO`, `bbp-scribe`, `PCG-PRD`— no se tocaron,
ni se consultaron más allá de listarlos para descartarlos.

## 2. Proyecto afectado

    nombre  : tms-by-ebim
    región  : us-east-1
    host    : db.<ref>.supabase.co
    estado  : ACTIVE_HEALTHY

Verificación triple antes de ejecutar nada destructivo:

    1. El nombre del proyecto es de TMS                                    OK
    2. El ref coincide con el identificado en el preflight                 OK
    3. La base tenía `tms.flyway_schema_history` con V23 = 196196275       OK
    4. No es comerza / eChange / eSupplier / EWM (refs distintos)          OK

    DESTRUCTIVE_TARGET_VERIFIED = YES

## 3. Evidencia previa al reset

Registrada en `TMS_QAS_RESET_BEFORE.md`, capturada a las 15:39:14 UTC con consultas `SELECT`.
Resumen de lo que se eliminó:

    Flyway   : V1..V23 aplicadas, 0 fallidas, última el 2026-08-20 23:32
    V23      : checksum 196196275  <- el de la versión anterior a la corrección
    Tablas   : 36 en el esquema `tms`
    Datos    : 1 organización, 2 empresas, 3 personas, 1 ubicación, 1 origen, 1 transportista
               0 pedidos, 0 viajes, 0 eventos de auditoría

Era el seed de demostración, no datos de negocio.

## 4. Reset realizado

    DROP SCHEMA IF EXISTS tms CASCADE;

Una sola sentencia, acotada al esquema de la aplicación. Comprobado inmediatamente después:

    tms schema        : ABSENT       (0 tablas, to_regclass del history = null)
    esquemas restantes: auth, extensions, graphql, graphql_public, public, realtime,
                        storage, vault      <- todos de la plataforma, intactos
    auth.users        : 3 cuentas preservadas
    rol tms_app       : preservado
    extensión postgis : preservada

Conservar lo que vive fuera de `tms` fue deliberado y es lo que permitió reconstruir **sin
inventar ninguna contraseña**: las cuentas siguen siendo las mismas y el seed sólo las vuelve a
enlazar. Las migraciones que crean el rol y la extensión están guardadas (`CREATE EXTENSION IF NOT
EXISTS`, `CREATE ROLE` bajo `IF NOT EXISTS`), así que replicar V1..V35 no tropieza con
encontrárselos ya puestos.

## 5. Flyway V1 → V35

Ejecutado con **el mecanismo real del despliegue**: el backend arrancado con
`SPRING_PROFILES_ACTIVE=prod` contra QAS, no un cliente SQL. Lo que valida el entorno es que
arranque el backend, no que las sentencias se dejen ejecutar.

Leído de la base, no de un log:

    latest    = V35
    applied   = 35        (V1…V35, más la fila 0 de creación de esquema = 36 filas)
    failed    = 0
    pending   = 0         (35 ficheros fuente = 35 aplicadas)
    tablas    = 52        (eran 36 en V23)
    permisos  = 47        roles = 4

Un **segundo arranque** confirmó lo que hará el despliegue del merge: no añadió ninguna fila
—`max(installed_rank)` sigue en 35 y la última instalación sigue siendo la de las 15:46— y el
backend levantó igual. Es decir, **validó** V35 en lugar de volver a migrar, que es exactamente el
comportamiento esperado.

    GET /actuator/health/readiness  -> 200 {"status":"UP"}
    GET /api/v1/system/info         -> 200 profiles:["prod"]
    GET /v3/api-docs                -> 401   (correcto bajo `prod`: la documentación queda
                                              detrás de autenticación, según application-prod.yml)

## 6-7. Checksum de V23

    ANTES (en QAS)     196196275     <- la V23 previa a la corrección
    AHORA (código dev) -194785114
    AHORA (en QAS)     -194785114    <- coinciden

    V23_CHECKSUM_CONFLICT = NO

El checksum se calcula con el mismo algoritmo que Flyway (CRC32 acumulado sobre los bytes UTF-8 de
cada línea, sin separadores); su coincidencia exacta con el valor almacenado es lo que acredita
que el cálculo es fiable.

No se usó `flyway repair`, no se escribió una V36 compensatoria y no se volvió a la V23
defectuosa. Las tres dejan historia y código discrepando sobre qué dice V23, que es justo la
situación que causó el problema.

### Validación del esquema que produce V23

    Las 12 claves foráneas de route, route_stop, transport_order, planning_run y trip_stop
    apuntan a tms.location.
    Claves foráneas que aún apunten a tms.origin / tms.destination: 0
    ck_location_role_role = CHECK (role IN ('ORIGIN','DESTINATION'))
    51 de 52 tablas con RLS; la única sin ella es flyway_schema_history, como está diseñado
    40 políticas p_tenant_company_scope

    V23_SCHEMA_VALIDATION = PASS

## 8. Seed de QAS

`supabase/seeds/qas_seed.sql`, nuevo. Es el seed **de QAS**, no el local promovido: el local crea
la organización y para ahí; QAS necesita que las veintiuna pantallas del menú muestren filas.

Propiedades: re-ejecutable (comprobado — segunda ejecución, conteos idénticos), sin ninguna
contraseña, todo prefijado `QAS-`, fechas literales y no `CURRENT_DATE` para que dos ejecuciones
den el mismo entorno, y enlace con Supabase Auth **por correo** en vez de por id escrito a mano,
de modo que sirve igual contra un QAS recreado.

| Entidad | Filas | | Entidad | Filas |
|---|---|---|---|---|
| organización | 1 | | transportistas | 1 |
| empresas | 2 | | tipos de vehículo | 1 |
| personas (todas enlazadas a Auth) | 3 | | vehículos | 1 |
| membresías / roles | 3 / 3 | | conductores | 1 |
| ubicaciones | 4 | | tarifarios | 1 |
| roles de ubicación ORIGIN / DESTINATION | 2 / 3 | | pedidos + líneas | 3 + 3 |
| zonas | 2 | | planes | 1 |
| frecuencias | 1 | | viajes | 1 |
| rutas + paradas | 1 + 2 | | | |

Supera el mínimo pedido. `QAS-MIXTO` lleva los dos roles operativos a propósito: es el caso que da
sentido al modelo canónico —una tienda es destino del reparto y origen de la devolución— y es el
mismo que destapó el defecto de ordenación de V23.

    QAS_SEED = PASS

Cuatro defectos del seed salieron durante la escritura y se corrigieron contra el esquema real, no
adivinando: `route_stop` y `transport_order_line` tienen restricciones `DEFERRABLE INITIALLY
DEFERRED` —lo tienen que ser, porque reordenar paradas pasa por estados intermedios con secuencias
repetidas— y PostgreSQL no admite una restricción diferible como árbitro de `ON CONFLICT`; tres
árbitros más no coincidían con las restricciones declaradas; `rate_card` exige al menos un
componente de precio; y `trip_number` es un entero, no texto.

## 9-13. Configuración de despliegue

    QAS_DEPLOY_PLATFORM       = Render (backend) + AWS Amplify (frontend)
    QAS_BACKEND_PROFILE       = prod        (render.yaml lo fija literalmente)
    QAS_FLYWAY_ENABLED        = YES         (application-prod.yml: enabled: true, sin variable
                                             que lo apague)
    BACKEND_DEPLOY_CONFIG     = PASS
    FRONTEND_DEPLOY_CONFIG    = PASS

**Backend.** `backend/tms-api/Dockerfile` existe en la ruta que declara el blueprint, construye
con `eclipse-temurin:21-jdk` y sirve con `21-jre-alpine`, corre como usuario no root, honra el
`$PORT` de Render, y su `healthCheckPath: /actuator/health/readiness` resuelve a una sonda que
existe (`management.endpoint.health.probes.enabled: true`) y que responde UP sólo **después** de
Flyway. Esa última propiedad es la que impide que un despliegue sirva tráfico mientras el esquema
migra — o que lo sirva si Flyway aborta.

**Frontend.** `render.yaml` declaraba un servicio `tms-web` construyendo desde
`./frontend/tms-web/Dockerfile`, fichero retirado en `00f9386` cuando la publicación pasó a
Amplify. El servicio llevaba desde entonces apuntando a una ruta inexistente. **Se retiró del
blueprint** en lugar de devolver el Dockerfile: Amplify ya construye este frontend y mantener dos
canales de publicación para un mismo artefacto es una invitación a que se desincronicen. Queda un
comentario en su lugar explicando qué había y qué haría falta para volver.

    servicios en render.yaml : 1  (tms-api)
    referencias a un Dockerfile de frontend : 0

> **Requiere una acción manual:** retirar un servicio del blueprint no lo borra en Render. Si en
> el panel existe un servicio `tms-web`, hay que retirarlo a mano.

**Desarrollo local deja de depender de QAS.** El `.env.example` del backend invitaba al problema:
su sección de Auth sugería `https://REPLACE-PROJECT-REF.supabase.co`, así que rellenarlo con
honestidad significaba nombrar un proyecto hosted — y desde ahí, apuntar también la base es el
paso obvio. Ahora apunta al stack local por defecto y su cabecera declara para qué entorno es y
para cuáles no. `LocalProfileDatabaseGuard` sigue siendo el control que era; esto quita la
invitación en vez de depender del rechazo. El `.env` real no se tocó: es local y no está
versionado.

`docs/environments/QAS.md` fija qué es QAS, cómo se reconstruye, y la tabla LOCAL / QAS / PROD.

### Variables (nombres reales; valores no observables desde aquí)

| Variable | Declarada | Valor |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | render.yaml, literal | `prod` |
| `TMS_DB_URL` / `TMS_DB_USERNAME` / `TMS_DB_PASSWORD` | render.yaml, `sync: false` | en el panel |
| `TMS_SUPABASE_JWT_ISSUER_URI` / `TMS_SUPABASE_JWKS_URI` | render.yaml, `sync: false` | en el panel |
| `TMS_CORS_ALLOWED_ORIGINS` | render.yaml, `sync: false` | en el panel |
| `VITE_API_BASE_URL` / `VITE_SUPABASE_URL` / `VITE_SUPABASE_ANON_KEY` | consola de Amplify | en la consola |

    QAS_BACKEND_POINTS_TO_QAS_DB       = UNKNOWN
    QAS_FRONTEND_POINTS_TO_QAS_BACKEND = UNKNOWN
    QAS_AUTH  = UNKNOWN
    QAS_CORS  = UNKNOWN

Cuatro `UNKNOWN` y no cuatro `PASS`: sin acceso al panel de Render ni a la consola de Amplify no
he visto ninguno de esos valores, y declararlos correctos sería inventarlo. Lo que **sí** quedó
demostrado es que un backend con perfil `prod` apuntado a esta base arranca, migra y responde —
así que si esas variables apuntan aquí, el despliegue funcionará.

## 14-17. Puertas de calidad

| Puerta | Resultado |
|---|---|
| `./mvnw -B clean verify` | **BUILD SUCCESS** — 1312 run, 0 failures, 0 errors, 0 skipped, 0 reruns |
| `npm run typecheck` | PASS |
| `npm run lint` | PASS — 17 warnings, 0 errores |
| `npm test` | PASS — 4 ficheros, 37/37 |
| `npm run build` | PASS |
| `npm run e2e` | PASS — 33 pasados, 7 saltados, 0 fallidos |

Los 7 saltados son el smoke autenticado nuevo, y se saltan porque no hay credenciales en este
entorno. Saltado se reporta como saltado: una suite que pasa en verde sin haberse autenticado
contra nada es peor que una que dice que no corrió.

    AUTHENTICATED_E2E = 0/0/0   (mecanismo entregado, no ejecutado)

El mecanismo queda listo: `e2e/authenticated.spec.ts` inicia sesión por el formulario real y abre
los siete módulos centrales comprobando que ninguna llamada de API devuelva 400 o peor, que no
haya errores de consola y que no caiga ninguna petición. Lee `E2E_USER_EMAIL`, `E2E_USER_PASSWORD`
y `E2E_BASE_URL` del entorno; ninguna credencial vive en el repositorio.

    SECRETS_COMMITTED = NO

Auditado antes de commitear: las coincidencias de `BEGIN PRIVATE KEY`, `eyJ` y `service_role` son
todas prosa de estos informes describiendo los patrones buscados, más los avisos del propio
`.env.example`. Ningún `.env` real entra. Ninguna línea con la forma `clave = "valor-largo"`.

## 18-19. Commits y push

Cuatro commits sobre `dev`:

    ade43c2  feat(qas): rebuild the QAS database from the corrected migration history
    64bff7c  fix(deploy): stop declaring a frontend service Render cannot build
    c15f9aa  test(qas): add an authenticated smoke for a real environment
    a2b8569  docs(tms): separate LOCAL from QAS in the backend environment template

    git push origin dev    48e7ed0..a2b8569    (fast-forward, sin force)

    LOCAL == REMOTE = YES        WORKING_TREE_CLEAN = YES
    DEV_PUSH = PASS

## 20. Preflight repetido

    merge dev -> qas      sin conflictos (merge-tree sale 0, sólo el OID del árbol)
    dev por delante       11 commits
    V23 código            -194785114
    V23 en QAS            -194785114     -> COINCIDEN
    Flyway QAS            V35, 0 pendientes, 0 fallidas
    backend deploy        PASS
    frontend deploy       PASS
    secretos              NO

Los tres bloqueantes del preflight anterior están resueltos: el checksum de V23, la ausencia de un
entorno QAS definido, y el `render.yaml` apuntando a un Dockerfile inexistente.

## 21-25. PR, merge y despliegue — NO EJECUTADOS

**Este es el punto donde me detuve.**

    gh pr create ...
    -> To get started with GitHub CLI, please run: gh auth login

`gh` está instalado (2.98.0) pero **no autenticado**, y no hay `GH_TOKEN` ni `GITHUB_TOKEN` en el
entorno ni configuración en `~/.config/gh`. El push por SSH funciona —por eso `dev` está subido—
pero crear un PR y mergearlo van por la API de GitHub, que necesita una credencial que aquí no
existe.

No lo rodeé haciendo un merge local y empujando a `origin/qas`. El repositorio usa PRs (el
historial de `qas` son merges de los PR #4, #5 y #6), saltárselo evitaría cualquier política de
revisión configurada, y dispararía un despliegue que tampoco puedo observar.

Y no puedo observarlo porque tampoco hay acceso a Render ni a Amplify: no hay MCP de Render, no
hay credenciales de Amplify, y las URLs reales de QAS no están en el repositorio. Por eso las
fases 23, 24 y 25 quedan sin ejecutar en lugar de rellenadas con suposiciones.

    PR_DEV_TO_QAS         = NOT_CREATED
    PR_MERGE              = NOT_EXECUTED
    BACKEND_QAS_DEPLOY    = NOT_EXECUTED
    FRONTEND_QAS_DEPLOY   = NOT_EXECUTED
    SMOKE QAS             = NOT_EXECUTED

### Qué hace falta para terminar

1. `gh auth login` en esta máquina, o exportar `GH_TOKEN` con permiso de `repo`. Con eso el PR y
   el merge son inmediatos.
2. Confirmar en el panel de Render que `TMS_DB_URL` del servicio `tms-api` apunta a este proyecto
   Supabase, y que `TMS_CORS_ALLOWED_ORIGINS` contiene la URL del frontend QAS.
3. Retirar a mano el servicio `tms-web` de Render si todavía existe.
4. Para el smoke autenticado: exportar `E2E_USER_EMAIL`, `E2E_USER_PASSWORD` y `E2E_BASE_URL`
   apuntando a QAS, y ejecutar `npm run e2e`.

## 26. Riesgos residuales

**1 · Las variables de QAS no están verificadas.** Es el único cabo que impide afirmar que el
despliegue funcionará. La base está lista y demostrada; lo que falta es comprobar que el servicio
desplegado apunta a ella.

**2 · El servicio `tms-web` puede seguir existiendo en Render.** Retirarlo del blueprint no lo
borra. Si existe y despliega desde `qas`, tras el merge fallará al no encontrar el Dockerfile.

**3 · QAS y desarrollo local comparten proyecto Supabase.** Es el mismo y único proyecto TMS. El
`.env.example` y `LocalProfileDatabaseGuard` ahora empujan a que el desarrollo local use una base
local, pero mientras no exista un segundo proyecto, un `.env` mal apuntado sigue pudiendo alcanzar
QAS. Un proyecto local por persona, o un QAS separado, cierra esto del todo.

**4 · PROD no existe.** Cuando exista debe ser **otro** proyecto Supabase, nunca éste.

**5 · Deuda heredada, sin bloquear.** MUI como librería principal sin ADR pese a CLAUDE.md; 17
warnings de `oxlint`; dos chunks por encima de 500 kB.

**6 · El smoke autenticado nunca se ha ejecutado.** El mecanismo está entregado y compila, pero
hasta que corra con credenciales reales no es evidencia de nada.
