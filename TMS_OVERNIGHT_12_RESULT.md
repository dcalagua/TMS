# JOB 12 - Control Tower V2

**RESULT = PASS** · **STOP_CHAIN = false** · **MIGRATION = none**

| | |
|---|---|
| Started | 2026-08-28 05:38 America/Lima |
| Completed | 2026-08-28 05:48 America/Lima |
| HEAD before | `d8b5ccc` |
| Backend, `./mvnw clean test` | **1661 pass, 0 fail, 0 skipped** |
| Frontend, `vitest run` | **79 pass** |
| E2E, `playwright test` | **34 pass, 7 skipped** |
| Typecheck / lint / build | clean |
| Flyway | V1-V43, contiguous, unchanged |
| Retries | 3 attempted, 3 recovered |

---

## The thesis, and why V2 is one panel rather than ten

Everything the control tower reported was **retrospective**: a stop past its window, a departure
already late, an exception somebody raised. All true, all about what has already happened. That is
most of what a control tower is for and it is not all of it.

JOBs 09 to 11 created several states that make `TripExecutionService.dispatch` **refuse** - and
nothing surfaced any of them until a dispatcher walked to the gate and found out. V2 adds one panel
for exactly that.

| Reason | Meaning | Who clears it |
|---|---|---|
| `AWAITING_CARRIER_VEHICLE` | Accepted by a carrier that does not own the vehicle on it (V42, D2) | A planner |
| `VEHICLE_UNAVAILABLE` | The vehicle is out of service at the planned departure (V42) | The workshop |
| `DRIVER_UNAVAILABLE` | The driver cannot work at the planned departure (V42) | A supervisor |

**Nothing here is a new rule.** Each reason is a refusal that already exists in the service, the
aggregate and the database - which is what makes the panel trustworthy rather than one more advisory
badge people learn to ignore. A shipment on this list genuinely cannot depart.

I deliberately did **not** pad this with more panels. Adding an appointments tile and an ETA tile
would have made the job look bigger and made the screen worse: a panel that mixes things that block
a departure with things that merely worry somebody stops being actionable, and the value of this one
is that every row on it is a hard stop.

## Two decisions that keep it honest

**Asked at each shipment's own planned departure, not at `now()`.** A truck free this minute and in
the workshop at 14:00 still cannot run a 14:00 shipment - that is the one a dispatcher needs told
this morning - while a vehicle blocked next Tuesday is not a blocker today. Judging against the
clock would make the panel's contents depend on when somebody happened to open the screen.

**Zero is shown as zero.** "Nothing is stuck" is a fact a dispatcher wants stated, not inferred from
an empty panel that might equally mean nobody looked.

---

## Finding: the control tower had no backend tests at all

`grep -rln "ControlTower" src/test` returned nothing. The screen's summary counts, its three panels,
its capping behaviour and its `ordersUnplanned` permission rule were all uncovered - including the
subtle one, where `null` rather than `0` is returned to a caller without `orders.order:read`.

**This is reported rather than quietly fixed in full.** Backfilling coverage for all of V1 is its
own job and would have made this one a test-writing exercise; what I did instead was cover the new
panel properly - 7 tests, including the two cases most likely to rot (a block at another time is not
a blocker now, and a shipment with no planned departure is not asked about at all). The gap for V1 is
recorded as **D7**.

## No defects found

Nothing here changed existing behaviour: the blocker panel is additive, and the two new repository
queries are read-only. The `ControlTowerSummaryView` and `ControlTowerView` records gained a
component each, which the compiler propagated to their one caller.

---

## Test counts

Backend **1654 → 1661** (+7). Frontend **76 → 79** (+3). E2E **34 pass / 7 skipped**, unchanged - no
menu entry was added; the panel sits on the existing control tower screen.

---

## Open debt register

| # | Debt | State | Note |
|---|---|---|---|
| **D1** | Proposal not priced | **CLOSED (JOB 11)** | |
| **D2** | Accepted tender vs vehicle owner | **CLOSED (V42)** | Now also surfaced before the gate |
| **D3** | Delivery records an outcome, not a delivered quantity | **OPEN, formally evaluated** | Unchanged |
| **D4** | No system-actor model | **DEFERRED_WITH_REASON** | Unchanged |
| **D5** | No work assignment across several shipments | **OPEN** | Unchanged |
| **D6** | No internal cost model for own fleet | **OPEN** | Unchanged |
| **D7** | Control Tower V1 has no backend tests | **OPEN (new)** | Summary counts, the three V1 panels, capping and the `ordersUnplanned` permission rule are uncovered. The V2 panel is covered |

---

## Files

**Backend** new `planning.application.ControlTowerBlockerView`; changed `ControlTowerService`
(the `blockers` panel), `ControlTowerView`, `ControlTowerSummaryView`, `TripRepository` (two
read-only queries)

**Tests** `ControlTowerBlockersTest` (new, 7)

**Frontend** `controlTowerApi` (`ControlTowerBlockerView`, `blockedShipments`),
`ControlTowerPanels` (`BlockersPanel`), `ControlTowerPage`, `lib/enums`,
`controlTowerBlockers.test.ts` (new)

**Docs** `docs/domain/CONTROL_TOWER_V1.md` (a V2 section)

---

**NEXT_JOB** - **JOB 13 - Integration Ops**, or **JOB 15 - Hardening** if time is short. Per the
brief's priority order, hardening outranks JOB 14 (UX).
