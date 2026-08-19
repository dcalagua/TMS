# Step 07 - Master Data: Routes

Date: 2026-08-19
Attempt: 1
Result: **PASS**

## 0. State inherited from prior steps

The repository arrived with a clean working tree and Step 06 (`06_DESTINATIONS_FREQUENCIES` in
the overnight log, migration V7) complete: `tms.destination`/`tms.frequency`/
`tms.frequency_weekly_rule`/`tms.frequency_exception`, the full `masterdata` module shape
(`domain`/`application`/`infrastructure`/`api`), the composite-FK tenant-guarantee idiom
(`destination.zone_id`), the `Frequency`/`FrequencyWeeklyRule` diff-and-replace-via-
`orphanRemoval` aggregate pattern, and working frontend vertical slices for Origins, Zones,
Destinations and Frequencies. `masterdata.route:read/manage` permissions already existed in
migration V3 and were already granted to `PLANNER`/`VIEWER` (read) and `COMPANY_ADMIN`/
`ORGANIZATION_ADMIN` (full) - nothing needed adding there. `router.tsx` already had a
`masters/routes` route pointing at `PlaceholderPage`, and `navConfig.ts` already listed a
"Routes" nav entry. This attempt read `docs/overnight/06_DESTINATIONS_FREQUENCIES.md` section 8
("Handoff to Step 07") end to end before writing anything (rule 6) and followed it point by
point - see section 8 below for how each point was applied.

## 1. Scope decisions made before writing code

