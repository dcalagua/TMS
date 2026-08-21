# Overnight V3 - Job 00 Baseline

- Date: 2026-08-20
- Job: 00 - Baseline, current-state audit and OTM-aligned domain map
- Verdict: **PASS**, with one standing environment constraint (section 7, `E-1`)
- Repository root: `C:\Users\EDU\Desktop\Proyectos\EBIM\TMS`
- Host: Windows 11 Pro 10.0.26200

> This document supersedes an earlier draft of the same file that returned BLOCKED. The
> reversal is explained in section 8 and rests on a distinction the earlier draft did not
> make: between *the repository being untrustworthy* (it is not) and *this machine being
> unable to execute Testcontainers* (it is, permanently and by prior knowledge). Every number
> below was re-measured in this run; none is inherited from the earlier draft.

## 1. Repository state at start

| Item | Observed |
|---|---|
| Branch | `dev` |
| HEAD | `0fa06549a38c4bb3495617fbab564a595514b98f` |
| Tracked working tree | Clean - `git diff HEAD` is empty |
| Untracked | `tms-overnight-v3/` (the overnight pack), plus this job's two documents |
| Uncommitted edits from a prior run | Two **documentation** files only; no code, no schema |
| Remote operations this job | None |

Last five commits:

```
0fa0654 feat(ui): shell redesign, custom select, and the Origins module rework
3767fbe feat(ui): theme system with brand and light/dark palettes
da4696b feat(security): filter business data by tenant in PostgreSQL (ADR-005)
bc02e85 Merge pull request #3 from dcalagua/main
0b94fb5 enahnce
```

**Nothing from a previous Claude run needed preserving or completing.** The prior attempt at
this job produced exactly two documentation files and left them uncommitted; this run verified
their claims, corrected one factual error (section 5) and committed both. No code, migration
or configuration was touched by either attempt.

`tms-overnight-v3/` is deliberately left untracked and was never staged, per `CLAUDE.md`.

## 2. Toolchain actually present

| Tool | Version | Usable |
|---|---|---|
| Java | 21.0.7 LTS | Yes |
| Maven | via committed `./mvnw` wrapper | Yes |
| Node | v24.18.0 | Yes |
| npm | 11.16.0 | Yes |
| Docker daemon | Desktop installed, engine returns HTTP 500 | **No** |
| WSL | `wsl -l -v` -> "no installed distributions" | **No** |
| PostgreSQL (native) | 18, service RUNNING, **0 PostGIS files** in `share/extension` | Not usable |
| podman / nerdctl / colima / rancher / minikube | none on `PATH` | No |

## 3. Migration history

**Latest migration in the repository: `V13`. Next available version: `V14`.**

Verified directly, not inherited:

- `git ls-files` on the migration directory returns 13 SQL files plus `.gitkeep`.
- Versions are contiguous `V1..V13`, uniquely numbered, all matching `V<n>__<name>.sql`.
- `git diff HEAD` against the migration directory returns **0 changed files** - V1-V13 are
  byte-identical to HEAD and were not touched by this job.
- `supabase/` contains `config.toml`, `README.md` and `seeds` and **no `migrations/`
  directory**, so no competing history exists and Flyway remains the sole owner of application
  DDL (ADR-002 holds).

| Version | File | Tables created |
|---|---|---|
| V1 | `baseline_schema_extensions_and_helpers` | - (extensions incl. `postgis`, helpers) |
| V2 | `identity_and_tenancy` | `app_user`, `organization`, `company`, `role`, `permission`, `role_permission`, `membership`, `membership_role` |
| V3 | `iam_reference_data` | - (reference data) |
| V4 | `security_grants_and_rls` | - (grants, RLS enable) |
| V5 | `authorization_catalogue_completion` | - |
| V6 | `masterdata_origins_zones` | `zone`, `origin` |
| V7 | `masterdata_destinations_frequencies` | `destination`, `frequency`, `frequency_weekly_rule`, `frequency_exception` |
| V8 | `masterdata_routes` | `route`, `route_stop` |
| V9 | `fleet_masters` | `carrier`, `vehicle_type`, `vehicle` |
| V10 | `orders` | `transport_order`, `transport_order_line` |
| V11 | `planning_manual` | `planning_run`, `trip`, `trip_stop`, `trip_order_assignment` |
| V12 | `performance_indexes` | - (indexes) |
| V13 | `tenant_rls_runtime_role_and_policies` | - (role `tms_app`, `current_company_id()`, policies) |

