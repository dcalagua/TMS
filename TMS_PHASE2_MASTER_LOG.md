# TMS by EBIM — Phase 2 Master Log

*Enterprise Completion & Pre-QAS Hardening. Resume state lives at the top; the per-job history
follows. Phase 1 (JOBS 01–16) is closed and recorded in `TMS_OVERNIGHT_MASTER_LOG.md`.*

```
LAST_COMPLETED_JOB=  20
CURRENT_JOB=         21
CURRENT_SUBSTEP=     starting
LATEST_MIGRATION=    V46  (next free: V47)
LAST_GOOD_HEAD=      pending commit of JOB 20E/20F
TEST_STATUS=         backend 1756/0/0 · frontend 107 · e2e 35 pass 7 skipped · lint/build clean
KNOWN_FAILURE=       none
STOP_CHAIN=          false
NEXT_ACTION=         JOB 21 - Work Assignments & Resource Sequencing (D5)
```

## Open debts

| # | Debt | State |
|---|---|---|
| D1 | `PlanningKpis.totalCost` | RESOLVED (Phase 1 JOB 11) |
| D2 | Accepted tender vs vehicle owner | RESOLVED (V42) |
| D3 | Delivered quantity | **RESOLVED** (JOB 19, V45) - two grains, ceiling enforced, nothing inferred |
| D4 | System actor / automatic tender advancement | DEFERRED_WITH_REASON — stays deferred in Phase 2 |
| **D5** | **Work assignment model** | **OPEN** → JOB 21 |
| **D6** | **Own-fleet costing** | **OPEN** → JOB 22 |
| **D10** | No cost allocation of an invoice across orders | **OPEN** - new in JOB 20 | V45 supplies delivered quantity so it is now possible; distributing on a rule nobody chose is not. Needs a per-company strategy decision |
| D7 | Control Tower backend tests | RESOLVED (Phase 1) |
| D8 | Java enum ↔ DB CHECK guard | **RESOLVED** (JOB 18) - all 46 enum columns guarded and matching |
| **D9** | **Accessibility testing** | **OPEN** → JOB 26 (partial only; full closure needs QAS) |

## Job status

| JOB | Title | Result | Completed | HEAD after | Migration | Backend |
|---|---|---|---|---|---|---|
| 17 | Documentation & capability reconciliation | **PASS** | 09:44 | `7547a9b` | none | 1684 / 0 |
| 18 | Persisted enum / CHECK guard | **PASS** | 09:58 | `8660062` | **V44** | 1688 / 0 |
| 19 | Delivered Quantity V1 | **PASS** | 10:09 | `2de7e27` | **V45** | 1712 / 0 |
| 20 | Freight Audit & Settlement V1 | **PASS** | 11:25 | *(this commit)* | **V46** | 1756 / 0 |
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

### JOB 19 — 2026-08-28 — PASS

`STARTED 09:47 · COMPLETED 10:09 · MIGRATION V45 · BACKEND 1712/0/0 · FRONTEND 101 · RETRIES 0`

**D3 RESOLVED**, on the terms its own JOB 10 evaluation set.

**Quantities land in two places** because two questions are being asked. The three summable measures
go on `order_delivery`, where the V37 allocation ceiling can be enforced; the per-product truth goes
in `order_delivery_line`, in the line's own unit, because *which product was refused* cannot be
answered in kilos. Neither is derived from the other.

**Absent is not zero, and there is no back-fill.** Back-filling would have asserted "nothing was
delivered" across the installation's entire history - the most damaging thing this migration could
do, and it would have looked like data. Carried through to the form, which sends `null` when the
operator types nothing.

**`delivered + refused <= attempted`**, not `=`: goods carried back are neither delivered nor
refused, and that difference is what a second attempt would carry. Checked per measure, never netted.

**Lifecycle now derives from the sum across attempts** - 60 on Monday plus 40 on Tuesday is a
complete delivery, and the latest row alone cannot say so. Backward compatible by construction:
1700 tests passed before a single new assertion was added.

**DEFECTS_FOUND=3, DEFECTS_FIXED=3.** My constraint fixture broke three *existing* rules (the
constraints were right, the fixture was lazy); a bean cycle needed `@Lazy`; and a type error in a
test file that `tsc -p tsconfig.app.json` does not cover but `npm run build` does - **another
instance of the narrower gate certifying nothing**.

Not modelled, deliberately: DAMAGED/MISSING/RETURNED (each needs semantics nothing can supply yet)
and a cross-attempt total column (a second answer that can drift).

### JOB 20 — 2026-08-28 — PASS

`STARTED 10:37 · COMPLETED 11:25 · MIGRATION V46 · BACKEND 1756/0/0 · FRONTEND 107 · E2E 35/7`

**Freight audit and settlement now exist.** Verified absent at the start of the job - no
`CarrierInvoice`, no invoice table, no matching, no tolerance, no discrepancy, no approval, no
export. All nine capability pillars are now YES with evidence, listed in the result file.

**The boundary is the design:** TMS validates and exports, the ERP pays. No ledger, no payment.
Nothing duplicates `trip_cost` - V30 already holds expected and actual, and settlement reads them
through a port and never writes back.

**Unknown is never zero**, carried from V45 into the money. `UNMATCHABLE` is a third verdict, not a
discrepancy: telling an auditor that a correct invoice is disputed wastes the attention this module
exists to direct.

**No path from a discrepancy to payable skips a person** - four layers, ending in
`decided_by NOT NULL` and `requireAppUserId` refusing machines. An unattended approval cannot be
represented. **Six permissions**, so whoever keys an invoice cannot approve their own.

**DEFECTS_FOUND=6, DEFECTS_FIXED=6.** The one that mattered: **two approval rows for one
expenditure**, because `transitionTo` returns silently when already in the target state while the
approval row was still inserted. Also: I wrote V46's audit list from memory and
`AuditVocabularyMigrationTest` caught it - it is now generated from the enum. Three fixture defects
were existing invariants my seed data ignored; the constraints were right every time.

**Not built, deliberately:** cost allocation across orders. Now possible thanks to V45, but
distributing an invoice on a rule nobody chose is worse than surfacing the figures. Recorded as
**D10**.
