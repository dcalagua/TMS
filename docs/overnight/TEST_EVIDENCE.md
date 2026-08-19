# TMS by EBIM - verification and smoke evidence

Step 13. Date: 2026-08-19. Attempt: 1. Result: **PASS**.

This document records what was **executed**, with the exact command and the exact numbers it
produced. Anything that was not run is listed in section 8 as blocked, with the reason. Nothing
here is reported green on the strength of having been written rather than run.

Environment: macOS (darwin 25.5.0, arm64), Java 21.0.9 (Homebrew), Node 22.17.0, npm 10.9.2,
Maven via the committed wrapper (`./mvnw`), Docker Engine 29.6.2 **running**. Because Docker was
available, every Testcontainers integration test executed for real against PostgreSQL/PostGIS;
none was skipped.

Starting state: clean working tree at `f7928aa` ("overnight 12 quality security performance"),
Steps 00-12 complete.

---

## 1. Backend verification

### 1.1 Command and result

```
cd backend/tms-api
./mvnw -B clean verify
```

```
[INFO] Tests run: 324, Failures: 0, Errors: 0, Skipped: 0
[INFO] Building jar: .../target/tms-api-0.1.0-SNAPSHOT.jar
[INFO] --- spring-boot:4.0.7:repackage (repackage) @ tms-api ---
[INFO] BUILD SUCCESS
[INFO] Total time:  01:00 min
```

**324 tests, 0 failures, 0 errors, 0 skipped.** `clean verify` covers compile, test, `jar` and
Spring Boot `repackage`, so packaging is verified by the same command: the executable jar is
produced at `target/tms-api-0.1.0-SNAPSHOT.jar` (69.9 MB, with nested dependencies in
`BOOT-INF/`). That the jar *runs* is verified separately in section 1.4 - a build that produces
an artifact nobody has started is not the same claim.

A baseline run of the same command *before* this step's only change produced **311 tests, 0
failures**. The delta is exactly the 13 tests of the new smoke class described in section 3; no
test was removed, weakened or disabled.

### 1.2 Per-class counts

Taken from `target/surefire-reports/TEST-*.xml` (counting `<testcase>` elements, which includes
tests inside `@Nested` classes).

| Test class | Tests | Time (s) |
|---|---:|---:|
| `architecture.LayeringTest` | 7 | 0.8 |
| `architecture.ModuleBoundaryTest` | 3 | 0.1 |
| `database.ApplicationDatabaseStartupIntegrationTest` | 1 | 1.2 |
| `database.FleetConstraintIntegrationTest` | 16 | 1.8 |
| `database.FlywayMigrationIntegrationTest` | 4 | 4.9 |
| `database.LocalSeedIntegrationTest` | 2 | 0.9 |
| `database.MasterDataConstraintIntegrationTest` | 6 | 1.2 |
| `database.MasterDataDestinationFrequencyConstraintIntegrationTest` | 10 | 1.5 |
| `database.MasterDataRouteConstraintIntegrationTest` | 14 | 2.0 |
| `database.MigrationConventionTest` | 7 | 0.0 |
| `database.OrderConstraintIntegrationTest` | 19 | 2.3 |
| `database.PlanningConstraintIntegrationTest` | 17 | 3.1 |
| `database.SchemaExposureIntegrationTest` | 6 | 1.0 |
| `database.TenancyConstraintIntegrationTest` | 10 | 2.2 |
| `fleet.api.FleetApiIntegrationTest` | 19 | 1.9 |
| `fleet.application.EffectiveCapacityResolverTest` | 4 | 0.0 |
| `iam.api.ApiSecurityTest` | 24 | 0.2 |
| `iam.infrastructure.IdentityResolutionIntegrationTest` | 10 | 1.7 |
| `masterdata.api.DestinationFrequencyApiIntegrationTest` | 19 | 2.0 |
| `masterdata.api.OriginZoneApiIntegrationTest` | 12 | 1.7 |
| `masterdata.api.RouteApiIntegrationTest` | 13 | 2.3 |
| `orders.api.OrderApiIntegrationTest` | 17 | 2.5 |
| `planning.api.PlanningApiIntegrationTest` | 25 | 10.4 |
| `planning.application.PlanningCapacityServiceTest` | 7 | 0.0 |
| `shared.api.DocumentationExposureTest` | 3 | 0.3 |
| `shared.api.PagingConventionsTest` | 10 | 0.0 |
| `shared.api.SystemInfoControllerTest` | 2 | 0.0 |
| `shared.audit.AuditActorProviderTest` | 4 | 0.0 |
| `shared.security.CapabilityTest` | 6 | 0.0 |
| `shared.security.SupabaseJwtDecodersTest` | 14 | 0.0 |
| **`smoke.EndToEndSmokeIntegrationTest`** (new) | **13** | **9.6** |
| **Total** | **324** | **55.7** |

