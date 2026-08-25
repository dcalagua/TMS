# Overnight Sellable V4 — Job 17: full verification and final handoff

- Date: 2026-08-21
- Job: `17` — verify, consolidate, and tell the truth
- Branch: `dev`. HEAD: **`b13e660`** — *unchanged by this pack*
- Latest migration: **V35**. This job created **none**
- Verdict: **`FINAL_STATUS=PARTIAL`** (§13)

> **How to read this.** Nothing in this report is carried forward on the authority of an earlier
> job's report. Every number was re-counted from the working tree in this session, and every finding
> was re-derived from source. Where this report disagrees with job 16, the disagreement is stated
> and the evidence given (§9.1, §9.4). Where a gate could not be run, it says `not-run` and does not
> estimate.

---

## 1. Executive summary

The TMS is a **substantially complete, well-architected transport management product** whose
business logic has never been executed against a database. That single sentence is the handoff.

What the eighteen-job pack produced is real: 16 new tables, 12 migrations, eight new functional
modules (drivers, trip execution, POD, tracking, rates, tendering, notifications, SaaS
administration), signed and retried webhooks, and a documentation set that is unusually honest about
its own limits. The code quality is high — the tenancy model is enforced in six independent layers,
the one background worker uses `SKIP LOCKED` with leases and commits before opening a socket, and
every one of the 16 new tables carries Row Level Security with an identically shaped policy.

What it does not have is proof. **Zero quality gates ran** — not in this session, and by the
evidence of the working tree, not in any session of this pack. Migrations V24 through V35 have never
been applied to any database. 443 of 1,243 declared backend assertions (35.6%) are gated behind a
Docker daemon that is not present. The `SchemaExposureIntegrationTest` suite — which would prove the
RLS claims of §7 rather than merely assert them — is one of the gated ones.

Three things follow, in order of urgency:

1. **`backend/tms-api/.env` still points at a live Supabase pooler with Flyway enabled.** Verified
   live in this session (§6.2). Someone starting the backend from an IDE applies twelve unproven
   migrations to a production project. This is the highest-consequence item in the repository and it
   is a one-line fix.
2. **The pack's entire output is uncommitted** — 435 working-tree entries, zero commits (§2). The
   runner cannot execute `git add`, so a per-job history no longer exists and cannot be
   reconstructed.
3. **The flagship feature has no test at any level.** `AutoPlanningService` — automatic planning,
   the thing that distinguishes this product — has no unit test, no integration test and no E2E
   test, and carries three confirmed defects, one of which violates a named architectural invariant
   (§9.3).

The product is **safe to demo and not safe to deploy**, and §11 and §12 say exactly where the line
falls.

---

## 2. Commits

**Commits created by this pack: zero.**

```
HEAD                    b13e660  docs(domain): frequency, route, fleet, order and planning contracts…
HEAD date               2026-08-20
Pack date               2026-08-21
Commits since HEAD      0
```

`b13e660` and the twenty-two commits before it belong to the **previous** pack (overnight-v3, dated
2026-08-19/20). Every commit in `git log` predates job 00 of this pack.

### 2.1 Working tree

```
Modified (tracked)      147
Untracked               288
Total                   435
```

### 2.2 Why there is no commit, and why there should not be one anyway

Two independent reasons, either sufficient:

**Reason one — the runner cannot stage.** `git add` and `git commit` are refused before execution by
this session's permission mode, as are `./mvnw`, `java`, `npm` and `docker` (§5.1). This is
**P1-3**, identified in job 00's third pass and unchanged since.

**Reason two — a per-job commit would not compile.** This is the more important one, because it
survives fixing P1-3. The pack's work is interleaved across tracked and untracked files:

| Path | State | Consequence |
|---|---|---|
| `shared/api/ApiExceptionHandler.java` | tracked, **modified** | imports from `shared/storage` |
| `shared/storage/` (10 files) | **entirely untracked** | so staging the handler alone breaks the build |
| `rates/`, `tracking/`, `notification/` | **entirely untracked** | whole modules, referenced from tracked files |
| `db/migration/V24…V35` | **entirely untracked** | 12 files |
| `CLAUDE.md` | tracked, **modified** | its diff references ADR-006/007, both untracked |

A commit scoped to one module produces a HEAD that does not compile; a commit of the whole tree
attributes eighteen jobs' work to one message. Both were declined. **`COMMIT=none`.**

