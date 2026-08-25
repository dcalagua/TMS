# Overnight Sellable V4 — Job 00 Baseline, Architecture & Sellability Audit

- Date: 2026-08-20
- Job: `00` — baseline audit and product map for the Sellable V4 pack
- Verdict: **PARTIAL** (see §11 — the audit is complete and verified; no quality gate could be
  executed in this session, and two carried P1s remain open)
- Repository root: `C:\Users\EDU\Desktop\Proyectos\EBIM\TMS`
- Host: Windows 11 Pro 10.0.26200

> **How to read this document.** Everything asserted here was re-derived from the current
> working tree in this run. Where a number is inherited from the Overnight V3 final report
> rather than measured now, it is labelled *(carried, unverified this run)*. Nothing that
> could not be checked is presented as checked.

> **Second pass.** After the first pass, a second session re-read the tree and independently
> re-checked every load-bearing claim below — the migration set, the `EXISTS/PARTIAL/MISSING`
> calls that decide which jobs build, both P1s, and each verified P2 — against the source
> rather than against this document. Three corrections came out of it and are marked
> **[2nd pass]** where they land (§4.1 item 38, §7 inventory, §8 item 5). Everything else was
> confirmed as written. The second session hit the same process-spawning wall as the first, so
> §7 still reports no measured gate.

> **Third pass.** A third session re-derived a 17-claim sample from source — the migration
> set, the controller inventory, `TripStatus`, the Driver/rates/tendering/POD absences, both
> Job 01 gaps, all three `AutoPlanningService` findings, the placeholder routes, the Docker
> gating ratio and the test-file inventory — and **overturned nothing**. It made no correction
> to the module map. What it did add is the *root cause* of the missing gates, which the first
> two passes could only describe as "refused": see **P1-3**. That finding is pack-level, not
> Job 00-level — it silently defeats the quality gates of all eighteen jobs.

---

## 1. Repository state

| Item | Observed |
|---|---|
| Branch | `dev` |
| HEAD | `b13e66094689090c9487f59364c4a36142761270` — `docs(domain): frequency, route, fleet, order and planning contracts, plus a V2 domain map` (2026-08-20 17:16:36 −0500) |
| Main branch | `main` |
| Tracked working tree | **Clean.** `git status --porcelain` shows no tracked modification; `git diff --stat` is empty |
| Untracked | `tms-overnight-sellable-v4/` (the overnight pack) and `TMS-by-EBIM-Overnight-Sellable-v4.zip`. Both deliberately unstaged, per the repository rule |
| Remote operations this job | None. No fetch, no push, no database connection |

### 1.1 Commits since the V3 handoff

Seven commits were added after the V3 final report (`6b0687c`), and they are what makes the
"already exists" claims in the pack's master context mostly true:

| SHA | Subject |
|---|---|
| `f0a2bdd` | docs(overnight-v3): revise final report after independent Job 14 re-verification |
| `5c89688` | feat(location): unify physical places and reduce roles to operational uses |
| `5e65a7d` | feat(ui): unify the location, origin and destination workflows |
| `1e3c208` | docs(location): domain contract, pre-change audit and ADR amendment |
| `f8ed0ed` | feat(planning): heuristic automatic planning V1 |
| `427aee2` | feat(ui): automatic planning review, and an editor for calendar exceptions |
| `b13e660` | docs(domain): frequency, route, fleet, order and planning contracts, plus a V2 domain map |

---

## 2. Migrations

### 2.1 Latest real migration: **V23**

Twenty-three files under `backend/tms-api/src/main/resources/db/migration`, contiguous
`V1`–`V23`, no gaps and no duplicate versions:

```
V1  baseline_schema_extensions_and_helpers   V13 tenant_rls_runtime_role_and_policies
V2  identity_and_tenancy                     V14 masterdata_canonical_location
V3  iam_reference_data                       V15 masterdata_location_frequency
V4  security_grants_and_rls                  V16 fleet_external_reference_and_double_booking
V5  authorization_catalogue_completion       V17 orders_declared_totals
V6  masterdata_origins_zones                 V18 integration_clients_and_inbox
V7  masterdata_destinations_frequencies      V19 planning_shipment_v2
V8  masterdata_routes                        V20 shipment_outbox_and_outbound_scope
V9  fleet_masters                            V21 master_data_import_batch
V10 orders                                   V22 audit_event
V11 planning_manual                          V23 location_canonical_unification
V12 performance_indexes
```

**The next available version is `V24`.** No job in this pack may reuse or edit `V1`–`V23`.

### 2.2 Uncertified migrations: **V14–V23 (ten files)**

V3's report established that V14–V22 had never been executed by any PostgreSQL server. V23
was added afterwards, in the same conditions. Nothing in this session executed any of them
either. `MigrationConventionTest` (8 tests) checks the `.sql` files as *text* — naming,
contiguity, no destructive DDL, no grants to API roles — and never opens a connection. It is
not evidence that the SQL parses.

V23 is the largest single risk in that set: it repoints six foreign keys
(`route`, `route_stop`, `transport_order`, `planning_run`, `trip_stop`) from `tms.origin` /
`tms.destination` to `tms.location`, adopts unlinked legacy rows, reduces `tms.location_role`
to two values, and revokes every write privilege on the two legacy tables. It deliberately
does **not** drop them, so a bad merge stays recoverable.

### 2.3 Flyway is still the sole migration owner

`supabase/` contains only `config.toml`, `README.md`, `.gitignore` and `seeds/`. There is no
`supabase/migrations/` and no `supabase/.temp/` — the CLI has never been linked to a remote
project. No competing history exists.

