# TMS by EBIM - Outbound Shipment Integration API v1

Machine-to-machine read access to confirmed and cancelled **Shipments** (planned trips), for an
ERP, a WMS or a carrier portal to pull.

Introduced by job 08 of the overnight-v3 pack. It reuses the credential model, the tenancy rule
and the security chain job 06/07's inbound API already built
(`docs/integrations/INBOUND_API_V1.md`) rather than a second one, and builds on
`docs/domain/SHIPMENT_V2.md` - the shipment header this API exposes is the same one `TripView`
already resolves internally, in the API's own frozen vocabulary.

- Base path: `/integration/v1`
- Authentication: the same bearer credential the inbound API uses, with a new scope
- Transport: HTTPS only
- Content type: `application/json`
- Errors: RFC 9457 problem documents, the same catalogue as the inbound API's section 7
- Schema: Flyway migration `V20__shipment_outbox_and_outbound_scope.sql`

---

## 1. Why pull, and why an outbox instead of a webhook

**V1 is pull-only.** A partner calls `GET /integration/v1/shipments`, not the other way around.
TMS never opens an outbound connection to a partner's system, which sidesteps an entire class of
problems a push design would need to solve: destination allowlisting, retry/backoff, HMAC signing,
what happens when the partner's endpoint is down for a day. Those are real problems and V1 does
not pretend to have solved them - see [§8](#8-what-v1-deliberately-does-not-do) for what a webhook
sender would still need before it could ship.

**Underneath the pull API is a transactional outbox**, not a query straight against `tms.trip`.
`tms.shipment_outbox_event` (migration V20) gets one row in the *same* database transaction as the
trip state change it records - `PlanningRunService.confirmTrip` writes both the trip's `CONFIRMED`
status and its outbox row before either is committed (and, since Job 13, a `SHIPMENT` /
`SHIPMENT_CONFIRMED` row in `tms.audit_event` alongside them - see
`docs/domain/AUDIT_TRAIL_V1.md`; the outbox row is the partner-facing change feed below, the
audit row is the company's own business-audit trail, and both are written in the same
transaction as the confirmation itself). That is what makes the change feed
(`GET /integration/v1/shipments/events`, [§6](#6-the-change-feed)) trustworthy: a row here can
never describe a change that was itself rolled back, and a change can never commit without a row
here to describe it. A poller that only looked at `updated_at` would not have that guarantee - an
index range scan can observe a half-finished multi-row transaction, an outbox row cannot.

The two mechanisms compose: `GET /integration/v1/shipments?updatedSince=...` is the resource
itself, always correct and independently queryable; `GET /integration/v1/shipments/events` is a
cheap watermark a partner can poll instead of re-listing every confirmed shipment on every call.
Neither depends on the other continuing to work.

---

## 2. Tenancy and credentials

Identical to the inbound API - re-read `INBOUND_API_V1.md` sections 1-3 before integrating; this
document does not repeat them. In short: the company is resolved from the credential, never from
a header or a query parameter; a credential is bound to exactly one company for its lifetime; and
every authentication failure answers the same generic `401`.

### The new scope

| Scope | Grants |
|---|---|
| `integration.shipment:read` | `GET /integration/v1/shipments`, `/shipments/{shipmentNumber}`, `/shipments/events` |

Read-only, and a separate scope from the two write scopes on purpose: a partner that only needs to
read confirmed shipments (a carrier portal, a BI feed) is never handed the ability to write a
location or an order, and a partner that needs both is issued a credential with both scopes
explicitly. The credential lifecycle API is unchanged - an administrator adds this scope through
`PUT /api/v1/integration-clients/{id}` exactly as for the write scopes.

---

## 3. Shipments

### 3.1 `GET /integration/v1/shipments`

Scope: `integration.shipment:read`. The header row of every publishable shipment matching the
filter - never its stops or orders, for the reason `docs/domain/SHIPMENT_V2.md` gives the internal
board: a list must not fan out into one query per row for data nobody asked for. Batched
end-to-end, the same discipline `TripViewAssembler` already proves for the board
(`boardQueryCountDoesNotGrowWithTheNumberOfTrips`).

**Only `CONFIRMED` and `CANCELLED` shipments are ever returned.** A `DRAFT` trip is a planner's
work in progress - it has no shipment number an ERP should plan around yet, and exposing one that
might still be torn up would tell an external system about a commitment TMS itself has not made.

| Query parameter | Type | Notes |
|---|---|---|
| `status` | string, repeatable | One or more of `CONFIRMED`, `CANCELLED`. Defaults to `CONFIRMED` only - a partner that never asked for cancellations does not start seeing them the day one happens. |
| `updatedSince` | ISO-8601 instant | Only shipments touched at or after this instant. |
| `page`, `size` | integer | Standard paging, `size` capped at 200. |

There is no `sort` parameter. Results are always ordered oldest-touched first
(`updated_at ASC, id ASC`), which is what makes paging through a full backfill deterministic and
what a watermark-based poll needs - an arbitrary client-chosen sort would defeat both.

**Response** (`200`)

```json
{
  "content": [
    {
      "id": "8a1f0c2e-0000-4000-8000-000000000101",
      "companyCode": "CO-LIMA",
      "companyName": "EBIM Peru",
      "shipmentNumber": "SH-00000042",
      "planNumber": "PL-00000017",
      "planningDate": "2026-08-20",
      "status": "CONFIRMED",
      "originCode": "CD-LIMA",
      "originName": "Distribution Center Lima",
      "originLatitude": -12.046400,
      "originLongitude": -77.042800,
      "plannedDepartureAt": "2026-08-20T08:00:00-05:00",
      "carrierCode": "CR-001",
      "carrierName": "Transportes Andinos S.A.C.",
      "vehicleCode": "VH-014",
      "vehicleLicensePlate": "ABC-123",
      "vehicleTypeCode": "TRUCK-8T",
      "capacitySource": "SNAPSHOT",
      "maxWeightKg": 8000.00,
      "maxVolumeM3": 32.000,
      "maxPallets": 18,
      "usedWeightKg": 6120.00,
      "usedVolumeM3": 21.400,
      "usedPallets": 12.50,
      "weightUtilizationPct": 76.5,
      "volumeUtilizationPct": 66.9,
      "palletsUtilizationPct": 69.4,
      "stopCount": 3,
      "orderCount": 5,
      "version": 4,
      "createdAt": "2026-08-19T14:02:11Z",
      "updatedAt": "2026-08-20T06:15:47Z"
    }
  ],
  "page": 0,
  "size": 25,
  "totalElements": 1
}
```

A field is `null` exactly when it is genuinely unknown, never omitted: `maxWeightKg` is `null`
when `capacitySource` is `"NONE"` (no vehicle was ever assigned before cancellation), and a
utilization percentage is `null` when its limit is unknown or zero - never a division by zero,
never a manufactured `0%`/`100%`. See `docs/domain/CAPACITY_MODEL.md`.

### 3.2 `GET /integration/v1/shipments/{shipmentNumber}`

Scope: `integration.shipment:read`. One shipment with its ordered stops and the orders assigned to
it. `shipmentNumber` is the external identity (`SH-00000042`, migration V19) - a partner never
learns the internal trip id from anywhere but this response's own `shipment.id`.

**A `DRAFT` shipment answers `404`, exactly as one that does not exist at all.** There is
deliberately no way to distinguish "no such shipment" from "not published yet" - a plan that might
still change is never exposed, and telling a partner it exists but is not ready yet would leak the
same information a `DRAFT` status field would.

**Response** (`200`)

```json
{
  "shipment": {
    "id": "8a1f0c2e-0000-4000-8000-000000000101",
    "companyCode": "CO-LIMA",
    "companyName": "EBIM Peru",
    "shipmentNumber": "SH-00000042",
    "planNumber": "PL-00000017",
    "planningDate": "2026-08-20",
    "status": "CONFIRMED",
    "originCode": "CD-LIMA",
    "originName": "Distribution Center Lima",
    "originLatitude": -12.046400,
    "originLongitude": -77.042800,
    "plannedDepartureAt": "2026-08-20T08:00:00-05:00",
    "carrierCode": "CR-001",
    "carrierName": "Transportes Andinos S.A.C.",
    "vehicleCode": "VH-014",
    "vehicleLicensePlate": "ABC-123",
    "vehicleTypeCode": "TRUCK-8T",
    "capacitySource": "SNAPSHOT",
    "maxWeightKg": 8000.00,
    "maxVolumeM3": 32.000,
    "maxPallets": 18,
    "usedWeightKg": 6120.00,
    "usedVolumeM3": 21.400,
    "usedPallets": 12.50,
    "weightUtilizationPct": 76.5,
    "volumeUtilizationPct": 66.9,
    "palletsUtilizationPct": 69.4,
    "stopCount": 2,
    "orderCount": 3,
    "version": 4,
    "createdAt": "2026-08-19T14:02:11Z",
    "updatedAt": "2026-08-20T06:15:47Z"
  },
  "stops": [
    {
      "locationId": "b41d8e02-0000-4000-8000-0000000004711",
      "sequence": 1,
      "locationCode": "ST-4711",
      "locationName": "Acme Store Miraflores",
      "latitude": -12.121500,
      "longitude": -77.029700,
      "serviceWindowStart": "08:00:00",
      "serviceWindowEnd": "12:00:00"
    },
    {
      "locationId": "b41d8e02-0000-4000-8000-0000000004712",
      "sequence": 2,
      "locationCode": "ST-4712",
      "locationName": "Acme Store Surco",
      "latitude": null,
      "longitude": null,
      "serviceWindowStart": null,
      "serviceWindowEnd": null
    }
  ],
  "orders": [
    {
      "orderId": "c92f7a13-0000-4000-8000-000000000123",
      "orderNumber": "ORD-2026-000512",
      "externalSource": "ACME-ERP",
      "externalReference": "SO-2026-000123",
      "destinationCode": "ST-4711",
      "weightKg": 1560.00,
      "volumeM3": 5.200,
      "pallets": 3.00
    }
  ]
}
```

`stops[].latitude`/`longitude` are always both present or both `null` - a location that has never
been geocoded reports `null`, and a client renders it in the list without a map marker rather than
inventing a position. `orders[].externalSource`/`externalReference` echo back the identity the
sending system used in the inbound API (`docs/integrations/INBOUND_API_V1.md` section 6), when the
order arrived that way; both are `null` for an order created by hand in TMS.

---

## 4. What is deliberately absent from the payload

Consistent with `docs/domain/SHIPMENT_V2.md`, "What was deliberately not added":

- **No per-stop planned arrival time.** TMS has one optional `plannedDepartureAt` and no
  travel-time model; publishing a stop ETA would be inventing the routing `CLAUDE.md` defers by
  decision (OR-Tools).
- **No route/distance/duration.** A master route is a planner's suggestion, not a measured figure
  - see `SHIPMENT_V2.md`, "Route master interaction".
- **No order lines.** `orders[]` carries the totals actually assigned to the trip, not each
  order's line items - the same reason `PlannableOrder` never carries lines internally
  (`docs/domain/PLANNING_MANUAL_V1.md`, "Performance").

---

## 5. Errors

The same catalogue as `docs/integrations/INBOUND_API_V1.md` section 7, minus the write-only rows
(`idempotency-key-reused` cannot happen on a `GET`). The two this API actually produces:

| Status | `code` | Meaning |
|---|---|---|
| `400` | `malformed-request` | `status` named something other than `CONFIRMED`/`CANCELLED` |
| `404` | `resource-not-found` | No confirmed or cancelled shipment of that number exists in this company (or it does not exist at all, or it is still a draft - indistinguishable, see §3.2) |

`401`/`403` follow the inbound API exactly: an unrecognised or scopeless credential is `401`; an
authenticated credential that lacks `integration.shipment:read` is `403`.

---

## 6. The change feed

### `GET /integration/v1/shipments/events`

Scope: `integration.shipment:read`. Rows of `tms.shipment_outbox_event`, oldest first.

| Query parameter | Type | Notes |
|---|---|---|
| `since` | ISO-8601 instant | Only events at or after this instant. Omit for the whole history. |
| `page`, `size` | integer | Standard paging. |

**Response** (`200`)

```json
{
  "content": [
    {
      "id": "d5e2b8f1-0000-4000-8000-000000000701",
      "eventType": "SHIPMENT_CONFIRMED",
      "shipmentNumber": "SH-00000042",
      "occurredAt": "2026-08-20T06:15:47Z"
    }
  ],
  "page": 0,
  "size": 50,
  "totalElements": 1
}
```

Recommended partner-side routine: keep the `occurredAt` of the last event read as a watermark,
poll `.../events?since=<watermark>`, and for every row returned fetch
`GET /integration/v1/shipments/{shipmentNumber}` for the current state - the event says
*something* changed, never *what*, which is deliberate: the shipment resource is the single
source of truth for its own fields, and an event payload that duplicated them would be a second
copy that could drift from it.

### Only one event type has a source today

`eventType` is one of `SHIPMENT_CONFIRMED`, `SHIPMENT_CHANGED`, `SHIPMENT_CANCELLED` in the schema
(`ck_shipment_outbox_event_type`), but **only `SHIPMENT_CONFIRMED` is ever written**, and this is
not an oversight:

- `SHIPMENT_CHANGED` would require a business rule that mutates a `CONFIRMED` trip. None exists -
  `planning.domain.TripStatus`'s own class comment states a confirmed trip is locked against every
  mutation (`docs/domain/PLANNING_MANUAL_V1.md`, "State rules").
- `SHIPMENT_CANCELLED` would require cancelling a trip that was already published.
  `TripService.cancel`/`PlanningRunService.cancel` only ever cancel a `DRAFT` trip - one that, by
  `GET /shipments`' own rule, was never exposed in the first place.

Both values are accepted by the schema and by this document's client contract anyway, so the day
either business rule changes (a "recall a confirmed shipment" feature, for instance), emitting the
event is an application change, not a migration or a partner-facing breaking change. A partner's
deserializer should not reject an unrecognised `eventType` outright, for the same forward-
compatibility reason.

---

## 7. Configuration

No new configuration. `GET /integration/v1/shipments*` shares `tms.integration.*`
(`docs/integrations/INBOUND_API_V1.md` section 9) for nothing beyond the credential machinery -
`retain-payloads`/`max-batch-size` do not apply to a read with no body.

---

## 8. What V1 deliberately does not do

**No webhook sender.** `CLAUDE.md`'s brief for this job allows implementing the outbox alone and
documenting delivery as a next step rather than faking a system this scope has no room to build
correctly. A production webhook sender still needs, at minimum:

1. **Destination allowlisting/configuration per credential** - a URL to call, not just a scope to
   hold.
2. **HMAC signing** of the outgoing payload with a per-credential secret, so a receiver can verify
   the call came from TMS.
3. **Retry with backoff** independent of the confirming request - a partner endpoint being down for
   an hour must not block planning, and a delivery attempt must never run inside the planning
   transaction (`PlanningRunService.confirm` must stay fast and side-effect-free outside its own
   database).
4. **Delivery status per event** (`PENDING`/`DELIVERED`/`FAILED`, attempt count, last error) - the
   outward-facing counterpart of `tms.integration_request`'s inbox for the inbound side.
5. **A poison-message policy** - what happens to an event a receiver has rejected ten times running.

`tms.shipment_outbox_event` is deliberately shaped so that adding this later is additive: a
delivery-status table can reference it by id, and a dispatcher can be introduced as a scheduled job
that reads unconsumed rows - no change to the write path in `PlanningRunService` is required, since
the outbox row already exists as soon as the trip is confirmed.

**No `SHIPMENT_CHANGED` producer.** See [§6](#6-the-change-feed).

---

## 9. Tests

| Test | Proves | Needs Docker |
|---|---|---|
| `IntegrationShipmentApiTest` | Scope enforcement, tenancy (a shipment never crosses companies), status/date filtering, `404` for a draft trip, the change-feed shape | no |
| `PlanningRunConfirmationOutboxTest` (or equivalent under `planning`) | Confirming a run writes exactly one `SHIPMENT_CONFIRMED` row per trip confirmed, in the same transaction | yes (Testcontainers) |

---

## 10. Onboarding checklist for a partner

1. An administrator creates a credential (or re-scopes an existing one) with
   `integration.shipment:read`.
2. The partner confirms `GET /integration/v1/ping` reports the scope.
3. The partner does an initial backfill: `GET /integration/v1/shipments?status=CONFIRMED` paged to
   completion, recording the latest `updatedAt` seen.
4. Ongoing polling uses `GET /integration/v1/shipments/events?since=<watermark>` and fetches detail
   only for what changed ([§6](#6-the-change-feed)).
5. The partner treats `SHIPMENT_CHANGED`/`SHIPMENT_CANCELLED` as valid, if currently unused, event
   types in its deserializer (forward compatibility, [§6](#6-the-change-feed)).
