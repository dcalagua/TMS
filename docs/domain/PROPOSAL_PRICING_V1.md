# Pricing a plan nobody has committed to - V1

*JOB 11, closing open debt D1. No migration: planning KPIs are computed per proposal and never
stored. Read with `docs/domain/RATE_ENGINE_V2.md` and `CARRIER_SELECTION_AND_WATERFALL_V1.md`.*

---

## The debt

`PlanningKpis.totalCost` was **always null**, and said so in its own javadoc:

> Pricing a hypothetical trip needs a rating port that takes a proposal rather than a persisted
> shipment... Left explicitly absent rather than filled with a plausible number: a fabricated cost
> is worse than a missing one, because somebody would compare two engines on it.

That was the right call at the time and it is the reason this document exists: the figure a planner
uses to choose between two engines must be a fact or must be absent, never a plausible substitute.

## What changed

V39 built the rating and JOB 07 built `CarrierQuotationPort`, which already prices a shipment
against a carrier that is **not** assigned to it - that is what a tender is. A proposal is the same
question one step earlier.

So `ProposalPricer` asks it through **the same port, the same selector and the same calculator** a
tender and an invoice use. That sameness is the whole design: a plan compared on price and the bill
that eventually follows it must come from one set of rules, and two code paths computing "the price"
is precisely how those two numbers come to differ.

The one new thing is an overload, `quoteWithKnownDistance`. The existing ones resolve the distance by
looking the persisted trip up; a proposal has no row to look up and does not need one, because the
planning run measured every leg through the same `RoutingPort` before the engine ran.

---

## The four refusals

Most of this feature is what it declines to report. Each refusal blocks one way a plausible wrong
number could have been produced.

### 1. No partial totals

If **any** proposed trip cannot be priced, there is no total - only `pricedTrips`, `totalTrips` and
a reason.

A sum that quietly omitted the three trips nobody has an agreement for would make the worse plan
look cheaper. Since comparing engines on cost is the entire purpose of the figure, a partial total
is not a smaller version of the answer - it is the wrong answer, pointed the wrong way.

`pricedTrips` is still reported, because "7 of 10 have an agreement" is the sentence that tells a
planner what to fix. It is a count, not a price.

### 2. No currency conversion

Agreements in different currencies do not add up, and nothing here converts them. This product
invents no FX rate (V30), and `CarrierQuote` already refuses the same thing when ranking carriers.
A plan costing 4,000 PEN and 900 USD has no total that is a fact.

### 3. No invented distance

`TravelMatrix.distanceKm` answers **zero** for a leg it does not know. That is right for planning -
an engine must place orders on a day where half the destinations are ungeocoded - and wrong for
money: a per-kilometre charge over a summed-up pile of zeros is a price that looks calculated and is
not.

`TravelMatrix.knows` was added for exactly this. A run with any unknown leg is quoted with a **null**
distance, so the per-kilometre component reports itself `NOT_CALCULABLE` rather than being multiplied
by a zero nobody meant - the "do not invent the distance" rule V30 states and V39 kept.

### 4. Own fleet is not free

A vehicle with no carrier has no rate card, because a rate card is an agreement *with a carrier* and
there is no agreement with yourself. Own-fleet cost is an internal-rate model this product does not
have. Pricing it at zero would make any plan that used own fleet unbeatable, which is the most
expensive way to be wrong here.

---

## Where the number comes from

| Term | Source |
|---|---|
| Carrier | The proposed vehicle's owner (`VehicleCapacityReference.carrierId`) |
| Vehicle type | The proposed vehicle's, so type-scoped cards select correctly |
| Weight / volume / pallets | Summed over the orders the engine placed on that trip. Null when **no** order declared one - a shipment nobody weighed is not a shipment weighing nothing |
| Distance | The proposed run, leg by leg, from the planning run's own travel matrix. Null if any leg is unknown |
| Stops | `stopLocationIds.size()`, feeding the per-stop charge |
| Lane | The sole destination, or **null** for a multi-drop trip - a lane is one origin to one destination, and naming one stop of four would price the shipment against an agreement that was never about it |
| Waiting hours | Null. A proposal has not run, so nothing has waited - and "nobody waited yet" is not "the truck waited no time" |

---

## Where it does not live

**Not in the engines.** `HeuristicPlanningEngine` and `PlanningEngineV2` stay pure functions of
their input - no repository, no rate card, no clock. Pricing needs a rate card, so it happens once
in `AutoPlanningService`, against the proposal the engine actually produced. An engine that could
read a rate card would stop being reproducible from its inputs, which is the property its whole test
suite rests on.

**Not stored.** Planning KPIs are computed per proposal and never persisted, so this needed no
migration. A proposal is not a record of anything until it is applied, at which point the trips it
creates are priced by `TripCostService` on confirmation - through the same calculator.

---

## What is still open

**D3, delivered quantity, is untouched and does not block any of this.** Every `RateComponent`
prices the shipment - distance, stops, weight, volume, pallets, waiting - not the handover. See
`docs/domain/DELIVERED_QUANTITY_EVALUATION.md`.

**No internal cost model for own fleet.** Fuel, driver hours and depreciation are a different model
with different inputs, and a plan that mixes a carrier's price with an own-fleet cost estimate is
comparing two things that are not the same kind of number. It needs its own decision.
