# Certificación del pack TMS enterprise (fase 80, 2026-10-18, ciclo 1 de 2)

> Resultado: **todo verde**. Dos ramas locales, sin push ni merge:
> TMS `feature/tms-enterprise-overnight` (base `40de0a5`, 18 commits de las fases 00-70 + el de esta fase) y
> EWM `feature/tms-sync-overnight` (base `f1a0e48`, 5 commits, `fb76b4c`; esta fase no lo tocó).
> Ninguna base remota ni servicio desplegado fue consultado. Toda la infraestructura fue desechable y local.

## 1 · Migraciones aplicadas sin cambios

Comando de `OVERNIGHT_LOG.md` §00 contra `MIGRATION_HASHES.txt`: **diff vacío**.

| Repo | Inmutables (verificadas) | Nuevas del pack |
|---|---|---|
| TMS | V1-V58 (58 archivos) | V59-V68 (10), contiguas, sin huecos |
| EWM | V1-V55 (55) + seed local `V900` | V56-V58 (3), contiguas |

## 2 · TMS

| Comando | Resultado |
|---|---|
| `./mvnw -B clean test` (1.ª corrida, código de las fases) | 2613 corridas, **1 fallo** (el conocido de la línea base), 0 errores, 2 omitidas · 5 min 31 s |
| `./mvnw -B clean test` (con el arreglo de la prueba, §6) | **2613 corridas, 0 fallos, 0 errores, 2 omitidas · BUILD SUCCESS** · 4 min 42 s |
| `npm run typecheck` | OK (0 errores) |
| `npm run lint` | OK: 0 errores, 21 avisos (los mismos 21 de la línea base: `only-export-components`, `set-state-in-effect`) |
| `npm test` | **394/394** en 58 archivos |
| `npm run build` | OK (aviso de chunk > 500 kB, como antes) |

Contra la línea base (fase 00): backend 2324 → 2613 (+289), front 308 → 394 (+86), 46 → 58 archivos.
Omitidas por diseño (necesitan servicios reales): `SupabaseEvidenceStorageLiveTest`, `MasterAdminMailboxBridgeTest`.

## 3 · EWM

| Comando | Resultado |
|---|---|
| `./mvnw -B clean verify` | unitarias **1728/1728** · IT (Testcontainers) **1704/1704** · `ArchitectureTest` 81/81 · `SchemaIT` 16/16 · **BUILD SUCCESS** · 6 min 00 s |
| `npm run typecheck` | OK |
| `npm run test` | **1476/1476** en 125 archivos |
| `npm run build` | OK (PWA, 147 entradas precacheadas) |
| `python3 scripts/auditar-alcance-de-almacen.py --gaps` | **GAP 0** (88 controladores, 457 endpoints: almacén 317, empresa 135, plataforma 5) |

Contra la línea base: unitarias 1658 → 1728 (+70), front 1457 → 1476 (+19), `ArchitectureTest` 80 → 81.

## 4 · E2E TMS ⇄ EWM

```
cd TMS_WT && BUILD=1 EWM_ROOT=<EWM_WT> E2E_WORK=$PACK/runtime/e2e TMS_PORT=18080 EWM_PORT=18081 \
    scripts/e2e/tms-ewm/run.sh
```

`TMS_PORT`/`EWM_PORT` porque un `python -m http.server 8080` ajeno al arnés ocupaba el 8080 (no se tocó); los
valores por defecto siguen siendo 8080/8081. El arnés ya aceptaba `EWM_ROOT` y nunca exigió una rama.

| Corrida | Resultado |
|---|---|
| 1 (`run-full-1.log`) | s1-s10 y s12 PASS; **s11 FAIL** por defectos del propio escenario (§6) |
| s11 sola, entorno nuevo (`run-s11.log`) | tres intentos hasta PASS, uno por defecto |
| **2, completa (`run-full-2.log`)** | **s1-s12: 12/12 PASS** |

Lo que prueban los escenarios nuevos (corrida 2):

| | Evidencia |
|---|---|
| s10 perfil de material | Material `NEST-…` creado en el EWM en cm → `tms.material_profile` `NESTABLE`, alto 0,25 m, incremento 0,012 m, pila 50, carga 25 kg, `EWM_EBIM`, versión 0; la edición en el EWM (1,2 → 1,5 cm) llega como versión 1 (0,015 m); una línea de pedido sin medidas copia el perfil (`physical_source = PROFILE`, versión 1). Dos tramas `TMS_MATP` `SENT` |
| s11 POD | Viaje despachado por SLS; llegada y cierre de la parada; entrega `DELIVERED` en el TMS → webhook `DELIVERY_RESULT_RECORDED` 202 → `out_shipment_delivery` `DELIVERED` en el EWM. El pedido del EWM queda `COMPLETED` por su propio despacho, no por la entrega |
| s12a anulación TMS → EWM | `ORDER_CANCELLED` `PROCESSED` → alerta `TMS_TRANSPORT_CANCELLED` `OPEN`; el pedido del EWM sigue `OPEN` (D-03) |
| s12b anulación EWM → TMS | `TMS_ORDCX` `SENT` → hold bloqueante `WAREHOUSE_CANCELLED`, `source = INTEGRATION`; el pedido del TMS sigue `READY_FOR_PLANNING` (D-03) |

