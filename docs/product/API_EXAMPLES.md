# TMS by EBIM — API examples

Copy-paste calls for a demo, a proof of concept, or a partner's first hour.

**No real credential appears anywhere in this file.** Every token, key and host below is a
placeholder or a shell variable, and the email domains use the reserved `.example` TLD. Do not
paste a live secret into this document, a ticket, or a chat.

- The **contracts** live in [`../integrations/`](../integrations/) — this file is the runnable
  companion to them, not a replacement.
- The register of every published interface, and what counts as a breaking change to each, is
  [`../integrations/API_CONTRACTS.md`](../integrations/API_CONTRACTS.md).

---

## 1. Two surfaces, two credentials

|  | Application API | Integration API |
|---|---|---|
| Base path | `/api/v1` | `/integration/v1` |
| For | the TMS browser client | partner machines: ERP, WMS, store systems, carriers, telematics |
| Auth | `Authorization: Bearer <Supabase JWT>` **and** `X-Company-Id: <uuid>` | `Authorization: Bearer <clientId>.<secret>` |
| Tenant | the header, validated against the caller's own memberships | a property of the credential — **there is no header to get wrong** |

Neither credential works on the other surface. An integration credential's company scope carries an
empty permission set, so no application endpoint can ever be satisfied by one.

```bash
export TMS_HOST='http://localhost:8080'

# Integration API — one bearer token, the two halves joined by a full stop.
export TMS_TOKEN='tmsc_9f3Kq2Lm0pXbT7wR4aZs1Q.tmss_REPLACE-WITH-THE-SECRET-SHOWN-ONCE'

# Application API — only if you are scripting the browser's own API.
export TMS_JWT='<a Supabase access token>'
export TMS_COMPANY='<company uuid from GET /api/v1/me>'
```

### Check the credential before anything else

```bash
curl -sS "$TMS_HOST/integration/v1/ping" -H "Authorization: Bearer $TMS_TOKEN" | jq
```

```json
{
  "clientId": "tmsc_9f3Kq2Lm0pXbT7wR4aZs1Q",
  "name": "Demo ERP",
  "companyCode": "DEMO-LIMA",
  "companyName": "Demo Logistica Lima",
  "companyTimeZone": "America/Lima",
  "scopes": ["integration.location:write", "integration.order:write"]
}
```

It writes nothing, needs no scope beyond being authenticated, and returns only facts the holder of
the credential already knows. If it fails, nothing below will work — and the failure is far clearer
here than inside a batch of two hundred orders.

Note that the company is identified by its **code**, never by a uuid. A partner speaks in codes
everywhere in this API, and publishing internal identifiers invites them to be used as keys, which
then have to keep working forever.

---

## 2. Inbound — a store or distribution centre

Scope: `integration.location:write`. This is an **upsert**: a sending system usually does not know
whether TMS has seen a store before, and making it find out first would add a round trip and a race
for no benefit.

```bash
curl -sS -X POST "$TMS_HOST/integration/v1/locations" \
  -H "Authorization: Bearer $TMS_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-store-4711-v1' \
  -d '{
    "code": "ST-4711",
    "name": "Tienda Miraflores",
    "type": "STORE",
    "roles": ["DESTINATION"],
    "address": "Av. Larco 1234",
    "district": "Miraflores",
    "province": "Lima",
    "department": "Lima",
    "country": "PE",
    "timeZone": "America/Lima",
    "latitude": -12.121500,
    "longitude": -77.029700,
    "serviceTimeMinutes": 25,
    "externalSystem": "DEMO-ERP",
    "externalReference": "4711"
  }' | jq
```

```json
{ "id": "b41d8e02-0000-4000-8000-0000000004711", "code": "ST-4711", "outcome": "CREATED" }
```

`outcome` is `CREATED`, `UPDATED` or `UNCHANGED`, and the status code follows: `201` for a location
that did not exist, `200` for one that did.

