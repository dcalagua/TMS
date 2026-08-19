# TMS by EBIM - Final overnight report and morning handoff

Step 14. Date: 2026-08-19. Attempt: 1.

This is an audit of the repository as it actually stands, not a summary of what the overnight
prompts asked for. Every number below was produced by a command run in this step, or is quoted
from an earlier step's record and labelled as such. Where the two could differ, the number from
this step wins.

Audited state: branch `main`, HEAD `7aa4ffe`, working tree clean before this step's two new
documents.

---

## 1. Executive status

**Overall: GREEN.** Everything built was verified by execution in this step, no P0 exists, and no
remote or shared system was touched. GREEN is not "nothing left to do": there are two **P1**s, and
neither is a defect in the delivered features - (1) a person cannot be onboarded without hand-written
SQL, and (2) the `local` profile's default database URL will silently adopt whichever Supabase stack
owns port 54322. Both are in section 9 with concrete fixes, and both are small.

**What is genuinely usable today.** A single-tenant-safe, multi-company transport planning core
that a planner can operate end to end in a browser:

- sign in with Supabase Auth, pick a company, and have every subsequent call authorized
  server-side against that company's permissions;
- maintain Origins, Zones, Destinations, Frequencies (with weekly rules and date exceptions) and
  Routes (with ordered stops);
- maintain Carriers, Vehicle Types and Vehicles, with capacity resolved from the type and
  overridable per vehicle;
- capture Transport Orders with lines, let the server compute weight/volume/pallet totals, and
  move them `NOT_READY -> READY_FOR_PLANNING -> PLANNED` or `CANCELLED`;
- open a manual Planning Run for an origin and a date, create Trips inside it, attach a vehicle,
  drag eligible orders onto trips, watch live capacity utilisation per dimension, move an order
  between trips, and confirm the run - which freezes a capacity snapshot and locks the trips.

All of that is exercised by 324 backend tests against a real PostgreSQL/PostGIS and 219 frontend
tests, all passing in this step (section 7), including a thirteen-test end-to-end smoke flow that
crosses masterdata, fleet, orders and planning in one chain.

**What is scaffold only.**

- `/trips` and `/admin/security` are honest placeholder screens ("Coming soon"), routed and
  navigable but with no feature behind them. Trips are fully functional *inside* a planning run;
  what does not exist is a standalone trip list across runs.
- Automatic planning: `PlanningMode.AUTOMATIC` exists in the enum and the column, and nothing
  produces it. `PlanningRunRequest` deliberately does not accept a mode, so no client can create
  a run that claims to be automatic. This is a correctly closed door, not a half-built feature.
- IAM has read-only endpoints (`/me`, `/companies/current`) and no management surface. Users,
  memberships and companies are provisioned with SQL. See finding P1-1.
- Drivers, telematics, EWM/ERP integration, OR-Tools, Realtime and Storage: **not started, by
  decision.** No stub, no dead code, no unused table.

**Remote and shared systems: untouched, and provable.** No push exists (section 8). No remote or
shared database was contacted in any step; every database used was a container created and
destroyed inside the run. The one piece of pre-existing local infrastructure on the machine - a
local Supabase stack that was already running before the overnight work started, and which this
audit identified as belonging to a **different project** (`supabase_*_eSupplier`) - was deliberately
not read from, written to or restarted (Step 13, `TEST_EVIDENCE.md` section 8.2). That stack
occupies the ports TMS's own local configuration expects, which is a foot-gun for the first
developer who runs the backend locally: see finding P1-2.

---

## 2. Architecture implemented

```
                    Browser
                       |
  +--------------------+-----------------------------------------------+
  |  React 19 + TypeScript + Vite + Bootstrap 5 + SweetAlert2           |
  |  frontend/tms-web                                                   |
  |                                                                     |
  |  AuthContext -------------------------------> Supabase Auth (JWT)   |  (1) sign in
  |    supabase.auth.signInWithPassword / getSession / onAuthStateChange |      ONLY auth
  |                                                                     |
  |  CompanyContext  -> selected company id                             |
  |  httpClient.ts   -> Authorization: Bearer <supabase jwt>            |
  |                     X-Company-Id: <company uuid>                    |
  +---------------------------------|-----------------------------------+
                                    |  (2) every business call, always
                                    v
  +--------------------------------------------------------------------+
  |  Spring Boot 4 / Java 21   backend/tms-api                          |
  |                                                                     |
  |  CorrelationIdFilter        -> correlation id in MDC before security |
  |  BearerTokenAuthFilter      -> JWKS signature, iss, aud, exp        |  (3) validate
  |  TmsJwtAuthenticationConverter -> PrincipalLoader                   |
  |       -> PrincipalResolutionService (iam)                           |  (4) resolve
  |          resolves app_user + memberships + permissions              |      server-side
  |  CompanyScopeFilter         -> X-Company-Id must be a membership;   |  (5) scope
  |                                authorities = that company's perms   |
  |  @PreAuthorize('<module>.<resource>:<action>')                      |  (6) authorize
  |                                                                     |
  |  api/ (controllers)  ->  application/ (services, use cases)         |
  |                              ->  infrastructure/ (repositories)     |
  |  cross-module access only through shared/reference ports            |
  +---------------------------------|-----------------------------------+
                                    |  JDBC, always company-scoped
                                    v
  +--------------------------------------------------------------------+
  |  PostgreSQL 17 + PostGIS 3.5 (Supabase platform)                    |
  |  schema tms - 25 tables, RLS enabled, 0 policies (deny-all)         |
  |  Flyway V1..V12 is the only owner of application DDL                |
  |  Supabase Data API cannot see schema tms (ADR-004)                  |
  +--------------------------------------------------------------------+
```