Evidencia en `PACK/runtime/e2e/` (`evidence-s1-…-s12.json`, `run-full-*.log`, `run-s11.log`, `tms.log`, `ewm.log`). Las
claves generadas por la corrida (`signing.pem`, `*-key`, `state.env` con el bearer) se borraron al terminar; los dos
contenedores y las dos JVM se bajaron con `run.sh down`.

## 5 · Revisión del diff de cada rama

TMS `40de0a5..HEAD`: 435 archivos, +37 045 / −502 (antes de esta fase). EWM `f1a0e48..HEAD`: 150 archivos, +9 622 / −149.

| Revisión | TMS | EWM |
|---|---|---|
| Secretos (claves privadas, JWT, `service_role`, contraseñas literales) en líneas agregadas | ninguno | ninguno |
| `console.log` / `console.debug` agregados | ninguno | ninguno |
| `TODO` / `FIXME` / `XXX` / `HACK` agregados | ninguno | ninguno |
| Rutas prohibidas (`.claude/`, `docs/quality/`, `node_modules`, `target`, `.env`) | ninguna | ninguna |
| Migraciones | V59-V68: cada tabla nueva con RLS y política por tenant (`material_profile`, `business_outbox_event`, `warehouse_order_event`, `freight_accrual`, `freight_accrual_entry`, `gl_account_mapping`, `carrier_payment`, `carrier_payment_request`, `trip_cost_allocation_run`, `trip_cost_allocation`); los únicos `INSERT` son del catálogo de permisos (`tms.permission`, `tms.role_permission`), ninguna fila de negocio | V56-V58 con bloques `DO` de autoverificación (4/3/2); V57 con `REVOKE` y política en sus tablas nuevas; ningún `INSERT` |
| Permisos nuevos de `Permission.java` sembrados | los 10: `masterdata.material_profile:read` (V59), `settlement.accrual:read|export|manage` (V65), `settlement.payment:record|confirm` y `portal.invoice:read` (V67), `settlement.cost_allocation:read|manage` (V68). `settlement.tolerance:manage` ya existía (V46) | — |
| Documentación | faltaban las filas de V64-V68 en el mapa de capacidades y ADR-022…026 en `CLAUDE.md`: **agregadas en esta fase** | `CLAUDE.md` y `CURRENT_CAPABILITIES.md` al día (fases 25-35) |

"Idempotentes" se leyó como "seguras en una base vacía y en una con datos, sin depender de filas de negocio": cada
migración nueva se aplicó desde cero en cada IT de Testcontainers y en las dos bases del E2E (Flyway completo). Las que
reescriben CHECK lo hacen con `DROP CONSTRAINT` + `ADD` **sin** `IF EXISTS` (27 en V59-V68): corren una sola vez bajo
Flyway y, si la restricción no fuera la esperada, fallan en voz alta en lugar de dejar dos versiones. No se re-ejecutan
por diseño. Ninguna se aplicó en una base compartida.

## 6 · Lo que se arregló en esta fase (pequeño y dentro del alcance)

1. **`PlatformLifecycleIntegrationTest.versionRules`**, roja desde `190245f` (línea base): defecto del arnés, no del
   receptor. El writer ahora corre en su propia transacción, como lo corre su proxy de Spring; `put` ya no envuelve el
   servicio. Sólo cambia la prueba. 46/46 en lifecycle y suspensión; suite completa verde.
2. **E2E s10** escrito (no existía: era de las fases 20/25). Usa un material propio para no darle perfil a SKU-100 y no
   cambiar la capacidad de los demás escenarios.
3. **E2E s11** (escrito en la fase 30, nunca corrido): `deliveredAt` sin serializar; la entrega se registraba sin llegar a
   la parada (el TMS responde 409, correcto); con segundos enteros la hora caía antes de la llegada del mismo segundo
   (400, correcto); la consulta leía columnas que `out_shipment_delivery` no tiene.
4. **Arnés**: `TMS_PORT`/`EWM_PORT` → `TMS_URL`/`EWM_URL` (también en `setup_link.py`); README y uso de `run.sh` al día.

## 7 · Defectos abiertos

**Ninguno rojo.** Lo que sigue son cortes declarados por las fases (detalle en `OVERNIGHT_LOG.md`, "Qué quedó fuera"),
no fallos:

| Fase | Pendiente |
|---|---|
| 40 | Front de geometría (barras de piso/alto, "Ocupa", L/A/H y factor del tipo de vehículo); re-apilado por vehículo (`OrderGeometryPort.restack`); edición de H de referencia y factor por pantalla. Fila 41 del mapa: PARTIAL |
| 45 | El plan hacia el almacén todavía no emite `orders[].geometry`; el EWM manda `loadingMeters` nulo |
| 50/55 | Selectores de transportista/viaje vacíos para quien sólo tiene permisos de liquidación |
| 60/65 | Edición de la configuración fiscal y del plazo por defecto desde "Configuración de empresa" (rigen los defaults `tms.finance.pe.*`) |
| 70 | Cotización de flota propia como fuente del costo; cajón "Reparto del viaje" en la ficha |
| 25 | Ejemplo publicado de la interfaz `materials` sin los campos nuevos; un conector guardado antes de V56 necesita volver a guardarse para tener el destino `TMS_MATERIALS` |
| todas | V59-V68 (TMS) y V56-V58 (EWM) validadas sólo con Testcontainers y el E2E local; no aplicadas en ninguna base compartida (por regla del pack) |
| — | D-01 ("TMS no paga") y D-04 (citas de andén, ADR-025 §7) siguen pendientes de confirmación humana |
