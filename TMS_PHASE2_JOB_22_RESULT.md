# JOB 22 — Own Fleet Costing V1

**MIGRATION = V48** · **DEBT = D6** · **D10 = OPEN, untouched**

---

## 1. Capability proof

```
OWN_FLEET_PROFILE=YES
EFFECTIVE_DATING=YES
DISTANCE_COSTING=YES
TIME_COSTING=YES
WORK_ASSIGNMENT_INTEGRATION=YES
PROVENANCE=YES
CURRENCY_SAFETY=YES
PLANNING_INTEGRATION=YES
BREAKDOWN=YES
UI=YES
TENANT_TESTS=YES
D6_RESOLVED=YES
```

| Pillar | Where it is |
|---|---|
| `OWN_FLEET_PROFILE` | `tms.own_fleet_cost_profile` (V48) · `OwnFleetCostProfile` · 7 nullable components |
| `EFFECTIVE_DATING` | `effective_from` / `effective_to` half-open · two `EXCLUDE USING gist` constraints · `OwnFleetProfileResolver` · 11 tests |
| `DISTANCE_COSTING` | `FUEL_PER_KM`, `MAINTENANCE_PER_KM`, `DEPRECIATION_PER_KM` over the measured route |
| `TIME_COSTING` | `DRIVER_PER_HOUR`, `VEHICLE_PER_HOUR` over resource duty |
| `WORK_ASSIGNMENT_INTEGRATION` | `ResourceDutyLookupPort` reads V47's **frozen** `reposition_minutes` |
| `PROVENANCE` | `OwnFleetQuantitySource` on every line, through the API to the screen |
| `CURRENCY_SAFETY` | `BigDecimal` throughout · no FX · `INCOMPARABLE_CURRENCY` |
| `PLANNING_INTEGRATION` | `OwnFleetProposalCostingPort` · `UnpricedReason.OWN_FLEET_NOT_COSTABLE` · `ProposalPricing.ownFleetCostedTrips` |
| `BREAKDOWN` | `OwnFleetCostBreakdown.tsx` — quantity, rate, amount, source per line |
| `UI` | `OwnFleetCostProfilesPage.tsx` + drawer · states ACTIVE/INCOMPLETE/EXPIRED/FUTURE/INACTIVE |
| `TENANT_TESTS` | 4 cross-company tests: read, create, quote, list |
| `D6_RESOLVED` | Planning can use the quote honestly, and says which alternative it used |

## 2. Gates

Every figure below is from a run after the last change, not quoted from a job's own report.

| Gate | Result |
|---|---|
| `./mvnw clean test` | **1833 pass · 0 fail · 0 error · 0 skipped** · BUILD SUCCESS (1787 → 1833, **+46**) |
| Frontend unit | **123 pass** (was 114 · +9) |
| Typecheck / lint / `vite build` | clean · exit 0 |
| Flyway | **V1–V48**, contiguous |
| ArchUnit | 4 pass — **including the new `costing` module, now covered** |

## 3. The one rule this job exists for

**Unknown own-fleet cost never becomes zero cost.** Enforced in four places, each with a test:

| Layer | Refusal |
|---|---|
| `OwnFleetCostInputs` | A quantity without provenance — `IllegalArgumentException` |
| `OwnFleetCostCalculator` | `comparableTotal = null` when any charged component had no quantity |
| `OwnFleetProfileResolver` | No fallback to an expired profile, none to a company-wide average |
| `ownFleetCostText.amountText` | Prints `—`, never `0.00`, for a non-calculable line |

The load-bearing assertion, in `OwnFleetCostCalculatorTest.unknownNeverWins`:

> Had the calculator summed what it had, the un-measurable trip would have scored **100.00** against
> a fully measured **316.60** and won every comparison **by being unmeasurable**.

## 4. Null rate vs zero rate

|  | Meaning | Charged? | Demands a quantity? |
|---|---|---|---|
| `NULL` | not modelled | No | No |
| `0` | modelled at nothing | Yes | **Yes** |

Stated at the column (V48), in `OwnFleetRates`, at the API boundary (a create returns
`depreciationPerKm` absent, not `0.00`), and in the drawer's warning banner.

## 5. Where the reposition goes

Charged to the trip it repositions **to** — you drive the empty leg because of the next job.
Across a day: **no leg counted twice, none dropped.** The figure is V47's frozen
`reposition_minutes`, never re-derived, so feasibility and costing cannot disagree.

An unmeasurable join leaves duty **null**, not the execution time alone.

## 6. Defects found and fixed

