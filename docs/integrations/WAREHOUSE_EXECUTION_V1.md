# TMS by EBIM - Warehouse Execution Contract v1 (TMS <-> WMS)

> **Status note - 2026-10-18 (overnight pack phase 05).** This contract is **implemented and
> merged** on both sides: TMS `feature/tms-ewm-integration-v1` reached `dev` on 2026-09-28 (PR #13,
> `0553f16`), and the EWM connector was merged on its side the same day (`00_ESTADO_ACTUAL.md` §3). Two statements below are older
> than the code and are kept as written rather than rewritten:
>
> - §3.1 says the `SHIPMENT_CHANGED` producer is "planned". **It exists**:
>   `TripService.announcePlanChange` publishes it when the driver changes on a `CONFIRMED` /
>   `READY_FOR_DISPATCH` trip (metadata `change=DRIVER`). It writes no timeline entry, by design.
> - §3.3 (and the table at the top) says `originExternalReference`, `routeCode`, `driver{…}` and
>   `orders[].stopSequence` are "planned, additive". **They are returned today**
>   (`ShipmentPlanHeaderV1`, `ShipmentPlanOrderV1`), null when not set.
>
> **Phase 10 (same date)** rewrote those two cells to match the code and added §7 "v1.1 (additive)"
> (ADR-025): material profiles, POD and order events in both directions, reconciliation.
>
> Known gaps of v1 on the EWM side (weight/volume/pallets/windows dropped by the parser, POD ignored,
> plate stored as `trailer_number`, no `updatedSince` pull) are listed in
> `docs/roadmap/2026-10-enterprise/00_ESTADO_ACTUAL.md` section 3.

The public contract between TMS and a warehouse management system: EWM by EBIM, or any third-party
WMS. It covers the transport plan going to the warehouse, and what physically left coming back.

| Direction | Status |
|---|---|
| **TMS → WMS** (transport plan) | **Available today.** Aliases of existing mechanisms: `OUTBOUND_SHIPMENT_V1.md`, `WEBHOOKS_V1.md`. The additive fields of §3.3 are returned |
| **WMS → TMS** (dispatch, milestones) | **Implemented** with migrations V52/V53 (ADR-013) |
| **v1.1** (material profiles, POD, order events both ways, reconciliation) | **Designed** in ADR-025, §7 below; additive; each message says which overnight-pack phase implements it |

Decisions: **ADR-013** (dispatch source and external dispatch) and **ADR-014** (scheduling and
release). Nothing here asks either product to read the other's tables. No primary key crosses the
boundary, and no foreign key does either.

---

## 1. Principles

1. **Each product keeps its own integration style.**
   - TMS publishes the way it already does: a signed webhook that says *something changed*, then a
     pull of the full resource.
   - TMS receives the way it already does: authenticated JSON over `/integration/v1`.
   - A WMS adapts in one connector on its side, which is what keeps TMS vendor-neutral.
2. **Business references only.** The shared values are:
   - the shipment number (`transportReference`);
   - the ERP order key (`sourceSystem` + order reference);
   - the warehouse code.
3. **Internal names do not cross.** Each product's `SHIPMENT_CONFIRMED` means something different
   (§2). The contract speaks only the names in this document.
4. **TMS works without a WMS.** Everything in §4 is optional for TMS. Its dispatch mode (ADR-013)
   decides what a received dispatch does.

## 2. Vocabulary

| Contract term | TMS | EWM by EBIM |
|---|---|---|
| `transportReference` | `trip.shipment_number` | `out_shipment_load.external_load_number` |
| Load (`loadReference`) | - | `out_shipment_load.load_number` (`CRG-…`) |
| Dispatch document (`dispatchReference`) | `external_dispatch.dispatch_reference` (V53) | `out_shipment.shipment_number` (`SLS-…`) |
| Order key | `transport_order (external_source, external_reference)` | `out_order (source_system, external_order_number)` |
| Warehouse code (`warehouseCode`) | `location.external_reference` of the trip's origin | `warehouses.code` (or `erp_code`) = `facility_code` |
| `TRANSPORT_PLAN_CONFIRMED` | internal `SHIPMENT_CONFIRMED` | - |
| `DISPATCH_CONFIRMED` | - | internal `SHIPMENT_CONFIRMED` + SLS |

> **Name collision, for implementers only.** In TMS, `SHIPMENT_CONFIRMED` is "the plan is
> committed". In EWM, `SHIPMENT_CONFIRMED` is "the truck left". A connector that forwards either
> native name to the other product violates this contract.

### 2.1 `transportReference` is opaque

It is `trip.shipment_number`, for example `SH-00000042`. The **prefix is chosen per company** (TMS
migration V34), so a WMS **stores and echoes it verbatim and never parses or validates its shape**.
TMS resolves it only inside the calling credential's company.

### 2.2 `sourceSystem` is a namespace of orders, not a product name

`sourceSystem` names the **stable namespace in which an order reference is unique**. It is the ERP
instance and country or company, not the ERP brand:

```
SAPB1_PE    SAPB1_EC    SAPS4_PE    JDE_ECUACORRIENTE    ODOO_EBIM
```

- **It must be byte-for-byte the same value** in TMS `transport_order.external_source` and in EWM
  `out_order.source_system`, for every order both products receive. The pair
  (`sourceSystem`, order reference) is how the two products agree they mean the same order.
- Format: `^[A-Z0-9_]{2,40}$`, upper case.
- It is assigned once per ERP connection when the connector is set up, and never changed. Changing
  it orphans every open order.
- In EWM it is derived from the integration client (`intg_client.external_system`), never from the
  file. That client must be configured with this value.

### 2.3 Cardinality in contract v1

**1 TMS trip = 1 active WMS load = 1 dispatch document.**

EWM enforces it: `ux_outld_externo (tenant_id, external_load_number) WHERE status <> 'CANCELLED'`.
TMS stores each dispatch document on its own, so its model can hold several per trip later. **This
contract does not promise that a trip can be split across loads.** That would be v2, with either a
`transportLoadReference` or a relaxed EWM index.

---

## 3. TMS → WMS: the transport plan

### 3.1 Events (contract aliases of existing TMS events)