The per-job history is unrecoverable. That consequence belongs to P1-3 and is the strongest argument
for fixing the runner before this pack is ever run again.

---

## 3. Features already existing, and only audited here

Re-derived from source in this session; not taken from any prior report.

| Area | Evidence |
|---|---|
| Canonical `Location` + `LocationType` + `LocationRole` | V23; `masters/locations` screen; Origins/Destinations survive as operational-use views, not parallel masters |
| Frequencies, weekly rules, exceptions | `FrequencyController`, `FrequencyCalendar`, `LocationEligibilityEvaluator` |
| Routes and ordered stops with reordering | `RouteController`, `RouteStop` |
| Carriers, vehicle types, vehicles, effective capacity | `fleet/`, `EffectiveCapacityResolver` |
| Vehicle double-booking invariant | `PlanningConstraintIntegrationTest` (Docker-gated), `TripService` |
| Orders V2, declared vs calculated totals | `OrderTotals` (13 unit tests), `OrderController` |
| Import Center — 5 entities | `LocationImport`, `CarrierImport`, `VehicleTypeImport`, `VehicleImport`, `OrderImport` controllers |
| Inbound M2M API with idempotency | `IntegrationOrderController`, `IntegrationLocationController`, integration inbox (V18) |
| Manual planning, Trip / TripStop | `PlanningRunController`, `TripController` |
| Transactional outbox | V20 |
| Google Maps picker with fallback | `LocationPickerMap`, `googleMapsLoader` |
| Auto Planning V1 — pure engine | `HeuristicPlanningEngine`, 15 unit tests. **The engine is covered; the service is not** — §9.3 |
| Tenant RLS runtime role (ADR-005) | `TenantScopedDataSource` — §7 |
| Business audit trail | `audit/` (committed in `635ba4e`) |

**Six architectural invariants re-verified this session:**

| Invariant | Method | Result |
|---|---|---|
| No parallel `Origin`/`Destination` physical masters | route + entity inspection | **Holds** — projections only |
| No controller trusts a client `companyId` | grep `companyId` across all `**/api/*.java` and `**/application/*Request.java` | **Holds** — the *only* occurrence in either layer is a comment in `PageQuery.java:25` explaining why it must never be accepted |
| Flyway is the sole schema owner | `hibernate.ddl-auto: none`, `clean-disabled: true`, no DDL in `supabase/` | **Holds** |
| Applied migrations are immutable | `git diff HEAD -- db/migration/` | **Holds** — empty output; V1–V23 byte-identical to HEAD |
| Every business table carries RLS | §7 | **Holds statically**, unproven at runtime |
| Auto planning never auto-confirms | `AutoPlanningService.apply` creates `DRAFT` trips only | **Holds** |
| `INPUT_ORDERS = PLANNED + UNPLANNED` | `AutoPlanningService:141-169` | **VIOLATED in the applied report** — §9.3, P2-4 |

---

## 4. Features added by this pack

| # | Feature | Migration | Backend | Frontend |
|---|---|---|---|---|
| 1 | Per-date cutoff override; per-stop service-time override | V24 | `FrequencyException.cutoffTimeOverride`, `RouteStop.serviceTimeOverrideMinutes` | `FrequencyFormDrawer`, `RouteFormDrawer` |
| 2 | Trip execution lifecycle — 6 states, actual times | V25 | `TripStatus`, `TripExecutionService` | `TripWorkspacePage`, `TripTimeline` |
| 3 | Drivers + licence-checked assignment | V26 | `Driver`, `DriverController` | `DriversPage`, `TripDriverDrawer` |
| 4 | Stop execution, transport events, typed exceptions | V27 | `TripStopExecutionService` | `TripWorkspacePage` |
| 5 | Delivery results and POD evidence | V28 | `TripDeliveryService`, `EvidenceStoragePort` (ADR-006) | `DeliveryDrawer` |
| 6 | Vehicle tracking contract | V29 | `TrackingIngestionService`, two ports (ADR-007) | `TripTrackingCard` |
| 7 | Rate cards and trip costing | V30, V33 | `RateCardSelector`, `TripCostCalculator` | `RateCardsPage`, `TripCostCard` |
| 8 | Carrier tendering | V31 | `TripTenderService`, `IntegrationTenderController` | `TripTenderCard` |
| 9 | Notifications / alerts | V32 | `NotificationService`, 7 types | `NotificationsMenu` |
| 10 | Company settings + SaaS user administration | V34 | `CompanyAdministrationController`, `UserAdministrationController` | `CompanySettingsPage`, `UsersPage` |
| 11 | Signed, retried, suspendable webhooks | V35 | `WebhookDispatchService`, `WebhookDeliveryQueue`, SSRF guard | `IntegrationsPage` |
| 12 | Control tower | — | `ControlTowerController` (owns no data) | `ControlTowerPage` |
| 13 | KPI aggregation + CSV export | V33 | `KpiService` | `ReportsPage`, `DailyColumnChart` |
| 14 | Product/sales documentation pack | — | — | `docs/product/` (7 docs + 7 demo fixtures) |

