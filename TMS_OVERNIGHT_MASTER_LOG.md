# TMS OVERNIGHT MASTER LOG

Enterprise TMS Evolution - OTM-inspired, EBIM architecture.
Chain started **2026-08-28** on branch `dev` from commit `0757afb`.

This file is the resume point. If the run is interrupted, read `LAST_COMPLETED_JOB` below, verify
that its RESULT still matches the working tree, and continue from the next pending job.

## Status board

| Job | Title | Result | Finished | Commit | Migration | Backend tests | STOP_CHAIN |
|---|---|---|---|---|---|---|---|
| 01 | Truth Baseline + Documentation | **PASS** | 2026-08-28 01:10 | `f666d63` | none (V35 is head; V36 next) | 1312 / 0 fail | false |
| 02 | Order Lifecycle V2 | **PASS** | 2026-08-28 01:40 | `32dcc65` | **V36** | 1389 / 0 fail | false |
| 03 | Ship Units + Partial Allocation | **PASS** | 2026-08-28 01:58 | `91540cb` | **V37** | 1409 / 0 fail | false |
| 04 | Routing Matrix + Travel Time | **PASS** | 2026-08-28 02:30 | `29b484c` | **V38** | 1466 / 0 fail | false |
| 05 | Advanced Bulk Planning Engine V2 | **PASS** | 2026-08-28 02:50 | `586e7ed` | none (V38 head) | 1498 / 0 fail | false |
| 06 | Rate Engine V2 | **PASS** | 2026-08-28 03:20 | `38172c3` | **V39** | 1517 / 0 fail | false |
| 07 | Carrier Selection + Tender Waterfall | **PASS** | 2026-08-28 03:45 | `631fa3c` | **V40** | 1536 / 0 fail | false |
| 08 | Dock / Appointment Scheduling | **PASS** | 2026-08-28 04:26 | `af57109` | **V41** | 1585 / 0 fail | false |
| 09 | Fleet Resource Scheduling | **PASS** | 2026-08-28 04:55 | `f46b5b0` | **V42** | 1617 / 0 fail | false |
| 10 | ETA + Geofencing + Predictive Tracking | pending | - | - | - | - | - |
| 11 | Freight Audit & Settlement | pending | - | - | - | - | - |
| 12 | Exception Management + Control Tower V2 | pending | - | - | - | - | - |
| 13 | Enterprise Integration Operations | pending | - | - | - | - | - |
| 14 | Enterprise UX + Frontend Testing | pending | - | - | - | - | - |
| 15 | Observability + Performance + Security | pending | - | - | - | - | - |
| 16 | Final Enterprise Certification | pending | - | - | - | - | - |

**LAST_COMPLETED_JOB = 13**

## OPEN TECHNICAL / DOMAIN DEBTS

Carried forward and re-stated after every job. A debt is never deleted because it has become
inconvenient - it moves to RESOLVED with the job that closed it, or to DEFERRED_WITH_REASON.

