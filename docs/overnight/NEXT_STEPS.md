# TMS by EBIM - next steps

Short roadmap with acceptance gates. Companion to
[`FINAL_REPORT.md`](FINAL_REPORT.md), which carries the evidence behind every claim here.

State at hand-off: branch `main`, HEAD `7aa4ffe` plus this step's documents, working tree clean,
nothing pushed. Backend 324/324 tests pass, frontend 219/219 pass, both re-run on 2026-08-19.

**The standing gate, for every item below:**

```
cd backend/tms-api  && ./mvnw -B clean verify            # must stay BUILD SUCCESS, Skipped: 0
cd frontend/tms-web && npm run lint && npm run typecheck && npm run test && npm run build
```

Test counts must go **up or stay flat**. A step that lowers them has removed or weakened a test
and is not done. Docker must be running, otherwise the integration tests report as *skipped* and
the run proves nothing.

---

## Now - before anyone touches a feature

### 0. Stop the `local` profile from adopting a neighbouring database  (P1-2, minutes)

`application-local.yml:11` defaults to `jdbc:postgresql://localhost:54322/postgres`. On this
machine that port belongs to **another project's** Supabase stack (`supabase_db_eSupplier`), so
starting the backend with the `local` profile and no `TMS_DB_URL` connects successfully to the
wrong database and lets Flyway create schema `tms` inside it. Remove the default so a missing
`TMS_DB_URL` fails fast, or move TMS's local stack to its own port range in `supabase/config.toml`
and update `README.md` and `supabase/README.md` with it.

**Gate:** starting the app with the `local` profile and no `TMS_DB_URL` fails with a clear message
instead of starting; standing gate green.

### 1. Make first login possible without guesswork  (P1-1, hours)

`supabase/seeds/local_dev_seed.sql:18-19` states that a seeded user with a NULL `auth_user_id` is
mapped at first login. The backend does no such thing: `JdbcIdentityRepository.PROFILE_SQL` matches
on `auth_user_id` only. Following the seed as written produces a `401` with no explanation.

- Correct the comment; document the explicit
  `UPDATE tms.app_user SET auth_user_id = '<auth.users uuid>' WHERE email = ...` step in
  `supabase/README.md`.
- Add a startup WARN when `tms.security.cors.allowed-origins` is empty (P3-1) - the failure is
  currently silent and looks like a broken frontend.

**Gate:** a new assertion in `LocalSeedIntegrationTest` proving a NULL `auth_user_id` user does not
resolve, so the document and the code can no longer disagree; standing gate green.

---

## Next - required before any pilot with real users

### 2. IAM admin module  (P1-1, 1 overnight batch)

Users, memberships and roles are provisioned only by hand-written SQL today. The `iam.*`
permissions already exist in the catalogue, so **no migration is needed**.

- `GET /iam/users`, `POST /iam/users`, `POST /iam/users/{id}/memberships`,
  `POST /iam/users/{id}/deactivate`.
- Replace the `/admin/security` placeholder with a real screen.

**Gate:** integration tests covering - a company admin cannot reach another organization's users;
a company-scoped membership cannot be granted an `ORGANIZATION` scope-level role (the rule
`JdbcIdentityRepository.COMPANY_PERMISSIONS_SQL` enforces on read must be refused on write too);
deactivation revokes access on the very next request. Frontend tests for the screen.

### 3. Finish the two incomplete masters  (P2-2, P2-5, small)

- Paginate `GET /masterdata/frequencies/{id}/exceptions` - it is the only unpaged collection in
  the API.
- Give the `size: 200` lookup dropdowns a server-side search parameter, or at minimum a visible
  "showing the first 200" notice. Affected: `PlanningRunFormModal`, `CreateTripModal`,
  `TripVehicleModal`, `RoutesPage`, `DestinationsPage`, `EligibleOrdersPanel`.

**Gate:** the exceptions endpoint returns the same `PageResponse` shape as every other list and is
covered by `PagingConventionsTest`; a frontend test proves the truncation notice appears when the
result count equals the page size.

---

## Then - product value, in this order

