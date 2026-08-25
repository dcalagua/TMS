# TMS by EBIM — Preflight DEV → QAS

Ejecutado: 2026-08-25, 03:45–04:05. **Read-only**: no se modificó código ni migraciones, no se
ejecutó Flyway, no hubo SQL de escritura, ni commit, ni push, ni PR, ni despliegue, ni cambio de
variables. Contra la base remota únicamente `SELECT`.

**Resultado: `NO_GO`.**

El código está sano —backend 1312/1312 verde, frontend 37/37, e2e 33/33, sin conflictos de merge y
sin secretos en el diff— pero **la única base de datos TMS que existe tiene la V23 antigua
aplicada**, y su checksum no coincide con el de `dev`. Con `validate-on-migrate: true`, el backend
no llegaría a arrancar. Hay además un segundo bloqueante de despliegue y una ausencia de fondo: no
existe un entorno QAS separado.

---

## 1. Git state

    LOCAL_HEAD      = 48e7ed005bed9558d732eac1d6cd4c01779f98dd
    ORIGIN_DEV_HEAD = 48e7ed005bed9558d732eac1d6cd4c01779f98dd
    ORIGIN_QAS_HEAD = eacbbf9436bd3e899e650e6f9a89f8a27e62e696

    WORKING_TREE_CLEAN          = YES
    LOCAL_DEV_EQUALS_ORIGIN_DEV = YES
    Rama local                  = dev (correcta; no hizo falta ningún checkout)

Ramas remotas: `origin/dev`, `origin/qas`, `origin/main` (0b94fb5).

### Discrepancia documental anterior, resuelta

`TMS_FINAL_CLEANUP_AND_PUSH.md` declara en §16 cinco commits terminando en `865f6e4`, y en §18/§19
ese mismo hash como HEAD local y remoto. El HEAD real es **`48e7ed0`**.

No es un error de medición: el informe se escribe *antes* del push, y sus secciones 17–19 sólo
pueden rellenarse después. Al rellenarlas se creó un sexto commit —`48e7ed0 docs(tms): record the
push result and the resulting HEADs`— que por definición no puede aparecer dentro del documento que
él mismo modifica. Los cinco hashes de §16 son correctos; lo que quedó desactualizado es §18/§19,
que describen el estado un commit antes del final. **El hash canónico de esta rama es `48e7ed0`**,
y `origin/dev` coincide exactamente.

## 2. origin/dev vs origin/qas

    merge-base = 015f8c57001d2e60ad6c026d62c6f2795902918a
    qas está 3 commits por delante de la base; dev, 7.

