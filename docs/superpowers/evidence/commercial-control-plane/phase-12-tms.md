# CCP fase 12: TMS (receptor de entitlements de EBIM MasterAdmin)

- **Worktree:** `TMS/.worktrees/ebim-commercial-control-plane-v1`, rama `feature/ebim-commercial-control-plane-v1`, base `692ff4f`.
  - `git fetch` falla en el sandbox (SSH). Se usaron las refs en caché: `dev == origin/dev == 692ff4f`, igual que en la fase 00.
  - `.worktrees/` quedó excluido vía `.git/info/exclude`.
  - La raíz de TMS está en `feature/tms-ewm-integration-v1` y no se tocó.
- **Alcance:** sólo LOCAL.
  - Sin push: la regla de TMS exige una orden humana explícita.
  - Sin QAS y sin ninguna base compartida.
  - Las pruebas de base de datos corrieron sobre un PostgreSQL 17 + PostGIS desechable (`tms_ccp12_pg`, `127.0.0.1:55912`), que se eliminó al terminar.
- **Contrato:** FIX-ENT-v1 vendorizado en `backend/tms-api/src/test/resources/contracts/entitlements-v1/` y fijado con `FIX_ENT_V1_SHA256 = 7aab413a…65d5`. No hizo falta v1.1.
- **Supabase:** no se tocó. TMS usa Flyway (ADR-002) y `supabase/migrations` no existe (lo verifica una regla de `MigrationConventionTest`), así que el archivo de CLI/changelog no aplica.
- **Evidencia:** los logs están en `runs/`, no en `logs/`, porque el `.gitignore` de TMS ignora todo directorio `logs/`.

## Commits

| Commit | Tarea |
| --- | --- |
| `da4dcdd` | TM12-01: pin FIX-ENT-v1 + `Jcs` propio (P-04). Pasa todos los vectores y los checksums de todos los fixtures |
| `1f44050` | TM12-02: V52, receptor (servicios PUT/GET), registro vacío, manifiesto `tms.core`, FIX-ENT-v1 13/13, IT sobre PostgreSQL 17 |
| `7f14b9d` | TM12-03: rutas PUT/GET/manifest, scopes `tms:entitlements:write\|read`, errores `{error,message}` en la cadena |
| `a92e1da` | TM12-04: `CommercialAccessGate` en las dos cadenas (personas y máquinas), `commercial-access-suspended` y copy web |
| `f009380` | ADR-017, `ENTITLEMENTS.md`, puente X-07 por buzón y esta evidencia |

## Mapa (lo pedido por la fase)

- **Tenant:** `controlPlaneTenantId` → `tms.platform_provisioning_request` (V51) → organización (ADR-003). Un tenant sin esa fila responde `404 TENANT_NOT_PROVISIONED`.
- **Infraestructura comercial previa:** ninguna.
  - Provisioning registra `plan.code` y no decide nada con él (`MASTERADMIN_GENERIC_CONTRACT.md`).
  - No hay ninguna función de IA (la búsqueda de anthropic/openai/gemini/llm en backend y frontend sólo da falsos positivos, p. ej. "fu**llm**ent").
  - No hay ningún medidor.
- **RBAC:** `Permission` (autorización fina) y `Capability` (agrupación de UX). **Ninguno se convirtió en capacidad pagada:**
  - el registro vendible (`TmsCapabilityRegistry.SELLABLE`/`LIMITS`) está vacío;
  - el manifiesto declara sólo el baseline `tms.core`.
- **Medidores / emisor de uso:** ninguno aprobado ni existente, así que no se emite nada (plan §12.2: "TMS no aplica").

## Implementado