| Contract event | TMS event (outbox, webhook) | When | Status |
|---|---|---|---|
| `TRANSPORT_PLAN_CONFIRMED` | `SHIPMENT_CONFIRMED` | The planning run is confirmed and the trip is committed | produced today |
| `TRANSPORT_PLAN_UPDATED` | `SHIPMENT_CHANGED` | The driver changes on a `CONFIRMED` / `READY_FOR_DISPATCH` trip | produced today (`TripService.announcePlanChange`) |
| `TRANSPORT_PLAN_CANCELLED` | `SHIPMENT_CANCELLED` | A committed trip is cancelled | produced today |

**Orders and vehicle of a confirmed trip never change.** A different plan is a
`TRANSPORT_PLAN_CANCELLED` followed by a `TRANSPORT_PLAN_CONFIRMED` for a **new**
`transportReference` (ADR-013 §9).

### 3.2 Notification: webhook v1, unchanged

`POST <wms endpoint>`. The envelope is exactly `WEBHOOKS_V1.md`'s:

```json
{
  "apiVersion": "v1",
  "id": "3f0a0e2c-9c4b-4b1e-9a6f-0f6b1c2d3e4a",
  "type": "SHIPMENT_CONFIRMED",
  "occurredAt": "2026-09-28T06:15:47Z",
  "companyId": "6b1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "resource": { "type": "shipment", "id": "9a6f0f6b-1c2d-3e4a-5b6c-7d8e9f0a1b2c", "reference": "SH-00000845" }
}
```

- Headers: `X-TMS-Event-Id`, `X-TMS-Event-Type`, `X-TMS-Delivery-Id`, `X-TMS-Delivery-Attempt`,
  `X-TMS-Signature: t=<unix>,v1=<hex>` (HMAC-SHA-256 over `"<t>.<rawBody>"`) and `X-Correlation-Id`.
- **The WMS must:**
  - verify the signature, rejecting a timestamp more than 5 minutes old;
  - de-duplicate by `id`;
  - treat the body as a **trigger only**.

  The body never carries the plan; the WMS fetches it (§3.3).
- **Retries:** 6 attempts over about 45 minutes, then `FAILED`. Recovery is §3.4, not the webhook.

### 3.3 The plan: `GET /integration/v1/shipments/{transportReference}`

Scope: `integration.shipment:read`. Today's response is documented in `OUTBOUND_SHIPMENT_V1.md` §3.2.
The fields a WMS needs, and their v1 status:

```json
{
  "shipment": {
    "shipmentNumber": "SH-00000845",
    "status": "CONFIRMED",
    "planningDate": "2026-09-28",
    "originCode": "CD-LIMA",
    "originExternalReference": "CD01",
    "plannedDepartureAt": "2026-09-28T08:00:00-05:00",
    "routeCode": "LIMA-SUR",
    "carrierCode": "TRSA",
    "vehicleCode": "V-012",
    "vehicleLicensePlate": "B7K-812",
    "driver": { "code": "DRV-044", "name": "Juan Pérez", "documentNumber": "40123456" },
    "version": 3
  },
  "stops": [
    { "sequence": 1, "locationCode": "SODIMAC-ATOCONGO", "serviceWindowStart": "09:00", "serviceWindowEnd": "12:00" }
  ],
  "orders": [
    { "orderNumber": "TO-00010011", "externalSource": "SAPB1_PE", "externalReference": "PED-1011",
      "stopSequence": 1, "weightKg": 420.5, "volumeM3": 1.8, "pallets": 2 }
  ]
}
```

| Field | Status |
|---|---|
| `shipmentNumber`, `status`, `planningDate`, `originCode`, `plannedDepartureAt`, `carrierCode`, `vehicleCode`, `vehicleLicensePlate`, `version`, `stops[]`, `orders[].orderNumber/externalSource/externalReference/weightKg/volumeM3/pallets` | **Returned today** |
| `originExternalReference`, `routeCode`, `driver{…}`, `orders[].stopSequence` | **Returned today** (added to v1 additively; null when not set). A consumer must ignore unknown fields |

The WMS maps the plan like this:
- `shipmentNumber` goes to `external_load_number`;
- `originExternalReference` gives the warehouse;
- `orders[]` give the planned stops (`out_shipment_load_order`), each order found by
  (`externalSource`, `externalReference`) = (`source_system`, `external_order_number`);
- the transport block is carrier, plate and driver.

**What the WMS does on each event:**

| Event | Load in `DRAFT` / `PLANNED` | `LOADING` / `READY_TO_SHIP` | `SHIPPED` |
|---|---|---|---|
| `TRANSPORT_PLAN_CONFIRMED` | create or update | update the transport block only | ignore |
| `TRANSPORT_PLAN_UPDATED` | update | update the transport block | ignore |
| `TRANSPORT_PLAN_CANCELLED` | cancel the load | **do not cancel automatically**: raise an operational exception | ignore (answer with `DISPATCH_CONFIRMED`) |

### 3.4 Recovery and reconciliation (pull)

- `GET /integration/v1/shipments?status=CONFIRMED,CANCELLED&updatedSince=<watermark>` pages by
  `updated_at, id`. **This is the reconciliation feed.** Run it periodically and after any outage.
- `GET /integration/v1/shipments/events?since=` is also available. Its watermark is **business
  time**, and a back-dated event can land behind it, so it is not the recovery mechanism.
- A `DRAFT` trip is never visible (404).

---

## 4. WMS → TMS: what physically happened (contract only)

**Authentication:** an integration credential of the TMS company, sent as
`Authorization: Bearer tmsc_<id>.tmss_<secret>`, holding only the scopes below. There is **one
credential per WMS company**. The company is the credential's, and never a header's.

| Scope | Endpoint |
|---|---|
| `integration.dispatch:write` | `POST /integration/v1/dispatch-confirmations` |
| `integration.warehouse-milestone:write` | `POST /integration/v1/warehouse-milestones` |
| `integration.shipment:read` | §3 (to read the plan) |

**Headers:** `Idempotency-Key` (recommended, `^[A-Za-z0-9._:-]{8,128}$`) and `X-Correlation-Id`.

**Versioning:** by path (`/integration/v1`). Additive fields do not bump the version, and consumers
ignore unknown fields.

### 4.1 `DISPATCH_CONFIRMED` - `POST /integration/v1/dispatch-confirmations`

One request per dispatch document (SLS). **Send it whatever the TMS dispatch mode.** TMS decides what
it does (ADR-013 §3).

