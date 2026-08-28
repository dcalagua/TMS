# JOB 15 - Hardening

**RESULT = PASS** · **STOP_CHAIN = false** · **MIGRATION = none**

| | |
|---|---|
| Started | 2026-08-28 06:00 America/Lima |
| Completed | 2026-08-28 06:10 America/Lima |
| HEAD before | `d958fe3` |
| Backend, `./mvnw clean test` | **1674 pass, 0 fail, 0 skipped** |
| Frontend, `vitest run` | **82 pass** |
| E2E, `playwright test` | **34 pass, 7 skipped** |
| Typecheck / lint / build | clean |
| Flyway | V1-V43, contiguous, unchanged |
| Retries | 2 attempted, 2 recovered |

---

## The bar I set for what counts as hardening

Only mistakes that are **silent**: they compile, the screen works, no test fails, and the damage is
found later by somebody outside the team. A rule that merely enforces taste does not belong in a
build - a guard people argue with is a guard people disable.

That ruled out most of what could have been added and left four, in two areas.

## Tenancy - the rule that was written everywhere and checked nowhere

"Every finder is scoped by `companyId` - no exceptions" appears in the javadoc of most repositories
here, in ADR-003 and in `RLS_STRATEGY.md`. **Nothing enforced it.**

Two guards now do, and only for the case where **an attacker supplies the id**:

1. **No service reads a row by bare id.** Spring Data hands every repository `findById`,
   `existsById`, `deleteById` and `getReferenceById` for free; they take a primary key and know
   nothing about tenancy, so a UUID out of a request fetches whatever row it names, in any company.
   One documented exception: the webhook dispatcher re-reading a row it already claimed, on a
   background thread, where the id did not come from a request.
2. **Every declared own-id finder names the tenant.**

### What the guard deliberately does not flag, and why that mattered

My first version flagged **thirty-one** finders and was wrong. Finders keyed by a *foreign* id -
`findByTripIds`, `countByRouteIds`, `findByIdAndFrequencyId` - inherit their scope from whoever
resolved the parent, which was itself a company-scoped read. That is the pattern the codebase uses
everywhere, and a guard with thirty-one exemptions is a list nobody reads.

I narrowed it rather than exempting them, and the narrowing is honest about what backs the
exclusion: not this test, but the composite foreign keys `(id, company_id)` that make a child of
another tenant's parent unrepresentable in the database, and `TenancyConstraintIntegrationTest`,
which proves it.

**A weaker test that passed on the first try would have been worse than the failure.**

### Found on introduction: one unscoped finder, removed

`TenderWaterfallRepository#findByIdForUpdate` - a **locking read of a waterfall by its own id with
no company predicate**, and `grep` across the whole repository found **no callers at all**. Dead
code in the exact shape a cross-tenant read takes, waiting for somebody to reach for it because it
was there. Deleted. A loaded gun with nobody holding it is still a loaded gun.

## Persistence mapping - two silent corruptions

**Every persisted enum is stored by name.** `@Enumerated` defaults to `ORDINAL`, which stores the
enum's *position*. The day somebody inserts a value into the middle of an enum - alphabetically,
tidily, in a refactor about something else - every stored row silently changes meaning. A shipment
that was `CONFIRMED` becomes `CANCELLED`; no migration runs, no test fails, and nothing in the
application can tell. All 46 enum columns here are already `STRING`; this keeps the forty-seventh
from not being.

**Money is never floating point**, flagged by field name only - coordinates and percentages are
legitimately not `BigDecimal`, and a rule that swept them in would be argued with.

## Secrets - the field somebody adds without meaning to

**No view carries a usable secret.** A webhook or client secret is shown to a person exactly once,
through a named show-once view; every ordinary view carries a four-character hint.

The leak this prevents is not a decision anybody makes. It is a field added to a view because it was
on the entity, in a change about something else, reviewed by somebody reading the business logic. It
compiles, the screen works, and the secret is in every JSON response, browser cache and proxy log
for an unknown length of time.

The two show-once views are listed **by name** rather than matched by suffix, so "the class that
shows a secret" is a decision somebody made and can be found - not a convention a new class can join
by accident.

---

## Defects found and fixed: 1

**An unscoped own-id finder with no callers** (`TenderWaterfallRepository#findByIdForUpdate`),
described above. Found by the guard on the run that introduced it, which is the best possible time.

## What I did not do

* **No `@Enumerated`/`CHECK` coverage guard.** Proving that every enum column's `CHECK` lists
  exactly the enum's values is worth having and is a bigger piece of work than the time left
  allowed. `AuditVocabularyMigrationTest` already does it for the vocabulary that drifts most.
  Recorded as **D8**.
* **No `@Transactional` or N+1 guards.** Both are real classes of bug and neither is decidable
  statically with the precision this bar requires. A guard that flags correct code is worse than
  none.

---

## Test counts

Backend **1669 → 1674** (+5, of which 4 are guards over the whole codebase rather than over one
class). Frontend **82**, E2E **34 pass / 7 skipped**, both unchanged - nothing here has a UI.

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
| **D8** | No guard that each enum column's `CHECK` matches its Java enum | **OPEN (new)** | 

---

## Files

**Backend** removed `TenderWaterfallRepository#findByIdForUpdate` (unscoped, dead)

**Tests** `TenantScopedRepositoryTest` (new, 2 guards), `PersistenceMappingTest` (new, 2 guards),
`SecretExposureTest` (new, 1 guard)

**Docs** `docs/security/STATIC_GUARDS.md` (new) - every guard in the build, old and new, with what
each refuses and why

---

**NEXT_JOB** - **JOB 14 - UX**, then **JOB 16 - Certification** and the morning report.