**Surface totals, counted this session:** 37 REST controllers · 22 application screens · 30 frontend
API-client modules · 1,934 i18n keys per language.

### 4.1 One design worth singling out

`WebhookDeliveryQueue` (V35) is the product's only background worker, and it is the one place where
RLS is deliberately *off* — a scheduled thread has no `CompanyScope`, so `TenantScopedDataSource`
leaves the connection on the owner role and the policies do not filter. That is documented at the
top of `WebhookDispatchScheduler` rather than discovered later, and the code earns it: `claim()`
locks with `SKIP LOCKED` under a lease sized at twice the request timeout, commits before any socket
opens, and `record()` runs `REQUIRES_NEW` per delivery so one failure cannot roll back twenty
successes. Every write carries the `companyId` copied off the row it came from. Reviewed
specifically for cross-tenant leakage in this session; **none found**.

---

## 5. Test matrix

### 5.1 What ran

**Nothing.** Four independent execution attempts in this session, all refused before execution:

| Attempt | Result |
|---|---|
| `./mvnw -v` / `mvnw.cmd -v` | refused |
| `java -version` | refused |
| `npm run typecheck --prefix frontend/tms-web` | refused |
| `docker info` | refused |

This is **P1-3**: the runner launches jobs with `--permission-mode acceptEdits`, which approves file
writes and never command execution, under a non-interactive `-p` session. Read-only git, `ls` and
`grep` work — which is why this report exists at all.

| Gate | Result |
|---|---|
| Backend unit | **not-run** |
| Backend integration / Testcontainers | **not-run** |
| Flyway replay | **not-run** |
| PostGIS | **not-run** |
| RLS / cross-tenant | **not-run** |
| Vertical smoke | **not-run** |
| Frontend unit | **not-run** |
| Typecheck / lint / build | **not-run** |
| Playwright E2E | **not-run** |
| DB certification | **`BLOCKED_ENVIRONMENT`** |

No suite was estimated, inferred, or carried forward as though it had run. In particular, the
`674 / 469 / 71` figures quoted in the pack's master context are **from a previous pack** and were
not reproduced here; the static inventory below supersedes them.

### 5.2 Static inventory — counted from source in this session

Declared test methods, therefore a floor: a `@ParameterizedTest` expands at runtime.

| Suite | Files | Declared | Executable here | Blocked |
|---|---|---|---|---|
| Backend | 110 | **1,243** | 800 | **443 (35.6%)** across **31** Docker-gated classes |
| Frontend unit | 73 | **574** | 574 | 0 — blocked by P1-3 only |
| E2E (Playwright) | 11 specs | **52** | 52 | 0 — blocked by P1-3 only |

The Docker gate is `@EnabledIf(DockerAvailability.CONDITION)`, present on exactly **31** classes
(verified by grepping `DockerAvailability.CONDITION`, not by name pattern). The gate is honest by
design: `DockerAvailability` skips and *reports skipped*, never falls back to a shared database, and
`PostgresTestDatabase` takes its coordinates from the container "and from nowhere else". No test in
this repository can reach a remote database.

**Playwright configuration** (`playwright.config.ts:20`): `workers: process.env.CI ? 2 : 4`, with
the four-worker contention documented in the adjacent comment. The stable configuration the brief
asks for is therefore already the committed default for CI. Not exercised — see P1-3.

### 5.3 The gap that matters most