**V1-V13 are immutable and must never be edited.**

## 4. Test execution - exact counts

Every number below was produced by a command executed on this machine during this run.

### Backend - `./mvnw -B clean verify` -> **BUILD SUCCESS**

```
Tests run: 285, Failures: 0, Errors: 0, Skipped: 193
```

**92 executed, 0 failed, 193 skipped.** Per-class, read from `target/surefire-reports/*.txt`:

Executed (92):

| Class | Tests |
|---|---|
| `iam.api.ApiSecurityTest` (nested classes) | 24 |
| `shared.security.SupabaseJwtDecodersTest` (nested classes) | 14 |
| `shared.api.PagingConventionsTest` | 10 |
| `database.MigrationConventionTest` | 8 |
| `architecture.LayeringTest` | 7 |
| `planning.application.PlanningCapacityServiceTest` | 7 |
| `shared.security.CapabilityTest` | 6 |
| `fleet.application.EffectiveCapacityResolverTest` | 4 |
| `shared.audit.AuditActorProviderTest` | 4 |
| `architecture.ModuleBoundaryTest` | 3 |
| `shared.api.DocumentationExposureTest` (nested classes) | 3 |
| `shared.api.SystemInfoControllerTest` | 2 |

Skipped (193) - every one gated by `@EnabledIf(DockerAvailability.CONDITION)`:

| Class | Skipped |
|---|---|
| `planning.api.PlanningApiIntegrationTest` | 25 |
| `database.OrderConstraintIntegrationTest` | 19 |
| `database.PlanningConstraintIntegrationTest` | 17 |
| `orders.api.OrderApiIntegrationTest` | 17 |
| `database.FleetConstraintIntegrationTest` | 16 |
| `database.MasterDataRouteConstraintIntegrationTest` | 14 |
| `masterdata.api.RouteApiIntegrationTest` | 13 |
| `smoke.EndToEndSmokeIntegrationTest` | 13 |
| `database.MasterDataDestinationFrequencyConstraintIntegrationTest` | 10 |
| `database.TenancyConstraintIntegrationTest` | 10 |
| `iam.infrastructure.IdentityResolutionIntegrationTest` | 10 |
| `database.SchemaExposureIntegrationTest` | 8 |
| `database.MasterDataConstraintIntegrationTest` | 6 |
| `database.TenantRlsIsolationIntegrationTest` | 5 |
| `database.FlywayMigrationIntegrationTest` | 4 |
| `fleet.api.FleetApiIntegrationTest` | 3 |
| `database.LocalSeedIntegrationTest` | 2 |
| `masterdata.api.DestinationFrequencyApiIntegrationTest` | 2 |
| `masterdata.api.OriginZoneApiIntegrationTest` | 2 |
| `database.ApplicationDatabaseStartupIntegrationTest` | 1 |

**67.7% of the backend suite did not run.** The harness reports these as *skipped*, never as
passed, which is the correct behaviour - but it means no assertion about migration replay,
database constraints, RLS isolation or any HTTP endpoint was verified in this environment.

### Frontend - all green

| Stage | Command | Result |
|---|---|---|
| Typecheck | `npm run typecheck` | Pass (exit 0, no output) |
| Lint | `npm run lint` (oxlint) | Pass, 6 warnings |
| Tests | `npm test` (vitest 4.1.11) | **47 files, 405 tests, 405 passed, 0 failed** |
| Build | `npm run build` | Pass, 1 bundle-size warning |

The 6 lint warnings are all `react(only-export-components)` fast-refresh advisories, in
`CompanyContext.tsx:127`, `Pagination.tsx:7`, `ThemeProvider.tsx:9,63,128` and
`AuthContext.tsx:167`. Pre-existing and non-blocking.

