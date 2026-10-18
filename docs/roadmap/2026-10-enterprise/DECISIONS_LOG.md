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

### 2026-10-18 · fase 30 · La huella del SLS no la puede calcular el conector

- **Regla/decisión afectada:** DESIGN §3.4 («`TmsDispatchConfirmationFormatter` agrega por pedido…» con
  `StackCapacityCalculator`) frente a `ArchitectureTest.tmsConnectorUsesTheWriters` (EWM): `integration.tms`
  no puede depender de `inventory..`, donde vive el calculador.
- **Motivo:** mover el calculador o relajar la regla rompería una frontera del EWM para una conveniencia.
- **Camino elegido:** la calcula `fulfillment.shipping.DispatchExportSource` (que sí puede ver `inventory`)
  por material del pedido, en la unidad de las dimensiones, y la deja en la foto (`DispatchSnapshot.
  MaterialGeometry`); el formateador sólo convierte a m³ con `TmsUnits` y suma. Una sola implementación de
  las fórmulas (regla 201).
- **Commit(s):** el de la fase 30 (repo EWM).

### 2026-10-18 · fase 30 · `loadingMeters` y el alto útil en el EWM

- **Regla/decisión afectada:** DESIGN §3.4 («el alto útil: el del tipo de vehículo del maestro si existe»;
  `loadingMeters` «con el ancho del vehículo si se conoce») y contrato §7.3.
- **Motivo:** `md_vehicle` (EWM V28) no tiene largo, ancho ni alto; `vehicle_type` es texto. No hay de dónde
  sacar ni H ni el ancho sin inventarlo (regla 199).
- **Camino elegido:** sin tope por alto (`k` sale del máximo por pila o del aplastamiento) y `loadingMeters`
  **siempre `null`** desde el EWM, dicho en el contrato §7.3. El TMS tiene el ancho de su vehículo y puede
  derivarlo (fase 45). Agregar medidas al maestro de vehículos del EWM es otra fase.
- **Commit(s):** el de la fase 30 (repo EWM).

### 2026-10-18 · fase 30 · `DELIVERY_RESULT_RECORDED` no toca la carga

- **Regla/decisión afectada:** `TmsPlanApplier` (EWM) aplica la carga para todo viaje comprometido, incluso
  `IN_TRANSIT`/`COMPLETED` (crea la carga si no existe).
- **Motivo:** un aviso de entrega de un viaje ya entregado crearía una carga para el andén de mercadería que
  ya está en el cliente.
- **Camino elegido:** ese aviso **sólo proyecta** la POD (y guarda la foto del plan). Toda otra lectura del
  plan también proyecta las entregas que traiga, así que un aviso perdido se repara con el siguiente.
- **Commit(s):** el de la fase 30 (repo EWM).

### 2026-10-18 · fase 30 · El plan sin bloque `delivery` y las líneas de entrega del TMS

- **Regla/decisión afectada:** contrato §7.2 (`delivery.lines[]` desde `order_delivery_line`, V45).
- **Motivo:** ningún código del TMS escribe `order_delivery_line` todavía (sólo existe la tabla): `lines`
  sale `[]` en la práctica. Y un TMS anterior a V60 no manda el bloque `delivery`.
- **Camino elegido:** el TMS lo publica igual (correcto cuando existan filas); el EWM proyecta el bloque y,
  si falta, los campos planos de v1 (`deliveryResult`, `deliveryNotes`, …) sin bloques ni líneas. Una API
  del TMS que registre resultados por línea queda fuera de esta fase.
- **Commit(s):** los de la fase 30 (TMS y EWM).

### 2026-10-18 · fase 30 · La huella del cuerpo v1 de `dispatch-confirmations`

- **Regla/decisión afectada:** «aditivo» en §7.3 frente a `PayloadHash` (TMS), que se calcula sobre el
  registro re-serializado.
- **Motivo:** campos nuevos con `null` cambiaban la huella de un cuerpo v1: un reenvío de un documento
  guardado antes de V60 habría dado `409`.