**Batch form** — up to 200 by default, hard ceiling 1,000. Items are **independent**: each runs in
its own transaction, so item 3 failing leaves items 1 and 2 committed. `200` when everything landed,
`207 Multi-Status` when anything was refused.

```bash
curl -sS -X POST "$TMS_HOST/integration/v1/locations/batch" \
  -H "Authorization: Bearer $TMS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"locations":[
        {"code":"ST-4712","name":"Tienda Surco","type":"STORE","roles":["DESTINATION"],
         "country":"PE","timeZone":"America/Lima",
         "externalSystem":"DEMO-ERP","externalReference":"4712"},
        {"code":"ST-4713","name":"Tienda San Isidro","type":"STORE","roles":["DESTINATION"],
         "country":"PE","timeZone":"America/Lima",
         "externalSystem":"DEMO-ERP","externalReference":"4713"}
      ]}' | jq
```

Each result carries the `index` **as it was sent**, so a failure correlates to the sender's own
record without relying on ordering guarantees.

---

## 3. Inbound — transport orders

Scope: `integration.order:write`. Identity is `(externalSource, externalReference)`, both required:
an order with no sending-system identity cannot be redelivered safely, so the API does not accept
one.

```bash
curl -sS -X POST "$TMS_HOST/integration/v1/orders/batch" \
  -H "Authorization: Bearer $TMS_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-nightly-release-2026-08-25' \
  --data @docs/product/demo-data/06-orders-inbound-batch.json | jq
```

A single order, with lines:

```bash
curl -sS -X POST "$TMS_HOST/integration/v1/orders" \
  -H "Authorization: Bearer $TMS_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-so-2026-000101' \
  -d '{
    "externalSource": "DEMO-ERP",
    "externalReference": "SO-2026-000101",
    "originCode": "CD-LIMA",
    "destinationCode": "ST-4711",
    "customerName": "Retail Demo S.A.C.",
    "customerReference": "PO-88311",
    "serviceDate": "2026-08-25",
    "priority": "NORMAL",
    "requestedWindowStart": "08:00",
    "requestedWindowEnd": "12:00",
    "lines": [
      { "materialCode": "MAT-1001", "materialDescription": "Bebida gaseosa 500 ml - caja x24",
        "quantity": 60, "uom": "CJ", "unitWeightKg": 14.5, "unitVolumeM3": 0.028, "palletQuantity": 2 }
    ],
    "markReadyForPlanning": true
  }' | jq
```

```json
{
  "id": "c92f7a13-0000-4000-8000-000000000101",
  "orderNumber": "ORD-2026-000512",
  "status": "READY_FOR_PLANNING",
  "outcome": "CREATED"
}
```

**A header-only order** — the shape an ERP produces constantly, "one order, 3,200 kg, 5 pallets, no
detail":

```bash
  -d '{ "externalSource":"DEMO-ERP", "externalReference":"SO-2026-000104",
        "originCode":"CD-LIMA", "destinationCode":"ST-4714",
        "serviceDate":"2026-08-25", "priority":"NORMAL",
        "declaredWeightKg":3200.0, "declaredVolumeM3":11.0, "declaredPallets":5,
        "markReadyForPlanning":true }'
```

The rule behind those two: **lines win wherever they speak**, a declared value fills a measure the
lines are silent about, and a declaration that contradicts the lines by more than 1% is an error
rather than a silent preference. No caller may ever send the effective totals — the server computes
them, and planning reads only what the server produced.

### Idempotency, twice over

1. **By business identity.** Re-sending the same `(externalSource, externalReference)` updates
   rather than duplicating.
2. **By `Idempotency-Key`, optionally.** Repeat the same key with the **same** payload and TMS
   replays the original response verbatim — same body, same status code — with
   `X-Idempotent-Replay: true`. That is for the sender who never learned the outcome.

Repeating a key with a *different* payload is refused with
`urn:tms:problem:idempotency-key-reused`, because answering it with the first body would hide a
client bug.

An order already **planned** or **cancelled** is not silently rewritten: the call fails `409` naming
its status. A sending system changing an order a planner has already put on a truck is describing a
real operational problem, and answering "accepted" would hide it.