---

## 3. Architecture conformance

| Rule | State | Evidence |
|---|---|---|
| `React → Spring Boot → PostgreSQL` for business data | **Holds** | Every module under `frontend/tms-web/src/shared/api/*.ts` goes through `httpClient.ts` to `${VITE_API_BASE_URL}`; Supabase is imported only by the auth context |
| Flyway owns application DDL | **Holds** | §2.3 |
| Business modules do not import each other | **Holds by test** | Every cross-module call goes through a port in `shared/reference` (`OrderPlanningPort`, `VehicleLookupPort`, `RouteTemplateLookupPort`, `ServiceCalendarPort`, `ShipmentPublicationPort`, `LocationIntakePort`, `OrderIntakePort`, …); `architecture/ModuleBoundaryTest` and `architecture/LayeringTest` enforce it |
| Tenancy defence in depth | **Holds** | `SupabaseJwtConfig` → `TmsJwtAuthenticationConverter` → `PrincipalLoader` → `CompanyScopeFilter`/`CompanyScopeArgumentResolver` → service → repository predicate → composite FK → `TenantScopedDataSource` + RLS on `tms_app` (V13) |
| Authorization is server-side | **Holds** | `Capability` is explicitly derived, never stored and never enforced; `Permission` is what endpoints check. Its own class comment says so and `CapabilityTest` covers the mapping |
| No deferred technology introduced early | **Holds** | No OR-Tools, no Kafka, no Realtime, no Storage, no telematics anywhere in `pom.xml` or `package.json` |
| No scheduler / background jobs | **True, by design** | Zero `@Scheduled`, `@Async` or `@EnableScheduling` in the whole backend. The outbox is *pull*-based: clients poll `/integration/shipments/events?since=` |

---

## 4. Module inventory

Legend: **EXISTS** = full vertical (UI → API client → Controller → Service → Repository → DB →
Security → Tests, with the DB/test layer subject to §7). **PARTIAL** = a real slice exists but
the capability is not complete. **MISSING** = no code.

### 4.1 Matrix