### 4. Trip list across planning runs  (P2-3, 1 batch)

`/trips` is a placeholder and there is no cross-run trip query. A dispatcher asking "every
confirmed trip for tomorrow" currently has to open each run.

- `GET /planning/trips` with company, date-range, status and vehicle filters, paged.
- The `/trips` screen.

**Gate:** tenant isolation covered exactly as the other list endpoints are (`403` on a foreign
company id, `404` when correctly scoped to a foreign resource, `totalElements = 0` on lists); a
query-count test proving the list does not N+1 on stops, in the shape of
`PlanningApiIntegrationTest.boardQueryCountDoesNotGrowWithTheNumberOfTrips`.

### 5. Manual planning UX pass  (1 batch, frontend-only)

The planning domain is stable; the screen is where the planner's day is spent.

- Multi-select assign instead of one order at a time.
- Capacity warning *before* the drop, not after.
- Keyboard-first move between trips.
- Printable/exportable trip manifest.

**Gate:** a frontend test per interaction; **backend counts unchanged** - if the backend total
moves, this batch has quietly become a backend change and needs its own review.

### 6. Bulk order import and idempotent intake  (P2-1, 1 batch)

This is what turns TMS from a data-entry tool into something an ERP can feed. The database already
has the right key: a partial unique index on `(company_id, external_source, external_reference)`.

- An identical `(source, reference)` replay returns the existing order instead of `409`.
- `POST /orders/bulk` with a bounded batch size and per-item results.

**Gate:** posting the same payload twice yields one row and two success responses; a mixed
valid/invalid batch reports per-row outcomes and leaves **no partial write** behind a rejected row;
`OrderConstraintIntegrationTest` still passes untouched.

### 7. Drivers - only when someone asks who is driving

A `driver` master plus a nullable `trip.driver_id` is a small migration. It blocks nothing above
and should not be built speculatively.

**Gate:** if built - new migration `V13`, no edit to any applied migration, and `MigrationConventionTest`
still green.

### 8. Planning Automatic V1 - a heuristic, not an optimizer  (2 batches)

- Group `READY_FOR_PLANNING` orders by destination/zone; fill trips by capacity in priority then
  service-date order; stop at the vehicle limit.
- The result is a **DRAFT** run the planner edits, never an auto-confirmed one.
- Reuse `PlanningCapacityService` so manual and automatic can never disagree about capacity.
- `PlanningMode.AUTOMATIC` finally gets a producer.

**Gate:** a deterministic fixture producing a known assignment; an explicit "these orders could not
be placed, and why" output that is asserted, not just logged; every manual planning test still
passes unchanged, proving the automatic path is additive.

---

## Later - each needs a concrete requirement and an ADR first

### 9. OR-Tools route optimization

**Not before item 8 has been used in production for a while.** A solver needs stable distance and
time inputs, a stable capacity model, and real historical runs to validate against. Adding it to a
domain that is still moving buys a fast answer to the wrong problem.

**Gate:** an ADR that states the objective function, the data it requires, and how a solver result
is validated against a human plan - written and accepted *before* any dependency is added.

### 10. EWM integration contracts

Define the contract first: events or an API, with a published schema and a versioning rule.
**Never** a shared internal table and never a cross-product foreign key - that boundary is the
reason TMS is a separate product.

**Gate:** an ADR plus a contract document; the first implementation may not add any column that
points at an EWM identifier without one.

### Explicitly not now

GPS/telematics, Kafka, microservices, event sourcing, Supabase Realtime, Storage, live map
tracking. Each needs a concrete requirement and an ADR, per `CLAUDE.md`.

---

## Rules that do not change

- Flyway owns every application-schema change. Applied migrations are immutable - add `V13`, never
  edit `V1..V12`.
- React talks to Spring Boot. Supabase from the browser is authentication only.
- Authorization is server-side: a screen that hides a button is not a permission.
- Every vertical slice is reviewed `UI -> API client -> Controller -> Service -> Repository -> DB
  -> Security -> Tests` before it is called done.
- No push, no remote or shared database mutation, without an explicit human decision.