The 443 blocked assertions are not evenly distributed. They are precisely the ones that would prove
the claims this report cannot otherwise support: `FlywayMigrationIntegrationTest` (replay),
`SchemaExposureIntegrationTest` (10 RLS/exposure assertions), `TenantRlsIsolationIntegrationTest`,
`IntegrationTenancyIsolationIntegrationTest`, `PlanningConstraintIntegrationTest` (29 assertions
including double-booking), and `EndToEndSmokeIntegrationTest`.

**Everything in §7 is a static reading of SQL and Java. The suite that would turn it into a
measurement exists, is well built, and has never run against V24–V35.**

---

## 6. Database certification

**`DB_CERTIFICATION=BLOCKED_ENVIRONMENT`.** Not `PASS`. Not `FAIL`.

### 6.1 Migration state

```
Latest migration        V35
Created by this pack     V24 … V35  (12 files, all untracked)
Created by this job      none
V1–V23                  immutable — `git diff HEAD -- db/migration/` is empty
Applied anywhere        V24–V35: never
```

No `Vnn` file was edited. No repeatable migration exists. Migration numbering is contiguous with no
gaps and no duplicates.

### 6.2 The remote database — verified live in this session

Checked with two anchored greps that read no values and printed no secrets:

```
TMS_DB_URL target       REMOTE       (no localhost / 127.0.0.1 match)
TMS_FLYWAY_ENABLED      true
spring.flyway.enabled   true         (application.yml:27)
baseline-on-migrate     false
```

**P1-2 is live and unchanged.** `backend/tms-api/.env` still resolves to a hosted Supabase pooler
with Flyway on. `application-local.yml` defaults to `localhost:54322`, and the shell scripts do not
source `.env` — so a backend started from a terminal fails to connect while the *same* backend
started from an IDE reaches production and migrates it. Twelve unproven migrations, sixteen new
tables, one `CREATE EXTENSION`, and every RLS policy in §7 would be applied in a single unattended
startup.

**`REMOTE_DB_MUTATED=NO` for this job**: no database connection of any kind was opened, no `psql`
was run, and no migration was applied. For the pack as a whole the claim rests on each job's own
report; it cannot be independently confirmed without connecting to the remote, which was not
authorized and was not done.

---

## 7. Security and tenant status

The six-layer model is intact end to end. Verified this session:

| Layer | Mechanism | Verified |
|---|---|---|
| 1 | Supabase JWT validated in Spring Security (`SupabaseJwtDecoders`, 14 tests) | Yes |
| 2 | App User + Membership resolved **server-side** (`PrincipalResolutionService`) | Yes |
| 3 | `CompanyScope` derived from membership, never from the request | Yes — §3 |
| 4 | Capability checks per endpoint (`Capability`, `Permission`) | Yes |
| 5 | Repository queries carry the company predicate | Yes (sampled) |
| 6 | PostgreSQL RLS on the non-owner `tms_app` role (ADR-005) | Statically — below |

### 7.1 RLS coverage of the 16 new tables — exact

| Migration | New tables | `ENABLE ROW LEVEL SECURITY` | `CREATE POLICY` |
|---|---|---|---|
| V24, V25, V33 | 0 | 0 | 0 |
| V26 driver | 1 | 1 | 1 |
| V27 transport_event, trip_exception | 2 | 2 | 3 |
| V28 order_delivery, delivery_evidence | 2 | 2 | 3 |
| V29 tracking_position | 1 | 1 | 1 |
| V30 rate_card, trip_cost, trip_cost_component | 3 | 3 | 3 |
| V31 trip_tender | 1 | 1 | 1 |
| V32 notification | 1 | 1 | 1 |
| V34 company_settings | 1 | 1 | 1 |
| V35 webhook_subscription + 3 | 4 | 4 | 4 |
| **Total** | **16** | **16** | **18** |

**16 tables, 16 enables — no table was missed.** Every policy has the identical shape:

```sql
CREATE POLICY p_tenant_company_scope ON tms.<table>
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
```

The one child table with no `company_id` of its own (`webhook_subscription_event`) correctly uses an
`EXISTS` against its parent instead of being left open.

### 7.2 Runtime enforcement

`TenantScopedDataSource` binds the tenant with `set_config(?, ?, false)` — a bound parameter, not a
literal — then `SET ROLE tms_app`. The returned connection is a proxy whose `close()` resets both
before returning to the Hikari pool; a reset that fails closes the physical connection rather than
returning a dirty one. Unscoped connections stay on the owner deliberately, for three named cases
(Flyway, authentication itself, `/me`), and the consequence is stated in the class comment rather
than hidden.

