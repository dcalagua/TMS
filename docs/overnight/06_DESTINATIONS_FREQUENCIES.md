# Step 06 - Master Data: Destinations and Frequencies

Date: 2026-08-19
Attempt: 1
Result: **PASS**

## 0. State inherited from prior steps

The repository arrived with a clean working tree and Step 05 (`06_ORIGINS_ZONES` in the
overnight log, migration V6) already complete: `tms.origin`/`tms.zone`, the full
`masterdata` module shape (`domain`/`application`/`infrastructure`/`api`), `ConflictException`,
`OriginZoneApiIntegrationTest`, `MasterDataConstraintIntegrationTest`, and a working
frontend vertical slice for Origins and Zones. `masterdata.destination:*` and
`masterdata.frequency:*` permissions, roles and grants already existed in migration V3 -
nothing needed adding there. This attempt read every one of those files end to end before
writing anything (rule 6), and followed their exact shape rather than inventing a new one -
see `docs/overnight/05_ORIGINS_ZONES.md` section 6 ("Handoff to Step 06"), which this attempt
followed point by point.

## 1. Scope decisions made before writing code

The step brief's "Frequency model" section lists a fourth recommended piece - an explicit
destination-frequency association table - alongside the header/weekly-rule/exception model
that was built. That table was **not** added in migration V7. Reasoning, recorded in full in
`docs/database/DATA_MODEL.md` section 8.3:

- Neither the Destinations nor the Frequencies part of the brief's Frontend section asks for
  an assignment screen.
- Nothing in Orders/Planning (not built yet) has a concrete requirement for how a destination
  should be tied to a schedule.
- Building the join table now, with no reachable API and no screen, is exactly the
  speculative complexity the repository instructions ask to avoid.
