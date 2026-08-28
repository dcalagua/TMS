# JOB 23 — Operational Exceptions + Control Tower V3

**RESULT = PASS** · **MIGRATION = none** — every fact it shows already had a home

---

## 1. Gates

Every figure from a run after the last change.

| Gate | Result |
|---|---|
| `./mvnw clean test` | **1840 pass · 0 fail · 0 error · 0 skipped** (1833 → 1840, **+7**) |
| Frontend unit | **129 pass** (123 → 129, **+6**) |
| E2E | **36 pass · 7 skipped** |
| Typecheck / lint / `vite build` | clean · exit 0 |
| Flyway | **V1–V48**, unchanged — no migration |

## 2. Why no migration

The exception model already existed. V27 built `tms.trip_exception` with types, an OPEN/RESOLVED
lifecycle, a mandatory human reporter and resolution notes, and `TripController` has carried
report/resolve endpoints since. **JOB 12 already kept blockers separate from exceptions.**

What was missing was not a table. It was that **settlement discrepancies never reached an
operational screen**, and that the tower had no place to put a fact that is worth knowing and stops
nothing. Adding a table to make the job look bigger would have been the empty scaffolding the brief
forbids.

## 3. The separation, preserved and now enforced by a test

| Stream | Raised by | Stops a truck? | Count |
|---|---|---|---|
| **Blockers** (JOB 12) | the system, computed | **Yes** | `summary.blockedShipments` |
| **Operational exceptions** (V27) | **a person**, through the UI | sometimes | `summary.openExceptions` |
| **Advisories** (JOB 23) | the system, observed | **No** | `summary.openAdvisories` |

Three lists, three counts, **never summed**.

`ControlTowerAdvisoriesTest.anAdvisoryIsNeverABlocker` is the assertion that holds the line: a
forty-cent rounding difference produces one advisory, **zero** blockers, and
`blockedShipments == 0`.

> Once a panel has cried wolf about forty cents, the shipment that genuinely cannot depart is one row
> among forty.

The UI reinforces it: separate panel, `info.main` blue rather than `warning.main` amber, its own
label, its own "of how many".

## 4. Advisories own no state

Both sources are read through a port from the module that owns the fact, and **never copied**.

- A discrepancy is accepted or rejected in **Settlement**. The row links there and the panel has
  **no button to resolve it** — `AdvisoriesPanel.test.tsx` asserts there is no `button` in a row.
- `SettlementAdvisoryPort` returns a **projection, not an entity**, so the tower cannot acquire the
  ability to close one by accident.

Copying would be two records of one dispute, drifting apart the first time somebody resolved the one
that does not write back.

## 5. Where the module boundary sits

`SettlementAdvisoryPort` takes **trip ids, not a date**.

My first version had settlement's JPQL join `Trip` to find "today's shipments". That compiles, passes
ArchUnit, and is a **cross-module dependency hidden inside a string** — settlement has no concept of
an operating day. Rewritten so the tower resolves the day's trips (it owns them) and passes them in.

## 6. The two advisories

| Type | Source | Why advisory |
|---|---|---|
| `SETTLEMENT_DISCREPANCY_OPEN` | V46 `freight_discrepancy` | The truck ran and the goods arrived; the money question is settled afterwards by somebody else |
| `STOP_ETA_MISSES_WINDOW` | V43 `eta_misses_window` | A prediction somebody can still act on |

Two, not a catalogue. **An advisory nobody can act on is noise wearing a severity**, and the fastest
way to make the panel worthless is to fill it with things that are merely true.

`STOP_ETA_MISSES_WINDOW` is **deliberately not** `outstandingStops`, which reports stops that *have*
run late. One is still actionable, the other is history — mixing them would tell a supervisor to
leave earlier for a delivery already three hours old.

**A difference the two sides could not be compared on stays null.** Zero would read as *the invoice
agrees*, the opposite of what it means (V46's rule, carried through the port and asserted in both the
backend and frontend tests). The UI omits the figure rather than printing `0.00`.

## 7. Defects found and fixed

| # | Defect | Would have cost |
|---|---|---|
| 1 | **`TripStop` has no `tripId` JPA attribute** — it maps `trip` as a `@ManyToOne`. My query said `join Trip t on t.id = s.tripId` | **`compile` passed. The Spring context failed to start and 365 tests died.** Exactly JOB 13's defect, in a new place |
| 2 | Settlement's JPQL joined planning's `Trip` | A cross-module dependency inside a string, invisible to ArchUnit |
| 3 | `findByCompanyIdAndPlanningDateAndStatusIn` called without its `Pageable` | An unbounded read of a day's shipments — bounded to `WORKLOAD_SCAN_LIMIT`, like the workload scan |
| 4 | `mvnw compile` returned **exit 0 against an arity mismatch** | I passed 8 arguments to a 7-component record and incremental Maven reported success. Caught by running `clean compile` |

**Defects 1 and 4 are the same lesson twice in one job:** an incremental Maven result is not
evidence. The chain has now been saved by `clean` four times.

## 8. Not built

| | Why |
|---|---|
| A severity column across all three streams | It would make one list of them again, in a different shape |
| Advisory acknowledgement or snoozing | State the tower would then own — the exact thing §4 forbids. An advisory that needs working needs working in its own module |
| More advisory types | Two with real sources beat five where three are merely true |
| Any duplication of discrepancy status | Explicitly out of scope, and the port's shape makes it impossible |

## 9. Coverage gap, stated

The advisory composition is unit-tested (7 tests) and both new queries are **validated against a real
Spring context** — an invalid one cannot start the application, which is how defect 1 was caught.

**What is not proven end to end** is that a real `freight_discrepancy` row surfaces on a real control
tower response through a real database. The query is valid; whether it selects exactly the right rows
rests on review rather than on a test. Recorded rather than implied.

## 10. Preserved

```
HARD BLOCKERS != ADVISORIES     three streams, three counts, never summed
Discrepancy state               owned by Settlement, read-only here, never duplicated
D10 COST ALLOCATION             OPEN, untouched
Nothing pushed, nothing deployed, no shared database touched
```