```json
{
  "sourceSystem": "EWM_EBIM",
  "dispatchReference": "SLS-000001",
  "revision": 1,
  "transportReference": "SH-00000845",
  "loadReference": "CRG-000001",
  "warehouseCode": "CD01",
  "actualDispatchAt": "2026-09-28T08:12:00-05:00",
  "carrier": { "code": "TRSA", "name": "Transportes SA" },
  "vehicle": { "licensePlate": "B7K-812", "type": "FURGON" },
  "driver": { "name": "Juan Perez", "documentNumber": null },
  "sealNumber": "PRE-00991",
  "transportDocumentNumber": "T001-000123",
  "totals": { "handlingUnits": 3, "weightKg": 1180.0, "volumeM3": 4.6 },
  "orders": [
    {
      "externalSource": "SAPB1_PE",
      "externalReference": "PED-1011",
      "warehouseOrderNumber": "ORR-001-00000007",
      "status": "PARTIALLY_SHIPPED",
      "handlingUnits": 2,
      "weightKg": 790.0,
      "lines": [
        { "lineNumber": 1, "materialCode": "SKU-100", "lotCode": "L2409", "quantity": 97, "uom": "UN",
          "handlingUnitCode": "OBLPN-00000001" }
      ]
    }
  ],
  "document": { "format": "SLS", "version": "LOGFIRE-1", "contentType": "text/plain",
                "content": "[H1]|CD01|EBIM|CREATE|TL|CRG-000001|..." }
}
```

| Field | Rule |
|---|---|
| `sourceSystem` | The **producer** of the dispatch, e.g. `EWM_EBIM` for EWM by EBIM. It is **not** an order namespace, and it is part of the business key |
| `dispatchReference` + `revision` | Business identity. A correction resends the same reference with `revision + 1` |
| `transportReference` | Echoed from the plan, opaque (§2.1) |
| `orders[].externalSource/externalReference` | The ERP order key (§2.2). `externalSource` is the **ERP namespace** (`SAPB1_PE`, …), equal byte for byte to TMS `transport_order.external_source` and EWM `out_order.source_system`. Never the producer's code |
| `orders[].lines[]` | **Optional.** Per-line quantities where the WMS line number is the ERP line number |
| `document` | **Optional.** The native document, stored as received |
| `status` of an order | The WMS's own reading (`SHIPPED` / `PARTIALLY_SHIPPED`), stored for reference |

**Response** (`200` on a repeat, `201` on first receipt):

```json
{
  "id": "0b7c6a1e-…",
  "dispatchReference": "SLS-000001",
  "revision": 1,
  "outcome": "APPLIED",
  "transportReference": "SH-00000845",
  "verificationStatus": "MISMATCH",
  "discrepancies": [
    { "code": "QUANTITY_VARIANCE", "orderReference": "PED-1011", "lineNumber": 1,
      "planned": 100, "dispatched": 97, "uom": "UN" },
    { "code": "MISSING_ORDER", "orderReference": "PED-1014" }
  ]
}
```

| `outcome` | Meaning |
|---|---|
| `APPLIED` | This document dispatched the trip |
| `RECONCILED` | The trip had already departed, or the company is in `MANUAL`. Compared only |
| `UNAPPLIED` | It would dispatch, but a TMS rule needs a person. Recorded and flagged |
| `RECORDED_UNMATCHED` | `transportReference` unknown in this company. Recorded |
| `UNCHANGED` | The same `dispatchReference`, `revision` and content was received before |
| `STALE` | A higher revision is already current |

**Discrepancies never produce a 4xx.** A dispatch that disagrees with the plan is still a true fact,
and a WMS must never dead-letter it. 4xx means only that the request itself is wrong: validation 400,
authentication 401, scope 403, or an `Idempotency-Key` reused with another body 409. Errors are
RFC 9457 documents, as in `INBOUND_API_V1.md` §7.

