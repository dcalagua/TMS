# Rates and costing V1

Migration: `V30__rates_and_trip_costing.sql`. Module: `com.ebim.tms.rates`.
Screens: `/rates/rate-cards`, and the cost card inside `/trips/{id}`.

## 1. What this is, and what it deliberately is not

Until V30 a shipment could be planned, dispatched, delivered and closed without anyone being able
to say what it was worth. Everything operational was recorded and nothing commercial was, so "which
corridor is losing money" and "did this carrier invoice what we agreed" were questions that had to
be answered in somebody's spreadsheet.

V1 answers exactly two of them:

1. **What should this shipment cost**, according to the agreement in force on the day it runs.
2. **What did it actually cost**, according to the carrier's invoice - and how far apart the two are.

It is **not** a rate engine. There is no accessorial catalogue, no fuel index, no break table, no
lane matrix, no tariff-version graph, and no sell-side price. Section 8 lists what was left out and
what would have to exist first. The rule applied throughout: *a component TMS cannot prove from data
it already holds is not a component TMS offers*.

## 2. The rate card

One row of `tms.rate_card` is one agreement with one carrier.

| Part | Meaning |
|---|---|
| `carrier_id` | who is paid. Mandatory, and never editable - see 2.3 |
| `scope` + `origin_id`/`route_id` | how narrowly it applies: `CARRIER`, `ORIGIN` or `ROUTE` |
| `vehicle_type_id` | optional refinement; `NULL` means "any vehicle type" |
| `valid_from` / `valid_to` | inclusive dates; `valid_to` `NULL` is open-ended |
| `currency` | ISO 4217, three letters. No conversion anywhere in V1 |
| five component columns | base amount, per km, per kg, per m³, per pallet - at least one required |
| `minimum_amount` | a floor applied after the components |
| `active` | deactivated, never deleted |

### 2.1 Why there is no zone scope

A zone belongs to a destination. A trip serves many destinations. "Which zone is this trip in" has
therefore no answer that is *true* rather than convenient, and a rule like "the zone of the first
stop" would produce invoices nobody could defend. Charging by zone needs a per-stop cost model - a
cost row per `trip_stop`, allocated back to the orders - which is a larger feature than a fourth
value in a CHECK constraint. The three scopes V1 ships are the three a *trip* can be matched against
unambiguously.

### 2.2 `NULL` and `0` are different statements

A component column that is `NULL` means "this card does not charge for it" and produces **no line**
on the estimate. A component set to `0` means "it charges nothing for it" and produces a `0.00`
line, which proves the question was asked and answered. The distinction survives into
`tms.trip_cost_component`, which is why the estimate can be read as a complete account of the
tariff rather than as a list of the non-zero parts of one.

### 2.3 The carrier is immutable

`RateCardService.update` refuses a request that names a different carrier, with a message saying to
create a new card instead. Re-pointing an agreement at another counterparty would silently restate
every estimate that has already cited it, and "we moved the tariff" is not something that happens
commercially - a new carrier is a new negotiation.

### 2.4 Non-overlapping validity

Two **active** cards for the same `(carrier, scope, target, vehicle type)` may not have
intersecting validity periods. `RateCardService.requireNoOverlap` refuses one, naming the card it
collides with; `activate` re-runs the same check, because the rule only ever constrained active
cards and switching an old one back on must not create the collision it was avoiding.

The database's `uq_rate_card_active_agreement` is a *backstop*, not the rule: it catches only two
concurrent inserts of the identical agreement. A full non-overlap constraint (`EXCLUDE USING gist`
over a `daterange`) needs the `btree_gist` extension, which this installation's baseline does not
create; adding a database extension to enforce a rule the service already enforces was judged a
bigger change than the rule deserves. What makes that safe is section 3: selection stays
deterministic even if an overlap ever slipped through.

## 3. Which card prices a shipment

`RateCardSelector.select` is a pure function over "the trip" and "every active card of its carrier".

A card is a **candidate** when all four hold:

1. it belongs to the trip's carrier;
2. it is in force on the trip's **planning date** - never on today, so re-estimating last month's
   shipment prices it with the tariff that was in force then;
3. its scope covers the shipment (`CARRIER` covers everything; `ORIGIN` and `ROUTE` are equality on
   the id they name);
4. its vehicle type is unset, or is the type the trip runs on. A card naming a type never applies to
   a trip whose type is unknown - "we do not know what it goes on" is not a reason to charge the
   articulated rate.

Candidates are ranked by, in order:

1. **scope**, narrowest first: `ROUTE` > `ORIGIN` > `CARRIER`;
2. **vehicle type**: a card naming one beats a card that does not. Below scope, not above it: a
   price agreed for a corridor is a more deliberate statement than a price agreed for a class of
   truck across the whole network;
3. **latest `valid_from`**: when two agreements genuinely overlap, the newer one is the
   renegotiation;
4. **code**, ascending - never reached by well-formed data, and present so the answer is total.

No card is an ordinary answer, not an error. A company that has not entered a tariff for a corridor
has shipments with no estimate, and that is what the screen says.

