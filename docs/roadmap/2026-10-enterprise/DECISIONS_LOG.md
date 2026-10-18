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

### 2026-10-18 · fase 20 · Código del permiso `masterdata.material-profile:read`

- **Regla/decisión afectada:** ADR-022 §1, prompt de la fase 20 y `PHASE_MAP.md` (nombre del permiso).
- **Motivo:** `ck_permission_resource_shape` (catálogo de permisos) no admite guiones en el recurso, y
  `CapabilityTest.permissionCodesRoundTrip` tampoco; la migración V59 falló con el nombre del plan. La
  convención existente para recursos de dos palabras es el guion bajo (`planning.document_delivery`, V57).
- **Camino elegido:** `masterdata.material_profile:read`, con el mismo alcance y los mismos roles. Las rutas
  HTTP siguen con guion (`/material-profiles`), como las demás.
- **Commit(s):** el de la fase 20.

### 2026-10-18 · fase 20 · ADR-022 §2.3 «se completan desde el perfil cuando la línea los omite»

- **Regla/decisión afectada:** ADR-022 §2 regla 3 (peso y volumen unitarios desde el perfil).
- **Motivo:** aplicada tal cual, un pedido que hoy es válido (peso total declarado, líneas sin peso
  unitario) pasaría a ser rechazado por `OrderTotals.mismatches` en cuanto exista un perfil cuyo peso no
  coincida al 1 % con lo declarado: un perfil nuevo rompería integraciones que funcionan. Y el peso del
  perfil es por unidad: multiplicarlo por una cantidad en `KG` o `M3` inventa una cifra.
- **Camino elegido:** se completa sólo si la línea lo omite **y** el pedido no declara total de esa medida
  **y** la unidad de la línea es un conteo (`shared.geometry.UnitsOfMeasure`). Con eso ninguna carga válida
  antes de V59 deja de serlo.
- **Commit(s):** el de la fase 20.

### 2026-10-18 · fase 20 · ADR-022 §2.4 «un cambio posterior del perfil no reescribe las líneas»

- **Regla/decisión afectada:** ADR-022 §2 regla 4 frente a `TransportOrder.applyLines`, que borra y recrea
  todas las líneas en cada edición.
- **Motivo:** sin más, cualquier edición del pedido (la fecha de servicio desde el formulario, un reenvío
  del ERP con otro dato de cabecera) volvería a copiar el perfil vigente, o perdería un bloque del ERP que
  el formulario no muestra.
- **Camino elegido:** en una edición, la línea en la misma posición que sigue nombrando el mismo material
  conserva su bloque, su origen y su versión (`OrderLinePhysicalResolver`). La intake compara las líneas
  *efectivas* para responder `UNCHANGED` a un reenvío idéntico.
- **Commit(s):** el de la fase 20.

### 2026-10-18 · fase 20 · ADR-022 §2.1 «campos que hacen ganar al bloque del ERP»

- **Regla/decisión afectada:** ADR-022 §2 regla 1 enumera `lengthM, widthM, heightM, stackMode`.
- **Motivo:** con esa lista, un ERP que mandara sólo `stackMaxQuantity` o `maxTopLoadKg` vería su dato
  descartado en silencio y reemplazado por el perfil.
- **Camino elegido:** gana el bloque del ERP si nombra cualquiera de los siete campos físicos;
  `orientationLocked` solo no es un bloque. Sigue siendo «en bloque», nunca campo a campo.
- **Commit(s):** el de la fase 20.


### 2026-10-18 · fase 25 · `intg_outbound_message.warehouse_id NOT NULL` frente a una interfaz de maestro

- **Regla/decisión afectada:** EWM V34 (toda trama de salida lleva almacén; `OutboundPayload` y
  `OutboundMessage` lo exigían) frente a DESIGN §2.2 (`TMS_MATP` por el mecanismo de salida existente).
- **Motivo:** el perfil de un material es un maestro de la **empresa**; no ocurrió en ningún almacén.
  Atribuirle uno (p. ej. `intg_tms_connector.default_warehouse_id`) falsificaría el alcance que V34 vino a
  hacer confiable — el propio V34 lo dice de los mensajes de entrada de maestros.
- **Camino elegido:** V56 relaja el `NOT NULL` **sólo** para `TMS_MATP` (`ck_intgout_almacen`:
  `warehouse_id IS NOT NULL OR interface_type IN ('TMS_MATP')`); `OutboundInterfaceType.deEmpresa()` es la
  mitad en Java. El monitor de salida usa `scopeSpecOSinAlmacen()` (lo que ya hacía la entrada) y el
  tablero de inteligencia cuenta la cola de salida «de este almacén o sin almacén»
  (`IntelligenceQueriesTest` reescrito a propósito). Ninguna otra interfaz puede quedar sin almacén.
- **Commit(s):** el de la fase 25 (repo EWM).

### 2026-10-18 · fase 25 · DESIGN §2.2 «el formateador la parte en lotes de 500 (un mensaje por lote)»

- **Regla/decisión afectada:** DESIGN §2.2 (reenvío de perfiles) y la clave `ewm-matp:<eventId>:<n>`.
- **Motivo:** `OutboundInterfaceFormatter.format` devuelve **una** trama por evento; partir dentro del
  formateador exigiría cambiar el contrato de todos los formateadores y de la fábrica.
- **Camino elegido:** el reenvío escribe un evento `ITEM_PROFILES_RESEND_REQUESTED` por cada 500
  materiales (cada uno con su agregado), y cada evento produce una trama. La clave queda
  `ewm-matp:<eventId>` (más `:r<n>` en una regeneración), siempre única por lote.
- **Commit(s):** el de la fase 25 (repo EWM).

### 2026-10-18 · fase 25 · El tercer destino del conector necesita una columna

- **Regla/decisión afectada:** DESIGN §1 (contenido de V56) y §2.2 («guardar el conector crea un tercer
  destino `TMS_MATERIALS`»).
- **Motivo:** el conector guarda el id de cada destino que administra (`dispatch_destination_id`,
  `milestone_destination_id`, V50); sin columna, re-guardarlo crearía un destino duplicado.
- **Camino elegido:** V56 agrega `intg_tms_connector.material_destination_id` (anulable). Un conector
  guardado antes de V56 no tiene el destino hasta volver a guardarse; mientras tanto las tramas
  `TMS_MATP` esperan con `DESTINATION_NOT_CONFIGURED` (regla 91).
- **Commit(s):** el de la fase 25 (repo EWM).

### 2026-10-18 · fase 25 · «Unidad contable» y lo que no viaja

- **Regla/decisión afectada:** DESIGN §2.2 («`stackMaxQuantity` sólo viaja si la unidad base es una
  unidad contable») y contrato §7.1 (`nestIncrementM` obligatorio con `NESTABLE`).
- **Motivo:** `md_uom` no tiene categoría; y un `NESTABLE` cuyo incremento está en una unidad no
  convertible daría un `400` del TMS para todo el lote de uno.
- **Camino elegido:** contable = la unidad base **no admite decimales** (`md_uom.decimal_allowed =
  false`) y el número es entero. Un `NESTABLE` sin incremento convertible viaja con `stackMode: null`
  (no declarado) y el incremento se cuenta como no convertido en la referencia del documento.
- **Commit(s):** el de la fase 25 (repo EWM).
