# JOB 27 — Phase 2 Enterprise Certification

**RESULT = PASS** · **PHASE 2 COMPLETE: JOBS 17–27** · **MIGRATION = none**

Certified at commit `0366e6c`. Every figure below is from a command run **after** the last commit,
not quoted from a job's own report.

---

## 1. The gates, re-run from clean

| Gate | Command | Result |
|---|---|---|
| Backend | `./mvnw clean test` | **1844 pass · 0 fail · 0 error · 0 skipped** · BUILD SUCCESS |
| Frontend unit | `vitest run` | **136 pass** · 22 files |
| E2E | `playwright test` | **38 pass · 7 skipped** · exit 0 |
| Typecheck | `tsc --noEmit` | exit 0 |
| Lint | `oxlint` | exit 0 |
| Build | `vite build` | exit 0 |

**The 7 skipped E2E specs are the authenticated ones.** They need a real environment this machine
does not have, they are skipped by an explicit condition rather than a disabled assertion, and the
count has not moved across either phase.

Backend went **1684 → 1844** across Phase 2 (**+160**). Frontend **97 → 136**. E2E **34 → 38**.
**No failing test was ever converted into a skip**, and no count dropped.

## 2. Structural invariants, verified independently

| Invariant | Check | Result |
|---|---|---|
| Flyway contiguous | versions sorted, gaps counted | **V1–V48, 48 files, no gap** |
| No duplicate versions | `uniq -d` | **0** |
| Applied migrations immutable | commits touching each of V44–V48 | **exactly 1 each** |
| No application DDL in Supabase | `supabase/migrations/` | **0 files** (ADR-002 holds) |
| Working tree clean | `git status --short` | **0 entries** |
| Nothing pushed | `git log origin/dev..HEAD` | **50 commits ahead, none pushed** |

## 3. Constraints held for the whole of Phase 2

* **No push, no deploy, no QAS write, no PRD write, no hosted Supabase change.** 50 commits sit on
  local `dev`. No remote command was run.
* **No destructive Git.** No force push, no history rewrite, no reset of anything shared.
* **Applied migrations are immutable.** V44–V48 each appear in exactly one commit.
* **No secret was read or printed.**
* **No fake user, hardcoded UUID or system principal was invented.** D4's refusal stands and shaped
  JOB 24 (a machine cannot approve an expenditure) and JOB 26.
* **No quantity was inferred, and no unknown became zero.** The rule appears in V43, V45, V46, V47
  and V48, and JOB 25 exists to keep the reads that carry it honest.
* **D10 was never implicitly decided.**

## 4. What Phase 2 delivered

| JOB | Migration | Delivered | Net backend tests |
|---|---|---|---|
| 17 | — | Documentation reconciliation | — |
| 18 | V44 | Enum/CHECK guard; **D8 closed** — the 46th enum column's missing constraint | +? |
| 19 | V45 | Delivered quantity; **D3 closed** — two grains, ceiling enforced, nothing inferred | |
| 20 | V46 | Freight audit & settlement | |
| — | — | **Settlement maker/checker post-check — a real overclaim of mine, corrected** | +5 |
| 21 | V47 | Work assignments & resource sequencing; **D5 closed** | +32 |
| 22 | V48 | Own-fleet costing; **D6 closed** | +46 |
| 23 | — | Operational exceptions + Control Tower V3 | +7 |
| 24 | — | Observability & operations; `docs/operations/` created | +2 |
| 25 | — | Performance harness & baseline | +2 |
| 26 | — | Accessibility foundation; **two real WCAG AA failures fixed** | frontend +7, e2e +2 |
| 27 | — | This certification | — |

**Five of eleven jobs shipped no migration, and that is the honest outcome.** JOB 23 found the
exception model already existed (V27) and the panels already separated (JOB 12); JOB 24 found twelve
metrics already emitted. Adding a table to either to make the job look bigger would have been the
empty scaffolding the brief forbids.

## 5. The debt register

