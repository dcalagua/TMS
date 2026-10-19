# Informe de la mañana · pack TMS enterprise (2026-10-18 → 2026-10-19)

> Para Edu y Dennis. Fase 90, escrita al cierre de la noche.
>
> **Resultado: las 15 fases de trabajo terminaron DONE, ninguna BLOCKED, ninguna con pruebas rojas.**
> Todo quedó en **dos ramas locales sin push**: TMS `feature/tms-enterprise-overnight` (base `dev` `40de0a5`) y
> EWM `feature/tms-sync-overnight` (base `f1a0e48`). **Ninguna base remota ni servicio desplegado fue tocado ni
> consultado**; las migraciones nuevas sólo corrieron en Testcontainers y en el E2E local.
>
> El lunes hay demo de PLAPASA sobre `feature/plapasa-overnight`: **nada de lo de abajo la toca**. Los pasos
> humanos (§6) conviene hacerlos **después** de la demo.

Documentos de detalle, en esta carpeta: `CERTIFICATION.md` (fase 80, la evidencia de pruebas), `OVERNIGHT_LOG.md`
(qué hizo cada fase y qué quedó fuera), `DECISIONS_LOG.md` (74 entradas razonadas), `PHASE_MAP.md` (fase → clases →
migración → pruebas). Diseño: ADR-022…026 en `docs/architecture/` y en el EWM `docs/tms-sync/DESIGN.md`.

---

## 1 · Fases

| Fase | Qué | Estado | Commits TMS | Commits EWM |
|---|---|---|---|---|
| 00 | Preflight, línea base de pruebas, hashes de migraciones | DONE | `40aad53` | — |
| 05 | Mapa de capacidades, `CLAUDE.md` al día, workflow de CI (sin activar) | DONE | `a57af24` | — |
| 10 | Diseño: ADR-022…026, contrato warehouse v1.1, mapa de fases | DONE | `e2ed171` | `ba4df62` |
| 20 | Perfil físico del material en el TMS (V59) | DONE | `1b99182` | — |
| 25 | Perfil físico en el EWM + interfaz `TMS_MATP` (V56 EWM) | DONE | `da06da0` (docs) | `4f942eb` |
| 30 | El EWM usa el plan, POD de vuelta, placa del tracto, huella en el SLS (V60 TMS, V57 EWM) — 2 ciclos | DONE | `4d95697`, `5466eff`, `4486f1e`, `e191882` | `f887011`, `f8c8fa4` |
| 35 | Anulación de pedidos en las dos direcciones, reconciliación, consola (V61 TMS, V58 EWM) | DONE | `e46fefe`, `f1fc6cd` | `fb76b4c` |
| 40 | Geometría de carga: apilables y anidables en la capacidad (V62) | DONE (corte declarado: falta el front) | `bde65eb` | — |
| 45 | Tarifa por metro lineal + huella del despacho comparada (V63) | DONE | `2e12ecb` | — |
| 50 | Conciliación endurecida: códigos de cargo, tolerancias, CSV, KPIs (V64) | DONE | `882cb94` | — |
| 55 | Provisión de fletes, mapeo contable, asiento (V65) | DONE | `c0c2e44` | — |
| 60 | IGV, CPE, detracción, retención, NC/ND, penalidades (V66) | DONE (corte: edición fiscal por pantalla) | `7b46863` | — |
| 65 | Estado de pago, vencimientos, portal «Mis facturas» (V67) | DONE (corte: edición del plazo por pantalla) | `2cfb855` | — |
| 70 | Costo para servir por cliente/destino/ruta + KPIs de finanzas (V68) | DONE | `facc81c` | — |
| 80 | Certificación: suites completas + E2E TMS⇄EWM s1-s12 | DONE (todo verde) | `8db8cbe` | — |
| 90 | Este informe | DONE | (commit de esta fase) | (commit de esta fase) |

No hubo ninguna fase `BLOCKED` ni `DONE_WITH_FAILURES`. Volumen: TMS 19 commits antes de esta fase (439 archivos,
+37 344 / −514); EWM 5 commits (150 archivos, +9 622 / −149).

---