### 7.3 Other security posture, verified

- POD evidence storage: **`DISABLED` by default** — a deployment that has not said where a customer's
  signed delivery note goes does not get one guessed for it (ADR-006).
- Webhook SSRF guards: `allow-insecure-targets` and `allow-private-network-targets` both default
  `false`, re-checked at send time and not only at save time.
- Application objects live in the `tms` schema, invisible to the Supabase Data API (ADR-004).
- No secret was read or printed by this job.

### 7.4 The caveat, stated plainly

**All of §7.1 is a reading of SQL text.** It proves the migrations *say* the right thing. It does not
prove PostgreSQL *does* the right thing — that a policy binds, that `tms_app` cannot escape it, that
no company column was left unpoliced. `SchemaExposureIntegrationTest` asserts exactly those
properties by introspecting `pg_policies` and `pg_class`, so it covers V24–V35 automatically and
needs no maintenance. **It has never run against them.** Static coverage is a good sign; it is not a
certification, and this report does not present it as one.

---

## 8. Product readiness matrix

Legend: **READY** demo-ready and code-complete · **BUILT** complete but never executed against a
database · **PARTIAL** usable with a named gap · **CONTRACT** deliberately contract-only.

| # | Area | State | What is there, and what is not |
|---|---|---|---|
| 1 | **Master Data** | **READY** | Locations (canonical), zones, frequencies with per-date cutoff override, routes with per-stop service-time override, carriers, vehicle types, vehicles, drivers. Bulk import for five entities with dry-run preview |
| 2 | **Orders** | **PARTIAL** | Orders V2, declared vs calculated totals, spreadsheet import, inbound M2M with idempotency. **Gap: the lifecycle has four states and no delivered state** — a delivered order still reads `PLANNED` (P2-11) |
| 3 | **Planning** | **PARTIAL** | Manual planning, capacity and double-booking refusals, shipment V2 with stable identity, auto planning that proposes drafts and never confirms. **Gap: `AutoPlanningService` is untested and holds three defects** (P2-3/4/5/6) |
| 4 | **Execution** | **BUILT** | Six-state `TripStatus` with the transition table as domain data, back-datable actual times, stop arrive/service/complete/skip with typed reasons, `transport_event`, `trip_exception`. 100+ unit tests; zero database runs |
| 5 | **POD** | **BUILT** | Per-order delivered/rejected at a stop, `EvidenceStoragePort` behind ADR-006, local-filesystem implementation. **Off by default** — the result is recorded either way, only attachments are gated |
| 6 | **Tracking** | **CONTRACT** | Normalised position contract, two ports, sampling floor, retention window (ADR-007). **No vendor adapter, by decision.** Positions inform people and move no lifecycle |
| 7 | **Costing** | **BUILT** | Rate cards with selection rules, estimated vs actual trip cost, per-component breakdown |
| 8 | **Tendering** | **BUILT** | One live offer per trip, one acceptance ever, carrier-facing API. **Gap: no scheduler materialises an expiry** — a lapse is resolved on read |
| 9 | **Control Tower** | **BUILT** | Live operational view that owns no data of its own — correct, and worth saying |
| 10 | **Reporting** | **BUILT** | Real KPI aggregation endpoint, CSV export, `null` rendered as a dash where a value is genuinely unmeasured rather than faked as zero |
| 11 | **Integrations** | **BUILT** | Inbound locations/orders, outbound shipment plan, transactional outbox, tender and tracking APIs, signed + retried + suspendable webhooks with a delivery log and an SSRF guard. `docs/product/API_EXAMPLES.md` is the runnable companion |
| 12 | **SaaS Admin** | **BUILT** | Company settings, user and membership administration |
| 13 | **UX** | **READY** | 22 screens, **exactly one placeholder** (`/account`). Bootstrap + SweetAlert2, right-side drawers, dense responsive lists, no MUI. ES/EN parity **exact: 1,934 keys each across 20 namespaces**, verified this session |
| 14 | **Security** | **BUILT** | Six layers (§7). Static RLS coverage complete; runtime certification blocked |
| 15 | **Observability** | **PARTIAL** | Actuator `health,info,metrics`; business audit trail; integration request log; webhook delivery log with per-attempt rows; correlation ids through the dispatcher. **Gaps: the audit trail is write-only — there is no `Audit*Controller` among 37 controllers — and there is no metrics backend, no tracing and no alerting on the operational logs** |

