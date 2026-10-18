# ADR-025 - Warehouse sync v1.1: material profiles, plan and POD to the warehouse, order cancellation both ways, reconciliation (and a proposal for dock appointments)

- Status: **Accepted** for sections 1-6 (S-1...S-4, additive to contract v1). Section 7 (S-5, dock
  appointments and truck ETA towards the warehouse) is **Proposed** - decision D-04 is pending and nothing
  of it is implemented until the product owner approves it.
- Date: 2026-10-18
- Relates to: ADR-013 (dispatch source and external dispatch; **its principles are unchanged**), ADR-014
  (holds), ADR-009 (delivery results), ADR-022 (material profile, load geometry), ADR-007 (a feed informs
  and never moves a lifecycle), contract `docs/integrations/WAREHOUSE_EXECUTION_V1.md` (section 7 "v1.1"),
  EWM `docs/integrations/TMS_CONNECTOR_V1.md` and `docs/tms-sync/DESIGN.md`, plan
  `01_SINCRONIZACION_EWM_TMS.md`, decisions D-02, D-03, D-04.
- Migrations (TMS): **V59** (material profile, ADR-022), **V60** (dispatch geometry and trailer plate),
  **V61** (order events both ways, business outbox, scopes). EWM: V56-V58 (`DESIGN.md`). Provisional;
  `PHASE_MAP.md` registers.

## Context

Contract v1 (merged 2026-09-28) carries the plan to the warehouse and what physically left back to TMS.
Gaps, verified in both codebases at `a57af24` (TMS) and `f1a0e48` (EWM):

1. No master data crosses. TMS has no material master (`transport_order_line.material_code` is text).
2. EWM `TmsShipmentPlanParser.parse` (:25-57) keeps only the header, the driver and the order keys; the
   per-order `weightKg/volumeM3/pallets` and the stop windows that TMS returns
   (`ShipmentPlanOrderV1`, `ShipmentPlanStopV1.serviceWindowStart/End`) are discarded.
3. EWM stores the plan's `vehicleLicensePlate` in `out_shipment_load.trailer_number`
   (`ExternalLoadPlanService.transporte`, :326) and sends it back as `vehicle.licensePlate`; TMS has no
   trailer concept anywhere, so a tractor + trailer pair cannot be told apart.
4. `DELIVERY_RESULT_RECORDED` exists in `WebhookEventType` and is published by
   `TripDeliveryService.record` (:168), but the warehouse link subscribes to three events only
   (`scripts/e2e/tms-ewm/lib/setup_link.py:31-34`) and EWM stores any non-contract event as `IGNORED`
   (`TmsWebhookEvent.java:149-154`). The plan already returns `deliveryResult`, `deliveredAt`,
   `deliveryReceiverName`, `evidenceCount` per order; delivered quantities exist in TMS per order
   (`order_delivery.delivered_*`, V45) and per line (`order_delivery_line`, V45) but are not published.
5. Order cancellation crosses in neither direction: `OrderService.cancel` (:310) writes an audit entry and
   no event; TMS has no inbound order-cancel; EWM's `CancelOutboundOrderUseCase` emits
   `OUTBOUND_ORDER_CANCELLED` only to its own outbox.
6. `GET /integration/v1/shipments?status=CONFIRMED,CANCELLED&updatedSince=` **already exists**
   (`IntegrationShipmentController:51-65`); EWM does not call it (its recovery is a manual reprocess or
   `POST .../tms/sync`).
7. Webhooks other than shipment events have no outbox: `tms.shipment_outbox_event.trip_id` is NOT NULL.
   `tms.webhook_delivery` itself is resource-agnostic (`event_id`, `event_type`, `payload`).
8. `tms.trip_exception.reported_by` is a NOT NULL person (V27:340): an integration cannot open one.
   The established machine-safe equivalent is a hold on a committed order, which raises the dispatch
   blocker and Control Tower advisory `ORDER_HOLD_ON_COMMITTED_TRIP` (ADR-014, `DispatchReadiness`).
9. `tms.order_hold` already allows `source = 'INTEGRATION'` with `created_by_client` (V54
   `ck_order_hold_actor`) and has the type `INTEGRATION`, but only `OrderHold.byOperator` exists in Java.

## Decision

### 1. One owner per fact; the other keeps a dated copy and shows disagreement

| Fact | Owner | Copy |
|---|---|---|
| Order (demand) | ERP | EWM and TMS, each from the ERP - unchanged |
| Material physical profile | **EWM** (`md_item`) | TMS `material_profile` (ADR-022) |
| Transport plan (trip, vehicle, driver, stops, windows, planned quantities) | **TMS** | EWM plan tables (`DESIGN.md`) |
| What left the warehouse (SLS) | **EWM** | TMS `external_dispatch` (v1) |
| Delivery result / POD | **TMS** | EWM read-only projection |
| Order cancellation | whoever originates it | the other receives a **signal**, never a command (D-03) |

