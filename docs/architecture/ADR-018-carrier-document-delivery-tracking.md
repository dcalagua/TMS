# ADR-018 - Carrier portal and document delivery tracking

**Status:** Accepted (product owner, 2026-10-12). Implemented with V57.
**Date:** 2026-10-12
**Supersedes in part:** ADR-016 (its proposal is approved in the carrier-scoped variant below),
ADR-015 section 5 (document delivery tracking, previously proposed and not implemented).
**Constrained by:** ADR-001, ADR-003, ADR-005 (tenant RLS), ADR-006 (evidence store), ADR-009 (order
execution lifecycle), ADR-013 (external dispatch), ADR-015 (logistics documents)

## Context

The operation needs to follow the paperwork that travels with a shipment - invoices, delivery notes,
GREs - to the customer and back: was each document handed over, refused, or not delivered, and did
the signed copy come back to the office. The people who know are the carrier's, so the carrier must
be able to report it from a phone, and the office must be able to watch it.

ADR-015 stored the documents and linked them to orders. ADR-016 described a carrier identity and left
it blocked on a security decision. Both decisions were taken by the product owner on 2026-10-12:

| Question | Decision |
|---|---|
| Who reports | Users of the carrier company. One user per driver is deferred. |
| What a carrier user sees | The trips **assigned to their carrier** (`trip.carrier_id`), nothing else. |
| When a trip becomes visible | When the warehouse system's dispatch (ADR-013) arrives for it through the integration. |
| Unit tracked | One document on one trip. A split order's document is tracked on every trip that carries part of it. |
| States | `PENDING`, `DELIVERED`, `REJECTED`, `NOT_DELIVERED`. **No partial state**: partial is a property of the goods, which ADR-009 already records. |
| Evidence | A photo is **mandatory** for `DELIVERED`; a signature is optional. |
| Return of the signed copy | In V1. Confirmed by the office, never by the carrier. |

## Decision

### 1. Carrier-scoped membership (ADR-016, approved variant)

- `tms.membership.carrier_id` (nullable). A membership with a carrier is a **carrier membership**: it
  must be company-scoped, and the carrier must belong to that company (composite FK).
- `tms.role.scope_level` gains `CARRIER`. The identity query grants a carrier membership the
  permissions of `CARRIER` roles **only**, and grants an ordinary membership **no** `CARRIER` role.
  The pairing is enforced in the one query every request runs (`JdbcIdentityRepository`), the same
  place `ORGANIZATION` roles are kept off company memberships.
- One seeded role, `CARRIER_OPERATOR`, with `portal.trip:read` and `portal.document:report`. It holds
  no planning, order, rate, settlement or master-data permission, so every existing endpoint stays
  closed to a carrier user by `@PreAuthorize`, not by a screen.
- If somebody reaches the same company through an ordinary membership *and* a carrier membership,
  the carrier membership is ignored and the incident logged. The administration screen refuses to
  create that pairing.
- `CompanyScope.carrierId()` carries the carrier into every request. `TenantScopedDataSource`
  publishes it as `tms.carrier_id` beside `tms.company_id`.

### 2. RLS: the carrier as a second boundary (ADR-005's pattern)

`tms.current_carrier_id()` reads the setting. Each table the portal reads gains a **restrictive**
policy that is true for every non-carrier transaction and, for a carrier transaction, only for rows of
that carrier's trips:

- `trip`: `carrier_id = current_carrier_id()`;
- `trip_stop`, `trip_order_assignment`, `external_dispatch`, `document_delivery*`: the row's trip is
  visible (an `EXISTS` on `trip`, which is itself filtered);
- `transport_order`, `logistics_document_order`, `logistics_document`: reachable through an active
  assignment on a visible trip.

Restrictive policies are ANDed with the tenant policy, so they can only narrow. Master data
(destinations, carriers, vehicles) is not narrowed: no carrier permission reaches an endpoint that
lists it, and the portal reads the names it needs through the trip.

### 3. Visibility rule (Java)

A carrier user sees a trip when all hold: same company; `trip.carrier_id` is their carrier; the trip
is not `DRAFT` or `CANCELLED`; and a **current** (not superseded) `external_dispatch` is matched to
it. In `MANUAL` dispatch mode no warehouse document arrives, so nothing is visible: that is the
decision, not a gap. A dispatch whose carrier differs from the plan (`CARRIER_MISMATCH`) stays with
the trip's assigned carrier; the monitor shows the mismatch.

