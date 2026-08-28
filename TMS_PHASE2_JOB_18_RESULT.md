# Phase 2 — JOB 18: Generic Java enum ↔ Postgres CHECK guard

```
RESULT=      PASS
STOP_CHAIN=  false

STARTED_AT=   2026-08-28 09:45 America/Lima
COMPLETED_AT= 2026-08-28 09:58 America/Lima
```

## OBJECTIVE

Close **D8**: stop a new Java enum value reaching runtime and failing because the database `CHECK`
was never migrated.

## BASELINE

Backend 1684 / 0 / 0 · Flyway V1–V43 · next free **V44**.

## DOMAIN_DECISIONS

**1. Ask PostgreSQL, do not parse SQL.**

`AuditVocabularyMigrationTest` reads the migration *files* with a regex, and says why: it must run
where Docker is not available. That is a real constraint with a real cost — it has to cope with a
constraint being dropped and re-added across seven migrations, and only the last definition counts.

This guard takes the other trade. It runs against a migrated database and asks for
`pg_get_constraintdef`, PostgreSQL's own **normalised** rendering of what the schema actually ended
up holding — after every drop, re-add and alter, in whatever order. There is no history to
reconstruct and no dialect to parse. **The two are complementary:** the audit test runs everywhere
and covers one table; this one needs Docker and covers all of them.

**2. Only single-column CHECKs are read.** This is load-bearing, not tidiness — see the defect below.

**3. No new dependency.** The entity scan uses ArchUnit's `ClassFileImporter`, already used by
`NativeQueryQuotingTest`. Adding `org.reflections` for one test would have been a build change to
avoid twenty lines.

## MIGRATIONS

```
V44__trip_cost_scope_snapshot_check.sql   (additive; one CHECK)
```

The guard found that **45 of 46** enum columns were governed by a `CHECK`. The forty-sixth,
`tms.trip_cost.rate_card_scope`, was governed by nothing.

It is a **snapshot**: V30 freezes the winning card's id, code, name and scope onto the cost row so
that deactivating or re-pricing a card cannot restate last week's estimate. The *source* column
(`rate_card.scope`) has always been constrained and was widened by V39 to admit `LANE`. The **copy
never was** — easy to miss precisely because it is a copy, since the value is written by Java from
an enum and has therefore always been valid in practice.

**This is a missing guard, not drift** — nothing disagreed. I added the constraint anyway, for three
reasons stated in the migration: the failure it prevents is a *read* failure that surfaces when
somebody opens a cost breakdown long after whatever wrote it; leaving one column permanently exempt
would turn "45 of 46" into a standing apology; and it is additive and cannot fail on existing data.

**No correct constraint was modified to make the test easier.** V44 adds one; nothing was relaxed.

## BACKEND

No production Java changed. One migration added.

## FRONTEND / SECURITY

Untouched.

## DATABASE

`ck_trip_cost_rate_card_scope` — `NULL OR IN ('CARRIER','ORIGIN','LANE','ROUTE')`. Flyway V1–V44
contiguous.

## TENANT_TESTS

Not applicable — no new entity; the constraint is on an existing company-scoped table.

## CONCURRENCY_TESTS

Not applicable.

## TESTS_FOCUSED

`PersistedEnumConstraintTest` — 4 tests:

| Test | What it protects |
|---|---|
| `everyCheckedEnumColumnMatchesItsJavaEnum` | The guard itself. Reports `table.column`, Java values, DB values, **missing in DB**, **extra in DB** |
| `theScanIsNotEmpty` | **A guard that silently covers zero columns is worse than none** — it reports green |
| `everyEnumColumnIsAccountedFor` | Every column is guarded; prints the coverage line each run |
| `catchesAValueMissingFromTheCheck` | **The controlled negative** — see below |

**The negative test is the one that makes the rest trustworthy.** It builds a throwaway database
with a deliberate three-vs-two mismatch and proves the comparison notices. Without it, a bug in the
query — a regex matching nothing, a filter returning no rows — would make the guard pass on every
schema, including a broken one. It runs against its own database so it cannot touch the real one.

```
BACKEND_CLEAN_PASS=  1688
BACKEND_CLEAN_FAIL=  0
FRONTEND_PASS=       97   (unchanged; no frontend file touched)
FRONTEND_FAIL=       0
E2E_PASS=            34
E2E_FAIL=            0
E2E_SKIPPED=         7
ACCESSIBILITY=       not addressed (JOB 26)
PERFORMANCE=         not addressed (JOB 25)
RETRIES=             0
DEFECTS_FOUND=       2
DEFECTS_FIXED=       2
```

## DEFECTS

**1. My first query invented drift that did not exist.** It unioned the literals of *every*
constraint mentioning the column, so multi-column rules such as
`ck_trip_cost_component_status_consistent` — which names several enums in one expression — leaked
their values in. Three false positives on `trip_cost_component`.

I checked the migrations before touching anything, and **the constraints were right; my query was
wrong.** Fixed with `cardinality(conkey) = 1` — expressed in PostgreSQL's own metadata rather than by
pattern-matching the definition text. Recorded because the tempting fix was to relax the comparison,
which would have produced a guard that passes and proves nothing.

**2. `trip_cost.rate_card_scope` had no CHECK** — the finding above, closed by V44.

## OPEN_DEBTS

```
D1 RESOLVED · D2 RESOLVED · D3 OPEN → JOB 19 · D4 DEFERRED_WITH_REASON
D5 OPEN → JOB 21 · D6 OPEN → JOB 22 · D7 RESOLVED
D8 RESOLVED  ← this job
D9 OPEN → JOB 26
```

**D8 = RESOLVED**, and on the terms the brief set: the guard covers **all 46** real target columns,
not a convenient subset.

## FILES_CHANGED

```
A  backend/tms-api/src/main/resources/db/migration/V44__trip_cost_scope_snapshot_check.sql
A  backend/tms-api/src/test/java/com/ebim/tms/database/PersistedEnumConstraintTest.java
```

```
NEXT_JOB= 19 — Delivered Quantity V1 (closes D3). Next migration V45.
```