---

## 4. Outbound — reading shipments

Scope: `integration.shipment:read`.

```bash
# Everything confirmed, oldest-touched first — a deterministic full backfill.
curl -sS "$TMS_HOST/integration/v1/shipments?size=50" \
  -H "Authorization: Bearer $TMS_TOKEN" | jq

# Incremental afterwards.
curl -sS "$TMS_HOST/integration/v1/shipments?updatedSince=2026-08-25T00:00:00Z" \
  -H "Authorization: Bearer $TMS_TOKEN" | jq

# One shipment, with its ordered stops and assigned orders.
curl -sS "$TMS_HOST/integration/v1/shipments/SH-00000001" \
  -H "Authorization: Bearer $TMS_TOKEN" | jq
```

A **draft** trip answers `404`, exactly as one that does not exist: a plan that might still change is
never exposed.

Paging is the same everywhere: `?page=0&size=50&sort=<field>,asc`. `size` defaults to 25, is capped
at 200, and an oversized request is clamped rather than rejected.

---

## 5. Outbound — the change feed, by polling

The cheap watermark pattern: ask what changed, then fetch detail only for what did.

```bash
SINCE='2026-08-25T00:00:00Z'
curl -sS "$TMS_HOST/integration/v1/shipments/events?since=$SINCE&size=100" \
  -H "Authorization: Bearer $TMS_TOKEN" | jq
```

Event vocabulary: `SHIPMENT_CONFIRMED`, `SHIPMENT_READY`, `SHIPMENT_DISPATCHED`,
`SHIPMENT_COMPLETED`, `SHIPMENT_CANCELLED`, `DELIVERY_RESULT_RECORDED`, the five `TENDER_*`
transitions, and `SHIPMENT_CHANGED` which is **reserved and produced by nothing today**.

> A partner's deserialiser must not reject an unknown enum value on sight. Reserved values exist
> precisely so that the day something starts producing them is not a breaking change.

Keep the watermark from the newest event you processed, not from your own clock.

---

## 6. Outbound — the same feed, pushed

Webhooks read the **same** transactional outbox and use the **same event ids**, so a partner can run
polling and webhooks together during a cutover with neither a gap nor a duplicate.

Configured from the Integration Hub (`/settings/integrations`), not from this API. The feature is on
exactly when `TMS_WEBHOOK_SECRET_KEY` is set — no separate `enabled` flag, because without a key TMS
cannot store a signing secret and therefore cannot have a subscription to deliver to.

### What arrives at your endpoint

```http
POST /your/endpoint HTTP/1.1
Content-Type: application/json; charset=utf-8
X-TMS-Event-Id: 3f0a0e2c-9c4b-4b1e-9a6f-0f6b1c2d3e4a
X-TMS-Event-Type: SHIPMENT_DISPATCHED
X-TMS-Delivery-Id: 7c1d9e40-2b3a-4c5d-8e6f-9a0b1c2d3e4f
X-TMS-Delivery-Attempt: 1
X-TMS-Signature: t=1787654321,v1=6f1c...
X-Correlation-Id: wh-dispatch-4f21
User-Agent: TMS-by-EBIM-Webhooks/1.0

{
  "apiVersion": "v1",
  "id": "3f0a0e2c-9c4b-4b1e-9a6f-0f6b1c2d3e4a",
  "type": "SHIPMENT_DISPATCHED",
  "occurredAt": "2026-08-25T13:40:12Z",
  "companyId": "6b1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "resource": { "type": "shipment", "id": "9a6f0f6b-1c2d-3e4a-5b6c-7d8e9f0a1b2c",
                "reference": "SH-00000001" }
}
```

**The envelope carries no business detail on purpose.** It says what happened, to what, and when;
you then read what TMS believes *now*. A retry three hours later cannot deliver a three-hour-old
snapshot, and customer names and addresses never travel to a URL an administrator typed.

### Verifying the signature