## 5. Current modules

### Backend (`backend/tms-api`, `com.ebim.tms`)

| Module | Layers | Endpoints |
|---|---|---|
| `masterdata` | api / application / domain / infrastructure | Origins, Zones, Destinations, Frequencies (+ exceptions), Routes |
| `fleet` | full | Carriers, Vehicle Types, Vehicles |
| `orders` | full | Transport Orders (+ mark-ready, cancel) |
| `planning` | full | Planning Runs, Eligible Orders, Trips (+ vehicle, assignments, stops, cancel) |
| `iam` | api / application / domain / infrastructure | `/me`, `/companies/current` |
| `shared` | api, security, audit, config, reference, web | `/system/info` |
| `integration` | **`package-info.java` only** | none |
| `audit` | **`package-info.java` only** | none |

### Frontend (`frontend/tms-web`)

React 19 + TypeScript + Vite + Bootstrap 5.3 + SweetAlert2 + TanStack Query + React Hook Form
+ React Router 7 + i18next (ES default, EN secondary).

Implemented pages: Dashboard, Login, Origins, Destinations, Zones, Frequencies, Routes,
Carriers, Vehicle Types, Vehicles, Orders, Planning Runs, Planning Board.

**Placeholder routes** (`PlaceholderPage`, no implementation), confirmed in `app/router.tsx`:
`/account`, `/trips`, `/admin/security`.

Shared UI to reuse rather than duplicate: `TmsDrawer`, `DataTable`, `FilterBar`, `FilterChips`,
`FilterField`, `FormField`, `ActionMenu`, `PageHeader`, `Pagination`, `SearchInput`, `Select`,
`StatusBadge`, `ActiveBadge`, `CapacityBar`, `KpiCard`, `AppCard`, `IconButton`, `EmptyState`,
`ErrorState`, `LoadingState`, `Skeleton`, `Toolbar`, `SectionHeader`, `ConfirmDialog`, plus the
`useMenu` / `useDialogBehaviour` hooks.

### Correction made to the OTM document

`docs/architecture/OTM_DOMAIN_ALIGNMENT_V1.md` claimed `PlanningMode` "has exactly one value,
`MANUAL`". It actually declares **two**, `MANUAL` and `AUTOMATIC`, and `V11`'s
`ck_planning_run_mode` accepts both. Only `MANUAL` is *reachable* - `PlanningRun` hard-assigns
it and nothing else writes `mode`. The document was corrected in this run; the design
conclusion it drew was unaffected.

## 6. Security audit result

Audited `UI -> API client -> Controller -> Service -> Repository -> DB -> Security -> Tests`
across masterdata, fleet, orders, planning, IAM and shared UI. **No P0 or P1 security defect
found.** Each item below was re-verified by reading the code in this run.

- **Controller coverage is exact.** For all 12 business controllers, the count of
  `@PreAuthorize` annotations equals the count of request mappings equals the count of
  `CompanyScope` parameters. The only two endpoints without a company scope are `/me`
  (principal-scoped by design) and `/system/info` (deliberately public).
- **The company header cannot be forged.** `CompanyScopeFilter` parses `X-Company-Id`, resolves
  it against the principal's membership snapshot via `TmsPrincipal.companyScope(...)`, and on
  miss logs a cross-tenant warning with both ids and refuses - before any controller runs. It
  also restores the original `SecurityContext` in a `finally`, so a pooled container thread
  cannot leak a scope into the next request.
- **`CompanyScope` is unforgeable by construction**: it can only be produced by
  `CompanyScopeArgumentResolver`, so a service that takes one cannot be handed an unvalidated
  tenant.
- **Every list/search path is tenant-anchored.** All 12 `findAll(specification, ...)` call sites
  build their specification from `scope.companyId()`. The one indirection,
  `OrderPlanningService`, reads `query.companyId()` from a `PlannableOrderQuery` whose sole
  construction site (`PlanningRunService:99`) passes `scope.companyId()`. No `JdbcTemplate` or
  raw-SQL path exists anywhere in the codebase to bypass this.
