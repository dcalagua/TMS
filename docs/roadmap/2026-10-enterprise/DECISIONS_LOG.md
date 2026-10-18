# Registro de decisiones del pack TMS enterprise (2026-10-18 → 2026-10-19)

> Las decisiones tomadas están en `DECISIONES.md` (D-01…D-09) y no se re-litigan.
> Este archivo registra **sólo** los casos en que una fase encontró que una decisión
> era imposible o rompía una regla de arquitectura, y el camino que eligió.
> Formato de cada entrada:
>
> - **Fecha/fase** · **Regla o decisión afectada** · **Motivo** · **Camino elegido**
>   (o `.blocked` si no hubo camino) · **Commit(s)**


### 2026-10-18 · fase 10 · Plan 02 §3.4 «una porción de la línea recalcula su geometría»

- **Regla/decisión afectada:** plan `02_APILABLES_ANIDABLES.md` §3.4 (división parcial recalcula).
- **Motivo:** el TMS divide un pedido por **montos tecleados** (peso, volumen, pallets; V37,
  `AssignOrderRequest`), no por cantidades de línea; no hay ledger por línea donde recalcular pilas.
  Inventarlo violaría «no se prorratea» (`SplitAssignDrawer`, V37).
- **Camino elegido:** la porción lleva su `floorAreaM2` tecleado con la misma regla que los otros tres
  (omitido = 0; el drawer precarga lo pendiente y lo topa) y el alto de pila del pedido. El
  re-apilado por vehículo sí recalcula, para asignaciones del pedido completo (ADR-022 §5).
- **Commit(s):** el de la fase 10 (diseño).

### 2026-10-18 · fase 10 · Plan 01 S-3 «abre una excepción del viaje»

- **Regla/decisión afectada:** plan `01_SINCRONIZACION_EWM_TMS.md` S-3 (EWM → TMS).
- **Motivo:** `tms.trip_exception.reported_by` es una persona NOT NULL (V27:340): una credencial no
  puede abrirla sin inventar un usuario (lo prohíbe ADR-013 §2).
- **Camino elegido:** la retención (`order_hold`, `INTEGRATION`/`WAREHOUSE_CANCELLED`, `source =
  INTEGRATION`) sobre un pedido de un viaje comprometido ya levanta el bloqueo de despacho y la alerta
  de Torre de Control `ORDER_HOLD_ON_COMMITTED_TRIP` (ADR-014). Es el equivalente existente y no
  requiere migración (ADR-025 §4).
- **Commit(s):** el de la fase 10 (diseño).

### 2026-10-18 · fase 10 · Prompt 60 «quitar la restricción >= 0 donde una NC lo exige»

- **Regla/decisión afectada:** `ck_carrier_invoice_total_nonnegative` (V46) y plan 03 F-3 «NC: monto
  negativo efectivo».
- **Motivo:** los CPE (07/08) se emiten con importes positivos; el signo lo da el tipo de documento.
- **Camino elegido:** importes positivos, `effectiveSign()` por tipo; el efecto sigue siendo negativo
  para la NC. La restricción se **mantiene** porque ninguna NC la necesita (ADR-024 parte 2 §5).
  Las penalidades sí son negativas, en la línea, sólo con `charge_code = PENALTY`.
- **Commit(s):** el de la fase 10 (diseño).

### 2026-10-18 · fase 10 · Plan 01 S-4 «`wms.tms.reconciliation.enabled`»

- **Regla/decisión afectada:** nombre de la propiedad del EWM en el plan y el prompt 35.
- **Motivo:** el prefijo real del conector es `wms.integration.tms.*` (`application.yml:356-370`,
  `TmsProperties`); y el conector es uno por tenant (regla 162: lo que varía por cliente no va al `.env`).
- **Camino elegido:** dos llaves apagadas de fábrica: `wms.integration.tms.reconciliation.enabled=false`
  (despliegue) y `intg_tms_connector.reconciliation_enabled=false` (tenant). `DESIGN.md` §4.3.
- **Commit(s):** el de la fase 10 (diseño, repo EWM).

### 2026-10-18 · fase 10 · Prompts 30/45 «`warehouse_dispatch`»

- **Regla/decisión afectada:** nombre de tabla en el plan y los prompts.
- **Motivo:** la tabla real es `tms.external_dispatch` (+ `external_dispatch_order`), V53.
- **Camino elegido:** las ADR y `PHASE_MAP.md` usan los nombres reales.
- **Commit(s):** el de la fase 10 (diseño).