## 4. The calculation

`TripCostCalculator.calculate` is pure: the same card and the same quantities produce the same
figure on any machine at any time.

```
BASE                    flat, if the card names one
DISTANCE  km  x rate    quantity: the master route's reference distance
WEIGHT    kg  x rate    quantity: the summed declared weight of the orders on the trip
VOLUME    m3  x rate    quantity: idem
PALLETS       x rate    quantity: idem
MINIMUM_ADJUSTMENT      the difference, when the lines above came to less than minimum_amount
```

Rounding is **per line, half up, at two decimals** - per line and not at the end, because the lines
are shown to an operator and printed on a settlement sheet, and a total that is not the sum of the
numbers above it is a support ticket.

### 4.1 What cannot be calculated is said out loud

A component the card charges for whose quantity the shipment cannot supply becomes a line with
`status = NOT_CALCULABLE`, a reason, and an amount of `0.00`. It is never skipped and never charged
at zero, because an estimate that is short by the entire line haul must not read as a price.
`TripCostView.estimateComplete` is false whenever any such line exists, and the screen says so
*above* the total rather than below it.

Two sources of "cannot":

- **No distance.** TMS measures no road distance. The only distance it has is
  `tms.route.reference_distance_km`, typed in by a planner (V8), and every line that uses it records
  `quantity_source = 'ROUTE_REFERENCE'` so nothing ever presents it as a measured figure. A shipment
  with no route, or whose route carries no distance, gets `DISTANCE_UNKNOWN`.
- **A declared total of zero.** A trip whose orders declare no weight sums to zero, and zero is
  indistinguishable from "nobody filled the field in". `CapacityLoad` resolves that ambiguity
  towards zero, because an unknown weight must not silently disable a capacity limit; costing
  resolves it the opposite way, for the same reason - charging a truckload at nothing per kilo
  because a field was blank produces an estimate that is confidently wrong.

### 4.2 The minimum applies to what was calculable

If the only calculable component came to 40 and the agreement says never less than 120, the shipment
costs 120 - and the non-calculable line stays on the estimate saying what is missing. A floor is a
floor; it is not a guess at what the missing components would have added.

## 5. The trip cost

