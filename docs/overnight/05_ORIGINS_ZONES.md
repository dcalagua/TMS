# Step 05 - Master Data: Origins and Zones

Date: 2026-08-19
Attempt: 2 (repair pass over an uncommitted attempt-1 implementation)
Result: **PASS**

## 0. State inherited from attempt 1

Attempt 1 left no overnight report and no commit, but the working tree already carried a
substantial, coherent implementation: migration `V6__masterdata_origins_zones.sql`, the full
`masterdata` module (`domain`/`application`/`api`/`infrastructure`), `ConflictException` +
its `ApiExceptionHandler` wiring, `MasterDataConstraintIntegrationTest`,
`OriginZoneApiIntegrationTest`, the `SchemaExposureIntegrationTest`/`LayeringTest` updates
for the new tables and package, and a full frontend slice (`originsApi.ts`, `zonesApi.ts`,
`OriginsPage`/`OriginFormModal`/`ZonesPage`/`ZoneFormModal`, `router.tsx` wiring). This
attempt did not regenerate any of it (rule 6): every file was read end to end first, then
the whole stack was actually run - backend Maven tests against real Testcontainers
PostgreSQL, frontend vitest/typecheck/lint/build - to find out what, if anything, was
actually broken rather than assuming attempt 1's code was correct because it looked
complete.

That check found the backend, the database constraints and the migration were correct as
written. The frontend had two real defects and one real gap, fixed below.

## 1. What attempt 1 got right (verified, not re-built)

- **Migration V6**: `tms.zone` and `tms.origin`, both `company_id NOT NULL` with an
  index leading with it, normalized-code `CHECK`s, `uq_*_company_code` unique per company,
  actor columns with FKs to `tms.app_user`, the `set_updated_at` trigger, RLS enabled - every
  rule `DATA_MODEL.md` section 8 (formerly 7) sets for a migration after V5. `origin.location`
  is a `GENERATED ALWAYS` PostGIS `geography(Point,4326)` column derived from
  `latitude`/`longitude`, GiST-indexed, resolved against whichever schema actually carries the
  `postgis` extension rather than hardcoding `public`. Coordinates are validated three ways at
  the database layer: both-or-neither (`ck_origin_coordinates_pair`), in-range
  (`ck_origin_latitude_range`/`ck_origin_longitude_range`), and `origin_type` restricted to a
  fixed vocabulary.
- **Service layer**: `OriginService`/`ZoneService` normalize codes (trim + upper), pre-check
  for a duplicate before writing, and translate a raced `DataIntegrityViolationException` from
  the database's own unique constraint into the same `ConflictException` a pre-check would
  throw - so a concurrent double-create answers identically to a synchronous one. Every finder
  goes through `CompanyScope`, so a query can never reach across tenants even by mistake -
  there is no method that accepts a bare `companyId` from a caller.
- **Controllers**: `masterdata.origin:read/manage` and `masterdata.zone:read/manage`
  (`@PreAuthorize`), both already present in the V3 permission catalogue and the `Permission`
  enum - nothing new needed there. No delete endpoint; only activate/deactivate, per the brief.
- **Tests already covered**: same code allowed in different companies but not within one,
  cross-company access blocked (404, not 403 - `ADR`-consistent, see `API_CONVENTIONS.md`
  section 4.4), invalid/incomplete coordinates rejected with field-level errors, activate/
  deactivate reflected in the `active` filter, read-only role blocked from managing, and
  server-side pagination reporting the size actually applied.

## 2. Defects found and fixed this attempt

### 2.1 Frontend: `OriginsPage.test.tsx` had three failing assertions

Running `npm test` (not just reading the file) surfaced three genuine bugs, all in
element-lookup ambiguity or a wrong expected string - not in the screen code itself:

