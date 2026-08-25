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

**This API is pull-only, and stays that way.** A partner calls `GET /integration/v1/shipments`, not
the other way around. That sidesteps an entire class of problems a push design has to solve:
destination allowlisting, retry/backoff, HMAC signing, what happens when the partner's endpoint is
down for a day.

> **Since job 13 there is also a push option**, built on this same outbox and solving exactly those
> problems: `docs/integrations/WEBHOOKS_V1.md`. It changes nothing here. A customer registers an
> endpoint, TMS POSTs each selected event to it signed and retried, and the polling endpoints below
> keep working identically - same facts, same event ids, so moving between them costs neither a gap
> nor a duplicate. [§8](#8-what-v1-deliberately-does-not-do) records what the two designs traded.

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

**Every state except `DRAFT` is returned.** A `DRAFT` trip is a planner's work in progress - it has
no shipment number an ERP should plan around yet, and exposing one that might still be torn up
would tell an external system about a commitment TMS itself has not made. Everything else is an
outcome of something already published.

Migration V25 added `READY_FOR_DISPATCH`, `IN_TRANSIT` and `COMPLETED`
(`docs/domain/TRIP_EXECUTION_V1.md`). The change is **additive and non-breaking**: the default
below is unchanged, so a partner that never touched `status` keeps seeing exactly what it saw
before. A shipment that moves to `IN_TRANSIT` simply stops matching `status=CONFIRMED`, which is
what "give me what is still only planned" should mean.

| Query parameter | Type | Notes |
|---|---|---|
| `status` | string, repeatable | One or more of `CONFIRMED`, `READY_FOR_DISPATCH`, `IN_TRANSIT`, `COMPLETED`, `CANCELLED`. Defaults to `CONFIRMED` only - a partner that never asked for the rest does not start seeing them the day one happens. |
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
      "readyAt": null,
      "actualDepartureAt": null,
      "actualCompletionAt": null,
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
    "readyAt": null,
    "actualDepartureAt": null,
    "actualCompletionAt": null,
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
      "pallets": 3.00,
      "deliveryResult": "PARTIAL",
      "deliveredAt": "2026-08-20T09:47:00Z",
      "deliveryReceiverName": "R. Diaz",
      "deliveryNotes": "One pallet refused, damaged film",
      "evidenceCount": 2
    }
  ]
}
```

`stops[].latitude`/`longitude` are always both present or both `null` - a location that has never
been geocoded reports `null`, and a client renders it in the list without a map marker rather than
inventing a position. `orders[].externalSource`/`externalReference` echo back the identity the
sending system used in the inbound API (`docs/integrations/INBOUND_API_V1.md` section 6), when the
order arrived that way; both are `null` for an order created by hand in TMS.

### The five delivery fields (migration V28)

Additive: a partner that integrated before them keeps seeing exactly what it saw.

| Field | Notes |
|---|---|
| `deliveryResult` | `DELIVERED`, `PARTIAL`, `REJECTED`, `FAILED`, `NOT_ATTEMPTED`, or **`null`** when nobody has recorded the delivery yet - which is every order on a shipment that has not run. `null` means *not known* and never *not delivered*; `NOT_ATTEMPTED` is the value that means the goods never left the vehicle |
| `deliveredAt` | When the goods changed hands. Always present for `DELIVERED`/`PARTIAL`, never for `NOT_ATTEMPTED`, optional for the rest |
| `deliveryReceiverName` | Who took them, where a name was recorded. Only ever present on a result reached with somebody present |
| `deliveryNotes` | Why it fell short. Always present for `PARTIAL`, `REJECTED` and `FAILED`, which TMS refuses to record without an explanation |
| `evidenceCount` | How many proof-of-delivery artefacts are on file. A **count and not links**: the bytes are served only through an authenticated, company-scoped TMS request, and a URL in this payload would be a second, quieter way to reach a customer's signed delivery note |

The receiver's identity **document** is deliberately not published. No partner has asked for it, and
it is the more sensitive half of the pair - see `docs/domain/PROOF_OF_DELIVERY_V1.md`.

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
| `400` | `malformed-request` | `status` named something other than the five publishable states (`DRAFT`, notably, is refused rather than silently ignored) |
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

### Six event types have a source; one still does not

`ck_shipment_outbox_event_type` (V20, widened by V25 and V28) accepts seven values. Six are
written:

| `eventType` | Written by |
|---|---|
| `SHIPMENT_CONFIRMED` | `PlanningRunService.confirm` |
| `SHIPMENT_READY` | `TripExecutionService.markReadyForDispatch` |
| `SHIPMENT_DISPATCHED` | `TripExecutionService.dispatch` |
| `SHIPMENT_COMPLETED` | `TripExecutionService.complete` |
| `SHIPMENT_CANCELLED` | `TripService.cancel`, for a trip that had already been confirmed |
| `DELIVERY_RESULT_RECORDED` | `TripDeliveryService.record` (V28) |
| `SHIPMENT_CHANGED` | *nothing* |

`DELIVERY_RESULT_RECORDED` is the first value that is **not** a trip-state change, and it is the
reason this column was built as an event type rather than a status: a partner told a shipment is
`IN_TRANSIT` learns nothing more until it completes, and "an order on it was refused" is exactly
the fact an ERP has to act on before then - it is what triggers a credit note, a re-delivery or a
customer call.

It is deliberately **one** event and not one per result. The row carries the shipment number and
nothing else, as every row here does; the partner re-reads the shipment and finds `deliveryResult`
on the order it is about (§3.2). Freezing today's five results into the wire contract would buy
nothing and cost a version bump the first time a sixth appeared.

One event is written per recording, so a corrected delivery produces a second one - which is
correct: the outcome a partner was told about has changed.

`SHIPMENT_CANCELLED` is exactly the case this document predicted: "the day either business rule
changes... emitting the event is an application change, not a migration". Cancelling a *confirmed*
trip became legal in V25, so a partner that was handed a shipment now learns when it is withdrawn.
`ShipmentEventPublisher` is the single place that pairs an outbox row with its audit event, so the
feed and the audit trail cannot tell different stories.

`SHIPMENT_CHANGED` still has no source: the committed states remain locked against edits to what a
shipment *carries*, so TMS cannot yet produce a change to publish.

A client must therefore already tolerate event types it has not seen before - a new one is added
by an application change, not by a version bump of this contract.

A partner's deserializer must not reject an unrecognised `eventType` outright, for that same
forward-compatibility reason.

---

## 7. Configuration

No new configuration. `GET /integration/v1/shipments*` shares `tms.integration.*`
(`docs/integrations/INBOUND_API_V1.md` section 9) for nothing beyond the credential machinery -
`retain-payloads`/`max-batch-size` do not apply to a read with no body.

---

## 8. What V1 deliberately does not do

**No webhook sender - superseded by job 13.** This section originally listed the five things a
production webhook sender would need before it could ship, and said `tms.shipment_outbox_event` was
shaped so that adding one later would be additive. Migration V35 built it, and the prediction held:
**no change to the write path was required.** `docs/integrations/WEBHOOKS_V1.md` is the contract; the
five points are answered as follows.

| What §8 said was still needed | Where it lives now |
|---|---|
| 1. Destination configuration - a URL to call, not just a scope to hold | `tms.webhook_subscription`, configured from the Integration Hub. Deliberately *not* per credential: a receiving system may never call TMS at all (`WEBHOOKS_V1.md` §1) |
| 2. HMAC signing with a per-destination secret | `X-TMS-Signature: t=…,v1=…`, HMAC-SHA-256 over `"<t>.<raw body>"` (§5). The timestamp is inside the MAC so a captured delivery cannot be replayed |
| 3. Retry with backoff, never inside the planning transaction | Three phases, none of which spans the network (§6). The confirming transaction does two indexed inserts and returns; the dispatcher retries on a published ladder |
| 4. Delivery status per event | `tms.webhook_delivery` (`PENDING`/`PROCESSED`/`FAILED`, attempt count, last error) plus `tms.webhook_delivery_attempt`, which keeps *every* attempt - the outward counterpart of the inbound inbox |
| 5. A poison-message policy | The schedule is exhausted after `max-attempts`; a subscription whose deliveries keep exhausting is suspended after ten in a row, and an operator retries one at a time once their side is fixed (§6) |

The polling API in this document is unchanged and remains fully supported. Both mechanisms read the
same outbox and use the same event ids, so a partner can move from polling to push, or run both
during a cutover, without a gap or a duplicate.

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
