# TMS by EBIM - carrier selection and the tender waterfall (V1)

Owner: `com.ebim.tms.planning`. Schema: `V31__carrier_tendering.sql`, `V40__tender_waterfall.sql`.

## 1. What V31 could not do

V31 built tendering properly: offer, accept, reject, withdraw, an immutable history of attempts, and
*exactly one acceptance per shipment* as a database fact. What it could not do is the thing that
actually fills a truck — **when the first carrier says no, offer it to the second.**

Until now that was a person watching a screen. Every rejection at 19:40 waited until somebody
noticed; every deadline that lapsed overnight went unanswered until morning.

## 2. The ranking

    1. A price beats no price
    2. Cheapest first, among comparable currencies
    3. Then by carrier code

**"No tariff entered" is not "free."** A carrier with no applicable rate card is still a candidate —
a dispatcher may well want to offer to somebody they have no tariff for — but it ranks **last**.
Reading an absent price as zero would put the one carrier nobody has an agreement with at the top of
every list.

**Currencies are not converted.** This product invents no FX rate (V30). A quote in another currency
ranks after every comparable one and is marked, rather than converted at a rate nobody agreed to or
dropped as though the carrier had no price. The *majority* currency is the reference, so a single
mis-keyed card cannot invert the whole list.

**Ranking by code is not a business rule** — it is what makes the ranking reproducible. Two carriers
quoting the same figure would otherwise swap places between runs, and "why did this go to the third
carrier" would have no stable answer.

## 3. The ranking is stored, not recomputed

The obvious design re-ranks at each step. It is wrong for the same reason a rate snapshot exists:
rate cards change and carriers are deactivated, so a shipment ranked on Monday and re-ranked on
Tuesday would walk a different list than the one anybody approved.

So candidates are written once, **with the price each was ranked on and the card that produced it**,
and the waterfall walks that list.

## 4. The sequence

    Carrier A → REJECTED   →  offer to B
    Carrier B → EXPIRED    →  offer to C
    Carrier C → ACCEPTED   →  waterfall ends

Rejections advance the waterfall **in the same transaction** as the rejection itself, so the tender's
status and the candidate's cannot disagree.

Candidates never reached become `SKIPPED`, not left `PENDING`: a finished waterfall showing pending
candidates reads as one still waiting to continue.

### 4.1 Every answer reports here, whoever gave it

`TripTenderService` tells the waterfall about **every** outcome, and from every door: the acceptance
or rejection a colleague types in, the one the carrier's own system sends over
`POST /integration/v1/tenders/{shipmentNumber}/response`, the withdrawal a planner makes by hand,
and the shipment that stops being offerable because it was cancelled or departed.

Until this was true the integration path wrote the tender and told nobody, and the two records the
sequence above depends on came apart exactly where it mattered most — on the carrier that has a
system rather than a phone:

| What arrived over the M2M API | What the waterfall showed |
|---|---|
| the carrier accepted | still `ACTIVE`, candidate still `OFFERED`, and the next `advance` walked on to a second carrier before `requireNotPlaced` refused it |
| the carrier rejected | candidate still `OFFERED` for good; a later `advance` recorded it as `EXPIRED`, which is not what happened |

**Recording an answer and sending the next offer are two different rights.** Anyone who may answer
may have their answer written down; only a person may commit the company to another offer — see §8.
So a rejection over the M2M API settles its candidate as `REJECTED` and the waterfall waits at that
rank for a dispatcher, exactly as it already waits after a lapse. Ending a waterfall commits the
company to nothing, so an **acceptance** ends it whoever sent it.

## 5. Guarantees

| Guarantee | How |
|---|---|
| One active tender per shipment | `uq_trip_tender_live` (V31) |
| One acceptance per shipment, ever | `uq_trip_tender_accepted` (V31) |
| One running waterfall per shipment | `uq_tender_waterfall_active` (V40) |
| One candidate per rank, one per carrier | `uq_twc_rank`, `uq_twc_carrier` |
| Response deadline | `response_minutes`, snapshotted on the waterfall |
| Max attempts | `max_attempts`, counted in the aggregate |
| Idempotency | `finish` is a no-op on a finished waterfall; `offerNext` is a no-op while an offer is out |
| A repeated carrier response advances nothing twice | the replay returns before the tender changes, so the waterfall is never told a second time (V31 §7.3) |
| No waterfall outlives its shipment | `withdrawOpen` ends it when the trip is cancelled or dispatched, with or without a live tender to point at |
| Concurrency | every mutation takes the **trip's** row lock, the same point every other trip write uses |
| Immutable history | attempts are appended; nothing is rewritten |