| # | Debt | Status | Notes |
|---|---|---|---|
| **D1** | `PlanningKpis.totalCost` is null - a proposal is not priced | **CLOSED (JOB 11)** | JOB 06 built the rating; a proposed trip's carrier is its vehicle's carrier, so the pieces exist. Must not sum incompatible currencies. Close before Planning V2 is called integrated with Settlement (JOB 11) |
| **D2** | An accepted tender can leave `shipment.carrier != shipment.vehicle.owner` | **CLOSED (V42, JOB 09)** | JOB 07 refused silent reassignment. **JOB 09 must resolve the invariant formally** - clear the vehicle, select a compatible one atomically, or model `RESOURCE_ASSIGNMENT_PENDING`. Never leave the previous carrier's vehicle attached |
| **D3** | Delivery records an outcome, not a delivered quantity | **OPEN, formally evaluated (JOB 10)** | `PARTIAL` implies no demonstrable amount. Must not be inferred from ordered/allocated/planned. Evaluate formally at JOB 10, close before JOB 11 if Settlement needs it |
| **D4** | No automatic tender scheduler: no system-actor model | **DEFERRED_WITH_REASON** | `requireAppUserId` refuses machines *by design* - an offer is a commercial commitment and the trail must name who made it. No fake user, hardcoded UUID or anonymous principal. Manual waterfall advance stands. Design only, if JOB 15 raises a real requirement |
| **D5** | No work assignment: several shipments cannot be sequenced onto one driver-and-vehicle pair with travel time between them | **OPEN** - new in JOB 09 | Deliberate. V42 delivers the availability layer it would be built on; a table nothing writes to would be scaffolding |
| **D6** | No internal cost model for own fleet - fuel, driver hours, depreciation | **OPEN** - new in JOB 11 | A plan mixing a carrier's price with an own-fleet estimate compares two unlike numbers. Own fleet is deliberately left unpriced rather than priced at zero |
| **D7** | Control Tower V1 has no backend tests | **OPEN** - new in JOB 12 | Summary counts, the three V1 panels, capping and the `ordersUnplanned` permission rule (null, not zero, without `orders.order:read`) are uncovered. The V2 blocker panel is covered by 7 tests |

## Baseline established by JOB 01

Every gate measured, all green. Later red is therefore attributable to the job that caused it.

    Backend      ./mvnw -B test          1312 tests, 0 failures, 0 errors    BUILD SUCCESS
    Typecheck    npm run typecheck       clean
    Lint         npm run lint            0 errors, 17 pre-existing warnings  exit 0
    Frontend     npm test                37 tests, 4 files, 0 failures
    Build        npm run build           1.11 MB bundle, chunk-size advisory only
    E2E          npx playwright test     33 passed, 7 skipped (auth smoke, no credentials)
    Flyway       V1 - V35 contiguous     next available: V36 (JOB 02 used it; V37 is next)

Docker Desktop was started locally, so the 32 Testcontainers classes ran for real. No remote
environment was contacted at any point.

## Job notes

### JOB 01 - 2026-08-28 - PASS

Baseline reconstructed from code. No production code changed; documentation only.

**Key findings.** The governing `CLAUDE.md` instructed Bootstrap + SweetAlert2 and "avoid MUI" while
the product is built entirely on MUI - the highest-severity drift found, because it would have
misdirected every later frontend job. Resolved by **ADR-008** and corrections to the five
authoritative documents; historical step reports left intact as dated records.

**Discovered already built** (later jobs must extend, not rebuild): the `PlanningEngine` port with
`HEURISTIC_V1` already named; `trip_order_assignment.whole_order` with a partial unique index V11
wrote specifically to admit split allocation later; `OrderDelivery` / `DeliveryResult` with POD
evidence; explicit transition tables on trip, tender and stop status; the idempotent integration
inbox with scopes.

**Genuinely missing** (not stubs): appointments, ship units, fleet availability, carrier invoices
and settlement, geofences, a routing/distance abstraction, generic operational exceptions,
Micrometer metrics.

Published `docs/architecture/TMS_CURRENT_CAPABILITY_MAP.md` - 25 rows, each partial or missing
capability carrying the job that closes it.

### JOB 02 - 2026-08-28 - PASS

The order lifecycle now reaches the end of the road. **V36** adds `IN_EXECUTION`, `DELIVERED`,
`PARTIALLY_DELIVERED` and `DELIVERY_FAILED`, plus `POST /orders/{id}/reopen` for a second attempt.

**The defect this closed.** An order whose delivery was refused stayed `PLANNED` forever: not
plannable, not cancellable, not deliverable. A customer waiting for a redelivery was invisible to
the system that owed them.

