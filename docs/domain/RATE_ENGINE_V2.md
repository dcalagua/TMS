# TMS by EBIM - rate engine (V2)

Owner: `com.ebim.tms.rates`. Schema: `V30__rates_and_trip_costing.sql`, `V39__rate_engine_v2.sql`.

## 1. The order of application is a contract

**These components do not commute.** The calculator walks `RateComponent` in declaration order and
this is the arithmetic it produces:

    BASE + DISTANCE + WEIGHT + VOLUME + PALLETS + STOP_OFF        →  the LINEHAUL
    + FUEL_SURCHARGE        (a percentage OF THE LINEHAUL, and of nothing else)
    + WAITING_TIME + TOLL + OTHER_ACCESSORIAL                     →  the accessorials
    then MINIMUM_ADJUSTMENT or MAXIMUM_ADJUSTMENT on the total

Two of those placements are decisions rather than details:

- **Fuel is taken on the linehaul only.** A fuel percentage applied to a toll is a fuel surcharge on
  a road authority's fee: no carrier bills it and no shipper would accept it. On a 175 linehaul with
  a 50 toll at 12%, the two readings differ by 6.00 on every single shipment.
- **The limits are applied last, after the accessorials.** A ceiling applied to the linehaul alone
  would let the accessorials carry the total past it, which is not what a capped agreement says.

`RateEngineV2CalculatorTest` asserts both on numbers small enough to check by hand.

## 2. The components

| Component | Unit | Where its quantity comes from |
|---|---|---|
| `BASE` | flat | — |
| `DISTANCE` | KM | the trip's **measured route** (V38), else the master route's reference |
| `WEIGHT` | KG | the orders' declared totals |
| `VOLUME` | M3 | the orders' declared totals |
| `PALLETS` | PALLET | the orders' declared totals |
| `STOP_OFF` | STOP | the trip's stops, **minus one** |
| `FUEL_SURCHARGE` | PERCENT | the linehaul subtotal |
| `WAITING_TIME` | HOUR | detention somebody recorded |
| `TOLL` | flat | — |
| `OTHER_ACCESSORIAL` | flat | — |
| `MINIMUM_ADJUSTMENT` | flat | — |
| `MAXIMUM_ADJUSTMENT` | flat, **negative** | — |

### The first stop is free

`STOP_OFF` charges drops **after the first**. The first is already inside `BASE` — a shipment exists
to deliver something somewhere — so charging it again bills the same drop twice, and a one-stop trip
must pay no stop-off at all. Every carrier's multi-drop schedule is written this way, and getting it
wrong overcharges the simplest shipment in the book.

### The ceiling is the one negative line

`ck_trip_cost_component_amount_sign` (V39) carves `MAXIMUM_ADJUSTMENT` out of V30's
"amount >= 0" rule by name. A ceiling adjusts the total *down*, and rendering it as a positive
number would read as one more charge on the very breakdown somebody is checking. Every other
component stays non-negative, because for every other component a negative amount is a bug.

## 3. Three states a line can be in, and why they are different

1. **Applied** — the card charges it and the quantity was known.
2. **Not calculable** — the card charges it and the quantity was **not** known. The line still
   appears, at zero, carrying a reason.
3. **Absent** — the card says nothing about it, so there is no line at all.

Conflating 2 and 3 is the failure this design exists to prevent. *"This agreement does not charge
for weight"* and *"this agreement charges for weight and we do not know the weight"* are different
statements, and only the second is a problem somebody has to fix.

**Waiting time is always state 2 on an estimate**, and that is correct rather than a gap: detention
is measured on the road. Showing it as zero would claim the truck will not wait.

## 4. Where the kilometres came from

Since V39, `DISTANCE` prefers **the shipment's own measured route** (V38) over the master corridor's
reference distance, and the line records which through `CostQuantitySource`:

| Source | Meaning |
|---|---|
| `MEASURED_ROUTE` | measured over this shipment's stops |
| `ROUTE_REFERENCE` | a number typed onto the master corridor |

Measured wins because it is about *this* shipment, and because **it exists for a trip with no master
route at all** — which before V39 simply could not be priced per kilometre however far it drove.

Nothing is still a legitimate answer: with neither, the component reports itself non-calculable
rather than being multiplied by a zero nobody meant. That rule is unchanged since V30; it just has
one more place to look first.

## 5. Scopes

| Scope | Specificity | Means |
|---|---|---|
| `CARRIER` | 0 | anything this carrier runs |
| `ORIGIN` | 1 | anything leaving this depot |
| `LANE` | 2 | **this origin to this destination** (V39) |
| `ROUTE` | 3 | shipments built from this master corridor |

A lane is what most freight agreements are actually priced on, and neither `ORIGIN` (everything out
of a depot) nor `ROUTE` (one named corridor) can express it.

**A multi-drop shipment is on no lane.** `CostableTrip.soleDestinationId` is null when a trip has
several destinations, and `appliesToScopeOf` refuses to match a lane card on a null — because
`Objects.equals(null, null)` would otherwise price a four-stop shipment against an agreement that
was never about it. That is the "pricing by coincidence" the scope rule has always refused.

## 6. Money

`BigDecimal` throughout, `numeric(14,2)` for amounts and `numeric(14,4)` for unit rates. No
`double`, no `float`, anywhere. Currency is stated on the card and the product invents **no FX
rate** — V30's rule, unchanged.

## 7. A limit is not a charge

`minimum_amount` and `maximum_amount` are deliberately **not** in
`ck_rate_card_has_a_component`. A card saying only *"never less than 200"* states a constraint on a
price that does not exist; pricing from it would conjure 200 out of nothing. V30 decided this and
V39 keeps it — `RateCardServiceTest.minimumAloneIsNotAComponent` is the guard.

## 8. Not here

- **No ZONE scope.** Pricing by destination zone needs a zone resolved at rating time, which is a
  masterdata lookup this module does not have and should not grow casually. `LANE` covers the case
  most agreements state.
- **No break-weight or tiered tables.** A rate that changes at 5,000 kg is a second table, not a
  column.
- **No proposal pricing yet.** JOB 05's `PlanningKpis.totalCost` is still null: rating a *proposed*
  trip needs a port that takes a proposal rather than a persisted shipment. The pieces now exist —
  a proposed trip's carrier is its vehicle's carrier — but wiring it is its own change with its own
  tests, and a cost that appeared without them would be exactly the fabricated figure JOB 05
  refused.
- **No customer sell rates.** Everything here is what a carrier charges. Merging buy and sell is how
  a TMS ends up unable to say what its own margin is.