| # | Debt | State |
|---|---|---|
| D1 | Proposal not priced | RESOLVED (Phase 1) |
| D2 | Accepted tender vs vehicle owner | RESOLVED (V42) |
| D3 | Delivered quantity | **RESOLVED** (JOB 19, V45) |
| D4 | No system-actor model | **DEFERRED_WITH_REASON** — and it is load-bearing, not dormant |
| D5 | No work assignment | **RESOLVED** (JOB 21, V47) |
| D6 | No own-fleet cost model | **RESOLVED** (JOB 22, V48) |
| D7 | Control Tower untested | RESOLVED (Phase 1) |
| D8 | No enum/CHECK guard | **RESOLVED** (JOB 18, V44) |
| D9 | No accessibility testing | **OPEN (PARTIAL)** — deliberately, see §7 |
| D10 | No cost allocation | **OPEN** — no business allocation policy has been selected |

**Six resolved, one deferred with reason, two open and honestly labelled.**

## 6. Defects found and fixed in Phase 2: 25

The ones worth reading:

| JOB | Defect | Why it matters |
|---|---|---|
| 18 | My enum guard produced 3 false positives | **The constraints were right and my query was wrong.** Fixed the query |
| 20 | Two approval rows for one expenditure | `transitionTo` returns silently when already in the target state; the approval row was inserted anyway |
| 20 | Audit vocabulary written from memory | Omitted 3 values and invented several. The guard caught it; the list is now generated from the enum |
| — | **Maker/checker was PERMITTED, and I had claimed it was enforced** | **You caught this.** Separable permissions are not separated ones |
| 21 | Shipment origin resolved as the first stop's *destination* | Wrong by one leg on every join, and the figures would have looked plausible |
| 22 | `OwnFleetTripLookupAdapter` read a planning run **by bare id** | A cross-tenant read. Caught by JOB 15's guard, **only on the full `clean test`** |
| 22 | **`costing` was missing from `ModuleBoundaryTest`'s module list** | The rule passed **vacuously** over a whole new module |
| 23 | `TripStop` has no `tripId` JPA attribute | **Compile passed; the context failed; 365 tests died.** JOB 13's defect in a new place |
| 23 | `mvnw compile` returned **exit 0 against an arity mismatch** | 8 arguments to a 7-component record |
| 26 | **Primary button at 2.83:1** | Every contained button in the app, and `theme.ts` documented the rule it broke |
| 26 | Wordmark at 1.72:1 | `opacity` changes no declared colour — only measuring rendered pixels finds it |

**`clean` caught what an incremental run could not, four times.** That is the single most repeated
lesson of both phases.

## 7. Certification statement

**Phase 2 is certified PASS.** Every gate was re-run from a clean tree after the final commit; the
figures in §1 are measurements, not quotations. Flyway is contiguous and immutable, the tree is
clean, nothing was pushed or deployed, and no shared database was touched.

**What is NOT certified**, stated plainly because a certification claiming more than it checked is
worth less than none:

* **Nothing was deployed or verified against a running environment.** All verification is local, on
  Testcontainers and a local build. **The deploy remains unverified**, exactly as at the end of
  Phase 1.
* **The 7 authenticated E2E specs have never executed.** Whatever they would prove is unproven.
* **No load, concurrency or soak testing.** JOB 25 proved the control tower does not issue more
  queries as the day grows — **it did not measure capacity, and nothing here says 10,000
  orders/day.**
* **Accessibility is a foundation, not a claim.** axe automates about a third of WCAG, only two
  screens are swept at page level, and **nothing has been tested by a person.**
* **No rollback procedure has been designed or tested.** Schema is forward-only.
* **The advisory panel's queries are valid but their row selection is not proven end to end**
  (JOB 23 §9).

## 8. Promotion readiness

```
READY_FOR_QAS_CODE_PROMOTION = YES
READY_FOR_PRODUCTION         = NO
```

Different answers on purpose, and the same shape as Phase 1's. The remaining unknowns — the deploy,
the authenticated specs, capacity, accessibility, rollback — are of a kind a local build **cannot**
resolve. Holding the code back would not reduce that risk, only delay learning it.

Detail and the QAS checklist: **`TMS_QAS_PROMOTION_PACKAGE.md`**.

---

**PHASE_2_STATUS = COMPLETE** · **JOBS 17–27** · **STOP_CHAIN = false**
**Certified at** `0366e6c` · **50 commits on local `dev`, none pushed**
