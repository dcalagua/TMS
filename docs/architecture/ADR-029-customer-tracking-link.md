# ADR-029 - A customer tracking link: one order, an unguessable token, a minimal public read

- **Status:** Accepted and implemented (V74, 2026-10-22)
- **Date:** 2026-10-22
- **Relates to:** ADR-003 (company scope), ADR-005 (tenant RLS for `tms_app`), ADR-009 (order execution
  lifecycle), ADR-011 (stop ETA), ADR-017 (commercial access), ADR-018 (carrier portal and the
  restrictive carrier policy), ADR-021 (tenant lifecycle).
- **Migration:** `V74__customer_tracking_link.sql` (number assigned by the backlog; V70-V73 belong to
  parallel branches and land first)
- **Backlog:** evaluation 2026-10-22, item 6 («¿dónde está mi pedido?» por WhatsApp)

## Context

Until now TMS had **no** surface for the final customer: no mail, no SMS, no WhatsApp and no public
page. The only unauthenticated business path was `/system/info` (`PublicApiPaths`). In the LatAm market
the question «¿dónde está mi pedido?» arrives by WhatsApp, and the competitors answer it with a link
that opens on a phone without signing in.

A link that works without a session is, by definition, a bearer credential that anyone holding the
message can use, and the first path in this product that serves tenant data to an anonymous caller. So
this ADR is mostly a threat model.

## Threat model