`tms.trip_cost` holds one row per trip with **two independent figures that never overwrite each
other**: the estimate (with the card that produced it, snapshotted) and the actual (with the
carrier's document reference). The number a company is managed by is the difference, and a model
where recording the invoice replaced the quote would destroy it.

### 5.1 Two doors onto the same estimate

| | on demand (`POST .../cost/estimate`) | at confirmation (`TripCostEstimationPort`) |
|---|---|---|
| trip is a draft | refused, with a message | not reachable - only confirmed trips get here |
| trip has no carrier | refused, with a message | silent, no row written |
| no card covers it | refused, with a message | silent, no row written |
| cost already closed | refused, with a message | silent, left untouched |

Both go through the same private method, so the figure they produce cannot differ. The silence on
the confirmation path is the contract, not an oversight: an installation that has not entered its
tariffs must still be able to confirm a plan, and refusing would make costing a precondition for
dispatching a truck. A genuine defect - a row that will not persist - still rolls the confirmation
back, because a plan confirmed in a half-failed transaction is worse than a plan not confirmed.

Confirmation is where the automatic estimate happens because that is the moment the plan becomes
binding, so it is the moment the tariff in force should be recorded against it.

### 5.2 The snapshot

`rate_card_id` stays a live foreign key (so a screen can link to the card, and so the card cannot be
deleted while an estimate cites it), and `rate_card_code`, `rate_card_name` and `rate_card_scope`
are copied at the moment of calculation. Editing a card tomorrow, or deactivating it, therefore
never restates what a shipment was estimated at last week - the same argument `tms.trip.snapshot_max_*`
makes for capacity (`docs/domain/CAPACITY_MODEL.md`).

The component lines are part of that snapshot. Re-estimating replaces the whole set in place; what
was superseded is not lost, because every run is audited as `COST_ESTIMATED` with its amount and its
card.

### 5.3 One currency, no conversion

A trip cost has one currency and both halves share it. It is fixed by whichever figure was recorded
first - normally the estimate. An actual in another currency is **refused by name**, not converted:
TMS holds no rates of exchange, and inventing one would be the most expensive kind of guess. A trip
that no card covered has no currency to inherit, which is the one case where the actual-cost request
must supply one.

### 5.4 Closing, and why it is reversible

Closing settles the figure: every write is refused afterwards, including a re-estimate, which would
restate a number somebody has already paid against.

`reopen` exists and is audited as its own action (`COST_REOPENED`). Without it a mis-clicked Close
would freeze a wrong number permanently and the only remedy would be hand-editing the table, which
is precisely the repair this product must never require. Reopening restores nothing and changes no
figure - it only makes the row writable again, so the correction that follows is itself recorded.

## 6. Authorization

Four permissions, because reading a tariff and reading what one shipment cost are different
disclosures:

| Permission | Granted by V30 to |
|---|---|
| `rates.rate_card:read` | `ORGANIZATION_ADMIN`, `COMPANY_ADMIN`, `PLANNER` |
| `rates.rate_card:manage` | `ORGANIZATION_ADMIN`, `COMPANY_ADMIN` |
| `rates.trip_cost:read` | `ORGANIZATION_ADMIN`, `COMPANY_ADMIN`, `PLANNER` |
| `rates.trip_cost:manage` | `ORGANIZATION_ADMIN`, `COMPANY_ADMIN`, `PLANNER` |

`VIEWER` gets **none of them**, and that is the one grant decision here worth defending. Every other
catalogue in this schema is readable by a viewer because operational data is what the role exists to
watch. A tariff is not operational data: it is what one company negotiated with another, and the
default for commercially sensitive figures is that somebody has to be given them on purpose.

The UI follows: the Rates nav group is behind the `RATES_VIEW` capability, and the cost card inside
a trip is absent - not greyed out - for a role without `rates.trip_cost:read`.

## 7. Module boundaries

`rates` is a business module of its own and imports no other one (`ModuleBoundaryTest`). Five ports
carry everything across:

| Port | Direction | Implemented by |
|---|---|---|
| `TripCostingLookupPort` | rates reads a trip | `planning.infrastructure.TripCostingLookupAdapter` |
| `TripCostEstimationPort` | planning asks for a price | `rates.infrastructure.TripCostEstimationAdapter` |
| `TripCostAnalyticsPort` | the KPI report asks what a range cost | `rates.infrastructure.TripCostAnalyticsAdapter` |
| `VehicleTypeLookupPort` | rates validates a card's vehicle type | `fleet.infrastructure.VehicleTypeLookupAdapter` |
| `CarrierLookupPort.findActiveInCompany` | rates validates a card's carrier | `fleet.infrastructure.CarrierLookupAdapter` |

`TripCostAnalyticsPort` (migration V33) aggregates and returns nothing per shipment - only sums by
currency, one entry each, because TMS holds no rates of exchange and a summed "total cost" across
currencies would be the most expensive kind of number. It reads `tms.trip_cost.planning_date`, a
column V33 added so the range predicate stays inside this module's own table: the join that would
otherwise answer it is `rates` reading `planning`'s rows, and passing the trip ids across the port
instead is tens of thousands of UUIDs at the stated scale. See
`docs/domain/KPIS_REPORTING_V1.md` section 3.7 and `docs/database/DATA_MODEL.md` section 27.

`RouteTemplateLookupPort.findReferenceDistanceKm` was added for the same reason and is deliberately
a method rather than a field on `RouteTemplate`, which states that it carries no reference distance
because copying one onto a shipment would publish a figure nobody measured. That stance is unchanged:
`rates` takes the number as an input it *labels* on the resulting cost line.

`CostableTrip` is assembled in exactly one place, so the on-demand estimate and the
confirmation-time estimate price the same shipment from the same numbers.

## 8. Deliberately not in V1

| Left out | What would have to exist first |
|---|---|
| Accessorials (waiting time, second driver, night delivery, tolls) | a measured input per surcharge - per-stop wait times, a toll table. A surcharge nobody measures is a hand-filled column, which is a spreadsheet with extra steps |
| Fuel index | a published index feed and a formula agreed per contract |
| Break/scale tables (0-500 kg at one rate, 501-1000 at another) | a child table, a bracket-selection rule and a UI to maintain it. V1's answer is a second card narrowed by vehicle type |
| Cost per order | an allocation basis (by weight? by pallet? by drop?), which is a commercial decision per company and must not be baked into the schema |
| Customer-facing price | a sell-side agreement with a different counterparty - a different table when it arrives, not a `direction` column on this one |
| Currency conversion | rates of exchange, their source and their as-of date |
| Road distance | a routing provider. Deferred by decision (`CLAUDE.md`) |
| Cost on the planning board | nothing technical; it was simply not asked for. `TripCostRepository` deliberately has no batched finder until the screen that needs one exists |

## 9. Tests

| File | Covers |
|---|---|
| `RateCardSelectorTest` | validity bounds, carrier isolation, scope and vehicle-type filtering, the four tie-breaks, "no card found" |
| `TripCostCalculatorTest` | deterministic totals, per-line rounding, every non-calculable case, the minimum in three positions |
| `RateCardServiceTest` | the scope trio, the at-least-one-component rule, overlap refusal and its successor case, carrier immutability |
| `TripCostServiceTest` | draft/no-carrier/no-card refusals, the snapshot, the confirmation path's silence, currency mismatch, close/reopen |
| `RateCardsPage.test.tsx` | the agreement rendered as one row, the in-force filter, permission gating |
| `TripCostCard.test.tsx` | not-priced state, estimate vs actual vs variance, the incomplete-estimate warning, permission gating |

Database-level rules - the CHECK constraints, `uq_trip_cost_trip`, the tenant policies - belong to an
integration test and need Docker, which is unavailable in this environment
(`DB_CERTIFICATION=BLOCKED_ENVIRONMENT`).
