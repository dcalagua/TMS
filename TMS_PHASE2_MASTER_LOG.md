# TMS by EBIM — Phase 2 Master Log

*Enterprise Completion & Pre-QAS Hardening. Resume state lives at the top; the per-job history
follows. Phase 1 (JOBS 01–16) is closed and recorded in `TMS_OVERNIGHT_MASTER_LOG.md`.*

```
LAST_COMPLETED_JOB=  24
CURRENT_JOB=         25
CURRENT_SUBSTEP=     not started
LATEST_MIGRATION=    V48  (next free: V49)
LAST_GOOD_HEAD=      pending commit of JOB 24
TEST_STATUS=         backend 1842/0/0 · frontend 129 · e2e 36 pass 7 skipped · lint/build clean
KNOWN_FAILURE=       none
STOP_CHAIN=          false
NEXT_ACTION=         JOB 25 - Performance Harness & Baseline
```

## Open debts

| # | Debt | State |
|---|---|---|
| D1 | `PlanningKpis.totalCost` | RESOLVED (Phase 1 JOB 11) |
| D2 | Accepted tender vs vehicle owner | RESOLVED (V42) |
| D3 | Delivered quantity | **RESOLVED** (JOB 19, V45) - two grains, ceiling enforced, nothing inferred |
| D4 | System actor / automatic tender advancement | DEFERRED_WITH_REASON — stays deferred in Phase 2 |
| D5 | Work assignment model | **RESOLVED** (JOB 21, V47) - sequencing, routing feasibility, concurrency, UI |
| **D6** | **Own-fleet costing** | **RESOLVED** (JOB 22, V48) - profile, effective dating, components with real quantity sources, provenance, planning integration, UI |
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
| 20 | Freight Audit & Settlement V1 | **PASS** | 11:25 | `02ab611` | **V46** | 1756 / 0 |
| 21 | Work Assignments & Resource Sequencing | **PASS** | 11:56 | *(this commit)* | **V47** | 1787 / 0 |
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

**DEFECTS_FOUND=7, DEFECTS_FIXED=7.** The one that mattered: **two approval rows for one
expenditure**, because `transitionTo` returns silently when already in the target state while the
approval row was still inserted. Also: I wrote V46's audit list from memory and
`AuditVocabularyMigrationTest` caught it - it is now generated from the enum. Three fixture defects
were existing invariants my seed data ignored; the constraints were right every time.

**Not built, deliberately:** cost allocation across orders. Now possible thanks to V45, but
distributing an invoice on a rule nobody chose is worse than surfacing the figures. Recorded as
**D10**.

### JOB 20 POST-CHECK — 2026-08-28 11:38 — maker/checker

`MIGRATION none · BACKEND 1761/0/0 (+5)`

**The claim was wrong and the post-check was right to ask.** JOB 20's result said *"whoever keys an
invoice cannot approve their own"* on the strength of `settlement.invoice:manage` and
`settlement.invoice:approve` being separate permissions. Separate permissions are **separable, not
separated**: one account can hold both, and `approve()` never compared the actor against
`created_by`. Verified in code - the behaviour was **PERMITTED**.

**No migration needed:** `carrier_invoice.created_by` already stored the maker reliably.
`SettlementService.requireDifferentApprover` is the whole fix - the simplest form that is real, with
no approval matrix, no authorisation limits and no BPM.

**Rejection is deliberately exempt.** Refusing to pay commits nothing and creates no obligation, so a
clerk who spots their own keying error can reject it rather than needing a second person to undo
their mistake. The control exists to stop money leaving, not to stop it staying.

Five tests: creator refused, second approver allowed, second person without the permission still
refused, cross-tenant approver refused, creator may reject. **Three existing tests broke** because
they had one admin creating and approving - the rule working, and they were moved to the checker
rather than the rule being weakened.

`TMS_PHASE2_JOB_20_RESULT.md` carries the correction in place, labelled, rather than quietly amended.

### JOB 21 — 2026-08-28 — PASS