| # | Threat | Control |
|---|---|---|
| T1 | **Guessing / enumerating tokens** | 256 random bits (`SecureRandom`, 32 bytes, base64url, 43 chars). A malformed token is refused without touching the database. Every anonymous miss is counted per client address and the address is throttled after a small number (rate limit, below). |
| T2 | **Database leak reveals working links** | Only `sha256(token)` is stored (`token_hash`, unique). The raw token exists in the creation response and nowhere else - not in a column, not in the audit trail, not in a log line this code writes. A plain SHA-256 is enough because the input has 256 bits of entropy; a slow KDF protects low-entropy secrets, which this is not. |
| T3 | **A forwarded message outlives its purpose** | Every link expires: a hard cap set at creation (`tms.tracking-links.max-lifetime`, 30 days) **and** a soft end 7 days after the order reaches a final state (`after-closure`, delivered / partly delivered / failed / cancelled), whichever comes first. The office can revoke any link at any time; revocation is one-way. |
| T4 | **Cross-order / cross-tenant leakage** | A token resolves to exactly one `(company_id, order_id)`. The only statement on the anonymous path that runs on the owner connection is `SELECT id, company_id FROM tms.tracking_link WHERE token_hash = ?`. Everything after it runs as `tms_app` with `tms.company_id` set to *that* company (ADR-005), so RLS filters every read to the link's tenant, and every query also names the order and the company in Java. |
| T5 | **Over-disclosure** | A fixed, minimal projection (below). No prices, no amounts, no weights, no other orders, no addresses (the destination's *name* only), no driver, no phone, no photos or signatures, no internal ids. The plate is masked by default and can be hidden per company. Tests assert the JSON keys, not just the values. |
| T6 | **Existence oracle** | Unknown, malformed, expired, revoked, tenant-inactive and commercially-suspended links all answer the **same** `404` with the same body. |
| T7 | **The public path widens the API** | A security chain of its own, matched to `${base}/public/**`, with no bearer-token processing at all, `GET ${base}/public/tracking/{token}` the only permitted route and `denyAll()` for anything else under the prefix. `PublicApiPaths` names it; tests prove the chain exposes nothing else and that a token for this path does not authenticate anywhere else. |
| T8 | **PostgREST / Supabase anon reads the table** | The table lives in `tms` (ADR-004), which the Data API does not expose; no grant to `anon`/`authenticated`; RLS enabled with tenant policies for `tms_app` only. The public read goes through Spring, never through Supabase. |
| T9 | **A carrier user reads the shipper's customer links** | `p_carrier_scope` restrictive policy (ADR-018 shape): a carrier transaction sees no row. |
| T10 | **Leaking the token through Referer, caches or search engines** | `Referrer-Policy: no-referrer` (both chains), `Cache-Control: no-store`, `X-Robots-Tag: noindex, nofollow`; the frontend page sets `<meta name="robots" content="noindex">` and `referrer=no-referrer`. |
| T11 | **Denial of service / scraping** | Per-address fixed-window limit on all requests and a tighter one on misses, per-token limit on hits, all in a bounded in-memory map. Behind a load balancer the address is the forwarded one (`server.forward-headers-strategy: framework` in `prod`). |
| T12 | **Abuse from inside (an employee mass-generating links)** | `orders.tracking_link:manage` is its own permission (admins and planners, not viewers); at most 5 live links per order; every generate and every revoke is an audit event on the order (`TRACKING_LINK_CREATED`, `TRACKING_LINK_REVOKED`) carrying the link id and expiry, never the token. |

**Accepted risks, stated:** the link is a bearer credential - whoever has the WhatsApp message sees the
status of that one order until it expires or is revoked. The token is in the URL path, so the hosting
platform's access logs for the static frontend (and any reverse proxy in front of the API) see it; that
is inherent in a shareable link and is why T3 caps its life. The in-memory rate limit is per instance; a
CDN/WAF in front of the deployment is the outer layer and is not this ADR's to configure.

## Decision

1. **One table, `tms.tracking_link`** (V74): `company_id`, `order_id` (composite FK to the order, ADR-003),
   `token_hash` (64 hex, unique), `expires_at`, `revoked_at` / `revoked_by` / `revoke_reason`
   (all-or-nothing), `created_by`, `last_viewed_at`, `view_count`. No `DELETE` for `tms_app`; a trigger
   keeps identity columns immutable and revocation one-way, against the owner too.
2. **Owned by `orders`.** The link is "per order", the permission is `orders.tracking_link:manage`, and
   the endpoints hang off the order: `GET|POST /api/v1/orders/{id}/tracking-links`,
   `POST /api/v1/orders/{id}/tracking-links/{linkId}/revoke`. The execution facts (trip, stop, ETA,
   delivery) belong to planning and are read through a new port, `OrderTrackingPort`, implemented by
   planning in SQL over its own tables - the direction `OrderFulfillmentPort` already established.
   A trip stop's share action calls the same order endpoint for the order on that stop: a link is never
   scoped to a stop or a trip, because a stop serves several customers.
3. **Public read: `GET /api/v1/public/tracking/{token}`**, read-only. Resolution in two steps on two
   connections: hash → `(link id, company)` on the owner connection, then the projection under a
   `PublicTrackingAuthentication` that binds the connection to that company as `tms_app` and carries no
   authorities. The view counter is updated in the scoped step. The commercial gate (ADR-017) is
   consulted: a suspended contract serves no link. A tenant suspension (ADR-021) does not stop reads, so
   it does not stop the link either.
4. **The projection** (`PublicTrackingView`): company display name; order number and external
   reference; destination **name**; overall status `RECEIVED | PLANNED | IN_TRANSIT | ARRIVED |
   DELIVERED | PARTIALLY_DELIVERED | REJECTED | NOT_DELIVERED | CANCELLED`; a timeline
   (registered, planned, en ruta, llegó, outcome) with the instants that are known; an arrival window
   for the stop while it is still ahead (the ETA of ADR-011 widened to a one-hour window, else the
   stop's service window); carrier display name; plate per company setting; POD summary (receiver's
   first name and initial, delivered at); the company's time zone; and the link's effective expiry.
   Draft trips are not shown - a plan nobody confirmed is not a promise to a customer.
5. **Plate per company**: `company_settings.tracking_plate_display` (`MASKED` default, `HIDDEN`). There
   is deliberately no `FULL`: a full plate plus a time and a place is more than "where is my order"
   needs.
6. **Office UX**: «Compartir seguimiento» in the order drawer and on each order of a trip stop. The raw
   token is shown once, right after generation; copy link and «Enviar por WhatsApp», which opens
   `https://wa.me/?text=<prefilled Spanish message>` - no WhatsApp API, no phone number stored. The
   list shows each link's state, expiry and views, and revokes with a reason.
7. **Public page**: `/t/:token` in the frontend, outside every authenticated layout, eTMS brand,
   Spanish/English, built for a 390 px phone, calling the API without an `Authorization` header.

## Consequences

- The first anonymous tenant-data path exists, behind its own chain; `EndpointContractTest` lists it
  under `UNGUARDED_BY_DESIGN` with this ADR as the reason.
- Notifications (sending the link automatically on dispatch) stay out: there is still no mail/SMS
  transport in the product. The link is shared by a person.
- Live map position is not in the projection: positions inform the office (ADR-007) and the customer
  page would become a fleet tracker for anyone with the message.