The signed material is `"<t>.<raw request body>"` — the timestamp from the header, a full stop, and
the body **exactly as received**, before any JSON parsing or re-serialisation.

```python
import hashlib, hmac, time

def verify(raw_body: bytes, signature_header: str, secret: str, tolerance_s: int = 300) -> bool:
    parts = dict(p.split("=", 1) for p in signature_header.split(","))
    t, v1 = parts["t"], parts["v1"]

    # 1. Replay window. The timestamp is inside the signed material, so editing it breaks the MAC.
    if abs(time.time() - int(t)) > tolerance_s:
        return False

    # 2. Recompute over "<t>.<rawBody>" and compare in constant time.
    expected = hmac.new(secret.encode(), f"{t}.".encode() + raw_body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, v1)
    # 3. Only now parse the body.
```

Signing the body alone would produce a signature valid forever, and anyone who captured one delivery
could replay it at any later moment.

### What your endpoint must do

| Rule | Why |
|---|---|
| **Answer quickly** — acknowledge and queue, do not work inside the request | One call's budget is 10 seconds by default. A receiver that needs longer is doing work in the wrong place |
| **Deduplicate on `id`** | Delivery is **at-least-once**. A node dying mid-attempt lets its lease expire and the delivery is tried again |
| **Do not assume order** | A retry of an earlier event can land after a later one. Order by `occurredAt`, which is the business fact's own time |
| **Ignore fields you do not recognise** | Fields may be added within `v1`; a field is never removed or given a new meaning |
| **Be reachable over HTTPS** | `http://` targets and private/loopback addresses are refused unless a development flag is set, and the application warns loudly on startup when one is |

Failures are retried six times by default, from one minute and doubling to a thirty-minute cap —
just over an hour in total. After ten consecutive exhausted deliveries the endpoint is **suspended**
and says so on the screen, rather than a queue filling up silently.

---

## 7. A carrier answering a tender

Scope: `integration.tender:respond`, and the credential must be **bound to a carrier** — that scope
is the one that is not sufficient on its own. Issue it from the Integration Hub: ticking the scope
reveals the carrier field.

```bash
export CARRIER_TOKEN='tmsc_....tmss_....'

# What have we been offered?
curl -sS "$TMS_HOST/integration/v1/tenders" \
  -H "Authorization: Bearer $CARRIER_TOKEN" | jq

# Yes.
curl -sS -X POST "$TMS_HOST/integration/v1/tenders/SH-00000001/response" \
  -H "Authorization: Bearer $CARRIER_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: carrier-answer-SH-00000001-1' \
  -d '{"decision":"ACCEPTED","reason":"Confirmado, unidad ABC-101"}' | jq

# No — and here `reason` is required.
curl -sS -X POST "$TMS_HOST/integration/v1/tenders/SH-00000002/response" \
  -H "Authorization: Bearer $CARRIER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"decision":"REJECTED","reason":"Sin unidad refrigerada disponible ese dia"}' | jq
```

`decision` is `ACCEPTED` or `REJECTED` and nothing else. A refusal with no reason is the answer that
helps the shipper's planner least, exactly when they have to decide what to do next.

The answer is recorded with a `response_source` saying it came from the carrier's system rather than
from a planner typing it in — and **at most one carrier can ever have accepted a given shipment**,
enforced by a partial unique index that no sequence of retries can defeat.

---

## 8. A telematics feed reporting positions

Scope: `integration.tracking:write`.

```bash
curl -sS -X POST "$TMS_HOST/integration/v1/tracking/positions" \
  -H "Authorization: Bearer $TRACKING_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "provider": "demo-feed",
    "positions": [
      { "shipmentNumber": "SH-00000001",
        "occurredAt": "2026-08-25T14:02:11Z",
        "latitude": -12.100000, "longitude": -77.020000,
        "speedKph": 34, "headingDegrees": 187,
        "externalVehicleReference": "TRK-ABC-101",
        "correlationReference": "ping-99812" }
    ]
  }' | jq
```

