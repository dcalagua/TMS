# Tracking V1 - where the vehicles are

Migration: **V29**. Architecture decision: **ADR-007**. Module: `com.ebim.tms.tracking`.

## 1. What this exists for

One question, asked several times a day at a customer service desk:

> "the customer is on the phone asking where the delivery is, and the driver is not picking up."

V27 answers "what happened at each stop" and V28 "what was handed over", and both answers come from
a person pressing a button. Between two stops - most of a delivery day - there was nothing. This
fills that gap and does nothing else.

**It is not a telematics integration.** TMS ships with no vendor, no credential for one and no
adapter that speaks anybody's protocol. What ships is the normalised contract, the storage, the
sampling policy and the read, so that onboarding a provider is an implementation of an interface
rather than a redefinition of the model. See ADR-007 for why that order matters commercially.

## 2. The one rule everything else follows

**Positions inform people; people record facts.**

Nothing in TMS reads `tms.tracking_position` except the screen that draws it. No status is derived
from a position, no stop is closed by one, no exception opened by one, no timeline entry written for
one. A vehicle standing at a customer's gate and a vehicle standing in traffic outside it produce
the same point.

The consequence is the reason this feature is safe to run at all: **losing this table entirely would
cost a map and no business fact.** A feed that is broken, hostile, absent or two hours behind cannot
corrupt a delivery record, block a dispatch or move a lifecycle.

## 3. The model

`tms.tracking_position`, one row per kept position:

| Column | Meaning |
| --- | --- |
| `trip_id` | the shipment. NOT NULL: TMS only accepts positions it can attach to one |
| `occurred_at` | when the device was there, as the feed reports it |
| `received_at` | when TMS stored it. The gap to `occurred_at` is feed latency |
| `latitude`, `longitude` | NOT NULL - a position without coordinates is not a position |
| `speed_kph`, `heading_degrees` | optional, as measured. Never derived by TMS |
| `provider` | which feed said so, as a lowercase slug |
| `external_vehicle_reference` | the provider's own id for the vehicle, for traceability |
| `correlation_reference` | the provider's own id for this ping |

Append-only in practice: `UPDATE` is revoked from `tms_app`. A measurement is never corrected, only
superseded by the next one.

**No raw payload is ever stored.** A telematics document routinely carries the driver's identity,
their device identifiers and their movements off shift - personal data TMS has no purpose for. The
correlation *reference* is what makes "we sent it and you do not have it" answerable without holding
any of it. There is also no driver column here: the trip already names its driver (V26), and copying
that onto half a million rows would turn a fleet feed into a per-employee movement record with a
different legal weight and no operational gain.

### Why not `tms.transport_event`'s latitude/longitude

V27 left those two columns unused and said they were for the day a feed existed. This is that day,
and they are still the wrong home, for three reasons:

- **Volume.** A trip produces ~12 transport events a day and, at one point a minute, ~500 positions.
  The timeline query a dispatcher runs constantly would scan a table that is 98% pings.
- **Attribution.** `ck_transport_event_actor_xor` is load-bearing - "a log the client can sign
  somebody else's name to is not a log". A feed has no actor and would need a fake one.
- **Lifetime.** A log is kept forever; a feed is purged. That difference is why this table has a
  `DELETE` grant and `tms.transport_event` does not.

