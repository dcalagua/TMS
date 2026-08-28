# TMS OVERNIGHT JOB 06 RESULT

RESULT=PASS
STOP_CHAIN=false

STARTED_AT=2026-08-28 02:51 America/Lima
COMPLETED_AT=2026-08-28 03:20 America/Lima

## OBJECTIVE

Evolve RateCard without breaking it: add the charges a real freight agreement contains, produce an
explainable breakdown, keep every amount in BigDecimal, snapshot what was used, and let planning
consume a port rather than internals.

## BASELINE

Clean tree at `3ec0d67`. Backend 1498, frontend 55, E2E 33. Flyway head V38, so **V39 was the next
free number** - checked on the filesystem.

## IMPLEMENTED

**Six new charges**, taking the card from six components to twelve: `STOP_OFF`, `FUEL_SURCHARGE`,
`WAITING_TIME`, `TOLL`, `OTHER_ACCESSORIAL`, `MAXIMUM_ADJUSTMENT`.

**The order of application, as a contract.** `RateComponent`'s declaration order *is* the
arithmetic, and two placements are decisions rather than details:
- fuel is a percentage of the **linehaul only** - a fuel percentage on a toll is a surcharge on a
  road authority's fee, which nobody bills. On a 175 linehaul with a 50 toll at 12% the two
  readings differ by 6.00 on **every** shipment;
- the floor and the ceiling are applied **after** the accessorials, not to the linehaul.

**The first stop is free.** `STOP_OFF` charges drops after the first, because the first is already
inside `BASE`. Getting this wrong overcharges the simplest shipment in the book.

**`LANE` scope** (origin → destination) - what most freight agreements are actually priced on, and
which neither `ORIGIN` nor `ROUTE` could express. **A multi-drop shipment is on no lane**:
`CostableTrip.soleDestinationId` is null for one, and the matcher refuses a null rather than letting
`Objects.equals(null, null)` price a four-stop trip against an agreement that was never about it.

**Distance provenance.** `DISTANCE` now prefers the shipment's **measured route** (JOB 04) over the
master corridor's reference, through a new `TripCostingLookupPort.findMeasuredDistanceKm` implemented
in planning. A trip with no master route can now be priced per kilometre - before V39 it simply
could not be, however far it drove. The line records which source it used.

## MIGRATIONS

**V39__rate_engine_v2.sql**. No applied migration touched. Seven rate columns, `destination_id`
plus its two company-pinned FKs, a rewritten scope-target rule covering four scopes, widened
component/unit/source/reason CHECKs, and `ck_trip_cost_component_amount_sign` - which carves
`MAXIMUM_ADJUSTMENT` out of V30's "amount >= 0" **by name**, because a ceiling adjusts the total
down and a positive one would read as another charge.

Every new column is nullable and no existing card changes, so **every price this system has ever
quoted still computes to the same number**.

## BACKEND

`RateComponent` (12 values, `isLinehaul`, `isAdjustment`), `CostUnit` (+STOP, HOUR, PERCENT),
`CostQuantitySource` (+4), `CostComponentReason` (+2), `RateCardScope` (+LANE, renumbered
specificity), `RateCard`, `RateComponents`, `CostInputs` (+ stops, waiting, distance provenance),
`TripCostCalculator` (the ordered arithmetic), `RateCardSelector`, `RateCardService` (LANE
validation, accessorial pair rule, min/max rule), `RateCardView`, `CostableTrip`
(+ soleDestinationId, stopCount), `TripCostingLookupAdapter`, `TripCostService`.

Compatibility constructors were added on `CostInputs`, `RateComponents`, `RateCardRequest`,
`CostableTrip` and `RateCard` so that a caller charging none of the new components need not name
seven nulls - a 24-field request literal is noise around the two fields a test is about.

## FRONTEND

Scope gains LANE with a destination selector; the amounts grid gains six inputs; labels for every
new component, unit, reason and source. **The breakdown table needed no change** - it renders
components generically, so the new lines appeared with their labels once the enums knew them.

## DATABASE

Flyway contiguous V1-V39. Every Testcontainers class applies the full history to a fresh database.

## SECURITY

No new endpoint and no new authority. LANE targets are validated against **active masters of the
caller's company** through the same ports every other scope uses, so a lane cannot be pointed at
another tenant's location; `fk_rate_card_destination_company` makes that a database fact as well.

## TENANT_TESTS

No new tenant surface. Lane validation goes through `DestinationLookupPort.findActiveInCompany`,
which carries the company predicate in the query, and the composite FK pins the row. Existing
rate-card tenancy coverage passes unchanged.

## AUDIT

Unchanged vocabulary: `CREATE`/`UPDATE` on `RATE_CARD` and `COST_ESTIMATED` on the shipment already
record what changed and against which agreement. No new action was minted, because no new *kind* of
decision was introduced - a card with a fuel surcharge is still a card.