| # | Capability | State | Where |
|---|---|---|---|
| 1 | Location (canonical master) | **EXISTS** | `LocationController`, `LocationService`, `Location`, V14+V23, `LocationsPage` + `LocationFormDrawer` |
| 2 | Location roles (ORIGIN / DESTINATION) | **EXISTS** | `LocationRoleAssignment`, V23 reduces the enum to the two operational uses |
| 3 | Origins / Destinations screens | **EXISTS as views** | `OriginsPage`/`DestinationsPage` delegate to `LocationsPage` with a preset filter; `originsApi`/`destinationsApi` are one-line lenses over `locationsApi` |
| 4 | Zones | **EXISTS** | `ZoneController`, `ZoneService`, `ZonesPage` |
| 5 | Frequencies + weekly rules + exceptions | **PARTIAL** | Full CRUD and a real exceptions editor (`FrequencyExceptionsPanel`), but no per-date cutoff override — see P2-1 |
| 6 | Location ↔ frequency assignment + eligibility | **EXISTS** | `LocationFrequencyService`, `LocationEligibilityEvaluator`, `FrequencyCalendar`, `LocationFrequencyPanel` |
| 7 | Routes / RouteStop | **PARTIAL** | Full CRUD, ordered stops, reordering; no `serviceTimeOverride` on the stop — see P2-2 |
| 8 | Carrier | **EXISTS** | `CarrierController`, `CarrierService`, `CarriersPage`, import |
| 9 | VehicleType | **EXISTS** | `VehicleTypeController`, capacity by weight/volume/pallets, import |
| 10 | Vehicle | **EXISTS** | `VehicleController`, `EffectiveCapacityResolver`, import |
| 11 | **Driver** | **MISSING** | No entity, no table, no endpoint, no screen. `driver` appears only in prose comments |
| 12 | Orders V2 (lines, declared totals, lifecycle) | **EXISTS** | `OrderController`, `OrderService`, `TransportOrder`, `OrderTotals`, V10+V17, `OrdersPage` + `OrderFormDrawer` |
| 13 | Order lifecycle | **PARTIAL** | `NOT_READY → READY_FOR_PLANNING → PLANNED → CANCELLED`. No dispatch/delivery states — deliberate, per the enum's own comment |
| 14 | Import Center (masters + orders) | **EXISTS** | `shared/imports/*` + five import controllers (locations, carriers, vehicle types, vehicles, orders), `ImportDrawer` on each page, dry-run then apply, XLSX/CSV templates |
| 15 | Inbound M2M API (locations, orders) | **EXISTS** | `integration/*`, `IntegrationInboxService`, `PayloadHash` idempotency, `IntegrationAuthenticationFilter`, V18 |
| 16 | Outbound shipment contract + transactional outbox | **EXISTS (pull only)** | `IntegrationShipmentController`, `ShipmentPublicationAdapter`, V20. No relay/push — documented as the V1 boundary |
| 17 | PlanningRun | **EXISTS** | `PlanningRunController`, `PlanningRunService`, V11+V19, `PlanningRunsPage` |
| 18 | Manual planning workspace | **EXISTS** | `PlanningBoardPage`, `EligibleOrdersPanel`, `TripCard`, `TripDetailDrawer`, `CreateTripDrawer`, `TripVehicleDrawer` |
| 19 | Trip / TripStop | **EXISTS** | `Trip`, `TripStop`, `TripStopPlanner`, `TripService`, `TripAssignmentService` |
| 20 | Capacity + double-booking guard | **EXISTS** | `PlanningCapacityService`, `CapacityLimits`, partial unique index `uq_trip_vehicle_active_planning_date` (V16) |
| 21 | Automatic planning V1 | **EXISTS (untested at service level)** | `PlanningEngine` + `HeuristicPlanningEngine` (pure, 15 unit tests) + `AutoPlanningService` + `AutoPlanDrawer`. See P2-3/P2-4/P2-5 |
| 22 | **Shipment execution lifecycle** | **MISSING** | `TripStatus` is `DRAFT / CONFIRMED / CANCELLED`. No `READY_FOR_DISPATCH`, `DISPATCHED`, `IN_TRANSIT`, `COMPLETED`; no actual departure/arrival times |
| 23 | **Stop events / execution exceptions** | **MISSING** | No arrival/loaded/departed/delivered event, no exception reason model |
| 24 | **POD / documents** | **MISSING** | No entity, no storage decision, no endpoint |
| 25 | **Tracking abstraction** | **MISSING** | Deferred by standing decision; no port exists yet either |
| 26 | **Rates / costing** | **MISSING** | No tariff, rate card, cost or freight-charge concept anywhere |
| 27 | **Carrier tendering** | **MISSING** | No offer, acceptance, expiry or preference order |
| 28 | **Control tower** | **MISSING** | `Permission.MONITORING_TRANSPORT_READ` and `Capability.TRANSPORT_MONITOR_VIEW` exist and are granted; nothing consumes them |
| 29 | **Alerts / notifications** | **PARTIAL (shell only)** | `NotificationsMenu.tsx` exists in the top bar; no backend notification concept behind it |
| 30 | **KPIs / reporting** | **PARTIAL** | `DashboardPage` shows real counts, but each is the `totalElements` of a `size:1` list query. No KPI endpoint, no aggregation, no time series |
| 31 | **SaaS admin / settings** | **MISSING** | `/admin/security` is a `PlaceholderPage`. `IAM_VIEW`/`IAM_MANAGE` capabilities exist; the only IAM endpoints are `/me` and `/companies` (context switch). No user, membership, role, organization or company administration |
| 32 | Integration hub / webhooks | **PARTIAL** | Machine clients, scopes, secret rotation and an inbox exist (`IntegrationClientController`); there is no webhook subscription/delivery and no admin UI for clients |
| 33 | Google Maps | **EXISTS** | `googleMapsLoader`, `LocationPickerMap`, `StopsMap`, `TripStopMap`, documented fallback to manual lat/long |
| 34 | Audit trail | **PARTIAL** | `AuditRecorder`, `AuditEvent` (V22), `AuditAction` including `AUTO_PLAN` — write path only. `AUDIT_VIEW` capability exists; **no read endpoint and no screen** |
| 35 | Permissions / RLS | **EXISTS** | `Permission`, `Capability`, V4/V5/V13, `TenantScopedDataSource` |
| 36 | Frontend design system | **EXISTS** | 20 components under `shared/ui/components`, Bootstrap + SweetAlert2, ES/EN across 15 bundles, responsive at 10 viewports |
| 37 | Observability | **PARTIAL** | `CorrelationIdFilter`, Actuator, `SystemInfoController`. No metrics, no structured request log, no tracing |
| 38 | Trips screen | **MISSING (menu entry exists)** | `/trips` is a `PlaceholderPage` while being a first-class sidebar item under `TRIPS_VIEW` |
| 39 | Account screen | **MISSING (route exists)** | **[2nd pass]** `/account` is a third `PlaceholderPage` (`router.tsx:37`), reachable from the user menu. The first pass counted two placeholders; there are three: `account`, `trips`, `admin/security` |

### 4.2 Summary

- **EXISTS: 20** — items 1, 2, 3, 4, 6, 8, 9, 10, 12, 14, 15, 16, 17, 18, 19, 20, 21, 33, 35, 36
- **PARTIAL: 8** — items 5, 7, 13, 29, 30, 32, 34, 37
- **MISSING: 11** — items 11, 22, 23, 24, 25, 26, 27, 28, 31, 38, 39

The master context's claim that "most of the TMS already exists" is **true for master data,
orders, planning and integration**, and **false for everything downstream of confirmation**.
The product plans a shipment well and then stops.

---

## 5. Domain duplication

**None found.** This is a change from the V3 baseline and the reason to record it:

- `tms.origin` / `tms.destination` are **frozen, not merely deprecated**. V23 revokes every
  write privilege from the application role, so "not a source of truth" is a grant rather
  than a convention. The tables remain readable as the recovery path for a bad V14/V23 merge;
  dropping them is a later migration, deliberately gated on the SQL having run once.
- There is **no** `OriginController` / `DestinationController` and no `/masterdata/origins`
  endpoint. `originsApi.ts` is 27 lines that call `fetchLocations({ role: 'ORIGIN' })`.
- `OriginsPage` / `DestinationsPage` are two-line delegations to `LocationsPage`.
- Column names (`route.origin_id`, …) were kept on purpose: renaming them would break the
  published inbound contract v1 for a synonym. `COMMENT ON COLUMN` carries the new meaning.

One piece of **stale prose**, not duplication: `shared/ui/navConfig.ts:35-37` still describes
Origins and Destinations as "compatibility projections" of migration V14. V23 retired the
projections. Recorded as P2-7.

---

## 6. Findings

### P0 — none

No defect that breaks a flow, leaks data across tenants, or exposes a secret was found.

### P1-1 — The database verification layer is unexecuted; V14–V23 have never been applied *(carried)*