Those columns keep their original meaning: the optional position of a *reported fact* ("I arrived,
and here is where I was standing").

## 4. Getting positions in

`POST /integration/v1/tracking/positions`, machine-to-machine, scope `integration.tracking:write`.

```json
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

- **Shipments are named by number, never by uuid.** A partner already has shipment numbers from the
  outbound `ShipmentPlan V1` contract and from the paperwork; an API demanding our primary keys
  would force every telematics integration to keep a mapping table it has no other use for.
- **The tenant comes from the credential.** Nothing in the body selects a company, and there is no
  header that could.
- **`provider` is a label, not an authority.** It is in the body rather than derived from the
  credential because one credential legitimately relays several upstream feeds (a 4PL forwarding two
  carriers' telematics), and forcing a credential per feed would make onboarding a subcontractor a
  key-management exercise. A sender that mislabels its own feed has mislabelled only its own feed.
- **One endpoint, not two.** A device reporting live sends a batch of one; a device flushing a
  buffer sends a batch of two hundred. Same operation, same rules.
- **There is no user-token endpoint for reporting a position.** That would be a driver app, which
  TMS does not have.

### Per-item outcomes

Every position gets its own answer at the index it was sent. Four of the seven mean "we have it,
you are done" - only the last three are refusals a sender can act on, and only they make the
response a 207.

| Outcome | Accepted? | Meaning |
| --- | --- | --- |
| `RECORDED` | yes | stored |
| `DUPLICATE` | yes | this feed already reported this shipment at this instant |
| `THINNED` | yes | closer to the last kept point than the sampling interval |
| `STALE` | yes | older than the newest position already held for this shipment and feed |
| `UNKNOWN_SHIPMENT` | no | this company has no such shipment |
| `NOT_TRACKABLE` | no | the shipment exists but has not left, or was cancelled |
| `INVALID` | no | coordinates out of range, a time in the future, a malformed provider slug |

The three accepted-but-not-stored outcomes are reported rather than folded into one "accepted"
because they are how a partner tunes their own sender without asking us: 95% `THINNED` means they
are pushing ten times faster than we keep, and any `STALE` means they are delivering out of order,
which no amount of retrying fixes.

`UNKNOWN_SHIPMENT` says only that *this company* has no such shipment - never whether it exists
elsewhere. A telematics credential is often held by a third party who should learn nothing about the
tenant's shipments beyond whether the numbers they were handed are usable.

### Idempotency

Two mechanisms, and they are not alternatives - the same pairing the rest of the inbound API uses:

1. **Business identity** - `(company, shipment, provider, occurred_at)`, enforced by
   `uq_tracking_position_feed_instant`. Redelivery is a no-op, so an at-least-once sender needs no
   cursor, no de-duplication of its own and no coordination with us.
2. **`Idempotency-Key`** - optional, and covers the case business identity cannot: a sender that
   never learned the outcome and wants the original response back rather than a second one.

## 5. Volume and retention

This is by design the largest table in the schema, and the sampling rule is what keeps its size a
function of the fleet rather than of a vendor's default push rate.

**The rule.** At most one position per `tms.tracking.min-interval` (default 60s) per
(shipment, feed). Denser points are accepted and dropped, never refused: a sender pushing every
second is not doing anything wrong, it is doing what its vendor's default does, and an API whose
scalability depends on every partner reconfiguring their equipment does not have a scalability
story.

**The arithmetic**, at the design target of 300 vehicles running 10 hours a day:

| `min-interval` | Points per vehicle-day | Rows per day | Rows at 30 days |
| --- | --- | --- | --- |
| 30s | 1 200 | 360 000 | 10.8 M |
| **60s (default)** | **600** | **180 000** | **5.4 M** |
| 120s | 300 | 90 000 | 2.7 M |
| 300s | 120 | 36 000 | 1.1 M |

5.4 M narrow rows is a table a b-tree on `(company_id, trip_id, occurred_at)` serves without help,
and every read TMS performs is bounded: "the newest row for this trip" or "the newest N". There is
no page-through API over the feed and no unbounded finder in the repository.

**Retention is an operational task in V1, and deliberately so.** The `DELETE` grant exists for it
and `ix_tracking_position_occurred_at` is its access path, but no scheduled sweep ships: TMS has no
job scheduler, and introducing one - with the multi-instance locking that implies - is a bigger
decision than this table justifies. A deployment keeping positions for 30 days runs the equivalent
of:

```sql
DELETE FROM tms.tracking_position WHERE occurred_at < now() - interval '30 days';
```

`tms.tracking.max-age` (default 24h) is the intake-side counterpart: a feed replaying its buffer
after an outage is normal and welcome, a feed replaying last month is misconfigured, and storing
what a sweep is about to remove helps nobody.

**When this stops being enough.** Partitioning by month is the next step, and it is a migration
rather than a redesign - the access paths do not change. That becomes worth doing somewhere north of
a few thousand vehicles, or when retention has to be measured in years for a customer's own
compliance reasons.

## 6. Reading it

`GET /tracking/trips/{tripId}`, permission `monitoring.transport:read` - which has been in the
catalogue since V3 and granted to the seeded roles since V5, and which until now was the one entry
with no endpoint behind it. Any trip status is readable: reviewing where a cancelled trip got to is
exactly what somebody does afterwards.

One document carries the last known position and a bounded recent trail, because the screen shows
both and two endpoints would be two round trips and two chances to disagree about which position is
newest.

Stored positions are answered first; `TrackingProviderPort` is consulted only when nothing has ever
been reported for that shipment, and never for a shipment that is not out on the road. A pulled
position is returned and **not** stored - see ADR-007.

### The three "no position" states are different states

A dispatcher does something different about each, so the document keeps them apart:

| `trackable` | `providerConfigured` | `lastPosition` | What it means |
| --- | --- | --- | --- |
| false | - | null | the shipment has not left, or was cancelled. Nothing to do |
| true | false | null | this deployment has no feed. Somebody's job, not today's dispatcher's |
| true | true | null | there is a feed and it has said nothing. **The one worth a phone call** |

## 7. On screen

The trip workspace (`/trips/{id}`) shows a **Location** card above the stops, and the vehicle's last
known position as a marker on the stop map.

- The marker is a different *shape* from the numbered stop markers, not a different colour of the
  same one: colour alone fails for a colour-blind dispatcher and fails again in a printout.
- It is kept out of the map's polyline. That line is the planned stop sequence; splicing a reported
  position into it would draw a route the plan never contained.
- It is drawn only while the shipment is out. A stored position from this morning on a trip that is
  already back is a true fact and a misleading pin - the card still reports it, with its age.
- The card shows the absolute time *and* how long ago, and turns to a warning past 15 minutes. That
  threshold is deliberately far above the 60-second sampling interval: a feed skipping a point or
  two is normal (a tunnel, a dead spot, a device rebooting), and a card that turned orange every
  time is a card people stop reading.
- Nothing in the copy says "real time", in any state. TMS stores at most one point per interval and
  the feed itself buffers; a label promising real time would be the UI making a claim the
  architecture deliberately does not.
- A tracking failure never takes the page down with it. The card is its own query with `retry:
  false`, so a provider outage costs the card and nothing else, and the card says so.

## 8. Security

The defence in depth is the ordinary one, with no exception made for a machine feed:

`credential -> scope -> company from the credential -> service -> company-scoped repository -> tenant FK -> RLS`

- `integration.tracking:write` is its own scope. A telematics vendor must not be able to create
  orders, and an ERP has no business reporting positions.
- It is write-only in effect: holding it grants no read anywhere. A provider pushing positions learns
  nothing about the shipments it pushes against beyond whether the numbers are usable.
- Shipment numbers are resolved *inside* the caller's company. A payload naming another tenant's
  shipment gets exactly what a payload naming a nonexistent one gets.
- `tms.tracking_position` carries the composite tenant FK `(trip_id, company_id)` and an RLS policy
  on `tms.current_company_id()`, so a query that lost its company predicate stops being a
  cross-tenant leak (ADR-005).
- Every delivery leaves an `integration_request` row, written in its own transaction, whatever it
  produced.

## 9. Deliberately not here

- **No geofencing and no automatic arrival detection.** Buildable on this data, and it moves
  accountability for the delivery record from the person who reported it to a box on a windscreen.
  That is a conversation with a customer, not a schema change.
- **No ETA and no delay calculation.** Both need a routing engine, and route optimisation is
  deferred by decision (`CLAUDE.md`).
- **No PostGIS geometry.** Every question V1 asks is "the newest row for this trip", which is a
  b-tree lookup. A spatial index earns its cost when something asks "which vehicles are within 2 km
  of here", and nothing does.
- **No vehicle-to-device registry.** The shipment number identifies the trip; a registry is what the
  *second* integration needs - the one that pulls by vehicle - and `TrackingProviderPort` is where
  that lands.
- **No live push to the browser.** Supabase Realtime and WebSockets are deferred by decision; the
  card is refreshed by the ordinary query, and a feed that updates once a minute does not justify a
  socket.
- **No backfill.** The feed starts when a provider is connected and says so by being empty before
  that.

## 10. Verification

| Gate | Where |
| --- | --- |
| Sampling, staleness, duplicates, validation, tenancy | `TrackingIngestionServiceTest` (unit, no DB) |
| The three "no position" states and the provider fallback | `TrackingQueryServiceTest` (unit, no DB) |
| The card's five states, the maps link, the staleness threshold | `TripTrackingCard.test.tsx` |
| Module isolation (`tracking` may not touch `planning`) | `ModuleBoundaryTest` |
| Schema constraints, RLS, the unique index | requires Docker/Testcontainers |
