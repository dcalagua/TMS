# ADR-007 - Tracking is a normalised contract behind two ports, with no vendor adapter in V1

- Status: Accepted
- Date: 2026-08-21
- Relates to: `CLAUDE.md`'s "Deferred by decision" list, which names GPS/telematics and requires an
  ADR before it is introduced; ADR-006 (the same port-with-a-disabled-default pattern, for evidence
  storage); ADR-003 (Organization/Company tenancy); ADR-005 (tenant RLS).

## Context

TMS can answer "what happened at each stop" (migration V27) and "what was handed over" (V28), and
both answers come from a person pressing a button. Between two stops - which is most of a delivery
day - the record is silent, and the question a customer service desk actually spends its day on
lands in that silence:

> "the customer is on the phone asking where the delivery is, and the driver is not picking up."

That is the concrete requirement `CLAUDE.md` asks for before GPS may be introduced. It is also,
notably, **not** "put a truck on a map": the map is the presentation, the requirement is that
somebody at a desk can answer a question without phoning a moving vehicle.

The trap in front of this feature is not technical, it is commercial. Every prospect has, or will
have, a telematics vendor, and no two of them have the same API. The usual way this gets built is:
a customer arrives with a contract, an adapter is written against that vendor's JSON, and because
it is the only one, its field names, its polling model and its quirks become the internal model.
The second vendor then arrives and is either translated into the first vendor's shape - which
misrepresents it - or bolted on beside it, and the product now has two tracking subsystems.

Three ways this normally goes, two of which are expensive to undo:

1. **Build against the first vendor.** Fastest to a demo, and the point at which the internal model
   stops being ours. Undoing it later means changing every reader.
2. **Ship nothing until a vendor is signed.** Leaves the sales conversation with "no tracking", and
   guarantees that when a vendor *is* signed, (1) happens under a deadline.
3. **Build the abstraction with no vendor behind it.** Costs a normalised contract and a table now;
   makes the first vendor an implementation instead of a definition.

## Decision

Take (3). Introduce a `tracking` module that owns a normalised position contract, its storage, its
sampling policy and its read, and **no vendor code at all**. Vendors attach at two ports, chosen to
match the two shapes telematics providers actually come in:

- **Push** - `shared.reference.TrackingIntakePort`, implemented by `tracking`, called by
  `integration`'s machine-to-machine endpoint `POST /integration/v1/tracking/positions` under the
  new `integration.tracking:write` scope. **This half works today**: a provider, or a customer's own
  middleware, posts positions and TMS stores them. No vendor-specific code is required for it,
  because the wire contract is TMS's own.
- **Pull** - `tracking.domain.TrackingProviderPort`, whose only implementation
  (`DisabledTrackingProvider`) answers "not enabled" and returns nothing. This is the half with no
  vendor behind it. The interface exists so the first vendor implements an interface rather than
  becoming one.

The normalised contract is `shared.reference.TrackingReport`: shipment number, provider slug,
`occurredAt`, latitude, longitude, optional speed and heading, an optional external vehicle
reference and an optional correlation reference. Nine fields, and the omissions are deliberate -
odometer, fuel, engine hours, harsh-braking counters and driver-behaviour scores are what every
vendor also offers, none of them answers a transport question TMS asks, and several are personal
data about an employee.

Three rules the module keeps, and they are what make it safe to run:

1. **Positions inform people; people record facts.** No status is derived from a position, no stop
   is closed by one, no exception is opened by one, and no timeline entry is written for one. A
   vehicle standing at a customer's gate and a vehicle standing in traffic outside it produce the
   same point. Losing `tms.tracking_position` entirely would cost a map and no business fact.
2. **The tenant comes from the credential, never from the payload.** `provider` is a label on the
   data, and a shipment is resolved by number *inside* the caller's company - so a payload naming
   another tenant's shipment gets exactly what a payload naming a nonexistent one gets.
3. **Intake decides what is kept.** At most one position per configured interval per (shipment,
   feed); denser points are accepted and dropped rather than refused. A partner never has to
   reconfigure their equipment to talk to us, and the table's size is a function of the fleet
   rather than of a vendor's default push rate.

Storage is `tms.tracking_position` (migration V29) rather than the unused latitude/longitude columns
on `tms.transport_event`. Those two columns stay what V27 made them - the optional position of a
*reported fact* - because a feed differs from a log in volume (~500 points against ~12 events per
trip-day), in attribution (a log requires an actor; a measurement has none) and in lifetime (a log
is kept forever; a feed is purged).

Reading is `GET /tracking/trips/{tripId}` under `monitoring.transport:read` - a permission that has
been in the catalogue since V3 and granted to the seeded roles since V5, and which until now had no
endpoint behind it.

## Consequences

**Positive.**

- Tracking is sellable now. A prospect with any telematics vendor - or with none, using their own
  middleware - can be onboarded by pointing that vendor's webhook at one documented endpoint, with
  no code written by us.
- The first vendor adapter, when it comes, is one class in `tracking.infrastructure` and no change
  to any caller. So is the second.
- The blast radius is bounded by construction. Because nothing in TMS reads positions except the
  screen that draws them, a broken, hostile or absent feed cannot corrupt a delivery record, block a
  dispatch or change a lifecycle.
- `monitoring.transport:read` finally means something, and no company has to be granted a new
  permission to get the feature.

**Negative, and accepted.**

- `TrackingProviderPort` has no working implementation, which is an interface designed without the
  feedback of one. That is the cost of (3) over (1), and it is bounded by the interface being three
  methods wide and by the push half - the one with real traffic - not going through it.
- A pulled position is returned and not stored (`TrackingQueryService`), so a pull deployment has no
  history. Persisting it would mean a GET that writes; the right home is a poller with its own
  schedule and quota, which is the next decision and is deliberately not pre-empted here.
- No scheduled retention sweep ships with this. The `DELETE` grant exists for one and the policy is
  documented (`docs/domain/TRACKING_V1.md`), but TMS has no job scheduler, and introducing one -
  with the multi-instance locking that implies - is a bigger decision than this table justifies.
  Until then, retention is an operational task.
- A concurrent double-delivery of the same instant loses the unique-index race and fails that one
  delivery with a 500, which the sender's retry resolves as duplicates. Accepted rather than solved,
  because the alternative (locking per shipment on a write path designed for volume) costs more than
  the race does.

## Alternatives considered

- **Write an adapter for a named vendor now.** Rejected: no customer has one signed, and the first
  adapter written without an interface *is* the interface (Context (1)).
- **Reuse `tms.transport_event`'s position columns.** Rejected on volume, attribution and lifetime -
  see Decision. It would also make the timeline query, which a dispatcher runs constantly, scan a
  table that is 98% pings.
- **Accept positions on a user-token endpoint too, "for testing".** Rejected: that is a driver app,
  which TMS does not have, and a test-only write path is how an effectively unauthenticated write
  path gets into a product.
- **Derive stop arrivals from positions (geofencing).** Rejected for V1 and recorded in V29's
  "Deliberately NOT here": it is buildable on this data, and it moves accountability for the delivery
  record from the person who reported it to a box on a windscreen. That is a conversation with a
  customer, not a schema change.
- **Store the raw provider payload for debugging.** Rejected: a telematics document routinely
  carries the driver's identity, their device identifiers and their movements off shift. TMS keeps a
  correlation *reference* instead, which answers "we sent it and you do not have it" without holding
  personal data it has no purpose for.