- **Camino elegido:** los campos nuevos llevan `@JsonInclude(NON_NULL)`; la prueba `dispatchV1BodyUnchanged`
  lo fija (sin la anotación falla).
- **Commit(s):** el de la fase 30 (repo TMS, `4d95697`).

### 2026-10-18 · fase 30 · Golden de `TMS_DISP` a `v1_1`

- **Regla/decisión afectada:** fixtures del contrato «copias textuales; no se editan acá» (EWM
  `contracts/tms/v1_1/README.md`).
- **Motivo:** el golden de la confirmación tenía que cambiar a propósito (campos v1.1) y el ejemplo v1 del
  contrato no los tiene.
- **Camino elegido:** `v1_1/dispatch-confirmation.json` = ejemplo §4.1 + campos §7.3 del contrato;
  `v1_1/golden/dispatch-confirmation.expected.json` reemplaza al de `v1/golden` (movido con `git mv`).
  `v1/dispatch-confirmation.json` queda igual (sigue siendo el ejemplo v1).
- **Commit(s):** el de la fase 30 (repo EWM).


### 2026-10-18 · fase 30 (ciclo 2) · La lectura de la POD no puede ver el despacho

- **Regla/decisión afectada:** `ArchitectureTest.tmsDeliveryProjectionDoesNotPost` (EWM): el paquete
  `integration.tms.delivery` no puede depender de `fulfillment`; y el checklist del EWM exige
  `WarehouseAccess.require` contra el almacén del agregado.
- **Motivo:** para leer la POD «del despacho» hace falta la carga, el almacén y los pedidos del despacho,
  que viven en `fulfillment`; y `fulfillment` no puede conocer al conector TMS (`tmsConnectorUsesTheWriters`).
- **Camino elegido:** `TmsDeliveryShipmentLookup` lee esos tres datos con un `SELECT` escrito a mano sobre
  `out_shipment`/`out_shipment_line` (el mismo patrón que la línea de tiempo), sin repositorio ajeno. El
  endpoint vive en el paquete de la POD (`GET /api/v1/shipments/{id}/tms-deliveries`) y el guardia va contra
  el almacén del despacho. Una prueba unitaria fija que el rechazo llega antes de leer una entrega.
- **Commit(s):** el de la fase 30 ciclo 2 (repo EWM).

### 2026-10-18 · fase 30 (ciclo 2) · El fan-out de la etapa `TMS_DELIVERY` se mide en su propia IT

- **Regla/decisión afectada:** checklist EWM «si agregaste una etapa al proceso: lo mide
  `ProcessQueryFanOutIT` comparando 1 dato contra 6».
- **Motivo:** `ProcessQueryFanOutIT` extiende `InboundFixtures` y sólo arma la cadena de entrada; la etapa
  nueva es de la salida.
- **Camino elegido:** `TmsDeliveryStageFanOutIT` (mismo paquete, mismo `QueryCounter`, mismo criterio: un
  pedido con 1 entrega contra uno con 6, mismas sentencias). `ProcessQueryFanOutIT` sigue verde.
- **Commit(s):** el de la fase 30 ciclo 2 (repo EWM).

### 2026-10-18 · fase 30 (ciclo 2) · «Planificado por TMS» en un endpoint aparte, no en el detalle de la carga

- **Regla/decisión afectada:** plan de la fase («la pantalla de la carga muestra Planificado por TMS al lado
  de lo real»).
- **Motivo:** el detalle de la carga (`LoadDetail`) es la respuesta de cada escaneo del andén (lote 05 lo
  midió); sumarle el plan serían tres consultas más por bulto subido, para un dato que un escaneo no cambia.
- **Camino elegido:** `GET /api/v1/shipment-loads/{id}/external-plan` (módulo de carga, nombre neutro: la
  carga no sabe quién la planificó) y una pestaña «Planificado por TMS» que lo pide al abrirse, sólo en
  cargas con número externo. Lo real sale de los bultos activos del pedido, sólo kg y m³ (sinónimos de
  código, no conversión); lo demás se cuenta (reglas 77/199).
