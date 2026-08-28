# JOB 14 - UX

**RESULT = PASS** · **STOP_CHAIN = false** · **MIGRATION = none**

| | |
|---|---|
| Started | 2026-08-28 06:11 America/Lima |
| Completed | 2026-08-28 06:13 America/Lima |
| HEAD before | `0a58a06` |
| Backend, `./mvnw clean test` | **1674 pass, 0 fail, 0 skipped** (unchanged - no backend change) |
| Frontend, `vitest run` | **97 pass** |
| E2E, `playwright test` | **34 pass, 7 skipped** |
| Typecheck / lint / build | clean |
| Flyway | V1-V43, contiguous, unchanged |
| Retries | 1 attempted, 1 recovered |

---

## What I decided this job was, and what I refused to make it

The brief ranks UX below hardening, and with the deadline in sight the temptation was to spend the
slot restyling screens. I did not, for a reason worth stating: **every screen built tonight carries
a sentence that is the whole point of the screen, and not one of them was tested.**

* The blockers panel says *"Ningún envío bloqueado hoy"* rather than rendering empty - because an
  empty panel means either "nothing is stuck" or "nobody looked", and a dispatcher cannot tell those
  apart.
* The availability drawer offers **different reasons per resource**, because the server rejects a
  truck on holiday and a driver in for repair, and a dropdown must not offer an option that can only
  fail.
* The geofence drawer says *"No cambia el estado de ninguna parada"*, because ADR-007's rule is that
  a position informs and moves no lifecycle - and this is the one screen where somebody could
  configure it believing they had switched on automatic arrival detection.
* The blockers panel says *"2 de 40"* when capped, because "there are 20" would send somebody away
  believing they had seen the whole problem.

All four are prose. A typechecker sees none of them; a layout refactor loses any of them silently;
and each is the difference between a screen that informs and one that misleads. So JOB 14 is fifteen
component tests over exactly those sentences.

That follows the pattern the codebase already set: `TripRouteCard.test.tsx` opens by saying *"lo que
se protege no es el maquetado"* - what is protected is that the screen does not present an estimate
as a measurement.

## What was covered

| Screen | Tests | The rule that would otherwise rot silently |
|---|---|---|
| `BlockersPanel` (JOB 12) | 6 | Zero is said out loud; the detail travels whole; a capped list says how many of how many; each row links to the shipment |
| `AvailabilityDrawer` (V42) | 4 | Vehicle reasons and driver reasons are different lists - both are `string[]` to a typechecker |
| `GeofenceDrawer` (V43) | 5 | The ADR-007 sentence is on screen; empty clears rather than meaning "unchanged"; no coordinates disables saving instead of offering a call that can only fail |

## Defects found: 0

One test failure during the run, and it was mine: I asserted a lowercase *"no cambia el estado"*
against a sentence that begins the clause with a capital. **The component was right and the test was
wrong**, so I fixed the test. Worth recording because the opposite - quietly relaxing the assertion
to a substring that matches whatever the code happens to say - is how a test stops protecting
anything.

## What is still not there, honestly

* **No accessibility testing anywhere in the project.** No axe, no a11y assertions, no keyboard
  navigation coverage. The MUI components carry sensible defaults and the drawers use real labels
  (the tests above rely on `getByLabelText`, which only works because they do), but nothing enforces
  it. This is a real gap and it is bigger than a job slot: it wants an axe pass over every screen
  behind authentication, which needs the 7 skipped E2E specs to be running first. Recorded as **D9**.
* **No visual regression testing.** Deliberately not attempted - it needs a baseline nobody has
  reviewed, and a screenshot suite approved by a machine at 06:00 is worth nothing.
* **No restyling.** Nothing tonight introduced an inconsistency with the existing MUI design system
  (ADR-008), and changing spacing at this hour would be unreviewable churn against a deadline.

---

## Test counts

Frontend **82 → 97** (+15). Backend **1674** and E2E **34 pass / 7 skipped**, both unchanged - this
job touched no backend code and added no route.

---

## Open debt register

| # | Debt | State |
|---|---|---|
| **D1** | Proposal not priced | **CLOSED (JOB 11)** |
| **D2** | Accepted tender vs vehicle owner | **CLOSED (V42)** |
| **D3** | Delivered quantity | **OPEN, formally evaluated** |
| **D4** | No system-actor model | **DEFERRED_WITH_REASON** |
| **D5** | No work assignment | **OPEN** |
| **D6** | No own-fleet cost model | **OPEN** |
| **D7** | Control Tower V1 untested | **OPEN** |
| **D8** | No enum/`CHECK` coverage guard | **OPEN** |
| **D9** | No accessibility testing anywhere | **OPEN (new)** |

---

## Files

**Tests only.** `BlockersPanel.test.tsx`, `AvailabilityDrawer.test.tsx`, `GeofenceDrawer.test.tsx` -
all new. No production code changed.

---

**NEXT_JOB** - **JOB 16 - Certification**, then the morning report.