---

## 9. Findings

### 9.1 P0 — none

No defect was found in this session, and none is carried, that corrupts data, breaks tenancy, or
makes the product unusable.

### 9.2 P1 — three, all environmental, none in the code

| Id | Finding | Status |
|---|---|---|
| **P1-1** | The database verification layer is unexecuted. V24–V35 have never been applied to any database; 443 assertions across 31 classes skip; Flyway replay, PostGIS, RLS and the vertical smoke are all unproven | **Carried. Re-verified** — counted from source this session |
| **P1-2** | `backend/tms-api/.env` targets a remote Supabase pooler with `TMS_FLYWAY_ENABLED=true`. A routine IDE start applies twelve unproven migrations to production | **Carried. Re-verified live this session** (§6.2) — two anchored greps, no values read |
| **P1-3** | The runner can execute no quality gate at all, for all eighteen jobs. Consequence: no measurement, and no commit — so the pack's per-job history is unrecoverable | **Carried. Re-verified** — four refusals this session (§5.1) |

None was introduced by any job in this pack. All three need a human. P1-3 is one line.

### 9.3 P2 — nine

| Id | Finding | Verified this session |
|---|---|---|
| **P2-4** | **Violates a named invariant.** On `apply`, an order refused by `TripService` in a lost race is appended to `unplanned` (line 167) while `outcome` reuses `proposal.trips()` unchanged (line 168) — so the same order appears on both sides and the screen can show `ordersConsidered ≠ planned + unplanned`. Database state stays correct; the report does not | Yes — `AutoPlanningService:141-169` read directly |
| **P2-5** | If every order of a proposed trip is refused, the empty draft trip survives: `created.add(trip)` at line 158 sits outside both the try/catch and the order loop. An empty trip has booked a vehicle for the date | Yes — line 158, unconditional |
| **P2-3** | **`AutoPlanningService` has zero test coverage at any level** — no unit test, no Docker-gated integration test, no E2E step. `grep -rl AutoPlanningService src/test` returns nothing; `planning.spec.ts` never mentions auto-plan. Only the pure `HeuristicPlanningEngine` is covered (15 tests). **Mockito is on the classpath, so this is closable today without Docker** | Yes. **Note: job 16 omitted this from its open list; it is open** |
| **P2-6** | `loadFreeVehicles` passes `UUID.randomUUID()` as the `idNot` argument. Correct — a random UUID excludes nothing — but it reads as a bug | Yes — line 281 |
| **P2-11** | **The order lifecycle has no delivered state.** `OrderStatus` has four values ending at `PLANNED`. `TripDeliveryService` never touches `OrderStatus` and `OrderView` carries no delivery field, so after a successful POD the Orders screen still shows `PLANNED`. Execution and POD moved forward in this pack; Orders did not | Yes — enum read; two greps returned empty |
| **P2-7** | `navConfig.ts:34-37` still calls Origins/Destinations "compatibility projections" since **V14**; the canonical unification is **V23** | Yes — read in place |
| **P2-8** | Frontend ships as a single ~1.12 MB chunk | Carried — needs a build |
| **P2-9** | Six oxlint `react(only-export-components)` warnings | Carried — needs a lint run |
| **P2-10** | ES/EN parity has no automated assertion. **The parity itself holds** — 1,934 keys each, all 20 namespaces matching file-for-file, verified this session — but nothing fails the build when it stops holding | Parity: yes. Guard: absent |

**P2-1** (frequency cutoff override) and **P2-2** (`RouteStop.serviceTimeOverride`) are **closed** by
V24 and were re-confirmed closed.

### 9.4 P3 — documentation drift

| Finding |
|---|
| Three Java class comments still say "nothing in this installation runs on a timer" (`TripTenderService:62`, `NotificationType:15`, `TripTender:274`). V35 added `@EnableScheduling` and `WebhookDispatchScheduler`. Job 16 corrected the equivalent statements in `docs/`, but not in source. Their *conclusion* still holds — a delivery worker is not a sweep over business state — so this is drift, not a defect |
| `docs/README.md`'s numbered index has fallen behind `docs/domain/` and `docs/integrations/` |
| This report counts **574** declared frontend unit tests where job 16 counted 572. The difference is regex sensitivity around `test.each`, not a change in the suite. Both are floors |