Auth flow in words: the browser gets a JWT from Supabase Auth and never uses Supabase for
anything else. Spring Boot validates that token against Supabase's JWKS, then answers the two
questions Supabase cannot: *which TMS user is this* and *which companies may they act in*. Those
answers come from `tms.app_user` and `tms.membership`, resolved per request, and become the
request's authorities. RLS sits underneath as a deny-all backstop for any connection that is not
the application.

---

## 3. Supabase vs Java ownership - intended versus implemented

| Concern | Intended owner | What the code actually does | Verdict |
|---|---|---|---|
| Authentication (credential, session, JWKS) | Supabase | `AuthContext.tsx` calls exactly six `supabase.auth.*` methods and nothing else; `SupabaseJwtDecoders` validates signature, issuer, audience and expiry with skew | **As intended** |
| Identity -> TMS user | Java | `PrincipalResolutionService` + `JdbcIdentityRepository.PROFILE_SQL` map `auth_user_id` to an active `tms.app_user` | **As intended** |
| Tenancy / company scope | Java | `CompanyScopeFilter` validates `X-Company-Id` against resolved memberships before any controller runs; every repository query carries `company_id` | **As intended** |
| Authorization | Java | 32 permissions, `@PreAuthorize` on **73 of the 75** endpoints; the two without are `/system/info` (public by design) and `/me` (self-information - authenticated, permission-free). Capabilities are UI-only and never enforced | **As intended** |
| Application schema | Flyway (Java side) | 12 migrations, 25 tables. `supabase/` holds `config.toml`, a README and one hand-applied local seed. `db.migrations.enabled = false`, no `supabase/migrations` directory | **As intended**, enforced by `MigrationConventionTest.supabaseCarriesNoParallelMigrationHistory` |
| Business rules | Java | Migrations contain constraints, indexes and one `set_updated_at` trigger. No RPC, no Edge Function, no stored business procedure anywhere in the repo | **As intended** |
| Row-level security | Supabase/PostgreSQL as defence in depth | 25/25 tables `ENABLE ROW LEVEL SECURITY` with **0 policies**; `REVOKE ALL` from `PUBLIC` and from the Supabase API roles (V4) | **As intended**, and deliberately *not* used as the authorization mechanism |
| PostGIS | Supabase | Installed in V1, used for origin/destination geography columns | **As intended** |
| Business data access from React | Java only | `grep 'supabase\.' frontend/tms-web/src` returns 6 hits, all `supabase.auth.*`. All data goes through `httpClient.ts` -> `/api/v1` | **As intended** |

**Where implementation is stricter than the document.** `FORCE ROW LEVEL SECURITY` is *not* set
(V4 line 62) because the backend connects as the table owner and would bypass it anyway; the
decision is written into the migration rather than left implicit. And `Capability` is derived,
never stored or granted - the UI can only ever hide things, never unlock them.

**Where implementation is thinner than the document.** Nothing material. The one asymmetry worth
naming is that `docs/security/AUTHORIZATION_MODEL.md` describes an IAM permission family
(`iam.*`, 8 permissions) that has no endpoint behind it yet - the catalogue is ahead of the API on
purpose, so adding the admin module later needs no migration.

---

## 4. Modules inventory

Endpoint counts are `@(Get|Post|Put|Patch|Delete)Mapping` occurrences per controller. Test counts
are `<testcase>` elements in this step's Surefire reports; where a class covers two modules the
count is shown once and marked shared.