Discrepancy codes: `UNKNOWN_TRANSPORT_REFERENCE`, `TRIP_CANCELLED`, `TRIP_NOT_COMMITTED`,
`MISSING_ORDER`, `EXTRA_ORDER`, `QUANTITY_VARIANCE`, `QUANTITY_UNCOMPARABLE`, `CARRIER_MISMATCH`,
`VEHICLE_MISMATCH`, `DRIVER_MISMATCH` (warning), `WAREHOUSE_MISMATCH`, `DISPATCH_TIME` (more than
15 minutes' difference, informative), `UNKNOWN_LOAD`. Definitions: ADR-013 §5 and §13. Plates are
compared upper case without spaces or hyphens. An `EXTRA_ORDER` never creates an assignment.

### 4.2 `WAREHOUSE_MILESTONE` - `POST /integration/v1/warehouse-milestones`

Informative. **A milestone never moves a TMS lifecycle.**

```json
{
  "sourceSystem": "EWM_EBIM",
  "milestones": [
    { "eventId": "8c1f2d3e-…", "type": "LOADING_STARTED", "transportReference": "SH-00000845",
      "loadReference": "CRG-000001", "warehouseCode": "CD01", "occurredAt": "2026-09-28T07:20:00-05:00" }
  ]
}
```

- `type` is one of `LOADING_STARTED`, `LOAD_READY` or `LOAD_CANCELLED`. TMS stores them as
  `transport_event` types `WAREHOUSE_LOADING_STARTED`, `WAREHOUSE_LOAD_READY` and
  `WAREHOUSE_LOAD_CANCELLED` with `source = INTEGRATION`. EWM translates its internal
  `SHIPMENT_LOAD_LOADING_STARTED`, `SHIPMENT_LOAD_READY`, `SHIPMENT_LOAD_CANCELLED`.
- It is a batch of up to 200. The response is `200`, or `207` when any item was refused, with one
  result per item: `RECORDED`, `DUPLICATE` (the same `eventId`), `UNKNOWN_SHIPMENT` or `INVALID`.
- Milestones may arrive in any order and after the dispatch; each keeps its own `occurredAt`.

### 4.3 How TMS answers (implementation, V53)

- **Status codes.** `201` on first receipt; `200` for `UNCHANGED` (same `dispatchReference`,
  `revision` and content - content is compared on the parsed document, so whitespace does not
  matter) and for `STALE` (a revision older than the current one, whatever its content). `409` only
  when the same revision arrives with different content, or when an `Idempotency-Key` is reused with
  another body. `400` for a malformed document, `401`/`403` for authentication and scope, `413` over
  2 MB.
- **Every discrepancy carries a `severity`.** `ERROR` makes the document `MISMATCH`; `WARNING` and
  `INFO` are shown without changing the verification.

  | Severity | Codes |
  |---|---|
  | ERROR | `UNKNOWN_TRANSPORT_REFERENCE`, `TRIP_CANCELLED`, `TRIP_NOT_COMMITTED`, `MISSING_ORDER`, `EXTRA_ORDER`, `QUANTITY_VARIANCE`, `CARRIER_MISMATCH`, `VEHICLE_MISMATCH`, `WAREHOUSE_MISMATCH`, `UNKNOWN_LOAD`, `NOT_APPLIED` |
  | WARNING | `QUANTITY_UNCOMPARABLE`, `DRIVER_MISMATCH`, `DISPATCH_BLOCKER` |
  | INFO | `DISPATCH_TIME` |

  `DISPATCH_BLOCKER` is a check a person dispatching would have been refused by (vehicle, driver,
  carrier, calendar, an open tender offer): the truck already left, so it is recorded, not refused.
  `NOT_APPLIED` is why an `UNAPPLIED` document could not move the trip: a database invariant (V42's
  carrier-owns-vehicle) or a dispatch time before the trip was confirmed or made ready. TMS never
  invents a time or a person to make a fact fit.
- **Quantities.** Per line (lines summed across handling units) when the whole order is on the trip
  and both sides name the line and the same unit; per order by weight with a 1% tolerance
  otherwise (a share of a split order is never compared line by line); `QUANTITY_UNCOMPARABLE` when
  neither is possible.
- **Warehouse code** matches the origin's external reference, or its code when it has none.
- **Reading it back.** `GET /api/v1/planning/trips/{tripId}/warehouse` (permission
  `planning.trip:read`) returns the mode, how the trip departed, the derived verification, every
  document with its discrepancies and orders, and the milestones;
  `GET …/warehouse/documents/{id}/raw` returns a document exactly as received.

**There is no `DISPATCH_CANCELLED`.** A WMS dispatch cannot be undone. Goods that come back are a
return, and TMS records them as a delivery outcome and reopen (ADR-009), not as a cancelled dispatch.

---

## 5. One trip, end to end

```
TMS                                         WMS (EWM)
 plan run confirmed
 ── webhook SHIPMENT_CONFIRMED ───────────▶ verify HMAC, dedupe by id
 ◀── GET /shipments/SH-00000845 ─────────── create load CRG-000001, external_load_number = SH-00000845
                                             picking · packing
 ◀── POST /warehouse-milestones LOADING_STARTED ─ (timeline only)
 ◀── POST /warehouse-milestones LOAD_READY ────── (timeline only)
                                             601 → SLS-000001
 ◀── POST /dispatch-confirmations SLS-000001 ─
 mode HYBRID, trip READY_FOR_DISPATCH → APPLIED: IN_TRANSIT, orders IN_EXECUTION
 or: already dispatched by hand            → RECONCILED
 discrepancies → Control Tower
```

## 6. What v1 deliberately does not do

- It creates no order in the WMS. Orders reach each product from the ERP.
- It sends no invoices or delivery documents. That is a separate ERP contract.
- It has no split of one trip across loads (§2.3).
- It does not normalise SKU, lot, serial or handling unit in TMS. They stay in the stored document.
- It sends no inbound order cancellation to TMS. That is a separate change: v1.1 adds it (§7.5).
- TMS never reads a WMS API. Everything the WMS knows reaches TMS by the two POSTs in §4.

---

## 7. v1.1 (additive)

Decision: **ADR-025** (S-1…S-4 accepted, S-5 proposed). Material profile and load geometry: **ADR-022**.
Everything here is **additive**: same `/integration/v1` path, same authentication, same envelope; a
partner that speaks v1 keeps working and ignores the new fields. Each message names the overnight-pack
phase that implements it; until that phase lands, the endpoint answers `404` and the field is absent. 7.1 landed in phase 20 (V59); 7.2 (POD part) and 7.3 (stored) landed in phase 30 (V60); 7.4 and 7.5 landed in phase 35 (V61); 7.3 (compared) landed in phase 45.

| # | Message | Direction | Endpoint | Scope | Phase |
|---|---|---|---|---|---|
| 7.1 | `MATERIAL_PROFILE_UPSERT` | WMS → TMS | `PUT /integration/v1/material-profiles/{materialCode}`, `POST …/material-profiles/batch` | `integration.material:write` | 20 |
| 7.2 | Plan v1.1 + `DELIVERY_RESULT_RECORDED` | TMS → WMS | webhook + `GET /integration/v1/shipments/{n}` | `integration.shipment:read` | 30 (POD), 40 (geometry) |
| 7.3 | `DISPATCH_CONFIRMED` v1.1 fields | WMS → TMS | `POST /integration/v1/dispatch-confirmations` | `integration.dispatch:write` | 30 (stored), 45 (compared) |
| 7.4 | `ORDER_CANCELLED` | TMS → WMS | webhook + `GET /integration/v1/orders/{orderNumber}`, `GET /integration/v1/orders?status=CANCELLED&updatedSince=` | `integration.order:read` | 35 |
| 7.5 | `WAREHOUSE_ORDER_EVENT` | WMS → TMS | `POST /integration/v1/warehouse-order-events` | `integration.warehouse-order-event:write` | 35 |
| 7.6 | Reconciliation | WMS pulls | `GET /integration/v1/shipments?updatedSince=` (exists), 7.4's order list | as above | 35 (WMS side) |

Headers, versioning and errors are §4's: `Idempotency-Key` (`^[A-Za-z0-9._:-]{8,128}$`) on every
POST/PUT, `X-Correlation-Id`, RFC 9457 errors, `409` `idempotency-key-reused` when a key is reused with
another body. Batches answer `200`, or `207` when any item is `INVALID`.

### 7.1 `MATERIAL_PROFILE_UPSERT` - `PUT /integration/v1/material-profiles/{materialCode}`

The warehouse owns the physical profile of a material (D-02); TMS keeps a dated copy and copies it onto
new order lines (ADR-022 §2). `materialCode` is byte-for-byte the code the ERP puts on the order line
(URL-encoded in the path; no case folding).

```json
{
  "sourceSystem": "EWM_EBIM",
  "sourceUpdatedAt": "2026-10-18T14:05:12-05:00",
  "description": "VASO PLP-571 7 OZ",
  "lengthM": 0.105,
  "widthM": 0.105,
  "heightM": 0.175,
  "unitWeightKg": 0.012,
  "unitVolumeM3": 0.0019,
  "stackMode": "NESTABLE",
  "stackMaxQuantity": 100,
  "nestIncrementM": 0.012,
  "maxTopLoadKg": null,
  "orientationLocked": false
}
```

| Field | Rule |
|---|---|
| `sourceSystem` | The producer (`^[A-Z0-9_]{2,40}$`), stored |
| `sourceUpdatedAt` | Required. Older than the stored value → `STALE`, `200`, nothing applied |
| `lengthM`, `widthM`, `heightM` | Metres, `> 0`. One unit in its loading orientation. **The WMS converts only from a declared unit** (cm, mm); otherwise it sends `null` |
| `unitWeightKg`, `unitVolumeM3` | `> 0` or `null` (kg and m³ only) |
| `stackMode` | `NONE`, `STACKABLE`, `NESTABLE`, or `null` (not declared) |
| `stackMaxQuantity` | Units per stack, integer `> 0`; same meaning as EWM `md_item.stack_max_quantity` |
| `nestIncrementM` | Required when `NESTABLE`; `> 0`; `null` otherwise |
| `maxTopLoadKg` | Crush limit of the bottom unit, `> 0` or `null` |
| `orientationLocked` | Default `false` |
| absent vs `null` | An **absent** field keeps the stored value; an explicit **`null`** clears it |

**Response** (`201` created, `200` otherwise):

```json
{ "materialCode": "PLP-571", "outcome": "UPDATED", "version": 4,
  "sourceUpdatedAt": "2026-10-18T14:05:12-05:00" }
```

`outcome`: `CREATED`, `UPDATED`, `UNCHANGED` (same content), `STALE`. `400` for a shape error (e.g.
`NESTABLE` without `nestIncrementM`), `403` without the scope.

**Batch** - `POST /integration/v1/material-profiles/batch`, up to 500 items, each the body above plus
`materialCode`:

```json
{ "sourceSystem": "EWM_EBIM",
  "profiles": [ { "materialCode": "PLP-571", "sourceUpdatedAt": "2026-10-18T14:05:12-05:00",
                  "lengthM": 0.105, "widthM": 0.105, "heightM": 0.175, "stackMode": "NESTABLE",
                  "stackMaxQuantity": 100, "nestIncrementM": 0.012 } ] }
```

```json
{ "received": 1, "applied": 1, "refused": 0,
  "results": [ { "index": 0, "materialCode": "PLP-571", "outcome": "CREATED", "message": null } ] }
```

Item `outcome` adds `INVALID` (with `message`); the batch is not atomic, each item stands alone.

**Implemented (phase 20, V59, 2026-10-18).** Details fixed by the implementation
(`docs/domain/MATERIAL_PROFILE_V1.md`):

- `applied` counts `CREATED` + `UPDATED`; `refused` counts `INVALID`; `UNCHANGED` and `STALE` are neither.
  A batch item may carry its own `sourceSystem`; otherwise the batch's applies.
- `UNCHANGED` writes nothing, not even a newer `sourceUpdatedAt`; `version` moves only on a real change.
- Validation applies to the merged result: a PUT naming only `stackMode: NESTABLE` is `400` when the stored
  copy has no `nestIncrementM`. Codes are exact (`stackable` is `400`).
- A body that also names `materialCode` must name the path's code.

Example (PUT with an idempotency key):

```
PUT /integration/v1/material-profiles/PLP-571
Authorization: Bearer tmsc_<id>.tmss_<secret>
Idempotency-Key: ewm-matp-PLP-571-20261018T140512
Content-Type: application/json

{ "sourceSystem": "EWM_EBIM", "sourceUpdatedAt": "2026-10-18T14:05:12-05:00",
  "lengthM": 0.105, "widthM": 0.105, "heightM": 0.175, "stackMode": "NESTABLE",
  "stackMaxQuantity": 100, "nestIncrementM": 0.012 }

201 { "materialCode": "PLP-571", "outcome": "CREATED", "version": 0,
      "sourceUpdatedAt": "2026-10-18T19:05:12Z" }
```

The same key with another body answers `409` `idempotency-key-reused`; the same key and body replays the
first answer.

**Warehouse emitter (EWM, overnight-pack phase 25, EWM V56, interface `TMS_MATP`).** EWM always uses the
batch (a single material is a batch of one; a code with `/` cannot travel in the PUT path), with
`Idempotency-Key: ewm-matp:<eventId>` (`:r<n>` when an operator regenerates the message). It sends every
field, with an explicit `null` for what it does not know, so the copy here mirrors the owner;
`orientationLocked` is never sent (EWM does not model it). Metres, kilograms and cubic metres are
converted only from a declared unit (`M/MTR`, `CM/CMT`, `MM/MMT`, `KG/KGM/KGS`, `G/GRM`, `M3/MTQ`);
anything else travels as `null`. `stackMaxQuantity` travels only when the material's base unit is a count
and the number is whole. A resend of every active material is one batch per 500. `INVALID` and `STALE`
items are a successful delivery on the EWM side, counted in its monitor. EWM detail:
`EWM/docs/tms-sync/DESIGN.md` §2.3 and `EWM/docs/STACKING.md` §11.

#### 7.1.1 Physical fields on the order line (ERP -> TMS, `POST /integration/v1/orders[/batch]`)

Additive to `OrderUpsertRequest.lines[]`: `lengthM`, `widthM`, `heightM`, `stackMode`, `stackMaxQuantity`,
`nestIncrementM`, `maxTopLoadKg`, `orientationLocked`, with the profile's rules. When a line names any of
the first seven, **its block wins over the warehouse profile as a whole** for that line (D-02); when it
names none, the profile is copied onto the line with its version. Unit weight and volume are filled from
the profile only when the line omits them and the order declares no total for that measure. A later
profile change does not rewrite the order's lines, and a redelivery of the same order stays `UNCHANGED`.

```json
{ "materialCode": "PLP-1601", "materialDescription": "BALDE 20 L", "quantity": 240, "uom": "UN",
  "heightM": 0.29, "lengthM": 0.32, "widthM": 0.32, "stackMode": "NESTABLE", "nestIncrementM": 0.03,
  "stackMaxQuantity": 40 }
```

### 7.2 Plan v1.1 and `DELIVERY_RESULT_RECORDED` (TMS → WMS)

**New contract event.** `DELIVERY_RESULT_RECORDED` (same name in TMS and in the contract) joins §3.1:
a delivery result was recorded or corrected for an order of the trip (ADR-009). The WMS subscribes to it
like the other three; the body is a trigger and the WMS pulls `GET /integration/v1/shipments/{n}`. **It
never moves inventory in the WMS**: goods are already out (the 601); a failed or partial delivery is a
reason for a person to open a return, never an automatic one.

**Additive fields in the plan** (`orders[]`):

```json
{
  "orderNumber": "TO-00010011", "externalSource": "SAPB1_PE", "externalReference": "PED-1011",
  "stopSequence": 1, "weightKg": 420.5, "volumeM3": 1.8, "pallets": 2,
  "deliveryResult": "PARTIAL", "deliveredAt": "2026-09-28T10:42:00-05:00",
  "delivery": {
    "result": "PARTIAL",
    "deliveredAt": "2026-09-28T10:42:00-05:00",
    "receiverName": "M. Quispe",
    "evidenceCount": 2,
    "attempted": { "weightKg": 420.5, "volumeM3": 1.8, "pallets": 2 },
    "delivered": { "weightKg": 380.0, "volumeM3": 1.6, "pallets": 2 },
    "refused":   { "weightKg": 40.5,  "volumeM3": 0.2, "pallets": 0 },
    "lines": [
      { "lineNumber": 1, "materialCode": "SKU-100", "uom": "UN",
        "quantityAttempted": 100, "quantityDelivered": 90, "quantityRefused": 10 }
    ]
  },
  "geometry": { "stacks": 10, "effectiveVolumeM3": 0.1503, "floorAreaM2": 0.1103,
                "maxStackHeightM": 1.363, "unmeasuredLines": 0 }
}
```

| Field | Rule |
|---|---|
| `delivery` | `null` until a result is recorded. `result` ∈ `DELIVERED`, `PARTIAL`, `REJECTED`, `FAILED`, `NOT_ATTEMPTED` (V28). The flat v1 fields `deliveryResult`, `deliveredAt`, `deliveryReceiverName`, `deliveryNotes`, `evidenceCount` stay |
| `delivery.attempted/delivered/refused` | From V45; a block is `null` when not recorded - **never zero for unknown** |
| `delivery.lines[]` | From `order_delivery_line` (V45); `lineNumber` is the ERP line number. Empty when recorded per order only |
| evidence | Only the count. No URL, no bytes, no receiver identity document (unchanged rule) |
| `geometry` | ADR-022, after phase 40; `null` when the order has no measured line |

Stops already carry `serviceWindowStart`/`serviceWindowEnd` and orders `weightKg/volumeM3/pallets`
(v1, §3.3): a WMS should store them as **planned by TMS** and show them next to what it actually loads.

**Implemented (phase 30, V60).** `orders[].delivery` is returned by `GET /integration/v1/shipments/{n}`
exactly as above, except `geometry`, which stays absent until phase 40. Details of the implementation:

- `delivery` is `null` until a result is recorded; `delivery.result/deliveredAt/receiverName/evidenceCount`
  carry the same values as the flat v1 fields, which are unchanged.
- `attempted` / `delivered` / `refused` are `null` when the delivery recorded no amounts (V45). Inside a
  block, a single measure is `null` when only the others were recorded; nothing is ever defaulted to `0`.
- `lines[]` is read from `order_delivery_line` (V45), ordered by `lineNumber`; `lineNumber` and
  `materialCode` come from the order line, `uom` is the unit snapshotted on the delivery line. No TMS API
  writes per-line results yet, so today `lines` is `[]` unless such rows exist.
- The plan is assembled with batched reads (the trip's deliveries, then all their lines, then all the
  referenced order lines), never one query per order.
- `DELIVERY_RESULT_RECORDED` needed no change: it is already a webhook event type, a subscription can
  select it, and recording a delivery publishes it to the outbox and fans it out through the same signed
  webhook v1 (§3.2), with `type` = `DELIVERY_RESULT_RECORDED` and `resource` = the shipment.

**EWM consumer (phase 30, EWM V57).** The warehouse stores what TMS planned per order (`weightKg`,
`volumeM3`, `pallets`) and the stops with their windows next to its own load, and shows them as
"Planificado por TMS" beside what the load actually carries (kilograms and m³ only, nothing converted).
It informs and validates nothing. `delivery` (or, from a TMS without it, the flat v1 fields) is
projected per trip and order into `out_shipment_delivery`, read on the dispatch screen and as the last
stage of the outbound timeline. **It never posts stock** - the goods left with the goods issue - and a
`FAILED`/`PARTIAL`/`REJECTED` result only offers a link to a pre-filled return that a person saves.

### 7.3 `DISPATCH_CONFIRMED` - v1.1 fields

Additive request fields on `POST /integration/v1/dispatch-confirmations` (§4.1):

```json
{
  "vehicle": { "licensePlate": "B7K-812", "trailerLicensePlate": "C4X-991", "type": "FURGON" },
  "orders": [
    { "externalSource": "SAPB1_PE", "externalReference": "PED-1011", "status": "SHIPPED",
      "handlingUnits": 2, "weightKg": 790.0,
      "stacks": 10, "effectiveVolumeM3": 0.1503, "loadingMeters": 0.05, "geometryReason": null }
  ]
}
```

| Field | Rule |
|---|---|
| `vehicle.licensePlate` | **The tractor / truck plate** (the plan's `vehicleLicensePlate`). `VEHICLE_MISMATCH` compares only this |
| `vehicle.trailerLicensePlate` | The trailer, when there is one. Stored and shown; TMS has no trailer master and never compares it |
| `orders[].stacks`, `effectiveVolumeM3`, `loadingMeters` | Computed by the WMS with the same footprint rules (ADR-022 §3). `null` when the profile is incomplete, with `geometryReason` (e.g. `NEST_INCREMENT_MISSING`) |

TMS stores them (phase 30, V60) and, from phase 45, compares them with the plan as `GEOMETRY_VARIANCE`
(severity `INFO`): **never a 4xx, never a `MISMATCH`**. The response shape is unchanged.

**Implemented (phase 30, V60).** The fields are stored on `tms.external_dispatch.vehicle_trailer_plate`
and `tms.external_dispatch_order.stacks / effective_volume_m3 / loading_meters / geometry_reason`, all
nullable; a v1 document keeps them `NULL`. They are shown in the office's trip view
(`GET /api/v1/planning/trips/{id}/warehouse`: `documents[].vehicleTrailerPlate`,
`documents[].orders[].stacks / effectiveVolumeM3 / loadingMeters / geometryReason`). Only their shape is
validated, like every other field (`trailerLicensePlate` ≤ 20 characters, `geometryReason` ≤ 60, numbers
≥ 0; otherwise `400`). A different trailer, or a trailer carrying the planned plate, never raises
`VEHICLE_MISMATCH`. A v1 body without them fingerprints exactly as before, so redelivering a document
stored before V60 is still `UNCHANGED`, not a `409`.

**EWM emitter (phase 30, EWM V57).** `licensePlate` is the tractor the plan named (`vehicle_plate`);
`trailerLicensePlate` is what the dock typed (`trailer_number`), and `null` for a load created before
V57 (whose only plate was stored as the trailer and is sent as `licensePlate`, as in v1). `stacks` and
`effectiveVolumeM3` are sent only when **every** material of the order has a complete profile, a
counted base unit and dimensions convertible to metres; otherwise both are `null` with
`geometryReason` (`STACK_MODE_NOT_DECLARED`, `PROFILE_INCOMPLETE`, `NEST_INCREMENT_MISSING`,
`DIMENSION_UOM_NOT_DECLARED`, `DIMENSION_UOM_NOT_CONVERTIBLE`, `UOM_NOT_COUNTABLE`,
`QUANTITY_NOT_IN_BASE_UOM`, `QUANTITY_NOT_WHOLE`, `STACK_LIMIT_NOT_DECLARED`, `MAX_TOP_LOAD_UOM_DIFFERS`,
`UNIT_WEIGHT_MISSING`). The WMS has no vehicle dimensions, so it stacks without a height cap (`k` from
the per-stack maximum or the top load) and **`loadingMeters` is always `null`** from the EWM.

**Compared (phase 45).** `DispatchReconciler` compares each order's footprint with the one TMS planned and
adds one `GEOMETRY_VARIANCE` discrepancy (severity `INFO`) per measure that differs. The response shape,
the HTTP status (`201`/`200`), the `outcome`, the `verificationStatus` and the order's `matchResult` are
**unchanged by it**: a dispatch whose only differences are geometric stays `MATCHED` and dispatches the
trip as its mode allows.

| Measure | TMS planned | Compared when | Tolerance | `uom` |
|---|---|---|---|---|
| `stacks` | sum of the order's line stacks (V62) | whole order on the trip, **every** line measured, and the document sends a number | exact | `STACKS` |
| `effectiveVolumeM3` | sum of the lines' effective volume (V62) | as above | 5% relative | `M3` |
| `loadingMeters` | assignment's floor area / (trip load width x floor fill factor) | the order has floor on this trip (whole or share), the vehicle has a width, and the document sends a number | 5% relative | `LDM` |

A `null` on either side compares nothing - a v1 document, a `geometryReason` without figures, an order
TMS never measured or a vehicle without dimensions produce no discrepancy. The warehouse stacks without a
vehicle height, so its stacks are often fewer and taller than TMS's: that is precisely why the difference
informs and never refuses. Example response line:

```json
{ "code": "GEOMETRY_VARIANCE", "severity": "INFO", "orderReference": "PED-1011", "lineNumber": null,
  "planned": 10, "dispatched": 7, "uom": "STACKS",
  "detail": "The warehouse stacked the order in 7 stacks; TMS planned 10. Informative only." }
```

The office sees them in the trip's warehouse card (`GET /api/v1/planning/trips/{id}/warehouse`,
`documents[].discrepancies[]`) and, per order, as the chip "Huella distinta a la planificada
(informativa)" beside the order's match result.

### 7.4 `ORDER_CANCELLED` (TMS → WMS)

TMS cancelled **its transport order** (`POST /api/v1/orders/{id}/cancel`; only possible before the order is
planned). It is a **signal, not a command** (D-03): cancelling the customer's order belongs to the ERP.

Webhook envelope (§3.2 rules, signature and retries unchanged):

```json
{
  "apiVersion": "v1",
  "id": "5d2c8a10-7e0b-4c55-9d1f-3a2b1c0d9e8f",
  "type": "ORDER_CANCELLED",
  "occurredAt": "2026-10-18T09:12:44Z",
  "companyId": "6b1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "resource": { "type": "order", "id": "1f2e3d4c-5b6a-4978-8a9b-0c1d2e3f4a5b", "reference": "TO-00010011" }
}
```

Pull - `GET /integration/v1/orders/{orderNumber}` (scope `integration.order:read`; `404` for another
company's or an unknown order):

```json
{ "orderNumber": "TO-00010011", "externalSource": "SAPB1_PE", "externalReference": "PED-1011",
  "status": "CANCELLED", "cancelledAt": "2026-10-18T09:12:44Z",
  "cancelReason": "Cliente anuló la compra", "updatedAt": "2026-10-18T09:12:44Z" }
```

Reconciliation - `GET /integration/v1/orders?status=CANCELLED&updatedSince=2026-10-18T00:00:00Z&page=0&size=200`
returns a page of the same objects ordered by `updatedAt, orderNumber`.

**What the WMS does:** finds its order by (`externalSource`, `externalReference`), raises an alert on it
("TMS anuló el transporte de este pedido") and, only if its order is still open with nothing reserved,
offers a supervisor the cancel action. It never cancels by itself.

**Implementation notes (TMS, phase 35, V61).**

- The event is written to `tms.business_outbox_event` in the same transaction as the cancellation
  (`OrderService.cancel` → `shared.reference.BusinessEventPublisher` → `integration`
  `BusinessEventOutboxAdapter`) and fanned out to `tms.webhook_delivery` like a shipment event. The
  outbox row id is the envelope `id`. A rolled-back cancellation publishes nothing.
- `cancelledAt` is the order's last update when its status is `CANCELLED` (a cancelled order cannot be
  edited, planned or held into another state, so nothing touches the row afterwards). TMS has no
  separate column; `cancelledAt` and `cancelReason` are `null` for any other status.
- `GET /integration/v1/orders` accepts `status` as one or more order statuses (`NOT_READY`,
  `READY_FOR_PLANNING`, `PLANNED`, `IN_EXECUTION`, `DELIVERED`, `PARTIALLY_DELIVERED`, `DELIVERY_FAILED`,
  `CANCELLED`); absent means `CANCELLED`, as the shipment list defaults to `CONFIRMED`. An unknown value
  is `400`. Page size is capped at 200. Index `ix_transport_order_company_status_updated` serves it.

### 7.5 `WAREHOUSE_ORDER_EVENT` - `POST /integration/v1/warehouse-order-events`

The warehouse cancelled its order, or closed it short. TMS **never cancels by itself**: it places a hold
(ADR-014) and a person decides.

```json
{
  "sourceSystem": "EWM_EBIM",
  "events": [
    { "eventId": "2b9e7c1d-0a4f-4e3b-9c8d-7f6e5d4c3b2a", "type": "ORDER_CANCELLED",
      "externalSource": "SAPB1_PE", "externalReference": "PED-1014",
      "warehouseOrderNumber": "ORR-001-00000009", "warehouseCode": "CD01",
      "occurredAt": "2026-10-18T08:55:00-05:00", "reason": "Anulado por el ERP (ORR DELETE)" },
    { "eventId": "6a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", "type": "ORDER_SHORT_CLOSED",
      "externalSource": "SAPB1_PE", "externalReference": "PED-1011",
      "warehouseOrderNumber": "ORR-001-00000007", "warehouseCode": "CD01",
      "occurredAt": "2026-10-18T09:30:00-05:00", "reason": "Saldo anulado tras despacho parcial",
      "lines": [ { "lineNumber": 1, "materialCode": "SKU-100", "uom": "UN",
                   "requested": 100, "shipped": 97, "cancelled": 3 } ] }
  ]
}
```

| Field | Rule |
|---|---|
| `eventId` | The WMS's stable id; dedup key with `sourceSystem` (`DUPLICATE` on repeat) |
| `type` | `ORDER_CANCELLED` or `ORDER_SHORT_CLOSED` |
| `externalSource` + `externalReference` | The ERP order key (§2.2) |
| `lines[]` | Optional; for `ORDER_SHORT_CLOSED`, the quantities per ERP line |
| batch | 1-200 events |

**Response** (`200`, or `207` when any item is `INVALID`):

```json
{ "received": 2, "recorded": 2, "refused": 0,
  "results": [
    { "index": 0, "eventId": "2b9e7c1d-…", "result": "HOLD_PLACED", "orderNumber": "TO-00010014", "message": null },
    { "index": 1, "eventId": "6a1b2c3d-…", "result": "NOTED", "orderNumber": "TO-00010011", "message": null } ] }
```

| `result` | Meaning |
|---|---|
| `HOLD_PLACED` | `ORDER_CANCELLED` on an open order: blocking hold `INTEGRATION` / `WAREHOUSE_CANCELLED`. Out of planning; on a committed trip it blocks dispatch and raises the Control Tower advisory `ORDER_HOLD_ON_COMMITTED_TRIP` |
| `ALREADY_HELD` | An active hold with the same reason code exists |
| `NOTED` | `ORDER_SHORT_CLOSED`: non-blocking hold `WAREHOUSE_SHORT_CLOSED` with the figures; stops nothing |
| `ORDER_CLOSED` | The TMS order is delivered or cancelled; recorded only |
| `UNKNOWN_ORDER` | No TMS order with that key in this company; recorded |
| `DUPLICATE` | Same `sourceSystem` + `eventId` received before |
| `INVALID` | The item is malformed (`message` says why) |

**Implementation notes (TMS, phase 35, V61).**

- Each event is its own transaction: the hold and the `tms.warehouse_order_event` row commit together.
  A concurrent repeat of the same `eventId` loses on `uq_warehouse_order_event`, its hold is rolled
  back with it, and it answers `DUPLICATE`. `recorded` counts every item stored by the request
  (everything except `DUPLICATE` and `INVALID`); `orderNumber` is the TMS order the key resolved to.
- Per-item checks: `eventId` 1-128 of `[A-Za-z0-9._:-]`; `externalSource` and `externalReference`
  required, at most 80; `occurredAt` required and not more than 5 minutes in the future;
  `warehouseOrderNumber`/`warehouseCode` at most 80; `reason` at most 500; up to 500 `lines` with
  non-negative quantities.
- The hold's text names the warehouse order, the warehouse, the reason and, for a short close, the
  figures per line ("line 1 SKU-100: requested 100, shipped 97, cancelled 3 UN"), cut at 500 characters.
  The full event stays in `warehouse_order_event.payload`.
- `ALREADY_HELD` looks for an active hold with the same reason code, blocking or not. Holds are placed
  on any order that is not `DELIVERED` or `CANCELLED` - including `IN_EXECUTION` and the shortfall
  states, where it informs and stops nothing that is already on the road.
- The audit entry is `ORDER_HOLD_PLACED` with `source=INTEGRATION`, the credential id and, on a trip,
  `onCommittedTrip=true`. In the TMS UI the hold reads "Puesta por integración" with its reason code.
- Example `Idempotency-Key` the EWM uses: `ewm-ordcx:<eventId>`.

### 7.6 Reconciliation (WMS side)

A WMS that wants to repair lost webhooks polls, with its own watermark and **off by default**:

- `GET /integration/v1/shipments?status=CONFIRMED,CANCELLED&updatedSince=<w>` (§3.4, exists);
- `GET /integration/v1/orders?status=CANCELLED&updatedSince=<w>` (§7.4).

Every item it reads is applied exactly as if its webhook had arrived, so a replay is harmless.

### 7.7 Scopes after v1.1

| Scope | Endpoints |
|---|---|
| `integration.shipment:read` | §3 and §7.2 |
| `integration.dispatch:write` | §4.1 and §7.3 |
| `integration.warehouse-milestone:write` | §4.2 |
| `integration.material:write` | §7.1 |
| `integration.order:read` | §7.4 pull and list |
| `integration.warehouse-order-event:write` | §7.5 |

Webhook subscription of a warehouse link after v1.1: `SHIPMENT_CONFIRMED`, `SHIPMENT_CHANGED`,
`SHIPMENT_CANCELLED`, `DELIVERY_RESULT_RECORDED`, `ORDER_CANCELLED`.

### 7.8 Proposed, not implemented: dock appointments and truck ETA (ADR-025 §7, D-04)

The warehouse would own the calendar of its doors; TMS would request a pickup appointment and receive the
confirmation as a milestone, and send `TRUCK_ETA_UPDATED` from ADR-011's estimate. **Nothing of this exists
or is promised** until D-04 is decided.