---

## 10. What is safe to demo

Everything below runs entirely in the browser against a local backend and a local database. Follow
`docs/product/DEMO_SCRIPT.md`, whose §1.1 correctly makes "apply V24–V35 to a real PostgreSQL first"
step one.

- **Master data end to end** — create a Location as both ORIGIN and DESTINATION, a frequency with a
  per-date cutoff override, a route with a per-stop service-time override, carriers, vehicle types,
  vehicles, drivers.
- **Bulk import with a dry run** — the fixture with two deliberate errors is the best two minutes in
  the script, because it shows the product refusing bad data *before* writing anything.
- **Orders both ways** — spreadsheet import and the inbound API batch, including an idempotency
  replay that returns the same result.
- **Manual planning refusals** — over-capacity, double-booked vehicle, licence warning. Refusals
  demo better than successes.
- **Automatic planning** — the fixtures are arithmetically built so five orders cannot fit one truck,
  which makes the unplanned-reasons list the point rather than a formality. **Demo the preview and
  the reasons; be ready for P2-4's counting discrepancy if you apply and a race occurs — on a
  single-operator demo it will not.**
- **Trip execution** — confirm, dispatch with a back-dated actual time, arrive/service/complete, a
  skip with a typed reason, an exception reported and resolved.
- **POD** — one order delivered and one rejected at the *same* stop.
- **Control tower, KPI report, CSV export** — including the dashes where a value is genuinely
  unmeasured. That honesty is a selling point; say so out loud.
- **Rate card, cost estimate vs actual, a tender sent and answered over the carrier API.**
- **Language switch** — ES/EN parity is exact.

**Two things to say before anyone asks:** `/account` is the one unbuilt screen, and a delivered
order still shows `PLANNED` on the Orders list (P2-11) — the delivery fact lives on the trip.

---

## 11. What is NOT safe to deploy

1. **Anything, to any environment, until P1-2 is fixed.** The `.env` in this repository migrates a
   live Supabase project on IDE startup. Fix this before touching anything else.
2. **V24–V35 to a database anyone depends on.** Twelve migrations, sixteen tables, never executed
   once. Apply them to a scratch database first, on an ordinary Tuesday.
3. **A multi-node deployment with POD evidence enabled in `LOCAL` mode.** It writes to the node's own
   volume; two instances behind a balancer will not find each other's files. The config comment says
   so; deployment must respect it.
4. **Automatic planning as an unattended path.** It is safe as a proposal tool with a human in the
   loop — which is its design — but P2-3 means its service layer has never been exercised, and
   P2-4/P2-5 are live under concurrency.
5. **Anything that assumes a scheduler.** There is exactly one (`WebhookDispatchScheduler`). Tender
   expiry, alert sweeps and report delivery have no timer behind them.
6. **Anything requiring an audit read.** The trail is write-only — 37 controllers, no `Audit*`
   among them. Compliance questions cannot be answered through the product.
7. **Load at the stated scale target.** 10,000 orders/day is designed for and unmeasured. No load
   test exists.

---

## 12. The next ten actions, ordered by business value