- V6 already made the identical call for zone geometry ("no geometry in V1... a future
  migration can add it without changing this shape") - this is the same judgment applied a
  second time, not a new pattern.

Everything else the brief asks for was built: the destination model (company, unique code,
type vocabulary, address/reference, neutral locality fields, coordinates with the same
generated-geography treatment as `tms.origin`, optional zone, service time, active, audit),
and the frequency model (header + weekly rule child collection + date exception child
collection, explicitly not five/seven boolean columns).

## 2. Database (migration V7)

`backend/tms-api/src/main/resources/db/migration/V7__masterdata_destinations_frequencies.sql`
follows V6's shape exactly - `docs/database/DATA_MODEL.md` section 9 ("Rules for the next
migrations") - and adds one new rule of its own (rule 6, section 8.1): a foreign key from one
company-scoped table into another gets a composite-FK tenant guarantee, not just a
same-column FK.

- **`tms.destination`**: `company_id NOT NULL` + FK + leading index, normalized-code
  `CHECK`, `uq_destination_company_code`, `destination_type` restricted to
  `CUSTOMER | STORE | BRANCH | HUB | DISTRIBUTION_CENTER | DELIVERY_POINT`, optional
  `address`/`address_reference` plus neutral `district`/`province`/`department`/`country`
  locality fields (Peru-shaped names, plain text, nothing validated against a Peru-specific
  catalogue), coordinate pair/range `CHECK`s identical to `tms.origin`, a `GENERATED ALWAYS`
  `geography(Point,4326) location` column with a GiST index, an optional `zone_id`,
  nonnegative `service_time_minutes`, and an `external_reference` that is a free-text column
  and never a foreign key into another product's schema (the EWM-independence rule).
- **`tms.zone.zone_id` tenant guarantee**: `tms.zone` gets a new `uq_zone_id_company UNIQUE
  (id, company_id)`, and `tms.destination` gets `fk_destination_zone_company FOREIGN KEY
  (zone_id, company_id) REFERENCES tms.zone (id, company_id)` - the same composite-FK idiom
  `tms.membership` (V2) uses for `company_id`/`organization_id`. `MATCH SIMPLE` means the FK
  is satisfied whenever `zone_id IS NULL`, so the optional zone stays optional; when set, the
  database itself refuses a zone from another company, not just the Java-side check.
- **`tms.frequency`**: a plain company-scoped header (code/name/description/active/audit) -
  deliberately just that, so the weekly cadence and date overrides can each evolve without
  touching it or each other.
- **`tms.frequency_weekly_rule`**: one row per configured day (`day_of_week` 1-7, ISO-8601
  Monday-Sunday), `UNIQUE (frequency_id, day_of_week)`, `enabled` boolean, optional
  `cutoff_time`/`lead_time_days` (nonnegative `CHECK`). No `company_id` of its own - it
  cascades from `tms.frequency`, the same "no meaning without its parent" shape
  `tms.membership_role` uses as a child of `tms.membership`.
- **`tms.frequency_exception`**: one row per date override (`UNIQUE (frequency_id,
  exception_date)`), a `service_override` boolean (true = extra service date, false =
  blackout), an optional non-blank `note`. Also cascades from `tms.frequency`.
- All four tables get `ENABLE ROW LEVEL SECURITY` in this same migration, matching every
  table before them (V4's `membership_role`, V6's `origin`/`zone`).

## 3. Backend

`masterdata` package additions follow the `Origin`/`Zone` reference implementation
(`docs/overnight/05_ORIGINS_ZONES.md` section 6, point 2) exactly:

- **`DestinationService`**: pre-check for a duplicate code, catch a raced
  `DataIntegrityViolationException` on the actual write and translate to the same
  `ConflictException` a pre-check would throw. `zoneId` is validated against the caller's own
  company (`ZoneRepository.findByIdAndCompanyId`) before any write, so a cross-company zone
  reference is rejected as `400 malformed-request`, not a raw constraint violation. List
  responses batch-resolve every zone referenced on the page in one query
  (`zoneRepository.findAllById`) rather than one lookup per row.
- **`FrequencyService`**: same duplicate-code pattern for the header. `Frequency` is the
  aggregate root for its weekly rules - `FrequencyService.create`/`update` calls
  `Frequency.replaceWeeklyRules(inputs, actorId)`, which diffs the incoming list against
  what is persisted (day present in both → update in place, day newly present → add, day no
  longer present → removed by Hibernate's `orphanRemoval`) inside the same transaction as the
  header write. A duplicate `dayOfWeek` within one request is rejected
  (`InvalidRequestException`) before it ever reaches the entity, since bean validation alone
  cannot express a cross-row uniqueness rule. Exceptions are managed one at a time through
  their own sub-resource methods (`listExceptions`/`createException`/`deleteException`), each
  re-validating the parent frequency is in the caller's company first.
- **Controllers**: `DestinationController`/`FrequencyController` mirror
  `OriginController`/`ZoneController` - `masterdata.destination:read/manage` and
  `masterdata.frequency:read/manage` `@PreAuthorize`, `CompanyScope` resolved by the
  framework, no delete endpoint for either master (deactivate only). `FrequencyController`
  adds `GET`/`POST /masterdata/frequencies/{id}/exceptions` and a real `DELETE
  /masterdata/frequencies/{id}/exceptions/{exceptionId}` - a genuine exception to "no delete"
  because an exception row is an independent calendar fact nothing else references, not a
  master record.

### 3.1 A real defect found and fixed before the suite was declared green

`DestinationService.loadZones` originally special-cased an empty zone-id set with `return
Map.of();`. Running the integration suite (not just reading the code) surfaced a
`NullPointerException` on every `GET /masterdata/destinations` call for a destination with no
zone: `Map.of()` is one of Java's immutable collections, and unlike `HashMap`, its `get(null)`
throws instead of returning `null` - so `zonesById.get(destination.zoneId())` blew up the
moment `zoneId()` was `null`. Fixed by always building a plain `HashMap` (which returns `null`
for a missing/null key, exactly the semantics the calling code needs) instead of reaching for
`Map.of()` as a shortcut for "nothing to look up." This is exactly the kind of thing that
looks correct on read-through and only shows up under a real request - the reason rule 13 (do
not claim untested passes) matters.

## 4. Frontend

`destinationsApi.ts`/`frequenciesApi.ts` and four screen files follow the `originsApi.ts`/
`zonesApi.ts` + `OriginsPage`/`OriginFormModal` template
(`docs/overnight/05_ORIGINS_ZONES.md` section 6, point 3) with no new shared component work:

- **`DestinationsPage`/`DestinationFormModal`**: list/filter (code, name, type, zone,
  status)/paginate/create/edit/activate/deactivate, all through `shared/ui/components`. The
  zone `<select>` in both the filter bar and the form is populated by fetching the company's
  active zones (`fetchZones`), matching the "zone selection" requirement. Coordinate
  validation (both-or-neither, range) is the same client-side check `OriginFormModal` already
  has, mirrored here.
- **`FrequenciesPage`/`FrequencyFormModal`**: list/filter/paginate/create/edit/activate/
  deactivate for the header, plus a fixed Monday-Sunday grid (7 rows, always rendered, always
  sent on submit - see the "not a partial update" reasoning in `FrequencyRequest`'s doc
  comment and `DATA_MODEL.md` section 8.2) with a service-day checkbox, an optional cutoff
  time and an optional lead-time-in-days input per row.
- **Exceptions editor**: per the brief's explicit allowance ("otherwise expose a clear V1
  placeholder and document the deferred UI"), `FrequencyFormModal` shows a plain informational
  note instead of a full editor - the backend fully supports exceptions
  (`fetchFrequencyExceptions`/`createFrequencyException`/`deleteFrequencyException` are
  exported from `frequenciesApi.ts` and exercised by
  `DestinationFrequencyApiIntegrationTest`), but no V1 screen calls them yet. This is
  documented in the component itself, not just here.
- **`router.tsx`**: `masters/destinations` and `masters/frequencies` now route to the real
  pages instead of `PlaceholderPage`; navigation entries in `navConfig.ts` already existed
  and needed no change.

## 5. Verification

Backend (`backend/tms-api`, `./mvnw -q -o test`, Docker Desktop running so every
Testcontainers-backed test executed rather than being skipped):

```
20 test classes, 155 tests, 0 failures, 0 errors
```

including the two new classes added this step:

- `MasterDataDestinationFrequencyConstraintIntegrationTest` (10 tests): code uniqueness per
  company for both masters; FK to a real company; code normalization; destination coordinate
  pair/range checks with the generated `location` column verified via `ST_X`/`ST_Y`;
  `destination_type` restriction and nonnegative `service_time_minutes`; a zone from another
  company rejected by the composite FK even though the plain `zone_id` FK alone would have
  accepted it; weekly rule day-of-week range, per-frequency uniqueness and nonnegative
  `lead_time_days`; weekly rules and exceptions cascade-deleted with their frequency (no
  orphans); exception date uniqueness and non-blank note; defaults and actor columns.
- `DestinationFrequencyApiIntegrationTest` (16 tests across `Destinations`/`Frequencies`/
  `Frequencies.Exceptions` `@Nested` classes): create normalizes the code; same code allowed
  across companies but not within one; cross-company access blocked with 404; invalid/
  incomplete coordinates rejected with field-level errors; a real zone from another company
  rejected as `malformed-request`; activate/deactivate reflected in the `active` filter;
  update re-checks the code; read-only role blocked from managing; server-side pagination;
  weekly rules returned sorted by day; a duplicate day-of-week in one request rejected; an
  out-of-range day-of-week rejected by bean validation; **update replaces the weekly rule set
  transactionally** (day 1 removed, day 2 kept-and-updated, day 3 removed, day 4 added, in one
  `PUT`); exception create/list/delete lifecycle with a duplicate-date conflict; exceptions of
  a frequency in another company return 404.

Backend also re-ran the full pre-existing suite (`LayeringTest`, `ModuleBoundaryTest`,
`SchemaExposureIntegrationTest` with the four new tables added to its RLS/`public`-schema
assertions, `OriginZoneApiIntegrationTest`, `ApiSecurityTest`, etc.) with no regressions.

Frontend (`frontend/tms-web`):

```
npm run typecheck    tsc -b                clean, no errors
npm run lint          oxlint                0 errors, 2 pre-existing warnings (unrelated files, documented in 04_FRONTEND_FOUNDATION.md)
npm test              vitest run            17 files, 94 tests passed
npm run build         tsc -b && vite build  built in 379ms, dist/ produced
```

The 94 tests include the 4 new files added this step
(`DestinationsPage.test.tsx`/`DestinationFormModal.test.tsx`/`FrequenciesPage.test.tsx`/
`FrequencyFormModal.test.tsx`) plus the full pre-existing suite, unmodified and still green.
The production build emits a chunk-size warning (>500 kB) that pre-dates this step - the
dependency set has not changed - and is not a regression.

### 5.1 Test coverage against the brief

| Required case | Backend test | Frontend test |
|---|---|---|
| cross-company isolation | `DestinationFrequencyApiIntegrationTest` (404, not 403), `MasterDataDestinationFrequencyConstraintIntegrationTest` | n/a (server-enforced) |
| duplicate codes | `DestinationFrequencyApiIntegrationTest`, `MasterDataDestinationFrequencyConstraintIntegrationTest` | n/a (server-enforced; UI surfaces the conflict message) |
| destination coordinates | `DestinationFrequencyApiIntegrationTest`, `MasterDataDestinationFrequencyConstraintIntegrationTest` | `DestinationFormModal.test.tsx` (range + both-or-neither) |
| weekly rule uniqueness | `MasterDataDestinationFrequencyConstraintIntegrationTest` (DB), `DestinationFrequencyApiIntegrationTest` (API-level duplicate-day rejection) | n/a (frontend grid cannot represent a duplicate day by construction) |
| invalid day/time values | `MasterDataDestinationFrequencyConstraintIntegrationTest` (day range, negative lead time), `DestinationFrequencyApiIntegrationTest` (bean validation) | n/a (`<input type="time">`/`type="number" min=0` constrain input; server is authoritative) |
| transactionality (weekly rule replace) | `DestinationFrequencyApiIntegrationTest.updateReplacesWeeklyRulesTransactionally` | n/a |
| frontend rule editing | - | `FrequencyFormModal.test.tsx` (fixed grid renders all 7 rows, sends all 7 on submit, pre-fills from an existing frequency) |
| zone selection | `DestinationFrequencyApiIntegrationTest.zoneMustBelongToCallersCompany` | `DestinationFormModal.test.tsx` (lists zones fetched from the backend) |
| list/filter/pagination | `DestinationFrequencyApiIntegrationTest` | `DestinationsPage.test.tsx`/`FrequenciesPage.test.tsx` |
| security permissions | `DestinationFrequencyApiIntegrationTest` (viewer role forbidden on manage) | both page test files (actions hidden without `*:manage`) |
| create/edit/activate/deactivate | `DestinationFrequencyApiIntegrationTest` | both page + form-modal test files |

## 6. Constraint compliance

| Constraint | How |
|---|---|
| never push, never deploy | nothing was pushed; no deployment exists |
| never mutate a remote/shared database | all tests ran against a local, disposable Testcontainers PostgreSQL; no Supabase project or shared database was touched |
| no real secrets | no `.env` file was read or created |
| no destructive Git operations | none run; nothing was staged or committed per the overnight-pack instruction |
| Flyway is the only migration owner | V7 is the only schema change; no `supabase/migrations` entry was added |
| Java owns business logic and authorization | company scoping, code normalization, cross-company zone rejection, conflict detection, weekly-rule diffing and `@PreAuthorize` all live in the backend |
| React talks to Spring Boot for business data | `destinationsApi.ts`/`frequenciesApi.ts` call `apiRequest` exclusively; no direct Supabase table access was added |
| TMS independent from EWM | `destination.external_reference` is a free-text optional column, never a foreign key |
| vertical slice checked end to end | `DestinationsPage`/`FrequenciesPage` → `destinationsApi.ts`/`frequenciesApi.ts` → `DestinationController`/`FrequencyController` → `DestinationService`/`FrequencyService` → `DestinationRepository`/`FrequencyRepository`/`FrequencyExceptionRepository` → `tms.destination`/`tms.frequency`/`tms.frequency_weekly_rule`/`tms.frequency_exception` → RLS + `@PreAuthorize` → the tests in section 5, read and verified layer by layer |
| do not claim untested passes | every number in section 5 comes from a run executed this session; the `Map.of()` defect in section 3.1 was only found by running the suite, not by reading the code |

## 7. Files

Added:

```
backend/tms-api/src/main/resources/db/migration/V7__masterdata_destinations_frequencies.sql
backend/tms-api/src/main/java/com/ebim/tms/masterdata/domain/{Destination,DestinationType,
  Frequency,FrequencyWeeklyRule,FrequencyWeeklyRuleInput,FrequencyException}.java
backend/tms-api/src/main/java/com/ebim/tms/masterdata/application/{DestinationFilter,
  DestinationRequest,DestinationService,DestinationView,FrequencyFilter,FrequencyRequest,
  FrequencyService,FrequencyView,FrequencyExceptionRequest,FrequencyExceptionView}.java
backend/tms-api/src/main/java/com/ebim/tms/masterdata/infrastructure/{DestinationRepository,
  DestinationSpecifications,FrequencyRepository,FrequencySpecifications,
  FrequencyExceptionRepository}.java
backend/tms-api/src/main/java/com/ebim/tms/masterdata/api/{DestinationController,
  FrequencyController}.java
backend/tms-api/src/test/java/com/ebim/tms/database/
  MasterDataDestinationFrequencyConstraintIntegrationTest.java
backend/tms-api/src/test/java/com/ebim/tms/masterdata/api/DestinationFrequencyApiIntegrationTest.java
frontend/tms-web/src/shared/api/{destinationsApi,frequenciesApi}.ts
frontend/tms-web/src/pages/masters/{DestinationsPage,DestinationFormModal,FrequenciesPage,
  FrequencyFormModal}.tsx
frontend/tms-web/src/pages/masters/{DestinationsPage,DestinationFormModal,FrequenciesPage,
  FrequencyFormModal}.test.tsx
docs/overnight/06_DESTINATIONS_FREQUENCIES.md
```

Modified:

```
backend/tms-api/src/test/java/com/ebim/tms/database/SchemaExposureIntegrationTest.java
  destination/frequency/frequency_weekly_rule/frequency_exception added to the RLS and
  not-in-public checks
docs/database/DATA_MODEL.md
  documented the V7 destination/frequency model (new section 8), renumbered the
  migration-rules section to 9 and added rule 6 (composite-FK tenant guarantee)
frontend/tms-web/src/app/router.tsx
  masters/destinations and masters/frequencies routed to real pages
```

## 8. Handoff to Step 07 (masters: routes)

1. **Follow V7's shape**, which itself follows V6's: `company_id NOT NULL` + leading index,
   normalized code `CHECK`, `uq_*_company_code`, actor columns, RLS in the same migration.
   `masterdata.route:*` permissions already exist in the V3 catalogue.
2. **If a route needs to reference a destination/origin/zone from the caller's own company**,
   reuse the composite-FK idiom from section 8.1 (`uq_<table>_id_company` on the referenced
   table, a composite FK on the referencing table) rather than trusting the Java-side check
   alone - it is now the established pattern for this exact situation, not a one-off.
3. **Reuse the aggregate/child-collection pattern from `Frequency`/`FrequencyWeeklyRule`** if
   a route needs an ordered or keyed list of child rows (stops, legs): diff-and-replace inside
   one transaction via `orphanRemoval`, not raw delete-then-insert SQL.
4. **`Map.of()`'s `get(null)` throws** - use a plain `HashMap` for any lookup map that may be
   queried with a `null` key (an optional foreign key resolved for a batch of parent rows).
   See section 3.1 for the concrete failure this caused and how it was caught.
5. **A destination-frequency (or route-frequency) association table is still deferred** - see
   section 8.3. Add it only when Orders/Planning states a concrete requirement for it.

## 9. Result

Destinations and frequencies are complete end to end: company-scoped CRUD with activate/
deactivate, server-side pagination and filtering, a generated PostGIS location column for
destinations (matching origins), an optional company-consistent zone reference, a frequency
model built from a header plus two owned child collections (weekly rules diffed
transactionally, date exceptions managed individually) instead of hardcoded booleans, and
Bootstrap/SweetAlert2 screens with permission-aware actions. 155 backend tests and 94
frontend tests pass; typecheck, lint and both production builds are clean. One real defect
(a `Map.of()`/`null`-key `NullPointerException` in `DestinationService`) was found by running
the suite and fixed before this report was written.

TMS_GATE=PASS