Los 3 de `qas` son merges de PR desde `dev` (#4, #5, #6) y **no aportan contenido propio**:
verificado con `git diff --quiet origin/qas 015f8c5`, que sale limpio — el árbol de `qas` es
idéntico al merge-base. Por eso el diff de dos puntos y el de tres puntos coinciden exactamente.

Consecuencia práctica: el PR es puramente aditivo sobre `qas`, sin nada que reconciliar.

## 3. Commits del PR

    COMMITS_DEV_AHEAD_QAS = 7

    48e7ed0  docs(tms): record the push result and the resulting HEADs
    865f6e4  docs(tms): the record of the diagnosis, the remediation and this cleanup
    d6c77e5  test(web): a regression and e2e baseline for a frontend that had neither
    1f58894  test(api): repair the integration tests that had never actually run
    43b52d1  fix(db): V23 repointed rows while the old foreign keys were still in force
    3467c64  fix(tms): the API could not start, and the guard that should have said so was blind
    00f9386  refactor(web): la interfaz pasa a MUI y adopta el diseño de la suite EWM

Nótese que `00f9386` —el refactor a MUI, con sus 45.000 líneas borradas— **todavía no está en
QAS**. Este PR lo lleva junto con todo lo demás.

## 4. Archivos del PR

    FILES_CHANGED = 336        (30 añadidos · 172 borrados · 134 modificados)

| Categoría | Ficheros |
|---|---|
| FRONTEND | 209 |
| TESTS | 112 |
| CONFIG / DEPLOY | 7 |
| DOCUMENTATION | 5 |
| BACKEND | 2 |
| DATABASE / FLYWAY | 1 |
| OTHER | 0 |

    MIGRATIONS_NEW     = NONE
    MIGRATIONS_CHANGED = V23__location_canonical_unification.sql   (modificada, no nueva)

Ninguna otra migración de V1–V35 aparece en el diff. Los 7 de CONFIG/DEPLOY merecen atención:

    D  frontend/tms-web/.dockerignore
    D  frontend/tms-web/Dockerfile            <-- §13
    D  frontend/tms-web/nginx.conf.template
    M  frontend/tms-web/.env.example
    M  frontend/tms-web/.gitignore
    M  frontend/tms-web/package.json
    M  frontend/tms-web/package-lock.json

`render.yaml` **no** está en el diff: entra en QAS sin cambios, apuntando a un fichero que el PR
borra.

## 5. Conflictos

    MERGE_CONFLICTS      = NO
    MERGE_CONFLICT_FILES = NONE

Comprobado sin tocar ninguna rama: `git merge-tree --write-tree origin/qas origin/dev` termina con
código 0 y su única línea de salida es el OID del árbol resultante
(`682c9e6f73649ce0c44a2df9fd5b9171aa610075`), sin lista de ficheros en conflicto. El
`git merge-tree` clásico sobre el merge-base tampoco produce un solo marcador. Era previsible:
el árbol de `qas` es el merge-base.

## 6. Build backend

Ejecutado sobre el árbol actual, que es bit a bit `origin/dev` (HEAD idéntico y árbol limpio).

    ./mvnw -B clean verify         BUILD SUCCESS

    Tests run: 1312   Failures: 0   Errors: 0   Skipped: 0
    Reruns: 0         Líneas con `Skipped: [1-9]`: 0

Docker disponible durante toda la ejecución, así que ninguna suite Testcontainers quedó sin correr.

## 7. Build frontend

    npm ci             EXIT 0
    npm run typecheck  PASS
    npm run lint       PASS   (17 warnings, 0 errores)
    npm test           PASS   4 ficheros, 37/37
    npm run build      PASS
    npm run e2e        PASS   33/33

## 8. Flyway source

    SOURCE_MIGRATIONS_COUNT = 35
    SOURCE_FIRST_VERSION    = V1
    SOURCE_LAST_VERSION     = V35
    VERSION_GAPS            = NONE
    DUPLICATE_VERSIONS      = NONE

Sólo migraciones versionadas: ni `R__` repetibles ni `U__` de undo.

Configuración efectiva (`application.yml`, heredada por todos los perfiles):

    spring.flyway.enabled             = true
    spring.flyway.locations           = classpath:db/migration
    spring.flyway.schemas             = tms
    spring.flyway.default-schema      = tms
    spring.flyway.create-schemas      = true
    spring.flyway.baseline-on-migrate = false
    spring.flyway.validate-on-migrate = true
    spring.flyway.clean-disabled      = true

    application-prod.yml : spring.flyway.enabled = true   (fijo, sin variable de entorno)
    application-local.yml: spring.flyway.enabled = ${TMS_FLYWAY_ENABLED:true}

> **Cuando el backend QAS arranque, ¿Spring Boot ejecutará Flyway automáticamente?**
>
> **YES.** Bajo `prod` —que es lo que `render.yaml:27-28` fija— `enabled: true` está escrito a
> mano en el perfil y no hay variable que lo apague. Y con `validate-on-migrate: true`, Flyway
> **valida antes de migrar**: un checksum que no cuadre aborta el arranque en vez de continuar.

## 9. Estado real de `flyway_schema_history` en la base

    QAS_DB_IDENTIFIED  = NO        (no existe una base QAS dedicada — ver abajo)
    QAS_DB_CONNECTION  = PASS      (sobre la única base TMS que existe, en modo lectura)

### Qué base es, y por qué no es "la de QAS"

Nueve proyectos Supabase en la organización. **Exactamente uno es de TMS**: `tms-by-ebim`
(`ocxmsluzegpkezkpcqjj`, us-east-1). No hay `tms-qas`, `tms-staging` ni equivalente, y
`list_branches` sobre ese proyecto devuelve `[]`: tampoco existe una rama de base de datos que
haga de QAS. Railway no aloja ningún proyecto TMS (sólo `discerning-reflection`,
`Estimador EBIM` y `Pichangol`).

Ese ref, `ocxmsluzegpkezkpcqjj`, es además el que apunta el `.env` local del frontend y el emisor
del `.env` del backend. Es decir: **la base a la que apunta el entorno local es la única base TMS
que existe.**

    SELECT current_database(), current_user;
      -> postgres / postgres

    SELECT to_regclass('tms.flyway_schema_history');
      -> tms.flyway_schema_history          (existe; el esquema tms también)

    QAS_FLYWAY_HISTORY_EXISTS = YES
    QAS_LATEST_MIGRATION      = V23
    QAS_FAILED_MIGRATIONS     = 0
    Filas con versión         = 23   (V1…V23, más la fila 0 de creación de esquema)
    Última migración aplicada = 2026-08-20 23:32

Contenido: 36 tablas en `tms`, 1 organización, 2 empresas, 3 usuarios, 1 ubicación, 0 pedidos,
0 viajes. Es el seed de demostración (`local_dev_seed.sql`), no datos de negocio.

**Corrección de un supuesto anterior.** `docs/hardening-v4/FINAL_REPORT.md` afirmaba que las
migraciones "nunca se han aplicado a ninguna base, aquí ni en ningún sitio". Es falso: V1–V23 se
aplicaron a este proyecto hosted los días 19 y 20 de agosto. Al autorizar el cambio de V23 dejé
escrito el riesgo — *"si V23 SÍ se aplicó en algún sitio que yo no veo (por ejemplo el Supabase
remoto del .env), ese entorno fallaría la validación de Flyway"*. **Ese entorno existe y ese riesgo
se ha materializado.**