329 of the backend tests are gated on `@EnabledIf(DockerAvailability#isAvailable)` and are
reported as **skipped**, not passed *(count carried from the V3 retry, unverified this run)*.
That set is exactly the layer the product's non-negotiable rules depend on: Flyway apply,
RLS/tenant isolation, schema exposure, the double-booking index, inbound idempotency and the
full vertical smoke.

Root cause on this host: Docker Desktop's Linux engine is unreachable because its backing WSL
distribution is missing. The native PostgreSQL on `localhost:5432` cannot substitute — V1 runs
`CREATE EXTENSION postgis` and the native install has no `postgis.control`.

**New this run:** V23 joins the unproven set, and it is the riskiest of the ten — it repoints
six foreign keys and revokes grants (§2.2).

**Status: open.** Not resolvable by unattended automation; it is a machine repair.

### P1-2 — A routine backend start could apply the ten unproven migrations to a remote Supabase project *(carried, re-verified twice)*

Verified directly against the untracked, git-ignored `backend/tms-api/.env`, by matching
variable names and host *class* only — no value was read or printed:

- `TMS_DB_URL` matches `.*(supabase\.co|pooler)` and does **not** match `localhost|127.0.0.1`;
- `TMS_FLYWAY_ENABLED=true`.

**[2nd pass]** Both matches were re-run independently and both still hold — the finding is
live, not stale. One detail the first pass did not record: a sibling `.env.remote.hold` sits
next to `.env`. Whatever its intent, the *active* `.env` is the one pointing at the remote
pooler, so the hold file provides no protection.

Spring is configured `baseline-on-migrate: false`, `validate-on-migrate: true`. So exporting
that file and starting the backend runs V14–V23 — 1439+ lines of never-executed SQL, including
a `NOT NULL` backfill (V16) and V23's FK repoint — against a live project.

Nothing loads the file automatically (no dotenv dependency, no `spring.config.import`,
`scripts/dev-backend.sh` does not source it), so it takes a deliberate human gesture. The
gesture is an ordinary one.

**Status: open.** Deliberately not "fixed" by this job: editing a developer's untracked `.env`
is not an audit's business, and adding a Flyway host guard is a code change this session
cannot test (§7).

### P1-3 — The overnight runner cannot execute a single quality gate, for all eighteen jobs *(new, 3rd pass)*

The first two passes reported their build commands as "refused before execution" and correctly
called it a session constraint. The third pass found **why**, and the cause is in the pack's own
runner rather than in the machine or the sandbox.

`tms-overnight-sellable-v4/scripts/Start-TmsOvernight.ps1:104` launches every job as:

```powershell
$args = @("-p", "--permission-mode", $PermissionMode)   # $PermissionMode defaults to "acceptEdits" (line 28)
```

`acceptEdits` auto-approves **file writes only**. It does not approve command execution, and `-p`
is non-interactive, so there is no human present to approve one. Every `./mvnw`, `npm`, `npx
playwright` and `docker` invocation is therefore declined before it starts — deterministically,
in every job, on every attempt. No `--allowedTools` is passed either (verified at lines 104-112:
the argument list is exactly those three elements).

Confirmed empirically in this session: `node --version` runs and returns `v24.18.0`, while
`java -version`, `docker info`, `npm run typecheck` and `npm --prefix … run typecheck` are each
refused. Process spawning as such is not blocked — the specific build toolchain is unapproved.

**It blocks the commit step too, not only the gates.** Read-only git is approved
(`git status`, `git rev-parse`, `git log`, `git diff` all executed here) but **`git add` is
refused**. Every job in this pack is required to end with a local commit and to report
`COMMIT=<sha>`; under the current runner none of them can, so each job's work stays untracked in
the working tree and the per-job history the pack is built around never materialises. This job
is itself an instance: its deliverable is written and correct, and `COMMIT=none` below is caused
by this finding rather than by anything about the audit.

**Why this matters more than its severity suggests.** `QUALITY_GATES.md` requires every job to
report `BACKEND_TESTS`, `FRONTEND_TESTS`, `E2E_TESTS` and `COMMIT`. Under the current runner
every job can only ever report `not-run` and `none`, so the pack's central safety property —
that a regression is caught the night it is introduced — does not hold for any of the seventeen
build jobs. Jobs 01-16 will write code that is never compiled, let alone tested, accumulating
uncommitted in one working tree; and Job 17's "full verification" cannot verify anything.
Because the changes never commit, a later job also cannot see where an earlier one stopped.

Note that this is **not** the same constraint as P1-1. P1-1 is a machine repair (Docker/WSL) that
blocks ~57% of backend assertions. P1-3 blocks *100% of all gates including the ones that would
otherwise pass today* — the frontend suite, typecheck, lint, build and the 40% of backend tests
that need no database. Fixing P1-3 is a one-line environment change; fixing P1-1 is not.

**Fix, before Job 01 runs:**

```powershell
$env:TMS_CLAUDE_PERMISSION_MODE = "bypassPermissions"   # honoured at line 28
```

The repository is the correct blast radius for that setting here: the runner already sets
`-WorkingDirectory $RepoRoot`, the test profile cannot reach a database (§7), and the standing
git/DB rules in `CLAUDE.md` remain in force regardless of permission mode. If a narrower grant is
preferred, add `--allowedTools` for `Bash(./mvnw:*)`, `Bash(npm:*)` and `Bash(npx playwright:*)`
at line 104 instead.

**Status: open.** Not self-fixable — a job cannot widen the permissions of the process running it.

### P2 findings

