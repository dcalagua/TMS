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