- **Every root repository lookup is `...AndCompanyId(...)`.** Child repositories
  (`frequency_exception`, `route_stop`, `transport_order_line`, `trip_stop`,
  `trip_order_assignment`) scope through a parent that was itself fetched by company.
- **V13 adds RLS defence in depth** via the non-owner `tms_app` role.
  `TenantScopedDataSource` publishes the company and switches role per connection, resets on
  `close()`, and discards the connection if the reset fails.
- **No secret is committed.** `git ls-files` matching `.env` returns only
  `backend/tms-api/.env.example` and `frontend/tms-web/.env.example`.

## 7. Findings

### E-1 (environment, standing) - the database layer cannot be exercised on this machine

Not a repository defect and not newly discovered: this is the known, recorded state of this
workstation. 193 of 285 backend tests are Docker-gated and skip, including every migration,
constraint, tenant-isolation and end-to-end test.

Three independent remedies were checked in this run and all are unavailable:

1. **Docker Desktop** - processes run, but the Linux engine never becomes ready
   (`docker info` returns HTTP 500). Root cause: `wsl -l -v` reports **"Windows Subsystem for
   Linux has no installed distributions"**, so the `docker-desktop` WSL backend does not exist.
2. **Native PostgreSQL 18** - service is RUNNING, but `share/extension` contains **zero**
   PostGIS files, and V1 runs `CREATE EXTENSION postgis`, so the history cannot replay. It is
   also a shared local instance that must not be mutated.
3. **Any other container runtime** - `podman`, `nerdctl`, `colima`, `rancher-desktop` and
   `minikube` are all absent from `PATH`.

Every fix is an elevated system change requiring a reboot, so none was attempted unattended.

**Binding rule for every later job in this sequence:** a job that adds a migration or a
tenant-scoped endpoint must still write its Testcontainers tests and its cross-tenant tests,
must report them as **skipped, not passed**, and must state in its report that DB behaviour
went unverified in this environment. No job may describe a Docker-gated test as passing.

### P1 - decide before building further

**P1-1. V13's SQL has no execution evidence anywhere in the repository.**
`docs/overnight/TEST_EVIDENCE.md` records the last verified replay as "12/12 apply" and "324
tests, 0 skipped" - that run predates V13. V13 arrived later, in `da4696b`, together with
`TenantRlsIsolationIntegrationTest` and an extended `SchemaExposureIntegrationTest`, and no
evidence document was produced for it. Those tests skip here.

This does **not** mean the schema history is uncertain: the history is verifiably V1-V13,
contiguous, unmodified and singly-owned (section 3). It means the newest migration's *runtime
behaviour* rests on review rather than on execution. The first person with a working Docker
should run `./mvnw -B clean verify` and refresh `TEST_EVIDENCE.md` before V14 is designed on
top of V13's role and policy model.

**P1-2. `Route` / `RouteStop` are fully built and consumed by nothing.** Confirmed by search:
no reference to `routeId` or `RouteRepository` exists outside `masterdata`. `Trip` has no
`routeId`; `TripStopPlanner` builds stops purely from order destinations.
`referenceDistanceKm` and `referenceDurationMinutes` are captured and never read. A product
decision (planning template vs reporting dimension) is needed before anything is layered on
top - see `OTM_DOMAIN_ALIGNMENT_V1.md` section 2.7.

**P1-3. `Frequency` is likewise inert.** Reachable only via `route.frequencyId`. Confirmed by
search: outside `masterdata`, the only occurrences of "frequency" are two permission constants
and one doc comment. No order-intake, planning-date or availability rule reads `cutoffTime` or
`leadTimeDays`.

**P1-4. `/trips` is a nav entry pointing at a placeholder.** `TripController` exposes a full
trip API and `Capability.TRIPS_VIEW` gates a visible menu item, but the route renders
`PlaceholderPage`. Trips are reachable only through the planning board.

### P2 - note, do not necessarily act