1. **Wrong error-state text.** The test built `new ApiError(500, null, 'corr-1', 'boom')` (a
   `null` Problem Details body) and then asserted the *`internal-error`*-coded message
   ("Something went wrong on our side...") appears. With `problem` `null`, `error.code` is
   `null`, so `describeApiError` correctly falls through to the generic
   `FALLBACK_MESSAGE` ("Something went wrong. Please try again.") - the screen was right, the
   test's fixture didn't match its own assertion. Fixed by giving the mocked rejection a
   `{ code: 'internal-error' }` problem body, which is also the more meaningful test: it now
   exercises the documented `internal-error` code path instead of the fallback.
2. **Ambiguous `getByText('Hub')`.** The origin's `type` column renders "Hub" and the filter
   bar's type `<select>` also has an option labelled "Hub" - both visible at once, so a plain
   text query is inherently ambiguous on this screen. Fixed with
   `screen.getByRole('cell', { name: 'Hub' })`, which only matches the table cell.
3. **Ambiguous `getByLabelText(/^code/i)`.** Same shape of bug: the filter bar has a "Code"
   field and the create modal has its own "Code" field, both mounted simultaneously once the
   modal opens. Fixed by scoping the query with
   `within(screen.getByRole('dialog')).getByLabelText(...)` for every field inside the modal in
   that test, rather than querying the whole document.

None of these were pre-existing, already-passing tests that regressed - they were failing
before this attempt touched anything, confirmed by running the suite before making any edit.

### 2.2 Frontend: Zones had no test coverage at all

`OriginsPage`/`OriginFormModal` had full test files (list states, form validation, create/
edit happy path, deactivate confirmation, permission gating - matching the step brief
exactly); `ZonesPage`/`ZoneFormModal` had none, despite being an equally real vertical slice
with its own permission (`masterdata.zone:manage`) and its own screen. Added
`ZonesPage.test.tsx` and `ZoneFormModal.test.tsx`, mirroring the Origins coverage trimmed to
Zone's smaller shape (no coordinates/type/timezone fields). These were written correctly the
first time by applying the two fixes from 2.1 up front, rather than discovering the same bugs
twice.

## 3. Verification

Backend (`backend/tms-api`, `./mvnw -q -o test`, Docker Desktop running so every
Testcontainers-backed test executed rather than being skipped):

```
18 test classes, 129 tests, 0 failures, 0 errors
```

including `MasterDataConstraintIntegrationTest` (6 tests: cross-company code reuse vs.
same-company conflict, FK to a real company, code normalization, coordinate pair/range
checks with the generated `location` column verified via `ST_X`/`ST_Y`, `origin_type`
restriction, defaults/actor columns) and `OriginZoneApiIntegrationTest` (10 tests across the
`Origins`/`Zones` `@Nested` classes - the per-class `.txt` surefire summary shows
`Tests run: 0` for this class the same way it does for `ApiSecurityTest`/
`DocumentationExposureTest` elsewhere in the suite, because the writer only counts direct
methods, not `@Nested` ones; the XML report is authoritative and shows `tests="10"
failures="0" errors="0"`, matching the note already on file in
`docs/overnight/03_BACKEND_SECURITY.md`). `LayeringTest` and `SchemaExposureIntegrationTest`
also pass with their Step-05 updates (the `use_cases_must_not_depend_on_the_web_layer` rule
now checks for `@RestController` rather than the `..api..` package, because `shared.api`
holds cross-cutting contract types like `PageQuery`/`ConflictException` that a use case is
expected to depend on; `origin`/`zone` are in the RLS-enabled, not-in-`public` table lists).

Frontend (`frontend/tms-web`):

```
npm run typecheck    tsc -b                clean, no errors
npm run lint          oxlint                0 errors, 2 pre-existing warnings (unrelated files, documented in 04_FRONTEND_FOUNDATION.md)
npm test              vitest run            13 files, 63 tests passed
npm run build         tsc -b && vite build  built in 380ms, dist/ produced
```

The 63 tests include the new `ZonesPage.test.tsx`/`ZoneFormModal.test.tsx` and the corrected
`OriginsPage.test.tsx`; `OriginFormModal.test.tsx` (coordinate pair validation, IANA time
zone validation via `Intl.DateTimeFormat`, field-error mapping) was already correct and
needed no change.