| Module | DB | API | UI | Tests | Status |
|---|---|---|---|---|---|
| **Origins** | `tms.origin` (V6), PostGIS point, company-scoped unique code | `/masterdata/origins` - 6 | `OriginsPage` + `OriginFormModal` | `OriginZoneApiIntegrationTest` 12 (shared), `MasterDataConstraintIntegrationTest` 6 (shared), 2 FE files | **COMPLETE** |
| **Zones** | `tms.zone` (V6) | `/masterdata/zones` - 6 | `ZonesPage` + `ZoneFormModal` | shared with Origins, 2 FE files | **COMPLETE** |
| **Destinations** | `tms.destination` (V7), FK to zone with composite tenant key | `/masterdata/destinations` - 6 | `DestinationsPage` + `DestinationFormModal` | `DestinationFrequencyApiIntegrationTest` 19 (shared), `MasterDataDestinationFrequencyConstraintIntegrationTest` 10 (shared), 2 FE files | **COMPLETE** |
| **Frequencies** | `tms.frequency`, `frequency_weekly_rule`, `frequency_exception` (V7) | `/masterdata/frequencies` - 9 (incl. 3 exception endpoints) | `FrequenciesPage` + `FrequencyFormModal` | shared with Destinations, 2 FE files | **COMPLETE** (exceptions endpoint unpaginated - P2-2) |
| **Routes** | `tms.route`, `tms.route_stop` (V8), ordered stops | `/masterdata/routes` - 6 | `RoutesPage` + `RouteFormModal` | `RouteApiIntegrationTest` 13, `MasterDataRouteConstraintIntegrationTest` 14, 2 FE files | **COMPLETE** |
| **Carriers** | `tms.carrier` (V9) | `/fleet/carriers` - 6 | `CarriersPage` + `CarrierFormModal` | `FleetApiIntegrationTest` 19 (shared), `FleetConstraintIntegrationTest` 16 (shared), 2 FE files | **COMPLETE** |
| **Vehicle Types** | `tms.vehicle_type` (V9), weight/volume/pallet limits | `/fleet/vehicle-types` - 6 | `VehicleTypesPage` + `VehicleTypeFormModal` | shared with Carriers, 2 FE files | **COMPLETE** |
| **Vehicles** | `tms.vehicle` (V9), FK to type and carrier, per-vehicle capacity override | `/fleet/vehicles` - 6 | `VehiclesPage` + `VehicleFormModal` | shared + `EffectiveCapacityResolverTest` 4, 2 FE files | **COMPLETE** |
| **Orders** | `tms.transport_order`, `tms.transport_order_line` (V10); server-computed totals; partial unique index on `(company_id, external_source, external_reference)` | `/orders` - 6 (list, get, create, update, mark-ready, cancel) | `OrdersPage` + `OrderFormModal` | `OrderApiIntegrationTest` 17, `OrderConstraintIntegrationTest` 19, 2 FE files | **COMPLETE** for manual entry; **PARTIAL** for integration intake - no bulk import, and a duplicate external reference answers `409` instead of returning the existing order (P2-1) |
| **Planning Run** | `tms.planning_run` (V11), one open run per (company, origin, date) | `/planning` - 7 | `PlanningRunsPage`, `PlanningRunFormModal`, `PlanningBoardPage` | `PlanningApiIntegrationTest` 25 (shared), `PlanningConstraintIntegrationTest` 17 (shared), `PlanningCapacityServiceTest` 7, 8 FE files (shared) | **COMPLETE** for MANUAL mode |
| **Trips** | `tms.trip`, `tms.trip_stop`, `tms.trip_order_assignment` (V11); assignment is its own aggregate, `transport_order` carries **no** `trip_id` | `/planning/trips` - 8 (create, get, vehicle, assign, move, remove, capacity, cancel) | `TripCard`, `TripDetailDrawer`, `CreateTripModal`, `TripVehicleModal` - all inside the planning board. Standalone `/trips` route is a **placeholder** | shared with Planning Run | **PARTIAL** - complete inside a run, no cross-run trip list (P2-3) |

Supporting, outside the eleven: `/me` (1), `/companies/current` (1), `/system/info` (1),
`EndToEndSmokeIntegrationTest` (13), `ApiSecurityTest` (24), and the architecture, migration and
schema-exposure suites.

**75 endpoints across 14 controllers. 211 backend main sources, 38 backend test sources,
112 frontend sources of which 36 are test files.**

---

## 5. Data model