| Id | Finding | Verified |
|---|---|---|
| P2-1 | `FrequencyException` has `exceptionDate`, `serviceOverride`, `note` — and **no cutoff override**. Cutoff lives on `FrequencyWeeklyRule` only, so "24/12 open but cutoff 11:00" cannot be expressed. Job 01's gap A is real | Yes, this run |
| P2-2 | `RouteStop` carries `route`, `companyId`, `destinationId`, `sequence` — and **no `serviceTimeOverride`**. `Location.serviceTimeMinutes` exists (V14), so `effectiveServiceTime = override ?? location.serviceTimeMinutes` has one half only. Job 01's gap B is real | Yes, this run |
| P2-3 | **`AutoPlanningService` has zero test coverage** — no unit test, and no *skipped* integration test either (`auto-plan` matches nothing under `src/test`). Only the pure `HeuristicPlanningEngine` is covered (15 tests). Repairing Docker would not close this gap. Mockito is on the classpath via `spring-boot-starter-test`, so a pure unit test is feasible today | Yes, this run |
| P2-4 | On `apply`, an order refused by `TripService` (lost race) is appended to `unplanned` while remaining listed on its `ProposedTripView`. `assertEveryOrderAccountedFor` runs on the *proposal*, not on the applied outcome, so the screen can show `ordersConsidered ≠ planned + unplanned`. Database state stays correct; the report does not | Yes, `AutoPlanningService:141-169`, `AutoPlanView:53-57` |
| P2-5 | If every order of a proposed trip is refused, `apply` still keeps the created trip (`created.add(trip)` is unconditional), leaving an empty draft trip that has booked a vehicle for the date | Yes, `AutoPlanningService:141-159` |
| P2-6 | `loadFreeVehicles` passes `UUID.randomUUID()` as the `idNot` argument of `existsByCompanyIdAndVehicleIdAndPlanningDateAndStatusNotAndIdNot`. It is correct — a random UUID excludes nothing — but it reads as a bug and depends on a finder shape that exists for another caller | Yes, `AutoPlanningService:277-283` |
| P2-7 | `shared/ui/navConfig.ts:35-37` still calls Origins/Destinations "compatibility projections" of V14; V23 removed them (§5) | Yes, this run |
| P2-8 | Frontend ships as a single ~1.12 MB chunk | Carried, unverified this run |
| P2-9 | Six oxlint `react(only-export-components)` warnings | Carried, unverified this run |
| P2-10 | ES/EN bundle parity is exact but has no automated assertion | Carried, unverified this run |

### Previously-listed P2s that are **closed**

- *"Playwright more stable with 2 workers than 4."* Resolved by design:
  `playwright.config.ts:20` uses `workers: process.env.CI ? 2 : 4` with the reason recorded
  in a comment (all workers share one Vite dev server).
- *"Legacy `tms.origin`/`tms.destination` frozen."* Confirmed frozen at the grant level by
  V23. Not a finding; the deferred drop is a deliberate, documented decision.
- *"VehicleType has no dimensions/temperature/axles."* Correct as-is. No rule consumes them.
  Adding them before a rule exists would be exactly the premature modelling the product
  principle forbids.

---

## 7. Quality gates in this session

| Gate | Result |
|---|---|
| Backend tests (`./mvnw -B test`) | **not-run** |
| Frontend unit (`npm test`) | **not-run** |
| E2E (`npx playwright test`) | **not-run** |
| Typecheck / lint / build | **not-run** |
| DB certification | `BLOCKED_ENVIRONMENT` |

**Why not-run.** Every process-spawning command other than `git` and directory listing was
refused by this session's permission layer (`docker info`, `npm --version`, `mvnw` — all
declined before execution). This is a *session* constraint, distinct from P1-1's *machine*
constraint, and it is reported rather than worked around. No suite was estimated, inferred or
copied forward as if it had run.

**[3rd pass — root cause found]** The wall is now explained rather than merely observed: the
runner passes `--permission-mode acceptEdits`, which approves edits but not commands, under a
non-interactive `-p` session. See **P1-3** for the evidence and the one-line fix. The third
session confirmed the shape precisely — `node --version` executes and returns `v24.18.0`, while
`java`, `docker`, `npm` and `mvnw` are each refused — so this is an unapproved-toolchain problem,
not an inability to spawn processes. It also re-confirmed the second pass's safety point from
source: `application-test.yml` sets `spring.flyway.enabled: false` and declares no datasource, so
running the backend suite is safe with respect to P1-2.

**[2nd pass]** The second session retried the gates through four different invocations —
`bash scripts/backend-test.sh`, `./mvnw -B test` under the module directory, `mvnw.cmd -f
backend/tms-api/pom.xml test` from the root, and a bare `npm --version` — and every one was
refused before execution by the same layer. The wall is the session sandbox, not the project:
`backend/tms-api/src/test/resources/application-test.yml` sets `spring.flyway.enabled: false`
and supplies no datasource, so running the backend suite would have been safe with respect to
P1-2. It simply could not be started.

**Static test inventory** (a floor, not a count — parameterized cases expand at runtime).
Re-measured in the second pass; where it differs from the first pass the method is noted:

| Suite | Files | Annotated / declared cases |
|---|---|---|
| Backend | 66 test classes (+2 support: `DockerAvailability`, `SecurityTestConfiguration`) | 771 `@Test`/`@ParameterizedTest` |
| Frontend unit | 56 files | 420 `it(`/`test(` — identical to the first pass |
| E2E | 11 specs | 52 line-initial `test(` |