### 3.1 Test coverage against the brief

| Required case | Backend test | Frontend test |
|---|---|---|
| same code allowed across companies, not within one | `OriginZoneApiIntegrationTest`, `MasterDataConstraintIntegrationTest` | n/a (server-enforced; UI surfaces the conflict message) |
| cross-company access blocked | `OriginZoneApiIntegrationTest` (404, not 403) | n/a |
| invalid coordinates blocked | `OriginZoneApiIntegrationTest`, `MasterDataConstraintIntegrationTest` | `OriginFormModal.test.tsx` (range + both-or-neither) |
| inactive behaviour | `OriginZoneApiIntegrationTest` (deactivate/activate + `active` filter) | `OriginsPage.test.tsx`/`ZonesPage.test.tsx` (deactivate confirmation) |
| pagination/filter | `OriginZoneApiIntegrationTest` | `OriginsPage.test.tsx`/`ZonesPage.test.tsx` (code filter, pagination display) |
| security permissions | `OriginZoneApiIntegrationTest` (viewer role forbidden on manage) | `OriginsPage.test.tsx`/`ZonesPage.test.tsx` (actions hidden without `*:manage`) |
| list states (loading/error/empty) | - | both page test files |
| form validation | - | both form-modal test files |
| create/edit happy path (mocked API) | - | both page + form-modal test files |
| deactivate confirmation | - | both page test files |
| permission gating | - | both page test files |

## 4. Constraint compliance

| Constraint | How |
|---|---|
| never push, never deploy | nothing was pushed; no deployment exists |
| never mutate a remote/shared database | all tests ran against a local, disposable Testcontainers PostgreSQL; no Supabase project or shared database was touched |
| no real secrets | no `.env` file was read or created; nothing in this step touches credentials |
| no destructive Git operations | none run; nothing was staged or committed per the overnight-pack instruction |
| Flyway is the only migration owner | V6 is the only schema change; no `supabase/migrations` entry was added |
| Java owns business logic and authorization | company scoping, code normalization, conflict detection and `@PreAuthorize` all live in the backend; the frontend's `hasPermission` checks are UX-only, matching the pattern documented in `04_FRONTEND_FOUNDATION.md` |
| React talks to Spring Boot for business data | `originsApi.ts`/`zonesApi.ts` call `apiRequest` exclusively; no direct Supabase table access was added |
| TMS independent from EWM | `origin.external_reference` is a free-text optional column, never a foreign key, exactly as the brief requires |
| vertical slice checked end to end | `OriginsPage`/`ZonesPage` -> `originsApi.ts`/`zonesApi.ts` -> `OriginController`/`ZoneController` -> `OriginService`/`ZoneService` -> `OriginRepository`/`ZoneRepository` -> `tms.origin`/`tms.zone` -> RLS + `@PreAuthorize` -> the tests in section 3, read and verified layer by layer, not assumed from attempt 1's code alone |
| do not claim untested passes | every number in section 3 comes from a run executed this session; the surefire `Tests run: 0` quirk is explained rather than left to look like a skipped/empty test class |

## 5. Files

Added (all by attempt 1, verified this attempt; only the two Zone test files are new to this
attempt):

