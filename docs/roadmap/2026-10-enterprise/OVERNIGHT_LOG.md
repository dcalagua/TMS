# Bitácora del pack TMS enterprise (2026-10-18 → 2026-10-19)

> Una sección por fase, en orden. Cada fase la lee al empezar (es reanudable) y agrega la
> suya al terminar: qué entró, archivos, pruebas con números y qué quedó fuera.
> Plan: `00_ESTADO_ACTUAL.md` … `04_ROADMAP_ENTERPRISE.md` y `DECISIONES.md` (copiados de
> `PACK/plan/`). Desvíos de decisiones: `DECISIONS_LOG.md`. Hashes: `MIGRATION_HASHES.txt`.

---

## 00 · Preflight (2026-10-18, ciclo 1)

### Entorno

| Elemento | Estado |
|---|---|
| `TMS_WT` | `feature/tms-enterprise-overnight` en `40de0a5` (base), árbol limpio |
| `EWM_WT` | `feature/tms-sync-overnight` en `f1a0e48` (base), árbol limpio |
| Docker | Responde (`docker info`: server 29.7.2, 18 CPU, ~7,7 GiB) |
| Java | Temurin 21.0.12.1 en `PATH` (`JAVA_HOME` se exporta con `/usr/libexec/java_home -v 21` por comando) |
| Node / npm | v24.20.0 / 11.19.0 |
| `npm ci` | OK en `TMS_WT/frontend/tms-web` y en la raíz de `EWM_WT` (sólo avisos de `install-scripts` no aprobados: `fsevents`, y `esbuild` en el EWM; no afectan typecheck ni tests) |

### Línea base de pruebas

| Repo | Comando | Resultado |
|---|---|---|
| TMS backend | `./mvnw -B test` (Testcontainers `postgis/postgis:17-3.5`) | **2324 corridas, 1 fallo, 0 errores, 2 omitidas** · 4 min 37 s · BUILD FAILURE (ver abajo) |
| TMS front | `npm run typecheck` | OK (`tsc -b`, 0 errores) |
| TMS front | `npm run lint` | OK: 0 errores, 21 avisos (`only-export-components`, `set-state-in-effect`) |
| TMS front | `npm test` | **308/308** en 46 archivos |
| EWM backend | `./mvnw -B test` (unitarias, surefire excluye `*IT`) | **1658/1658**, 0 omitidas · BUILD SUCCESS |
| EWM backend | `./mvnw -B verify -Dtest=ArchitectureTest -Dit.test=SchemaIT` | `ArchitectureTest` **80/80** · `SchemaIT` **16/16** · BUILD SUCCESS |
| EWM front | `npm run typecheck` | OK (0 errores) |
| EWM front | `npm run test` | **1457/1457** en 121 archivos |

Omitidas en el TMS (por diseño, requieren servicios reales): `SupabaseEvidenceStorageLiveTest`,
`MasterAdminMailboxBridgeTest`.

`00_ESTADO_ACTUAL.md` §1 cita números viejos (backend 2114, front 189 en 29 archivos): los de
arriba son la línea base vigente.

### Prueba ya roja en la línea base (no es de ninguna fase del pack)

**`com.ebim.tms.iam.lifecycle.PlatformLifecycleIntegrationTest.versionRules`**: falla de forma
determinista (también aislada con `-Dtest=PlatformLifecycleIntegrationTest`: 5 corridas, 1 fallo).

```
Expecting actual:  ["APPLIED", "REPLAYED", "APPLIED"]
to contain exactly in any order:
  ["APPLIED", "REPLAYED", "VERSION_CONFLICT", "APPLIED", "STALE_LIFECYCLE"]
```

Diagnóstico (leído, sin cambiar código): es un defecto **del arnés de la prueba**, no del
receptor. La prueba envuelve `service.apply(...)` en un `TransactionTemplate` propio y crea
`LifecycleApplyWriter` con `new` (sin proxy, su `@Transactional` no actúa). Cuando el servicio
lanza `LifecycleRejection` para `VERSION_CONFLICT`/`STALE_LIFECYCLE`, el template revierte la
transacción y con ella la fila de auditoría del rechazo. En producción
`PlatformLifecycleController` y `PlatformLifecycleService.apply` no son transaccionales y el
proxy de Spring de `LifecycleApplyWriter.apply` confirma la auditoría antes de que se lance el
rechazo, así que el comportamiento documentado en ADR-021 se mantiene. Existe desde `190245f`
(V58). Arreglo propuesto (fuera del alcance de 00): no envolver en el template las llamadas que
se esperan rechazadas, o ejecutar el writer en su propia transacción en la prueba.

**Regla para las fases siguientes**: la suite completa del TMS da **1 fallo conocido** en esta
prueba. Una fase puede marcar `.done` si el único rojo es éste (decirlo con el nombre); cualquier
otro fallo es suyo.

### Hashes de migraciones

`MIGRATION_HASHES.txt`: SHA-256 de TMS V1–V58 (58 archivos), EWM V1–V55 (55 archivos) y, aparte,
el seed local del EWM `V900__local_seed_data.sql`. Sin huecos ni duplicados de versión. Siguiente
libre: TMS **V59**, EWM **V56**.

Verificación de una fase posterior (debe salir vacío):

```
diff <(grep -E '^[0-9a-f]{64}' MIGRATION_HASHES.txt) <(
  (cd $TMS_WT/backend/tms-api/src/main/resources/db/migration && shasum -a 256 $(ls V*__*.sql | sort -V | head -58))
  (cd $EWM_WT/backend/wms-api/src/main/resources/db/migration && shasum -a 256 $(ls V*__*.sql | sort -V | head -55))
  (cd $EWM_WT/backend/wms-api/src/main/resources/db/seed && shasum -a 256 V900__local_seed_data.sql))
```

### Archivos

- `docs/roadmap/2026-10-enterprise/00_ESTADO_ACTUAL.md` … `04_ROADMAP_ENTERPRISE.md`,
  `DECISIONES.md` (copia de `PACK/plan/`)
- `docs/roadmap/2026-10-enterprise/MIGRATION_HASHES.txt`
- `docs/roadmap/2026-10-enterprise/OVERNIGHT_LOG.md` (este archivo)
- `docs/roadmap/2026-10-enterprise/DECISIONS_LOG.md` (encabezado, sin entradas)

### Qué quedó fuera

- No se tocó código en ningún repo. El EWM no tiene commit de esta fase (no hubo cambios).
- La prueba roja de la línea base queda documentada, sin arreglar.

---

## 05 · Documentación al día y CI (2026-10-18, ciclo 1)

### Qué entró

1. **`docs/architecture/TMS_CURRENT_CAPABILITY_MAP.md`** reconciliado desde el código en `40aad53`
   (V1–V58), con el estilo «corrección nombrada» (`**CORRECTION (phase 05):**` en cada celda
   tocada; el texto de JOB 17 queda como registro):
   - Bloque «What phase 05 corrected» al tope; línea base nueva con los números de la fase 00
     (2324 corridas / 1 fallo conocido / 2 omitidas; front 308/308 en 46 archivos; lint 0/21) y la
     de JOB 17 conservada debajo; nota de QAS sin versión de Flyway verificada, sin PRD, sin CI.
   - *Platform truth*: 14 módulos + `shared`, 992/229 archivos Java, 61 controladores, 144
     archivos con MUI, Leaflet, tres cadenas de seguridad, filtros de suspensión y acceso comercial.
   - Fila 16 (settlement): la de JOB 17 tachada y una nueva **IMPLEMENTED (v1, gaps named)** con
     los huecos leídos del código: `tolerance_policy` sin escritor (siempre `Tolerance.NONE`), 2.ª
     línea del mismo viaje → `DUPLICATE_INVOICE`, `invoice_line_id` siempre `NULL`
     (`SettlementService:326`), CSV declarado sin adaptador, y ausentes provisión, pagos,
     IGV/detracción/CPE, notas de crédito, costo por pedido. Cuatro ojos verificado
     (`requireDifferentApprover`).
   - Correcciones en filas 8, 10, 13 (V45 cantidades entregadas, D3), 14 (Leaflet), 17 (V47, D5),
     19 (12 controladores de integración), 23 (sin KPI de settlement), 25 (`docs/operations/` sí
     existe).
   - **Filas nuevas 26–38**: guardia de enums (V44), work assignment (V47), costo de flota propia
     (V48), provisioning (V51), modo de despacho + despacho externo (V52/V53), programación/
     liberación/retenciones (V54), documentos logísticos (V55), entitlements (V56), portal del
     transportista + entrega de documentos (V57), offline + mapa de seguimiento de despachos
     (ADR-019), almacenamiento de evidencias (ADR-006/020), ciclo de vida del tenant (V58),
     mínimo privilegio de `tms_app` (V49/V50).
   - «What must not be re-created» y «Documentation corrected in phase 05» ampliados.
