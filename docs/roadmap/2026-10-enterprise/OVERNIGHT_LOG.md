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