31 classes. There is no separate Failsafe phase: integration tests run under Surefire and are
gated on Docker with `@EnabledIf(DockerAvailability.CONDITION)`, so on a machine without a
daemon they report as *skipped*, never as passed. On this run the skip count was **0**, which
is the evidence that they actually executed.

### 1.3 Architecture tests

**10 tests, 0 failures**, across two classes analysing main classes only
(`ImportOption.DoNotIncludeTests`), so the new test class cannot influence them.

`LayeringTest` (7): `controllers_must_not_reach_repositories`,
`controllers_must_not_use_persistence_apis`, `controllers_live_in_api_packages`,
`use_cases_live_in_application_packages`, `repositories_live_in_infrastructure_packages`,
`use_cases_must_not_depend_on_the_web_layer`, `dependencies_are_injected_through_constructors`.

`ModuleBoundaryTest` (3): `modules_are_free_of_cycles`,
`shared_must_not_depend_on_business_modules`, `business_modules_must_not_depend_on_each_other`.

### 1.4 The packaged jar actually boots

Run against a throwaway PostGIS container created for this check and removed after it, with the
real `local` profile - so this exercises the production startup path, not a test context.

```
docker run -d --rm -p 55432:5432 -e POSTGRES_USER=tms_boot -e POSTGRES_DB=tms_boot \
           -e POSTGRES_PASSWORD=<throwaway> postgis/postgis:17-3.5
SPRING_PROFILES_ACTIVE=local TMS_DB_URL=jdbc:postgresql://127.0.0.1:55432/tms_boot \
TMS_DB_USERNAME=tms_boot TMS_DB_PASSWORD=<throwaway> TMS_API_PORT=8081 \
  java -jar target/tms-api-0.1.0-SNAPSHOT.jar
```

The application's own log:

```
Current version of schema "tms": null
Migrating schema "tms" to version "1 - baseline schema extensions and helpers"
... (2 through 11) ...
Migrating schema "tms" to version "12 - performance indexes"
Successfully applied 12 migrations to schema "tms", now at version v12 (execution time 00:00.231s)
Tomcat started on port 8081 (http) with context path '/'
Started TmsApiApplication in 4.317 seconds (process running for 4.656)
```

So the repackaged fat jar starts, Flyway applies the **whole history to an empty database on the
real startup path** (`Current version: null` -> `v12`), and the web server binds. Afterwards the
database held 26 tables in schema `tms` - the 25 application tables plus Flyway's own
`flyway_schema_history`.

The application and the container were both stopped at the end of the run. **No HTTP request was
issued against this instance**; see section 8 item 1 for why, and what that does and does not
leave unproven.

---

## 2. Migration replay from an empty database

### 2.1 Inside the suite

`database.FlywayMigrationIntegrationTest` (4 tests, all passing) applies the history to
databases created fresh inside the disposable container:

- `migrationsApplyFromAnEmptyDatabase` - the whole history applies, in order, without failures;
- `migrateIsIdempotentAndValidates` - `validate` passes (which fails on any edited checksum, so
  this is also the *immutability* check) and a second `migrate` is a no-op;
- `replayIsDeterministic` - the history is applied to a **second** empty database and the two
  schema fingerprints (columns, constraints, indexes, triggers, reference rows) are identical;
- `postgisExtensionIsInstalled` - PostGIS is genuinely created, not inherited.

`database.MigrationConventionTest` (7 tests) additionally asserts contiguous versioning, no
destructive or role-management DDL, no writes to Supabase-managed schemas, no tenant/user data
in migrations, no grant to the Supabase API roles, and - directly relevant to section 6 -
`supabaseCarriesNoParallelMigrationHistory`.

