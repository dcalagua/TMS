# 01 · Sincronización EWM ⇄ TMS (contrato v1 → v1.1)

> Objetivo: que el almacén y el transporte **cuenten la misma historia** de cada
> pedido, de cada material y de cada camión, sin compartir una sola tabla
> (CLAUDE.md del TMS: «integrate only through APIs/events/contracts»).
> Todo lo nuevo es **aditivo y opcional**: un socio que habla v1 sigue funcionando.

## 1 · Principios

1. **Cada dato tiene UN dueño.** El otro sistema guarda una *copia fechada* (snapshot) y
   nunca la corrige: si discrepa, lo **muestra**.
   | Dato | Dueño | Copia en |
   |---|---|---|
   | Pedido (demanda) | ERP | EWM (ORR) y TMS (`/integration/v1/orders/batch`), por separado — **no cambia** |
   | Perfil físico del material (dimensiones, peso, pila, anidado) | **EWM** (`md_item`) | TMS `material_profile` |
   | Plan de transporte (viaje, vehículo, chofer, paradas, ventanas) | **TMS** | EWM `out_shipment_load_plan_order` + columnas nuevas |
   | Lo que salió del almacén (SLS) | **EWM** | TMS `warehouse_dispatch` (v1, ya existe) |
   | Resultado de entrega / POD | **TMS** | EWM proyección de sólo lectura |
   | Anulación de pedido | quien la origine | el otro recibe una **señal**, no una orden |
2. **Nada que llegue del otro sistema mueve inventario ni el ciclo de vida del viaje.**
   En el EWM, la POD no contabiliza nada (regla 1/83: el 601 ya bajó el stock). En el TMS,
   un hito del almacén no cambia `TripStatus` (ADR-013).
3. **Sin conversión inventada.** Unidades que no son kg/m/m³ viajan `null` y se cuentan
   (regla 199 del EWM, misma regla del conector v1 línea 246).
4. **Idempotencia** en todo mensaje nuevo: clave + huella (regla 29 del EWM;
   `Idempotency-Key` en el TMS).

## 2 · Lo que se construye

### S-1 · Perfil físico del material: EWM → TMS  *(fases 20 y 25)*

- **TMS** — tabla `tms.material_profile` (company, `material_code` único, `source_system`,
  `length_m`, `width_m`, `height_m`, `unit_weight_kg`, `unit_volume_m3`,
  `stack_mode` NONE|STACKABLE|NESTABLE, `stack_max_quantity`, `nest_increment_m`,
  `max_top_load_kg`, `orientation_locked`, `source_updated_at`, `version`).
  Endpoint `PUT /integration/v1/material-profiles/{materialCode}` (upsert idempotente,
  scope `integration.material:write`) y `POST /integration/v1/material-profiles/batch`.
  Pantalla de sólo lectura «Perfiles de material» en Maestros. Las líneas de pedido
  **copian** el perfil al crearse (snapshot), igual que hoy copian el texto del material.
- **EWM** — V56 en `md_item`: `stack_mode`, `nest_increment` (en `dimension_uom_code`),
  `max_top_load` (+ unidad de peso). Formulario (viaja en el PUT, regla 203), importador
  (plantilla generada, regla 116) y handler de entrada (ausente conserva).
  Interfaz de salida nueva `TMS_MATP` por los destinos BEARER existentes (V49/V50):
  se emite al crear/editar un material y por un botón «Reenviar perfiles» (lote).
  Conversión: cm→m, mm→m, g→kg sólo con unidad declarada; si no, `null`.

### S-2 · El EWM usa lo que el TMS planificó  *(fase 30)*

- Guardar del plan: `weightKg`, `volumeM3`, `pallets` por pedido y las ventanas por parada
  (`stops[]`). Mostrarlos en la carga (`CRG-…`) como **«Planificado por TMS»** al lado de lo
  real. No validan nada: informan (regla 96).
- Placa: el plan trae `vehicleLicensePlate` del **tracto**; guardar en un campo propio
  (`vehicle_plate`) y dejar `trailer_number` para el remolque. El SLS manda los dos.