Nothing received moves inventory in EWM or `TripStatus`/`OrderStatus` in TMS. Unit conversion happens
only with a declared unit; otherwise the field travels `null` and is counted.

### 2. S-1 - Material profiles, EWM -> TMS (TMS side; ADR-022 section 1)

`PUT /integration/v1/material-profiles/{materialCode}` and `POST /integration/v1/material-profiles/batch`,
scope `integration.material:write`, `Idempotency-Key`; EWM interface `TMS_MATP` over the existing BEARER
destinations. Stale protection by `sourceUpdatedAt`. Response outcome per item: `CREATED`, `UPDATED`,
`UNCHANGED`, `STALE`, `INVALID`.

### 3. S-2 - The warehouse uses the plan; POD back; plate; geometry in the SLS

**TMS side (V60 + additive DTO fields):**

- Plan (`GET /integration/v1/shipments/{n}`), additive fields:
  - `orders[].delivery` (null until recorded): `{ result, deliveredAt, receiverName, evidenceCount,
    attempted|delivered|refused { weightKg, volumeM3, pallets }, lines[ { lineNumber, materialCode, uom,
    quantityAttempted, quantityDelivered, quantityRefused } ] }`, from `order_delivery` and
    `order_delivery_line` (V45). The existing flat `deliveryResult/deliveredAt/...` stay (v1 consumers).
    The receiver's identity document and evidence bytes are never published (unchanged rule).
  - `orders[].geometry` (after ADR-022 phase 40; null before or when unmeasured): `{ stacks,
    effectiveVolumeM3, floorAreaM2, maxStackHeightM, unmeasuredLines }`.
- Dispatch confirmation, additive request fields: `vehicle.trailerLicensePlate`, `orders[].stacks`,
  `orders[].effectiveVolumeM3`, `orders[].loadingMeters`, `orders[].geometryReason`. V60 adds
  `external_dispatch.vehicle_trailer_plate` and `external_dispatch_order.stacks`,
  `effective_volume_m3`, `loading_meters`, `geometry_reason`. `VEHICLE_MISMATCH` keeps comparing
  `vehicle.licensePlate` (the tractor) with the trip's vehicle; the trailer is stored and shown, never
  compared (TMS has no trailer master). Geometry is compared in phase 45 (`GEOMETRY_VARIANCE`, `INFO`).
- `DELIVERY_RESULT_RECORDED` becomes a **contract event** (alias of the same name). The warehouse
  subscribes to it like the other three. The body is a trigger; the warehouse pulls the plan.

**EWM side** (detail in `DESIGN.md`): store planned quantities and windows, show them as "Planificado por
TMS" without validating (rule 96); `vehicle_plate` for the tractor and `trailer_number` for the trailer;
projection `out_shipment_delivery` (no posting, ArchitectureTest), a FAILED/PARTIAL offers - never creates -
a return (rule 193); `StackCapacityCalculator` gains nesting to fill the SLS geometry.

### 4. S-3 - Order cancellation both ways (D-03)

**TMS -> warehouse.** When a TMS order is cancelled (`OrderService.cancel`), TMS writes, in the same
transaction, an `ORDER_CANCELLED` row in the new **`tms.business_outbox_event`** (V61: `company_id`,
`resource_type` `ORDER` (and later `CARRIER_INVOICE`, ADR-023), `resource_id`, `resource_reference`,
`event_type`, `occurred_at`; `SELECT, INSERT` only; RLS) through a `shared` port
(`BusinessEventPublisher`) implemented in `integration`, which fans out to `tms.webhook_delivery` with the
existing `EventFanoutPort` path. Envelope = `WEBHOOKS_V1.md`'s with `resource.type = "order"`,
`resource.reference = orderNumber`. `WebhookEventType` gains `ORDER_CANCELLED`
(`ck_webhook_subscription_event_type` restated; `WebhookEventTypeTest` learns that order events have no
`ShipmentEventType` mirror). The warehouse pulls **`GET /integration/v1/orders/{orderNumber}`** (new,
scope **`integration.order:read`**): `{ orderNumber, externalSource, externalReference, status,
cancelledAt, cancelReason, updatedAt }`, and reconciles with
`GET /integration/v1/orders?status=CANCELLED&updatedSince=` (paged by `updated_at, id`, like shipments).
TMS only cancels orders that are not planned (`OrderService.cancel` refuses PLANNED/IN_EXECUTION/...), so
the signal never concerns goods already on a TMS trip. The warehouse **does not cancel by itself**: it
raises an alert, and offers "Anular" to a supervisor only when its order is `OPEN` with no reservation.