| Pedido | Dónde | Prueba |
| --- | --- | --- |
| Snapshot local durable y versionado | V52 `tms.platform_entitlement_applied` (last-good + FK a V51 y organización), `EntitlementApplyWriter` (una transacción, bloqueo consultivo), auditoría append-only | FIX-ENT-v1 13/13 contra los servicios reales; `PlatformEntitlementsIntegrationTest` 6/6 en PostgreSQL 17 |
| GET del estado aplicado | `EntitlementQueryService`; `GET …/tenants/{id}/entitlements` devuelve versión, checksum, estado y modo guardados | `PlatformEntitlementsApiTest`, X-07 pasos 3/11/12 |
| Servicio comercial por tenant | `CommercialEntitlementService` (por organización): `access`, `accessForCurrentCompany`, `capabilityEnabled`, `limit` (sólo de lo local; cierra ante un código no registrado, un alcance con companyIds explícitos y un límite ausente, que nunca significa ilimitado) | `CommercialEntitlementServiceTest` 6/6, `CommercialAccessTest` 11/11 |
| Enforcement separado del RBAC | Puerto `shared.security.CommercialAccessGate` + `CommercialAccessFilter` después de `CompanyScopeFilter` (personas) y de `IntegrationAuthenticationFilter` (credenciales de partner). Respuesta `403 commercial-access-suspended`. `/me` queda libre y el filtro falla cerrado | `CommercialAccessSecurityTest` 5/5 (ningún permiso lo levanta), `IntegrationApiTenancyTest` +2, `CommercialAccessFilterTest` 4/4 |
| `tms_app` sin acceso a las tablas | Política deny-all + REVOKE, igual que V51. `tms.commercial_access_current_company()` es SECURITY DEFINER, sin parámetro y con `search_path` fijo: devuelve sólo la organización propia | IT: 42501 en las 6 tablas; la función devuelve A para A, B para B y nada sin scope |
| Modos | `platform_entitlement_mode`, sembrado SHADOW. Un trigger impone un paso por vez (también en INSERT por tenant) y prohíbe DELETE; cada cambio queda en la historia append-only | IT `modesMoveOneStep` |
| Scopes / jti | `tms:entitlements:write\|read` en la cadena y en `@PreAuthorize`. El `jti` es de un solo uso en estas rutas; el alta (V51) queda como está | `PlatformEntitlementsApiTest` 9/9 |
| Offline | TMS no tiene ningún cliente hacia MasterAdmin: el último snapshot sigue decidiendo sin vencer | `offlineLastGood`, X-07 paso 11 |
| Provisioning intacto (INV-1) | Los cambios en `iam/provisioning` son aditivos: rutas, scopes, matchers antes de `denyAll` y la forma del error sólo en rutas de entitlements. Servicio, provisioner, repositorio, V51 y sus suites sin cambios | `git diff 692ff4f` vacío en `iam/provisioning/{application,infrastructure}`, V51 y `src/test/.../iam/provisioning`; suites protegidas verdes |

Modo al cerrar la fase: **SHADOW** (lo siembra V52).

## X-07: MasterAdmin real → TMS real (18/18)

`masteradmin/scripts/ccp/tms-x07-e2e.mts` (`runs/TM12-07-x07-e2e.txt`).
- **MasterAdmin:** `buildSnapshot` y `EntitlementSyncClient` reales; ES256 con una clave en memoria.
- **TMS:** la cadena MasterAdmin de producción, el controlador y los servicios reales dentro de su JVM (`MasterAdminMailboxBridgeTest`).
- **Transporte:** buzón de archivos.

Pasos cubiertos:
- manifiesto (sólo `tms.core`, registro vendible vacío);
- en SHADOW, APPLIED sin decidir nada;
- cohorte → PRIMARY;
- GET con la misma versión y el mismo checksum;
- acceso permitido;
- REPLAYED;
- `appActive=false` → acceso suspendido;
- STALE 409 y CONFLICT 409;
- credencial de provisioning → 403 `INSUFFICIENT_SCOPE`;
- TMS caído → RETRYABLE, el GET delata la deriva y el last-good sigue suspendiendo;
- v3 → en sincronía y acceso restablecido;
- 11 `jti` distintos.

## Gate

