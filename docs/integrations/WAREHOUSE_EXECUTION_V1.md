# TMS by EBIM - Warehouse Execution Contract v1 (TMS <-> WMS)

The public contract between TMS and a warehouse management system: EWM by EBIM, or any third-party
WMS. It covers the transport plan going to the warehouse, and what physically left coming back.

| Direction | Status |
|---|---|
| **TMS → WMS** (transport plan) | **Available today.** Aliases of existing mechanisms: `OUTBOUND_SHIPMENT_V1.md`, `WEBHOOKS_V1.md`. Three additive fields are planned (§3.3) |
| **WMS → TMS** (dispatch, milestones) | **Implemented** with migrations V52/V53 (ADR-013) |

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
| `TRANSPORT_PLAN_UPDATED` | `SHIPMENT_CHANGED` | The driver changes on a `CONFIRMED` / `READY_FOR_DISPATCH` trip | **reserved since V20; producer planned** |
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
| `originExternalReference`, `routeCode`, `driver{…}`, `orders[].stopSequence` | **Planned, additive.** They arrive as new fields in v1, and a consumer must ignore unknown fields |

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
- It sends no inbound order cancellation to TMS. That is a separate change.
- TMS never reads a WMS API. Everything the WMS knows reaches TMS by the two POSTs in §4.
