# Carrier tendering V1

Migration: `V31__carrier_tendering.sql`. Module: `com.ebim.tms.planning`.
Screens: the tender card inside `/trips/{id}`.
Carrier-facing API: `GET /integration/v1/tenders`, `POST /integration/v1/tenders/{shipmentNumber}/response`.

## 1. What this is, and what it deliberately is not

Until V31 a confirmed shipment named a carrier and nothing said whether that carrier had agreed to
run it. The agreement happened - on the phone, over WhatsApp, in a mail thread - and TMS held no
trace of it, so "did ACME accept SH-00000142, and when" was a question answered by scrolling
somebody's inbox. It is exactly the kind of fact that matters most on the day it is disputed.

V1 answers three questions:

1. **Was this shipment offered to its carrier, when, and on what terms?**
2. **What did they say, and if they said no, why?**
3. **How many times have we tried to place it?**

It is **not** a marketplace. There is no bidding, no counter-offer, no ranked carrier list, no
automatic waterfall down a preference order and no spot rate. Section 9 lists what was left out and
what each would need first. The rule applied throughout: *one shipment, one carrier, one live offer
at a time, and every attempt kept.*

## 2. The tender

One row of `tms.trip_tender` is one **attempt** to place one shipment with one carrier.

| Part | Meaning |
|---|---|
| `trip_id` + `attempt` | which shipment, and the nth try at placing it (from 1) |
| `carrier_id` | who it was offered to. Always the trip's own carrier - see 3 |
| `status` | `DRAFT`, `SENT`, `ACCEPTED`, `REJECTED`, `EXPIRED`, `CANCELLED` |
| `offered_amount` + `currency` | what is being paid, or neither. Optional as a pair |
| `notes` | instructions travelling with the offer, addressed to a person |
| `expires_at` | the deadline, optional - see 6 |
| `sent_at` / `sent_by` | when the offer left, and who released it |
| `responded_at` + `response_source` + `response_notes` | the answer, and whether it came from a person here or from the carrier's own system |
| `expired_at` | when TMS *resolved* that the offer had lapsed, which is not `expires_at` |
| `cancelled_at` + `cancel_reason` | the withdrawal, and why |

Nothing is ever deleted. A rejection stays a rejection after attempt 2 is accepted, because the
history of who was asked and what they said is the product.

### 2.1 Why `DRAFT` is a state

The test migration V25 applied to `DISPATCHED` - *would the two rows differ by anything but a
name?* - is the one `DRAFT` passes and `DISPATCHED` failed.

A draft tender is **editable and publishes nothing**. A sent tender is **frozen and has left a row
in the outbox that a carrier can already read**. Those are different rows with different rules. A
planner who mistyped `1.200,00` as `12.000,00` fixes it while it is a draft; afterwards they
withdraw the offer and send another, which is a second attempt and is recorded as one.

That also means creating a draft is not audited and not published. `TENDER_SENT` is the first moment
anything leaves this company.

### 2.2 The two invariants the database owns

| Index | Rule |
|---|---|
| `uq_trip_tender_live` | at most one `DRAFT`/`SENT` tender per trip |
| `uq_trip_tender_accepted` | at most one `ACCEPTED` tender per trip, ever |

The service refuses both first, with a sentence a planner can read. The indexes are the backstop for
what a row lock cannot cover - a planner and a carrier served by two application instances at the
same instant. The second one is the invariant the whole feature exists to guarantee: **exactly one
carrier has agreed to run this shipment**, and no sequence of retries can produce a second.

## 3. Which shipments, and which carrier

A shipment may be offered while it is `CONFIRMED` or `READY_FOR_DISPATCH`.

* **Never a `DRAFT` trip.** Its stops, load and vehicle can all still change. The outbound shipment
  API already refuses to expose a draft for exactly that reason, and showing a carrier a plan that
  can be rewritten under them would be worse than showing them nothing.
* **Never after departure.** At that point who is running the shipment is a fact, not an offer.

The carrier is **always the trip's own** - `TripTenderService` refuses anything else. This is not a
limitation of the table, it is the shape of the model above it: a trip's carrier comes from the
vehicle planned on it (V11), and the vehicle may only be swapped while the trip is a draft
(V11/V25). So from the moment a shipment is offerable, who would run it is already decided, and a
tender naming a second carrier would produce a shipment whose accepted tender and whose
`carrier_id` disagree.