| # | Defect | Would have cost |
|---|---|---|
| 1 | **`costing` was missing from `ModuleBoundaryTest`'s module list** | The boundary rule passed **vacuously** over an entire new module. Added it — which immediately caught a real violation (costing borrowing `CostUnit`/`CostComponentStatus` from `rates`) and forced planning's dependency through a port |
| 2 | `Map.of().get(null)` on every vehicle-scoped profile | A 500 on create — an immutable map throws on a null key rather than answering null |
| 3 | `Permission` is an allowlist; the two new codes were absent | Every request 403 despite V48 granting the permissions |
| 4 | Labels always null on create/get | The row just added looked different from every other row until reload |
| 5 | Two `*_WITH_REPOSITION` distance sources nothing could emit | Vocabulary implying a reposition distance V1 does not charge |
| 6 | The integration fixture shared one vehicle across tests | The overlap constraint made them order-dependent — **the constraint was right, the fixture was wrong** |
| 7 | **`OwnFleetTripLookupAdapter` read a planning run by bare id** | A cross-tenant read: a UUID out of a request would have fetched whatever run it named. **Caught by JOB 15's `TenantScopedRepositoryTest`, only on the full `clean test`** |

### The first full `clean test` failed with 4 failures. All four were guards working.

| Guard | Said |
|---|---|
| `TenantScopedRepositoryTest` | `OwnFleetTripLookupAdapter -> PlanningRunRepository.findById` — **a real defect** (#7 above) |
| `SchemaExposureIntegrationTest` ×2 | `own_fleet_cost_profile` was not declared in the schema inventory. RLS and the policy were both present; the table simply had not been registered |
| `TenancyConstraintIntegrationTest` | The permission catalogue is asserted **exactly**: 58 → 60, and the role grants 163 → 168 |

None of this appears in a focused run. **The third time in this chain that `clean test` caught what a
targeted run could not.**

**Defect 1 is the one worth reading.** A new module invisible to the guard that exists to police
modules is worse than no guard, because it reports success.

## 7. Known limitation, recorded rather than hidden

**Reposition distance is not charged.** Duty includes the empty run; distance does not. V47 froze
the reposition's *minutes*, not its kilometres, and re-measuring at quote time would drift from the
frozen figure as the routing cache changes.

So V1 charges the driver and the vehicle for repositioning and charges no fuel, maintenance or
depreciation for it. **This understates a multi-trip day's distance costs** — and it errs in the
direction that does not make own fleet look better than it is.

## 8. Price vs cost, preserved end to end

`TransportCostNature` travels from the calculator to the screen. Both figures are labelled wherever
both appear, and `comparesCostAgainstPrice()` exists so a UI can say so without re-deriving it.

> An own-fleet estimate coming out lower than a carrier price is the expected shape of comparing a
> number with margin against a number without one. It is not, on its own, evidence that running it
> ourselves is cheaper.

`ProposalPricing.mixesPriceAndCost()` flags a total that adds the two.

## 9. Decisions taken, and what they rule out

| Decision | Alternative rejected |
|---|---|
| No company-wide fallback profile | A fuel rate averaged over a van and an articulated truck is wrong for both while looking authoritative |
| Expired profiles are not fallen back to | A June trip silently costed at last year's fuel price |
| `TOLL` flat, never per km | Kilometres × an average has the shape of a measurement and the content of a guess |
| Separate `OwnFleetComponent` from `RateComponent` | A carrier's `DISTANCE` charge is one number covering fuel, wear, driver and margin; mapping our fuel rate onto it makes a report summing across both meaningless |
| Own `OwnFleetUnit` / `OwnFleetLineStatus` | ArchUnit was right — borrowing two enums coupled two modules that must stay extractable |
| VIEWER gets neither permission | These rates are our cost structure, not our operation |

## 10. Preserved, as instructed

```
Settlement maker/checker    creator still cannot approve; rejection still allowed
D10 COST ALLOCATION         OPEN - no allocation rule chosen, implicitly or otherwise
ROUTING_UNKNOWN != 0 km     TravelMatrix.knows still gates every priced distance
NULL quantity != 0          V45 untouched
UNKNOWN COST != ZERO COST   the whole of this job
V47 and earlier             unmodified
```

**D10 = OPEN.** Reason: *no business allocation policy has been selected.* Own-fleet costing
computes what the transport costs; it does not decide how that cost is shared between orders or
customers, and choosing delivered quantity, weight, volume or pallets as a default would be
inventing the rule nobody has approved.

## 11. Not done

* **Nothing pushed, nothing deployed, no shared database touched.**
* No actual own-fleet cost — no fuel readings, no payroll, no workshop invoices, no toll records.
* No reposition distance (§7).
* No FX.
* No network modelling, what-if or digital twin.