**[2nd pass]** The backend figure is higher than the first pass's 755/67 and the E2E figure
lower than its 59, both for method rather than for change: the backend regex here counts
`@ParameterizedTest` separately, and the E2E regex anchors at line start so it excludes
`test.describe(` wrappers. Neither suite gained or lost files.

**Docker-gated share.** 31 of the 66 backend test classes reference
`DockerAvailability`, carrying roughly 436 declared cases — about 57% of the backend floor.
That is the shape of P1-1: the majority of declared backend assertions cannot run on this
host. For reference, the V3 retry measured 671 backend (329 skipped), 491 frontend, 68 E2E.
The static floors above are consistent with growth since then; they are not a substitute for
executing the suites.

---

## 8. Sellability gaps, ranked

What a buyer would notice, in the order they would notice it.

1. **The product stops at "plan confirmed."** There is no dispatch, no in-transit, no
   completion, no actual times (item 22). Everything a customer calls "operations" is absent.
2. **No proof of delivery and no execution exceptions** (items 23, 24). A TMS that cannot
   answer "did it arrive?" is a planning tool.
3. **No driver** (item 11). The one master missing from the shipment picture; also the
   prerequisite for any driver-facing surface.
4. **No money.** No rates, no cost, no planned-vs-actual (item 26). This is what turns the
   planning board into something with a P&L attached, and it gates tendering (item 27).
5. **Three routes that lead nowhere.** **[2nd pass]** `/trips`, `/admin/security` and
   `/account` are placeholders, and the first two are promoted to first-class sidebar items
   (items 31, 38, 39). In a demo this reads worse than not having the entries.
6. **No tenant administration.** Users, memberships, roles, companies — the permission model
   is complete and there is no way to administer it in the product (item 31). Every
   onboarding is a SQL insert today.
7. **Audit is write-only** (item 34). Events are recorded, `AUDIT_VIEW` is granted, nothing
   can read them.
8. **No control tower and no alerts** (items 28, 29). `NotificationsMenu` is a shell.
9. **KPIs are list counts** (item 30). Honest, and explicitly documented as such in
   `DashboardPage`'s own comment — but not a dashboard a buyer scores well.
10. **No webhooks** (item 32). Outbound is poll-only; every partner must write a poller.

---

## 9. Guidance for the following jobs

Nothing in this pack should be skipped wholesale — but three jobs must **audit before
building**, because a large part of what they name already exists.

| Job | Instruction |
|---|---|
| `01` frequency & route hardening | **Build.** Both gaps are open and confirmed (P2-1, P2-2). Do not touch the surrounding CRUD, the exceptions editor, `LocationEligibilityEvaluator` or `FrequencyCalendar` — they are complete |
| `02` shipment execution V1 | **Build**, but extend `TripStatus`/`Trip`; do not create a parallel execution aggregate. `TripStopPlanner`, `ShipmentTimeRules` and the outbox already define the semantics to respect. **Critical: this job needs `V24`** |
| `03` drivers | **Build.** Nothing exists. Model it as a company-scoped master with the same tenant-safe FK shape the fleet masters use |
| `04` stop events & exceptions | **Build** on `TripStop`. Depends on `02` |
| `05` POD | **Build.** Note the standing Supabase Storage deferral — an ADR is required before choosing storage |
| `06` tracking abstraction | **Port only.** Live tracking is deferred by standing decision; deliver the boundary, not an implementation |
| `07` rates & costing | **Build.** Nothing exists |
| `08` tendering | **Build.** Depends on `07` and on the carrier master (which exists) |
| `09` control tower | **Build.** `MONITORING_TRANSPORT_READ` is already defined and granted — wire the screen to it, do not invent a new permission |
| `10` alerts | **Build.** `NotificationsMenu` is a shell to fill, not a component to replace |
| `11` KPIs | **Build** a real aggregation endpoint. Do not keep counting via `size:1` list queries |
| `12` SaaS admin | **Build.** The `IAM_*` and `INTEGRATION_*` permission catalogue is complete and unused — this job is screens and endpoints over an existing model, not a new model |
| `13` integration hub | **Audit first.** Machine clients, scopes, secret rotation, inbox and the outbox already exist and are documented. The genuinely new part is *webhook delivery*. Do not rebuild the inbound API |
| `14` UX polish | **Audit first.** The design system, ES/EN parity, responsive coverage and SweetAlert2 conventions already exist. Prefer closing P2-7, P2-8 and the two placeholder screens over restyling |
| `15` security / perf / observability | **Audit first.** Tenancy defence in depth, JWT validation, `Capability`-vs-`Permission` separation and RLS are complete and reviewed (`docs/security/OVERNIGHT_V3_TENANCY_REVIEW.md`). The real gaps are metrics, structured logging and the audit *read* path (item 34) |
| `16` demo readiness | Depends on the placeholder screens being gone |
| `17` verification & handoff | Must not report a DB PASS while P1-1 stands |

**Standing constraints for every job:** next migration version is `V24`; `V1`–`V23` are
immutable; no migration may be applied to the remote project; `tms-overnight-sellable-v4/`
and its zip are never staged.

---

## 10. Risk register

| Id | Risk | Severity |
|---|---|---|
| E-1 | Docker/Testcontainers unavailable → 329 assertions skip and ten migrations stay unproven | P1-1 |
| E-2 | `backend/tms-api/.env` points `TMS_DB_URL` at a remote Supabase pooler with `TMS_FLYWAY_ENABLED=true` | P1-2 |
| E-3 | No session could run a build/test command, so no gate was measured. **Root cause identified in the third pass:** the runner's `--permission-mode acceptEdits` approves edits but not commands, under non-interactive `-p`. Affects all eighteen jobs | **P1-3** |
| R-1 | Each pack job that adds schema increases the unproven-SQL backlog. The backlog is already ten migrations deep, and the first real apply will surface every error at once | High |