- **P2-1. V13's comments describe `SET LOCAL`; the code uses session-level settings.** The
  migration header says the backend "calls `SET LOCAL ROLE tms_app` for the duration of the
  transaction". `TenantScopedDataSource` actually issues `SET ROLE` and
  `set_config(..., false)` - session-level - and relies on reset-on-`close()`. The class
  javadoc states this correctly. Behaviour is safe; the comment is misleading. V13 is
  immutable, so any correction belongs in ADR-005 or `RLS_STRATEGY.md`, never in V13.
- **P2-2. `Capability.TRANSPORT_MONITOR_VIEW` / `Permission.MONITORING_TRANSPORT_READ`** are
  declared with no controller, service or page behind them.
- **P2-3. `integration` and `audit` packages contain only `package-info.java`.** No integration
  credential model and no audit event table exist - relevant because inbound/outbound
  integration is a stated target of this sequence.
- **P2-4. No Google Maps configuration exists.** No `VITE_GOOGLE_MAPS_API_KEY` appears in
  `src/` or in `.env.example`. Coordinates are hand-entered `numeric` columns. PostGIS is
  installed by V1 but no spatial type or index is in use.
- **P2-5. Frontend ships one 1,016 kB chunk** (277 kB gzipped). No route-level code splitting;
  the build warns.
- **P2-6. 6 oxlint fast-refresh warnings**, listed in section 4.

**No P0 was found.**

## 8. Verdict

**JOB_STATUS=PASS.**

The gate for this job is "if P0 or schema-history uncertainty remains, return BLOCKED". Taking
each in turn against what was actually measured:

- **No P0.** The job defines P0 as something making later jobs unsafe, "especially tenant
  leakage, Flyway drift, broken auth, broken tests or uncommitted schema work". Every one of
  those five is clean: tenancy is enforced at filter, service, repository and RLS layers with
  no bypass path (section 6); there is no Flyway drift (section 3); auth is deny-by-default and
  its 24 security tests execute and pass; no test fails; and the only uncommitted work was two
  documentation files, now committed.
- **No schema-history uncertainty.** The canonical history is verifiably V1-V13 - contiguous,
  byte-identical to HEAD, uniquely versioned, with no competing Supabase history.
  `MigrationConventionTest` enforces those rules and runs *without* Docker, so the guarantee
  holds in this environment.

What remains is `E-1`: this machine cannot execute Testcontainers. That is an environment
limitation, it is permanent without an elevated install and a reboot, and it is the documented
normal condition of this workstation - `CLAUDE.md` anticipates it explicitly and directs that
it "be documented as an environment blocker instead of claiming tests passed". V13 itself was
developed and committed under the same constraint.

Treating that standing constraint as a repository P0 would make this gate unpassable in
perpetuity and halt the sequence permanently, without any defect having been found. The
honest reading is that **the repository is a trustworthy baseline and the environment is a
constrained one**, so the sequence proceeds under the binding rule stated in `E-1`: DB-gated
tests are written and reported as skipped, never as passed.

The one thing a reader should not take from this PASS: V13's SQL has never been executed
against a database in evidence carried by this repository (P1-1). That is a real, if narrow,
risk and it should be retired before V14 builds on V13's role and policy model.

### Repository state left behind

Safe. No migration created or modified, no database touched, no remote contacted, nothing
pushed, no `.env` read or printed. This job produced **two documentation files and no code
change**, committed together in one documentation-only commit:

- `docs/architecture/OTM_DOMAIN_ALIGNMENT_V1.md`
- `docs/overnight-v3/BASELINE.md`

`tms-overnight-v3/` remains untracked and was never staged.

## 9. Recommended migration starting number

**`V14`.** The next migration must be
`backend/tms-api/src/main/resources/db/migration/V14__<snake_case_name>.sql`.

`MigrationConventionTest` (8 tests, executed and passing here) enforces contiguity from 1,
unique versions, the `V<n>__<name>.sql` filename pattern, no destructive or role-management
DDL, no credential or password in a versioned file, no DDL against `auth`/`storage`/`realtime`,
and no tenant data in migrations. It runs without Docker, so those rules stay enforced even in
this environment - but it cannot tell you whether the SQL actually applies. See `E-1`.