**Warehouse -> TMS.** `POST /integration/v1/warehouse-order-events`, scope
**`integration.warehouse-order-event:write`**, operation `WAREHOUSE_ORDER_EVENT_BATCH`,
`Idempotency-Key`, batch of up to 200 like the milestones:

- `type` `ORDER_CANCELLED` (the warehouse cancelled its order, rules A/B/F of the EWM) or
  `ORDER_SHORT_CLOSED` (it closed the order shipping less than requested: the remainder of a
  `PARTIALLY_SHIPPED`).
- Stored in **`tms.warehouse_order_event`** (V61; `uq (company_id, source_system, event_id)`, the
  order key, the resolved `order_id` or null, `outcome`, `hold_id`, `payload_hash`, the credential;
  `SELECT, INSERT, UPDATE(outcome)` only). Item results: `HOLD_PLACED`, `ALREADY_HELD`, `NOTED`,
  `DUPLICATE`, `UNKNOWN_ORDER`, `ORDER_CLOSED`, `INVALID` (200, or 207 when any item is `INVALID`).
- Effect, never a cancellation:
  - `ORDER_CANCELLED` on an open TMS order -> a **blocking** hold `hold_type = INTEGRATION`,
    `reason_code = WAREHOUSE_CANCELLED`, `source = INTEGRATION`, `created_by_client` = the credential
    (new factory `OrderHold.byIntegration`, new `OrderHoldPort.placeFromIntegration`). The order leaves
    planning (existing `noActiveBlockingHold`). If it is already on a committed trip, the same hold raises
    the existing `ORDER_HOLD_ON_COMMITTED_TRIP` blocker and Control Tower advisory - this is the "trip
    exception" of the plan, because `trip_exception` requires a person (context 8; logged in
    `DECISIONS_LOG.md`). A person lifts the hold or cancels the order.
  - `ORDER_SHORT_CLOSED` -> a **non-blocking** hold `reason_code = WAREHOUSE_SHORT_CLOSED` carrying the
    shipped vs requested figures in `reason`: it informs the planner and stops nothing.
  - Order delivered, cancelled or unknown -> recorded (`ORDER_CLOSED` / `UNKNOWN_ORDER`), nothing else.
- Audit `ORDER_HOLD_PLACED` (existing action) with `source=INTEGRATION`.

### 5. S-4 - Reconciliation and console (warehouse side)

TMS adds nothing beyond section 4's order list: the shipment feed exists. The EWM pulls
`GET /integration/v1/shipments?status=CONFIRMED,CANCELLED&updatedSince=` and
`GET /integration/v1/orders?status=CANCELLED&updatedSince=` periodically, **off by default**, and shows a
console (Integraciones > TMS). `DESIGN.md` owns it.

### 6. Compatibility

Every field and message is additive; a v1 partner keeps working unchanged. New scopes are opt-in per
credential. `WAREHOUSE_EXECUTION_V1.md` section 6 ("sends no inbound order cancellation") is superseded by
section 7.4 for v1.1 partners.

### 7. S-5 - Dock appointments and truck ETA (**Proposed**, D-04)

TMS `tms.appointment` (V41) models **destination/customer** docks (`purpose PICKUP|DELIVERY`, one door
per `location_resource`, no double booking); EWM `dock_appointment` (V39) and `yard_visit` (V45) model
**its own** doors and gate. Two calendars for one wall is the failure to avoid. Proposal:

1. The warehouse owns the calendar of its doors. For an origin that is a warehouse with a connector, TMS
   does not book a door locally; it **requests** one: `POST {ewm}/api/v1/integration/tms/appointment-requests`
   (`transportReference`, desired window, vehicle, carrier), answered asynchronously with
   `APPOINTMENT_CONFIRMED|RESCHEDULED|REFUSED` as a warehouse milestone (new types on
   `/warehouse-milestones`), stored in TMS as an `appointment` with `source = WAREHOUSE` read-only.
2. The truck's estimated arrival at the origin travels as a TMS -> warehouse event `TRUCK_ETA_UPDATED`
   (from ADR-011's estimate, with its provenance), shown on the EWM appointment and yard visit; it never
   moves either.
3. Destination appointments (customer docks) stay in TMS V41 unchanged.

Open questions for the decision: who reschedules when the truck is late; whether a warehouse without
EWM keeps TMS-local origin appointments (proposed: yes, per origin); and whether EWM's yard visit should
be created from the TMS plan. **Not implemented.**

## Consequences

- Five new endpoints/messages and three scopes, all additive; three TMS tables (`material_profile`,
  `business_outbox_event`, `warehouse_order_event`) and a few columns.
- A warehouse cancellation can never silently drop an order from a truck: it stops it and asks a person.
- `ShipmentPlanOrderV1` grows; the plan stays one pull per trip.
