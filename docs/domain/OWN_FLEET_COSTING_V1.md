# Own-fleet costing V1

**Migration V48 · JOB 22 · closes debt D6**

What it costs *us* to run one of our own trucks — as distinct from what a carrier charges to run
theirs.

---

## 1. The distinction the whole design rests on

A carrier presents a **PRICE**. It is agreed, it binds, and it already contains their costs, their
overhead and **their margin**.

Own fleet produces an **INTERNAL COST ESTIMATE**. It is modelled, it binds nobody, it contains **no
margin**, and it is only ever as good as the rates somebody typed into a profile.

These are not the same kind of number, and `TransportCostNature` carries the difference from the
calculator to the screen so that nothing downstream can lose it.

> **An own-fleet estimate coming out lower than a carrier's price is the expected shape of comparing
> a number with margin against a number without one.** It is not, on its own, evidence that running
> it ourselves is cheaper.

Every screen that shows both labels both. `TransportCostComparison.Result.comparesCostAgainstPrice()`
exists so a UI can say so without re-deriving it.

## 2. What debt D6 actually was

JOB 11 priced proposals from carrier rate cards and left own-fleet trips **unpriced**, with this
reasoning, which still stands:

> *"A rate card is an agreement with a carrier, and there is no agreement with yourself. Pricing it
> at zero would make any plan that used it unbeatable."*

That was correct and incomplete: a plan containing one own-fleet trip came back
`NO_AGREEMENT_FOR_SOME_TRIP` — a sentence that sends a planner looking for a contract with
themselves. V48 supplies the missing model and `OWN_FLEET_NOT_COSTABLE` supplies the right sentence.

## 3. Unknown cost is not zero cost

The rule this job exists to protect, in the four places it is enforced:

| Where | What it refuses |
|---|---|
| `OwnFleetCostInputs` | A quantity without its provenance. Distance and duty are nullable and null means *not measured* |
| `OwnFleetCostCalculator` | A `comparableTotal` when any component the profile charges for had no quantity |
| `OwnFleetProfileResolver` | Falling back to an expired profile, or to a company-wide average |
| `ownFleetCostText.amountText` | Printing `0.00` for a `NOT_CALCULABLE` line, even though the API sends `amount: 0` |

The API sends `0.00` on a non-calculable line so that summing the lines is a plain sum. **The screen
prints a dash.** Printing the zero would say fuel cost nothing, which is the opposite of what
happened.

### Null rate vs zero rate

| | Meaning | Charged? | Demands a quantity? |
|---|---|---|---|
| `NULL` | This profile does not model the component | No | No |
| `0` | This profile charges nothing for it | Yes | **Yes** |

A company that does not model depreciation has said nothing about depreciation, and its estimate is
complete without it. One that typed `0.00` has said depreciation is nil — and its estimate still
needs the kilometres before it can say so.

## 4. The components, and the quantity each needs

| Component | Unit | Quantity source | Can be unknown? |
|---|---|---|---|
| `FIXED_TRIP` | — | profile | No |
| `FUEL_PER_KM` | km | measured route | Yes |
| `DRIVER_PER_HOUR` | h | resource duty | Yes |
| `VEHICLE_PER_HOUR` | h | resource duty | Yes |
| `MAINTENANCE_PER_KM` | km | measured route | Yes |
| `DEPRECIATION_PER_KM` | km | measured route | Yes |
| `TOLL` | — | profile | No |

**`TOLL` is flat per trip and deliberately not per kilometre.** Tolls depend on which roads a route
uses, not how long it is; kilometres times an average would produce a figure with the shape of a
measurement and the content of a guess.

There is no `OVERHEAD_ALLOCATION` and no `INSURANCE_PER_TRIP` — not because they are not real costs,
but because TMS holds no input that would give either a quantity. They would be a second flat charge
wearing a specific name.

## 5. Duty time, and where the reposition goes

```
Trip A execution   2h00
reposition A→B     0h40
Trip B execution   3h00
                  ------
resource duty      5h40      (trips alone: 5h00)
```

**The reposition is charged to the trip it repositions *to*.** You drive the empty leg because of the
next job. Applied to every trip in a day, the day's charged duty is exactly the resource's duty —
**no leg counted twice and none dropped.**

- Trip A costed alone charges no inbound reposition; it repositioned from nowhere.
- Trip B charges its own 40 minutes, and A never does.

The figure comes from `WorkAssignmentTrip.repositionMinutes`, **frozen when V47 validated the
sequence**, never re-derived. A day called feasible on one number and costed on another would be two
answers about one empty leg.

When a trip is sequenced behind another by a join nobody could measure, **duty is null** and the
time-based components have no quantity. Charging only the execution would understate it by however
long the empty run takes, and would look like a complete answer.