25 application tables in schema `tms`, created by 12 Flyway migrations. 52 explicit
`CREATE INDEX` statements; the migrated database reports **116 indexes** in total once primary-key
and unique-constraint indexes are counted (measured in Step 13's independent replay).

### Tables by migration

| Migration | Tables |
|---|---|
| V2 identity and tenancy | `organization`, `company`, `app_user`, `role`, `permission`, `role_permission`, `membership`, `membership_role` |
| V6 origins and zones | `origin`, `zone` |
| V7 destinations and frequencies | `destination`, `frequency`, `frequency_weekly_rule`, `frequency_exception` |
| V8 routes | `route`, `route_stop` |
| V9 fleet | `carrier`, `vehicle_type`, `vehicle` |
| V10 orders | `transport_order`, `transport_order_line` |
| V11 manual planning | `planning_run`, `trip`, `trip_stop`, `trip_order_assignment` |

V1 installs PostGIS and shared helpers, V3/V5 seed the role and permission catalogue, V4 applies
grants and RLS, V12 adds three performance indexes.

### Key foreign keys

Tenancy backbone:

- `company.organization_id -> organization.id`
- `membership.app_user_id -> app_user.id`, `membership.(company_id, organization_id) -> company.(id, organization_id)` - a composite FK, so a membership can never point at a company of another organization
- `membership_role.membership_id -> membership.id`, `... role_id -> role.id`
- `role_permission.(role_id, permission_id)`

Every business table carries `company_id -> company.id ON DELETE RESTRICT`, and every
cross-business FK is **composite on `(id, company_id)`**, which makes "a trip of company A can
never reference an order of company B" a database fact rather than only a service check:

- `destination.(zone_id, company_id) -> zone.(id, company_id)`
- `route.(origin_id, company_id)`, `route.(frequency_id, company_id)`, `route_stop.(destination_id, company_id)`, `route_stop.route_id -> route.id ON DELETE CASCADE`
- `vehicle.(vehicle_type_id, company_id)`, `vehicle.(carrier_id, company_id)`
- `transport_order.(origin_id, company_id)`, `transport_order.(destination_id, company_id)`, `transport_order_line.transport_order_id ON DELETE CASCADE`
- `trip.(planning_run_id, company_id)`, `trip.(vehicle_id, company_id)`, `trip.(carrier_id, company_id)`
- `trip_stop.trip_id ON DELETE CASCADE`, `trip_stop.(destination_id, company_id)`
- `trip_order_assignment.(trip_id, company_id)`, `trip_order_assignment.(transport_order_id, company_id)`

Audit columns `created_by` / `updated_by -> app_user.id ON DELETE RESTRICT` exist on every
business table.

### Indexes that matter

| Index | Table | Why it exists |
|---|---|---|
| `ix_transport_order_planning_pool` (partial) | `transport_order` | the eligible-orders query; 5,051 buffers -> 28 at 900k orders |
| `ix_transport_order_company_status_service_date` | `transport_order` | order list with a selective status filter; 20,275 buffers -> 28 |
| `ix_trip_planning_run_number` | `trip` | ordered board read without a sort |
| `ix_membership_app_user_active` | `membership` | drives identity resolution on every request |
| one unique index per master on `(company_id, code)` | all masters | tenant-scoped natural keys |
| partial unique on `(company_id, external_source, external_reference)` | `transport_order` | integration idempotency guard |

Deliberate absences worth knowing: no `trip_id` column on `transport_order` (the link is
`trip_order_assignment`, with the rejection written into V11 as a comment), and no index on
`created_by` / `updated_by` (P3-2).

---

## 6. Security

**JWT.** Bearer only; no form login, no HTTP Basic, no session, no cookie, no
profile-conditional relaxation of the chain. `SupabaseJwtDecoders` validates signature against
Supabase's JWKS (cached, so key rotation needs no redeploy), plus issuer, audience, subject and
expiry with clock skew. Forged, expired, wrong-issuer and wrong-audience tokens are each covered
by a test in `ApiSecurityTest` (24 tests).

**Identity and company isolation.** A valid token is not yet a principal: `PrincipalResolutionService`
must find an **active** `tms.app_user` for that `auth_user_id`, and no row means `401`
unprovisioned. `CompanyScopeFilter` then requires `X-Company-Id` to be a company the caller holds
a membership in, and the request's authorities become *that company's* permissions - not the
union across companies. Deactivating a membership, organization or company revokes access,
because rows are never deleted. Cross-tenant attempts are logged at WARN with the correlation id.

**Permissions.** 32 permissions in a `<module>.<resource>:<action>` catalogue, seeded by V3/V5 and
mapped to four roles (`ORGANIZATION_ADMIN`, `COMPANY_ADMIN`, `PLANNER`, `VIEWER`). Every business
endpoint carries `@PreAuthorize` - 73 annotations across 12 controllers; multi-resource endpoints require *both* permissions (for example
the board needs `planning.plan:read` **and** `planning.trip:read`). `Capability` is a UI-only
derivation used for menu visibility and is never enforced anywhere - hiding a button changes
nothing about what a caller may do.

**Isolation proven, not assumed.** The smoke suite drives a second, fully legitimate user of a
different company against the first company's records: `403` when presenting the other company's
id, `404` on every read and every mutation when correctly scoped to their own, `totalElements = 0`
on list endpoints, and a re-read of the database confirming nothing moved.

**RLS.** All 25 tables have RLS enabled with zero policies, `PUBLIC` and the Supabase API roles
are revoked, and schema `tms` is invisible to the Supabase Data API (ADR-004,
`SchemaExposureIntegrationTest`). It is a backstop, not the authorization mechanism.

**Errors and logs.** RFC 9457 documents that never name a missing permission, never carry a stack
trace and never echo a hostile correlation id. Correlation id is established before the security
chain, so even a 401 is traceable. No PII in logs - users are identified by UUID, never email.

**Known gaps (none is an authorization hole).**

| Gap | Where | Consequence |
|---|---|---|
| No in-product user/membership provisioning | no IAM write endpoints | onboarding requires SQL - P1-1 |
| Two SQL statements per authenticated request, no cache | `JdbcIdentityRepository` | fixed cost per call at high concurrency - P2-4 |
| `FORCE ROW LEVEL SECURITY` not set | `V4` line 62 | a superuser/owner connection bypasses RLS; documented decision |
| Actuator `metrics` unexposed | `application.yml` | no per-route metrics until a `system.*` permission or a separate management port exists |
| CORS allow-list empty by default | `TmsSecurityProperties` | a deployment that forgets to configure origins serves no browser - the safe failure, but it *will* look like a bug on first deploy |

---

## 7. Test evidence

Run in this step, on this machine, with Docker Engine 29.6.2 running - so every Testcontainers
test executed for real rather than skipping.

**Backend** - `cd backend/tms-api && ./mvnw -B clean verify`

```
[INFO] Tests run: 324, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:03 min
```

Artifact produced: `target/tms-api-0.1.0-SNAPSHOT.jar`, 69,909,055 bytes. **Skipped = 0** is the
evidence that the integration tests ran; they are gated on `@EnabledIf(DockerAvailability.CONDITION)`
and would report as skipped, never as passed, on a machine without a daemon.

31 test classes. The largest: `PlanningApiIntegrationTest` 25, `ApiSecurityTest` 24,
`FleetApiIntegrationTest` 19, `DestinationFrequencyApiIntegrationTest` 19,
`OrderConstraintIntegrationTest` 19, `PlanningConstraintIntegrationTest` 17,
`OrderApiIntegrationTest` 17, `FleetConstraintIntegrationTest` 16, `MasterDataRouteConstraintIntegrationTest` 14,
`SupabaseJwtDecodersTest` 14, `RouteApiIntegrationTest` 13, `EndToEndSmokeIntegrationTest` 13.
Architecture rules: `LayeringTest` 7 + `ModuleBoundaryTest` 3, all passing.

**Frontend** - `cd frontend/tms-web && npm run lint && npm run typecheck && npm run test && npm run build`

| Stage | Exit | Result |
|---|---|---|
| `lint` (oxlint) | 0 | 2 warnings, both pre-existing `react(only-export-components)` at `AuthContext.tsx:112` and `CompanyContext.tsx:119` |
| `typecheck` (`tsc -b`) | 0 | clean |
| `test` (vitest) | 0 | **36 test files, 219 tests, 219 passed, 0 failed** |
| `build` (`tsc -b && vite build`) | 0 | built in 427 ms; one advisory warning - the JS chunk exceeds Vite's 500 kB pre-gzip threshold |

**Consistency with Step 13.** Identical: 324 backend, 219 frontend. Nothing drifted between the
two runs, and no test was added, removed or weakened in this step.

**What is still not proven by execution.** Driving a *booted* server over HTTP - this environment
denies outbound requests from the shell, including to loopback. The jar boots and Flyway applies
the full history on the real startup path (Step 13 section 1.4), and the whole request path is
exercised through MockMvc against a real database, but Tomcat-served filter ordering, TLS/proxy
behaviour and actuator exposure as served in production remain unverified here. Recorded as an
environment blocker, not as a pass.

---

## 8. Git evidence

| Item | Value |
|---|---|
| Branch | `main` |
| HEAD before this step | `7aa4ffe` - *chore(tms): overnight 13 13 full verification* |
| Working tree at audit time | **clean** (`git status --porcelain` empty) |
| Remote configured | `origin` -> `git@github.com:dcalagua/TMS.git` |
| Remote-tracking refs | **none** - `.git/refs/remotes` is empty and `packed-refs` holds 0 remote refs |
| `FETCH_HEAD` | present but **0 bytes** |
| Upstream state | `origin/main: gone` |

**No push was performed.** Not in this step and not in any earlier one. The proof is structural
rather than testimonial: a repository that had ever pushed or fetched successfully would hold at
least one ref under `refs/remotes/origin/` and a non-empty `FETCH_HEAD`. It holds neither. Every
one of the 14 commits below is local-only.

Local checkpoint commits, oldest first:

```
63425d9  02:31  chore(tms): overnight 00 00 preflight and architecture   (initial)
3200fa7  02:53  chore(tms): overnight 01 01 repository bootstrap
b71f51a  03:26  chore(tms): overnight 02 02 database tenancy supabase
e09f145  04:11  chore(tms): overnight 03 03 backend security foundation
fd029a9  04:33  chore(tms): overnight 04 04 frontend foundation
cb9233e  05:12  chore(tms): overnight 05 05 masters origins zones
ebbda4d  05:38  chore(tms): overnight 06 06 masters destinations frequencies
05c982a  06:05  chore(tms): overnight 07 07 masters routes
d7d9b5d  06:33  chore(tms): overnight 08 08 fleet masters
d2d2e97  07:08  chore(tms): overnight 09 09 orders
68ff73e  07:49  chore(tms): overnight 10 10 manual planning backend
2c2d0f5  08:09  chore(tms): overnight 11 11 manual planning frontend
f7928aa  08:34  chore(tms): overnight 12 12 quality security performance
7aa4ffe  08:56  chore(tms): overnight 13 13 full verification
```

No destructive Git operation was run at any point: no `reset --hard`, no `rebase`, no
`push --force`, no `clean -fd`, no branch or tag deletion. The reflog is a straight line of 14
commits with no rewrite. `tms-overnight-pack/` is excluded through `.git/info/exclude` and has
**0 tracked files**, as required.

Also verified: no `.env` with real values is tracked (only two `.env.example` files), no
`target/`, `dist/` or `node_modules/` is tracked, and no secret pattern appears in any tracked
file.

---

## 9. Findings

Severity: **P0** exploitable or broken now; **P1** broken under a plausible condition, or blocks
real use; **P2** hardening with real value; **P3** noted, no action justified yet.

### P0 - none

No authorization hole, no cross-tenant leak, no data-loss path, no failing test. The Step 12
review's P0/P1 list was fixed and the fixes are covered by regression tests that were verified to
fail without them.

### P1-1 - A real user cannot be onboarded without direct SQL access

- **Where:** no write endpoints in `iam` (`MeController`, `CompanyContextController` are read-only);
  `backend/tms-api/src/main/java/com/ebim/tms/iam/infrastructure/JdbcIdentityRepository.java:24`;
  `supabase/seeds/local_dev_seed.sql:39`.
- **Impact:** onboarding a person requires (a) creating the Supabase Auth user in Studio, then
  (b) inserting `tms.app_user` with the resulting `auth_user_id`, then (c) inserting `membership`
  and `membership_role` rows - all by hand, in SQL, against the database. Any pilot with more than
  a handful of users needs a DBA in the loop for every hire, every role change and every offboard.
- **Compounding defect:** `local_dev_seed.sql:18-19` tells the reader they may "leave them NULL and
  let the backend map them at first login, which is what Step 03 implements". **It does not.**
  `PROFILE_SQL` matches on `auth_user_id` only; there is no email fallback and no just-in-time
  provisioning. The three seeded demo users are inserted with `(email, full_name)` and a NULL
  `auth_user_id`, so following the seed's own instructions produces a `401` on first login with no
  hint as to why. First contact with the product on a fresh machine fails, and the document that
  explains the failure says the opposite.
- **Fix:** either a minimal `iam` admin module (users, memberships, roles - 3 endpoints and a
  screen), or, as a stopgap this week, correct the seed comment and add the `UPDATE tms.app_user
  SET auth_user_id = ...` step to `supabase/README.md`. The comment fix is fifteen minutes and
  should not wait for the module.

### P1-2 - The `local` profile's default database URL points at whatever Supabase stack owns port 54322

- **Where:** `backend/tms-api/src/main/resources/application-local.yml:11` -
  `url: ${TMS_DB_URL:jdbc:postgresql://localhost:54322/postgres}`; `supabase/config.toml:53`
  (`db.port = 54322`); `supabase/README.md:79`; `README.md:61`.
- **Impact:** on **this machine, right now**, port 54322 is held by a Supabase stack belonging to a
  different product (`supabase_db_eSupplier`). A developer who follows the README and starts the
  backend with `SPRING_PROFILES_ACTIVE=local` and no `TMS_DB_URL` does not get a connection error -
  they get a successful connection to *another project's database*, and Flyway then creates schema
  `tms` with 25 tables inside it. Nothing warns them; the application starts and looks healthy.
  That is exactly the cross-product mutation the independence rule exists to prevent, and the
  default makes it the path of least resistance.
- **This did not happen during the overnight run.** Every test builds its JDBC URL from the
  Testcontainers-assigned host and port
  (`backend/tms-api/src/test/java/com/ebim/tms/database/PostgresTestDatabase.java:79`), never from a
  fixed one, and the application was never started with the `local` profile against 54322.
- **Fix:** remove the default so `TMS_DB_URL` is mandatory and a missing value fails fast; or move
  TMS's local stack to a project-specific port range in `supabase/config.toml` and update the three
  documents. The first is one line and closes the hole for every developer; the second also fixes
  `supabase start` colliding with the other stack.

### P2-1 - Order intake has no bulk path and no idempotent replay

- **Where:** `backend/tms-api/src/main/java/com/ebim/tms/orders/api/OrderController.java:75`;
  `OrderApiIntegrationTest.duplicateExternalReferenceIsScopedToItsCompany`.
- **Impact:** the database already carries the right idempotency key - a partial unique index on
  `(company_id, external_source, external_reference)` - but the API surfaces it as a `409` rather
  than returning the existing order. A retrying ERP that times out after the insert commits gets a
  conflict it cannot distinguish from a genuine duplicate, and at 10,000 orders/day the only intake
  path is one HTTP call per order.
- **Fix:** return the existing order for an identical `(source, reference)` replay, and add
  `POST /orders/bulk` with per-item results.

### P2-2 - `GET /masterdata/frequencies/{id}/exceptions` is unpaginated

- **Where:** `backend/tms-api/src/main/java/com/ebim/tms/masterdata/api/FrequencyController.java:114`
  returns `List<FrequencyExceptionView>`.
- **Impact:** every other collection in the API is paged. A frequency accumulating years of
  blackout dates returns an unbounded array; the response grows without limit and no client can
  page it.
- **Fix:** page it like every other list. Breaking change to one endpoint with one consumer.

### P2-3 - Trips exist only inside a planning run

- **Where:** `frontend/tms-web/src/app/router.tsx:45` routes `/trips` to
  `PlaceholderPage`; the nav group `Trips` (`navConfig.ts`) leads there.
- **Impact:** a dispatcher wanting "all confirmed trips for tomorrow" has to open each planning run
  in turn. The backend has no cross-run trip query either, so this is not purely a screen: it needs
  a `GET /planning/trips` list endpoint with filters.
- **Fix:** one list endpoint plus one screen. Bounded, and the highest-value UI gap.

### P2-4 - Identity resolution costs two SQL statements on every authenticated request

- **Where:** `backend/tms-api/src/main/java/com/ebim/tms/iam/application/PrincipalResolutionService.java:47-55`.
- **Impact:** no cache. At the target of 100-300 vehicles and many concurrent planners, every call
  - including every board poll - pays two round trips before doing any work.
- **Fix:** a short-TTL per-token cache with explicit invalidation on membership change. Needs care:
  the current design's virtue is that revoking a membership takes effect on the next request.

### P2-5 - Lookup dropdowns silently truncate at 200 rows

- **Where:** `PlanningRunFormModal.tsx:32`, `CreateTripModal.tsx:32`, `TripVehicleModal.tsx:56`,
  `RoutesPage.tsx:68`, `DestinationsPage.tsx:75`, `EligibleOrdersPanel.tsx:63` - all
  `size: 200`.
- **Impact:** a company with more than 200 active destinations or vehicles gets a dropdown that
  quietly omits the rest. The user's failure mode is "the vehicle isn't in the list", which reads
  as data corruption rather than as a paging limit.
- **Fix:** a typeahead backed by a server-side search parameter, or at minimum a visible "showing
  first 200" notice.

### P2-6 - Single 882 kB JS bundle

- **Where:** `npm run build` output, unchanged since Step 12.
- **Impact:** 236 kB gzipped on first load for a dense internal application. Acceptable on a LAN,
  poor on a 3G tablet in a yard.
- **Fix:** route-level code splitting - the router is already the natural boundary.

### P3-1 - CORS allow-list is empty by default

- **Where:** `SecurityConfig.corsConfigurationSource` returns an empty source when
  `tms.security.cors.allowed-origins` is unset.
- **Impact:** correct and deliberate (fail closed), but the first deployment will present as "the
  frontend can't reach the API" with no server-side error. Worth a startup WARN.

### P3-2 - `created_by` / `updated_by` are unindexed

- **Where:** every business table, V6-V11.
- **Impact:** nothing today. It will matter the first time an audit screen asks "everything this
  user touched", and it will matter as a slow `RESTRICT` check if a user row is ever deleted -
  which cannot currently happen.

### P3-3 - `PlanningMode.AUTOMATIC` exists with no producer

- **Where:** `planning/domain/PlanningMode.java`, `tms.planning_run.mode`.
- **Impact:** none - `PlanningRunRequest` refuses to accept a mode and every run is created
  `MANUAL`. Noted only so that a future reader does not mistake the enum value for a working
  feature.

---

## 10. Recommended next work

In order. Each item assumes the ones above it are done.

1. **Close the two P1s.** Today: make `TMS_DB_URL` mandatory in the `local` profile (P1-2), fix
   the seed comment and document the `auth_user_id` step (P1-1). This week: a minimal IAM admin module - list/create users, attach a
   membership with roles, deactivate. The permission catalogue (`iam.*`) already exists, so this is
   endpoints, a screen and tests, with no migration.
2. **Finish the masters that are incomplete rather than starting new ones.** Only two items:
   paginate frequency exceptions (P2-2) and add server-side search to the lookup dropdowns
   (P2-5). Neither is a new module.
3. **Improve manual planning UX.** The domain is solid; the screen is where the planner's day is
   spent. In value order: the cross-run trip list (P2-3); multi-select assign rather than one order
   at a time; capacity warning *before* the drop rather than after; keyboard-first move; a printable
   or exportable trip manifest.
4. **Drivers - only if the operation needs them.** A `driver` master plus a nullable
   `trip.driver_id` is a small, well-understood migration. It is *not* a prerequisite for anything
   above, and it should not be built speculatively - build it when someone asks who is driving.
5. **Bulk order import and API idempotency (P2-1).** This is what turns TMS from a data-entry tool
   into a system that an ERP can feed. Return the existing order on replay, add `POST /orders/bulk`
   with per-row results, and add a rejected-rows report.
6. **Planning Automatic V1 - a heuristic, not an optimizer.** Group `READY_FOR_PLANNING` orders by
   destination/zone, fill trips by capacity in priority then service-date order, stop at the vehicle
   limit, and *propose* the result as a DRAFT run the planner edits. Reuse `PlanningCapacityService`
   as the single source of truth for capacity so manual and automatic can never disagree. Requires:
   a producer for `PlanningMode.AUTOMATIC`, and an explicit "these orders could not be placed, and
   why" output.
7. **OR-Tools - not before item 6 has been in real use.** Route optimization needs stable distance
   and time inputs, a stable capacity model and real historical runs to validate against. Adding a
   solver to a domain that is still moving buys a fast answer to the wrong problem. Requires an ADR.
8. **EWM integration contracts - last.** Define the contract first (events or an API, with a
   published schema and a versioning rule), never a shared table or a cross-product foreign key.
   Requires an ADR.

**Not now, and each still requires a concrete requirement plus an ADR:** GPS/telematics, Kafka or
microservices, event sourcing, Supabase Realtime, Storage, live map tracking.

---

## 11. Safe next overnight batches

Bounded, independently verifiable, and each one leaves the repository green. **None was executed
in this step.**

**Batch A - Onboarding and local-environment correctness (small, highest value).**
Remove the `localhost:54322` default from `application-local.yml` so a missing `TMS_DB_URL` fails
fast instead of connecting to a neighbouring project's database (P1-2); fix `local_dev_seed.sql`'s
inaccurate first-login comment; add the explicit `auth_user_id` update to `supabase/README.md`; add
a startup WARN when the CORS allow-list is empty.
*Gate:* backend 324/324 still pass; a new `LocalSeedIntegrationTest` assertion proves a seeded user
with a NULL `auth_user_id` does not resolve, so the documented behaviour and the code agree.

**Batch B - IAM admin module (medium).**
`GET/POST /iam/users`, `POST /iam/users/{id}/memberships`, `POST /iam/users/{id}/deactivate`,
guarded by the existing `iam.*` permissions, plus the `/admin/security` screen that replaces the
placeholder. No migration - the catalogue already exists.
*Gate:* new integration tests covering cross-organization refusal and the "a company admin cannot
grant an organization-scope role" rule; frontend tests for the screen; total counts rise, nothing
falls.

**Batch C - Trip list across runs (small-medium).**
`GET /planning/trips` with company, date-range, status and vehicle filters, paged like every other
list; the `/trips` screen replacing the placeholder.
*Gate:* the new endpoint is covered for tenant isolation exactly as the others are (403 on a
foreign company id, 404 when correctly scoped to a foreign resource, `totalElements = 0` on lists);
a query-count test proves the list does not N+1 on stops.

**Batch D - Order intake for integrations (medium).**
Idempotent replay on `(external_source, external_reference)` returning the existing order, and
`POST /orders/bulk` with per-item results and a bounded batch size.
*Gate:* a test that posts the same payload twice and asserts one row and two `200`s; a bulk test
with a mixed valid/invalid batch asserting partial success is reported per row and that no partial
write survives a rejected row.

**Batch E - Planning UX pass (medium, frontend-only).**
Multi-select assign, pre-drop capacity warning, keyboard move, "showing first 200" notices on every
truncated lookup.
*Gate:* frontend tests for each interaction; `npm run lint`, `typecheck`, `test` and `build` all
exit 0; no backend change, so backend counts must be unchanged.

**Batch F - Frontend bundle split (small).**
Route-level `lazy()` boundaries, `manualChunks` for vendor.
*Gate:* build succeeds with no chunk over 500 kB pre-gzip and the Vite advisory gone; 219/219
frontend tests still pass.

Each batch is one overnight step. Batches A, C, E and F are independent of one another; B should
precede any real pilot; D should follow B.

---

## 12. Final safety check

| Claim | Evidence |
|---|---|
| No push occurred | `.git/refs/remotes` empty, 0 remote refs in `packed-refs`, `FETCH_HEAD` 0 bytes, `origin/main: gone`. A repository that had ever pushed would hold a remote-tracking ref |
| No remote or shared database was mutated | Every database used in Steps 12-14 was a Docker container created and removed inside the run, with its JDBC URL taken from the container's assigned port (`PostgresTestDatabase.java:79`). The pre-existing local Supabase stack of the other project was neither read nor written. No connection string in the repository points anywhere but `localhost` or a container |
| No destructive Git operation | 14-commit linear reflog, no rewrite, no reset, no force |
| No secret exposed | Regex sweep over all tracked files: 0 matches. Only `.env.example` files are tracked |
| Applied migrations are immutable | `FlywayMigrationIntegrationTest.migrateIsIdempotentAndValidates` runs Flyway `validate`, which fails on any edited checksum. It passes |
| Flyway is the sole schema owner | No `supabase/migrations` directory; `db.migrations.enabled = false`; asserted by `MigrationConventionTest` |

All six hold. Status **GREEN**.