## 10. Análisis V23

    DEV_V23_SHA256           = b7c61a4b72ba02c93ce5a6471680872b27f7cdfa261654311ac556bc62a4501f
    DEV_V23_FLYWAY_CHECKSUM  = -194785114

    QAS_BRANCH_V23_SHA256    = 888a5006bda21ee202b534e4c11b26e43fd6345e632705e84d6345d4eb424979
    QAS_BRANCH_V23_CHECKSUM  = 196196275
    QAS_BRANCH_V23_EQUALS_DEV = NO
    V23_CHANGED_BETWEEN_QAS_AND_DEV = YES     (44 líneas de diferencia)

    BASE V23 APLICADA:  version=23, checksum=196196275, success=true,
                        installed_on=2026-08-20 23:32:26

El checksum almacenado en la base (`196196275`) coincide **exactamente** con el que calcula el
algoritmo de Flyway sobre la V23 de la rama `qas`, y **no** con el de la V23 de `dev`
(`-194785114`). El checksum se calculó con la misma función que usa Flyway —CRC32 acumulado sobre
los bytes UTF-8 de cada línea, sin separadores— y su coincidencia exacta con el valor almacenado es
la prueba de que el cálculo es fiable.

    V23_CHECKSUM_COMPATIBLE = NO
    V23_DEPLOYMENT_RISK     = BLOCKER          (ESCENARIO B)

Escenario B del encargo: la V23 está presente y su checksum no coincide. Flyway fallará en
`validate`.

    QAS_DB_DISPOSABLE = UNKNOWN

No hay documentación que declare esta base desechable. Su contenido es de demostración, lo que
*sugiere* que podría reconstruirse, pero eso es una decisión de negocio y no una evidencia. **No se
reconstruyó, ni se reparó, ni se tocó.**

## 11. Migraciones que ejecutaría QAS al arrancar

| Versión | Source DEV | Historial de la base | Acción esperada |
|---|---|---|---|
| V1 – V22 | existe | aplicada, success | validate → OK |
| **V23** | **existe (modificada)** | **aplicada, checksum 196196275** | **validate → FALLA: checksum mismatch** |
| V24 – V35 | existe | no aplicada | *nunca se alcanzan* |

Detalle de lo que quedaría sin ejecutar (12 migraciones):

    V24 frequency exception cutoff and route stop service time
    V25 trip execution lifecycle
    V26 fleet driver and trip assignment
    V27 trip stop execution and transport events
    V28 delivery result and pod evidence
    V29 tracking position and provider scope
    V30 rates and trip costing
    V31 carrier tendering
    V32 notification
    V33 trip cost planning date
    V34 company settings and iam administration
    V35 integration webhooks

    MIGRATIONS_EXPECTED_ON_QAS_START = NONE (el arranque aborta en validate, antes de migrar)

Ninguna se ejecutó ni se simuló contra la base.