**Mitigation for R-1:** every job in this pack should keep its migration as small and as
independently revertible as it can, and should assume the first execution will fail somewhere.

---

## 11. Verdict

- P0: **0**
- P1: **3** (P1-1 unexecuted DB layer and ten unproven migrations; P1-2 remote-pooler `.env`
  with Flyway enabled; **P1-3 the runner cannot execute any quality gate**) — none introduced by
  this job, all three requiring a human
- P2: **10** (7 verified in the first pass, **re-verified against source in the second**, and
  sampled again in the third with no correction; 3 carried and still unverified — P2-8, P2-9,
  P2-10, all of which need a build to check)

`JOB_STATUS=PARTIAL`

The deliverable — this audit — is complete, and every claim in it was checked against the
current tree, the load-bearing ones twice by two independent passes (§12). It is not a PASS
because no quality gate could be executed here and the DB certification remains blocked. It is
not a FAIL because nothing is broken and no defect was introduced; under a strict reading of
the pack's gate ("FAIL if any P1 is unresolved") the two carried P1s would force FAIL, and
they are reported at full strength above so that reading stays available to the supervisor.

**What the second pass changes about the verdict: nothing, and that is the useful result.**
The audit's module map — which is what Jobs 01–17 consume — survived independent
re-derivation from source with three corrections, none of which move a job between "build" and
"skip". §9's guidance can be acted on.

**What the third pass changes: the module map is now confirmed by three independent
derivations and needs no fourth.** A 17-claim sample was re-checked against source and nothing
was overturned; further re-auditing of the same tree has reached diminishing returns. The one
material addition is P1-3, and it is addressed to the operator rather than to a job: until the
runner's permission mode is widened, every remaining job in this pack will produce untested code
and report `not-run` for gates that would otherwise pass today. **That single environment change
is worth more to this pack than any further audit work.**

---

## 12. Second-pass verification log

What the second session re-checked against source, and the evidence it accepted. Claims not
listed here were left as the first pass wrote them.

| Claim re-checked | Evidence | Result |
|---|---|---|
| Branch, HEAD, working tree (§1) | `git rev-parse`, `git status --porcelain`, `git diff --stat` | Confirmed — `dev` @ `b13e660`, tracked tree clean, only the pack and its zip untracked |
| Latest migration is V23, contiguous V1–V23 (§2.1) | Directory listing of `db/migration` | Confirmed — 23 files, no gaps, no duplicates. Next free version is **V24** |
| Flyway is sole migration owner (§2.3) | Recursive listing of `supabase/` incl. hidden entries | Confirmed — only `config.toml`, `README.md`, `.gitignore`, `seeds/`. No `migrations/`, no `.temp/` |
| No scheduler / background jobs (§3) | Grep `@Scheduled\|@Async\|@EnableScheduling` over `src/main/java` | Confirmed — 0 occurrences |
| Driver is MISSING (item 11) | Grep `driver` over `src/main/java` | Confirmed — 3 hits, all prose in `Trip`, `TripAssignmentService`, `PlanningRunService`. No entity, no table, no endpoint |
| Shipment execution is MISSING (item 22) | Read `TripStatus.java` | Confirmed — `DRAFT / CONFIRMED / CANCELLED` only, and the enum's own comment scopes it as "the minimal V1 trip lifecycle" |
| Rates, tendering, POD are MISSING (items 24, 26, 27) | Grep `rate\|tariff\|costing\|freight_charge\|tender\|proof_of_delivery\|pod` over `src/main/java` | Confirmed — the single hit is `IntegrationSecrets` (substring of "rotate"). No such domain exists |
| No webhook delivery (item 32) | Grep `webhook\|notification\|subscri` over `src/main/java` | Confirmed — one prose hit in `IntegrationShipmentController`. Outbound stays poll-only |
| Audit read path, control tower, KPI endpoint, SaaS admin all MISSING (items 28, 30, 31, 34) | Full `*Controller.java` glob — 23 controllers | Confirmed — no `Audit*`, `Kpi*`, `Dashboard*`, `User*` or `Membership*` controller. IAM exposes only `/me` and `/companies` |
| P2-1 no cutoff override | Read `FrequencyException.java` | Confirmed — fields are `frequencyId`, `exceptionDate`, `serviceOverride`, `note` + audit columns. No cutoff |
| P2-2 no `serviceTimeOverride` | Read `RouteStop.java` | Confirmed — `route`, `companyId`, `destinationId`, `sequence` + audit columns only |
| P2-3 `AutoPlanningService` untested | Glob `**/*Planning*` under `src/test` | Confirmed — 4 files, none targeting `AutoPlanningService` |
| P2-4 applied outcome can disagree with the trip view | Read `AutoPlanningService:141-169` | Confirmed — `outcome` reuses `proposal.trips()` unchanged while refused orders are appended to `unplanned`, so an order can appear on both sides |
| P2-5 empty trip survives total refusal | Read `AutoPlanningService:141-159` | Confirmed — `created.add(trip)` at line 158 is outside the try, unconditional |
| P2-6 `UUID.randomUUID()` as `idNot` | Read `AutoPlanningService:277-283` | Confirmed verbatim, and confirmed harmless-but-misleading as described |
| P2-7 stale navConfig prose | Read `navConfig.ts:25-44` | Confirmed — lines 34-37 still call Origins/Destinations "compatibility projections" of V14 |
| P1-2 remote `.env` + Flyway on | Two anchored greps on `.env` (name + host class only, no values read) | Confirmed still live; see the P1-2 entry |

