# TMS Platform Provisioning V1 — final report

| | |
|---|---|
| HEAD inicial | `07a26dcbc51a0799eebd673dd1e1a1d991f82bcf` |
| HEAD final | `f5138089e2f741436c2a828c4871f348ff88bb8c` |
| Rama | `dev` — **sin push** |
| Fecha | 2026-09-18 |

## Modelo de tenant

TMS **no tiene una tabla `tenant`**. El tenant es el par organización → compañía, y el ámbito
operativo es la compañía (ADR-003). V51 no inventa un tenant de negocio: añade un **mapeo explícito**
entre el identificador del plano de control y esas dos filas, más el administrador opcional.

`controlPlaneTenantId` nunca se trata como identificador interno. `externalTenantId` es el id de la
fila de mapeo, y la organización y la compañía se devuelven a su lado.

## Migración

`V51__platform_tenant_provisioning.sql` (184 líneas, append-only, ninguna migración histórica
modificada):

- `tms.platform_tenant` — mapeo, único por `control_plane_tenant_id`, por `organization_id` y por
  `company_id`, con FK compuesta `(company_id, organization_id)` que garantiza en base que la
  compañía pertenece a su organización.
- `tms.platform_provisioning` — una fila por clave de idempotencia: qué se pidió (hash), qué se
  respondió, y quién lo pidió (`m2m_subject`, `m2m_jti`, `actor_id`, `actor_role`, `correlation_id`).

RLS habilitada en ambas con `p_backend_managed`, y **todos los privilegios revocados a `tms_app`**:
una tabla de plano de control lista todos los tenants, y no debe ser alcanzable por el rol bajo el
que corre cada petición de tenant. Provisioning escribe en la conexión del dueño porque ocurre antes
de que exista compañía alguna. Verificado en ejecución: `has_table_privilege('tms_app', …) = f`.

## Endpoints

- **Create:** `POST /internal/platform/v1/tenants`
- **Get:** `GET /internal/platform/v1/tenants/{controlPlaneTenantId}`

Sin DELETE, sin suspend, sin activate.

| | |
|---|---|
| Issuer | `masteradmin.ebim` |
| Audience | `tms.ebim` |
| Algorithm | `ES256` |
| Create scope | `tms:tenant:create` |
| Read scope | `tms:tenant:read` |
| TTL máximo | `300s` |
| Public key local | `C:\EBIM\keys\tms\qas\masteradmin-public.pem` |
| Public key QAS (env) | **`EBIM_MASTERADMIN_M2M_PUBLIC_KEY_B64`** |

## Resultados

| Gate | Resultado |
|---|---|
| M2M (matriz de tokens) | **PASS** — 17/17 |
| Aislamiento de JWT humano | **PASS** — token de plano de control → 401 en `/api/v1/me`; token humano → 401 en `/internal/platform/**` |
| Aislamiento de integración legacy | **PASS** — credencial `Basic` → 401; token de plano de control → 401 en `/integration/v1/**` |
| Create | **PASS** — 201 en test y en ejecución real |
| Get | **PASS** — 200; 404 para un tenant inexistente |
| Idempotencia | **PASS** — replay 200 con el mismo `provisioningId`; payload distinto → 409 |
| Concurrencia | **PASS** — dos llamadas simultáneas con una clave: un 201 y un 200, un solo tenant |
| Rollback atómico | **PASS** — conflicto tras insertar organización y compañía: no queda nada, y el fallo sí queda registrado |
| Auditoría | **PASS** — `m2m_subject`, `m2m_jti`, `actor_id`, `actor_role` y `correlation_id` persistidos y verificados |
| Backend (suite completa) | **1993 / 1993** — 0 fallos, 0 errores, 0 saltados, contra PostgreSQL 17.11 real |
| Secretos | **PASS** — sin PEM ni claves rastreadas; `C:\EBIM` sólo en documentación |
| PRD tocado | **NO** |
| MasterAdmin modificado | **NO** |

### Gates de frontend

Esta tarea no toca `frontend/`; los gates se ejecutan igualmente para demostrar que nada se rompió.
Con Node v22.23.2 portátil (el Node del sistema es 18 y no cumple `engines`):

| Gate | Resultado |
|---|---|
| `npm ci` | exit 0 — `found 0 vulnerabilities` |
| `npm run typecheck` | exit 0 |
| `npm run lint` | exit 0 — 0 errores, 18 warnings preexistentes |
| `npm test` | **136 / 136**, 22 ficheros |
| `npm run build` | exit 0 — `built in 6.32s` |

### Cómo se ejecutó el backend sin Docker

