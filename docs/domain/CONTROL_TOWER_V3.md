# Control Tower V3 — operational exceptions and advisories

**JOB 23 · no migration** — every fact it shows already had a home.

---

## 1. Three streams, never one list

| Stream | What it is | Who raised it | Does it stop a truck? |
|---|---|---|---|
| **Blockers** (JOB 12) | A state that makes `dispatch` refuse | The system, computed | **Yes** |
| **Operational exceptions** (V27) | "The truck broke down", "the customer was closed" | **A person**, through the UI | Sometimes, indirectly |
| **Advisories** (JOB 23) | A money question, an arrival estimate outside its window | The system, observed | **No** |

**These are not severities of one thing.** They differ in who raised them and what they demand, and
that is why they are three lists with three counts rather than one alert feed with a colour column.

> Once a panel has cried wolf about a forty-cent rounding difference, the shipment that genuinely
> cannot depart is one row among forty.

JOB 12 kept the blockers panel to hard stops and recorded mixing them as the thing not to do. V3 adds
the advisory stream **beside** it. `ControlTowerAdvisoriesTest.anAdvisoryIsNeverABlocker` is the
assertion that keeps it there.

## 2. Advisories own no state

Both sources are read from the module that owns the fact, through a port, and are **never copied**.

- A freight discrepancy is accepted or rejected in **Settlement**, by somebody with
  `settlement.invoice:match`. The tower shows that one exists and links to it. There is no button
  here to close it, and the row carries no status of its own.
- A stop's ETA is computed by V43. The tower reads `eta_misses_window` and restates nothing.

Copying either would be two records of one fact, drifting apart the first time somebody resolved the
one that does not write back.

## 3. What the two advisories are

### `SETTLEMENT_DISCREPANCY_OPEN`

A carrier's invoice disagrees with what we expected the shipment to cost (V46). **Advisory and not a
blocker on purpose:** the truck ran, the goods arrived, and the money question is settled afterwards
by somebody else entirely.

The difference is **null when the two sides could not be compared** — V46's rule, carried through the
port rather than flattened. Zero would read as *the invoice agrees*, which is the opposite. The UI
omits the figure rather than printing `0.00`.

### `STOP_ETA_MISSES_WINDOW`

A stop's *estimated* arrival falls outside its service window (V43).

**Deliberately not the same fact as `outstandingStops`**, which reports stops that **have** run late.
One is a prediction somebody can still act on; the other is history. A panel that mixed them would
tell a supervisor to leave earlier for a delivery that is already three hours old.

Under ADR-011 a stop with an unmeasurable leg has **no ETA at all**, so every row here rests on a leg
that was actually measured.

## 4. Where the module boundary sits

`SettlementAdvisoryPort` takes **trip ids, not a date**.

Settlement has no concept of an operating day — that is planning's — and a query in the settlement
module joining `Trip` to find one would be a cross-module dependency **hidden inside a string**,
where `ModuleBoundaryTest` cannot see it. The tower already holds the day's shipments and passes them
in.

The port returns a **projection and no entity**, so the tower cannot acquire the ability to resolve
a discrepancy by accident.

## 5. Not built

| | Why |
|---|---|
| A severity column across all three streams | It would make one list of them again, in a different shape |
| Advisory acknowledgement / snoozing | State the tower would then own — the exact thing §2 forbids. If an advisory needs working, it needs working in its own module |
| More advisory types | Two with real sources and real fixes beat five where three are merely true. An advisory nobody can act on is noise wearing a severity |
| Duplicating discrepancy status | Explicitly out. The tower reads and points |
