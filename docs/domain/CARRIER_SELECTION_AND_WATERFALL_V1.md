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

**The follow-up this needs**, if unattended tendering is wanted: a first-class system actor with its
own audit identity, a `@Scheduled` sweep using `FOR UPDATE ... SKIP LOCKED` so multiple instances do
not duplicate work, and a company setting to opt in. That is a design, not a patch.

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