### 2.2 Independent replay, outside the suite

Run separately so the migrations are proven to apply without Flyway, Spring or the test
harness in the picture. The database is created from `template1` on purpose: the
`postgis/postgis` image pre-installs the extension into its default database, so only a
template1-derived database makes V1's `CREATE EXTENSION` genuinely execute.

```
docker run -d --rm -e POSTGRES_PASSWORD=<throwaway> postgis/postgis:17-3.5
psql -h 127.0.0.1 -U postgres -d postgres -c 'CREATE DATABASE tms_replay TEMPLATE template1'
for f in V1..V12; do psql -v ON_ERROR_STOP=1 -U postgres -d tms_replay -q < $f; done
```

All twelve applied cleanly, in order:

```
V1__baseline_schema_extensions_and_helpers.sql      OK
V2__identity_and_tenancy.sql                        OK
V3__iam_reference_data.sql                          OK
V4__security_grants_and_rls.sql                     OK
V5__authorization_catalogue_completion.sql          OK
V6__masterdata_origins_zones.sql                    OK
V7__masterdata_destinations_frequencies.sql         OK
V8__masterdata_routes.sql                           OK
V9__fleet_masters.sql                               OK
V10__orders.sql                                     OK
V11__planning_manual.sql                            OK
V12__performance_indexes.sql                        OK
```

Resulting objects:

| Metric | Value |
|---|---:|
| Tables in schema `tms` | 25 |
| Indexes in schema `tms` | 116 |
| Tables in `tms` with RLS **enabled** | 25 |
| RLS **policies** in `tms` | 0 |
| Application tables in `public` | 0 |
| PostGIS version | 3.5.2 |

25 of 25 tables have RLS enabled with zero policies - the documented deny-all posture of
ADR-004 and `docs/security/RLS_STRATEGY.md`, not an oversight. The single table reported in
`public` by a raw count is PostGIS's own `spatial_ref_sys` (V1 installs the extension
`WITH SCHEMA public`); no application table lives there, which is what
`SchemaExposureIntegrationTest` asserts.

The container was stopped and removed at the end of the run. **No shared or remote database
was contacted at any point in this step.**

---

## 3. API smoke flow

### 3.1 How it was run, and why this way

The eleven-step flow was executed **end to end through the HTTP layer** by a new test,
`backend/tms-api/src/test/java/com/ebim/tms/smoke/EndToEndSmokeIntegrationTest.java`, against a
disposable PostGIS container.

A live "start the server and curl it" run of the eleven steps was **not** performed. The server
itself does start (section 1.4); what could not be done is drive it over HTTP. Two independent
reasons, both recorded as blocked items in section 8: this environment denies outbound HTTP
requests from the shell, so no endpoint could be probed at all; and obtaining a genuine bearer
token would mean creating users in the already-running local Supabase Auth container, which is
pre-existing local infrastructure this run did not create and has no authorization to mutate.

What the smoke test does instead is materially equivalent for everything the flow proves:

- requests go through the **production filter chain, security chain, controllers, services and
  repositories** - only the *source of the signing key* differs;
- tokens are genuinely RS256-signed by a keypair generated in-JVM and genuinely verified by the
  production claim validators (`SupabaseJwtDecoders.validator`: expiry with skew, issuer,
  audience, subject);
- the database is a real PostgreSQL/PostGIS with the real migrated schema;
- **every row except the tenants is created by calling the API.** The only SQL in the class
  seeds one organization, two companies, two users and their memberships - which are provisioned
  out of band, because TMS has no endpoint that creates a tenant, by design.

That last point is what makes this a smoke run rather than another module test: every other
integration test in the repository seeds its fixture with SQL and exercises one module. This one
proves the eleven steps **compose**.

### 3.2 Coverage, step by step

`./mvnw -B -Dtest=EndToEndSmokeIntegrationTest test` -> **13 tests, 0 failures, 9.5 s.**