### 3.1 What that costs, stated plainly

**A rejected shipment cannot be re-offered to a different carrier without cancelling and replanning
it.** That is the one thing an operator will want that V1 does not do.

The fix is not in tendering. It is in the trip lifecycle: swapping a vehicle on a *confirmed*
shipment would have to re-derive the capacity snapshot the plan was validated against
(`docs/domain/CAPACITY_MODEL.md`) and re-publish a shipment a partner has already been handed. That
is a bigger decision than tendering should make on its way past, and it needs its own ADR.

What V1 does support is re-offering to the **same** carrier: a lapsed or withdrawn offer frees the
live slot, and attempt 2 can carry a different price, a different deadline and different
instructions. In practice that is the negotiation.

## 4. The lifecycle

```
DRAFT ──send──▶ SENT ──accept──▶ ACCEPTED
  │               ├──reject──▶ REJECTED
  │               ├──(deadline)──▶ EXPIRED
  └──withdraw─────┴──withdraw──▶ CANCELLED
```

Terminal: `ACCEPTED`, `REJECTED`, `EXPIRED`, `CANCELLED`. There is no move back. A carrier who
changes their mind is a second attempt, because the fact that they first said no is exactly the
history worth keeping.

The transition table lives in `planning.domain.TenderStatus`, testable without Spring or a database.
`TripTenderService` consults it for its caller-facing refusals and `TripTender` asserts it again as a
last line of defense - the same two-layer shape `TripStatus`/`Trip` uses.

### 4.1 A rejection always carries a reason

Enforced three times, which is this module's shape for any rule a `CHECK` can express:
`TripTenderService.requireReason` refuses with a sentence, `TripTender.reject` asserts it, and
`ck_trip_tender_rejection_has_reason` is the backstop. "They declined" with no reason is the answer
that helps a planner least, precisely when they have to decide what to do next.

A withdrawal carries one for the same reason `TripService.cancel` requires one on a confirmed trip.

## 5. What happens to an offer when the shipment moves

Two lifecycle hooks, and both are load-bearing rather than tidy-up:

| Trip transition | Effect on a live tender |
|---|---|
| cancelled (`TripService.cancel`) | withdrawn, reason names the cancellation |
| dispatched (`TripExecutionService.dispatch`) | withdrawn, "departed before the carrier answered" |

Without the first, **a carrier could accept a shipment that is not happening** - the one way this
feature could send a truck to a depot for nothing. Without the second, a shipment that left without
an answer would keep an offer live that nobody can act on and that occupies the trip's live slot for
good.

Departure does **not** refuse over an unanswered tender. A shipment leaving before its carrier
replied is an ordinary bad day, not an illegal state, and refusing to dispatch over it would stop a
truck that is already loaded. The trip workspace shows the tender state beside the dispatch button
and lets a dispatcher decide, which is where that judgement belongs in V1.

Likewise, **nothing requires an acceptance before dispatch**. An installation that never tenders
must still be able to send a truck.

## 6. Expiry, and why there is no job

This installation runs no scheduler - there is not one `@Scheduled` method in the backend - and
introducing one for this feature would drag in how it behaves across two application instances. So a
lapse is resolved in two places:

* **On every read.** `TripTender.effectiveStatus(now)` reports a `SENT` tender past its deadline as
  `EXPIRED`. Every view, every guard and every carrier-facing response goes through it, so no screen
  shows a dead offer as live and no response is ever accepted after the deadline. This is the half
  that matters for correctness, and it writes nothing.
* **On the next write that touches the trip's tenders and succeeds.** `TripTenderService.resolveLapse`
  materialises the lapse into the table, audits it and publishes `TENDER_EXPIRED` at the deadline
  (not at the moment of resolution - the offer died when it said it would). This is the half that
  matters for reporting, and it is what frees `uq_trip_tender_live` for the next attempt.

  "And succeeds" is load-bearing. A call that resolves a lapse and then refuses - a carrier
  answering a second late - rolls its own write back with the rest of the transaction, and the next
  caller resolves it again. Nothing about correctness depends on which happens, because every
  refusal is computed from `effectiveStatus` and needs no write at all. Withdrawing a lapsed offer
  is deliberately *not* a refusal for this reason: it answers with the tender, so the lapse sticks.