## 2 · Qué puede hacer hoy el producto que ayer no

### El caso PLAPASA, en concreto

**Vasos anidados (PLP-571).** Ayer el TMS creía que 1.000 vasos ocupaban **1,93 m³** (cantidad × volumen unitario),
porque no sabía que un vaso entra dentro del otro. Hoy:

- El almacén (EWM) declara en la ficha del material que es `NESTABLE`, con su incremento de anidado (≈1,2 cm), su
  pila máxima y la carga máxima encima. Ese perfil viaja solo al TMS (interfaz `TMS_MATP`) y el TMS lo guarda.
- Cuando entra un pedido sin medidas propias, la línea copia el perfil. Si el ERP manda medidas en la línea, ganan
  las del ERP (D-02).
- El TMS calcula la **huella real**: 1.000 vasos = **10 pilas de 1,363 m, 0,1503 m³ efectivos, 0,11 m² de piso**.
- Resultado práctico, probado: **dos pedidos de anidables que ayer pedían 2 camionetas hoy entran en 1**, en los dos
  motores de planificación.

**Gavetas apiladas.** Una gaveta `STACKABLE` se planifica por pilas: cuántas por pila, cuánto piso ocupan y qué
altura alcanza la pila más alta. La capacidad del vehículo ya no es sólo kg + m³ + pallets: son **cinco dimensiones**
(se suman piso y peso; la altura es la máxima). Si una pila no entra de alto (el PLP-1601 apilado de piso a 2,9 m en
una caja de 2,5 m), el TMS **lo rechaza nombrando la causa** (`ITEM_TALLER_THAN_VEHICLE`) en lugar de aceptarlo.
Las tarifas pueden cobrar **por metro lineal** (lo que el camión usa de largo), y el despacho real que confirma el
almacén se compara con la huella planificada como **aviso informativo**, nunca como bloqueo.

**El almacén y el transporte se hablan más.** El EWM ahora:

- ve el **plan del TMS** (peso, volumen, pallets, paradas con ventana) junto a lo que realmente cargó;
- recibe la **POD**: el resultado de cada entrega aparece en el despacho y en la línea de tiempo, y una entrega
  fallida/parcial ofrece armar la devolución (no la contabiliza sola);
- separa **tracto y remolque** (se acabó el falso `VEHICLE_MISMATCH` por la placa);
- y una **anulación de pedido cruza en las dos direcciones como alerta o retención**: nadie anula solo (D-03); decide
  una persona. Hay reconciliación periódica (apagada de fábrica) y una consola Integraciones › TMS en el EWM.

**Provisión del mes.** Ayer el TMS no provisionaba fletes. Hoy:

- cada viaje **nace provisionado al despachar** (o al entregar, si la empresa lo configura, D-07), por su costo
  estimado; cuando se cierra el costo real, la provisión se **ajusta**; cuando se aprueba la factura del
  transportista, la provisión se **consume** en la misma transacción;
- al cierre de mes hay **antigüedad de la provisión abierta**, lista de **viajes sin provisión con su motivo**, botón
  «provisionar ahora» y el **asiento contable** en JSON/CSV con las cuentas del mapeo GL de la empresa;
- todo es append-only y auditado: nada se borra, se revierte con motivo.

**Detracción y la factura peruana.** La factura del transportista ahora tiene **subtotal + IGV + total**, tipo SUNAT
(01 factura, 07 nota de crédito, 08 nota de débito), serie y correlativo con RUC validado (módulo 11), y:

- **detracción SPOT código 027 al 4 % para totales en soles por encima de S/ 400** (y retención 3 % sólo si la
  empresa es agente y no hay detracción): se calcula, se muestra antes de grabar y se exporta;
- la comparación con el costo del viaje y el consumo de la provisión van **por el subtotal** (el IGV es crédito
  fiscal), así una factura en PEN ya no sale «18 % cara»;
- NC y ND enlazadas a su factura, penalidades como ítem propio que acepta el aprobador;
- después de exportar, la cuenta por pagar tiene **vencimiento, pagos parciales, retención y estado PAGADO**, que
  informa el ERP por API o una persona con cuatro ojos. **El TMS no mueve dinero** (D-01). El transportista ve sus
  facturas, vencimientos y pagos en su portal.