```
backend/tms-api/src/main/resources/db/migration/V6__masterdata_origins_zones.sql
backend/tms-api/src/main/java/com/ebim/tms/masterdata/domain/{Origin,OriginType,Zone}.java
backend/tms-api/src/main/java/com/ebim/tms/masterdata/application/{OriginFilter,OriginRequest,
  OriginService,OriginView,ZoneFilter,ZoneRequest,ZoneService,ZoneView}.java
backend/tms-api/src/main/java/com/ebim/tms/masterdata/infrastructure/{OriginRepository,
  OriginSpecifications,ZoneRepository,ZoneSpecifications}.java
backend/tms-api/src/main/java/com/ebim/tms/masterdata/api/{OriginController,ZoneController}.java
backend/tms-api/src/main/java/com/ebim/tms/masterdata/package-info.java
backend/tms-api/src/main/java/com/ebim/tms/shared/api/ConflictException.java
backend/tms-api/src/test/java/com/ebim/tms/database/MasterDataConstraintIntegrationTest.java
backend/tms-api/src/test/java/com/ebim/tms/masterdata/api/OriginZoneApiIntegrationTest.java
frontend/tms-web/src/shared/api/{originsApi,zonesApi}.ts
frontend/tms-web/src/pages/masters/{OriginsPage,OriginFormModal,ZonesPage,ZoneFormModal}.tsx
frontend/tms-web/src/pages/masters/{OriginsPage,OriginFormModal}.test.tsx
docs/overnight/05_ORIGINS_ZONES.md
```

Added this attempt:

```
frontend/tms-web/src/pages/masters/ZonesPage.test.tsx
frontend/tms-web/src/pages/masters/ZoneFormModal.test.tsx
```

Modified (by attempt 1, verified; no attempt-1 production code needed further changes):

```
backend/tms-api/src/main/java/com/ebim/tms/shared/api/ApiExceptionHandler.java   ConflictException -> 409 mapping
backend/tms-api/src/test/java/com/ebim/tms/architecture/LayeringTest.java         @RestController-based web-layer rule
backend/tms-api/src/test/java/com/ebim/tms/database/SchemaExposureIntegrationTest.java  origin/zone in the RLS and not-in-public checks
frontend/tms-web/src/app/router.tsx    masters/origins and masters/zones routed to real pages
```

Modified this attempt:

```
frontend/tms-web/src/pages/masters/OriginsPage.test.tsx   fixed the three failing assertions (section 2.1)
docs/database/DATA_MODEL.md   documented the V6 origin/zone model (new section 7), renumbered the migration-rules section to 8
```

## 6. Handoff to Step 06 (masters: destinations, frequencies)

1. **Follow V6's shape**, not a new pattern: `company_id NOT NULL` + leading index, normalized
   code `CHECK`, `uq_*_company_code`, actor columns, RLS in the same migration
   (`DATA_MODEL.md` section 8). `masterdata.destination:*`/`masterdata.frequency:*`
   permissions already exist in the V3 catalogue - nothing to add there.
2. **Reuse the service pattern**: pre-check for a duplicate code, then catch
   `DataIntegrityViolationException` on the actual write and translate to the same
   `ConflictException` - `OriginService`/`ZoneService` are the reference implementation, not
   something to reinvent.
3. **Reuse the frontend pattern**: `originsApi.ts`/`zonesApi.ts` and the four `masters/*`
   screen files are the template for list/filter/paginate/create/edit/activate-deactivate.
   `shared/ui/components/` (`DataTable`, `FilterBar`, `Pagination`, `FormField`,
   `confirmDialog`) needs no new component work for a screen with this shape.
4. **When a page mounts a filter bar and a modal with overlapping field labels** (a "Code"
   filter next to a "Code" form field, a table cell whose text matches a filter `<select>`
   option), scope Testing Library queries with `within(screen.getByRole('dialog'))` or
   `getByRole('cell', ...)` rather than a bare `getByLabelText`/`getByText` - see section 2.1
   for the concrete failures this avoids.
5. **Run the suite, don't just read the diff.** This attempt's actual defects were only found
   by running `npm test`; reading the files first suggested everything was correct, and would
   have been the wrong conclusion to hand off on.

## 7. Result

The origins and zones vertical slice is complete end to end: company-scoped CRUD with
activate/deactivate, server-side pagination and filtering, a generated PostGIS location
column for origins, an EWM-safe external reference field, `masterdata.origin/zone:read|manage`
permission enforcement, and Bootstrap/SweetAlert2 screens with permission-aware actions. 129
backend tests and 63 frontend tests pass; typecheck, lint and both production builds are
clean.

TMS_GATE=PASS