- **List and detail return different view shapes.** The step brief is explicit ("Avoid N+1 on
  route list/detail. Do not load every destination for each route list row.") in a way Steps 05
  and 06 were not - `OriginView`/`ZoneView`/`DestinationView`/`FrequencyView` are each one flat
  shape reused for both list and single-record reads. Routes needed a second shape
  (`RouteView` for list rows - a stop *count*; `RouteDetailView` for get/create/update/
  activate/deactivate - the full ordered stop list) rather than forcing the brief's constraint
  into the existing one-shape pattern. See section 3.4 of the updated `DATA_MODEL.md` (section
  9.4) for the reasoning.
- **Stop sequence is server-assigned from array order, never a client-supplied number.** The
  brief asks for "sequence starting at a consistent convention." Rather than accepting an
  explicit `sequence` field per stop (which opens the door to gaps, out-of-range values or two
  stops claiming the same position), `RouteRequest.destinationIds` is the whole ordered list and
  `Route.replaceStops` assigns `1..N` from the array's position. This also happens to be what
  the frontend's "simple move up/down ordering" naturally produces: reordering just changes
  array order and resends the whole list.
- **A destination may appear at most once per route** (`uq_route_stop_route_destination`). The
  brief explicitly asks this be "either explicitly forbidden or intentionally supported with a
  documented reason" - forbidden, documented in the V8 migration and in `DATA_MODEL.md` section
  9.3: a master Route in V1 is a corridor of distinct stops; a genuine "visit twice" itinerary is
  Trip-level planning (not built), not a reusable master. Revisit only if a concrete round-trip
  requirement appears later.
- **`route_stop` carries its own `company_id`**, unlike `frequency_weekly_rule`/
  `frequency_exception` (V7), which are pure children with none. The difference: `route_stop`
  references `tms.destination`, another company-scoped table, which needs the composite-FK
  tenant guarantee (`DATA_MODEL.md` section 10 rule 6) - and that guarantee needs `company_id`
  on the referencing row. This is documented as a new, explicit rule (rule 7) rather than a
  one-off, since it will recur (fleet/vehicle assignment tables are a likely next case).
- **The reorder-uniqueness constraint is `DEFERRABLE INITIALLY DEFERRED`.** Reordering two stops
  (a swap) via in-place `UPDATE`s necessarily passes through a transient duplicate
  `(route_id, sequence)` pair mid-transaction. Rather than avoiding the diff-and-replace pattern
  the handoff asked to reuse (which would mean falling back to delete-then-insert, explicitly
  discouraged), the unique constraint defers its check to `COMMIT` - the standard PostgreSQL
  idiom for a reorderable unique-position list. Documented as rule 8 in `DATA_MODEL.md` section
  10, with both directions (a legitimate swap survives; a genuine unresolved duplicate still
  fails, later) proven by `MasterDataRouteConstraintIntegrationTest`.
- **`RouteFormModal` fetches its own detail by id** instead of receiving an already-fetched
  object like `DestinationFormModal`/`OriginFormModal`/`FrequencyFormModal` do. Those modals can
  reuse the list row directly because list and detail share one shape; `RoutesPage`'s list rows
  (`RouteView`) do not carry stops at all, so the edit modal takes a `routeId` and calls
  `fetchRoute` itself, showing a brief loading state first. This is a direct, necessary
  consequence of the N+1-avoidance decision above, not a stylistic deviation.

## 2. Database (migration V8)

`backend/tms-api/src/main/resources/db/migration/V8__masterdata_routes.sql` follows V6/V7's
shape (`DATA_MODEL.md` section 10, "Rules for the next migrations") and adds the two new rules
described above (7 and 8):

- **`tms.route`**: `company_id NOT NULL` + FK + leading index, normalized-code `CHECK`,
  `uq_route_company_code`, mandatory `origin_id` with the composite-FK tenant guarantee
  (`fk_route_origin_company`, backed by a new `uq_origin_id_company` on `tms.origin`),
  optional `zone_id` (reusing `tms.zone.uq_zone_id_company` from V7) and optional
  `frequency_id` (backed by a new `uq_frequency_id_company` on `tms.frequency`), optional
  `reference_distance_km numeric(8,2)` and `reference_duration_minutes integer` (both
  nonnegative `CHECK`s, planner-entered values, not measured/optimized ones), `active`, audit
  columns, and `uq_route_id_company` as the composite-FK target `route_stop` needs.
- **`tms.route_stop`**: `route_id` FK `ON DELETE CASCADE` (pure lifecycle child of its route),
  its own `company_id` FK (see section 1's third bullet), the composite FK
  `(route_id, company_id) -> route (id, company_id)` guaranteeing the stop's company matches
  its own route's, `destination_id` FK plus the composite
  `(destination_id, company_id) -> destination (id, company_id)` (backed by a new
  `uq_destination_id_company` on `tms.destination`), `sequence integer` with
  `ck_route_stop_sequence_positive CHECK (sequence >= 1)`,
  `uq_route_stop_route_destination UNIQUE (route_id, destination_id)` (no duplicate stop), and
  `uq_route_stop_route_sequence UNIQUE (route_id, sequence) DEFERRABLE INITIALLY DEFERRED` (see
  section 1's fifth bullet).
- All new/changed tables get `ENABLE ROW LEVEL SECURITY` in this same migration, matching every
  table before them.

## 3. Backend

`masterdata` package additions follow the `Origin`/`Zone`/`Destination`/`Frequency` reference
implementations exactly (`docs/overnight/06_DESTINATIONS_FREQUENCIES.md` section 8, points 1-4):

- **`Route`/`RouteStop`**: `Route` is the aggregate root; every stop mutation goes through
  `Route.replaceStops(List<UUID> destinationIds, UUID actorId)`, which diffs the incoming
  ordered list against what is persisted keyed by `destinationId` (present in both → sequence
  updated in place; newly present → added; no longer present → removed via `orphanRemoval`) -
  the same shape `Frequency.replaceWeeklyRules` (V7) established, keyed by `dayOfWeek` there and
  by `destinationId` here. `RouteService` rejects a duplicate `destinationId` in the incoming
  list before calling in, for the same reason `FrequencyService` rejects a duplicate
  `dayOfWeek` - a `Map` would otherwise silently drop one.
- **`RouteService`**: pre-checks for a duplicate code, then catches a raced
  `DataIntegrityViolationException` on the actual write (`saveOrConflict`, identical pattern to
  `DestinationService`/`FrequencyService`). `originId` is mandatory and validated against the
  caller's company (`requireOriginInScope`, 400 `malformed-request` on mismatch, not a raw
  constraint violation); `zoneId`/`frequencyId` are optional and validated the same way
  `DestinationService.requireZoneInScope` validates `zoneId`; every `destinationId` is validated
  in one batched `findAllById` call (`requireDestinationsInScope`), never one lookup per id.
  List batch-resolves origin/zone/frequency for the whole page in one query each
  (`loadByIds`, a small generic helper built once instead of three near-identical private
  methods) and stop counts in one `GROUP BY` query
  (`RouteStopRepository.countByRouteIds`) - the brief's N+1 constraint. Every lookup map is a
  plain `HashMap`, not `Map.of()`, per the `Map.of().get(null)` defect the Step 06 handoff
  flagged (section 3.1 of that report) - a route with no zone/frequency looks up a `null` key.
- **`RouteController`**: mirrors `DestinationController` - `masterdata.route:read/manage`
  `@PreAuthorize`, `CompanyScope` resolved by the framework, no delete endpoint (deactivate
  only, which is also what keeps a deactivated origin/destination from erasing route history -
  the row is never removed). List returns `RouteView`; every other endpoint returns
  `RouteDetailView` - see section 1.

## 4. Frontend

`routesApi.ts` and two screen files follow the `destinationsApi.ts`/`DestinationsPage`/
`DestinationFormModal` template with one structural change forced by the list/detail split
(section 1):

- **`RoutesPage`**: list/filter (code, name, origin, zone, status)/paginate/activate/deactivate,
  built from the same `shared/ui/components` primitives as every other masters screen. Columns
  are exactly what the brief asks for - code, name, origin, zone, stop count, active status -
  plus the row actions. The page header copy explicitly distinguishes "Master Route" (a
  reusable planned corridor a planner sets up once) from a future Trip's calculated route (not
  built, will be produced per shipment). Deactivation goes through `confirmDialog`
  (SweetAlert2), matching every other masters screen.
- **`RouteFormModal`**: an outer component that, for edit mode, fetches the route's full detail
  by id (`fetchRoute`) and shows `LoadingState`/an error message until it resolves, then hands
  off to an inner `RouteForm` that does the actual `react-hook-form` work - the split exists
  because `useForm`'s `defaultValues` must be known at mount, and the detail is not available
  synchronously for edit mode the way it is for every other masters form (section 1). Ordered
  stops are managed with `useFieldArray` (`stops: { destinationId }[]`): a "destination to add"
  `<select>` (excluding destinations already on the route) plus an "Add stop" button appends;
  each row has move-up/move-down (`useFieldArray.move`, disabled at the ends) and Remove
  buttons - the brief's explicit "simple move up/down ordering" allowance instead of
  drag-and-drop. Origin/zone/frequency `<select>`s and the stop lookup all prepend the
  currently-assigned value if a deactivation dropped it out of the active-only fetch, so editing
  a route whose origin/zone/frequency/destination was deactivated since never breaks - the
  direct frontend consequence of the "deactivating does not silently delete route history"
  invariant (`DATA_MODEL.md` section 9.5). Submission is blocked client-side with a plain
  `formError` message if the stop list is empty (`RouteRequest.destinationIds` is
  `@NotEmpty` server-side too).
- **`router.tsx`**: `masters/routes` now routes to `RoutesPage` instead of `PlaceholderPage`;
  `navConfig.ts` already had the entry and needed no change.

## 5. Verification

Backend (`backend/tms-api`, `./mvnw -q -o test`, Docker Desktop running so every
Testcontainers-backed test executed rather than being skipped):

```
22 test classes, 182 tests, 0 failures, 0 errors
```

including the two new classes added this step:

- `MasterDataRouteConstraintIntegrationTest` (14 tests): route code uniqueness per company; FK
  to a real company; code normalization; a route's origin must belong to its own company even
  though the FK columns are separate; the same for optional zone and frequency; nonnegative
  reference distance/duration; a route stop's destination must belong to the same company as
  the route; a route stop's `company_id` must match its own route's; positive sequence; a
  destination cannot appear twice on the same route; stops cascade-delete with their route (no
  orphans); defaults and actor columns; **two dedicated tests for the `DEFERRABLE INITIALLY
  DEFERRED` sequence constraint** - swapping two stops' sequence in place survives `COMMIT`
  (the legitimate reorder case), and a genuine unresolved duplicate sequence still fails, just
  at `COMMIT` instead of at the statement (proving the constraint still holds, not that it was
  silently disabled).
- `RouteApiIntegrationTest` (13 tests): create persists the ordered stops and reads back in
  order through both the create response and a subsequent `GET`; **list shows a stop count and
  never a `stops` field** (the N+1-avoidance shape, asserted directly); same code allowed
  across companies but not within one; cross-company access blocked with 404; an origin from
  another company rejected as `malformed-request`; a destination from another company rejected
  the same way; a repeated destination within one request rejected; an empty stop list rejected
  with `validation-failed`; **update reorders/re-sequences stops transactionally** (a dropped
  stop disappears, kept stops re-sequence from the new array order); activate/deactivate
  reflected in the active filter; a read-only role blocked from managing; server-side
  pagination; **deactivating a destination used by a route does not remove it from the route's
  stop history** (the invariant from `DATA_MODEL.md` section 9.5, exercised end to end through
  both the destinations and routes controllers in one test).

Backend also re-ran the full pre-existing suite (`LayeringTest`, `ModuleBoundaryTest`,
`SchemaExposureIntegrationTest` with `route`/`route_stop` added to its RLS/`public`-schema
assertions, `OriginZoneApiIntegrationTest`, `DestinationFrequencyApiIntegrationTest`,
`ApiSecurityTest`, etc.) with no regressions.

One real defect was found and fixed while writing the constraint test, not by reading the code:
the two commit-based tests (`reorderingStopsInPlaceSurvivesCommit`,
`genuineDuplicateSequenceFailsAtCommit`) must actually call `connection.commit()` to observe a
`DEFERRABLE INITIALLY DEFERRED` constraint, unlike every other test in the class, which relies
on an `@AfterEach` rollback for isolation. The first run reused the same organization code
(`MDR-ORG`) the committing tests also used, and JUnit happened to run a committing test before a
later rollback-based test that inserted the same code, producing a real `uq_organization_code`
violation. Fixed by giving the two committing tests their own dedicated fixture codes
(documented inline in the test) so they cannot collide with the rest of the class regardless of
execution order.

Frontend (`frontend/tms-web`):

```
npm run typecheck    tsc -b                clean, no errors
npm run lint          oxlint                0 errors, 2 pre-existing warnings (unrelated files, documented since 04_FRONTEND_FOUNDATION.md)
npm test              vitest run            19 files, 111 tests passed
npm run build         tsc -b && vite build  built in 392ms, dist/ produced
```

The 111 tests include the 2 new files added this step (`RoutesPage.test.tsx`,
`RouteFormModal.test.tsx`, 8 and 9 tests respectively) plus the full pre-existing suite,
unmodified and still green. `RouteFormModal.test.tsx` covers: rejecting an empty submission;
requiring at least one stop even when every other field is valid; adding/removing a stop;
reordering with move up/down; creating a route with stops in the order they were added;
loading and pre-filling an existing route and calling `updateRoute` with its id; **still
showing a stop whose destination was deactivated since the route was saved** (an active-only
fetch would otherwise drop it from the dropdown, but not from the lookup used to render the
existing stop); mapping a backend field error onto the matching input; closing on Cancel. The
production build emits the same pre-existing chunk-size warning documented since Step 04 - the
dependency set has not changed - not a regression.

An initial run of the two ordering-related `RouteFormModal` tests failed because
`selectOptions`/an assertion ran before the mocked `fetchDestinations` promise had resolved into
the `<select>`'s options, and because `queryByText` on a removed stop's label also matched the
same text now reappearing as an `<option>` in the "add" dropdown. Both were test-only timing/
scoping issues (fixed by awaiting the option's presence first and scoping the post-removal
assertion to `listitem` roles) - not a defect in `RouteFormModal` itself.

### 5.1 Test coverage against the brief

| Required case | Backend test | Frontend test |
|---|---|---|
| company isolation | `RouteApiIntegrationTest` (404, not 403), `MasterDataRouteConstraintIntegrationTest` | n/a (server-enforced) |
| sequence uniqueness/order | `MasterDataRouteConstraintIntegrationTest` (DB constraint, both directions of the deferred check), `RouteApiIntegrationTest` (create returns stops in submitted order; update re-sequences) | `RouteFormModal.test.tsx` (move up/down changes rendered order and submitted `destinationIds`) |
| invalid cross-company destination | `MasterDataRouteConstraintIntegrationTest`, `RouteApiIntegrationTest` | n/a (server-enforced; the add-dropdown only ever offers the caller's own company's destinations) |
| route detail ordering | `RouteApiIntegrationTest.createWithOrderedStopsAndReadBack` | `RouteFormModal.test.tsx` (pre-fills existing stops in sequence order) |
| N+1-sensitive query shape | `RouteApiIntegrationTest.listShowsStopCountNotFullStops` (response-shape proof: list carries a count, no `stops` field) plus the code-level batching described in section 3 (`loadByIds`, `countByRouteIds`, `loadDestinations`) | n/a |
| permission gating | `RouteApiIntegrationTest.readOnlyRoleCannotManage` | `RoutesPage.test.tsx` (manage actions hidden without `masterdata.route:manage`) |
| route editor behavior | `RouteApiIntegrationTest` (create/update/duplicate-destination/empty-stops) | `RouteFormModal.test.tsx` (add/remove/reorder/validate/submit/field-error/cancel) |
| deactivating origin/destination preserves history | `RouteApiIntegrationTest.deactivatingADestinationPreservesRouteHistory` | `RouteFormModal.test.tsx` (still renders a stop whose destination is no longer in the active fetch) |

## 6. Constraint compliance

| Constraint | How |
|---|---|
| never push, never deploy | nothing was pushed; no deployment exists |
| never mutate a remote/shared database | all tests ran against a local, disposable Testcontainers PostgreSQL; no Supabase project or shared database was touched |
| no real secrets | no `.env` file was read or created |
| no destructive Git operations | none run; nothing was staged or committed per the overnight-pack instruction |
| Flyway is the only migration owner | V8 is the only schema change; no `supabase/migrations` entry was added |
| Java owns business logic and authorization | company scoping, code normalization, cross-company origin/zone/frequency/destination rejection, conflict detection, stop-order diffing and `@PreAuthorize` all live in the backend |
| React talks to Spring Boot for business data | `routesApi.ts` calls `apiRequest` exclusively; no direct Supabase table access was added |
| TMS independent from EWM | no new external-system reference was added; routes reference only TMS's own origin/zone/frequency/destination masters |
| vertical slice checked end to end | `RoutesPage`/`RouteFormModal` → `routesApi.ts` → `RouteController` → `RouteService` → `RouteRepository`/`RouteStopRepository`/`OriginRepository`/`ZoneRepository`/`FrequencyRepository`/`DestinationRepository` → `tms.route`/`tms.route_stop` → RLS + `@PreAuthorize` → the tests in section 5, read and verified layer by layer |
| do not claim untested passes | every number in section 5 comes from a run executed this session; the organization-code collision in the constraint test (section 5) and the two frontend test timing issues were only found by running the suites, not by reading the code |

## 7. Files

Added:

```
backend/tms-api/src/main/resources/db/migration/V8__masterdata_routes.sql
backend/tms-api/src/main/java/com/ebim/tms/masterdata/domain/{Route,RouteStop}.java
backend/tms-api/src/main/java/com/ebim/tms/masterdata/application/{RouteFilter,RouteRequest,
  RouteView,RouteDetailView,RouteService}.java
backend/tms-api/src/main/java/com/ebim/tms/masterdata/infrastructure/{RouteRepository,
  RouteStopRepository,RouteSpecifications}.java
backend/tms-api/src/main/java/com/ebim/tms/masterdata/api/RouteController.java
backend/tms-api/src/test/java/com/ebim/tms/database/MasterDataRouteConstraintIntegrationTest.java
backend/tms-api/src/test/java/com/ebim/tms/masterdata/api/RouteApiIntegrationTest.java
frontend/tms-web/src/shared/api/routesApi.ts
frontend/tms-web/src/pages/masters/{RoutesPage,RouteFormModal}.tsx
frontend/tms-web/src/pages/masters/{RoutesPage,RouteFormModal}.test.tsx
docs/overnight/07_ROUTES.md
```

Modified:

```
backend/tms-api/src/test/java/com/ebim/tms/database/SchemaExposureIntegrationTest.java
  route/route_stop added to the RLS and not-in-public checks
frontend/tms-web/src/app/router.tsx
  masters/routes routed to the real RoutesPage
docs/database/DATA_MODEL.md
  documented the V8 route model (new section 9), renumbered the migration-rules section to 10
  and added rules 7 (child tables that reference another company-scoped table still need their
  own company_id) and 8 (deferrable unique constraints for reorderable position lists), and
  updated section 6's test-coverage table
```

## 8. Handoff to Step 08 (fleet masters)

1. **Fleet's own masters (carrier, vehicle type, vehicle) are unlikely to need the
   deferrable-constraint or list/detail-split patterns** this step introduced - those were
   consequences of routes specifically needing an ordered, reorderable child list and an
   explicit N+1 constraint in the brief. Default to the plain `Origin`/`Zone` shape (rule 1-6
   in `DATA_MODEL.md` section 10) unless a fleet table genuinely needs one of them.
2. **If a vehicle needs to reference a carrier/vehicle type from the caller's own company**,
   reuse the composite-FK idiom (rule 6) - by now `uq_<table>_id_company` targets already exist
   on `origin`, `zone`, `destination` and `frequency`; add one for any new table a fleet table
   references the same way.
3. **If a fleet table is a child of another fleet table AND references a third company-scoped
   table** (the `route_stop` situation), it needs its own `company_id` even if it is otherwise a
   pure lifecycle child - rule 7 in `DATA_MODEL.md` section 10 names this explicitly now so it
   does not need to be re-derived.
4. **The `loadByIds` generic batch-resolution helper in `RouteService`** (one method instead of
   three near-identical `loadOrigins`/`loadZones`/`loadFrequencies` methods) is a reasonable
   pattern to reuse if a fleet list endpoint needs to batch-resolve more than one related
   master per page - see `RouteService.java` for the exact shape.
5. **A route-frequency or fleet-vehicle-type-capacity-override table, if the fleet brief asks
   for one, should follow the same "defer until a concrete screen needs it" judgment** Steps 05
   and 06 already established for zone geometry and the destination-frequency association -
   still not needed for routes either (see `DATA_MODEL.md` section 8.3, unchanged by this step).

## 9. Result

Master routes are complete end to end: company-scoped CRUD with activate/deactivate, an
N+1-safe list (stop count via one batched query) versus detail (full ordered stops, also
batch-resolved) split, an ordered stop list managed as a genuine aggregate (diff-and-replace via
`orphanRemoval`, keyed by destination id, sequence assigned from array order rather than
trusted from the client), a `DEFERRABLE INITIALLY DEFERRED` constraint that makes in-place
reordering both correct and provably still enforced, composite-FK tenant guarantees on every
cross-table reference (origin, zone, frequency, destination), and a Bootstrap/SweetAlert2 editor
with move-up/move-down ordering and permission-aware actions that clearly reads as a "Master
Route," distinct in its own copy from a future calculated Trip route. 182 backend tests and 111
frontend tests pass; typecheck, lint and both production builds are clean. Two real defects (a
fixture organization-code collision in the new constraint test, and two frontend test
timing/scoping issues) were found by running the suites and fixed before this report was
written - not found by reading the code alone.

TMS_GATE=PASS