`provider` sits on the **batch**, not on each position: it identifies the sender, and one credential
legitimately relays several upstream feeds — a 4PL forwarding two carriers' telematics.

Three things worth telling a provider up front:

- **Accepted is not stored.** TMS keeps at most one position per 60 seconds per shipment and feed;
  anything denser is accepted and dropped rather than refused, so nobody has to reconfigure their
  equipment to talk to us.
- **A stale or future timestamp is refused.** More than 24 hours old, or more than 5 minutes ahead
  of our clock.
- **No raw payload is ever stored.** Send a `correlationReference`, never a document — a telematics
  payload carries the driver's identity and their movements off shift, and TMS has no purpose for
  either.

Read them back on the application API: `GET /api/v1/tracking/trips/{tripId}`.

---

## 9. When something is refused

Every failure, from a filter or a controller, is one RFC 9457 document:

```json
{
  "type": "urn:tms:problem:resource-not-found",
  "title": "Resource not found",
  "status": 404,
  "detail": "No destination with code 'ST-9999' exists in this company.",
  "instance": "/integration/v1/orders",
  "code": "resource-not-found",
  "timestamp": "2026-08-25T13:41:07.123Z",
  "correlationId": "erp-nightly-8f31"
}
```

**Branch on `type` or `code`, never on `detail`** — the first two are stable, the third is human
prose and may be reworded.

| `code` | Status | What the sender should do |
|---|---|---|
| `unauthenticated` / `invalid-token` | 401 | Fix the credential. Do not retry |
| `access-denied` | 403 | The scope is missing. Ask for it; do not retry |
| `validation-failed` | 400 | Fix the payload. Never retry unchanged |
| `resource-not-found` | 404 | A code you sent does not exist in this company |
| `conflict` | 409 | A business rule refused it — read `detail`, do not blind-retry |
| `idempotency-key-reused` | 409 | Your client sent two different bodies under one key |
| `storage-unavailable` / `feature-not-configured` | 503 | An optional capability is not configured here. Stop offering it; retrying will not help |
| `internal-error` | 500 | Retry with backoff and quote `correlationId` |

### Correlation ids

Send `X-Correlation-Id` (or `X-Request-Id`, accepted as an alias because most gateways already emit
one). It is sanitised, echoed on the response, written to every log line of the request and included
in the error document — so one value quoted in a support ticket is findable on both sides. It is
generated when you do not send one.

---

## 10. Credential hygiene

| Rule | Consequence |
|---|---|
| The secret is shown **once**, by the response that creates or rotates it | TMS stores a one-way hash. There is no "show me the secret again" |
| The client id is **not** secret | `tmsc_…` is safe in a log and in a support ticket. `tmss_…` never is |
| Rotation has a grace window | The superseded secret keeps working for seven days by default, so a partner redeploys without a coordinated cutover. A rotation responding to a suspected leak passes `graceHours=0` and ignores it |
| One credential, one company | A partner writing into two companies is issued two credentials. There is no operation that re-points one |
| Scopes are closed | Adding an integration capability takes a migration **and** an enum constant, deliberately, so a new capability cannot be granted by a typo in a payload |
| Webhook secrets rotate with **no** grace window | TMS *produces* those signatures rather than verifying them, and sending two would mean the receiver had to accept either — which is exactly the property a rotation removes |

---

## 11. Onboarding a partner, in order

1. Issue a credential in the Integration Hub with **only** the scopes they need. Hand over the
   secret once, through a channel that is not this repository.
2. They call `GET /integration/v1/ping` and see the company and scopes they expect.
3. They send **one** location and **one** order, and check the outcome.
4. They re-send both, unchanged, and confirm nothing duplicated.
5. They batch a realistic day.
6. They poll `GET /shipments/events?since=` for one operating day — or register a webhook endpoint
   and verify a signature before anything else.
7. Both sides agree what a `409` on a planned order means operationally, because that one is a
   conversation and not a bug.

Every delivery, successful or not, is visible to the customer's administrator in the Integration Hub
under **Entradas**, with its outcome and its correlation id.