**The consequence, stated rather than hidden:** a tender that lapses and is never touched again keeps
`status = 'SENT'` in the table while every API and every screen calls it `EXPIRED`. A report reading
the column directly must apply the same rule - `status = 'SENT' AND expires_at < now()` - which is
why `ix_trip_tender_company_outstanding` indexes `expires_at`.

The day a reliable scheduler exists, a sweep on a timer is a new class and changes nothing here: the
states, the columns and the reads are already correct.

## 7. How a carrier answers

Two ways, and TMS records which:

| `response_source` | Who | How |
|---|---|---|
| `OPERATOR` | a person at the shipper | the tender card in `/trips/{id}`, after a phone call or a mail |
| `INTEGRATION` | the carrier's own system | `POST /integration/v1/tenders/{shipmentNumber}/response` |

The distinction is evidentiary, not cosmetic. An acceptance typed in by a colleague and one signed by
the carrier's credential are worth different things when the load does not turn up.

### 7.1 The credential is bound to one carrier

`tms.integration_client.carrier_id` (nullable, added by V31) is what makes
`integration.tender:respond` mean *"answer my tenders"* rather than *"answer anybody's"*. The
endpoints resolve the carrier from the credential and never from a payload or a header, exactly as
the company is resolved. A credential holding the scope with no carrier is refused with a message an
administrator can act on - **never** a fallback to the company, which would hand one partner every
carrier's offers.

`IntegrationClientService` enforces both directions: the scope requires a carrier, and a carrier
without the scope is refused as a field that would mean nothing.

### 7.2 What a carrier can see

`CarrierTenderOffer` is the only read in TMS whose audience is outside the company, and what it
omits is the design: no vehicle, no licence plate, no driver, no capacity figures, no order numbers,
no customer names, and no TMS uuid anywhere. What is left is which shipment, when it runs, from
where, how big the job is (`stopCount`), what is being paid, and by when they must answer.

A shipment this carrier has no tender on answers 404 with the same sentence a shipment that does not
exist gets, so the endpoint cannot be used to enumerate the shipper's business.

### 7.3 Idempotency

Re-sending the **same** decision returns the answer already recorded - what an at-least-once sender
needs. Sending the **opposite** decision is refused with 409: reversing a commitment is not a retry,
and it needs a person.

An `Idempotency-Key` works as it does everywhere else in the inbound API. The shipment number travels
in the path but is folded into the fingerprinted payload (`TenderResponseEnvelope`), so a key reused
across two shipments with the same decision is a 409 rather than a silently replayed answer for the
wrong shipment.

### 7.4 There is no carrier portal

A web portal needs external identity, invitation, password reset and session management for users
who are not in `tms.app_user`. That is a product, not an endpoint. The two ways above cover the two
kinds of carrier a mid-market shipper actually has: one with a system, and one with a phone.

## 8. What is published

Every transition reaches three audiences at once through `ShipmentEventPublisher`, in the same
transaction as the fact itself:

| Event | Outbox (`shipment_outbox_event`) | Timeline (`transport_event`) | Audit (`audit_event`) |
|---|---|---|---|
| sent | `TENDER_SENT` | `TENDER_SENT` | `TENDER_SENT` |
| accepted | `TENDER_ACCEPTED` | `TENDER_ACCEPTED` | `TENDER_ACCEPTED` |
| rejected | `TENDER_REJECTED` | `TENDER_REJECTED` | `TENDER_REJECTED` |
| lapsed | `TENDER_EXPIRED` | `TENDER_EXPIRED` | `TENDER_EXPIRED` |
| withdrawn | `TENDER_CANCELLED` | `TENDER_CANCELLED` | `TENDER_CANCELLED` |

Nothing is published for a draft, in either direction: it was never sent, so there is nothing to
withdraw and nobody to tell.

These are the first outbox events whose audience is the **carrier** rather than the shipper's own
back office. `TENDER_SENT` is how an integrated carrier learns there is an offer waiting;
`TENDER_CANCELLED` is how they learn it was withdrawn before they answered. Without those two,
`GET /integration/v1/tenders` would be an endpoint they had to poll blind.

The outbox row still carries the shipment number and nothing else, exactly as V20 designed it.

Audit events are recorded against `SHIPMENT`, not a new aggregate type - the same call V28 made for
delivery results. What changed commercially is the shipment; the tender is *how* it changed, and its
id and attempt number travel in the metadata.

