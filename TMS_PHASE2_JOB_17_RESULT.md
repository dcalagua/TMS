# Phase 2 — JOB 17: Documentation & Capability Reconciliation

```
RESULT=      PASS
STOP_CHAIN=  false

STARTED_AT=   2026-08-28 09:35 America/Lima
COMPLETED_AT= 2026-08-28 09:44 America/Lima
```

## OBJECTIVE

Remove documentation staleness before building on it. Phase 2 will make claims that reference these
documents; a stale baseline would propagate into every job after it.

## BASELINE

**Verified, not trusted.** The prompt supplied a baseline; I re-ran it rather than quoting it.

| | Reported | Verified at `729f155` |
|---|---|---|
| Backend | 1684 / 0 / 0 | **1684 pass · 0 fail · 0 skipped** · BUILD SUCCESS |
| Flyway | V1–V43 | **V1–V43**, next free **V44** |
| HEAD | `729f155…` | **matches** |
| Tree | clean | **clean** |

## DOMAIN_DECISIONS

None. No domain decision was taken or revisited in this job.

## What was actually stale, and what was not

I checked the four named files rather than assuming all four needed work.

| File | Stale? | Action |
|---|---|---|
| `docs/architecture/TMS_CURRENT_CAPABILITY_MAP.md` | **Yes, badly** | Reconciled — see below |
| `TMS_ENTERPRISE_READINESS.md` | Wording only | Headline rewritten; matrix untouched |
| `README.md` | **No** | Carries no test counts, no migration numbers and no settlement claim. Left alone |
| `CLAUDE.md` | **No** | No settlement mention; the deferred list was already corrected by ADR-011 in JOB 10. Left alone |

**Two of the four files did not need changing.** Editing them to make the job look bigger would have
been churn against documents that were already correct.

### The capability map — three kinds of staleness

**1. Gate numbers.** `1585` → `1684`, frontend `60` → `97`, `V1–V41` → `V1–V43` (next free V44). The
progression is kept rather than replaced, so the file still reads as a history.

**2. Six rows pointed at jobs that have since completed.** Rows 8, 10, 11, 14, 18, 19, 20, 23 and 25
named JOBs 05/06/09/10/11/12/13/15 as future work. Each now states what was delivered *and* what
genuinely remains — for example row 14 now records that stop ETA and geofences shipped (V43,
ADR-011) while **route deviation and automatic arrival detection remain absent by decision**.

**3. The one that mattered — row 16, settlement.** It read:

> Invoice → match → approve → export → **JOB 11**

**JOB 11 was titled *Settlement* and delivered proposal pricing**, closing debt D1. It never built
freight audit. `TMS_OVERNIGHT_11_RESULT.md` is accurate and does not overclaim, but a reader
comparing it to this row could reasonably conclude carrier invoicing shipped.

Verified by inspection at `729f155`: **no `CarrierInvoice`, no `carrier_invoice` table, no matching,
no tolerance, no discrepancy, no approval, no export** anywhere in `src/main/java` or `db/migration`.

Row 16 now carries the correction **in the row itself**, labelled as a correction, and points at
Phase 2 JOB 20. The mistake is recorded rather than overwritten.

### The readiness headline

Changed from:

```
PRODUCTION READY WITH LIMITATIONS — for QAS code promotion only
```

to:

```
READY FOR QAS CERTIFICATION
NOT PRODUCTION CERTIFIED
```

The old phrasing used a *per-capability* classification as a *whole-product* verdict, and the first
three words survive being quoted out of context in a way the meaning does not. **The technical matrix
is byte-for-byte unchanged** — only the headline sentence and the matching `OVERALL_` line moved, and
the change is noted in the document itself.

## MIGRATIONS

```
none — and none were needed
```

## BACKEND / FRONTEND / DATABASE / SECURITY

Untouched. `git status --porcelain` filtered for `*.java|*.sql|*.ts|*.tsx|pom.xml|package.json`
returns **0 files**.

## TENANT_TESTS / CONCURRENCY_TESTS

Not applicable — no persisted entity was added or changed.

## TESTS_FOCUSED

None. Nothing testable changed.

```
BACKEND_CLEAN_PASS=  1684
BACKEND_CLEAN_FAIL=  0
FRONTEND_PASS=       97
FRONTEND_FAIL=       0
E2E_PASS=            34
E2E_FAIL=            0
E2E_SKIPPED=         7
```

Backend figure is from the verification run at the start of this job. Frontend and E2E figures are
carried from the JOB 16 re-certification and were not re-run, because **no frontend file changed** —
re-running them would reproduce a known number while implying a verification that did not happen.

```
ACCESSIBILITY= not addressed (JOB 26)
PERFORMANCE=   not addressed (JOB 25)
RETRIES=       0
DEFECTS_FOUND= 1 (documentation)
DEFECTS_FIXED= 1
```

**The defect:** capability-map row 16 implying settlement was delivered by JOB 11. Documentation, not
code — but it is exactly the kind of inconsistency that would have let JOB 20 be mis-scoped, which is
why JOB 17 runs first.

## OPEN_DEBTS

Unchanged by this job — a debt cannot be closed by editing a document.

```
D1 RESOLVED  · D2 RESOLVED · D3 OPEN · D4 DEFERRED_WITH_REASON · D5 OPEN
D6 OPEN      · D7 RESOLVED · D8 OPEN · D9 OPEN
```

## FILES_CHANGED

```
M  docs/architecture/TMS_CURRENT_CAPABILITY_MAP.md
M  TMS_ENTERPRISE_READINESS.md
A  docs/superpowers/plans/2026-08-28-tms-phase2-enterprise-completion.md
A  TMS_PHASE2_JOB_17_RESULT.md
A  TMS_PHASE2_MASTER_LOG.md
```

`README.md` and `CLAUDE.md` deliberately unchanged — verified accurate.

```
NEXT_JOB= 18 — Generic Java enum ↔ Postgres CHECK guard (closes D8)
```
