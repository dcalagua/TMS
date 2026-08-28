# TMS by EBIM - planning engines (V2)

Owner: `com.ebim.tms.planning.application`. No migration: JOB 05 added no schema.

## 1. Two engines, one port

`PlanningEngine` was written with the sentence *"the interface exists before there is a second
implementation, and that is the point."* This is that second implementation arriving.

| Engine | What it does |
|---|---|
| `HEURISTIC_V1` | Groups by corridor, orders by priority/date/corridor position, fills the largest free vehicle first-fit-decreasing, never splits an order. Ignores distance, time and cost. |
| `PLANNING_V2` | Everything above, **plus** the three facts V1 had no way to know. |

**The default is still `HEURISTIC_V1`.** Every company using automatic planning today gets V1's
proposals, and silently swapping the algorithm underneath a familiar button would change what it
does with no way to tell. V2 is opt-in per run; promoting it is a decision to take with evidence
from real datasets.

## 2. What V2 adds

### It plans what is actually outstanding

V1 packs against an order's **totals**. Since V37 an order can already be half loaded, and packing
its whole weight onto a second truck reserves capacity nobody needs. V2 packs against
`PlannableOrder.pending()`.

An order already wholly on trips is reported `FULLY_ALLOCATED` — a distinct reason, because nothing
is wrong: the work is done, and listing it as a failure would put a solved order on an exception
list.

### It sequences stops by distance

V1 visits destinations in whatever order the orders happened to load. V2 walks them
**nearest-neighbour from the origin** using the travel matrix (V38). This is the single change that
moves total kilometres, and `PlanningEngineComparisonTest` measures it: on a six-stop corridor fed
farthest-first, V1 drives out and back along the line while V2 drives **85 km straight out**.

Nearest-neighbour and not something better, deliberately: it is O(n²) on a handful of stops and
every step is explainable to the dispatcher who has to drive it. A proper tour improvement (2-opt,
or a solver) is the next step and is **not** taken here — this is the honest heuristic, not a solver
wearing its name.

### It refuses a trip that cannot be driven in a shift

Driving plus service time against `PlanningShift` (06:00, ten hours by default). A board full of
shipments that run out of hours at the fourth stop is worse than a board with one fewer shipment.

The refusal is its own reason, `EXCEEDS_SHIFT`, and not `NO_VEHICLE_AVAILABLE`: the two call for
different actions. A capacity problem is solved with another truck; this one is solved with an
earlier departure, a longer shift or closer stops. Saying "no vehicle" while the fleet sits idle
would send a planner looking for the wrong thing.

## 3. Hard constraints versus soft objectives

| Hard — the proposal is refused | Soft — reported, not refused |
|---|---|
| company and tenant scope | total cost *(not computed — see §5)* |
| order eligibility and service calendar | total kilometres |
| **pending** weight, volume, pallets | total duration |
| vehicle capacity per dimension | number of trips and vehicles |
| vehicle availability, no double booking | weight / volume / pallet utilisation |
| **the shift** | **lateness against the requested window** |

**Lateness is soft on purpose, and this is the one judgement call worth defending.** A shipment that
cannot physically be driven in a shift is not a plan. A shipment that arrives after a customer's
requested window is a real delivery, usually the best answer available, and refusing to plan it
would leave the customer with **nothing** instead of something late — and would hide the lateness
from the planner who could still fix it. So the shift refuses and the window is counted.

## 4. KPIs

`PlanningKpis`, on every proposal, so that comparing two engines is a comparison rather than two
opinions:

    trips · vehicles · plannedOrders · unplannedOrders · lateOrders
    totalDistanceKm · totalDurationMinutes
    weight/volume/pallet utilisation · plannedRatePercent · kilometresPerPlannedOrder
    distanceEstimated · totalCost

Two details that are not accidents:

- **Utilisation is null, not zero, when no vehicle declares a limit.** "Unknown" and "empty truck"
  are different statements and a reader would act differently on each.
- **Utilisation is averaged over trips, not pooled.** Two trips, one full and one empty, is a fleet
  being used badly; a pooled figure would report it as half full and hide that.

## 5. Cost is null, and stays null

`totalCost` is **always null today.** Pricing a hypothetical trip needs a rating port that takes a
proposal rather than a persisted shipment, which is JOB 06's. `TripCostEstimationPort` only prices
trips that already exist.

Filling it with a plausible figure is the one outcome that would be worse than leaving it out,
because somebody would compare two engines on it. The drawer says so on screen rather than showing
a zero.

## 6. Purity, and why the matrix is handed in

Both engines are pure functions: no repository, no clock, no randomness. That is what makes a
proposal reproducible and provable on a host with no database — and every Testcontainers test in
this repository is skippable, so an engine that reached for a repository would ship unproven.

So distances arrive as a `TravelMatrix` resolved by `AutoPlanningService` **before** the engine
runs. Letting the engine call `RoutingPort` itself would cost a cache read per leg and take that
property away.

`AutoPlanningService` asks routing for the whole matrix in one call — a day with fifteen
destinations costs one call instead of 240.

## 7. Degrading without distances

A company whose locations are not geocoded must still be able to plan. With `TravelMatrix.EMPTY`:

- every leg measures zero, so the nearest-neighbour sequence degrades to insertion order —
  **exactly V1's behaviour**;
- nothing is ever refused for the shift;
- `totalDistanceKm` is zero and is honestly zero.

`PlanningEngineComparisonTest.identicalWithoutAMatrix` asserts the two engines produce the same
loads in that case, so V2 cannot drift away from behaviour a planner already knows.

## 8. Not here

- **No solver.** OR-Tools remains deferred by decision. `PlanningEngine` is where one attaches.
- **No engine-proposed splits.** V37 makes splitting expressible, but *which* 30 of the 100 pallets
  go on the second truck is a planner's decision today.
- **No auto-dispatch.** Both engines return draft trips. Confirming stays a separate, deliberate act.
- **No driving-hours model.** `PlanningShift` is a configurable ceiling, not the rules of any
  jurisdiction; pretending otherwise would be worse than a number a planner sets to what their own
  operation does.