2. **`CLAUDE.md`**, «Deferred by decision»: la integración EWM sale de la lista (v1 mergeada
   2026-09-28, PR #13, ADR-013 + `WAREHOUSE_EXECUTION_V1.md`), y «live map tracking» pasa a «live
   fleet map tracking» con la aclaración de que el mapa Leaflet de envíos despachados (ADR-019) ya
   existe. Las ADR de las fases 10+ las agrega la fase 90.
3. **Notas fechadas al tope** (sin reescribir el cuerpo) en
   `docs/implementation/TMS_EWM_IMPLEMENTATION_REPORT.md` (mergeado; `SHIPMENT_CHANGED` y campos
   aditivos en `dev`; fase 11 entregada luego por ADR-018/019; pruebas vigentes) y
   `docs/integrations/WAREHOUSE_EXECUTION_V1.md` (§3.1 `SHIPMENT_CHANGED` existe en
   `TripService.announcePlanChange`; §3.3 los cuatro campos aditivos ya se devuelven).
4. **`.github/workflows/ci.yml`** (nuevo): job `backend` (Temurin 21, caché Maven, paso que falla
   si no hay Docker y pre-descarga `postgis/postgis:17-3.5` — sin él `DockerAvailability` omitiría
   las pruebas de base y el job saldría verde sin verificar migraciones —, `scripts/backend-test.sh`
   = `./mvnw -B test`, sube `surefire-reports` si falla) y job `frontend` (Node desde `.nvmrc`,
   `npm ci`, typecheck, lint, test, build). Disparadores: push a `dev`/`qas`/`main`, PR, manual.
   `permissions: contents: read`, sin secretos. El build de CI no publica: los gates de publicación
   (`check-frontend-env.sh`, `assert-frontend-bundle.sh`) siguen en el pipeline de despliegue.
   **No se activó** (no hay push). Validado con **actionlint 1.7.12** (con shellcheck): sin
   hallazgos. El job `frontend` se reprodujo localmente paso a paso (abajo).

### Archivos

- `docs/architecture/TMS_CURRENT_CAPABILITY_MAP.md`
- `CLAUDE.md`
- `docs/implementation/TMS_EWM_IMPLEMENTATION_REPORT.md`
- `docs/integrations/WAREHOUSE_EXECUTION_V1.md`
- `.github/workflows/ci.yml` (nuevo)
- `docs/roadmap/2026-10-enterprise/OVERNIGHT_LOG.md` (esta sección)

### Pruebas

| Repo | Comando | Resultado |
|---|---|---|
| TMS front | `npm run typecheck` | OK (0 errores) |
| TMS front | `npm run lint` | OK: 0 errores, 21 avisos (= línea base) |
| TMS front | `npm test` | **308/308** en 46 archivos |
| TMS front | `npm run build` | OK (aviso de chunk > 500 kB, preexistente) |
| TMS backend | `./mvnw -B test` | **2324 corridas, 1 fallo, 0 errores, 2 omitidas** = línea base; el único rojo es el conocido `PlatformLifecycleIntegrationTest.versionRules` (fase 00), no de esta fase |
| CI | `actionlint .github/workflows/ci.yml` | sin hallazgos |

No se tocó código Java ni TypeScript; el EWM no se tocó (sin commit EWM en esta fase).

### Qué quedó fuera

- El workflow no corrió en GitHub (prohibido el push); su primera corrida real dirá si el runner
  necesita algo más (p. ej. tiempo del job backend).
- Frontend E2E (Playwright) no entra en el CI: necesita backend y credenciales.
- La prueba roja conocida (`PlatformLifecycleIntegrationTest.versionRules`) hará fallar el job
  `backend` en su primera corrida hasta que se arregle su arnés (fuera del alcance de 05).
- `TMS_CONNECTOR_V1.md` §10 (EWM, «endpoints del TMS dan 404») no se tocó: es del repo EWM y la
  fase 05 es sólo TMS.

---

## 10 · Diseño: ADR-022…026, contrato v1.1 y mapa de fases (2026-10-18, ciclo 1)

### Qué entró

Diseño verificado contra el código (TMS `a57af24`, EWM `f1a0e48`): cuatro exploraciones de sólo
lectura sobre capacidad/geometría, liquidación/finanzas, integración del TMS y conector del EWM;
cada ADR cita clase, tabla, constraint o línea. **Sin código de producción, sin migraciones.**

1. **ADR-022** perfil físico del material y geometría de carga: `tms.material_profile` (masterdata,
   dueño EWM), snapshot en la línea (la línea del ERP gana **en bloque**), `LoadGeometryCalculator`
   puro en `shared.geometry` (PLP-571 = 10 pilas, 0,1503 m³), `planning_volume_m3` en el pedido,
   piso y alto como dimensiones 4 y 5 de `CapacityLimits`/`CapacityLoad`, resolvedor, ledger V37,
   snapshot del viaje, motores (`ITEM_TALLER_THAN_VEHICLE`, dimensión que ata), `LOADING_METER`.
2. **ADR-023** provisión (barrido idempotente, no listener; consumo en la aprobación; append-only por
   trigger + grants; mapeo contable; cierre) y estado de pago post-`EXPORTED` (ERP informa, cuatro
   ojos manual, portal «Mis facturas» con RLS restrictiva, webhooks por `business_outbox_event`).
3. **ADR-024** F-1 (tolerancias con el permiso ya sembrado, `charge_code`, duplicado por viaje+cargo,
   discrepancias por componente con `invoice_line_id`, base ACTUAL/ESTIMATED dicha, CSV, KPIs) y F-3
   (IGV, CPE 01/07/08, RUC módulo 11, `CpeValidationPort` deshabilitado, NC/ND positivas con signo por
   tipo, penalidades negativas sólo `PENALTY`, detracción por umbral, retención excluyente; defaults
   D-06 por propiedad + columna nula por empresa).
4. **ADR-025** sync v1.1 (S-1…S-4 aceptadas, **S-5 Proposed** por D-04): `DELIVERY_RESULT_RECORDED` como
   evento de contrato, `orders[].delivery` (V45) y `geometry` en el plan, remolque y geometría en el SLS,
   `ORDER_CANCELLED` TMS→EWM (+ `GET /integration/v1/orders…`), `warehouse-order-events` EWM→TMS que
   sólo **retiene** (hold `INTEGRATION`).
5. **ADR-026** costo por pedido: corridas versionadas, base configurable, sin reparto inventado, sin margen.
6. Contrato TMS `WAREHOUSE_EXECUTION_V1.md` §7 «v1.1 (additive)» con cada mensaje (endpoint, scope,
   idempotencia, JSON de ejemplo y respuestas); se corrigieron las celdas «planned» de §3.1/§3.3.
7. EWM: `docs/tms-sync/DESIGN.md` (nuevo) y `TMS_CONNECTOR_V1.md` §12 v1.1 + mapa V56–V58; corregida la
   afirmación «los endpoints del TMS dan 404» (estaba al final de §9, no en §10; nota también en §10).
8. `PHASE_MAP.md`: fase → clases → migración prevista (TMS V59–V68, EWM V56–V58) → pruebas, con la regla
   de renumeración y las dependencias.
9. `DECISIONS_LOG.md`: cinco entradas (división parcial no recalcula, excepción de viaje = hold,
   NC positivas, prefijo `wms.integration.tms.*`, `external_dispatch`).

### Hallazgos que cambian a las fases siguientes

- `settlement` **no está** en `ModuleBoundaryTest.BUSINESS_MODULES` (la regla lo saltea): la fase 50 lo agrega.
- No hay CSV, ni escritor de `tolerance_policy`, ni triggers append-only en V46 (sólo grants).
- `GET /integration/v1/shipments?updatedSince=` **ya existe**; falta el equivalente de pedidos.
- `trip_exception` exige persona; `order_hold` ya admite `INTEGRATION` en la base (falta la fábrica Java).
- `shipment_outbox_event.trip_id` es NOT NULL: los webhooks de pedido/factura necesitan otro outbox.
- `CAPACITY_MODEL.md` está desactualizado sobre asignación parcial desde V37 (lo corrige la fase 40).
- EWM: el maestro de materiales no escribe outbox; no hay tabla de alertas; `VEHICLE_MISMATCH` lo calcula sólo el TMS.

### Archivos

- TMS: `docs/architecture/ADR-022…ADR-026` (nuevos), `docs/integrations/WAREHOUSE_EXECUTION_V1.md`,
  `docs/roadmap/2026-10-enterprise/PHASE_MAP.md` (nuevo), `DECISIONS_LOG.md`, este archivo.
- EWM: `docs/tms-sync/DESIGN.md` (nuevo), `docs/integrations/TMS_CONNECTOR_V1.md`.

### Pruebas

Fase de sólo documentación; se corrieron igual las suites para dejar constancia de que nada cambió.

| Repo | Comando | Resultado |
|---|---|---|
| TMS backend | `./mvnw -B test` | **2324 corridas, 1 fallo, 0 errores, 2 omitidas** = línea base; el único rojo es el conocido `PlatformLifecycleIntegrationTest.versionRules` (fase 00), no de esta fase |
| EWM backend | `./mvnw -B test` | **1658/1658**, BUILD SUCCESS |

Front de los dos repos no se corrió: no se tocó TypeScript ni configuración (sólo Markdown).
Migraciones: ninguna creada ni tocada (hashes de `MIGRATION_HASHES.txt` intactos por construcción).
Commit EWM: `ba4df62`.

### Qué quedó fuera

- `CLAUDE.md` del TMS no lista todavía ADR-022…026 (lo hace la fase 90, como decidió la 05).
- `CLAUDE.md` del EWM: su mapa de migraciones ya omite V46–V53; no se tocó (lo actualizan las fases que
  crean V56+).
- S-5 (citas de muelle y ETA) queda sólo como propuesta hasta la decisión D-04.

---

## 20 · Perfil físico del material (2026-10-18, ciclo 1)

### Qué entró

1. **V59** `V59__material_profile_and_line_snapshot.sql`: `tms.material_profile` (único por company +
   `material_code`, CHECKs de medidas > 0, `NESTABLE` ⇔ `nest_increment_m`, `stack_max_quantity > 0`,
   `source_system` con patrón; RLS `p_tenant_company_scope`, `SELECT/INSERT/UPDATE` a `tms_app`, sin DELETE);
   10 columnas de snapshot anulables en `transport_order_line` (bloque físico + `physical_source` +
   `material_profile_version`, con CHECKs del perfil, uno de una sola columna para el vocabulario y la
   coherencia origen ⇔ bloque ⇔ versión); scope `integration.material:write`; permiso
   `masterdata.material_profile:read` (ORGANIZATION_ADMIN, COMPANY_ADMIN, PLANNER, VIEWER); acciones
   `MATERIAL_PROFILE_CREATED/UPDATED` y agregado `MATERIAL_PROFILE`. No siembra filas de negocio.
2. **API de integración** `PUT /integration/v1/material-profiles/{materialCode}` y `POST …/batch` (≤ 500):
   `IntegrationMaterialProfileController` (lee el JSON como árbol para distinguir ausente de `null`),
   `IntegrationMaterialProfileService`, puerto `shared.reference.MaterialProfileIntakePort` implementado por
   `masterdata.application.MaterialProfileIntakeService` (STALE por `sourceUpdatedAt`, merge ausente
   conserva / `null` vacía, validación del resultado, `UNCHANGED` no escribe, auditoría con la credencial).
   `Idempotency-Key` por `IntegrationRequestExecutor` (misma clave + otro cuerpo = 409).
3. **Lectura humana** `GET /api/v1/masterdata/material-profiles` (búsqueda por código o descripción,
   paginado) y `/{id}`; `MaterialProfileService`, `MaterialProfileView`.
4. **Snapshot en la línea**: `orders.application.OrderLinePhysicalResolver`, única implementación de la
   regla de copia, usada por `OrderService` (formulario y API de órdenes; la intake de integración pasa por
   ahí) y `OrderImportService` (una consulta de perfiles por archivo). El bloque del ERP gana en bloque
   (D-02); en una edición la línea conserva su bloque; si no, se copia el perfil con su versión; peso y
   volumen unitarios sólo si la línea los omite, el pedido no declara ese total y la unidad es un conteo.
   `OrderRequest.OrderLineRequest`, `OrderUpsertRequest.LineRequest` y `OrderIntakeCommand.Line` aceptan los
   8 campos físicos opcionales; `OrderDetailView.OrderLineView` los devuelve con `physicalSource` y versión.
   La intake compara las líneas *efectivas* para seguir respondiendo `UNCHANGED` a un reenvío idéntico.
5. `shared.geometry` nace con `PhysicalBlock` (reglas compartidas), `StackMode`, `UnitsOfMeasure`.
6. **Front** «Perfiles de material» (`/masters/material-profiles`), sólo lectura, MUI: lista con búsqueda y
   paginado, drawer de detalle (origen, «Actualizado en origen», «Sincronizado»), aviso de que el dueño es el
   EWM; entrada de menú bajo Maestros con permiso fino (`NavLeaf.permission` + `leafAllowed()` compartido por
   menú y buscador, porque `MASTER_DATA_VIEW` no implica este permiso); la pantalla también lo comprueba.
7. Docs: `docs/domain/MATERIAL_PROFILE_V1.md` (nuevo), contrato `WAREHOUSE_EXECUTION_V1.md` §7.1
   (implementado, semántica de conteos, ejemplo con `Idempotency-Key`, §7.1.1 campos físicos en la línea del
   pedido), fila 39 del mapa de capacidades, nota de implementación en ADR-022, `PHASE_MAP.md` (migración real),
   `DECISIONS_LOG.md` (4 entradas).

### Desvíos registrados (`DECISIONS_LOG.md`)

- Permiso `masterdata.material_profile:read` (el CHECK del catálogo no admite guion en el recurso).
- Relleno de peso/volumen unitario sólo sin total declarado y con unidad de conteo (ninguna carga válida
  antes de V59 deja de serlo).
- Conservación del bloque en ediciones (las líneas se recrean en cada edición).
- Cualquiera de los 7 campos físicos hace ganar al bloque del ERP (no sólo L/A/H/modo).

### Archivos

- Migración: `backend/tms-api/src/main/resources/db/migration/V59__material_profile_and_line_snapshot.sql`.
- Nuevos (main): `masterdata/{domain/MaterialProfile, application/MaterialProfile{Service,IntakeService,View,Filter},
  infrastructure/MaterialProfile{Repository,Specifications,LookupAdapter}, api/MaterialProfileController}`;
  `integration/{api/IntegrationMaterialProfileController, application/IntegrationMaterialProfileService,
  MaterialProfile{V1,Delivery,BatchDelivery,ResultV1,BatchResultV1}}`; `orders/{application/OrderLinePhysicalResolver,
  domain/PhysicalSource}`; `shared/geometry/{PhysicalBlock,StackMode,UnitsOfMeasure}`;
  `shared/reference/MaterialProfile{IntakePort,LookupPort,Snapshot}`.
- Modificados (main): `OrderLineInput`, `TransportOrderLine`, `OrderRequest`, `OrderDetailView`, `OrderService`,
  `OrderIntakeService`, `OrderImportService`, `OrderUpsertRequest`, `OrderIntakeCommand`, `IntegrationScope`,
  `IntegrationOperation`, `Permission`, `Capability`, `AuditAction`, `AuditAggregateType`.
- Pruebas nuevas: `MaterialProfileApiIntegrationTest` (8), `OrderLineMaterialSnapshotIntegrationTest` (5),
  `OrderLinePhysicalResolverTest` (6), `PhysicalBlockTest` (6), `MaterialProfilesPage.test.tsx` (6).
- Pruebas ajustadas (listas/conteos exactos del esquema, cableado): `SchemaExposureIntegrationTest`
  (tabla nueva, sin DELETE), `TenancyConstraintIntegrationTest` (67 permisos, 186 asignaciones),
  `CrossTenantAttackTest` (importa el resolvedor y simula el puerto de perfiles).
- Front: `shared/api/materialProfilesApi.ts`, `pages/masters/{MaterialProfilesPage,MaterialProfileDrawer}.tsx`,
  `pages/masters/materialProfiles.ts`, `lib/i18n.materialProfiles.ts`; modificados `App.tsx`,
  `app/lazyRoutes.tsx`, `shared/ui/{navConfig,AppLayout,NavSearch}.tsx`, `lib/enums.ts`, `lib/i18n.ts`.
- Docs: los del punto 7.

### Pruebas

| Repo | Comando | Resultado |
|---|---|---|
| TMS backend | `./mvnw -B test` (suite completa, Testcontainers) | **2349 corridas, 1 fallo, 0 errores, 2 omitidas** · 3 min 36 s. El único rojo es el conocido `PlatformLifecycleIntegrationTest.versionRules` (fase 00), no de esta fase. +25 pruebas nuevas respecto de la línea base (2324) |
| TMS backend | `-Dtest=` de lo nuevo y lo ajustado | `MaterialProfileApiIntegrationTest` 8/8 (CREATED/UNCHANGED, ausente conserva / `null` vacía, STALE, 400 de forma, idempotencia + 409, 403 sin scope, aislamiento de tenant en escritura y lectura, batch 207), `OrderLineMaterialSnapshotIntegrationTest` 5/5 (perfil copiado con versión, bloque del ERP gana en bloque, perfil posterior no reescribe ni en reenvío `UNCHANGED` ni en edición desde el formulario, sin relleno con total declarado ni con `KG`, 400 bloque inválido), `OrderLinePhysicalResolverTest` 6/6, `PhysicalBlockTest` 6/6, `ModuleBoundaryTest` 4/4, `AuditVocabularyMigrationTest` 2/2, `CapabilityTest` 6/6, `PersistedEnumConstraintTest`, `SchemaExposureIntegrationTest` 11/11, `TenancyConstraintIntegrationTest` 10/10, `CrossTenantAttackTest` 15/15 |
| TMS front | `npm run typecheck` | OK (0 errores) |
| TMS front | `npm run lint` | OK: 0 errores, 21 avisos (= línea base) |
| TMS front | `npm test` | **314/314** en 47 archivos (+6 en `MaterialProfilesPage.test.tsx`) |
| Migraciones | hashes V1–V58 contra `MIGRATION_HASHES.txt` | intactos |

El EWM no se tocó (sin commit EWM en esta fase).

### Qué quedó fuera

- No se calcula geometría (pilas, volumen efectivo, piso, alto): es la fase 40 (V62) y lee estas columnas.
- La plantilla de importación masiva no tiene columnas físicas: las líneas importadas sólo reciben la copia
  del perfil; la vista previa de la importación no muestra los pesos unitarios que completa el perfil.
- El formulario de pedido no edita ni muestra el bloque físico (el detalle de la API ya lo devuelve; la UI
  «Ocupa: …» es ADR-022 §10, fase 40). Una edición desde el formulario conserva el bloque por posición y material.
- Un `materialCode` con `/` no puede ir por el PUT (el servidor rechaza `%2F` en la ruta): debe ir por el
  batch. Anotado para la fase 25 (emisor EWM) y en `MATERIAL_PROFILE_V1.md` §3.
- No hay re-aplicación de perfiles a pedidos abiertos (acción explícita futura, ADR-022 §2.4).

---

## 25 · Perfil físico en el EWM + interfaz `TMS_MATP` (2026-10-18, ciclo 1)

Trabajo en `EWM_WT` (commit EWM `4f942eb`); en el TMS sólo documentación.

### Qué entró (EWM)

1. **V56** `V56__item_physical_profile_and_tms_matp.sql`: `md_item.stack_mode` (`NONE|STACKABLE|NESTABLE`,
   nulo = no declarado), `nest_increment` (en `dimension_uom_code`; `ck_mditem_nest_increment`: obligatorio
   sólo con `NESTABLE`, `> 0`, exige unidad de dimensión), `max_top_load` + `max_top_load_uom_code`
   (`ck_mditem_max_top_load`: los dos o ninguno, `> 0`). `TMS_MATP` en `ck_intgout_interfaz` y
   `ck_intgdestif_interfaz` (recreados). `intg_outbound_message.warehouse_id` anulable **sólo** para
   `TMS_MATP` (`ck_intgout_almacen`). `intg_tms_connector.material_destination_id`. Bloque `DO` de
   autoverificación (nada sembrado, ninguna trama vieja sin almacén) y cierre a PostgREST comprobado.
2. **Materiales**: `StackMode` + `ItemStacking` (dominio, mismas reglas que los CHECK), entidad, DTO
   (`ItemCreateRequest`/`ItemUpdateRequest`/`ItemResponse`), `ItemService`; formulario `MaterialsPage`
   (modo, incremento —deshabilitado si no anida y se suelta al cambiar—, carga máxima y su unidad, aviso de
   la regla 96); importador (4 columnas en la plantilla generada; vacío en UPDATE conserva; pasar a
   `STACKABLE` suelta el incremento); interfaz `materials` (ausente conserva, `null` vacía).
3. **Outbox en la misma transacción** (regla 42): `ItemPhysicalProfileEvents` escribe
   `ITEM_PHYSICAL_PROFILE_CHANGED` con la foto cuando cambia el perfil (alta, edición, importador, interfaz y
   CUBISCAN); `ITEM_PROFILES_RESEND_REQUESTED` un evento por lote de 500.
4. **`TMS_MATP`**: `OutboundInterfaceType.TMS_MATP` (JSON, `deEmpresa()`), `TmsMaterialProfileFormatter`
   (lote del contrato §7.1, todos los campos con `null` explícito, `orientationLocked` no viaja),
   `TmsUnits` (M/MTR, CM/CMT, MM/MMT, KG/KGM/KGS, G/GRM, M3/MTQ; otra unidad → `null` contado en la
   referencia), `ContractIdempotencyKey` `ewm-matp:<eventId>` (`:r<n>` en regeneración), referencia de la
   entrega con `received/applied/refused/stale`. El conector crea el tercer destino `TMS_MATERIALS`
   (`…/material-profiles/batch`, BEARER). «Reenviar perfiles al TMS»:
   `POST /api/v1/admin/integrations/tms/material-profiles/resend` (`MANAGE`). `TMS_MATP` en
   `enabled-interfaces` por defecto: sin conector el evento queda `SKIPPED`, sin destino la trama espera.
5. Monitor de salida con `scopeSpecOSinAlmacen()`; tablero de inteligencia cuenta la cola de salida «de este
   almacén o sin almacén». Front: `TMS_MATP` en el monitor y en los destinos.
6. Docs EWM: `docs/STACKING.md` §11 (qué se declara, qué todavía no se usa, `TMS_MATP`), fila en §8,
   `CURRENT_CAPABILITIES.md` (3 filas), `docs/tms-sync/DESIGN.md` §2.3 (lo que fijó el código), mapa de
   migraciones de `CLAUDE.md` (V56). Docs TMS: nota del emisor EWM en `WAREHOUSE_EXECUTION_V1.md` §7.1,
   `PHASE_MAP.md` (migración real), `DECISIONS_LOG.md` (4 entradas).

### Desvíos registrados (`DECISIONS_LOG.md`)

- Trama sin almacén para un maestro de empresa (V34 lo exigía): relajado sólo para `TMS_MATP` con CHECK.
- El reenvío parte en eventos de 500 (no dentro del formateador): una trama por evento.
- Columna `material_destination_id` para el tercer destino.
- «Unidad contable» = unidad base sin decimales; `NESTABLE` sin incremento convertible viaja sin modo.

### Archivos (EWM)

- Migración: `backend/wms-api/src/main/resources/db/migration/V56__item_physical_profile_and_tms_matp.sql`.
- Nuevos: `masterdata/item/domain/{StackMode,ItemStacking}`, `masterdata/item/application/ItemPhysicalProfileEvents`,
  `integration/tms/application/{TmsMaterialProfileFormatter,TmsUnits}`.
- Modificados: `Item`, `ItemService`, `ItemMapper`, `ItemRepository`, DTO de materiales, `ItemImporter`,
  `MaterialInboundHandler`, `OutboundInterfaceType`, `OutboundPayload`, `OutboundMessage`,
  `OutboundMonitorService`, `ContractIdempotencyKey`, `HttpOutboundDeliveryAdapter`, `TmsConnector`,
  `TmsConnectorService`, `TmsConnectorController`, `TmsConnectorResponses`, `IntelligenceQueries`,
  `ErpProfileService` (comentario), `application.yml`; front `types.ts`, `MaterialsPage.tsx`,
  `OutboundMonitorPage.tsx`, `OutboundDestinationsPage.tsx`.
- Pruebas nuevas: `ItemStackingTest` (5), `TmsMaterialProfileFormatterTest` (33, con golden
  `contracts/tms/v1_1/golden/material-profiles.expected.json` y fixtures del contrato v1.1),
  `TmsMaterialProfileIT` (14), `MaterialsPage.perfil.test.tsx` (4). Ampliadas: `MaterialInboundHandlerTest`
  (+1), `BulkImportIT` (+1 y la plantilla guarda el perfil), `TmsInboundPlanIT` (3 destinos),
  `IntelligenceQueriesTest` (reescrita: la salida también admite «sin almacén»), `TmsConnectorFixtures`
  (ruta de perfiles en el TMS de mentira).

### Pruebas

| Repo | Comando | Resultado |
|---|---|---|
| EWM backend | `./mvnw -B verify` (completo, Testcontainers) | unitarias **1697/1697** (línea base 1658, +39) · IT **1681/1681** · BUILD SUCCESS |
| EWM backend | `-Dtest=ArchitectureTest -Dit.test=SchemaIT,TmsMaterialProfileIT` sobre los archivos finales | 80/80 · 30/30 (`SchemaIT` 16 + perfil 14) |
| EWM front | `npm run typecheck` | OK |
| EWM front | `npm run test` | **1461/1461** en 122 archivos (línea base 1457) |
| EWM | `python3 scripts/auditar-alcance-de-almacen.py --gaps` | GAP 0 (86 controladores, 452 endpoints) |
| Migraciones | hashes TMS V1–V58 / EWM V1–V55 contra `MIGRATION_HASHES.txt` | intactos |

TMS: sólo Markdown (sin Java ni TypeScript), no se corrieron sus suites.

### Qué quedó fuera

- El almacén no usa el anidado ni el aplastamiento (dicho en pantalla y en `STACKING.md` §11).
- El botón «Reenviar perfiles» en la consola Integraciones › TMS llega con la consola (fase 35); la acción
  existe por API.
- Un conector guardado antes de V56 no tiene el destino `TMS_MATERIALS` hasta volver a guardarse (las tramas
  esperan, regla 91).
- No se agregaron los campos nuevos al ejemplo publicado de la interfaz `materials`
  (`InboundPayloadExamples`); el contrato los acepta.
- E2E s10 (perfil llega al TMS) es de la fase 80.

---

## 30 · Plan del TMS, POD, placa y huella en el SLS (2026-10-18, ciclo 1 de 2 · corte declarado)

Commits: TMS `4d95697` (código + contrato) y el de esta bitácora; EWM `f887011`. **Sin `.done`**: el ciclo 2
termina la lectura y las pantallas (abajo). Lo que entró está probado con las suites completas.

### Qué entró

**TMS (V60, aditivo)**
1. `V60__dispatch_confirmation_v1_1_fields.sql`: `external_dispatch.vehicle_trailer_plate`;
   `external_dispatch_order.stacks / effective_volume_m3 / loading_meters / geometry_reason` (anulables, CHECK
   de forma, sin backfill; heredan grants y RLS de V53).
2. `DispatchConfirmationV1` acepta `vehicle.trailerLicensePlate` y la geometría por pedido; se guardan y se ven en
   `GET /api/v1/planning/trips/{id}/warehouse`. `VEHICLE_MISMATCH` compara sólo el tracto. `@JsonInclude(NON_NULL)`
   mantiene la huella de un cuerpo v1 (reenvío previo a V60 = `UNCHANGED`).
3. Plan v1.1: `orders[].delivery` (resultado, bloques intentado/entregado/rechazado nulos si no se registraron,
   `lines[]` de `order_delivery_line`) en lecturas por conjunto. `DELIVERY_RESULT_RECORDED` ya salía por el mismo
   webhook firmado: probado, sin cambios.

**EWM (V57)**
1. `V57__tms_plan_amounts_vehicle_plate_and_delivery.sql`: `planned_*` en `out_shipment_load_plan_order`,
   `out_shipment_load_plan_stop`, `vehicle_plate` en carga y despacho, `out_shipment_delivery` (+ `_line`),
   `ck_tmswh_contrato` con `DELIVERY_RESULT_RECORDED`; RLS + `REVOKE`; autoverificación; en el `TRUNCATE` de
   `AbstractPostgresIT` y `AuthenticatedScopeITBase`.
2. Parser/plan: lo planificado y las paradas se guardan (informan, regla 96); placa del tracto en su campo, el
   remolque del andén no se pisa, conciliación tracto con tracto (cargas previas a V57 leídas de `trailer_number`).
3. POD: proyección en `integration.tms.delivery` (reemplaza por viaje + pedido; `DELIVERY_RESULT_RECORDED` sólo
   proyecta). **No contabiliza**: `ArchitectureTest.tmsDeliveryProjectionDoesNotPost`.
4. Huella: `StackCapacityCalculator.geometria` (puro, con anidado) calculada en `DispatchExportSource`; la
   confirmación lleva `stacks`, `effectiveVolumeM3`, `loadingMeters` (siempre `null`) y `geometryReason`.
   Golden de `TMS_DISP` movido a `v1_1/golden` y actualizado a propósito (dicho en el commit).
5. Docs EWM: mapa V57 en `CLAUDE.md`, 4 filas en `CURRENT_CAPABILITIES.md`, `DESIGN.md` §3.5. Docs TMS: notas de
   implementación y del emisor EWM en `WAREHOUSE_EXECUTION_V1.md` §7.2/§7.3, `PHASE_MAP.md`, `DECISIONS_LOG.md`
   (6 entradas).

### Desvíos registrados (`DECISIONS_LOG.md`)

- La huella se calcula en `fulfillment` (el conector no puede ver `inventory`).
- Sin alto útil ni ancho en el maestro de vehículos del EWM: sin tope por alto, `loadingMeters` siempre `null`.
- `DELIVERY_RESULT_RECORDED` no crea ni toca la carga.
- `order_delivery_line` no lo escribe ningún código del TMS: `lines` sale `[]` en la práctica; el EWM proyecta
  también desde los campos planos de v1.
- `@JsonInclude(NON_NULL)` en los campos v1.1 de la confirmación (huella de idempotencia).
- Golden de `TMS_DISP` a `contracts/tms/v1_1`.

### Pruebas

| Repo | Comando | Resultado |
|---|---|---|
| TMS backend | `./mvnw -B test` (completo, Testcontainers) | **2354 corridas, 1 fallo, 0 errores, 2 omitidas**. El fallo es el conocido de la línea base `PlatformLifecycleIntegrationTest.versionRules` (fase 00). +5 en `WarehouseExecutionApiIntegrationTest` (25/25) |
| TMS backend | `-Dtest=WarehouseExecutionApiIntegrationTest,DispatchReconcilerTest,IntegrationShipmentApiTest` | 57/57 |
| EWM backend | `./mvnw -B verify` (completo) | unitarias **1718/1718** (fase 25: 1697) · IT **1689/1689** (fase 25: 1681) · BUILD SUCCESS |
| EWM backend | nuevas | `StackGeometryTest` 13 (PLP-571 con y sin H, última pila, tope por alto, aplastamiento, no entra, sin medir), `TmsShipmentPlanParserTest` 3, `TmsDispatchGeometryTest` 4, `TmsInboundPlanIT` +6 (plan v1.1, remolque, carga vieja, POD proyecta sin contabilizar ni crear carga, corrección reemplaza, campos planos + pedido desconocido), `TmsOutboundIT` +2 (tracto/remolque/huella; perfil incompleto), `ArchitectureTest` +1 |
| EWM | `python3 scripts/auditar-alcance-de-almacen.py --gaps` | GAP 0 (86 controladores, 452 endpoints) |
| Migraciones | hashes TMS V1–V58 / EWM V1–V55 contra `MIGRATION_HASHES.txt` | intactos |

Frentes (TMS y EWM): no se tocó código de front en este ciclo; no se corrieron sus suites.

### Qué falta (ciclo 2)

- EWM: lectura de la POD por API (lectura separada del proyector) y su vista en el despacho (`SLS-…`), la etapa
  `TMS_DELIVERY` en `OutboundGraphBuilder` (una consulta por conjunto, `ProcessQueryFanOutIT`) y el enlace
  «Crear devolución» para FAILED/PARTIAL/REJECTED (abre el RMA prellenado, no lo crea).
- EWM: exponer lo planificado y las paradas en el detalle de la carga y la columna «Planificado por TMS» (`.tnum`)
  con su prueba de front; `vehiclePlate` en los tipos del front.
- TMS: tipos del front (`warehouseApi.ts`, `TripWarehouseCard`) para la placa del remolque y la geometría;
  `OUTBOUND_SHIPMENT_V1.md` §3.2 con `delivery`.
- Fuera de la fase: `orders[].geometry` del plan (fase 40), `GEOMETRY_VARIANCE` (fase 45), API de resultados por
  línea en el TMS.

## 30 · Plan del TMS, POD, placa y huella en el SLS (2026-10-18, ciclo 2 de 2 · cierre)

Commits: EWM «fase 30 ciclo 2» y TMS «fase 30 ciclo 2» (hashes en `30-ewm-plan-and-pod.done`). Continúa el
ciclo 1 sin rehacerlo: lo que faltaba era la lectura y las pantallas.

### Qué entró

**EWM (sin migración)**
1. **Lectura de la POD**, separada del proyector: `TmsDeliveryQueryService` (sólo lee) +
   `GET /api/v1/shipments/{id}/tms-deliveries` (`ShippingPolicy.READ`, guardia contra el almacén del
   despacho). `TmsDeliveryShipmentLookup` lee carga/almacén/pedidos del despacho con un `SELECT` propio
   (el paquete no puede ver `fulfillment`). Bloques no registrados viajan ausentes, nunca en cero.
2. **Línea de tiempo**: `DocumentKind.TMS_DELIVERY` y etapa `TMS_DELIVERY` al final de la salida, de una
   consulta por conjunto; `NOT_APPLICABLE` sin entregas, `DONE` con alguna; nota
   `TMS_DELIVERY_NOT_COMPLETE` con FAILED/PARTIAL/REJECTED. Front: la unión `TipoDeDocumento` y `NOMBRE_DE`
   (sin pantalla: se dibuja como texto).
3. **Pantalla del despacho**: pestaña «Entrega (TMS)» y enlace **«Crear devolución»** para
   FAILED/PARTIAL/REJECTED → devoluciones con el formulario **prellenado** (despacho y pedido originales,
   nota del TMS). No hay POST: lo guarda una persona (regla 193). Estados `DELIVERED`/`REJECTED`/
   `NOT_ATTEMPTED` en `TONO_DE_ESTADO`/`TEXTO_DE_ESTADO`.
4. **Pantalla de la carga**: `GET /api/v1/shipment-loads/{id}/external-plan` (`ExternalPlanQueryService`)
   y pestaña «Planificado por TMS» (cargas con número externo): por pedido, planificado (kg, m³, pallets)
   al lado de lo real de sus bultos activos (sólo kg/m³, lo no medido contado), y las paradas con ventana.
   Tracto y remolque visibles en carga y despacho (`transport.vehiclePlate` en los tipos del front).
5. Docs EWM: `CURRENT_CAPABILITIES.md` (2 filas pasan a ✅), `DESIGN.md` §3.6.

**TMS (sólo front y docs; backend sin cambios desde `4d95697`)**
1. `warehouseApi.ts`: `vehicleTrailerPlate` y, por pedido, `stacks`/`effectiveVolumeM3`/`loadingMeters`/
   `geometryReason` (opcionales, contrato v1.1). `TripWarehouseCard`: «Remolque» (no comparado) y la
   huella por pedido, o «Sin huella: <motivo>» (catálogo `dispatchGeometryReason` en `enums.ts`).
2. `OUTBOUND_SHIPMENT_V1.md` §3.2: el bloque `delivery` (ejemplo y reglas). `WAREHOUSE_EXECUTION_V1.md`
   §7.2: nota del consumidor EWM.
3. `DECISIONS_LOG.md` (+3: lectura de la POD sin ver el despacho, fan-out en IT propia, plan externo en
   endpoint aparte), `PHASE_MAP.md`.

### Pruebas

| Repo | Comando | Resultado |
|---|---|---|
| EWM backend | `./mvnw -B clean verify` (completo, Testcontainers) | unitarias **1720/1720** (ciclo 1: 1718) · IT **1693/1693** (ciclo 1: 1689) · BUILD SUCCESS |
| EWM backend | nuevas | `TmsDeliveryQueryServiceTest` 2 (fuera de alcance rebota antes de leer; despacho inexistente), `TmsOutboundIT.EnPantalla` 3 (plan externo planificado/real; POD en el despacho + etapa `TMS_DELIVERY` + nota, sin contabilizar ni crear RMA; 404), `TmsDeliveryStageFanOutIT` 1 (1 contra 6 entregas). `LoadingAuthorizationTest` ajustado (bean nuevo) |
| EWM frontend | `npm run typecheck` · `npm run test` | OK · **1467/1467** (123 archivos; nuevo `salidaTms.test.tsx`, 6) |
| EWM | `python3 scripts/auditar-alcance-de-almacen.py --gaps` | GAP 0 (87 controladores, 454 endpoints) |
| TMS backend | `./mvnw -B test` (completo, sin cambios de código en este ciclo) | **2354 corridas, 1 fallo, 0 errores, 2 omitidas**: el fallo es el conocido de la línea base `PlatformLifecycleIntegrationTest.versionRules` (fase 00) |
| TMS frontend | `npm run typecheck && npm run lint && npm test` | OK · lint 0 errores (avisos previos) · **315/315** (+1 en `TripWarehouseCard.test.tsx`) |
| Migraciones | ninguna tocada en este ciclo | TMS V1–V60 / EWM V1–V57 intactas |

### Qué quedó fuera (y por qué)

- `orders[].geometry` del plan (fase 40) y `GEOMETRY_VARIANCE` (fase 45): fuera de la fase por plan.
- API del TMS para registrar resultados por línea: `lines` sigue saliendo `[]` (DECISIONS_LOG ciclo 1).
- Las líneas del RMA no se prellenan: el TMS casi nunca registra líneas; las carga quien recibe.
- V57 del EWM y V60 del TMS siguen **sin aplicar** en las bases compartidas (validadas sólo con
  Testcontainers, como manda el pack).


## 35 · Anulaciones de pedido, reconciliación y consola del conector (2026-10-18, ciclo 1 de 2)

Commits: TMS `e46fefe` (código) + el de documentación de la fase; EWM `fb76b4c`. D-03 respetada en los dos lados:
ninguna señal del otro sistema anula nada.

### Qué entró

**TMS (V61 `V61__order_cancellation_sync.sql`)**
1. `tms.business_outbox_event` (outbox de hechos que no son de un viaje) y `tms.warehouse_order_event` (recepción);
   RLS `p_tenant_company_scope`, sólo `SELECT, INSERT`; índice `ix_transport_order_company_status_updated`;
   `ORDER_CANCELLED` en `ck_webhook_subscription_event_type`; scopes `integration.order:read` e
   `integration.warehouse-order-event:write`. Sin filas de negocio, sin acción de auditoría nueva (DECISIONS_LOG).
2. TMS → almacén: `OrderService.cancel` publica `ORDER_CANCELLED` en la misma transacción
   (`shared.reference.BusinessEventPublisher` → `integration.BusinessEventOutboxAdapter` → fan-out de V35; sobre
   `resource.type = "order"`, `reference` = número de pedido). Lectura `GET /integration/v1/orders/{orderNumber}` y feed
   `GET /integration/v1/orders?status=&updatedSince=` (por defecto `CANCELLED`, paginado por `updatedAt, id`).
3. Almacén → TMS: `POST /integration/v1/warehouse-order-events` (operación `WAREHOUSE_ORDER_EVENT_BATCH`,
   `Idempotency-Key`, ≤ 200). `ORDER_CANCELLED` → retención bloqueante `INTEGRATION`/`WAREHOUSE_CANCELLED`
   (`OrderHold.byIntegration`, `OrderHoldPort.placeFromIntegration`, misma fila bloqueada que la del operador, auditoría
   `ORDER_HOLD_PLACED` con `source=INTEGRATION`); `ORDER_SHORT_CLOSED` → no bloqueante `WAREHOUSE_SHORT_CLOSED` con las
   cifras. `ALREADY_HELD`, `ORDER_CLOSED`, `UNKNOWN_ORDER`, `DUPLICATE`, `INVALID` (207). En viaje comprometido: bloquea
   el despacho y levanta `ORDER_HOLD_ON_COMMITTED_TRIP` (en vez de una excepción del viaje, DECISIONS_LOG fase 10).
4. Front: la retención del almacén se ve «Puesta por integración» con su código rotulado (`holdReasonCode`).
5. E2E: `s11` (POD vuelve al EWM) y `s12` (anulación en las dos direcciones) en `scripts/e2e/tms-ewm/lib/scenarios.py`;
   la credencial del harness gana los scopes v1.1 y la suscripción los eventos v1.1 (`ensure_v11_link`). **No corridos**
   (los corre la fase 80). s10 no existe (era de 20/25; DECISIONS_LOG).
6. Docs: `WAREHOUSE_EXECUTION_V1.md` §7.4-7.5 (notas de implementación), `WEBHOOKS_V1.md`, `INBOUND_API_V1.md`,
   `TMS_CURRENT_CAPABILITY_MAP.md` fila 40, `PHASE_MAP.md`, `DECISIONS_LOG.md` (+5 TMS).

**EWM (V58 `V58__tms_order_cancellation_and_reconciliation.sql`)**
1. `ORDER_CANCELLED` del TMS → lee el pedido, una alerta `TMS_TRANSPORT_CANCELLED` en el pedido (nunca anula);
   «Anular» sólo `OPEN` sin reservas ni asignaciones activas (`/api/v1/outbound-orders/{id}/tms-alerts[/cancel-order]`,
   por `CancelOutboundOrderUseCase`), alerta en el detalle del pedido.
2. `TMS_ORDCX`: reglas A/B/F → `ORDER_CANCELLED`; E con `PARTIALLY_SHIPPED` → `ORDER_SHORT_CLOSED` con líneas; C/D/E
   rechazadas y pedidos sin clave del ERP no salen. Destino `TMS_ORDER_EVENTS`; sin destino espera (regla 91).
3. Reconciliación apagada de fábrica por dos llaves (propiedad + columna del conector), «Reconciliar ahora».
4. Consola **Integraciones › TMS** (`admin/integraciones/tms`, `INTEGRATION_VIEW`, `TruckIcon`, 5 pestañas).
5. Docs EWM: DESIGN §4.5, `TMS_CONNECTOR_V1.md` §6.1-6.2, `CURRENT_CAPABILITIES.md`, mapa V58 en `CLAUDE.md`.
   Desvíos en `DECISIONS_LOG.md` (+5 EWM).

### Pruebas

| Repo | Comando | Resultado |
|---|---|---|
| TMS backend | `./mvnw -B test` (completo, Testcontainers) | **2367 corridas, 1 fallo, 0 errores, 2 omitidas** (fase 30: 2354). El fallo es el de la línea base `PlatformLifecycleIntegrationTest.versionRules` |
| TMS backend | nuevas | `OrderCancellationSyncIntegrationTest` 9 (hold bloqueante + DUPLICATE, ALREADY_HELD + NOTED, UNKNOWN_ORDER + ORDER_CLOSED, 207, Idempotency-Key 409, tenant + 403, viaje comprometido bloquea despacho y Torre de Control, cancelación → outbox + webhook + lectura, reglas de lectura), `WarehouseOrderEventRulesTest` 3, `WebhookEventTypeTest` +1. Ajustadas: `SchemaExposureIntegrationTest` (tablas V61), `IntegrationApiTenancyTest`, `CrossTenantAttackTest` (beans nuevos). `ModuleBoundaryTest`, `AuditVocabularyMigrationTest`, `PersistedEnumConstraintTest` verdes |
| TMS frontend | `npm run typecheck && npm run lint && npm test` | OK · lint 0 errores (avisos previos) · **317/317** (+2 `enums.test.ts`) |
| EWM backend | `./mvnw -B clean verify` | unitarias **1728/1728** (fase 30: 1720) · IT **1704/1704** (1693) |
| EWM frontend | `npm run typecheck` · `npm run test` | OK · **1476/1476** (125 archivos) |
| EWM | `auditar-alcance-de-almacen.py --gaps` | GAP 0 (88 controladores, 457 endpoints) |
| Migraciones | TMS V1–V58 contra `MIGRATION_HASHES.txt` / `git diff` | intactas |

### Qué quedó fuera (ciclo 2)

- EWM: IT de un cierre corto real (regla E con despacho parcial); hoy lo cubre el golden del formateador.
- E2E s11/s12 sin ejecutar (fase 80); s10 sin escribir.
- V61 del TMS y V58 del EWM sólo validadas con Testcontainers; no aplicadas en ninguna base compartida.

---

## 40 · Geometría de carga: apilables y anidables (2026-10-18, ciclo 1 de 2 · corte declarado)

Corte del ciclo 1: **todo el backend** (calculador, V62, pedido, capacidad de cinco dimensiones, ledger, snapshot,
motores, KPIs, tipo de vehículo) con sus pruebas y documentos. Quedan para el ciclo 2 el **front** y el
**re-apilado por vehículo** (ver «Qué quedó fuera»). Nada a medias: lo que entró está probado y es coherente solo.

### Qué entró

1. **`shared.geometry.LoadGeometryCalculator`** (puro) + `LineGeometry` + `GeometryStatus`: pilas, alto de pila (última
   exacta), volumen efectivo, piso, metros lineales, giro (`fitsAcross`). `MEASURED` / `UNMEASURED` con motivo (nada se
   estima, regla 199) / `DOES_NOT_FIT` (`ITEM_TALLER_THAN_VEHICLE`, descrita como una unidad por pila). PLP-571 fijo:
   10 pilas de 1,363 m, 0,1503 m³, 0,1103 m².
2. **V62** `V62__load_geometry_and_capacity_floor_height.sql`: geometría de la línea (6 columnas + 4 CHECKs); en el pedido
   `planning_volume_m3` (backfill = total; NOT NULL con disparador `tr_transport_order_default_planning_volume`),
   `floor_area_m2`, `max_stack_height_m`, `max_unit_height_m`, `geometry_unmeasured_lines`, `geometry_reference_height_m`,
   `allocated_floor_area_m2`; `ck_transport_order_not_over_allocated` reescrito (volumen contra planificación, piso contra
   piso); ledger `assigned_floor_area_m2/assigned_stack_height_m` con su CHECK reescrito; snapshot del viaje
   `snapshot_max_floor_area_m2/_stack_height_m/_load_width_m` y `ck_trip_draft_has_no_snapshot` reescrito
   (`ck_trip_confirmed_is_complete` intacto); `vehicle_type.load_floor_fill_factor`;
   `company_settings.reference_load_height_m/load_floor_fill_factor`. Sin tablas, permisos, scopes ni filas nuevas.
3. **Pedido**: `TransportOrder.applyLines(…, H, …)` calcula cada línea y los totales de geometría;
   `LoadGeometrySettings` resuelve `H` y el factor (empresa, si no `tms.planning.geometry.*` en `application.yml`: 2,40 /
   0,85). `OrderService` (formulario, API e intake) e importación lo usan. `GET /orders/{id}` devuelve `geometry` y la
   geometría de cada línea.
4. **Capacidad**: `OrderAmounts` (+piso, +alto que toma el máximo), `PlannableOrder` (+volumen de planificación, piso,
   alturas; `totalVolumeM3` sigue ingenuo), `CapacityLoad`/`CapacityLimits` de cinco dimensiones (`accommodates`,
   `bindingDimension`, `admitsUnitHeight`), `TripCapacityView.floor/height/bindingDimension`, mensajes de rechazo con
   «floor area» y «stack height», `ITEM_TALLER_THAN_VEHICLE` al asignar/mover, `AssignOrderRequest.floorAreaM2` (omitido =
   0; el tramo lleva el alto del pedido), `Trip.confirm` congela piso/alto/ancho.
5. **Flota**: `EffectiveCapacity` y `VehicleCapacityReference` llevan L/A/H y factor resuelto (tipo → empresa →
   propiedad); `VehicleTypeRequest/View` con `loadFloorFillFactor` (0 < x ≤ 1).
6. **Motores**: `HEURISTIC_V1` y `PLANNING_V2` agregan `ITEM_TALLER_THAN_VEHICLE` y `bindingDimension` por viaje
   propuesto; `PlanningKpis` + `floorUtilizationPercent`, `heightUtilizationPercent` (V2).
7. Entregas: el cumplimiento compara sin el piso (`OrderAmounts.withoutGeometry`).
8. Docs: `docs/domain/LOAD_GEOMETRY_V1.md` (nuevo), `CAPACITY_MODEL.md` v2, notas de implementación en ADR-022, fila 41
   del mapa de capacidades (y fila 39 al día), `PHASE_MAP.md`, `DECISIONS_LOG.md` (+5).

### Desvíos registrados (`DECISIONS_LOG.md`)

- Re-apilado por vehículo diferido al ciclo 2 (rechazo conservador por altura de pila; `max_unit_height_m` nuevo).
- Volumen de planificación cuando el volumen sólo se declaró.
- `planning_volume_m3` NOT NULL con disparador en vez de DEFAULT.
- Alturas de pila `numeric(9,3)`.
- La entrega se compara sin el piso.

### Archivos

- Migración: `backend/tms-api/src/main/resources/db/migration/V62__load_geometry_and_capacity_floor_height.sql`.
- Nuevos (main): `shared/geometry/{LoadGeometryCalculator,LineGeometry,GeometryStatus}`, `shared/settings/LoadGeometrySettings`.
- Modificados (main): `orders` (`TransportOrder`, `TransportOrderLine`, `OrderService`, `OrderImportService`,
  `OrderPlanningService`, `OrderDetailView`); `planning` (`CapacityLimits`, `CapacityLoad`, `PlanningCapacityService`,
  `TripCapacityView`, `AssignOrderRequest`, `TripService`, `PlanningRunService`, `AutoPlanningService`,
  `HeuristicPlanningEngine`, `PlanningEngineV2`, `PlanningKpis`, `PlanningProposal`, `Trip`, `TripOrderAssignment`,
  `TripOrderAssignmentRepository`, `OrderFulfillmentAdapter`); `fleet` (`EffectiveCapacity`, `EffectiveCapacityResolver`,
  `VehicleLookupService`, `VehicleType`, `VehicleTypeRequest/View/Service`); `shared` (`OrderAmounts`, `PlannableOrder`,
  `VehicleCapacityReference`, `CompanySettings`); `iam/JdbcCompanySettingsAdapter`; `application.yml`.
- Pruebas nuevas: `LoadGeometryCalculatorTest` (18: tabla STACKABLE, NESTABLE PLP-571, NONE, última pila incompleta,
  aplastamiento, máximo declarado, sin H, `h > H`, 8 casos sin medir incluido uom no contable, giro bloqueado, metros
  lineales), `LoadGeometryCapacityTest` (7), `LoadGeometryPlanningEnginesTest` (6: los dos motores; anidables 2 → 1
  furgón, `ITEM_TALLER_THAN_VEHICLE`, piso que ata), `LoadGeometryMigrationIntegrationTest` (4: backfill, guard
  reescrito, disparador, CHECKs), `PlanningApiIntegrationTest` +3 (ledger + snapshot, rechazos por altura, split con
  piso), `OrderLineMaterialSnapshotIntegrationTest` +2 (PLP-571 desde el perfil; sin medir cuenta).
- Prueba ajustada: `CrossTenantAttackTest` (importa `LoadGeometrySettings` en su contexto recortado).

### Pruebas

| Repo | Comando | Resultado |
|---|---|---|
| TMS backend | `./mvnw -B test` (completo, Testcontainers) | **2407 corridas, 1 fallo, 0 errores, 2 omitidas** · 4 min 03 s (fase 35: 2367; +40). El único rojo es el conocido `PlatformLifecycleIntegrationTest.versionRules` (fase 00) |
| TMS backend | `-Dtest=` de lo nuevo y lo afectado | calculador 18/18, capacidad 7/7, motores 6/6, migración 4/4, `PlanningApiIntegrationTest` 72/72, `OrderLineMaterialSnapshotIntegrationTest` 7/7, `CrossTenantAttackTest` 15/15, `ModuleBoundaryTest` 4/4, `SchemaExposureIntegrationTest` 11/11 |
| TMS front | — | no se tocó en este ciclo |
| Migraciones | hashes V1–V58 contra `MIGRATION_HASHES.txt`; V59–V61 sin diff | intactos |

El EWM no se tocó.

### Qué quedó fuera (ciclo 2)

- **Front**: «Ocupa: N pilas · m³ efectivos · m² · alto» en el formulario de pedido; barras de piso y alto en
  `TripCard`/`TripDetailDrawer` (`CapacityBar`); `SplitAssignDrawer` con piso precargado; L/A/H y factor en
  `VehicleTypeFormDrawer`; tipos en `planningApi.ts`. La API ya devuelve todo lo necesario.
- **Re-apilado por vehículo** (`OrderGeometryPort.restack`): hoy un vehículo más bajo que las pilas del pedido se rechaza
  por altura aunque pilas más bajas entrarían. Requiere decidir qué guarda el ledger (DECISIONS_LOG).
- Edición de `reference_load_height_m`/`load_floor_fill_factor` en la pantalla de configuración de empresa (hoy por SQL
  o por la propiedad); `EligibleOrderView` sin piso pendiente.
- El plan del TMS hacia el almacén (`orders[].geometry`) y la tarifa por metro lineal son de la fase 45.

---

## 45 · Tarifa por metro lineal y geometría del despacho (2026-10-18, ciclo 1 de 2 · completa)

Todo lo pedido entró en el ciclo 1: backend, migración, front, pruebas y documentos. El ciclo 2 queda libre para lo
que se anota al final.

### Qué entró

1. **V63** `V63__rate_loading_meter.sql`: `rate_card.amount_per_loading_meter numeric(14,4)` (+ `ck_rate_card_loading_meter_nonnegative`,
   `ck_rate_card_has_a_component` reescrito); `trip_cost_component`: `ck_…_component` (+`LOADING_METERS`), `ck_…_unit`
   (+`LOADING_METER`), `ck_…_quantity_source` (+`EFFECTIVE_GEOMETRY`), `ck_…_reason` (+`FLOOR_AREA_UNKNOWN`,
   `LOAD_WIDTH_UNKNOWN`), `ck_…_shape` reescritos. Sin tablas, permisos, scopes ni filas nuevas.
2. **Motor de tarifas**: `RateComponent.LOADING_METERS` (linehaul, entre `PALLETS` y `STOP_OFF`; el recargo de
   combustible lo incluye), `CostUnit.LOADING_METER`, `CostQuantitySource.EFFECTIVE_GEOMETRY`, dos motivos nuevos.
   `CostInputs` (+`loadingMeters`, `loadingMetersReason`, `volumeSource`, `missingReasonFor`): metros lineales =
   `SUM(assigned_floor_area_m2) / (ancho × factor)` con `LoadGeometryCalculator.loadingMetres`; `VOLUME` dice
   `EFFECTIVE_GEOMETRY` cuando el ledger tiene piso, si no `ORDER_DECLARED_TOTALS`. `RateCard`/`RateComponents`/
   `RateCardRequest`/`RateCardView`/`RateCardService` con `amountPerLoadingMeter` (constructores previos conservados).
3. **Costeo**: `CostableTrip` (+piso, ancho, factor; `withCarrier`), armado por `TripCostingLookupAdapter` (ancho del
   snapshot del viaje, si no del tipo; factor del resolvedor de flota) y por `ProposalPricer` (que ahora cotiza con el
   volumen de planificación y el piso pedido).
4. **Conciliación del despacho** (ADR-022 §9): `DispatchReconciler.Code.GEOMETRY_VARIANCE` (`INFO`) y `PlannedGeometry`;
   `ExternalDispatchService.plannedGeometry` (pilas y volumen efectivo = suma de líneas si el pedido va completo y todas
   sus líneas están medidas; metros lineales del piso de la asignación). Pilas exactas, volumen y metros con 5 %. Nunca
   `MISMATCH`, nunca 4xx, no cambia el `matchResult`. `OrderLineSnapshot` (+`stacks`, `effectiveVolumeM3`).
5. **Front**: `RateCardFormDrawer` «Por metro lineal»; `ratesApi.ts` (componente, unidad, fuente y motivos — incluidos
   `STOPS_UNKNOWN`/`WAITING_NOT_RECORDED`, que faltaban); etiquetas en `enums.ts`; `warehouseDispatch.geometryVariances` y
   chip «Huella distinta a la planificada (informativa)» por pedido en `TripWarehouseCard`.
6. **Docs**: `RATE_ENGINE_V2.md` §4.1-4.2, `WAREHOUSE_EXECUTION_V1.md` §7.3 «Compared (phase 45)» con ejemplo, notas de
   implementación en ADR-022, fila 42 de `TMS_CURRENT_CAPABILITY_MAP.md`, `PHASE_MAP.md`, `DECISIONS_LOG.md` (+5).

### Desvíos registrados (`DECISIONS_LOG.md`)

- Dos motivos para un metro lineal no calculable (`FLOOR_AREA_UNKNOWN`, `LOAD_WIDTH_UNKNOWN`).
- Ancho del snapshot (si no, del tipo); el factor no se congela.
- `EFFECTIVE_GEOMETRY` cuando el ledger tiene piso.
- La propuesta se cotiza con el volumen de planificación.
- Qué huella se compara y con qué tolerancia.

### Archivos

- Migración: `backend/tms-api/src/main/resources/db/migration/V63__rate_loading_meter.sql`.
- Modificados (main): `rates/domain` (`CostUnit`, `RateComponent`, `CostQuantitySource`, `CostComponentReason`,
  `CostInputs`, `CostLine`, `TripCostCalculator`, `RateCard`, `RateComponents`), `rates/application` (`RateCardRequest`,
  `RateCardService`, `RateCardView`, `CarrierQuotationService`), `shared/reference` (`CostableTrip`, `OrderLineSnapshot`,
  `DispatchConfirmationCommand` doc), `planning` (`DispatchReconciler`, `ExternalDispatchService`, `ProposalPricer`,
  `TripCostingLookupAdapter`, `ExternalDispatchOrder` doc), `orders/infrastructure/OrderReferenceAdapter`.
- Pruebas nuevas: `LoadingMeterRateCalculatorTest` (7), `DispatchGeometryReconcilerTest` (6);
  `WarehouseExecutionApiIntegrationTest` +3; `TripWarehouseCard.test.tsx` +1.
- Front: `ratesApi.ts`, `warehouseApi.ts` (doc), `enums.ts`, `i18n.ts`, `RateCardFormDrawer.tsx`, `warehouseDispatch.ts`,
  `TripWarehouseCard.tsx`.

### Pruebas

| Repo | Comando | Resultado |
|---|---|---|
| TMS backend | `./mvnw -B test` (completo, Testcontainers) | **2423 corridas, 1 fallo, 0 errores, 2 omitidas** · 4 min 06 s (fase 40: 2407; +16). El único rojo es el conocido `PlatformLifecycleIntegrationTest.versionRules` (fase 00) |
| TMS backend | nuevas y afectadas | `LoadingMeterRateCalculatorTest` 7/7 (metro lineal, sin ancho, sin piso, VOLUME efectivo vs declarado, linehaul + combustible, tarjeta sólo LDM, tarjeta muda); `DispatchGeometryReconcilerTest` 6/6 (iguales, tres INFO y MATCHED, tolerancia, nada que comparar, junto a un ERROR sigue MISMATCH, huella planificada); `WarehouseExecutionApiIntegrationTest` 28/28 (+3: varianza INFO en `dispatch-confirmations` y en `/warehouse`, cuerpo v1 contra plan medido sin discrepancias, tarifa por metro lineal + `NOT_CALCULABLE FLOOR_AREA_UNKNOWN` persistidos con los CHECK de V63); `PersistedEnumConstraintTest`, `ModuleBoundaryTest`, `DispatchReconcilerTest`, `TripCostCalculatorTest`, `RateEngineV2CalculatorTest`, `RateCardServiceTest`, `TripCostServiceTest` verdes |
| TMS frontend | `npm run typecheck && npm run lint && npm test` | OK · lint 0 errores (21 avisos previos) · **318/318** (fase 35: 317; +1) |
| Migraciones | hashes V1–V58 contra `MIGRATION_HASHES.txt`; V59–V62 sin diff | intactos |

El EWM no se tocó.

### Qué quedó fuera (ciclo 2 / otras fases)

- El bloque `orders[].geometry` del plan hacia el almacén (contrato §7.2) sigue sin emitirse: la fase 40 lo dejó para la 45
  pero el prompt de la 45 no lo pide; es aditivo y pequeño (`ShipmentPlanOrderV1`), candidato del ciclo 2.
- El EWM manda `loadingMeters` siempre `null` (no tiene medidas de vehículo), así que en la práctica sólo se comparan pilas
  y volumen efectivo hasta que lo calcule.
- El factor de piso no se congela en el viaje (DECISIONS_LOG); el front de la fase 40 (barras de piso/alto) sigue pendiente
  de su ciclo 2.
- V63 sólo validada con Testcontainers; no aplicada en ninguna base compartida.