### 4. Document delivery tracking (planning-owned tables)

- `tms.document_delivery`: one row per (trip, document), materialised as `PENDING` with a snapshot of
  the document's identity (type and number, which are immutable under ADR-015's unique key). Carries
  the current status, reason, remark, when it happened (`reported_at`, the carrier's time), who
  recorded it, and the office's return confirmation.
- `tms.document_delivery_order`: the orders of the trip the document covers.
- `tms.document_delivery_event`: append-only history - `REPORTED` (with status) and `RETURNED`. The
  carrier may correct a report until the office confirms the return; each correction is a new event.
- `tms.document_delivery_evidence`: photos and signatures, in ADR-006's private store through
  `EvidenceStoragePort`, bytes first and row second (as `DeliveryEvidenceService`). Never a URL.

Rows are materialised when a dispatch is matched to a trip (`ExternalDispatchService`) and when a
document is linked to an order that is already on a dispatched trip (a `LogisticsDocumentLinkListener`
declared in `shared` and implemented by planning, so `orders` does not depend on `planning`). V57
backfills trips dispatched before it.

Reasons for `REJECTED` / `NOT_DELIVERED`: `CUSTOMER_ABSENT`, `CUSTOMER_REFUSED`, `CUSTOMER_CLOSED`,
`ADDRESS_NOT_FOUND`, `DOCUMENT_ERROR`, `GOODS_REJECTED`, `OTHER` (remark required).

**Nothing here moves an order, a stop or a trip.** The monitor compares the document state with the
delivery result of the order on that trip (ADR-009) and flags disagreements; it never resolves them.

### 5. Idempotency

Every portal mutation requires an `Idempotency-Key` header, stored on the event and unique per
(company, user, key). A retry with the same key and target returns the current state without a second
event; the same key on another target is `409`.

### 6. API

Portal (role `CARRIER_OPERATOR`, header `X-Company-Id` as everywhere):

| Method | Path | Permission |
|---|---|---|
| GET | `/api/v1/portal/trips?days=14&pendingOnly=false` | `portal.trip:read` |
| GET | `/api/v1/portal/trips/{tripId}` | `portal.trip:read` |
| POST (multipart) | `/api/v1/portal/trips/{tripId}/documents/{documentId}/report` | `portal.document:report` |
| GET | `/api/v1/portal/trips/{tripId}/documents/{documentId}/evidence/{evidenceId}/content` | `portal.trip:read` |

The report form fields: `status`, `reasonCode`, `remark`, `reportedAt` (ISO-8601, optional), files
`photos` (1..5, mandatory for `DELIVERED`) and `signature` (optional).

Office monitor:

| Method | Path | Permission |
|---|---|---|
| GET | `/api/v1/document-deliveries?dateFrom&dateTo&carrierId&status&returned&q&page&size` | `planning.document_delivery:read` |
| GET | `/api/v1/document-deliveries/summary?dateFrom&dateTo&carrierId` | `planning.document_delivery:read` |
| GET | `/api/v1/document-deliveries/{id}` | `planning.document_delivery:read` |
| POST | `/api/v1/document-deliveries/{id}/return` | `planning.document_delivery:return` |
| GET | `/api/v1/document-deliveries/{id}/evidence/{evidenceId}/content` | `planning.document_delivery:read` |

`planning.document_delivery:read` goes to the administrators, PLANNER and VIEWER;
`planning.document_delivery:return` to the administrators and PLANNER.

### 7. Frontend

The portal is its own route set (`/portal`) in the same Vite app, laid out for a phone, with a web
app manifest so it can be installed. Online-first: no offline queue (deferred by decision); the
idempotency key makes a retry on a bad network safe. A user whose selected company is carrier-scoped
is routed to `/portal` and sees no other menu. The monitor is a page of the office application.

## Consequences

- Migration V57: membership carrier scope, the `CARRIER` role level and role, four permissions, the
  four tracking tables, `current_carrier_id()` and the restrictive policies, and the backfill.
- Evidence requires `TMS_EVIDENCE_STORAGE_MODE=LOCAL` (or a later store). With the store disabled a
  document can be reported `REJECTED` / `NOT_DELIVERED` but not `DELIVERED`, and the portal says so.
- A document unlinked from its order after materialisation keeps its tracking row; the monitor shows
  it as it was. Re-linking rules are left to a later ADR if the ERP ever unlinks documents.
- Still deferred: a login per driver, offline capture, issuing a GRE transportista, and any rule that
  makes a document state move an order.