## 12. Configuración backend deploy

    QAS_DEPLOY_PLATFORM        = Render (blueprint `render.yaml`) — sin CI/CD en el repositorio
    QAS_BACKEND_DEPLOY_METHOD  = Docker, servicio web `tms-api`

`.github/` contiene sólo `modernize/java-upgrade/` (hooks de una herramienta), **ningún workflow**.
No hay `scripts/deploy*`, ni documentación de despliegue en `docs/` ni en el README. La plataforma
se deduce de los dos únicos ficheros de configuración que existen, no de una declaración.

Comprobaciones sobre `backend/tms-api/Dockerfile`:

| Comprobación | Resultado |
|---|---|
| El fichero existe en la ruta que declara `render.yaml` | **SÍ** |
| `dockerContext: ./backend/tms-api` | correcto |
| Java 21 | `eclipse-temurin:21-jdk` (build) → `21-jre-alpine` (runtime) |
| Usuario no root | `USER tms` |
| Puerto | `EXPOSE 8080`, y el entrypoint honra `${PORT}` de Render |
| Comando de arranque | `exec java $JAVA_OPTS -jar app.jar --server.port=${PORT:-${TMS_API_PORT}}` |
| Healthcheck configurado | `/actuator/health/readiness` |
| ¿Ese endpoint existe? | **SÍ** — `management.endpoint.health.probes.enabled: true` y `health` expuesto |

    BACKEND_DEPLOY_CONFIG = PASS

El healthcheck es acertado precisamente por lo que aquí importa: la sonda de readiness responde UP
sólo *después* de Flyway, de modo que un despliegue no puede servir tráfico mientras el esquema
migra — y tampoco lo servirá si Flyway aborta.

## 13. Configuración frontend deploy

    FRONTEND_DOCKERFILE_EXISTS              = NO      (en dev; en qas SÍ existe hoy)
    RENDER_REFERENCES_FRONTEND_DOCKERFILE   = YES     (render.yaml:61)
    QAS_ACTUALLY_USES_THIS_RENDER_SERVICE   = UNKNOWN

`render.yaml` declara un segundo servicio `tms-web` con
`dockerfilePath: ./frontend/tms-web/Dockerfile`. Ese fichero **existe hoy en `origin/qas` y este PR
lo borra**, junto con `.dockerignore` y `nginx.conf.template`.

En paralelo, `amplify.yml` declara el frontend construido por AWS Amplify desde
`appRoot: frontend/tms-web`, con artefacto estático en `dist` — sin contenedor y sin nginx. El
mensaje de `00f9386` lo dice explícitamente: *"el frontend se publica con Amplify desde 0b94fb5"*, y
deja anotado que *"render.yaml todavía declara un servicio tms-web que apunta al Dockerfile que
este commit borra"*.

Las dos configuraciones se contradicen y **no puedo determinar cuál está activa**: no hay acceso al
panel de Render desde este entorno, ni MCP de Render, ni documentación que lo declare.

    FRONTEND_DEPLOY_CONFIG = FAIL (condicional)

Siguiendo la regla del encargo al pie de la letra: si el servicio `tms-web` de Render está activo
para QAS, entonces `RENDER_REFERENCES_FRONTEND_DOCKERFILE=YES` +
`FRONTEND_DOCKERFILE_EXISTS=NO` + servicio en uso ⇒ **BLOCKER**. Si QAS no usa ese servicio, es
deuda heredada y no bloquea. Como el tercer término es `UNKNOWN`, **no puede declararse PASS**.

Es verificable en un minuto: abrir el panel de Render y mirar si el servicio `tms-web` existe y de
qué rama despliega.

## 14. Variables QAS

Todos los secretos de `render.yaml` están declarados con `sync: false`, que es lo correcto —Render
los pide en el panel y no viven en el repositorio— pero implica que **desde aquí no son
observables**. No hay acceso al panel.

**Backend** (nombres reales del proyecto, tal y como los declara `render.yaml`):

| Variable | En render.yaml | Valor visible |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | declarada, valor literal | `prod` |
| `TMS_DB_URL` | declarada `sync: false` | no observable |
| `TMS_DB_USERNAME` | declarada `sync: false` | no observable |
| `TMS_DB_PASSWORD` | declarada `sync: false` | no observable |
| `TMS_SUPABASE_JWT_ISSUER_URI` | declarada `sync: false` | no observable |
| `TMS_SUPABASE_JWKS_URI` | declarada `sync: false` | no observable |
| `TMS_CORS_ALLOWED_ORIGINS` | declarada `sync: false` | no observable |
| `TMS_FLYWAY_ENABLED` | **no declarada** | irrelevante bajo `prod`: el perfil fija `enabled: true` |