- **Commit(s):** el de la fase 30 ciclo 2 (repo EWM).


### 2026-10-18 · fase 35 · `cancelledAt` sin columna en `transport_order`

- **Regla/decisión afectada:** contrato §7.4 (`GET /integration/v1/orders/{orderNumber}` devuelve `cancelledAt`).
- **Motivo:** `tms.transport_order` no tiene fecha de anulación (V10 guarda `cancel_reason` y nada más), y
  agregarla exigía un backfill de las anuladas desde `updated_at`, que es el mismo dato.
- **Camino elegido:** `cancelledAt` = `updated_at` cuando el estado es `CANCELLED` (una orden anulada no se
  edita, ni se planifica, ni pasa a otro estado: nada toca la fila después), `null` en cualquier otro estado.
  Dicho en el contrato §7.4 (notas de implementación). Si algún día una anulada se pudiera tocar, toca
  agregar la columna.
- **Commit(s):** el de la fase 35 (TMS).

### 2026-10-18 · fase 35 · `warehouse_order_event` sin `UPDATE(outcome)`

- **Regla/decisión afectada:** ADR-025 §4 («`SELECT, INSERT, UPDATE(outcome)` only»).
- **Motivo:** el resultado se decide antes de escribir la fila, en la misma transacción que la retención;
  no hay nada que actualizar después.
- **Camino elegido:** `SELECT, INSERT` (como `warehouse_milestone`, V53). Cada evento es su propia
  transacción (`WarehouseOrderEventRecorder`); un repetido concurrente pierde en `uq_warehouse_order_event`,
  su retención se revierte con él y responde `DUPLICATE`. Menos privilegio que el diseño.
- **Commit(s):** el de la fase 35 (TMS).

### 2026-10-18 · fase 35 · Tablas nuevas sin acción de auditoría nueva

- **Regla/decisión afectada:** reglas del pack §4 («toda tabla nueva del TMS: … acciones de auditoría»).
- **Motivo:** las dos tablas de V61 son registros de hechos (outbox y recepción), no agregados que una
  persona edite. Lo que cambia en el negocio ya tiene acción: la anulación es `CANCEL` (V10) y la
  retención es `ORDER_HOLD_PLACED` (V54), ahora con `source=INTEGRATION` y la credencial en el detalle.
- **Camino elegido:** sin acción nueva (ADR-025 §4 dice lo mismo); `AuditVocabularyMigrationTest` sin cambios.
- **Commit(s):** el de la fase 35 (TMS).

### 2026-10-18 · fase 35 · El feed de pedidos por defecto es `CANCELLED`

- **Regla/decisión afectada:** ADR-025 §4 / contrato §7.4 (`GET /integration/v1/orders?status=&updatedSince=` no
  decía qué pasa sin `status`).
- **Motivo:** el feed existe para la reconciliación de anulaciones; devolver todos los pedidos a un almacén que
  se olvidó el filtro sería el caso caro.
- **Camino elegido:** sin `status` = `CANCELLED`, igual que el de viajes es `CONFIRMED`; acepta cualquier estado
  de `OrderStatus` (paridad fijada por `WarehouseOrderEventRulesTest`); uno desconocido es `400`.
- **Commit(s):** el de la fase 35 (TMS).

### 2026-10-18 · fase 35 · Escenario E2E s10

- **Regla/decisión afectada:** prompt de la fase 35 («s11 POD si la fase 30 terminó, s12 anulación») y plan 01 §4
  (s10 perfil de material).
- **Motivo:** s10 era de las fases 20/25 y no se escribió; no es de esta fase.
- **Camino elegido:** esta fase agrega s11 y s12 (y el enlace v1.1: scopes de la credencial y eventos de la
  suscripción); s10 queda para la fase 80 o para quien retome 20/25. No se corrieron (> 20 min; los corre la 80).
- **Commit(s):** el de la fase 35 (TMS).

### 2026-10-18 · fase 35 (EWM) · Primera pasada de la reconciliación

