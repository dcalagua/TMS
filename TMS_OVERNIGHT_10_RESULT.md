# JOB 10 - Stop ETA and geofencing

**RESULT = PASS** · **STOP_CHAIN = false** · **MIGRATION = V43**

| | |
|---|---|
| Started | 2026-08-28 04:57 America/Lima |
| Completed | 2026-08-28 05:22 America/Lima |
| HEAD before | `362f586` |
| Backend, `./mvnw clean test` | **1643 pass, 0 fail, 0 skipped** |
| Frontend, `vitest run` | **72 pass** |
| E2E, `playwright test` | **34 pass, 7 skipped** |
| Typecheck / lint / build | clean |
| Flyway | V1-V43, contiguous, no gaps |
| Retries | 4 attempted, 4 recovered |

---

## The conflict this job opened with, and how it was resolved

`CLAUDE.md` defers three things by decision: "ETA calculation, geofencing and automatic arrival
detection remain deferred." JOB 10's brief asks for two of them. The repository's own rule for that
situation is explicit - *add a new ADR instead of silently diverging* - so **ADR-011** was written,
and it moves exactly one of the three.

**ETA moves, because the objection to it expired rather than being overruled.** V27 refused per-stop
planned times and gave a reason, not a preference:

> There is nothing to put in them: TMS has no ETA engine... Two columns holding an arrival time
> nobody computes would read as a plan the actual times could be judged against, and there would be
> no such plan.

That objection was about **inputs**. V38/ADR-010 supplied per-leg driving time with provenance, V14
has carried `service_time_minutes` since the canonical location model, and V11 has carried each
stop's service window. Departure + driving + service + window is an arrival time with every term
stored and none invented. The thing V27 said did not exist now does.

**Automatic arrival detection does not move,** and ADR-007 is the reason: *positions inform people
and never move a lifecycle*. A detector writing `ARRIVED` onto a stop would make a business fact
depend on a feed that nothing currently supplies - ADR-007 ships no vendor adapter - and would make
the record say a driver arrived because a device was near, which is a different claim and the one
that gets disputed. So the geofence in V43 is a circle on a location and nothing else: no column on
`trip_stop` is written from it, no transition is enabled by it, and `actual_arrival_at` goes on
being entered by a person. `CLAUDE.md`'s deferred list was updated to say all of this rather than
left contradicting the code.

---

## What was built

### The engine

`StopScheduleEngine` is a **pure function** - no repository, no clock, no randomness - so all 14 of
its tests run without Docker. An arrival time that cannot be reproduced from its inputs cannot be
defended when somebody disputes it, and a test that needs a container to check arithmetic is a test
nobody runs while changing the arithmetic.

Three rules carry the whole feature:

**1. An unmeasurable leg ends the chain.** A stop whose incoming leg has no travel time gets no
estimate, and neither does any stop after it. Not a guess, not the previous stop's time, not zero.
A schedule that silently absorbed one missing leg would show eight plausible arrival times of which
five are wrong, with *nothing on the board saying which five*. Asserted in the engine and again end
to end, where the fixture's coordinate-less origin makes the whole run come back unscheduled.

**2. Provenance degrades and never upgrades.** One straight-line leg makes every later stop
`FALLBACK`, because that is what those times are genuinely built on. There is deliberately no
`CACHE` value: V38 records the defect where serving a cached row overwrote `FALLBACK` with `CACHE`
and a straight-line guess became indistinguishable from a measured road. Whether an estimate came
from a cache is a fact about the lookup; what it was measured over is a fact about the number.

**3. A window is never made to fit.** Arriving early is a *wait* that pushes the next leg. Arriving
after closing is reported as computed with `eta_misses_window` raised - never quietly moved to the
next morning, which would turn a route that does not work into one that appears to. Lateness is
judged on the arrival and not the wait-adjusted start, and the window is resolved against the
arrival's own local date so a run crossing midnight compares against the day it actually arrives.

### The rest

Stamped rather than derived on read (V30's reason: a number a person acted on must stay
reproducible). Computed **on request**, because a background loop needs an actor to attribute the
write to and that is open debt D4. Refused outright when a shipment has no planned departure -
falling back to `now()` would give a board whose times changed on every refresh.

`location.geofence_radius_m`, 25m to 20km, set through its own endpoint. Null clears it, which is
why the radius travels in a body rather than a query parameter that could not tell "clear it" from
"not supplied".

---

## Defects found and fixed: 2

**1. The ETA service asked one lookup port for both origins and destinations.** Origins and
destinations resolve through *different* ports (`OriginLookupPort`, `DestinationLookupPort`), and
asking one for both returns nothing for the other half - silently, as an empty map. Every leg then
looks unmeasurable and the entire run loses its ETA while appearing to work. Caught by the
end-to-end test, fixed by merging both maps in one place so the mistake has one place to be made.