**Costo para servir.** El costo de cada viaje se reparte entre sus pedidos (por peso, volumen efectivo, piso, pallets,
paradas o partes iguales; configurable por empresa) y se reporta **por cliente, destino y ruta** (costo total, por kg,
por m³, por pedido). Cada recálculo es una versión nueva. **No hay margen**: no existe tarifa de venta, y la pantalla
lo dice.

### Lo que todavía NO se ve en pantalla (cortes declarados)

- La geometría (fase 40) está completa en el backend, pero **el planificador todavía no ve barras de piso y alto** ni
  la columna «Ocupa» del pedido; el efecto (más pedidos por camión, rechazos por altura) sí se produce.
- La configuración fiscal y el plazo de pago por defecto **se leen** de la empresa pero **no se editan por pantalla**:
  rigen los valores de despliegue `tms.finance.pe.*` (IGV 18, detracción 027/4 %/S/ 400, retención 3 %, 30 días).
- La pantalla «Perfiles de material» del TMS es de sólo lectura: el perfil lo escribe el almacén (D-02).

---

## 3 · Migraciones nuevas — **NINGUNA APLICADA**

Validadas sólo con Testcontainers y en el E2E local. Ninguna siembra filas de negocio (los únicos `INSERT` del TMS
son del catálogo de permisos). Hashes de las aplicadas (TMS V1-V58, EWM V1-V55 + seed local V900) verificados
idénticos a `MIGRATION_HASHES.txt` en la fase 80.

### TMS (`backend/tms-api/src/main/resources/db/migration`) — aplicar **después de V58**

| # | Archivo | Contenido |
|---|---|---|
| V59 | `V59__material_profile_and_line_snapshot.sql` | `tms.material_profile` (copia del perfil del EWM; RLS por tenant) + columnas de snapshot físico en `transport_order_line`; scope `integration.material:write`; permiso `masterdata.material_profile:read`; auditoría |
| V60 | `V60__dispatch_confirmation_v1_1_fields.sql` | `external_dispatch.vehicle_trailer_plate`; `external_dispatch_order` pilas / volumen efectivo / metros lineales / motivo |
| V61 | `V61__order_cancellation_sync.sql` | `business_outbox_event` y `warehouse_order_event` (RLS, SELECT/INSERT); webhook `ORDER_CANCELLED`; scopes `integration.order:read` y `integration.warehouse-order-event:write` |
| V62 | `V62__load_geometry_and_capacity_floor_height.sql` | geometría por línea y pedido, `planning_volume_m3` (backfill = volumen total, NOT NULL por disparador), piso y alturas en ledger y snapshot del viaje, factor de piso en tipo de vehículo y empresa; reescribe `ck_transport_order_not_over_allocated` y `ck_trip_draft_has_no_snapshot` |
| V63 | `V63__rate_loading_meter.sql` | `rate_card.amount_per_loading_meter`; CHECKs de `trip_cost_component` con `LOADING_METERS` y motivos |
| V64 | `V64__settlement_charge_codes_and_match_basis.sql` | `carrier_invoice_line.charge_code`, `freight_match.expected_basis`; auditoría de tolerancias |
| V65 | `V65__freight_accruals_and_gl_mapping.sql` | `freight_accrual`, `freight_accrual_entry` (append-only), `gl_account_mapping`, `company_settings.accrual_trigger`; 3 permisos |
| V66 | `V66__peruvian_taxes_cpe_notes_and_penalties.sql` | columnas fiscales de la factura (desglose, tipo SUNAT, serie, correlativo, RUC, referencia, detracción, retención), `uq_carrier_invoice_cpe`, penalidades, configuración fiscal por empresa (nula = default) |
| V67 | `V67__payables_payment_status.sql` | plazos de pago, vencimiento, estados `SCHEDULED/PARTIALLY_PAID/PAID/ON_HOLD`, `carrier_payment` (append-only), `carrier_payment_request` (cuatro ojos), políticas del portal del transportista, scope `integration.payable-payment:write`, 3 permisos |
| V68 | `V68__trip_cost_allocation.sql` | `trip_cost_allocation_run` (versionada) + `trip_cost_allocation` (append-only), `company_settings.cost_allocation_basis`, 2 permisos |