- **Regla/decisión afectada:** DESIGN §4.3 (no decía desde dónde arranca un conector sin marca).
- **Motivo:** sin tope, encenderla aplicaría todo el histórico del TMS y crearía cargas para viajes de hace meses.
- **Camino elegido:** la primera pasada mira 24 h atrás (`wms.integration.tms.reconciliation.initial-lookback=PT24H`)
  y como mucho 50 páginas de 200 por listado y pasada; la marca avanza sólo tras aplicar una página entera.
- **Commit(s):** EWM `fb76b4c`.

### 2026-10-18 · fase 35 (EWM) · Consola sin «Reenviar perfiles»

- **Regla/decisión afectada:** DESIGN §4.4 (botones de la consola).
- **Motivo:** la consola es de sólo lectura salvo «Reconciliar ahora»; el reenvío y la edición del conector siguen por API.
  Un formulario que cambia el endpoint de producción desde el navegador merece su fase.
- **Camino elegido:** sólo «Reconciliar ahora» (`POST /api/v1/admin/integrations/tms/reconcile`, permiso MANAGE).
- **Commit(s):** EWM `fb76b4c`.

### 2026-10-18 · fase 35 (EWM) · Una alerta por pedido del TMS, abierta o resuelta

- **Regla/decisión afectada:** DESIGN §4.1 («una abierta por pedido y código»).
- **Motivo:** con sólo el índice de alerta abierta, una alerta resuelta volvería a aparecer en la siguiente reconciliación.
- **Camino elegido:** el índice parcial `ux_tmsexc_pedido_abierta` más un control en el servicio: la alerta se levanta una
  vez por pedido del TMS (`ALERT_ALREADY_RAISED` en adelante).
- **Commit(s):** EWM `fb76b4c`.

### 2026-10-18 · fase 35 (EWM) · «Sin reserva» y dónde viven los endpoints de alertas

- **Regla/decisión afectada:** plan S-3 («sólo si está `OPEN` sin reserva»), `ArchitectureTest` del EWM.
- **Motivo:** el conector no puede depender de `inventory` ni el módulo de pedidos conocer al conector.
- **Camino elegido:** «sin reserva» = ninguna reserva ni asignación `ACTIVE`/`PARTIAL`, leída con un `SELECT` propio
  (`TmsOrderCommitmentLookup`); los endpoints `/api/v1/outbound-orders/{id}/tms-alerts[/cancel-order]` viven en el paquete
  `integration.tms`, guardia contra el almacén del pedido; la anulación pasa por `CancelOutboundOrderUseCase` (origen MANUAL)
  y el POST vuelve a comprobar la condición.
- **Commit(s):** EWM `fb76b4c`.

### 2026-10-18 · fase 35 (EWM) · Número del pedido del TMS en columnas de viaje

- **Regla/decisión afectada:** V50 del EWM (`intg_tms_exception.shipment_number`, `intg_tms_webhook_event.shipment_number`).
- **Motivo:** el aviso `ORDER_CANCELLED` nombra un pedido, no un viaje; agregar otra columna no aportaba.
- **Camino elegido:** esas columnas guardan la referencia del aviso (el número de pedido del TMS) en estos casos; dicho en
  `TMS_CONNECTOR_V1.md` §6.2.
- **Commit(s):** EWM `fb76b4c`.

### 2026-10-18 · fase 40 · Re-apilado por vehículo diferido al ciclo 2

- **Regla/decisión afectada:** ADR-022 §5 («si el vehículo es más bajo que el `H` de referencia, se recalcula con el `H`
  del vehículo antes de decidir») y `OrderGeometryPort.restack`.
- **Motivo:** el ledger de V37 guarda lo asignado por pedido; un re-apilado más bajo produce **más** pilas, más piso y
  más volumen efectivo que lo que el pedido tiene, y `ck_transport_order_not_over_allocated` lo rechazaría. Decidir qué
  guarda el ledger en ese caso (lo re-apilado o lo de referencia) necesita su propio diseño, y los motores son puros
  (habría que entregarles la geometría por altura de vehículo).