**2. A test asserted the wrong thing, and the code was right.** The first end-to-end ETA test
expected arrival times from a run whose origin (`ORIGIN-A`) deliberately has no coordinates. It
failed - correctly - because rule 1 was working. Rather than weaken the assertion, the test was
split in two: one path uses a geocoded origin created for it, and the other now asserts the honest
gap explicitly. Rule 1 gained end-to-end coverage out of the failure.

## A defect looked for and not found

The stop's service window is a `time` column, which is the exact shape JOB 08 proved gets shifted by
`hibernate.jdbc.time_zone: UTC` - and the ETA reads it. Investigated before building on it. The
codebase already knows: `PlanningApiIntegrationTest` documents the normalisation and points at
`OrderApiIntegrationTest`, where an order written through the API is asserted to round-trip
unchanged. The shift is symmetric, so a Java reader gets back what it wrote, and the engine is a
Java reader. **A probe test written during the investigation was deleted rather than kept**: it
exercised raw JDBC and so could not support the claim its javadoc made, and a test that asserts less
than it says is worse than none.

---

## D3 - the formal evaluation JOB 10 was required to produce

`docs/domain/DELIVERED_QUANTITY_EVALUATION.md`. In summary:

* **Not a defect - a missing capability.** Nothing claims to know a delivered quantity. V27 refused
  it by name, V28 stopped at the outcome, and `PARTIAL` documents itself as exactly the claim the
  data supports.
* **It must not be inferred, and this changes nothing about that.** Ordered, allocated (V37) and
  planned are three different facts, and a `PARTIAL` delivery is by definition the case where the
  delivered amount differs from all three. Any of them used as a delivered quantity would be
  *exactly wrong in exactly the case it is needed*, and would look like a measurement.
* **It does not block JOB 11.** Every `RateComponent` in V30/V39 prices the shipment - distance,
  stops, weight, volume, pallets, waiting - not the handover. A carrier is paid for running the
  shipment. Charging per delivered unit, or crediting a customer for a shortfall, would need it;
  neither is in scope, and TMS does not invoice customers.
* **Closing it is a table, not a column**: per order *line*, in the line's own unit, with a
  refused/returned counterpart, an allocation ceiling, and evidence behind `EvidenceStoragePort`.

**Recommendation: leave D3 open, and do not let Settlement create a delivered quantity as a side
effect.**

---

## Test counts

Backend **1617 → 1643** (+26). Frontend **69 → 72** (+3). E2E **34 pass / 7 skipped**, unchanged -
this job added no menu entry; the ETA appears inside the existing trip drawer and the geofence
inside the existing locations screen. No failing test was converted into a skip.

---

## Open debt register

| # | Debt | State | Note |
|---|---|---|---|
| **D1** | `PlanningKpis.totalCost` is null - a proposal is not priced | **OPEN** | Untouched. JOB 11's |
| **D2** | An accepted tender can leave `shipment.carrier != shipment.vehicle.owner` | **CLOSED (V42)** | |
| **D3** | Delivery records an outcome, not a delivered quantity | **OPEN, formally evaluated** | Not a defect, must not be inferred, does not block Settlement. `docs/domain/DELIVERED_QUANTITY_EVALUATION.md` |
| **D4** | No system-actor model, so no unattended scheduler | **DEFERRED_WITH_REASON** | Now also the reason the ETA has no background recomputation |
| **D5** | No work assignment across several shipments | **OPEN** | JOB 09's, unchanged |

---

## Files

**Migration** `V43__stop_eta_and_geofence.sql`

**Backend** new `planning.domain.StopScheduleEngine` / `StopSchedule` / `EtaSource`,
`planning.application.StopEtaService`, `shared.reference.StopServicePort`,
`masterdata.infrastructure.StopServiceAdapter`, `masterdata.application.GeofenceRequest`;
changed `TripStop`, `TripStopView`, `TripViewAssembler`, `TripController`, `Location`,
`LocationView`, `LocationService`, `LocationController`

**Tests** `StopScheduleEngineTest` (new, 14), `PlanningApiIntegrationTest` (+6 incl. the
honest-gap case), `LocationApiIntegrationTest` (+6), `stopEta.test.ts` (new)

**Frontend** `planningApi`, `locationsApi`, `TripDetailDrawer` (per-stop ETA, provenance,
out-of-window chip, recompute action), `pages/masters/GeofenceDrawer.tsx` (new), `LocationsPage`

**Docs** `ADR-011-stop-eta-and-geofence-observation.md` (new),
`docs/domain/STOP_ETA_V1.md` (new), `docs/domain/DELIVERED_QUANTITY_EVALUATION.md` (new),
`CLAUDE.md` (deferred list and ADR index)

---

**NEXT_JOB** - **JOB 11 - Settlement**, which must close **D1** if Planning V2 is to be called
integrated with it. Next migration **V44**.