## OBSERVABILITY

None added. Rating metrics are JOB 15's and were left there rather than half-done.

## TESTS_FOCUSED

`RateEngineV2CalculatorTest` (15) and four lane-selection tests. The calculator suite asserts the
fuel-on-linehaul arithmetic, the free first stop, the negative ceiling, limits-applied-last,
waiting-is-unknown-not-zero, absent-vs-not-calculable, distance provenance both ways, and a full
seven-line breakdown that sums exactly to its total.

## TESTS_CLEAN

`./mvnw -B clean test` - **1517 tests, 0 failures, 0 errors**, BUILD SUCCESS. (+19.)

## FRONTEND_TESTS

typecheck clean; lint 0 errors (17 pre-existing warnings); `npm test` **55 passed**; build succeeds.

## E2E

33 passed, 7 skipped.

## RETRIES_ATTEMPTED=6
## RETRIES_RECOVERED=6

1. **TYPE C.** `MAXIMUM_ADJUSTMENT` is negative and V30's CHECK demanded `amount >= 0`. Fixed in
   V39 (not applied outside disposable databases, per the migration retry rule) by carving out the
   one component by name rather than dropping the rule.
2. **TYPE C.** Test call sites building `CostInputs` directly - compatibility constructor added.
3. **TYPE C, and a real regression of mine.** I had added `minimum_amount` to
   "a card must charge something". `RateCardServiceTest.minimumAloneIsNotAComponent` caught it: V30
   deliberately excluded it, because a floor is a rule *about* other charges. Reverted in both the
   entity and the migration.
4. **TYPE C, and the one worth recording.** I had changed `DISTANCE`'s quantity source constant to
   `MEASURED_ROUTE` before wiring any measured distance, so every line would have *claimed*
   `MEASURED_ROUTE` while the number still came from the route master. `lineProvenance` caught it.
   Fixed properly: provenance is now a fact about the **estimate** (`CostInputs.distanceSource`),
   not a constant on the component.
5. **TYPE A.** `maven-clean-plugin` failed to delete `target/test-classes` - a transient file lock.
   Recovered on the first retry after a 10-second wait.
6. **TYPE C.** `RateCardScope.LANE` silently failed to insert (my edit did not match), which the
   clean compile caught as `cannot find symbol`. Inserted properly with the specificity renumbered.

## BLOCKED_GATES

None. Docker up throughout. No remote environment contacted.

## KNOWN_LIMITATIONS

- **Proposal pricing is still not wired**, so JOB 05's `PlanningKpis.totalCost` remains null. The
  pieces now exist - a proposed trip's carrier is its vehicle's carrier - but wiring it is its own
  change with its own tests, and a cost that appeared without them would be exactly the fabricated
  figure JOB 05 refused. Named as the first candidate for the next rating change.
- **Waiting time is never populated.** Nothing in the product records detention, so the line is
  always non-calculable. That is honest rather than broken: the alternative is showing zero, which
  would claim the truck did not wait.
- **No ZONE scope** - needs a zone resolved at rating time, which is a masterdata lookup this
  module does not have.
- **No break-weight tiers**, **no FX**, **no customer sell rates** - each stated in the doc with
  its reason.
- **Delivery quantity remains unmodelled** (JOB 03). Nothing here needed or inferred it.

## FILES_CHANGED

    backend/.../db/migration/V39__rate_engine_v2.sql                   new
    backend/.../rates/domain/RateComponent.java                        12 components, ordered
    backend/.../rates/domain/{CostUnit,CostQuantitySource,CostComponentReason}.java   extended
    backend/.../rates/domain/RateCardScope.java                        + LANE
    backend/.../rates/domain/{RateCard,RateComponents,CostInputs,CostLine}.java       extended
    backend/.../rates/domain/TripCostCalculator.java                   the ordered arithmetic
    backend/.../rates/domain/RateCardSelector.java                     lane matching
    backend/.../rates/application/{RateCardService,RateCardRequest,RateCardView,TripCostService}.java
    backend/.../shared/reference/{CostableTrip,TripCostingLookupPort}.java
    backend/.../planning/infrastructure/TripCostingLookupAdapter.java  measured distance
    frontend/.../shared/api/ratesApi.ts                                scopes, components, fields
    frontend/.../pages/rates/RateCardFormDrawer.tsx                    lane + six charges
    frontend/.../lib/enums.ts, lib/i18n.ts                             labels, ES + EN
    docs/domain/RATE_ENGINE_V2.md                                      new

## LOCAL_COMMIT

One local commit. No push.

## NEXT_JOB

**JOB 07 - Carrier Selection + Tender Waterfall.** Its dependency is satisfied: ranking carriers
needs a rate per carrier, and rating is now complete enough to produce one per candidate.
Next migration: **V40**.