Nothing here calls an external system inside a transaction. A partner learns about these events by
polling the shipment event feed, which is the whole point of the outbox.

## 9. Authorization

| Permission | Who has it by default |
|---|---|
| `planning.tender:read` | `ORGANIZATION_ADMIN`, `COMPANY_ADMIN`, `PLANNER` |
| `planning.tender:manage` | `ORGANIZATION_ADMIN`, `COMPANY_ADMIN`, `PLANNER` |
| `integration.tender:respond` | granted per credential, never to a role |

Its own resource rather than part of `planning.trip:manage`, for the reason V25 split
`planning.trip:execute` out: offering a load at a price is a commercial act, building the plan is
not, and an installation may want the two in different hands.

`VIEWER` gets neither, which is the call V30 made for rates and for the same reason: a tender carries
an offered price, and what one company offers another is commercially sensitive by default. An
installation that wants read-only accounts to watch tender status grants the permission on purpose.

Both permissions map to the existing `TRIPS_VIEW`/`TRIPS_MANAGE` capabilities: tendering has no
screen of its own, it is a card on the trip workspace, and a capability answers "should this menu
entry be visible", never "may this caller do it".

## 10. Module boundaries

Tendering lives in `planning` rather than in a module of its own, unlike costing. Costing earned one
because it answers to different people - planning is judged on whether the truck went out, costing on
whether the invoice matched. Tendering answers to the *same* people, it is gated by the trip's own
lifecycle in both directions, and it publishes through `planning`'s outbox. A module boundary here
would have bought three ports and no separability.

The one boundary that does exist is the carrier's:

```
integration.api.IntegrationTenderController
  └─> integration.application.IntegrationTenderService   (wire contract only)
        └─> shared.reference.CarrierTenderPort
              └─> planning.infrastructure.CarrierTenderAdapter
                    └─> planning.application.TripTenderService   (every rule)
```

The adapter is a pass-through on purpose. Every rule about what may be answered and when lives in one
service, so the M2M path and the UI path cannot diverge on any of them.

## 11. Deliberately not in V1

* **No multi-carrier tender and no bidding.** Offering one load to three carriers at once needs a
  rule for what happens when two accept - first wins? cheapest? the planner chooses? - and that is a
  commercial policy per company, not a schema decision.
* **No automatic waterfall down a carrier preference list.** The natural next feature, and it needs
  two things that do not exist: an ordered carrier preference per lane, and a scheduler to advance
  the waterfall when an offer lapses (section 6).
* **No counter-offer and no negotiation thread.** A carrier answers yes or no. One who wants a
  different price says so in `response_notes`, and the planner sends attempt 2.
* **No reassignment to a different carrier after a rejection.** See 3.1.
* **No acceptance requirement before dispatch.** See 5.
* **No link between `offered_amount` and `tms.trip_cost`.** The estimate is what the tariff says the
  shipment should cost; the offer is what somebody offered on the day. Letting either overwrite the
  other would destroy the comparison that makes both worth having.
* **No carrier-facing web portal.** See 7.4.
* ~~**No UI for binding a credential to a carrier.**~~ **Closed by job 13.** V31 added the field to
  the existing `POST/PUT /integration/clients` contract and stopped there, because the module was
  API-only at the time. The Integration Hub (`/settings/integrations`) now issues credentials from
  the browser, and `IntegrationClientDrawer` reveals the carrier field exactly when
  `integration.tender:respond` is ticked - the same rule the backend enforces, stated once more in
  the form so it does not offer a combination the API will refuse.

## 12. Tests

| File | What it pins |
|---|---|
| `planning/domain/TenderStatusTest` | the transition table, terminality, and that `DRAFT` is the only editable state |
| `planning/domain/TripTenderTest` | the entity's own rules: the offer pair, the deadline, the response source/actor pairing, and `effectiveStatus` |
| `planning/application/TripTenderServiceTest` | the service rules - tenderable states, one live attempt, one acceptance, lapse resolution, and what each transition publishes |
| `pages/trips/TripTenderCard.test.tsx` | what the card renders and which buttons it offers per state |

The database half - the two partial unique indexes actually refusing a second row, the CHECK
constraints, the outbox row landing - belongs to the Testcontainers integration tests and needs
Docker.
