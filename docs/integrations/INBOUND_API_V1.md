# TMS by EBIM - Inbound Integration API v1

Machine-to-machine intake of **Locations/Stores** and **Transport Orders**.

This API is for server-to-server delivery from an ERP, a WMS or a store system. It has no
browser session, no user password and no Supabase key anywhere in the picture. It is a separate
URL space, a separate security chain and a separate credential vocabulary from the application
API that the TMS web client uses.

- Base path: `/integration/v1`
- Authentication: a bearer credential issued per company (never a user token)
- Transport: HTTPS only
- Content type: `application/json`
- Errors: RFC 9457 problem documents

Related: `docs/architecture/ADR-003-multitenancy-company-scope.md` (tenancy),
`docs/architecture/ADR-005-tenant-rls-runtime-role.md` (RLS), `docs/security/RLS_STRATEGY.md`.
The schema lives in Flyway migration `V18__integration_clients_and_inbox.sql`.

---

## 1. Tenancy: the company is not something you send

**The company an inbound request operates in is a property of the credential.** It is resolved
server-side from the credential presented, and it cannot be influenced by a header, a path
parameter or a field in the body.

This is the strongest form of the rule ADR-003 states. The user-facing API asks the caller to
select a company with `X-Company-Id` and then validates that selection against the caller's
memberships. Here the caller is never asked, so there is nothing to validate and nothing to get
wrong.

Concretely:

- A credential is bound to exactly one company at creation and there is no operation that
  re-points it. A partner who must write into two companies is issued two credentials.
- `X-Company-Id` is not part of this API's contract. If it is sent and does **not** match the
  credential's own company, the request is refused with `403` rather than ignored - a partner
  sending it has misunderstood the API, and silently overriding them would let that
  misunderstanding survive until the day it matters.
- Every statement after authentication runs on the tenant-scoped PostgreSQL role with the
  credential's company published, so RLS refuses a cross-company row even if an application query
  forgot its predicate.
- The database enforces it structurally too: `integration_request` carries a composite foreign
  key `(integration_client_id, company_id)` into `integration_client (id, company_id)`, so an
  inbox row belonging to company A under a credential of company B cannot be stored.