**Frontend** (`render.yaml`, servicio `tms-web`): `VITE_API_BASE_URL`, `VITE_SUPABASE_URL`,
`VITE_SUPABASE_ANON_KEY`, las tres `sync: false`. En Amplify se configuran en la consola y tampoco
son observables. `VITE_GOOGLE_MAPS_API_KEY` no está declarada en `render.yaml` aunque el código la
soporta y degrada con elegancia si falta.

    QAS_BACKEND_POINTS_TO_QAS_DB       = UNKNOWN
    QAS_FRONTEND_POINTS_TO_QAS_BACKEND = UNKNOWN
    QAS_AUTH_POINTS_TO_QAS             = UNKNOWN
    QAS_CORS_INCLUDES_QAS_FRONTEND     = UNKNOWN

Ninguna se declara PASS sin haberla visto. Y hay una razón de fondo por la que las cuatro no pueden
ser YES: **no existe una base, ni un proyecto de Auth, que sea "de QAS"**. Sólo hay un proyecto TMS.
Si QAS apunta a él, comparte base y proveedor de identidad con el entorno local.

## 15. Supabase / Auth / CORS

`ADR-004` y `RLS_STRATEGY` sitúan los objetos de la aplicación en el esquema `tms`, fuera del Data
API de Supabase; la base remota lo confirma (36 tablas en `tms`). Supabase actúa aquí sólo como
PostgreSQL gestionado y como emisor de identidad.

Bajo el perfil `prod`, `SupabaseJwtDecoders` exige que el emisor y el JWKS sean `https` y no
resuelvan a loopback (`docs/security/SECURITY_BASELINE.md:83`). Es una salvaguarda real, pero no
puede comprobar *cuál* proyecto es: un emisor de otro entorno pasaría igual mientras sea https.

CORS parte vacío por diseño —`allowed-origins: ${TMS_CORS_ALLOWED_ORIGINS:}`— así que si esa
variable no está puesta en el entorno, ninguna llamada del navegador funciona. No es observable
desde aquí.

## 16. Secretos

    SECRETS_IN_DEV_QAS_DIFF = NO

El diff son 75.541 líneas. Las coincidencias léxicas son abundantes (`secret` 177, `token` 205,
`password` 62, `Authorization` 23) y se revisaron: son prosa de los informes de las fases
anteriores, nombres de clase (`WebhookSecretCipher`, `IntegrationSecrets`, `SecretRevealDrawer`) y
cabeceras en tests. Cero coincidencias de `BEGIN PRIVATE KEY`, `SUPABASE_SERVICE_ROLE_KEY`,
`DB_PASSWORD` y `service_role`; cero cadenas `eyJ…` (JWT) añadidas; ninguna línea añadida con la
forma `clave = "valor-largo"`.

El único `.env*` en el diff es `frontend/tms-web/.env.example`, y sólo contiene marcadores
(`REPLACE-PROJECT-REF`, `REPLACE-WITH-SUPABASE-ANON-KEY`).

## 17. Predicción del arranque QAS

Suponiendo el escenario más probable —que QAS apunte a `tms-by-ebim`, la única base TMS que existe:

| Etapa | Veredicto | Por qué |
|---|---|---|
| Deploy backend (Render recibe el push) | PASS | El blueprint es válido y el servicio está declarado |
| Container / build | PASS | `backend/tms-api/Dockerfile` existe, Java 21, multi-stage correcto |
| Spring config | PASS | `prod` no tiene defaults: una variable ausente falla rápido, que es lo deseado |
| DataSource QAS | UNKNOWN | Depende de `TMS_DB_URL`, no observable |
| **Flyway validate** | **BLOCKER** | V23 aplicada con checksum `196196275`; el código trae `-194785114` |
| Flyway migrate | *no se alcanza* | `validate-on-migrate: true` aborta antes |
| JPA / ApplicationContext | *no se alcanza* | |
| Security / OIDC | *no se alcanza* | |
| Readiness health | *no se alcanza* | `/actuator/health/readiness` nunca responde UP; Render marca el despliegue como fallido |