| Comando | Resultado |
| --- | --- |
| `./mvnw -o -B clean test` (sandbox, Mockito como `-javaagent`) | Base 1831/0/0 (399 skipped por Docker) → cierre **1904/0/0** (406 skipped). **+73** pruebas (`runs/TM12-00-baseline.txt`, `runs/TM12-0*-gate.txt`) |
| IT con PostgreSQL 17 + PostGIS (`TMS_TEST_DB_URL`, suite completa, `./mvnw -o -B clean test` sobre `f009380`) | **2103/0/0**, 1 skipped: `MasterAdminMailboxBridgeTest`, opt-in por `CCP_X07_MAILBOX`, corrido aparte en X-07 18/18 (`runs/TM12-08-full-it.txt`). La primera corrida se cortó al terminar la sesión anterior (surefire `EOFException`, sin fallos registrados); se repitió completa en la reanudación |
| `frontend/tms-web`: `tsc -b`, `vitest run`, `oxlint` | typecheck OK · 137/137 · oxlint sólo con avisos previos |
| ArchUnit (`ModuleBoundaryTest`, `LayeringTest`, `EndpointContractTest`), `MigrationConventionTest` | verdes |
| Secretos | Sin claves, JWT ni PEM en los diffs. Las pruebas firman con claves generadas en la JVM |
| INV-4 | Ningún precio, monto ni moneda en V52, en el manifiesto ni en el snapshot guardado |

## Bloqueos de entorno (registrados, no ampliados)

1. **`git fetch`** falla en el sandbox (SSH). Se usaron las refs en caché, que coinciden con la fase 00.
2. **Docker:** el socket está denegado dentro del sandbox y fuera de él está disponible.
   - Testcontainers no llegó a levantar `postgis/postgis:17-3.5` dentro de su timeout: Docker tenía 78 contenedores corriendo y ryuk tardó 64 s.
   - Se usó el mecanismo propio del repo (`TMS_TEST_DB_URL`, `ExternalTestServer`) contra un contenedor desechable levantado a mano, que se eliminó al terminar.
3. **Mockito self-attach:** bloqueado en el sandbox. Se usó `-javaagent` en `argLine`, sin tocar el `pom`, igual que en la fase 10.

## Desviaciones

1. **Numeración.** El plan decía `ADR-013` y `V52`.
   - La ADR es **017**, porque 013-016 ya existen en ramas sin fusionar (`docs/adr-013-014-warehouse-execution`, `feature/tms-ewm-integration-v1`).
   - La migración es **V52**, porque `MigrationConventionTest` exige versiones contiguas desde 1. Esas ramas también traen V52-V55, así que quien fusione segundo tendrá que renumerar. Queda como paso de integración, no hay que forzarlo acá.
2. **`appActive=false` bloquea el acceso operativo de TMS** (sólo en DUAL_READ/PRIMARY), como piden el contrato (README §2) y el plan §11.4.
   - EWM (fase 10) decidió lo contrario, por la regla "comercial ≠ acceso operativo".
   - Acá la regla se interpretó como "no es RBAC": ningún permiso lo concede ni lo levanta.
   - Queda anotado para unificar el criterio de la suite antes de PRIMARY (fase 18).
3. **SECURITY DEFINER** para que `tms_app` responda el acceso. Es la única función definer del esquema, y está justificada en V52 §6 y en ADR-017.
4. No hay materialización: TMS no tiene tabla legada que reescribir. El snapshot guardado es el estado local, y retroceder de modo no necesita restaurar nada.
5. Las IT de V52 y del repositorio JDBC se escribieron después de V52, dentro de la misma tarea. En TM12-02 el RED registrado es el de compilación del receptor.
6. `IntegrationApiTenancyTest` recibió un gate conmutable (permite por defecto) y 2 pruebas nuevas. `SchemaExposureIntegrationTest` recibió las 6 tablas nuevas en su lista. Ambos cambios son aditivos.
7. Los 503 de entitlements con la superficie apagada responden `{error:"M2M_NOT_CONFIGURED"}`. No tienen prueba propia; la cadena apagada ya la cubre `PlatformProvisioningDisabledApiTest`.

## Operador (no ejecutado)

- **Push:** requiere una orden explícita. Comando mínimo cuando la haya: `git -C <TMS> push origin feature/ebim-commercial-control-plane-v1`.
- **Tras GATE C:**
  - En TMS: `TMS_ENTITLEMENTS_ENVIRONMENT=<DEV|QAS>`, con la superficie M2M ya encendida.
  - En MasterAdmin, la integración TMS: `entitlements_path=/tenants/{controlPlaneTenantId}/entitlements`, `entitlements_manifest_path=/entitlements/manifest` y scopes `tms:entitlements:write|read`.
  - V52 se aplica sólo por Flyway al arrancar.
- **Cambio de modo:** sólo en la fase 18 y con D-14, con las sentencias de `docs/platform-provisioning/ENTITLEMENTS.md`.
