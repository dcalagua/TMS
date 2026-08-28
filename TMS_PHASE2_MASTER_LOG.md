# TMS by EBIM — Phase 2 Master Log

*Enterprise Completion & Pre-QAS Hardening. Resume state lives at the top; the per-job history
follows. Phase 1 (JOBS 01–16) is closed and recorded in `TMS_OVERNIGHT_MASTER_LOG.md`.*

```
LAST_COMPLETED_JOB=  18
CURRENT_JOB=         19
CURRENT_STEP=        starting
LATEST_MIGRATION=    V44  (next free: V45)
LAST_GOOD_HEAD=      pending commit of JOB 18
STOP_CHAIN=          false
NEXT_ACTION=         Delivered Quantity V1 (D3) - the model, then lifecycle derivation
```

## Open debts

| # | Debt | State |
|---|---|---|
| D1 | `PlanningKpis.totalCost` | RESOLVED (Phase 1 JOB 11) |
| D2 | Accepted tender vs vehicle owner | RESOLVED (V42) |
| **D3** | **Delivered quantity** | **OPEN** → JOB 19 |
| D4 | System actor / automatic tender advancement | DEFERRED_WITH_REASON — stays deferred in Phase 2 |
| **D5** | **Work assignment model** | **OPEN** → JOB 21 |
| **D6** | **Own-fleet costing** | **OPEN** → JOB 22 |
| D7 | Control Tower backend tests | RESOLVED (Phase 1) |
| D8 | Java enum ↔ DB CHECK guard | **RESOLVED** (JOB 18) - all 46 enum columns guarded and matching |
| **D9** | **Accessibility testing** | **OPEN** → JOB 26 (partial only; full closure needs QAS) |

## Job status

| JOB | Title | Result | Completed | HEAD after | Migration | Backend |
|---|---|---|---|---|---|---|
| 17 | Documentation & capability reconciliation | **PASS** | 09:44 | `7547a9b` | none | 1684 / 0 |
| 18 | Persisted enum / CHECK guard | **PASS** | 09:58 | *(this commit)* | **V44** | 1688 / 0 |
| 19 | Delivered Quantity V1 | pending | - | - | - | - |
| 20 | Freight Audit & Settlement V1 | pending | - | - | - | - |
| 21 | Work Assignments & Resource Sequencing | pending | - | - | - | - |
| 22 | Own Fleet Costing V1 | pending | - | - | - | - |
| 23 | Operational Exceptions & Control Tower V3 | pending | - | - | - | - |
| 24 | Observability & Operations Completion | pending | - | - | - | - |
| 25 | Performance Harness & Baseline | pending | - | - | - | - |
| 26 | Accessibility Foundation | pending | - | - | - | - |
| 27 | Phase 2 Enterprise Certification | pending | - | - | - | - |

---

### JOB 17 — 2026-08-28 — PASS

`STARTED 09:35 · COMPLETED 09:44 · MIGRATION none · BACKEND 1684/0/0 · RETRIES 0`

**Baseline verified rather than trusted.** Re-ran `./mvnw clean test` before touching anything:
**1684 pass, 0 fail, 0 skipped**, HEAD and Flyway V1–V43 exactly as reported.

**Two of the four named files did not need changing.** `README.md` and `CLAUDE.md` carry no test
counts, no migration numbers and no settlement claim — editing them to make the job look bigger would
have been churn.

**The capability map was stale in three ways:** gate numbers (`1585`/`V1–V41` → `1684`/`V1–V43`), nine
rows still pointing at jobs since completed, and — the one that mattered — **row 16 implying JOB 11
would deliver settlement**. JOB 11 was *titled* Settlement and delivered proposal pricing. Verified by
inspection that no invoice, matching, tolerance, discrepancy, approval or export exists anywhere. The
row now carries the correction in place, labelled, pointing at JOB 20.

**Readiness headline** rewritten from `PRODUCTION READY WITH LIMITATIONS — for QAS code promotion
only` to `READY FOR QAS CERTIFICATION / NOT PRODUCTION CERTIFIED`, because the old phrasing survived
being quoted out of context in a way the meaning did not. **The technical matrix is unchanged.**

`DEFECTS_FOUND=1` (documentation: the settlement implication). Debts unchanged — a debt cannot be
closed by editing a document.

### JOB 18 — 2026-08-28 — PASS

`STARTED 09:45 · COMPLETED 09:58 · MIGRATION V44 · BACKEND 1688/0/0 · RETRIES 0`

**D8 RESOLVED.** A generic guard comparing every persisted `@Enumerated(STRING)` column against the
`CHECK` that governs it. **Asks PostgreSQL for `pg_get_constraintdef`** rather than parsing migration
SQL — the normalised definition of what the schema actually holds, after every drop and re-add, with
no history to reconstruct. Complementary to `AuditVocabularyMigrationTest`, which runs without Docker
and covers one table.

No new dependency: the entity scan uses ArchUnit's `ClassFileImporter`, already in the codebase.

**DEFECTS_FOUND=2, DEFECTS_FIXED=2.**

1. **My first query invented drift.** It unioned literals from *every* constraint touching the
   column, so multi-column rules leaked other enums in — three false positives on
   `trip_cost_component`. I read the migrations first: **the constraints were right, my query was
   wrong.** Fixed with `cardinality(conkey) = 1`. The tempting fix was to relax the comparison, which
   would have produced a guard that passes and proves nothing.
2. **`trip_cost.rate_card_scope` had no CHECK** — 45 of 46 columns were guarded; this snapshot column
   was the exception. Its *source* (`rate_card.scope`) has always been constrained and V39 widened it
   for LANE; the **copy never was**. Closed by **V44**, additive. Recorded honestly as a *missing
   guard, not drift* — nothing disagreed.

Coverage is now **46 of 46**, and the test asserts that rather than a threshold.

The guard carries its own **controlled negative** (`catchesAValueMissingFromTheCheck`): without it, a
bug in the query would make it pass on every schema including a broken one.
