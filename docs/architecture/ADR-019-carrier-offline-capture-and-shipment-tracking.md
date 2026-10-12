# ADR-019 - Carrier portal offline capture, and the dispatched-shipment tracking report

**Status:** Accepted (product owner, 2026-10-12).
**Date:** 2026-10-12
**Amends:** ADR-018 section 7 ("online-first, no offline queue") - withdrawn for the carrier portal only.
**Constrained by:** ADR-001, ADR-006, ADR-007 (positions inform, never move a lifecycle), ADR-013, ADR-018

## Context

Carriers deliver in zones with no coverage. A portal that needs a network at the customer's door
loses the report, or the photo, exactly where it matters. The product owner asked for the portal to
keep working without signal and to synchronise when it comes back.

The office also asked for a report of the shipments that actually left - the warehouse's dispatch,
ADR-013's "actual shipment" - beside the existing "Seguimiento de reparto", with a map. The existing
report is built on the Control Tower board and stays exactly as it is.

## Decision

### 1. Offline capture in the carrier portal (frontend only)

- **App shell** cached by a hand-written service worker (`public/sw.js`, no new dependency):
  navigations network-first with the cached shell as fallback, hashed assets cache-first. The
  service worker **never caches `/api` or `/integration` responses** - an HTTP cache shared by
  whoever next uses the phone is not where a customer's documents should sit.
- **Trip data** for offline reading is kept by the app in IndexedDB, keyed by user and company,
  written after every successful read and wiped on sign-out and on a change of user. The screen
  says how old it is.
- **Outbox**: a report made without network is stored in IndexedDB whole - fields, photos and
  signature as blobs, its `Idempotency-Key` and its `reportedAt` (the moment the carrier tapped
  "save", not the moment it synchronised). It is sent when the device is back online, on start-up
  and on demand, oldest first, with backoff. ADR-018's idempotency makes a resend safe: the server
  answers a replay with the current state and records nothing twice.
- **Outcomes**: 2xx removes the entry; a network failure or 5xx keeps it for later; 409 (the office
  already closed the document, or the key was used elsewhere) and 400 (rules) mark it **failed**,
  visible with the server's reason, for the carrier to discard or redo. Nothing is dropped silently.
- **Limits**: the outbox lives on one device; a report queued on a phone that is lost is lost. The
  screen counts what is waiting and asks before signing out while anything is.

No backend change is needed: `reportedAt` may be any time not in the future, and the key is stored
per user.

### 2. Dispatched-shipment tracking report (office)

`GET /api/v1/monitoring/shipment-tracking` and `/summary`, permission `monitoring.transport:read`.
One row per trip with a current warehouse dispatch (ADR-018 section 3's rule, any carrier): what left
(dispatch reference and time, plate and driver as the warehouse reported them, verification), stop
progress, and the document counts of ADR-018, with the stops' coordinates so the page can draw them.
Filtered by dispatch date (company zone), carrier, trip status, document state and free text.

The map shows the stops of the listed shipments and, for one shipment, its route and its last
reported positions through the existing `/api/v1/tracking/trips/{id}` (ADR-007). It is a report
refreshed on request or every minute, not a live fleet map: that remains deferred, and a position
still moves nothing.

The portal's stops also carry their coordinates (additive), so the carrier can open navigation.

## Consequences

- No migration. One read endpoint pair in planning, planning-owned tables plus ports for names.
- The portal works on a warm device without signal: it must have been opened online once on that
  device, and the data shown is the last copy read.
- Clearing the browser's data clears the outbox. The portal says so where it shows the outbox.
- **Open, needs a decision:** a cold start without signal after the Supabase access token expired
  (1 hour by default) cannot refresh the session, so the portal shows the sign-in screen. Queued
  reports stay on the device and are sent after the next sign-in; nothing is lost, but the carrier
  cannot read trips until then. Options: a longer token lifetime for carrier users, or an offline
  session mode in the frontend - either needs its own ADR, because it touches authentication.
- The service worker is registered for the whole app in production; office screens get only the
  shell and asset caching, never API data. `/sw.js` must be served with `Cache-Control: no-cache`.