| # | Brief step | Test | What it asserts |
|---|---|---|---|
| 1 | Authenticate | `authenticate` | A signed token resolves to `smoke.a@example.invalid` with exactly 1 company; the same call **without** a token is `401`, so authentication is real and not assumed |
| 2 | Select authorized company | `selectCompany` | `X-Company-Id` for the caller's own company returns it and the `planning.plan:manage` permission; the **other** company is `403` |
| 3 | Origin/Zone/Destination/Frequency/Route | `createMasterdata` | All five created via `POST`; destination carries the zone, route carries the origin and one stop |
| 4 | Carrier/Vehicle Type/Vehicle | `createFleet` | All three created via `POST`; the vehicle carries its type and carrier; type limits 12,000 kg / 45 m³ / 16 pallets |
| 5 | Order + line, mark ready | `createOrderAndMarkReady` | Order created `NOT_READY` with server-computed totals (5,000 kg, 18 m³, 10 pallets - weight/volume from quantity × unit, pallets from the line's own declared contribution per V10); `mark-ready` moves it to `READY_FOR_PLANNING`; it then appears in `GET /planning/eligible-orders` |
| 6 | Planning Run | `createPlanningRun` | Run opens `DRAFT` for that origin and date with 0 trips |
| 7 | Trip | `createTrip` | Trip 1 created `DRAFT` inside the run, with no vehicle and therefore *unlimited* capacity |
| 8 | Assign Vehicle | `assignVehicle` | `PUT /trips/{id}/vehicle` attaches vehicle and carrier; capacity source becomes `LIVE` with a 12,000 kg limit |
| 9 | Assign Order | `assignOrder` | Order assigned, stop created for its destination, order becomes `PLANNED`, eligible pool drops to 0, and **exactly one `ACTIVE` row exists in `trip_order_assignment`** |
| 10 | Verify capacity | `verifyCapacity` | `GET /trips/{id}/capacity`: `LIVE`, 1 order, 5,000/12,000 kg with 7,000 remaining, 18/45 m³, 10/16 pallets, `withinCapacity=true`, nothing exceeded |
| 11 | Confirm Planning Run | `confirmPlanningRun` | Run and trip become `CONFIRMED`, capacity source flips to `SNAPSHOT` retaining the 12,000 kg limit, and a replayed confirm is `409` |

### 3.3 Isolation smoke

Two further tests in the same class (12 and 13 of 13) prove the tenant line holds for a second,
fully legitimate user of a *different* company, against the records the flow above created.

`anotherCompanyCannotRead` - company B's planner:

- presenting **company A's** id in `X-Company-Id` is `403` at the scope filter (no membership);
- presenting **B's own** id - so the request is authenticated *and* correctly scoped, and
  reaches the service - still gets `404` for A's order, trip, trip capacity, planning run,
  origin, destination, route, vehicle and carrier;
- collection endpoints (`GET /orders`, `GET /masterdata/origins`) return `totalElements = 0`,
  so nothing leaks through a list either.

`anotherCompanyCannotMutate` - the same planner is refused `404` on: cancelling A's order,
updating A's order, removing A's assignment, assigning to A's trip, cancelling A's planning run,
deactivating A's origin, deactivating A's vehicle. The test then re-reads the database and
asserts **nothing moved**: the order's status is unchanged, the trip and run are still
`CONFIRMED`, and the assignment row is still there. The refusals are refusals, not partially
applied writes.

This complements - and does not duplicate - the 24 tests of `iam.api.ApiSecurityTest`, which
cover the authentication and scope-resolution layer directly (forged, expired, wrong-issuer,
wrong-audience tokens; sibling company; another organization's company; unknown company id;
per-company permissions; and RFC 9457 error documents that never name the missing permission,
carry a stack trace or echo a hostile correlation id).

---

## 4. Frontend verification

```
cd frontend/tms-web
npm ci            # lockfile-exact install
npm run lint      # oxlint
npm run typecheck # tsc -b
npm run test      # vitest run
npm run build     # tsc -b && vite build
```

All five exited **0**.

| Stage | Result |
|---|---|
| `npm ci` | 156 packages added, 157 audited, **0 vulnerabilities** |
| `npm run lint` | Pass, **2 warnings**, both pre-existing `react(only-export-components)` on `AuthContext.tsx:112` and `CompanyContext.tsx:119` |
| `npm run typecheck` | Clean, no output |
| `npm run test` | **36 test files, 219 tests, 219 passed, 0 failed** (6.89 s) |
| `npm run build` | Success in 740 ms - `index.html` 0.46 kB, CSS 230.20 kB (30.79 kB gzip), JS 881.62 kB (236.27 kB gzip) |

The build emits one advisory warning: the JS chunk exceeds Vite's 500 kB pre-gzip threshold and
code splitting is suggested. It is **not** a failure and is left as-is deliberately - splitting
the bundle is a frontend performance decision with routing implications, not a verification fix,
and this step does not implement features. Recorded here so it is a known number rather than a
surprise.

`npm ci` was used rather than `npm install` precisely so the lockfile is the thing being
verified: it deletes `node_modules` and installs exactly what `package-lock.json` pins.

---

## 5. Full-chain review status

Every business vertical was already walked `UI -> API client -> Controller -> Service/Use Case ->
Repository -> DB -> Security -> Tests` in Steps 05-12 and re-verified here by execution rather
than by reading. This step adds the one thing that per-module review cannot give: a single
transaction chain that crosses masterdata, fleet, orders and planning in one flow (section 3).

No RPC and no Edge Function exists in the repository, so there is none to review - confirmed by
`MigrationConventionTest` (migrations contain constraints, indexes and one `set_updated_at`
trigger; no business logic in SQL) and by the absence of any `supabase/functions` directory.

---

## 6. Repository hygiene

Every row below was checked by a command, not by inspection.

| Check | Method | Result |
|---|---|---|
| No secrets committed | Regex sweep of all tracked files for JWTs, `sb[pks]_` keys, AWS keys, PEM private keys, `sk-`/`ghp_` tokens | **0 matches** |
| No real env files | `git ls-files \| grep -i '\.env'` | Only `backend/tms-api/.env.example` and `frontend/tms-web/.env.example` |
| No generated build output committed | `git ls-files \| grep -E '(^\|/)(target\|dist\|node_modules\|build)/'` | **0 matches**. `target/` and `dist/` exist on disk and are gitignored |
| No TODO hiding a P0/P1 | `grep -nE '\b(TODO\|FIXME\|HACK\|XXX)\b'` over **all** tracked files | **0 matches** - there is no hidden-correctness-debt marker anywhere in the repository |
| No duplicate migration system | `supabase/` contains only `.gitignore`, `README.md`, `config.toml`, `seeds/local_dev_seed.sql`; no `supabase/migrations`. Enforced by `MigrationConventionTest.supabaseCarriesNoParallelMigrationHistory` | Flyway is the sole owner |
| Supabase seed carries no application DDL | `grep -Ei 'create\|alter\|drop table\|index'` on `local_dev_seed.sql` | **0 matches**; it is `INSERT ... ON CONFLICT DO NOTHING` only, and is not wired into `supabase db reset` |
| No `trip_id` shortcut on Order | Read `tms.transport_order` in V10 in full; `grep trip_id` across all migrations | **Confirmed absent.** The link is the `trip_order_assignment` entity (V11), which carries an explicit comment stating the column was rejected. Also asserted at runtime by smoke step 9 |
| No direct frontend business Supabase query | `grep -rn 'supabase\.' frontend/tms-web/src` | 6 call sites, **all** `supabase.auth.*` (`getSession` ×2, `onAuthStateChange`, `signOut` ×2, `signInWithPassword`) in `AuthContext.tsx`. All business data goes through `httpClient.ts` -> `appEnv.apiBaseUrl` |
| Applied migrations immutable | `FlywayMigrationIntegrationTest.migrateIsIdempotentAndValidates` runs `validate`, which fails on any edited checksum | Pass |
| EWM independence | No reference to EWM in schema or code | Confirmed |

Repository size for context: 12 migrations / 25 tables, 211 backend main + 38 backend test Java
files, 112 frontend source files of which 36 are test files, 33 documents.

---

## 7. Changes made in this step

Three files, one of them code:

- `backend/tms-api/src/test/java/com/ebim/tms/smoke/EndToEndSmokeIntegrationTest.java` - new, the
  only source change in this step;
- `docs/overnight/TEST_EVIDENCE.md` - new, this document;
- `docs/README.md` - one index line pointing at it.

**No production source, no migration, no configuration and no frontend file was modified.** No
verification gate failed in a way that required a fix, so nothing was fixed.

During authoring, the smoke test failed once on a wrong expectation of *the test*, not of the
product: it assumed `palletQuantity` on an order line was a per-unit value multiplied by
quantity. It is not - V10 and `TransportOrderLine` define it as the line's own declared pallet
contribution, deliberately not derived from quantity. The test was corrected to match the
documented behaviour. Recorded here because "a test failed then passed" is only trustworthy
when what changed is stated: the product behaviour was never touched.

The same failure also exposed a fragility in the test itself - the created id was captured
behind the failing assertion, so one wrong expectation cascaded into five confusing downstream
failures. All eight create steps now capture the new id **before** any body assertion
(`created(actions, sink)`), so a future expectation failure reports itself instead of producing
a null id in every later step.

---

## 8. Blocked, not run, and deliberately out of scope

Stated explicitly so nothing here reads as green by omission.

1. **Driving the running server over HTTP: blocked by the environment.** The packaged jar was
   started successfully against a throwaway database (section 1.4), but **no HTTP request could
   be issued against it**: this run's shell denied outbound `curl` requests, including to
   loopback. So what remains unproven by a live process specifically is the servlet-level
   behaviour of a *booted* instance - the filter chain ordering, TLS/proxy concerns and actuator
   exposure as served by Tomcat rather than MockMvc. Everything else in the request path
   (security chain, controllers, services, repositories, SQL) is exercised against a real
   database by the 324 tests, and the eleven-step flow specifically by section 3.
2. **A real Supabase-minted bearer token was not used, by choice.** A local Supabase stack
   (`gotrue`, `kong`, `postgres`, and the rest) was found already running in Docker - started
   about five hours before this step, not by it - so a local JWKS endpoint does in fact exist
   and `application-local.yml` already points at it. It was **not** used: minting a real token
   means creating an auth user inside that pre-existing stack, which is a mutation of local
   infrastructure this run did not create and was not authorized to change. Nothing was read
   from, written to, or started in that stack. The smoke test's locally signed tokens go through
   the same production validators instead (section 3.1).
3. **The local Supabase database was not migrated or touched.** The schema is owned by Flyway and
   was replayed against disposable containers twice over (sections 1.4 and 2.2), which is
   stronger evidence for application DDL than `supabase db reset` and leaves no local state
   behind.
4. **No remote or shared database, project or cloud environment was contacted.** Every database
   used in this step was a container created and removed inside the run.
5. **Nothing was committed and nothing was pushed.** The working tree carries the new test file,
   this document and the `docs/README.md` index line, for human review.
6. **Frontend bundle size** (section 4) is a known advisory, not a failure, and was not acted on.
7. **The Step 12 open items remain open and unchanged** - items 8-12 of
   `docs/overnight/12_HARDENING.md`, detailed in `docs/security/SECURITY_REVIEW.md` and
   `docs/performance/PERFORMANCE_BASELINE.md`: two-statement identity resolution per request,
   unpaginated frequency exceptions, `size: 200` lookup dropdowns, `FORCE ROW LEVEL SECURITY`
   not set, unindexed `created_by`/`updated_by`. None is an authorization gap; this step neither
   closed nor worsened any of them.
8. **Actuator `metrics` is still deliberately unexposed** (Step 12 section 6). Unchanged here.

---

## 9. Remaining failures

**None.** Backend `clean verify`: 324/324 pass. Frontend lint, typecheck, test and build: all
exit 0. Independent migration replay: 12/12 apply. No test is skipped, quarantined, `@Disabled`
or expected-to-fail.

The two frontend lint warnings and the one Vite bundle-size warning are warnings, are listed
above with their exact locations, and are unchanged by this step.

---

## 10. Reproducing this document

```
# Backend: tests, architecture rules, migration replay, packaging - Docker must be running
cd backend/tms-api && ./mvnw -B clean verify

# The smoke flow alone
cd backend/tms-api && ./mvnw -B -Dtest=EndToEndSmokeIntegrationTest test

# Frontend
cd frontend/tms-web && npm ci && npm run lint && npm run typecheck && npm run test && npm run build
```

If Docker is not running, the integration tests report as **skipped** rather than passed, the
counts above will not be reproduced, and the run must be documented as an environment blocker
instead of a green build.