**The design tension.** `OrderFulfillmentStatus` carried a reasoned objection to storing delivery
outcomes on the order. It was upheld rather than overruled: the new states are the lifecycle
*consequence* of the delivery fact, not a copy of it, and drift is prevented structurally - the
status is recomputed from the delivery rows in the same transaction as every change to them,
including corrections keyed after the trip closed. `OrderFulfillmentStatus` is unchanged.
Recorded in **ADR-009**; the lifecycle is documented in `docs/domain/ORDER_LIFECYCLE_V2.md`.

**Caught in passing.** `OrderBacklogTotals` would have silently dropped departed orders out of the
KPI planned-rate - a reporting regression no existing test would have flagged, because the figure
would still have been a number. It now derives `planned()` from leaf counters so the identity holds
by construction.

**Tests:** 1389 backend (+77), 42 frontend (+5), 33 E2E. New: `OrderStatusTest` (40 pure-domain),
`OrderPlanningServiceExecutionTest` (idempotency, replay safety, the row lock),
`OrderExecutionPropagatorTest`, and six smoke steps driving the vertical over HTTP from dispatch
through close-out, correction and reopen.

### JOB 03 - 2026-08-28 - PASS

One order can now be split across several trips without being duplicated. **V37** adds the
allocation ledger's ceiling.

**What was already there.** V11 had written the ledger and said so in its header: the assignment
row carries *allocated* amounts, capacity already sums that table, and the whole-order unique index
was deliberately partial so a split would fall outside it. The job was to give it a ceiling, not to
build it.

**The design call.** `allocated <= ordered` had to survive two planners racing, and a CHECK cannot
sum a ledger - so the running total lives on the order row. That looks like the thing ADR-009 argued
against a job earlier; it is not. ADR-009 rejected storing a derived figure *for convenience*. Here
storing it is the only way the rule becomes a database guarantee instead of a hope.

**No ship_unit table**, and that is deliberate: a ship unit here is a portion of demand in the three
measures a vehicle is constrained by. Order lines carry their own `uom`, so a fourth "quantity"
measure would be one nothing else in the product uses. Documented in
`docs/domain/SHIP_UNITS_AND_ALLOCATION_V1.md`.

**Proved over HTTP**: 70 pallets on one truck, 30 on another, one order row, two assignment rows,
the stored total agreeing with the ledger recomputed from those rows - and two concurrent
70-of-100 splits where exactly one wins.

**Known sharp edge**, stated rather than hidden: deliveries record an outcome, not an amount, so an
order reopened after a *partial* delivery is replanned in full.

**Tests:** 1409 backend (+20), 47 frontend (+5), 33 E2E.

### JOB 04 - 2026-08-28 - PASS

STARTED_AT=02:06 · COMPLETED_AT=02:30 · HEAD_BEFORE=`2f7dca6` · HEAD_AFTER=`29b484c` · MIGRATION=**V38**
BACKEND_PASS=1466 · BACKEND_FAIL=0 (clean build) · FRONTEND_PASS=55 · E2E_PASS=33 · RETRIES=2, both recovered

Distance and travel time now live behind `RoutingPort` with a company-scoped cache
(`tms.travel_estimate`), a `RoutingProviderAdapter` seam and a local geodesic estimator that is the
whole of routing when no vendor is configured. **ADR-010** records the reasoning, which follows
ADR-007's for tracking.

**Not scaffolding.** `TripRoutingService` is a real consumer: a trip reports how far it drives and
how long that takes, on the API and on the workspace, and names the legs it could not measure.

**The defect worth remembering.** `RoutingSource` originally had a `CACHE` value, so serving a
cached row overwrote its source - silently turning a straight-line estimate into something
indistinguishable from a measured road the moment it was stored. The smoke run caught it on the
second read of the same trip. Fixed at the design level: *how a number was produced* and *where the
read came from* are now two independent fields. A per-km charge computed from a straight line stays
visibly so.

**Also caught:** `ck_travel_estimate_expiry_after_calculation` refused a test that was manufacturing
a row born already expired. The constraint was right; the test now ages rows honestly.