`STARTED 11:39 · COMPLETED 11:56 · MIGRATION V47 · BACKEND 1787/0/0 · FRONTEND 114 · E2E 36/7`

**D5 RESOLVED.** All nine pillars YES with evidence.

**The rule the job is built around:** a work assignment organises shipments and **never becomes an
alternative route past a dispatch guard**. A shipment whose accepted carrier does not own the vehicle
(D2) is reported as `CARRIER_MISMATCH` and repaired nowhere.

**The core invariant is a pure function:** `previous.end + reposition <= next.start`, measured
through the routing port and never invented. **An unmeasurable leg is `ROUTING_UNKNOWN`, not zero** -
the third time this chain has had to make that distinction (V43 ETAs, V45 quantities, now this).

**Nine typed reasons, not one generic.** `MAINTENANCE_BLOCK` is separate from `VEHICLE_UNAVAILABLE`
because a workshop books a truck out and a planner cannot argue with it.

**Every operation revalidates the whole sequence** - one endpoint for add, remove and reorder,
because moving a shipment breaks the leg into it *and* the leg out of it.

**V42's refusal of overnight shifts respected**, not quietly removed: work crossing midnight is
refused with `SHIFT_CONFLICT` rather than granted support the model does not have.

**Concurrency is a database fact:** two partial unique indexes, two real two-thread races, exactly
one winner each.

**DEFECTS_FOUND=1, DEFECTS_FIXED=1.** A shipment's origin was resolved as its first stop's
destination - wrong by exactly one leg on every join in every day, and the resulting figures would
have looked entirely plausible. Caught on review before any test ran.

**D10 untouched and still OPEN**, as instructed: no default allocation rule was chosen.

### JOB 22 — 2026-08-28 — PASS

`STARTED 12:06 · COMPLETED 13:12 · MIGRATION V48 · BACKEND 1833/0/0 · FRONTEND 123 · E2E 36/7`

**D6 RESOLVED.** All twelve pillars YES with evidence.

**A price and a cost are not the same number**, and the design is mostly that sentence enforced. A
carrier presents a PRICE — agreed, binding, with their margin inside. Own fleet produces an INTERNAL
COST ESTIMATE — modelled, binding nobody, no margin, worth exactly what the typed-in rates are worth.
`TransportCostNature` carries the difference from the calculator to the screen so nothing downstream
can lose it, and both are labelled wherever both appear.

**Unknown cost never becomes zero cost**, in four places with a test each. The load-bearing
assertion: had the calculator summed what it had, an un-measurable trip would have scored **100.00**
against a fully measured **316.60** and won every comparison **by being unmeasurable**.

**Null rate ≠ zero rate.** Null means the profile does not model the component — not charged, nothing
missing. Zero means it charges nothing for it — charged, and still demanding its quantity. Stated at
the column, in the domain, at the API boundary and in the drawer's banner.

**The reposition is charged to the trip it repositions *to*.** Across a day: no leg counted twice,
none dropped. The figure is V47's **frozen** `reposition_minutes`, never re-derived — a day called
feasible on one number and costed on another would be two answers about one empty leg.

**Precedence is vehicle > vehicle type > no cost.** No company-wide fallback: a fuel rate averaged
over a van and an articulated truck is wrong for both while looking authoritative. Overlap is a
database fact (`EXCLUDE USING gist` over a `daterange`), so the resolver never breaks a tie with a
rule nobody chose.

**`TOLL` is flat per trip and deliberately not per kilometre** — tolls depend on which roads a route
uses, not how long it is.

**DEFECTS_FOUND=7, DEFECTS_FIXED=7.** The one worth reading: **`costing` was missing from
`ModuleBoundaryTest`'s module list**, so the boundary rule passed *vacuously* over an entire new
module. Adding it immediately caught a real violation and forced planning's dependency on costing
through a port.