**The response deadline is snapshotted on the waterfall** rather than read from settings at each
step, so lengthening the company default does not silently extend an offer already out.

## 6. What it is never allowed to do

- **It never accepts.** A carrier accepts, through the same path a manual tender uses.
- **It never dispatches.** V31's rule, unchanged.
- **It never reassigns a vehicle** — see §8.

## 7. The manual override

`POST /tenders/waterfall/stop` ends the waterfall and **withdraws the offer that is out**, so a
carrier cannot accept a shipment whose waterfall a planner has just stopped. A tender withdrawn by
hand also ends the waterfall: withdrawing is a decision to stop, not a refusal to route around, and
continuing down the list would re-offer a shipment somebody just pulled back.

`stop` ends the waterfall **before** it withdraws the offer, and that order is load-bearing rather
than tidy: a withdrawal reports itself to the waterfall (§4.1), so a still-active waterfall would be
ended a second time under *"the offer to rank N was withdrawn by hand"* and lose the reason the
planner actually gave. `finish` is idempotent, so the second notification finds a finished waterfall
and does nothing.

## 8. Two limits, stated plainly

### There is no background scheduler

Creating a tender goes through `AuditActorProvider.requireAppUserId`, which **refuses a machine by
design** — *"this operation is restricted to an interactive user."* That rule predates this feature
and it is right: an offer to a carrier is a commercial commitment and the trail has to name the
person who made it.

A background sweep offering on a company's behalf would need a system-actor concept this product
does not have. Inventing one at speed would put an unattributable commercial commitment into the
history, so instead: the waterfall **reports** `currentOfferLapsed` (computed on read, never stored)
and a dispatcher advances it with one click.

The same rule has a second consequence, and it is the honest price of §4.1: **a rejection that
arrives from the carrier's own system does not send the next offer either.** The request is a
machine's, so there is no `app_user` to sign the commitment it would make. The candidate is settled
`REJECTED` — that much is a fact, and recording it is the whole point — and the waterfall waits at
that rank for a dispatcher's `advance`, which is the click it already waits for after a lapse. The
alternative, letting `requireAppUserId` throw from inside the carrier's own request, would throw the
carrier's answer away over a rule that has nothing to do with it.

**The follow-up this needs**, if unattended tendering is wanted: a first-class system actor with its
own audit identity, a `@Scheduled` sweep using `FOR UPDATE ... SKIP LOCKED` so multiple instances do
not duplicate work, and a company setting to opt in. That is a design, not a patch, and
`TenderWaterfallService.offerNextIfAttributable` is the one place it plugs into.

### Accepting does not reassign the vehicle

A shipment's carrier is **the owner of its assigned vehicle** (`Trip.assignVehicle` sets both). A
waterfall offers the shipment to carriers that do *not* own its vehicle, which is what subcontracting
is — so an acceptance is recorded, and putting one of the accepting carrier's vehicles on the trip
stays an explicit planner action.

Doing it silently would leave a trip whose `carrierId` and whose vehicle's owner disagreed, which is
worse than an extra click.

## 9. Audit

Two actions: `WATERFALL_STARTED` and `WATERFALL_ENDED`. Every step in between already produces
`TENDER_SENT`, `TENDER_REJECTED` or `TENDER_EXPIRED` against the shipment; a parallel row per step
would duplicate the trail rather than extend it. What the two add is what the per-tender rows cannot
carry: who decided to run a waterfall at all and over how many candidates, and how it ended after how
many offers.

## 10. Metrics

`tms.tender.waterfall` (started / stopped / accepted / exhausted / cancelled) and
`tms.tender.waterfall.advances` (offered / accepted / rejected / expired / withdrawn).

## 11. Not here

- **No carrier capacity model.** Ranking uses price, applicability and active status — facts the
  product holds. *"How many trucks does this carrier have free on Thursday"* needs a real
  carrier-capacity feature, not a column guessed at.
- **No spot-market or broadcast tendering.** This offers to one carrier at a time, in order. Offering
  to five at once has different fairness and pricing consequences and is a separate product decision.
- **No automatic acceptance**, ever.