- **Camino elegido (ciclo 1):** el pedido se apila contra el `H` de referencia. Un vehículo más bajo que las pilas se
  rechaza por **altura de pila** (conservador: nunca acepta lo que no entra) y uno más bajo que una unidad se rechaza como
  `ITEM_TALLER_THAN_VEHICLE`. Para distinguir las dos sin leer líneas, el pedido guarda `max_unit_height_m` (columna no
  prevista en el ADR). El re-apilado queda para el ciclo 2.
- **Commit(s):** el de la fase 40, ciclo 1.

### 2026-10-18 · fase 40 · Volumen de planificación cuando el volumen sólo se declaró

- **Regla/decisión afectada:** ADR-022 §4 («SUM(efectivo de las medidas) + SUM(ingenuo del resto)»).
- **Motivo:** si ninguna línea trae volumen unitario, el total del pedido es el **declarado** (V17) y cubre también las
  líneas sin medir; sumar el efectivo de las medidas encima lo contaría dos veces.
- **Camino elegido:** sin línea medida → total. Si las líneas traen volumen → la regla del ADR. Si el volumen sólo se
  declaró → la declaración, salvo que **todas** las líneas estén medidas (entonces la suma de efectivos).
  `OrderTotals` no cambia; la regla vive en `TransportOrder.applyGeometry`.
- **Commit(s):** el de la fase 40, ciclo 1.

### 2026-10-18 · fase 40 · `planning_volume_m3` NOT NULL con disparador en vez de DEFAULT

- **Regla/decisión afectada:** ADR-022 §4 (`planning_volume_m3 NOT NULL`, backfill = total).
- **Motivo:** muchos escritores existentes (pruebas, guiones) insertan `transport_order` sin nombrar la columna; un DEFAULT
  0 mentiría (y rompería el guard del ledger para lo que ya estuviera asignado) y no hay DEFAULT que valga «el total».
- **Camino elegido:** `BEFORE INSERT` `tr_transport_order_default_planning_volume` copia `total_volume_m3` cuando viene
  nulo (la misma regla del backfill); la aplicación siempre la escribe explícita. Función con `search_path` fijo y sin
  EXECUTE para PUBLIC (forma de V51).
- **Commit(s):** el de la fase 40, ciclo 1.

### 2026-10-18 · fase 40 · Alturas de pila en `numeric(9,3)`

- **Regla/decisión afectada:** ADR-022 §4 y §6 (`max_stack_height_m numeric(6,3)`, `assigned_stack_height_m numeric(6,3)`).
- **Motivo:** V59 admite alturas unitarias de hasta 9999,9999 m y una unidad más alta que `H` se describe como una pila de
  una unidad: `numeric(6,3)` (máx. 999,999) podría desbordar con datos que V59 ya acepta.
- **Camino elegido:** `numeric(9,3)` en la línea, el pedido y el ledger; el snapshot del viaje (que copia el alto del tipo
  de vehículo, `numeric(6,2)`) se queda en `numeric(6,3)`.
- **Commit(s):** el de la fase 40, ciclo 1.

### 2026-10-18 · fase 40 · La entrega se compara sin el piso

- **Regla/decisión afectada:** `OrderAmounts` (V37) gana `floorAreaM2` y entra en `covers/exceeds`.
- **Motivo:** una entrega se registra en peso, volumen y pallets; comparada con el pedido completo (que ahora tiene piso)
  nunca lo «cubriría» y el pedido no pasaría a `DELIVERED`.
- **Camino elegido:** `OrderAmounts.withoutGeometry()` en `OrderFulfillmentAdapter`; el resto de la regla de entrega no
  cambia. Nota: el volumen del pedido contra el que se compara es ahora el de planificación (lo asignado). La pantalla
  de entrega no pide volumen (manda 0), así que no cambia nada para ella; un integrador que reporte el volumen ingenuo
  de un pedido medido recibiría `409` por exceder lo pedido - dicho en `LOAD_GEOMETRY_V1.md`.
- **Commit(s):** el de la fase 40, ciclo 1.

### 2026-10-18 · fase 45 · Dos motivos para un metro lineal no calculable