Cross-tenant behaviour is covered by explicit tests - see [§10](#10-tests).

---

## 2. Credentials

### 2.1 Shape

A credential is two halves:

| Part | Shape | Secret? |
|---|---|---|
| Client id | `tmsc_` + 22 base64url chars (128 bits) | No. Safe to log, safe in a support ticket. |
| Secret | `tmss_` + 43 base64url chars (256 bits) | **Yes.** Shown once, never recoverable. |

They are presented together as one bearer token, joined by a full stop:

```
Authorization: Bearer tmsc_9f3Kq2Lm0pXbT7wR4aZs1Q.tmss_Hn8vQ2xR7bLpY4tW9cE6mK1sD3fG0jN5uZaI8oPqTrY
```

The full stop is outside the base64url alphabet, so splitting the token is never ambiguous. The
`tmsc_`/`tmss_` prefixes are not decoration: they make a leaked value recognisable to a secret
scanner and let the server reject a malformed credential before it touches the database.

### 2.2 How the secret is stored

**Only a one-way hash is persisted.** The plaintext secret exists exactly once - in the HTTP
response that created or rotated it - and is never stored, never logged, never echoed in an error
message and never recoverable. A partner who loses it rotates.

The scheme is **SHA-256, lower-case hex**, with **constant-time comparison** at verification
(`MessageDigest.isEqual`). `integration_client.secret_algorithm` records the scheme per row, so a
future migration to a different one can re-hash lazily rather than invalidating every partner
credential at once.

#### Why SHA-256 and not bcrypt/argon2

This is a deliberate decision, and the reasoning is the opposite of the one that applies to user
passwords:

- A password KDF's work factor exists to make a dictionary or brute-force search of a
  **low-entropy** input expensive. This input is 32 bytes from a CSPRNG, and the server refuses
  any other shape. Against 2^256 there is nothing to slow down, so the work factor buys no
  security here.
- It does buy a cost. This hash is verified on **every** inbound call. An 80 ms KDF on a partner
  posting 10,000 orders a day is a self-inflicted denial of service.

What actually protects the secret is therefore stated plainly: **entropy at generation, a one-way
digest at rest, constant-time comparison at verification, TLS in transit, and a rotation path
that needs no downtime.** A pepper was considered and rejected for the same reason as the KDF - it
defends against inverting a low-entropy hash, and there is no low-entropy hash to invert.

Human passwords are a different problem and are not handled here at all: user authentication is
Supabase's, on the other security chain.

### 2.3 Scopes

A credential holds one or more scopes, and at least one is required:

| Scope | Grants |
|---|---|
| `integration.location:write` | `POST /integration/v1/locations`, `/locations/batch` |
| `integration.order:write` | `POST /integration/v1/orders`, `/orders/batch` |
| `integration.tracking:write` | `POST /integration/v1/tracking/positions` - see [§12](#12-vehicle-positions-migration-v29) |
| `integration.tender:respond` | `GET /integration/v1/tenders`, `POST /integration/v1/tenders/{shipmentNumber}/response` - see [§13](#13-carrier-tendering-migration-v31) |

`integration.tender:respond` is the one scope that is **not sufficient on its own**: the credential
holding it must also be bound to a carrier (`carrierId`, §2.4). Read and write are one scope rather
than a pair, because a carrier reading its own offers and answering them is one capability from one
party's point of view.

Scopes are a **different vocabulary** from user permissions on purpose. A partner credential is
not a user with a role; mixing the namespaces would make it possible to grant a machine
`iam.user:manage` by copying a role. The authorities on an integration authentication are scope
codes and nothing else, and the company scope it carries holds an **empty permission set** - so a
partner credential cannot reach `/api/v1/...` even though those endpoints are company-scoped too.

The value domain is closed at the database level (`ck_integration_client_scope_value`) and
mirrored by the `IntegrationScope` enum. Adding a capability takes a migration plus an enum
constant, which is exactly the friction such a change deserves.

### 2.4 Lifecycle (administrator API)

Credentials are managed by a signed-in administrator on the **user-facing** API, not here. An
integration credential cannot mint another integration credential - a partner key that could
would make revocation meaningless.

| Operation | Endpoint | Permission |
|---|---|---|
| List | `GET /api/v1/integration-clients` | `integration.client:read` |
| Get | `GET /api/v1/integration-clients/{id}` | `integration.client:read` |
| Create | `POST /api/v1/integration-clients` | `integration.client:manage` |
| Rename / re-scope | `PUT /api/v1/integration-clients/{id}` | `integration.client:manage` |
| Rotate secret | `POST /api/v1/integration-clients/{id}/rotate?graceHours=` | `integration.client:manage` |
| Revoke | `POST /api/v1/integration-clients/{id}/revoke` | `integration.client:manage` |
| Inbox history | `GET /api/v1/integration-clients/requests` | `integration.client:read` |

Only `ORGANIZATION_ADMIN` and `COMPANY_ADMIN` hold these permissions. `PLANNER` and `VIEWER` get
nothing here - not even read, because the credential list is an inventory of who can write into
the tenant.

There is no delete. A credential is **revoked**, which keeps its inbox history intact and
answerable, following the "deactivate, never delete" rule every master in TMS follows.

Create and update take an optional `carrierId` (migration V31). It is **required** when `scopes`
contains `integration.tender:respond` and **refused** otherwise: a carrier key must say whose tenders
it answers, and a carrier on a key that cannot answer tenders would be a field that means nothing.
The id is resolved through the fleet master inside the administrator's own company, so a body naming
another tenant's carrier is refused without revealing whether it exists, and a deactivated carrier
cannot acquire a working key. Unlike the company, it is re-pointable: an administrator who bound a
key to the wrong haulier can fix it without re-issuing a secret the partner has already deployed.

These endpoints have no screen yet - the integration module is API-only in V1 - so `carrierId` is set
with the same `POST`/`PUT` calls above.

Create, rotate and revoke each write an `INTEGRATION_CLIENT` row to `tms.audit_event`
(`CREDENTIAL_CREATE`, `CREDENTIAL_ROTATE`, `CREDENTIAL_REVOKE`) - the credential's `name` and, for
a rotation, `graceHours`; never the secret or its hash. See
`docs/domain/AUDIT_TRAIL_V1.md`. This is a second, complementary record to the inbox row every
*delivery* produces (section 8): this one is "the credential itself changed", the inbox is
"a request happened using it".

**Create** (the only response that ever contains a secret):

```http
POST /api/v1/integration-clients
X-Company-Id: 6f1a2b3c-0000-4000-8000-0000000000c1
Content-Type: application/json

{
  "name": "Acme ERP",
  "description": "Nightly store and order feed",
  "scopes": ["integration.location:write", "integration.order:write"]
}
```

```json
{
  "client": {
    "id": "3a7e5c11-0000-4000-8000-000000000021",
    "clientId": "tmsc_9f3Kq2Lm0pXbT7wR4aZs1Q",
    "name": "Acme ERP",
    "description": "Nightly store and order feed",
    "scopes": ["integration.location:write", "integration.order:write"],
    "active": true
  },
  "clientId": "tmsc_9f3Kq2Lm0pXbT7wR4aZs1Q",
  "secret": "tmss_Hn8vQ2xR7bLpY4tW9cE6mK1sD3fG0jN5uZaI8oPqTrY",
  "bearerToken": "tmsc_9f3Kq2Lm0pXbT7wR4aZs1Q.tmss_Hn8vQ2xR7bLpY4tW9cE6mK1sD3fG0jN5uZaI8oPqTrY",
  "previousSecretValidUntil": null,
  "notice": "This secret is shown once and cannot be recovered. Store it in your secret manager now. If it is lost or exposed, rotate the credential."
}
```

### 2.5 Rotation

`POST /api/v1/integration-clients/{id}/rotate` issues a new secret **and keeps the old one
working** until `previousSecretValidUntil`. That grace window is what makes a rotation deployable:
the partner receives the new value, redeploys at their own pace, and the old secret stops working
when the window closes rather than the instant the button was pressed.

- Default grace: **7 days** (`tms.integration.rotation-grace`, `TMS_INTEGRATION_ROTATION_GRACE`).
- **Suspected leak: pass `graceHours=0`.** The superseded secret is dropped immediately.
- The superseded hash is cleared automatically once the window closes, so no stale hash lingers.
- Revocation is terminal and needs no window: both the current and any superseded secret stop
  working at once.

Recommended partner-side routine: rotate on a schedule, deploy the new secret within the window,
and treat any authentication failure after a rotation as a deployment that was missed rather than
a reason to widen the window.

---

## 3. Authentication and its failure modes

```http
Authorization: Bearer <clientId>.<secret>
```

**Every** authentication failure answers identically - `401` with the same body:

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/problem+json
WWW-Authenticate: Bearer
```

```json
{
  "type": "urn:tms:problem:unauthenticated",
  "title": "Authentication required",
  "status": 401,
  "detail": "The integration credential is missing, malformed, revoked or not valid.",
  "instance": "/integration/v1/orders",
  "code": "unauthenticated",
  "timestamp": "2026-08-20T11:04:22.317Z",
  "correlationId": "8f2c1d40-3b6e-4a11-9c77-52b0d9a1e004"
}
```

That covers a missing header, a malformed token, an unknown client id, a wrong secret, a revoked
credential, a deactivated credential, a credential with no scopes, and a credential whose company
has been deactivated. Telling them apart would let an attacker enumerate client ids; the **server
log** records which it actually was, under the request's correlation id, so a genuine
misconfiguration is still diagnosable by the operator.

A credential that authenticates but lacks the scope for the endpoint gets `403`.

### Self-check

Before sending real data, confirm the credential and its company binding:

```http
GET /integration/v1/ping
Authorization: Bearer tmsc_9f3Kq2Lm0pXbT7wR4aZs1Q.tmss_...
```

```json
{
  "clientId": "tmsc_9f3Kq2Lm0pXbT7wR4aZs1Q",
  "name": "Acme ERP",
  "companyCode": "CO-LIMA",
  "companyName": "EBIM Peru",
  "companyTimeZone": "America/Lima",
  "scopes": ["integration.location:write", "integration.order:write"]
}
```

It writes nothing and requires no scope beyond being authenticated. It exists because the
alternative is debugging authentication by posting real orders. Note that the company is
identified by **code**, not by UUID: publishing internal identifiers to an external system invites
them to be used as keys, which then have to keep working forever.

### CORS is deliberately absent

There is no CORS configuration on this chain. A partner integration is a server, not a browser,
and an integration credential in a browser is a leaked credential. Omitting CORS means a page on
any origin simply cannot call these endpoints.

---

## 4. Idempotency

Repeated delivery of the same business object is safe and deterministic. Two independent
mechanisms do this, and they are **not** alternatives.

### 4.1 Business identity (always on)

| Object | Identity |
|---|---|
| Location | `(externalSystem, externalReference)` when present, otherwise `code` |
| Order | `(externalSource, externalReference)` |

Redelivering the same object **updates** it rather than duplicating it. This is what makes an
at-least-once sender safe with no header at all, and it is the primary mechanism.

For locations, the external key wins when the payload carries one, because it is the sending
system's own identity and survives a rename of the TMS code. When it resolves nothing, the code is
tried - that is how a location an operator created by hand gets adopted by the integration on the
first delivery instead of being duplicated. The one case that is **refused** rather than resolved:
the external key names row A while the code names row B. Either answer would be wrong, and picking
one silently would merge two real places or steal an identity from one of them.

### 4.2 `Idempotency-Key` (optional)

```http
Idempotency-Key: acme-2026-08-20-orders-0042
```

Shape: 8-128 characters of letters, digits, `.`, `_`, `:` or `-`. Anything else is `400`.

This covers what business identity cannot: the case where **the sender never learned the
outcome**. The first response is stored and replayed byte for byte, so a partner that timed out
and retried sees the answer they missed instead of a second, possibly different one.

Scope of a key: `(company, credential, operation)`. The same partner correlation id may
legitimately accompany a location delivery and an order delivery; forcing them to differ would be
a rule about our storage leaking into the partner's design.

Every response carries:

```http
X-Idempotent-Replay: true | false
```

so a partner can verify their own retry logic from their side of the wire.

**A key replayed with a different payload is refused with `409`.** Silently returning the first
response would tell the caller their second object was accepted when nothing was written:

```json
{
  "type": "urn:tms:problem:idempotency-key-reused",
  "title": "Idempotency key reused",
  "status": 409,
  "detail": "Idempotency-Key 'acme-2026-08-20-orders-0042' was already used by this client for a different payload. Use a new key for a new object.",
  "instance": "/integration/v1/orders",
  "code": "idempotency-key-reused",
  "timestamp": "2026-08-20T11:07:03.882Z",
  "correlationId": "8f2c1d40-3b6e-4a11-9c77-52b0d9a1e004"
}
```

A prior delivery that was itself **rejected** is not replayed. The reason it failed may since have
been fixed - the missing store finally created, the master activated - and answering "still 400"
forever would make the key a permanent tombstone.

---

## 5. Locations / Stores

### 5.1 `POST /integration/v1/locations`

Scope: `integration.location:write`. Upsert, not a create/update pair: a sending system generally
does not know whether TMS has seen a store before, and making it find out first would add a round
trip and a race for no benefit.

| Field | Type | Required | Notes |
|---|---|---|---|
| `code` | string(32) | yes | The TMS code. Normalised by the owning module. |
| `name` | string(200) | yes | |
| `type` | string(40) | yes | e.g. `STORE`, `WAREHOUSE`, `PLANT`, `CUSTOMER` |
| `roles` | string[] | yes | At least one, e.g. `ORIGIN`, `DESTINATION` |
| `address` | string(500) | no | |
| `addressReference` | string(300) | no | |
| `district` / `province` / `department` | string(120) | no | |
| `country` | string(60) | yes | |
| `timeZone` | string(64) | yes | IANA id, e.g. `America/Lima` |
| `latitude` / `longitude` | decimal | no | Both or neither |
| `zoneCode` | string(32) | no | Must name a zone of the same company |
| `serviceTimeMinutes` | integer | no | Defaults to 0 |
| `externalSystem` | string(60) | no | Your system's name |
| `externalReference` | string(100) | no | Your identifier for this store |
| `active` | boolean | no | Omit to leave unchanged |

**Request**

```http
POST /integration/v1/locations HTTP/1.1
Host: tms.ebim.example
Authorization: Bearer tmsc_9f3Kq2Lm0pXbT7wR4aZs1Q.tmss_Hn8vQ2xR7bLpY4tW9cE6mK1sD3fG0jN5uZaI8oPqTrY
Content-Type: application/json
Idempotency-Key: acme-store-4711-v3

{
  "code": "ST-4711",
  "name": "Acme Store Miraflores",
  "type": "STORE",
  "roles": ["DESTINATION"],
  "address": "Av. Larco 1234",
  "addressReference": "Frente al parque Kennedy",
  "district": "Miraflores",
  "province": "Lima",
  "department": "Lima",
  "country": "PE",
  "timeZone": "America/Lima",
  "latitude": -12.121500,
  "longitude": -77.029700,
  "zoneCode": "ZN-LIMA-SUR",
  "serviceTimeMinutes": 25,
  "externalSystem": "ACME-ERP",
  "externalReference": "4711",
  "active": true
}
```

**Response - created** (`201`, `X-Idempotent-Replay: false`)

```json
{
  "id": "b41d8e02-0000-4000-8000-0000000004711",
  "code": "ST-4711",
  "outcome": "CREATED"
}
```

**Response - redelivered unchanged** (`200`)

```json
{
  "id": "b41d8e02-0000-4000-8000-0000000004711",
  "code": "ST-4711",
  "outcome": "UNCHANGED"
}
```

`outcome` is one of `CREATED`, `UPDATED`, `UNCHANGED`. The status code follows: `201` for a
location that did not exist, `200` for one that did. A partner keying their own bookkeeping on the
status code gets the honest answer, and it costs nothing to give.

### 5.2 `POST /integration/v1/locations/batch`

Up to `tms.integration.max-batch-size` items (default **200**, hard ceiling 1000). Above that,
one request stops being a delivery and becomes a migration.

**Items are independent.** Each item runs in its own transaction, so item 3 failing leaves items 1
and 2 committed. `200` when every item landed, **`207 Multi-Status`** when any was refused.

```http
POST /integration/v1/locations/batch
Authorization: Bearer tmsc_....tmss_...
Content-Type: application/json

{
  "locations": [
    { "code": "ST-4711", "name": "Acme Store Miraflores", "type": "STORE", "roles": ["DESTINATION"],
      "country": "PE", "timeZone": "America/Lima", "externalSystem": "ACME-ERP", "externalReference": "4711" },
    { "code": "ST-4712", "name": "Acme Store Surco", "type": "STORE", "roles": ["DESTINATION"],
      "country": "PE", "timeZone": "America/Lima", "zoneCode": "ZN-DOES-NOT-EXIST",
      "externalSystem": "ACME-ERP", "externalReference": "4712" }
  ]
}
```

**Response** (`207`)

```json
{
  "submitted": 2,
  "succeeded": 1,
  "failed": 1,
  "results": [
    {
      "index": 0,
      "reference": "ST-4711",
      "result": { "id": "b41d8e02-0000-4000-8000-0000000004711", "code": "ST-4711", "outcome": "CREATED" },
      "error": null
    },
    {
      "index": 1,
      "reference": "ST-4712",
      "result": null,
      "error": {
        "code": "resource-not-found",
        "message": "No zone with code 'ZN-DOES-NOT-EXIST' exists in this company.",
        "fields": []
      }
    }
  ]
}
```

`index` is the position **as it was sent**, so a partner can correlate a failure to their own
record without relying on ordering guarantees.

---

## 6. Transport Orders

### 6.1 `POST /integration/v1/orders`

Scope: `integration.order:write`. Identity is `(externalSource, externalReference)`, both
required - an order without a sending-system identity cannot be redelivered safely, so the API
does not accept one.

| Field | Type | Required | Notes |
|---|---|---|---|
| `externalSource` | string(64) | yes | Your system's name |
| `externalReference` | string(128) | yes | Your order identifier |
| `originCode` | string(32) | yes | Location code with the `ORIGIN` role |
| `destinationCode` | string(32) | yes | Location code with the `DESTINATION` role |
| `customerName` | string(200) | no | |
| `customerReference` | string(100) | no | |
| `serviceDate` | date | yes | `YYYY-MM-DD` |
| `priority` | string(20) | yes | e.g. `NORMAL`, `HIGH`, `URGENT` |
| `requestedWindowStart` / `requestedWindowEnd` | time | no | `HH:mm`; start must precede end |
| `declaredWeightKg` / `declaredVolumeM3` / `declaredPallets` | decimal | no | Used when no lines are sent |
| `lines[]` | array | no | Totals are recomputed from lines when present |
| `markReadyForPlanning` | boolean | no | Defaults to `false` |

Line fields: `materialCode`, `materialDescription`, `quantity`, `uom` (all required),
`unitWeightKg`, `unitVolumeM3`, `palletQuantity` (optional).

**Request**

```http
POST /integration/v1/orders HTTP/1.1
Authorization: Bearer tmsc_9f3Kq2Lm0pXbT7wR4aZs1Q.tmss_Hn8vQ2xR7bLpY4tW9cE6mK1sD3fG0jN5uZaI8oPqTrY
Content-Type: application/json
Idempotency-Key: acme-so-2026-000123

{
  "externalSource": "ACME-ERP",
  "externalReference": "SO-2026-000123",
  "originCode": "CD-LIMA",
  "destinationCode": "ST-4711",
  "customerName": "Acme Retail S.A.C.",
  "customerReference": "PO-88213",
  "serviceDate": "2026-08-25",
  "priority": "NORMAL",
  "requestedWindowStart": "08:00",
  "requestedWindowEnd": "12:00",
  "lines": [
    { "materialCode": "SKU-001", "materialDescription": "Agua 625ml x15",
      "quantity": 120, "uom": "CJ", "unitWeightKg": 9.4, "unitVolumeM3": 0.012, "palletQuantity": 0.1 },
    { "materialCode": "SKU-002", "materialDescription": "Gaseosa 500ml x12",
      "quantity": 60, "uom": "CJ", "unitWeightKg": 7.2, "unitVolumeM3": 0.009, "palletQuantity": 0.08 }
  ],
  "markReadyForPlanning": true
}
```

**Response - created** (`201`, `X-Idempotent-Replay: false`)

```json
{
  "id": "c92f7a13-0000-4000-8000-000000000123",
  "orderNumber": "ORD-2026-000512",
  "status": "READY_FOR_PLANNING",
  "outcome": "CREATED"
}
```

**Response - the same delivery repeated** (`200`, `X-Idempotent-Replay: true`)

```json
{
  "id": "c92f7a13-0000-4000-8000-000000000123",
  "orderNumber": "ORD-2026-000512",
  "status": "READY_FOR_PLANNING",
  "outcome": "CREATED"
}
```

Note that a **replayed** response is the stored first answer, verbatim - including its `outcome`
and its status code. That is the point: the partner sees the answer they missed.

**An order that has already been planned or cancelled is not silently rewritten** - the call fails
with `409` naming the status. A sending system that changes an order a planner has already put on a
trip is describing a real operational problem, and answering "accepted" would hide it.

### 6.2 `POST /integration/v1/orders/batch`

Same contract as the location batch: independent items, `200` or `207`, per-item `index` and
`error`, `max-batch-size` bound.

```json
{
  "orders": [
    { "externalSource": "ACME-ERP", "externalReference": "SO-2026-000123",
      "originCode": "CD-LIMA", "destinationCode": "ST-4711",
      "serviceDate": "2026-08-25", "priority": "NORMAL", "declaredWeightKg": 1560.0 },
    { "externalSource": "ACME-ERP", "externalReference": "SO-2026-000124",
      "originCode": "CD-LIMA", "destinationCode": "ST-9999",
      "serviceDate": "2026-08-25", "priority": "NORMAL", "declaredWeightKg": 820.0 }
  ]
}
```

```json
{
  "submitted": 2,
  "succeeded": 1,
  "failed": 1,
  "results": [
    {
      "index": 0,
      "reference": "SO-2026-000123",
      "result": { "id": "c92f7a13-0000-4000-8000-000000000123", "orderNumber": "ORD-2026-000512",
                  "status": "NOT_READY", "outcome": "CREATED" },
      "error": null
    },
    {
      "index": 1,
      "reference": "SO-2026-000124",
      "result": null,
      "error": {
        "code": "resource-not-found",
        "message": "No destination with code 'ST-9999' exists in this company.",
        "fields": []
      }
    }
  ]
}
```

Batching orders is justified here rather than speculative: the sending pattern is a nightly or
hourly release of a whole day's orders, and one request per order would mean thousands of
round trips and thousands of authentications for one logical delivery.

---

## 7. Errors

All single-object failures are RFC 9457 problem documents, the same format the user-facing API
uses. **No stack traces, no exception messages, no SQL fragments ever reach a response body.**

```json
{
  "type": "urn:tms:problem:validation-failed",
  "title": "Request validation failed",
  "status": 400,
  "detail": "One or more fields are not valid.",
  "instance": "/integration/v1/locations",
  "code": "validation-failed",
  "timestamp": "2026-08-20T11:04:22.317Z",
  "correlationId": "8f2c1d40-3b6e-4a11-9c77-52b0d9a1e004",
  "errors": [
    { "field": "timeZone", "message": "must not be blank" },
    { "field": "roles", "message": "a location must hold at least one role" }
  ]
}
```

`type` is a URN rather than a dereferenceable URL: the identifier has to be stable and
unambiguous, and RFC 9457 does not require it to resolve. **Branch on `type` or `code`, never on
`detail`** - `detail` is human-readable prose and may be reworded.

| Status | `code` | Meaning |
|---|---|---|
| `400` | `validation-failed` | A field is missing or out of range; see `errors[]` |
| `400` | `malformed-request` | Unparseable JSON body, bad `Idempotency-Key` shape, batch over the size bound |
| `401` | `unauthenticated` | Credential missing, malformed, unknown, revoked, inactive, wrong secret, scopeless, or its company is deactivated |
| `403` | `access-denied` | Authenticated, but the credential lacks the scope for this endpoint |
| `403` | `company-scope-forbidden` | An `X-Company-Id` was sent naming a company other than the credential's own |
| `400` | `company-scope-invalid` | An `X-Company-Id` was sent that is not a UUID |
| `404` | `resource-not-found` | A referenced master (origin, destination, zone) does not exist **in this company** |
| `409` | `conflict` | Business conflict, e.g. an order already planned, or a code/external-reference collision |
| `409` | `idempotency-key-reused` | The key was reused with a different payload |
| `500` | `internal-error` | Ours, not yours. Retry is safe; quote the `correlationId` |

### Correlation ids

Every response - success or failure - carries `X-Correlation-Id`, and every error document repeats
it as `correlationId`. Send your own with `X-Correlation-Id` and it is honoured and echoed;
otherwise one is generated. It appears on every server log line for the request and is stored on
the inbox row, so a support question resolves to a single request without guesswork.

**A `500` is the one case where the response deliberately says less than the log.** The
correlation id is the whole answer the partner needs; the cause is ours to fix.

### Retry guidance

| Status | Retry? |
|---|---|
| `400`, `403`, `404`, `409` | **No.** Fix the payload, the scope or the master data first. |
| `401` | No, unless a rotation is in flight - then deploy the new secret. |
| `500`, `502`, `503`, `504` | **Yes**, with exponential backoff. Reuse the same `Idempotency-Key`. |

Because the operations are idempotent, a retry after a timeout is always safe.

---

## 8. Integration inbox and audit

Every authenticated delivery writes exactly one row to `tms.integration_request`, **whatever the
outcome** - accepted, rejected for a bad field, or failed on our side - in its own transaction.
That last part is the design: a delivery that fails rolls its business transaction back, and if
the inbox row shared that transaction it would roll back too - the one record explaining what went
wrong would be the one record that never survives.

This is also why the intake endpoints do not put `@Valid` on the request body. Spring refuses a
`@Valid` argument *before* the controller method runs, which would make a constraint failure the
one outcome the inbox never records - and a rejected field is the most common thing to go wrong
while onboarding a partner. Bean Validation therefore runs inside the executor, on the audited
path. The document the caller receives is identical either way.

**The one delivery that is not inboxed** is a body that could not be parsed as JSON at all: there
is no payload to fingerprint and no operation context beyond the URL. It is answered with a
`malformed-request` problem document and logged under its correlation id.

Recorded per delivery:

| Column | Content |
|---|---|
| `company_id`, `integration_client_id` | Tenant and credential (composite FK ties them together) |
| `operation` | `location.upsert`, `location.batch`, `order.upsert`, `order.batch` |
| `idempotency_key` | As sent, or null |
| `external_system`, `external_reference` | The business identity of the delivery |
| `payload_hash` | SHA-256 of the request body |
| `status` | `SUCCEEDED`, `PARTIAL`, `REJECTED`, `FAILED` |
| `http_status` | The status actually returned |
| `item_count`, `succeeded_count`, `failed_count` | Batch outcome |
| `resource_id` | The business row a single-object operation produced |
| `response_body` | The stored answer, replayed on an idempotent retry |
| `error_summary` | **Sanitised** business summary |
| `correlation_id` | Links the row to the server log |
| `received_at`, `completed_at`, `duration_ms` | Timing |

`payload_hash` is always written and is what makes idempotency honest: the same key replayed with
a different body is a client bug, and answering it with the first body would hide it.

**What is deliberately never stored:** no credential material of any kind - not the secret, not a
fragment of it, not the bearer token. `error_summary` is a short business message, never an
exception message, a stack frame or a SQL fragment; those belong in the server log under the
correlation id.

An administrator reads the inbox at `GET /api/v1/integration-clients/requests` (newest first,
filterable by `clientId`). It carries no payload content - only what was delivered and what
happened to it.

### 8.1 Raw payload retention (optional, off by default)

`integration_request.payload_snapshot` can hold the raw request body, but it is written **only
when the deployment opts in**:

```yaml
tms:
  integration:
    retain-payloads: ${TMS_INTEGRATION_RETAIN_PAYLOADS:false}
```

**The default is `false`, and that default is the security decision.** An order payload carries
customer names, addresses and references. Copying them into an append-only audit table is a
data-protection commitment, not a debugging convenience.

If you enable it, you take on these obligations:

1. **Set a retention job.** Nothing in TMS deletes these rows. Decide a window (30 days is a
   reasonable default for troubleshooting) and delete `payload_snapshot` beyond it - the rest of
   the row is the audit trail and should be kept.
2. **Treat the table as personal data** in your records of processing, your DSAR procedure and
   your erasure procedure.
3. **Restrict who can read it.** Only `integration.client:read` holders can, and only within their
   own company; keep it that way.
4. **Expect truncation.** Snapshots are capped at 64 KB; a snapshot is for debugging one delivery,
   not for archiving a megabyte of it.

Enable it in staging while onboarding a partner, and turn it off in production once the feed is
stable.

### 8.2 Metrics

`IntegrationRequestExecutor` increments the Micrometer counter `tms.integration.requests`, tagged
`operation` (`location.upsert`, `order.batch`, ...) and `outcome` (`SUCCEEDED`, `PARTIAL`,
`REJECTED`, `FAILED`), on every delivery it finishes - the same four outcomes the inbox row's own
`status` column can hold. It is readable at `GET /actuator/metrics/tms.integration.requests`
(`metrics` is on the exposed actuator endpoint list, `application.yml`), with a `tag` query
parameter to break it down per operation or outcome. This is the aggregate view an operator
watches without querying `tms.integration_request`; the inbox row is still the record for any one
delivery.

---

## 9. Configuration

```yaml
tms:
  integration:
    retain-payloads: ${TMS_INTEGRATION_RETAIN_PAYLOADS:false}   # see §8.1
    rotation-grace:  ${TMS_INTEGRATION_ROTATION_GRACE:168h}     # 7 days
    max-batch-size:  ${TMS_INTEGRATION_MAX_BATCH_SIZE:200}      # hard ceiling 1000
```

No secret is ever read from configuration: credentials are created through the administrator API
and exist only as hashes in the database.

---

## 10. Tests

| Test | Proves | Needs Docker |
|---|---|---|
| `IntegrationSecretsTest` | Entropy, shape, one-way hashing, constant-time verification | no |
| `IntegrationClientTest` | Rotation window, revocation, scope diffing | no |
| `IntegrationAuthenticationServiceTest` | The company comes from the credential; indistinguishable rejections | no |
| `IntegrationApiTenancyTest` | **Cross-tenant HTTP behaviour** over the real security chain: a company-A credential writing only into company A, `X-Company-Id` for company B refused, per-endpoint scope enforcement, idempotent replay, idempotency keys not crossing companies, inbox contents | no |
| `IntegrationTenancyIsolationIntegrationTest` | **Cross-tenant at the database**: RLS hides another company's credentials, scopes and inbox rows and refuses writes into them; the composite FK refuses a mismatched (company, credential) pair; idempotency uniqueness; the credential CHECK constraints | yes |

---

## 11. Onboarding checklist for a partner

1. An administrator creates a credential for the company with the **narrowest** scope set that
   works - a store feed needs `integration.location:write` only.
2. The secret is copied into the partner's secret manager from the creation response. It is not
   recoverable afterwards.
3. The partner confirms `GET /integration/v1/ping` returns the expected `companyCode` and scopes.
4. Locations are loaded first, orders second - an order references locations by code.
5. The partner sends an `Idempotency-Key` per logical delivery and retries `5xx` with backoff.
6. The administrator watches `GET /api/v1/integration-clients/requests` during the first days.
7. A rotation schedule is agreed, and `graceHours=0` is understood as the leak response.

---

## 12. Vehicle positions (migration V29)

Added after sections 7-11 were written and numbered here rather than as section 7, so that the
cross-references other documents already make to "section 8.1", "section 9" and so on keep pointing
at what they meant. Full design: `docs/domain/TRACKING_V1.md`. Decision:
`docs/architecture/ADR-007-tracking-provider-port.md`.

### 12.1 `POST /integration/v1/tracking/positions`

Scope: `integration.tracking:write`. One endpoint, no single-position sibling - a device reporting
live sends a batch of one, a device flushing a buffer sends a batch of two hundred, and both are the
same operation.

```http
POST /integration/v1/tracking/positions
Authorization: Bearer <clientId>.<secret>
Content-Type: application/json

{
  "provider": "acme-telematics",
  "positions": [
    {
      "shipmentNumber": "SH-00000042",
      "occurredAt": "2026-08-21T09:56:00Z",
      "latitude": -12.046374,
      "longitude": -77.042793,
      "speedKph": 62.5,
      "headingDegrees": 183.4,
      "externalVehicleReference": "TRK-0431",
      "correlationReference": "ping-991"
    }
  ]
}
```

```json
{
  "submitted": 1,
  "accepted": 1,
  "stored": 1,
  "refused": 0,
  "results": [
    { "index": 0, "shipmentNumber": "SH-00000042", "outcome": "RECORDED", "error": null }
  ]
}
```

**Shipments are named by number, never by uuid.** A partner already holds shipment numbers from the
outbound `ShipmentPlan V1` contract and from the paperwork; requiring our primary keys would force
every telematics integration to keep a mapping table it has no other use for.

**`provider` is a label, not an authority.** The tenant comes from the credential as everywhere else
(§1). It is in the body rather than derived from the credential because one credential legitimately
relays several upstream feeds - a 4PL forwarding two carriers' telematics - and a credential per
feed would make onboarding a subcontractor a key-management exercise.

### 12.2 Outcomes, and why "accepted" is not "stored"

| `outcome` | Accepted | Meaning |
|---|---|---|
| `RECORDED` | yes | stored |
| `DUPLICATE` | yes | this feed already reported this shipment at this instant |
| `THINNED` | yes | closer to the last kept point than the sampling interval |
| `STALE` | yes | older than the newest position already held for this shipment and feed |
| `UNKNOWN_SHIPMENT` | no | this company has no such shipment |
| `NOT_TRACKABLE` | no | the shipment exists but has not left, or was cancelled |
| `INVALID` | no | coordinates out of range, a time in the future, a malformed provider slug |

TMS keeps at most one position per configured interval per (shipment, feed) - 60 seconds by
default. Denser points are **accepted and dropped**, never refused: a sender pushing every second is
doing what its vendor's default does, and an API whose scalability depends on partners
reconfiguring their equipment does not have a scalability story.

`stored` is therefore usually lower than `accepted`, and that ratio is the number worth watching: a
feed seeing 95% `THINNED` is spending bandwidth on nothing, and any `STALE` means it is delivering
out of order, which retrying does not fix.

Only the three refusals count as failed items, so a run answers **200** when everything was accepted
and **207** when anything was refused - the same rule as `/orders/batch` (§6.2). A run whose every
position names a shipment cancelled an hour ago still answers 207 with a reason per item, not 400: a
feed doing exactly what it should must not be told it is broken.

`UNKNOWN_SHIPMENT` says only that *this company* has no such shipment - never whether it exists
elsewhere. A telematics credential is often held by a third party.

### 12.3 Idempotency

The ordinary pair (§4), with business identity being `(company, shipment, provider, occurredAt)` and
enforced by `uq_tracking_position_feed_instant`. Redelivery is a no-op, so an at-least-once sender
needs no cursor and no de-duplication of its own; `Idempotency-Key` remains available for the case
where the sender never learned the outcome.

### 12.4 Reading positions back

There is none here. `integration.tracking:write` grants no read anywhere: a provider pushing
positions learns nothing about the shipments it pushes against beyond whether the numbers are
usable. Positions are read by a signed-in user at `GET /api/v1/tracking/trips/{tripId}` under
`monitoring.transport:read`.

### 12.5 Onboarding a tracking provider

1. An administrator creates a credential holding `integration.tracking:write` **only**.
2. The provider confirms `GET /integration/v1/ping`.
3. The provider posts a batch of one against a shipment that is in transit and checks for
   `"outcome": "RECORDED"`.
4. `NOT_TRACKABLE` during the first tests almost always means the shipment has not been dispatched
   in TMS yet - positions are accepted from dispatch onwards, and after completion, so a late
   buffer flush is not lost.
5. The deployment's `min-interval` is agreed, and the provider sizes its push rate to it rather
   than the other way round.

## 13. Carrier tendering (migration V31)

Full design: `docs/domain/CARRIER_TENDERING_V1.md`.

**This is the only part of the API where the authenticated party is not the tenant.** Every other
endpoint here is a system acting *for* the company that issued the key. These two are a
*counterparty* answering for itself, and everything about their shape follows from that.

### 13.1 The credential

A tender credential holds `integration.tender:respond` and is bound to exactly one carrier
(`carrierId`, §2.4). The carrier is resolved from the credential on every call and never read from a
payload or a header - the same discipline that makes the company unspoofable (§1).

A credential that holds the scope with **no** carrier bound to it is refused with `409` and a message
naming the misconfiguration. It is never allowed to fall back to the company, which would hand one
partner every carrier's offers.

Holding this scope grants **no other read**. It is deliberately not `integration.shipment:read`,
which exposes every confirmed shipment of the company; a carrier learns about the shipments it was
offered and no others.

### 13.2 `GET /integration/v1/tenders`

The offers this carrier is holding and can still answer, oldest first.

```http
GET /integration/v1/tenders
Authorization: Bearer <clientId>.<secret>
```

```json
[
  {
    "shipmentNumber": "SH-00000142",
    "attempt": 2,
    "status": "SENT",
    "planningDate": "2026-08-24",
    "plannedDepartureAt": "2026-08-24T06:00:00Z",
    "originCode": "DC-LIM",
    "originName": "Lima distribution centre",
    "stopCount": 7,
    "offeredAmount": 1240.00,
    "currency": "PEN",
    "notes": "Load 06:00, gate B, tail lift required",
    "sentAt": "2026-08-21T14:10:00Z",
    "expiresAt": "2026-08-22T12:00:00Z",
    "respondedAt": null,
    "responseNotes": null
  }
]
```

Optional `?shipmentNumber=SH-00000142` fetches the one offer instead of the whole queue.

**An offer whose deadline has passed is absent, not listed as expired.** This is a work queue, and an
offer that can no longer be accepted is not work. What happened to it is answerable from the shipment
event feed.

**Not paginated,** deliberately: the result is bounded by what one carrier has outstanding right now.
A carrier with two hundred unanswered tenders has an operational problem that a page boundary would
hide rather than solve.

**What is deliberately absent** from the payload is the point: no vehicle, no licence plate, no
driver, no capacity figures, no order numbers, no customer names, and no TMS uuid. The shipper
planned a truck onto the shipment, but who the carrier sends is the carrier's decision; and the
destinations a load is going to are the shipper's commercial relationships, learned when the carrier
accepts and gets the manifest - not while they are deciding. `stopCount` is the coarsest honest
measure of the job and the only one that discloses nothing.

### 13.3 `POST /integration/v1/tenders/{shipmentNumber}/response`

```http
POST /integration/v1/tenders/SH-00000142/response
Authorization: Bearer <clientId>.<secret>
Content-Type: application/json

{
  "decision": "REJECTED",
  "reason": "No 12t available on the 24th"
}
```

Answers `200` with the same offer shape, now carrying `status`, `respondedAt` and `responseNotes`.

| Field | Rules |
|---|---|
| `decision` | required, `ACCEPTED` or `REJECTED` (case-insensitive) |
| `reason` | required on `REJECTED`, optional on `ACCEPTED`, at most 1000 characters |

`reason` is required on a refusal because it is what the shipper's planner needs in order to decide
what to do next; "they declined" with no reason is the answer that helps least.

### 13.4 Failure modes

| Status | When |
|---|---|
| `400` | `decision` missing or not one of the two values; `reason` missing on a rejection |
| `404` | this carrier has no tender on that shipment number in this company |
| `409` | the offer is no longer answerable - lapsed, withdrawn, or already answered the other way |
| `409` | the credential holds the scope but is not bound to a carrier |

**`404` is the same sentence a shipment that does not exist gets.** A carrier must not be able to
tell the two apart, or this endpoint becomes a way to enumerate the shipper's business.

An offer can stop being answerable without the carrier doing anything: the shipper withdraws it, the
deadline passes, or the shipment is cancelled or dispatched (`TENDER_CANCELLED` /`TENDER_EXPIRED` on
the shipment event feed). A `409` here is normal traffic, not a bug in the sender.

### 13.5 Idempotency

Re-sending the **same** decision returns the answer already recorded, so an at-least-once sender is
safe with no key and no cursor. Sending the **opposite** decision is `409`: reversing a commitment is
not a retry, and it needs a person on the shipper's side.

`Idempotency-Key` behaves exactly as in §4.2. The shipment number travels in the path but is folded
into the fingerprinted payload, so a key reused across two shipments with the same decision is a
`409` naming the reuse rather than a silently replayed answer for the wrong shipment.

### 13.6 Learning that there is something to answer

Polling `GET /integration/v1/tenders` is enough and needs no other scope. A carrier that also holds
`integration.shipment:read` can instead watch the shipment event feed, which since V31 carries five
new event types: `TENDER_SENT`, `TENDER_ACCEPTED`, `TENDER_REJECTED`, `TENDER_EXPIRED` and
`TENDER_CANCELLED`. `TENDER_SENT` is the arrival signal and `TENDER_CANCELLED` is the withdrawal
signal; both matter, because an offer pulled back before it was answered would otherwise sit in the
queue until the next poll noticed it was gone.

### 13.7 Onboarding a carrier

1. An administrator creates a credential holding `integration.tender:respond` **only**, with
   `carrierId` set to that carrier's row in the fleet master.
2. The carrier confirms `GET /integration/v1/ping`.
3. The carrier polls `GET /integration/v1/tenders` and expects `[]` until the shipper offers
   something.
4. The shipper tenders a test shipment; the carrier answers `REJECTED` with a reason and checks that
   the response echoes `"status": "REJECTED"`.
5. Poll interval is agreed. There is no webhook - V1 stops at the transactional outbox, for the
   reasons `docs/integrations/OUTBOUND_SHIPMENT_V1.md` sets out.