### Known limitation: reposition distance is not charged

Duty includes the empty run. **Distance does not.** V47 froze the reposition's minutes and not its
kilometres, and re-measuring the leg at quote time would drift away from the frozen figure as the
routing cache changes.

So V1 charges the driver and the vehicle for repositioning and does **not** charge fuel, maintenance
or depreciation for it. **This understates a multi-trip day's distance costs.** It is recorded here
rather than papered over, and it errs in the direction that does not make own fleet look better than
it is.

## 6. Which profile applies

```
this specific vehicle  >  this vehicle's type  >  no cost available
```

**There is no company-wide fallback.** A rate applying to every truck a company owns regardless of
type is a number that means nothing — a van and an articulated truck do not share a fuel rate, and
averaging them is wrong for both while looking authoritative.

An expired profile is **not** fallen back to. A June trip costed at last year's fuel price, silently,
is worse than no cost at all.

Overlap is prevented in the database (`ex_own_fleet_profile_vehicle_no_overlap`,
`ex_own_fleet_profile_type_no_overlap`, `EXCLUDE USING gist` over a `daterange`), so the resolver
never breaks a tie with a rule nobody chose. `effective_to` is **exclusive**, so a profile ending on
the 1st and one starting on the 1st are a rate change and not a conflict.

## 7. Partial estimates

A profile charging `FIXED_TRIP 100` + `DRIVER 80` + `FUEL ?`:

```
Fixed trip      100.00
Driver           80.00
Fuel            UNKNOWN   (DISTANCE_UNKNOWN)
               --------
TOTAL        UNAVAILABLE
```

Not `180.00`. The breakdown survives for whoever has to fix it — `partialSubtotal` is named so it
cannot be mistaken for the total, and nothing that makes a decision reads it.

**The system must not reward a plan for missing its own costs.** A trip that cannot be measured
would otherwise score 100 against a fully measured 316.60 and win every comparison by being
unmeasurable.

## 8. Currency

Money is `BigDecimal` throughout, compared with `compareTo`. Each line is rounded once, at the line,
so the total equals what the screen shows added up.

**No FX, ever.** Two options in different currencies come back `INCOMPARABLE_CURRENCY` and are shown
side by side in their own currencies. 320 USD against 1,430 PEN is a case where a naive `min()`
returns the wrong answer *and looks right*.

## 9. Planning integration

`ProposalPricer` costs an own-fleet trip through `OwnFleetProposalCostingPort` — planning never
depends on the costing module, which stays extractable.

- Distance: the run's legs, or **null when any leg is unknown**.
- Duty: driving plus service time, **the same arithmetic `PlanningEngineV2.sequence` uses for the
  shift check**, so a day called fittable and a day costed cannot disagree about how long it takes.
- No reposition: a proposal is one day's plan from a depot, and V47's assignment does not exist yet.

`ProposalPricing.ownFleetCostedTrips` carries the provenance the total would otherwise lose, and
`mixesPriceAndCost()` tells a screen when a total adds a carrier's price to our own cost — a real and
useful figure, and one that must be labelled.

## 10. Estimate is not actual

`ESTIMATED` and `ACTUAL` stay separate. TMS holds no fuel consumption readings, no payroll, no
workshop invoices and no toll records, so an "actual own-fleet cost" would be the estimate wearing a
different label. `tms.trip_cost.actual_*` remains what it was: a figure a person recorded.

## 11. Deliberately not built

| | Why |
|---|---|
| **Cost allocation across orders** | **Debt D10, still OPEN.** Knowing what a trip cost does not decide how that cost is shared. Choosing delivered quantity, weight, volume or pallets as a default would be inventing a business rule nobody has approved |
| Actual own-fleet cost | No input exists for any of its four terms |
| Company-wide fallback profile | A fuel rate averaged over a van and an articulated truck is wrong for both |
| FX conversion | Two currencies do not add up |
| Reposition distance | V47 froze minutes, not kilometres — see §5 |
| Network modelling, what-if, digital twin | Out of scope by standing decision |

## 12. Permissions

`costing.own_fleet:read` · `costing.own_fleet:write` — capabilities `OWN_FLEET_COSTING_VIEW` /
`OWN_FLEET_COSTING_MANAGE`.

Its own resource rather than `rates.rate_card`: a tariff is a commercial agreement and this is a
finance model of our own operation, and an installation will want them in different hands.

- **ORGANIZATION_ADMIN / COMPANY_ADMIN** — read and write.
- **PLANNER** — read only. Choosing between a carrier and our own truck is their decision; deciding
  what the truck costs to run is a finance decision about the business.
- **VIEWER** — neither. These rates are what we pay a driver by the hour and what we believe fuel
  runs at: our cost structure, not our operation. The same line `rates` draws for tariffs.