El fallo sería exactamente:

    FlywayValidateException: Validate failed: Migrations have failed validation
    Migration checksum mismatch for migration version 23

Y el frontend, en paralelo: si el servicio `tms-web` de Render está activo, su build falla al no
encontrar el `Dockerfile`. Si el frontend va por Amplify, construye sin problema —`npm ci` y
`npm run build` están verificados verdes— pero apuntaría a un backend que no arranca.

Nota: el healthcheck de readiness protege el entorno de un despliegue a medias — Render no enruta
tráfico a una instancia que no responde UP. El daño esperado es un despliegue fallido, no una base
corrompida. **Flyway aborta en validate sin escribir nada.**

## 18. Blockers

**B1 · Checksum de V23 incompatible con la base.** La única base TMS tiene V23 aplicada con el
checksum de la versión antigua. Con `validate-on-migrate: true`, el backend no arranca. Es el
bloqueante duro y no depende de ninguna suposición: checksum almacenado y checksum calculado están
ambos medidos arriba.

**B2 · No existe un entorno QAS identificable.** No hay proyecto Supabase de QAS, ni branch de
base, ni proyecto en Railway, ni workflow de CI, ni documentación de entornos. `qas` es una rama de
Git. Por el criterio del encargo, "QAS DB desconocida" es por sí solo `NO_GO`; y si QAS resulta
apuntar al mismo proyecto que el entorno local, entonces dev y QAS comparten base, lo que es un
problema de separación de entornos anterior a este PR.

**B3 · `render.yaml` referencia un Dockerfile que este PR borra.** Bloqueante si el servicio
`tms-web` de Render está activo para QAS; deuda heredada si no. No determinable desde aquí, y por
tanto no declarable como PASS.

## 19. Warnings

**W1 · 17 warnings de `oxlint`**, ningún error: 12 `only-export-components`, 4
`incompatible-library`, 1 `set-state-in-effect` (`AppLayout.tsx:73`). No bloquean.

**W2 · Dos chunks por encima de 500 kB** (`index` ~1.110 kB, `ReportsPage` ~381 kB). Aviso de
tamaño, no error.

**W3 · El e2e no cubre flujos autenticados.** Cubre login, enrutado, guards y smoke de consola sin
sesión, porque no hay credenciales en el repositorio ni proveedor de identidad de prueba.

**W4 · Deuda MUI sin ADR.** `00f9386` adopta MUI como librería principal, lo que contradice
CLAUDE.md, y este PR lo lleva a QAS. Necesita su propio ADR, pero no bloquea un despliegue.

**W5 · `backend/tms-api/.env` apunta a un pooler Supabase remoto** con Flyway habilitado y perfil
`local`. No afecta a QAS —bajo `prod` el guard ni siquiera se registra— pero sigue siendo un
arranque distraído de distancia en la máquina de quien desarrolla.

## 20. Recomendación

**No abrir el PR todavía.** El código está listo; el entorno no.

Tres decisiones, en este orden, y ninguna es mía:

1. **Definir qué es QAS.** Hoy no existe como base de datos. O se crea un proyecto Supabase propio
   para QAS —y entonces la V23 nueva se aplica limpia desde cero, sin conflicto alguno, porque
   `QAS_V23_PRESENT` pasaría a ser `NO` (Escenario A, riesgo bajo)— o se declara explícitamente que
   `tms-by-ebim` es QAS y se asume que comparte base con el entorno local.

2. **Resolver la V23 en la base elegida**, si resulta ser la actual. Hay tres caminos y ninguno
   debe tomarse sin decidirlo: recrear la base (es un seed de demo, 0 pedidos y 0 viajes, así que
   el coste sería bajo); `flyway repair` para reescribir el checksum almacenado, que sólo es
   correcto si se acepta que el V23 ya aplicado y el nuevo producen el mismo esquema —lo producen,
   porque el cambio es de orden de sentencias, no de resultado final—; o revertir la corrección de
   V23 y arrastrar el defecto del camino de actualización. **No ejecuté ninguno.**

3. **Aclarar el frontend**: retirar el servicio `tms-web` de `render.yaml` si Amplify es el canal
   real, o devolver el `Dockerfile`. Es una línea de configuración y elimina B3 por completo.

Hecho eso, el PR queda en `GO`: no hay conflictos, no hay secretos, y las seis puertas de build
están verdes con evidencia fresca de esta misma ejecución.