Orden: **V59 → V68 en número**, de una vez (Flyway las corre en orden al arrancar). Antes, confirmar que el destino
ya tiene V58 (ver §6 paso 3: no hay documento que diga a qué versión llegó QAS).

### EWM (`backend/wms-api/src/main/resources/db/migration`) — aplicar **después de V54 y V55**

| # | Archivo | Contenido |
|---|---|---|
| V56 | `V56__item_physical_profile_and_tms_matp.sql` | `md_item.stack_mode` / `nest_increment` / `max_top_load(+uom)`; interfaz `TMS_MATP` en los CHECK; `intg_outbound_message.warehouse_id` anulable sólo para `TMS_MATP`; `intg_tms_connector.material_destination_id` |
| V57 | `V57__tms_plan_amounts_vehicle_plate_and_delivery.sql` | peso/volumen/pallets planificados por pedido, `out_shipment_load_plan_stop`, `vehicle_plate` (tracto), `out_shipment_delivery` + `_line` (proyección de la POD, no contabiliza); RLS + `REVOKE` |
| V58 | `V58__tms_order_cancellation_and_reconciliation.sql` | interfaz `TMS_ORDCX`, `ORDER_CANCELLED` en el contrato, alerta de pedido en `intg_tms_exception`, destino de eventos de pedido, `reconciliation_enabled = false` |