| # | Action | Effort | Why here |
|---|---|---|---|
| 1 | **Repoint `TMS_DB_URL` at a local database, or set `TMS_FLYWAY_ENABLED=false`** | 1 line | Removes the only path by which unproven DDL reaches production. Nothing else matters until this is done |
| 2 | **Fix P1-3: give the runner `--allowedTools` for the build, test and git verbs** | 1 line | Every remaining item on this list is blocked by it. Highest leverage per character in the repository |
| 3 | **Install a WSL distro and start Docker Desktop, then run the full backend suite** | ~1 hour | Unblocks 443 assertions and turns §7 from a reading into a certification. `FlywayMigrationIntegrationTest` proves V24–V35 replay onto an empty database |
| 4 | **Commit the tree.** One honest commit — `feat: overnight sellable V4 (jobs 01-17)` — after 2 and 3 are green | 10 min | 435 entries and zero commits is the largest operational risk in the repo. Per-job attribution is already lost; recovering it is not worth delaying a working baseline |
| 5 | **Write `AutoPlanningServiceTest` (P2-3) and fix P2-4 and P2-5 with it** | ~3 hours | The flagship feature, untested, with a defect that violates `INPUT_ORDERS = PLANNED + UNPLANNED`. Mockito is already on the classpath — no Docker needed. Fix P2-6 in the same pass |
| 6 | **Give the order lifecycle a delivered state (P2-11)** | ~1 day + a migration | The most visible functional inconsistency in the product. Execution and POD are complete; Orders stops at `PLANNED`, so the list lies after a successful delivery |
| 7 | **Run the frontend gates — typecheck, lint, build, 574 unit tests, 52 E2E at 2 workers** | ~30 min | Free once 2 is done, and closes P2-8 and P2-9 or proves them stale |
| 8 | **Add an ES/EN parity assertion to the test suite (P2-10)** | ~1 hour | Parity is currently exact at 1,934 keys. Without a guard it degrades on the first hurried merge |
| 9 | **Expose the audit trail read-only** — one controller, one screen | ~1 day | Converts an existing write-only asset into something a buyer's compliance officer can be shown. Highest sellable value per hour on this list |
| 10 | **Load-test one vertical at the stated scale** — 10,000 orders/day through import → planning → execution | ~2 days | The scale target is designed for and entirely unmeasured. Better to learn where it bends internally than in front of a customer |

Items 1–4 are prerequisites; 5–10 are ordered by value once the ground is solid.

---

## 13. Verdict

- **P0: 0**
- **P1: 3** — all environmental, all carried, none introduced by any job in this pack, all requiring
  a human
- **P2: 9** — one violating a named architectural invariant (P2-4), one leaving the flagship feature
  untested (P2-3), one making a screen lie after a successful delivery (P2-11)
- **P3: 3** — documentation drift

```text
FINAL_STATUS=PARTIAL
```

**Not `PASS`**, and the brief is explicit about why: no quality gate could be executed, the database
certification is `BLOCKED_ENVIRONMENT`, and three P1s stand.

**Not `FAIL`**: nothing is broken, no P0 exists, no defect was introduced by this job, the
architecture holds on all seven invariants tested except P2-4's reporting arithmetic, applied
migrations are byte-identical to HEAD, and no capability is claimed here that was not verified
against source in this session.

**Not `BLOCKED`**: the work exists and is substantially complete. It is unproven, which is a
different thing, and the ten actions above are what turn one into the other.

The single most valuable hour anyone can spend on this repository is items 1 through 3: fix the
`.env`, fix the runner, start Docker. Everything the pack built is probably correct. After that hour
it would be *known*.

---

## 14. Closing-rule compliance

| Rule | State |
|---|---|
| No push | **Honoured.** No remote git operation of any kind |
| No migration applied to remote Supabase | **Honoured.** No database connection was opened |
| V1–V23 (and V24–V35) untouched | **Honoured.** `git diff HEAD -- db/migration/` is empty; no migration file was written |
| No shared data mutated | **Honoured** |
| `git status` and `git diff --stat` reviewed before commit | **Done** — §2 |
| Local commit if P0=0 and P1=0 | **Not applicable.** P1=3, and `git add` is refused (P1-3). `COMMIT=none` |
| No falsified DB PASS | **Honoured** — `BLOCKED_ENVIRONMENT`, §6 |
| Module documentation updated where a contract changed | **Not applicable.** This job changed no contract and no source file; its only output is this report |
| No secret read or printed | **Honoured.** `.env` checked with two anchored greps that read no values |

---

```text
JOB=17
CHANGES=full independent verification of the working tree (git, 12 migrations, RLS coverage of all 16 new tables, 6 architecture invariants, test inventory, i18n parity, webhook dispatcher tenancy review) + docs/overnight-sellable-v4/FINAL_REPORT.md; two corrections to job 16's findings (P2-3 reopened, P2-11 numbered)
MIGRATIONS_CREATED=none
REMOTE_DB_MUTATED=NO
PUSH_PERFORMED=NO
BACKEND_TESTS=not-run
FRONTEND_TESTS=not-run
E2E_TESTS=not-run
DB_CERTIFICATION=BLOCKED_ENVIRONMENT
P0=0
P1=3
P2=9
COMMIT=none
JOB_STATUS=PARTIAL
```