- **Regla/decisión afectada:** ADR-022 §8 («`NOT_CALCULABLE` con un motivo cuando el ancho es desconocido»).
- **Motivo:** hay dos huecos distintos que arregla gente distinta: ninguna línea medida (maestro de materiales) y un
  vehículo sin ancho (maestro de flota). Un solo motivo mandaría a uno de los dos a buscar en el lugar equivocado.
- **Camino elegido:** `FLOOR_AREA_UNKNOWN` y `LOAD_WIDTH_UNKNOWN`, ambos en `ck_trip_cost_component_reason` (V63);
  `CostInputs.missingReasonFor` elige; el resto de componentes sigue con su motivo único.
- **Commit(s):** el de la fase 45, ciclo 1.

### 2026-10-18 · fase 45 · Ancho del snapshot, factor del resolvedor actual

- **Regla/decisión afectada:** ADR-022 §8 (`snapshot_load_width_m x fill`).
- **Motivo:** V62 congela el ancho al confirmar pero no el factor de piso; un viaje sin snapshot de ancho (tipo sin
  dimensiones al confirmar) quedaría siempre sin metros lineales aunque la flota ya los tenga.
- **Camino elegido:** ancho = snapshot del viaje, si no el del tipo de vehículo actual; factor = el que resuelve flota
  hoy (tipo → empresa → propiedad), el mismo que usa el límite de piso. Congelar el factor sería otra columna en
  `trip`; no se agregó.
- **Commit(s):** el de la fase 45, ciclo 1.

### 2026-10-18 · fase 45 · `EFFECTIVE_GEOMETRY` cuando el ledger tiene piso

- **Regla/decisión afectada:** ADR-022 §8 («`EFFECTIVE_GEOMETRY` cuando alguna línea medida contribuyó»).
- **Motivo:** el módulo `rates` no puede leer líneas de pedido; el ledger sí sabe cuánto piso suma el viaje y el piso
  sólo existe en líneas medidas (V62).
- **Camino elegido:** `SUM(assigned_floor_area_m2) > 0` ⇒ `EFFECTIVE_GEOMETRY`. Matiz dicho en `RATE_ENGINE_V2.md`
  §4.1: con un volumen sólo declarado y líneas parcialmente medidas, el volumen de planificación es el declarado
  (regla de la fase 40) y la fuente igual dice `EFFECTIVE_GEOMETRY`, porque una línea medida está detrás.
- **Commit(s):** el de la fase 45, ciclo 1.

### 2026-10-18 · fase 45 · La propuesta se cotiza con el volumen de planificación

- **Regla/decisión afectada:** `ProposalPricer` (cotizaba con `totalVolumeM3`, el ingenuo).
- **Motivo:** la tarifa de un viaje real multiplica `SUM(assigned_volume_m3)` = volumen de planificación; una propuesta
  cotizada con el ingenuo daría otro precio que el viaje en que se convierte.
- **Camino elegido:** `ordered().volumeM3()` y `ordered().floorAreaM2()` con el ancho y el factor del vehículo. Para
  pedidos sin geometría no cambia nada (planificación = total).
- **Commit(s):** el de la fase 45, ciclo 1.

### 2026-10-18 · fase 45 · Qué huella se compara y con qué tolerancia

- **Regla/decisión afectada:** ADR-022 §9 y contrato §7.3 («compara `stacks`, `effectiveVolumeM3`, `loadingMeters`»).
- **Motivo:** el TMS no guarda qué unidades de una línea lleva una porción de un pedido dividido, y el EWM manda la huella
  del pedido entero sólo cuando todos sus materiales tienen perfil completo; comparar una suma parcial con un total
  inventaría una diferencia. El EWM apila sin alto de vehículo y redondea por su lado.
- **Camino elegido:** pilas y volumen efectivo sólo para pedido completo con todas sus líneas medidas; metros lineales con
  el piso de la asignación (completa o porción) sobre el ancho del viaje. Pilas exactas; volumen y metros con 5 %
  relativo (`DispatchReconciler.GEOMETRY_TOLERANCE`, constante, no configuración). Una `GEOMETRY_VARIANCE` por medida
  (`uom` `STACKS`/`M3`/`LDM`), `INFO`, nunca cambia el `matchResult` del pedido ni la verificación. Un nulo de cualquier
  lado no compara.
