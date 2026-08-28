# JOB 11 - Settlement: pricing a plan nobody has committed to

**RESULT = PASS** · **STOP_CHAIN = false** · **MIGRATION = none (see below)**

| | |
|---|---|
| Started | 2026-08-28 05:24 America/Lima |
| Completed | 2026-08-28 05:36 America/Lima |
| HEAD before | `25554cc` |
| Backend, `./mvnw clean test` | **1654 pass, 0 fail, 0 skipped** |
| Frontend, `vitest run` | **76 pass** |
| E2E, `playwright test` | **34 pass, 7 skipped** |
| Typecheck / lint / build | clean |
| Flyway | V1-V43, contiguous, unchanged |
| Retries | 3 attempted, 3 recovered |

---

## Why there is no migration, stated up front

**Planning KPIs are computed per proposal and never stored.** There is no `total_cost` column
anywhere, because a proposal is not a record of anything until it is applied - at which point the
trips it creates are priced by `TripCostService` on confirmation, through the same calculator.

D1 was therefore a correctness debt and not a schema gap, and adding a migration to make the job
look substantial would have been the empty scaffolding this brief forbids. **V43 remains the latest
migration.**

---

## D1, closed

`PlanningKpis.totalCost` was always null and documented why:

> Pricing a hypothetical trip needs a rating port that takes a proposal rather than a persisted
> shipment... a fabricated cost is worse than a missing one, because somebody would compare two
> engines on it.

The gap was the *port*, and it turned out to already exist. JOB 07's `CarrierQuotationPort` prices a
shipment against a carrier that is **not** assigned to it - that is what a tender is - and a proposal
is the same question one step earlier. So `ProposalPricer` asks it through **the same port, the same
selector and the same calculator** a tender and an invoice use.

That sameness is the design, not a convenience: a plan compared on price and the bill that follows
it must come from one set of rules, and two code paths computing "the price" is exactly how those
two numbers come to differ. The only addition is `quoteWithKnownDistance` - a proposal has no
persisted row to look a distance up from, and does not need one, because the planning run measured
every leg through the same `RoutingPort` before the engine ran.

## The four refusals, which are most of the feature

**1. No partial totals.** If any proposed trip cannot be priced, there is no total - only
`pricedTrips`, `totalTrips` and a reason. A sum omitting the three trips nobody has an agreement for
would make the worse plan look *cheaper*; since comparing engines on cost is the whole purpose of the
figure, a partial total is not a smaller answer but the wrong one pointed the wrong way. The count is
still reported, because "7 of 10 have an agreement" is what tells a planner what to fix.

**2. No currency conversion.** Agreements in different currencies do not add up and nothing converts
them. This product invents no FX rate (V30), and `CarrierQuote` already refuses the same thing when
ranking carriers. **This was an explicit constraint of the brief and is enforced by a test.**

**3. No invented distance.** `TravelMatrix.distanceKm` answers zero for a leg it does not know -
right for planning, wrong for money. A run with any unknown leg is quoted with a **null** distance,
so the per-kilometre component reports itself non-calculable rather than being multiplied by a pile
of zeros.

**4. Own fleet is not free.** A vehicle with no carrier has no rate card, because a rate card is an
agreement *with a carrier*. Pricing it at zero would make any plan using own fleet unbeatable, which
is the most expensive way to be wrong here.

---

## Defects found and fixed: 2

**1. `TravelMatrix.distanceKm` returns zero for legs it does not have.** Pre-existing and correct
for its original caller - an engine must place orders on a day where half the destinations are
ungeocoded - but silently catastrophic the moment a distance is multiplied by money. Found while
writing the pricer rather than after. Fixed by adding `TravelMatrix.knows`, so a caller that will
turn a distance into a price asks first; the pricer returns null for the whole run rather than a
short sum. Asserted by `unknownLegGivesNoDistance`.

**2. My own compact constructor silently overwrote a caller's argument.** The first version of
`PlanningKpis` derived `totalCost` from `pricing` inside the compact constructor, which meant a
record quietly rewriting what was handed to it - harmless today only because `totalCost` was always
null, and exactly the kind of thing nobody finds twice. Fixed: the compact constructor only defaults
`pricing`, and `pricedWith` is the single place both fields are decided together.

## Rules deliberately not broken

**The engines stay pure functions.** Pricing needs a rate card, so it happens once in
`AutoPlanningService` against the proposal the engine produced - never inside an engine. An engine
that could read a rate card would stop being reproducible from its inputs, which is the property its
entire test suite rests on.

**No `TripCostService` behaviour changed.** Confirmation-time estimation, actual cost recording and
close/reopen are untouched. Settlement as a lifecycle already existed (V30/V39); what was missing was
the number *before* commitment, which is what D1 named.

---

## D3 - not closed, and correctly so

JOB 10's evaluation concluded that delivered quantity does not block Settlement, and building
Settlement confirmed it: every `RateComponent` prices the shipment - distance, stops, weight, volume,
pallets, waiting - and none prices the handover. **Nothing in this job created or inferred a
delivered quantity**, and `docs/domain/DELIVERED_QUANTITY_EVALUATION.md` stands as written.

---

## Test counts

Backend **1643 → 1654** (+11). Frontend **72 → 76** (+4). E2E **34 pass / 7 skipped**, unchanged -
no menu entry was added; the cost appears inside the existing auto-plan drawer. No failing test was
converted into a skip.

Of the 11 new backend tests, **6 assert refusals** rather than results. That ratio is deliberate: the
happy path here is a sum, and everything that could go wrong is a plausible number.

---

## Open debt register

| # | Debt | State | Note |
|---|---|---|---|
| **D1** | `PlanningKpis.totalCost` is null - a proposal is not priced | **CLOSED (JOB 11)** | Priced through `CarrierQuotationPort`; no partial totals, no FX conversion, no invented distance, own fleet not free |
| **D2** | Accepted tender vs vehicle owner | **CLOSED (V42)** | |
| **D3** | Delivery records an outcome, not a delivered quantity | **OPEN, formally evaluated** | Confirmed not to block Settlement. Nothing here inferred one |
| **D4** | No system-actor model | **DEFERRED_WITH_REASON** | Unchanged |
| **D5** | No work assignment across several shipments | **OPEN** | Unchanged |
| **D6** | No internal cost model for own fleet | **OPEN (new)** | Fuel, driver hours and depreciation are a different model; mixing a carrier's price with an own-fleet estimate compares two unlike numbers. Needs its own decision |

---

## Files

**Backend** new `planning.application.ProposalPricer` / `ProposalPricing`; changed
`PlanningKpis` (the `pricing` component and `pricedWith`), `AutoPlanningService`, `TravelMatrix`
(`knows`), `CarrierQuotationPort` / `CarrierQuotationService` (`quoteWithKnownDistance`)

**Tests** `ProposalPricerTest` (new, 11 - six of them refusals), `AutoPlanningServiceTest` (wiring)

**Frontend** `planningApi` (`ProposalPricing`), `AutoPlanDrawer` (the cost, or the reason there is
none), `proposalPricing.test.ts` (new)

**Docs** `docs/domain/PROPOSAL_PRICING_V1.md` (new)

---

**NEXT_JOB** - **JOB 12 - Control Tower V2**. Next migration **V44** if one is needed.