**The first full `clean test` failed with 4 failures, and all four were guards working** — one real
cross-tenant read (`PlanningRunRepository.findById` by bare id, caught by JOB 15's guard), the new
table undeclared in the schema inventory, and the permission catalogue's exact counts (58 → 60,
grants 163 → 168). None of it appears in a focused run. **The third time this chain has been saved
by running `clean test` rather than trusting a targeted one.**

**Known limitation, recorded not hidden:** reposition *distance* is not charged, only its time. V47
froze minutes and not kilometres. This understates a multi-trip day — in the direction that does not
make own fleet look better than it is.

**D10 untouched and still OPEN**, as instructed: own-fleet costing computes what the transport costs
and does not decide how that cost is shared.

### JOB 23 — 2026-08-28 — PASS

`STARTED 13:20 · COMPLETED 14:12 · MIGRATION none · BACKEND 1840/0/0 · FRONTEND 129 · E2E 36/7`

**No migration, and that is the honest outcome.** V27 already built the exception model - table,
lifecycle, human reporter, resolve endpoints - and JOB 12 already kept blockers apart from
exceptions. What was missing was that **settlement discrepancies never reached an operational
screen**, and that the tower had nowhere to put a fact worth knowing that stops nothing.

**Three streams, three counts, never summed.** Blockers (computed, stop a truck), operational
exceptions (a person reported them), advisories (observed, stop nothing).
`anAdvisoryIsNeverABlocker` is the assertion that holds it: forty cents of rounding produces one
advisory, zero blockers, and `blockedShipments == 0`.

**Advisories own no state.** The port returns a projection and no entity, so the tower cannot
acquire the ability to resolve a discrepancy by accident. The panel has no button to close one, and
the frontend test asserts there is no `button` in a row.

**The port takes trip ids, not a date.** My first version had settlement's JPQL join `Trip` to work
out "today" - a cross-module dependency hidden inside a string, where ArchUnit cannot see it.

**DEFECTS_FOUND=4, DEFECTS_FIXED=4.** Two are the same lesson twice: `TripStop` has no `tripId` JPA
attribute and my query used one - **compile passed, the Spring context failed, 365 tests died**,
exactly JOB 13's defect in a new place. And `mvnw compile` returned **exit 0 against an arity
mismatch** where I passed 8 arguments to a 7-component record. The chain has now been saved by
`clean` four times.

**Coverage gap recorded:** the composition is unit-tested and both queries are validated by a real
Spring context starting, but no test proves a real discrepancy row reaching a real control tower
response. The query is valid; that it selects exactly the right rows rests on review.

### JOB 24 — 2026-08-28 — PASS

`STARTED 14:20 · COMPLETED 14:52 · MIGRATION none · BACKEND 1842/0/0`

**Observability was not absent - it was undocumented.** Actuator was already configured with real
security discipline, a correlation id filter existed, and twelve business metrics were already
emitted. What was missing was `docs/operations/` entirely, any signal from Phase 2's new
capabilities, and anything keeping a metrics document honest.

**Two metrics, both chosen for 02:00.** `tms.settlement.decisions` tagged approved/rejected, because
a rising rejection rate means a tariff is out of date and is invisible in a single "invoices
processed" figure. `tms.costing.own_fleet.quotes` with five outcomes, because "nobody configured
this truck" and "we could not measure the route" are different jobs for different people.

**Neither carries an amount.** `/actuator/metrics` has no authorisation, so what a company pays its
carriers stays in the database where RLS covers it.

**`MetricCatalogueTest` failed on its first run against my own document**, which is the case for it
made by it: I had documented a counter as a timer, named neither real timer, and abbreviated two
rows so that two live metrics were undocumented. **A hand-written catalogue was wrong within an hour
of being written.**

**Three documents, and the honest one is `DEPLOYMENT.md`**, which opens with a warning that no
deployment has been verified and records **forward-only schema with no tested rollback as the
largest unaddressed operational risk** rather than inventing a procedure.

**No alert thresholds and no custom health indicator, both on purpose.** There is nowhere to send an
alert, and a stale partner feed must not take TMS out of a load balancer.