Docker no está instalado en esta máquina. El harness de test admite una base externa desechable, así
que la suite corrió contra **PostgreSQL 17.11 + PostGIS 3.6.2 portátil** en `localhost:55450`.
**H2 no se usó** en ningún punto, y no podría usarse: el esquema depende de PostGIS, índices únicos
parciales, `EXCLUDE` y RLS.

## Arranque local real (Fase 22)

Servicio levantado con perfil `local` contra la base desechable, y llamadas HTTP reales:

```
POST  /internal/platform/v1/tenants           → 201  provisioningId 4851891f…, PREPROVISIONED
POST  (misma Idempotency-Key)                 → 200  replayed:true, mismo provisioningId
GET   /internal/platform/v1/tenants/{id}      → 200
GET   sin token                               → 401  unauthenticated
POST  con scope de solo lectura               → 403  missing-scope
```

Estado en base tras la ejecución: 1 organización, 1 compañía, 1 administrador **sin
`auth_user_id`**, 1 mapeo, 1 registro `SUCCEEDED`; la membresía es **de organización**
(`company_id IS NULL`) con rol `ORGANIZATION_ADMIN`; y `tms_app` **no puede leer** el mapeo.

### La clave QAS real

Configurada como `EBIM_MASTERADMIN_M2M_PUBLIC_KEY_B64` en un arranque local, un token firmado con
otra clave recibe `401 invalid-m2m-token` — *"The token signature does not verify against the
configured key"*. Una clave que no se hubiera podido leer habría respondido `503
feature-not-configured`, así que esa respuesta **sólo puede venir de una clave parseada y usada**.

No se pidió ni se usó la clave privada. No existe ninguna clave privada en este repositorio, y los
tests generan claves ES256 efímeras.

## Configuración requerida en QAS

```
EBIM_MASTERADMIN_M2M_ENABLED=true
EBIM_MASTERADMIN_M2M_ISSUER=masteradmin.ebim
EBIM_MASTERADMIN_M2M_AUDIENCE=tms.ebim
EBIM_MASTERADMIN_M2M_ALGORITHM=ES256
EBIM_MASTERADMIN_M2M_MAX_TOKEN_LIFETIME=300s
EBIM_MASTERADMIN_M2M_CREATE_SCOPE=tms:tenant:create
EBIM_MASTERADMIN_M2M_READ_SCOPE=tms:tenant:read
EBIM_MASTERADMIN_M2M_PUBLIC_KEY_B64=<base64 del PEM público>   # secret/env, nunca una ruta
TMS_PLATFORM_RETAIN_REQUEST_SNAPSHOT=false
```

| | |
|---|---|
| QAS migration applied | **NO** — V51 no se ha aplicado a QAS |
| QAS backend configured | **NO** — no hay acceso a la consola ni a la base de QAS desde aquí |

## Veredicto

### `READY_FOR_MASTERADMIN_INTEGRATION` — con dos condiciones de despliegue

La integración está lista **como código**: la seguridad, el aislamiento, la idempotencia, la
concurrencia y la atomicidad tienen evidencia ejecutada, no razonada, y la suite completa está en
verde contra PostgreSQL real. El arranque local provisiona un tenant de verdad y la clave QAS real
carga y verifica.

No está desplegado, y eso no lo decide este trabajo:

1. **Aplicar V51 en QAS**, arrancando el backend (Flyway corre al inicio). El esquema sólo avanza
   hacia delante: tomar antes un respaldo según `docs/operations/BACKUP_AND_RESTORE.md`, incluyendo
   los roles globales.
2. **Configurar las variables** de arriba en el servicio de QAS, con la clave pública como secreto.

Hasta que MasterAdmin firme un token real contra QAS, lo verificado extremo a extremo es local. Esa
es la única afirmación que este reporte no puede hacer, y no la hace.

## Riesgo residual

| Riesgo | Nota |
|---|---|
| Ningún token firmado por el MasterAdmin real se ha verificado | Requiere la clave privada, que es suya. Lo que sí está probado es que su clave pública carga y rechaza firmas ajenas |
| V51 sin aplicar a ninguna base compartida | Sólo local y en bases de test desechables |
| Sin CI | `.github/workflows/ci.yml` se eliminó en `07a26dc`; los gates se ejecutaron localmente |
| `LINKED` no tiene test de integración | El camino existe y está razonado; el probado es `PREPROVISIONED`. Un usuario preexistente **con** membresía sí está probado: se rechaza |
| Sin pruebas de carga ni de volumen | La concurrencia se prueba con dos llamantes, no con cien |
| `deploymentMode` sólo admite `SHARED` | Por decisión: una instancia dedicada es infraestructura, no un valor de columna |