- `DELIVERY_RESULT_RECORDED` → el EWM lo consume (mismo webhook firmado + pull del plan) y
  guarda una proyección `out_shipment_delivery` (pedido, resultado DELIVERED / PARTIAL /
  FAILED, cantidades entregadas, fecha, referencia de evidencia **sin URL**). Visible en el
  despacho y en la línea de tiempo del pedido (`process`). **No contabiliza.**
  Un FAILED/PARTIAL **sugiere** crear un RMA (enlace), no lo crea (regla 193).
- La confirmación de despacho agrega, cuando el perfil está completo: `stacks`,
  `effectiveVolumeM3`, `loadingMeters` (calculados con el mismo calculador puro del
  EWM extendido con anidado). Si falta algo, `null` + motivo.

### S-3 · Anulaciones de pedido en las dos direcciones  *(fase 35)*

- **TMS → EWM:** evento `ORDER_CANCELLED` (webhook nuevo). El EWM **no anula solo**:
  crea una alerta operativa en el pedido («El TMS anuló el transporte de este pedido») y,
  sólo si el pedido está `OPEN` sin reserva, ofrece el botón «Anular» al supervisor.
  Motivo: anular un pedido es del ERP/cliente; el TMS anula el **transporte**.
- **EWM → TMS:** interfaz `TMS_ORDCX` → `POST /integration/v1/warehouse-order-events`
  (`ORDER_CANCELLED`, `ORDER_SHORT_CLOSED`). El TMS pone el pedido en **retención**
  (`order_hold`, V54 del TMS) con motivo `WAREHOUSE_CANCELLED` y lo saca de la
  planificación; si ya está en un viaje confirmado, abre una excepción del viaje.
  Una persona decide.

### S-4 · Reconciliación y consola  *(fase 35)*

- EWM: pull periódico `GET /integration/v1/shipments?updatedSince=` (si el TMS no lo tiene,
  se agrega en el TMS) que repara avisos perdidos; apagado de fábrica
  (`wms.tms.reconciliation.enabled=false`).
- EWM: pantalla **Integraciones › TMS** con conectores, últimos avisos (recibido, duplicado,
  ignorado, error), últimos SLS enviados y su outcome (APPLIED / RECONCILED / …), y el
  botón «Reconciliar ahora». Sólo lectura salvo ese botón.

### S-5 · Citas de muelle y ETA  *(sólo diseño esta noche — decisión D-04)*

El TMS tiene citas (V41) para muelles de **destino/cliente**; el EWM tiene citas (V39) y
patio (V45) para **sus** puertas. Propuesta: el EWM es dueño de la agenda de sus puertas;
el TMS **solicita** una cita (`POST` al EWM) para la recogida y recibe la confirmación; la
ETA del viaje al origen viaja como hito `TRUCK_ETA_UPDATED` y el EWM la muestra en la cita.
Se documenta como ADR propuesta; **no se implementa** hasta que Dennis la apruebe.

## 3 · Documentación que se corrige

- TMS `docs/integrations/WAREHOUSE_EXECUTION_V1.md` → v1.1 con S-1…S-4 y quitar «planned».
- EWM `docs/integrations/TMS_CONNECTOR_V1.md` §10 → los endpoints existen; agregar S-1…S-4.
- TMS `docs/implementation/TMS_EWM_IMPLEMENTATION_REPORT.md` → nota de que está mergeado.
- TMS `CLAUDE.md` «Deferred by decision»: la integración EWM **ya no está diferida** (v1 + v1.1).

## 4 · Pruebas

- Unitarias de cada parser/formateador con golden files (EWM regla de golden files).
- IT con Testcontainers en cada lado.
- El harness `TMS/scripts/e2e/tms-ewm/run.sh` con `EWM_ROOT` apuntando al worktree:
  escenarios nuevos **s10** (perfil de material llega al TMS), **s11** (POD vuelve al EWM),
  **s12** (anulación de pedido en las dos direcciones).