- **Commit(s):** el de la fase 45, ciclo 1.

### 2026-10-18 · fase 50 · `expected_basis` admite `MIXED`

- **Regla/decisión afectada:** ADR-024 parte 1, ítem 4 (`ACTUAL` si `trip_cost.closed_at`, si no `ESTIMATED`).
- **Motivo:** una factura cubre varios viajes y cada uno puede estar cerrado o no; ninguno de los dos valores describe
  la mezcla y elegir uno mentiría sobre la mitad de la cifra.
- **Camino elegido:** `freight_match.expected_basis` ∈ (`ACTUAL`, `ESTIMATED`, `MIXED`), nulo sin cifra esperada
  (`ck_freight_match_basis_needs_expected`). Por línea sólo `ACTUAL`/`ESTIMATED`.
- **Commit(s):** el de la fase 50, ciclo 1.

### 2026-10-18 · fase 50 · El desglose por componente es el del estimado

- **Regla/decisión afectada:** plan F-1 «compara por componente contra el desglose del costo estimado/real».
- **Motivo:** el costo real (V30) es un monto único; `trip_cost_component` sólo existe para el estimado.
- **Camino elegido:** el total del viaje se compara contra el real si está cerrado; cada línea con código de componente
  contra el componente del estimado (`BASE` incluye los ajustes de mínimo/máximo; un componente que el estimado no cobra
  es un 0 conocido); la línea `FREIGHT` contra la cifra del viaje menos los componentes de las otras líneas. Sin desglose
  los esperados por componente son nulos (nunca cero) y el viaje fuera de tolerancia se levanta una vez a nivel viaje.
  La pantalla y el detalle de la discrepancia dicen «estimate» / «closed actual cost».
- **Commit(s):** el de la fase 50, ciclo 1.

### 2026-10-18 · fase 50 · La tolerancia absoluta sólo vale en su moneda

- **Regla/decisión afectada:** V46 `toleranceFor` (aplicaba el monto absoluto a cualquier moneda).
- **Motivo:** 50 PEN no dicen nada de una factura en USD; aplicarlo aceptaría una diferencia que nadie autorizó.
- **Camino elegido:** `TolerancePolicy.toleranceFor(currency)`: en otra moneda sólo el porcentaje; sin porcentaje,
  `Tolerance.NONE`. Sin conversión de monedas.
- **Commit(s):** el de la fase 50, ciclo 1.

### 2026-10-18 · fase 50 · Penalidades fuera de la comparación desde ya

- **Regla/decisión afectada:** ADR-024 parte 2, ítem 6 (fase 60).
- **Motivo:** con `charge_code` existe `PENALTY` desde V64; dejarla dentro de la suma haría que una deducción abriera
  una diferencia de total.
- **Camino elegido:** el matcher excluye las líneas `PENALTY` de toda comparación y las resta del total de cabecera
  comparado. El CHECK de signo, el motivo obligatorio y la aceptación en la aprobación siguen siendo de la fase 60.
- **Commit(s):** el de la fase 50, ciclo 1.

### 2026-10-18 · fase 50 · El formato de exportación lo elige la primera exportación

- **Regla/decisión afectada:** ADR-024 parte 1, ítem 6 (no dice cómo se elige el formato).
- **Motivo:** `uq_payable_export_invoice` admite una exportación por factura; el formato es parte de esa obligación.
- **Camino elegido:** `POST /settlement/invoices/{id}/export?format=CSV|JSON` (por defecto JSON). Repetir devuelve la
  exportación existente en su formato original (`alreadyExported: true`), pida lo que pida. CSV sin BOM, CRLF,
  RFC 4180 (`DelimitedTextWriter.text`, nuevo, junto al `write` con BOM de los reportes).
- **Commit(s):** el de la fase 50, ciclo 1.
