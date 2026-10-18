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