**Delivery quantity** (JOB 03's known limitation) is unchanged and was not needed here. Nothing in
JOB 04 inferred a quantity.

### JOB 05 - 2026-08-28 - PASS

STARTED_AT=02:31 · COMPLETED_AT=02:50 · HEAD_BEFORE=`714d16c` · HEAD_AFTER=`586e7ed` · MIGRATION=**none**
BACKEND_PASS=1498 · BACKEND_FAIL=0 (clean) · FRONTEND_PASS=55 · E2E_PASS=33 · RETRIES=3, all recovered

`PLANNING_V2` joins `HEURISTIC_V1` behind the same port. It packs against **pending** amounts (V37),
sequences stops nearest-neighbour on the **travel matrix** (V38), and refuses trips that will not
fit a shift. **Default remains `HEURISTIC_V1`** - opt-in per run, so no existing caller's proposals
change silently.

**The comparison is measured, not asserted.** Six stops fed farthest-first: V1 drives out and back,
V2 drives 85 km straight out with identical loads. No matrix → identical trips. One-hour shift → V2
plans **fewer** orders, correctly, and that case is in the suite on purpose.

**Cost is null and stays null.** Pricing a proposal needs a rating port that takes a proposal, not a
persisted shipment - JOB 06. Documented, and said on screen, rather than filled with a number two
engines would be compared on.

**Caught in passing.** My first V2 carried its own copy of `Corridors` that ignored
`route.active()`. Extracted V1's instead: two engines must group identically or the comparison is
meaningless. Also: a javadoc claiming "in registration order" over a `Map.copyOf` - the doc was the
lie and the implementation was fixed to match it.

**Open, and named:** service time per location is carried by the input but passed empty, so
durations are driving-only today. **Delivery quantity** (JOB 03) unchanged and not inferred.

### JOB 06 - 2026-08-28 - PASS

STARTED_AT=02:51 · COMPLETED_AT=03:20 · HEAD_BEFORE=`3ec0d67` · HEAD_AFTER=`38172c3` · MIGRATION=**V39**
BACKEND_PASS=1517 · BACKEND_FAIL=0 (clean) · FRONTEND_PASS=55 · E2E_PASS=33 · RETRIES=6, all recovered

Six charges added (stop-off, fuel, waiting, toll, accessorial, ceiling) plus **LANE** scope. **The
order of application is the contract**: fuel is a percentage of the linehaul and of nothing after
it, and the limits come after the accessorials. On a 175 linehaul with a 50 toll at 12%, the wrong
reading is off by 6.00 on every shipment - which is why it is asserted rather than assumed.

**The first drop is free**: it is already inside the base. **A multi-drop shipment is on no lane** -
the matcher refuses a null destination rather than matching by coincidence.

`DISTANCE` now prefers the shipment's **measured route** (JOB 04), so a trip with no master route
can be priced per kilometre at all.

**The retry worth remembering (#4).** I changed `DISTANCE`'s quantity-source constant to
`MEASURED_ROUTE` before wiring any measured distance, so every line would have *claimed* it while
the number still came from the route master. A pre-existing test caught it. Provenance is now a fact
about the estimate, not a constant on the component.

**Also caught:** I had made a lone minimum count as "a charge", which V30 deliberately refuses - a
floor is a rule about other charges. Reverted in entity and migration.

**Open:** proposal pricing still unwired, so JOB 05's `totalCost` stays null; the pieces exist but a
number without tests would be the fabrication JOB 05 refused. **Waiting time** is never populated -
honest, not broken. **Delivery quantity** (JOB 03) unchanged and not inferred.

### JOB 07 - 2026-08-28 - PASS

STARTED_AT=03:22 · COMPLETED_AT=03:45 · HEAD_BEFORE=`3ec0d67` · HEAD_AFTER=`631fa3c` · MIGRATION=**V40**
BACKEND_PASS=1536 · BACKEND_FAIL=0 (clean) · FRONTEND_PASS=55 · E2E_PASS=33 · RETRIES=4, all recovered

The waterfall: A rejected → B expired → C accepted, ranked by what each carrier would charge through
**the same selector and calculator that price the invoice**. The ranking is **stored**, not
recomputed, so the list walked is the list approved.

**"No tariff entered" is not "free"** - such a carrier ranks last, not first. Currencies are never
converted; the majority currency is the reference.

**Two limits are decisions, and both are documented rather than worked around:**

1. **No background scheduler.** `requireAppUserId` refuses a machine *by design* - an offer to a
   carrier is a commercial commitment and the trail must name who made it. A sweep needs a
   system-actor concept this product does not have, and inventing one at speed would put an
   unattributable commitment into the history. The waterfall reports `currentOfferLapsed` and a
   dispatcher advances. The follow-up design is written down in the domain doc.
2. **Accepting does not reassign the vehicle** - a shipment's carrier is its vehicle's owner, so
   doing it silently would leave the two disagreeing.

**Delivery quantity** (JOB 03) unchanged and not inferred.

### JOB 08 - 2026-08-28 - PASS

JOB=08 · STARTED_AT=03:55 · COMPLETED_AT=04:26 · HEAD_BEFORE=`1d2d3e5` · HEAD_AFTER=`af57109`
MIGRATION=**V41** · BACKEND_CLEAN_PASS=1585 · BACKEND_CLEAN_FAIL=0
FRONTEND_PASS=60 · E2E_PASS=34 · E2E_SKIPPED=7 · RETRIES=6, all recovered

Dock scheduling: doors, local opening hours, closures, and the seven-state appointment lifecycle.

**No double booking is a database fact**, not a service check: `EXCLUDE USING gist` over
`(resource_id, tstzrange(window_start, window_end))`. A test runs two real threads booking one door
for one hour at the same instant and asserts exactly one wins. A door takes one vehicle and six
doors are six rows - PostgreSQL cannot refuse "more than N overlapping", so a capacity column would
put the invariant back in application code.

**DEFECTS_FOUND=1 (by a CHECK constraint), DEFECTS_FIXED=1.** Opening hours in a `time` column came
back zone-shifted: `hibernate.jdbc.time_zone: UTC` normalises temporal values on write, turning
00:00 into `05:00+00` and 23:59 into `04:59+00` - close before open. **Every site's opening hours
would have silently shifted by its own UTC offset.** Now stored as minutes since local midnight,
which no configuration can shift.

**Also caught by existing guards:** the trip port written in the wrong module (ModuleBoundaryTest),
three permissions with no capability (CapabilityTest), and the exact permission counts
(TenancyConstraintIntegrationTest) - each working exactly as designed.

**No WMS/EWM table, column or view was created.** The boundary is a port, documented in
`docs/domain/APPOINTMENTS_V1.md`.

OPEN_DEBTS: D1 OPEN · D2 OPEN (JOB 09's) · D3 OPEN, not needed or inferred here ·
D4 DEFERRED_WITH_REASON, unchanged.

NEXT_JOB=09 Fleet Resource Scheduling, which must resolve D2. Next migration **V42**.

### JOB 09 - 2026-08-28 - PASS

JOB=09 · STARTED_AT=04:26 · COMPLETED_AT=04:55 · HEAD_BEFORE=`af57109` · HEAD_AFTER=`f46b5b0`
MIGRATION=**V42** · BACKEND_CLEAN_PASS=1617 · BACKEND_CLEAN_FAIL=0 · BACKEND_SKIPPED=0
FRONTEND_PASS=69 · E2E_PASS=34 · E2E_SKIPPED=7 · RETRIES=5, all recovered

**D2 is closed.** Two of the three resolutions the brief offered turned out to be unavailable:
clearing the vehicle is impossible (`ck_trip_confirmed_is_complete` requires one on every confirmed
trip, and only confirmed trips are tenderable), and picking one of the accepting carrier's
automatically would mean choosing among another company's fleet by rules nobody has stated. So the
third: `accepted_carrier_id` records who agreed, `carrier_id` goes on meaning the owner of the
assigned vehicle, and **a shipment where the two disagree cannot depart** - refused in the service,
in the aggregate, and by `ck_trip_departed_carrier_matches_vehicle`. There is no separate resolve
action: assigning one of the accepting carrier's vehicles is what fixes it.

Also fleet availability - vehicle and driver downtime with overlaps made impossible by two partial
`EXCLUDE` constraints (two threads, one truck, one row), weekly driver shifts stored as **minutes
since local midnight**, and `ResourceAvailabilityPort` so planning asks rather than reaches in.

**DEFECTS_FOUND=3, DEFECTS_FIXED=3.** The block delete resolved by bare id, so the driver endpoint
could remove a vehicle's block and the vehicle endpoint a person's - which would have undone the
`fleet.driver` / `fleet.vehicle` split V26 made precisely so workshop clerks cannot see who is off
sick. An integration acceptance would have overwritten `updatedBy` with null. And three existing
guards - `SchemaExposureIntegrationTest`, `ck_trip_committed_requires_confirmed_at`,
`ck_trip_ready_actor_pair` - each caught exactly what it was built for.

**No new permission pair**, deliberately: reads and writes ride the existing `fleet.vehicle:*` and
`fleet.driver:*`, so the personal-data boundary holds and the permission count is unchanged.

**Backend skipped 7 → 0**: Docker was up throughout this run, so the Testcontainers tests skipped
during JOB 08 all executed. No failing test was converted to a skip anywhere.

**NOT delivered, by decision:** no work-assignment table. Sequencing several shipments onto one
driver-and-vehicle pair with travel time between them needs a scheduling model, a rebalancing story
and its own screen; V42 ships the availability layer underneath it. Recorded as **D5**, not claimed.

OPEN_DEBTS: D1 OPEN · **D2 CLOSED** · D3 OPEN (JOB 10's) · D4 DEFERRED_WITH_REASON ·
**D5 OPEN (new)** - no work assignment.

NEXT_JOB=10 ETA / Geofencing, which must evaluate D3. Next migration **V43**.

### JOB 10 - 2026-08-28 - PASS

JOB=10 · STARTED_AT=04:57 · COMPLETED_AT=05:22 · HEAD_BEFORE=`362f586` · HEAD_AFTER=`d5d49f8`
MIGRATION=**V43** · BACKEND_CLEAN_PASS=1643 · BACKEND_CLEAN_FAIL=0 · BACKEND_SKIPPED=0
FRONTEND_PASS=72 · E2E_PASS=34 · E2E_SKIPPED=7 · RETRIES=4, all recovered

**This job opened with a conflict against `CLAUDE.md`**, which defers ETA, geofencing and automatic
arrival detection. The repository's own rule - add an ADR rather than diverge silently - was
followed: **ADR-011** moves exactly one of the three. ETA moves because V27's objection to it was
about *inputs* ("there is nothing to put in them") and V38, V14 and V11 have since supplied every
term. **Automatic arrival detection does not move**, because ADR-007 says positions inform people
and never move a lifecycle, and because no vendor adapter exists to supply the feed. `CLAUDE.md` was
updated to say so rather than left contradicting the code.

Three rules carry the feature, and the first is what it is judged by: **an unmeasurable leg ends the
chain** - no guess, no previous stop's time, no zero, for that stop and every stop after it.
Provenance degrades and never upgrades (no `CACHE` value, for the reason V38 records). A window is
never made to fit: early is a wait, late is flagged and not moved to the next morning.

**DEFECTS_FOUND=2, DEFECTS_FIXED=2.** The service asked one lookup port for both origins and
destinations, which returns an empty map for the other half and silently loses the whole run's ETA
while appearing to work. And an end-to-end test asserted arrival times from an origin the fixture
deliberately leaves un-geocoded - the code was right, so the test was split rather than weakened,
and rule 1 gained end-to-end coverage out of the failure.

**A defect looked for and not found:** the stop's service window is a `time` column, the exact shape
JOB 08 proved gets shifted. Investigated before building on it; the round trip is symmetric and
already covered in `OrderApiIntegrationTest`. A probe test written during the investigation was
**deleted rather than kept** - it exercised raw JDBC and could not support the claim its javadoc
made.

**D3 formally evaluated** (`docs/domain/DELIVERED_QUANTITY_EVALUATION.md`): a missing capability and
not a defect; must not be inferred from ordered, allocated or planned, each of which is wrong in
precisely the `PARTIAL` case; and **it does not block JOB 11**, because every rate component prices
the shipment rather than the handover.

OPEN_DEBTS: D1 OPEN (JOB 11's) · D2 CLOSED · **D3 OPEN, formally evaluated** ·
D4 DEFERRED_WITH_REASON, now also why the ETA has no background job · D5 OPEN.

NEXT_JOB=11 Settlement, which must close D1. Next migration **V44**.

### JOB 11 - 2026-08-28 - PASS

JOB=11 · STARTED_AT=05:24 · COMPLETED_AT=05:36 · HEAD_BEFORE=`25554cc` · HEAD_AFTER=`4e57642`
MIGRATION=**none** · BACKEND_CLEAN_PASS=1654 · BACKEND_CLEAN_FAIL=0 · BACKEND_SKIPPED=0
FRONTEND_PASS=76 · E2E_PASS=34 · E2E_SKIPPED=7 · RETRIES=3, all recovered

**No migration, and that is the honest answer rather than a shortfall:** planning KPIs are computed
per proposal and never stored, so D1 was a correctness debt and not a schema gap. Adding a migration
to make the job look substantial would have been the empty scaffolding the brief forbids. V43 stands.

**D1 is closed.** The missing piece was a rating port taking a proposal, and it already existed -
JOB 07's `CarrierQuotationPort` prices a shipment against a carrier it does not have, which is what
a tender is. A proposal is that question one step earlier, so it is asked through the same port,
selector and calculator a tender and an invoice use. A plan compared on price and the bill that
follows it now come from one set of rules.

**Most of the feature is refusals, and 6 of the 11 new tests assert one.** No partial totals - one
unpriceable trip means no total, because a sum omitting the trips nobody has an agreement for makes
the *worse* plan look cheaper. No currency conversion (the brief's explicit constraint, enforced by
a test). No invented distance. Own fleet is not priced at zero.

**DEFECTS_FOUND=2, DEFECTS_FIXED=2.** `TravelMatrix.distanceKm` answers zero for a leg it does not
know - correct for planning, silently catastrophic once a distance is multiplied by money; fixed
with `TravelMatrix.knows` and a null distance for the whole run. And my own first `PlanningKpis`
derived `totalCost` inside the compact constructor, quietly rewriting an argument the caller passed.

**The engines stay pure functions.** Pricing needs a rate card, so it happens once in
`AutoPlanningService` against the proposal the engine produced - never inside an engine, which would
cost the reproducibility its whole test suite rests on.

**D3 was not closed, and building Settlement confirmed JOB 10's evaluation:** every rate component
prices the shipment, none prices the handover. Nothing here created or inferred a delivered quantity.

OPEN_DEBTS: **D1 CLOSED** · D2 CLOSED · D3 OPEN, evaluated, confirmed not blocking ·
D4 DEFERRED_WITH_REASON · D5 OPEN · **D6 OPEN (new)** - no internal cost model for own fleet.

NEXT_JOB=12 Control Tower V2.

### JOB 12 - 2026-08-28 - PASS

JOB=12 · STARTED_AT=05:38 · COMPLETED_AT=05:48 · HEAD_BEFORE=`d8b5ccc` · HEAD_AFTER=`6a2a862`
MIGRATION=**none** · BACKEND_CLEAN_PASS=1661 · BACKEND_CLEAN_FAIL=0 · BACKEND_SKIPPED=0
FRONTEND_PASS=79 · E2E_PASS=34 · E2E_SKIPPED=7 · RETRIES=3, all recovered

Everything the control tower reported was **retrospective**. JOBs 09-11 created several states that
make `dispatch` refuse, and nothing surfaced them until a dispatcher reached the gate. V2 adds one
panel for that: shipments accepted by a carrier that does not own their vehicle (D2's state), and
shipments whose vehicle or driver cannot work at the planned departure.

**Nothing here is a new rule** - each reason is a refusal that already exists in three layers, which
is what makes the panel trustworthy rather than one more badge people learn to ignore.

Asked at each shipment's **own planned departure** and not at `now()`: a truck free this minute and
in the workshop at 14:00 still cannot run a 14:00 shipment, and one blocked next Tuesday is not a
blocker today.

**Deliberately one panel and not several.** An appointments tile and an ETA tile would have made the
job look bigger and the screen worse - mixing hard stops with things that merely worry somebody is
how a panel stops being actionable. Both are named in the doc as not-built, with the reason.

**FINDING: the control tower had no backend tests at all.** The new panel is covered with 7,
including the two cases most likely to rot. Backfilling V1 is recorded as **D7** rather than folded
in here, where it would have turned this job into a test-writing exercise.

DEFECTS_FOUND=0. The change is additive and both new queries are read-only.

OPEN_DEBTS: D1 CLOSED · D2 CLOSED · D3 OPEN, evaluated · D4 DEFERRED_WITH_REASON · D5 OPEN ·
D6 OPEN · **D7 OPEN (new)**.

NEXT_JOB=13 Integration Ops, or 15 Hardening if time runs short - the brief ranks hardening above
JOB 14.

### JOB 13 - 2026-08-28 - PASS

JOB=13 · STARTED_AT=05:49 · COMPLETED_AT=05:59 · HEAD_BEFORE=`83d3917` · HEAD_AFTER=`d06a38d`
MIGRATION=**none** · BACKEND_CLEAN_PASS=1669 · BACKEND_CLEAN_FAIL=0 · BACKEND_SKIPPED=0
FRONTEND_PASS=82 · E2E_PASS=34 · E2E_SKIPPED=7 · RETRIES=3, all recovered

**The inspection changed the job.** Integration ops was largely built - delivery list and detail,
attempt history, per-delivery retry, activate/deactivate, secret rotation, a `SKIP LOCKED`
dispatcher, an inbound inbox with typed outcomes. Building a second version would have been
busywork. What was missing is what two paginated lists cannot do: **answer a question**.

One endpoint, carrying the two signals the lists could not. **Age, not count** - a queue of a
thousand that is draining is healthy and three waiting since Tuesday is not, and a counter cannot
order those. And **the failure that looks like silence** - deactivating a subscription discards
nothing and keeps queueing, so a partner switched off during an incident and never switched back on
produces no errors at all.

**DEFECTS_FOUND=1, DEFECTS_FIXED=1, and it is why `clean test` is the gate.** My cross-entity JPQL
used `d.subscriptionId`, which does not exist - the entity holds a `@ManyToOne`. **`mvnw compile`
passed.** Spring validated the query at context startup and took down **323 tests** from one bad
string, because that repository sits on the shipment event publisher's dependency path. Second time
this chain has been saved by running `clean test` instead of trusting a compile.

**No bulk retry, deliberately:** the reason forty deliveries failed is usually still true, and
re-queueing them all turns one broken endpoint into forty more attempts against it.

OPEN_DEBTS: unchanged - D1 CLOSED · D2 CLOSED · D3 OPEN evaluated · D4 DEFERRED_WITH_REASON ·
D5 OPEN · D6 OPEN · D7 OPEN. No new debt.

NEXT_JOB=15 Hardening, which the brief ranks above JOB 14.