Orden: **V54 → V55 → V56 → V57 → V58.** V54 y V55 vienen de PLAPASA (PR #44, ya en `origin/dev`) y, según el
`CLAUDE.md` del EWM, **tampoco están aplicadas** en la base compartida: van primero, en el mismo lote o antes.

---

## 4 · Pruebas: antes y después

| Suite | Línea base (fase 00) | Al cierre (fase 80) |
|---|---|---|
| TMS backend `./mvnw -B clean test` | 2324 corridas, **1 fallo** (`PlatformLifecycleIntegrationTest.versionRules`, roja desde V58), 2 omitidas | **2613 corridas, 0 fallos, 0 errores**, 2 omitidas por diseño (+289) |
| TMS front `npm test` | 308/308 (46 archivos) | **394/394** (58 archivos) (+86) |
| TMS front typecheck / lint / build | OK / 0 errores, 21 avisos / OK | OK / 0 errores, **los mismos** 21 avisos / OK |
| EWM unitarias | 1658/1658 | **1728/1728** (+70) |
| EWM IT (Testcontainers) | — (sólo `SchemaIT` 16 y `ArchitectureTest` 80) | **1704/1704**, `ArchitectureTest` 81, `SchemaIT` 16 |
| EWM front | 1457/1457 (121 archivos) | **1476/1476** (125 archivos) |
| EWM auditoría de alcance | — | GAP 0 (88 controladores, 457 endpoints) |
| E2E TMS ⇄ EWM | 9 escenarios (s1-s9) | **s1-s12: 12/12 PASS** (s10 perfil de material, s11 POD, s12 anulación en las dos direcciones) |

La prueba roja de la línea base era un defecto del arnés de la prueba (no del receptor de ADR-021); la fase 80 la
arregló cambiando sólo la prueba. Las 2 omitidas necesitan servicios reales (`SupabaseEvidenceStorageLiveTest`,
`MasterAdminMailboxBridgeTest`). Esta fase 90 sólo escribe Markdown: no se re-corrieron suites.

---

## 5 · Decisiones

### Las del plan (`DECISIONES.md`)

| # | Decisión | Estado |
|---|---|---|
| D-01 | El TMS **no mueve dinero**: provisiona, calcula impuestos, exporta y **registra** el pago que informa el ERP o una persona con cuatro ojos | **PENDIENTE — Dennis** (implementado así; si se decide que el TMS pague, va encima sin rehacer) |
| D-02 | El EWM es dueño del perfil físico; el ERP en la línea gana para esa línea | Tomada, implementada |
| D-03 | Una señal del otro sistema nunca anula sola: alerta/retención y decide una persona | Tomada, implementada y probada en E2E (s12) |
| D-04 | Citas de muelle unificadas (el EWM dueño de sus puertas, el TMS solicita) | **PENDIENTE — Dennis**. Sólo ADR propuesta (ADR-025 §7); **nada implementado** |
| D-05 | Geometría por huella (pilas × L × A, alto de pila), no 3D | Tomada, implementada |
| D-06 | Defaults Perú configurables: IGV 18 %, detracción 027 al 4 % por encima de S/ 400, retención 3 %, 30 días | Tomada. **Verificar porcentajes con la contabilidad de PLAPASA** |
| D-07 | La provisión nace al despachar (configurable a entregar) | Tomada, implementada |
| D-08 | Costo para servir con base configurable, sin margen | Tomada, implementada (base de despliegue: peso) |
| D-09 | La rama EWM nace de `feature/plapasa-overnight` y se rebasa cuando PR #44 entre a `dev` | **PR #44 ya está en `origin/dev`** (`a69d0f9` mergea `f1a0e48`, la base exacta de la rama): **no hace falta rebasar**, el PR muestra sólo los commits del pack |

### Las que tomaron las fases (`DECISIONS_LOG.md`)

74 entradas, cada una con regla afectada, motivo y camino elegido. Ninguna rompe una regla de arquitectura;
la mayoría son de forma (p. ej. permisos con guion bajo porque el CHECK del catálogo prohíbe el guion). Las que
**conviene que Dennis lea** porque fijan comportamiento de negocio:

| Fase | Decisión | Por qué mirarla |
|---|---|---|
| 35 | El feed de pedidos para el almacén devuelve por defecto los `CANCELLED`; la primera reconciliación mira 24 h atrás | Define qué ve el EWM al encenderse |
| 40 | El re-apilado por vehículo quedó diferido: con pila más alta que la caja, el TMS rechaza (conservador) en lugar de re-apilar más bajo | Puede rechazar cargas que una persona armaría bajando la pila |
| 45 | Huella del despacho vs plan: pilas exactas, m³ y metros lineales con 5 %; sólo informa | Umbral elegido sin dato del cliente |
| 55 | La provisión mira 31 días atrás en automático; más viejo sólo con «provisionar ahora» | Ver riesgo R-1 (primer despliegue) |
| 60 | Comparación y consumo por subtotal; las notas no mueven la provisión; detracción/retención sólo en PEN; detracción **estrictamente por encima** del umbral | Criterios contables a validar con el contador |
| 65 | «Saldo» y 422 por sobrepago/moneda; el transportista ve aprobaciones, no la auditoría | Contrato con el ERP |
| 70 | «Facturado» = suma de documentos autorizados (NC resta, ND suma, penalidad cuenta); flota propia reparte su `trip_cost`, no la cotización; definiciones de los 4 KPIs de finanzas | Definiciones que verá gerencia |

### Pendientes para Dennis (resumen)

1. **D-01**: ¿confirmamos que el TMS no paga (sólo registra el pago que informa el ERP)?
2. **D-04**: ¿avanzamos con citas de muelle unificadas (EWM dueño, TMS solicita)? Cambia los dos productos.
3. **D-06**: validar con la contabilidad de PLAPASA IGV/detracción/retención/30 días y el criterio «por encima de S/ 400».
4. OK para el **push de ADR-021** (4 commits de `TMS/dev`, ver §6).
5. Si se crea **PRD** (Supabase aparte, Render, Amplify) — sigue en la Fase 0 del roadmap (`04_ROADMAP_ENTERPRISE.md`).

---

## 6 · Pasos humanos, en orden

> Nada de esto lo hizo el pack. Cada paso que toca un remoto o una base compartida necesita su propia autorización.
> Hay un hook `pre-push` instalado por el pack en los dos repos principales (`EBIM/TMS/.git/hooks/pre-push` y
> `EBIM/IACLAUDE/WMS-by-EBIM/.git/hooks/pre-push`): **quitarlo a mano** cuando se decida pushear.

1. **Revisar.** Este informe → `CERTIFICATION.md` → `DECISIONS_LOG.md` (las de la tabla de §5) → los ADR-022…026.
   Diffs: `git log --oneline 40de0a5..feature/tms-enterprise-overnight` (TMS) y
   `git log --oneline f1a0e48..feature/tms-sync-overnight` (EWM). Decidir D-01, D-04 y D-06.
2. **Push de ADR-021** (Fase 0.1 del roadmap, con OK de Dennis): `TMS/dev` local está 4 commits adelante de
   `origin/dev` (`190245f`, `468a13d`, `d66db77`, `40de0a5`: receptor `lifecycle.v1`, V58, aviso de cuenta
   suspendida). Desde `EBIM/TMS`: `git push origin dev`. Va primero porque la rama del pack nace de `40de0a5`; así el
   PR del pack muestra sólo lo suyo.
3. **Saber dónde está QAS** (Fase 0.2): versión de Flyway del TMS y del EWM en QAS (`/actuator/health` y la tabla de
   historial, en lectura), front de Amplify. `origin/qas` del TMS estaba 12 commits detrás de `dev` (le faltaba V58).
   Sin este dato no se sabe si el lote del TMS es V58-V68 o V59-V68.
4. **Push de las ramas** (sin `--force`):
   - TMS, desde el worktree: `git push -u origin feature/tms-enterprise-overnight`
   - EWM, desde su worktree: `git push -u origin feature/tms-sync-overnight`
5. **PRs a `dev`**:
   - TMS: `gh pr create --base dev --head feature/tms-enterprise-overnight` (repo `dcalagua/TMS`)
   - EWM: `gh pr create --base dev --head feature/tms-sync-overnight` (repo `dcalagua/WMS-by-EBIM`)
   - Activar el CI del TMS (Fase 0.4; `.github/workflows/ci.yml` lo dejó escrito la fase 05). Con la prueba de
     lifecycle arreglada, la primera corrida debería salir verde.
   - Orden de merge sugerido: **TMS primero**, luego EWM. Todo el contrato v1.1 es aditivo; con el TMS arriba, el
     EWM encuentra los endpoints nuevos (`material-profiles`, `warehouse-order-events`) cuando se le configure el
     destino.
6. **Aplicar migraciones con el mecanismo oficial de cada producto** (nunca SQL a mano):
   - **TMS**: Flyway al arrancar el backend (perfil `prod`, `spring.flyway.enabled: true`) en el despliegue de
     Render; aplica V59…V68 en orden. Antes, **apagar los dos barridos para el primer arranque** (riesgo R-1):
     `TMS_ACCRUAL_SWEEP_ENABLED=false` y `TMS_COST_ALLOCATION_SWEEP_ENABLED=false`.
   - **EWM**: con autorización explícita de Dennis para el lote V54-V58: `node scripts/wms.mjs dryrun <archivo>`
     por cada una (`BEGIN … ROLLBACK`), luego arrancar el backend con el perfil `supabase` y
     `ALLOW_SHARED_DB_MIGRATIONS=1` (como V32, `CLAUDE.md` del EWM).
7. **Desplegar**: backends en Render (TMS primero), fronts en Amplify.
8. **Smoke de sólo lectura** (sin crear nada):
   - TMS: `/actuator/health` UP; Flyway en 68; con un usuario de QAS, `GET /api/v1/masterdata/material-profiles`,
     `GET /api/v1/settlement/fiscal-settings` (debe devolver los defaults D-06), `GET /api/v1/settlement/payables/summary`,
     `GET /api/v1/settlement/cost-to-serve`, `GET /api/v1/reporting/kpis`; abrir «Provisiones», «Cuentas por pagar»,
     «Costo para servir», «Tolerancias», «Perfiles de material».
   - EWM: health UP; Flyway en 58; abrir Integraciones › TMS (reconciliación **apagada**); ficha de un material con los
     campos de anidado.
9. **Configurar antes de encender** (ya no es sólo lectura; con la persona de negocio):
   - EWM: **volver a guardar el conector TMS** (por API: la consola sólo tiene «Reconciliar ahora») para que tome
     los destinos nuevos (`TMS_MATERIALS`, eventos de pedido); sin destino, las interfaces nuevas esperan (regla 91).
     Para poblar el TMS con los perfiles existentes: `POST /api/v1/admin/integrations/tms/material-profiles/resend`.
   - TMS: mapeo GL, disparador de provisión y base de reparto por empresa; recién entonces volver a encender los
     barridos.

---

## 7 · Riesgos y deuda abierta

| # | Riesgo | Mitigación |
|---|---|---|
| R-1 | **Los barridos de provisión y de costo para servir vienen encendidos por defecto** (`sweep-enabled: true`). En el primer arranque sobre QAS crearían provisiones de los viajes despachados en los últimos **31 días** y repartos de los últimos **62**, con la configuración por defecto y sin mapeo GL | Primer despliegue con `TMS_ACCRUAL_SWEEP_ENABLED=false` y `TMS_COST_ALLOCATION_SWEEP_ENABLED=false`; encender después de configurar (§6 paso 9). Las filas son append-only: un error no se borra, se revierte |
| R-2 | **No se sabe a qué versión llegó Flyway en QAS** (TMS ni EWM) | §6 paso 3 antes de cualquier despliegue |
| R-3 | 27 CHECK reescritos con `DROP CONSTRAINT` **sin** `IF EXISTS` en V59-V68: si QAS tiene una restricción distinta a la del repo, la migración falla en voz alta | Es intencional (mejor fallar que dejar dos versiones); un fallo se arregla con una migración nueva, nunca editando |
| R-4 | V62 hace backfill de `planning_volume_m3` en todos los pedidos existentes (`UPDATE` + disparador) | A escala de QAS es trivial; en una base grande, desplegar fuera de hora |
| R-5 | Volumen de la revisión: +37 k líneas en el TMS, +9,6 k en el EWM, en una noche | Revisar por fase (un commit por fase), empezando por migraciones y seguridad (RLS de las 10 tablas nuevas, políticas del portal) |
| R-6 | Defaults fiscales para todo el despliegue mientras no haya edición por pantalla | Validar D-06; si una empresa necesita otro valor antes del ciclo 2, se cambia la columna de `company_settings` con una persona autorizada, o la variable de entorno para todos |
| R-7 | `CpeValidationPort` está deshabilitado: ningún CPE se valida contra SUNAT (se informa `EXTERNAL_DEPENDENCY`, nunca «válido») | Roadmap enterprise ítem 10 |

**Deuda abierta (cortes declarados, ninguno rojo):**

- Fase 40: front de geometría (barras de piso/alto, «Ocupa», L/A/H y factor del tipo de vehículo), re-apilado por
  vehículo (`OrderGeometryPort.restack`), edición de H de referencia y factor por pantalla. Fila 41 del mapa: PARTIAL.
- Fase 45: el plan hacia el almacén aún no emite `orders[].geometry`; el EWM manda `loadingMeters` nulo (no tiene
  ancho de vehículo), así que el metro lineal casi nunca se compara.
- Fases 50/55: selectores de transportista/viaje vacíos para quien sólo tiene permisos de liquidación; botones del
  cajón de factura no ocultos por capacidad (el servidor sí lo exige).
- Fases 60/65: edición de configuración fiscal y plazo por defecto en «Configuración de empresa»; registrar constancia
  de detracción; facturas offline en el portal; CSV de antigüedad/vencimientos.
- Fase 70: cotización de flota propia como fuente del costo; cajón «Reparto del viaje» en la ficha del viaje.
- Fase 25 (EWM): el ejemplo publicado de la interfaz `materials` no muestra los campos nuevos; el almacén declara el
  anidado pero todavía no lo usa para su propio apilado.
- D-04 (citas de muelle unificadas): sólo ADR propuesta.
- Documentación: el `CLAUDE.md` del TMS nombra `confirmWithReason` en `src/lib/ui.tsx`, pero la función real se llama
  `promptDialog` (DECISIONS_LOG, fase 55). Corregir en una próxima pasada de docs.
- Front del TMS: 21 avisos de lint de la línea base y el chunk > 500 kB del build, sin cambios.