**Corrections the second pass made:** item 39 (`/account` is a third placeholder), the §7
inventory figures and their method note, and the §8 item 5 count. No claim was overturned.

---

## 12b. Third-pass verification log

A 17-claim sample, chosen for load-bearing weight rather than coverage: the facts that decide
which jobs build, plus every finding a job is expected to close.

| Claim re-checked | Evidence | Result |
|---|---|---|
| Branch, HEAD, working tree (§1) | `git rev-parse`, `git status --porcelain` | Confirmed — `dev` @ `b13e660`; only the pack, its zip and this doc's directory untracked |
| Latest migration V23, contiguous, next is V24 (§2.1) | Glob of `db/migration/*.sql` | Confirmed — 23 files, `V1`–`V23`, no gaps or duplicates |
| Flyway sole migration owner (§2.3) | Glob `supabase/**` | Confirmed — `config.toml`, `README.md`, `.gitignore`, `seeds/` only. No `migrations/` |
| Controller inventory (items 28, 30, 31, 34) | Glob `**/*Controller.java` — 23 files | Confirmed — no `Audit*`, `Kpi*`, `Dashboard*`, `User*` or `Membership*` controller; IAM is `MeController` + `CompanyContextController` only |
| Shipment execution MISSING (item 22) | Read `TripStatus.java` in full | Confirmed — `DRAFT / CONFIRMED / CANCELLED`, 16 lines, comment scopes it as "the minimal V1 trip lifecycle" |
| Driver / rates / tendering / POD MISSING (items 11, 24, 26, 27) | Word-boundary grep over `src/main/java` | Confirmed — 3 hits total, all prose comments containing "driver" (`Trip:407`, `TripAssignmentService:115`, `PlanningRunService:265`). Zero hits for tariff, rate_card, tender, proof_of_delivery, freight_charge |
| Three placeholder routes (items 31, 38, 39) | Grep `PlaceholderPage` over `src` | Confirmed — `router.tsx:37` account, `:53` trips, `:54` admin/security |
| P2-1 no cutoff override | Field grep on `FrequencyException.java` | Confirmed — `id`, `frequencyId`, `exceptionDate`, `serviceOverride`, `note` + 4 audit columns. **Job 01 gap A is real** |
| P2-2 no `serviceTimeOverride` | Field grep on `RouteStop.java` | Confirmed — `id`, `route`, `companyId`, `destinationId`, `sequence` + 4 audit columns. **Job 01 gap B is real** |
| P2-3 `AutoPlanningService` untested | Glob `**/*Planning*` under `src/test` — 4 files | Confirmed — `PlanningCapacityServiceTest`, `HeuristicPlanningEngineTest`, and two Docker-gated integration tests. None targets `AutoPlanningService` |
| P2-4 applied outcome disagrees with trip view | Read `AutoPlanningService:130-170` | Confirmed — line 167 appends `rejected` to `unplanned` while line 168 reuses `proposal.trips()` unchanged, so a refused order appears on both sides |
| P2-5 empty trip survives total refusal | Read `AutoPlanningService:141-159` | Confirmed — `created.add(trip)` at line 158 sits outside the try/catch and outside the order loop, so it runs unconditionally |
| P2-6 `UUID.randomUUID()` as `idNot` | Read `AutoPlanningService:277-283` | Confirmed verbatim at line 281 |
| P2-7 stale navConfig prose | Read `navConfig.ts:25-46` | Confirmed — lines 34-37 still call Origins/Destinations "compatibility projections" of V14 |
| P1-2 remote `.env` + Flyway on | Two count-only greps (no values read or printed) | Confirmed live — 3 matches for the remote host class, 1 for `TMS_FLYWAY_ENABLED=true` |
| Docker-gated share (§7) | Grep `DockerAvailability` under `src/test` — 32 files | Confirmed — 31 gated test classes + `DockerAvailability` itself, against 73 backend test files |
| Test-file inventory (§7) | `find` counts | Confirmed — frontend 56 test files, E2E 11 specs, both matching the second pass |
| **P1-3 runner cannot execute gates** | Read `Start-TmsOvernight.ps1:28,104-112`; empirical `node` vs `java`/`docker`/`npm` | **New finding** — `acceptEdits` + `-p`, no `--allowedTools` |

**Corrections the third pass made: none.** The module map has now been derived from source three
times independently, by three sessions, with agreement.

---

## 13. What a human must do before Job 01 proceeds

1. **Read P1-2 before starting the backend for any reason.** Repoint `TMS_DB_URL` at a local
   database or set `TMS_FLYWAY_ENABLED=false` until P1-1 is closed.
2. **Fix P1-3 first — it is the highest-leverage action on this list and takes one line.**
   Set `$env:TMS_CLAUDE_PERMISSION_MODE = "bypassPermissions"` before launching the pack (or add
   `--allowedTools` at `Start-TmsOvernight.ps1:104`). Without it, Jobs 01-16 write code that is
   never compiled and Job 17 verifies nothing. With it, the frontend suite, typecheck, lint,
   build and the non-Docker backend tests all become measurable **tonight**, without touching
   Docker. Re-running Job 00 after this change would also close P2-8, P2-9 and P2-10, which are
   carried purely for want of a build.
3. **Repair Docker** and require `Skipped: 0`. Run `smoke.EndToEndSmokeIntegrationTest` first.
