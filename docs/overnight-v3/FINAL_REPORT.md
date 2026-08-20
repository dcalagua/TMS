# Overnight V3 - Final Report and Morning Handoff

Verification run: Job 14. Date: 2026-08-20.

**Gate result: `JOB_STATUS=BLOCKED`.**

Nothing is broken. Every test that *could* execute on this machine passed. The gate is
blocked because a specific, load-bearing half of the verification never ran: no Docker
daemon is reachable, so all 329 database-backed assertions were skipped, and migrations
V14-V22 have therefore never been applied to any PostgreSQL instance. See P1-1.

---

## 1. Final state

| Item | Value |
| --- | --- |
| Branch | `dev` |
| HEAD | `635ba4efa88c90aa54eb44776d07f686c5ffd8de` |
| HEAD subject | `feat(audit): business audit trail, integration/import observability, docs (Job 13)` |
| Working tree | Clean except untracked `tms-overnight-v3/` (the overnight pack - deliberately never staged) |
| Pushed | No. Nothing was pushed. `origin` is untouched. |

---

## 2. Commits made tonight

Baseline for the run is `0fa0654` (the last pre-V3 commit). Fourteen commits were added:

| # | SHA | Subject | Files | +/- |
| --- | --- | --- | --- | --- |
| 00 | `0c1229a` | docs(overnight-v3): baseline audit and OTM domain alignment | 2 | +644 / -0 |
| 01 | `17802db` | feat(masterdata): canonical Location master with compatibility projections | 49 | +5575 / -18 |
| 02 | `79a8eb8` | feat(maps): Google Maps location picker for the Location drawer | 22 | +1098 / -26 |
| 03 | `b0fb02f` | feat(masterdata): location service calendars, route/frequency select polish | 34 | +2766 / -73 |
| 04 | `6259ca8` | feat(fleet): carrier/vehicle external reference and vehicle double-booking invariant | 27 | +674 / -76 |
| 05 | `bcb27aa` | feat(orders): transport orders V2 - declared totals, bulk import, async location lookup | 73 | +6708 / -163 |
| 06 | `3a440c7` | feat(integration): inbound machine-to-machine API v1 for locations and orders | 82 | +7381 / -27 |
| 07 | `f71aa2b` | feat(planning): shipment V2 - stable shipment identity, resolved header, route suggestion | 44 | +2542 / -108 |
| 08 | `f327cd8` | feat(integration): outbound shipment plan V1 - confirmed shipment read API + transactional outbox | 28 | +1854 / -11 |
| 09 | `d1fb7e0` | feat(imports): Import Center for master data | 87 | +7496 / -28 |
| 10 | `14e2a72` | feat(planning): trip stop map and sequence UX | 17 | +662 / -76 |
| 11 | `5647c0f` | feat(ui): eliminate remaining native selects, fix i18n gaps | 28 | +453 / -334 |
| 12 | `28e9485` | security(tenancy): audit overnight V3 surfaces and repair the RLS drift guard | 4 | +627 / -7 |
| 13 | `635ba4e` | feat(audit): business audit trail, integration/import observability, docs | 31 | +998 / -51 |

No secrets, no `.env` file and no part of `tms-overnight-v3/` entered any commit
(scanned the full range for service-role keys, `AIza…` Google keys, JWTs and literal
passwords - zero hits outside placeholders).

---

## 3. Migrations

### 3.1 V1-V13 were not edited

Each of V1-V13 still has exactly **one** commit in its file history - the commit that
created it. None was touched tonight. Recorded SHA-256 for tomorrow's comparison:

```
70bc62f67f4a52c31d8e4dab059cd0863e93dbcd9fd0fef9dd798a1d443e0eea  V1__baseline_schema_extensions_and_helpers.sql
9234d2bacdb820aa33976f8706b30cd969ea9c791f13761982f041e5eab87272  V2__identity_and_tenancy.sql
eca358ed3f8c26d23f9cb80913c2305b97e5727cfe06f1403500a100c91572bb  V3__iam_reference_data.sql
b5ae86dac5eb82e55430dec8a36331d4d0da11af0d547987876c5d8818da06b2  V4__security_grants_and_rls.sql
075aa8a6e884a508906ae3494e08bcc1e966be9591e85808fa72a60e1d9227ee  V5__authorization_catalogue_completion.sql
139102014fd37bc22676949424feaeb9f419f5de1bd3ec3e849eb5af8b0a96df  V6__masterdata_origins_zones.sql
772f5f99d853727bb4ced00c8f2a94b54e5238c9a026c7a9ff3ba60154c1b3fc  V7__masterdata_destinations_frequencies.sql
218db4a2327eabe29ad9c43e5af12a90987c80dbca539b5087f618404ea3afe6  V8__masterdata_routes.sql
9d36d6ffa3a341726d1cafa20500310d5f562c485ffd64b3b5e480ab7cdecb3b  V9__fleet_masters.sql
e4bf43fe363c57d05721414f3aa9f3bfa88b96e6d929716e1b383cc17d7bae4e  V10__orders.sql
0dbf0900ebc5ec7ea30957738ba118c168c50d859754faa93bd2ebb426ef1dd4  V11__planning_manual.sql
f52fedeb42e7f26f7fe9364cce9d306c39e2f9172fa1a11c358cdf83f6335089  V12__performance_indexes.sql
556fd2e7138d82a1cb5d80c9ae61d35e2026dfef5f77cfc020607c33016ef214  V13__tenant_rls_runtime_role_and_policies.sql
```

### 3.2 Migrations added (V14-V22, 1439 lines)

```
171d8cbaf85f9e65343624db05d10e2b2acd67dad3ac6e2782a280438bf3c5e8  V14__masterdata_canonical_location.sql        (403 lines)
504847a1a83e6c3e3bd9ac7c77545d698523f6088b9a75ee65e876466c5d772a  V15__masterdata_location_frequency.sql        (110 lines)
ca5c35dad0011fb54655c5fe96c54109b1c68c24263567346875c5c1ce32842f  V16__fleet_external_reference_and_double_booking.sql (77 lines)
80e7a579615f8e1bb6b56e3ae67f955d7375665187df23fbc1fc70eee1c7c80c  V17__orders_declared_totals.sql               (166 lines)
7057f2968dbec2359ca92f65b0df47b3652e7c750ee46941ab204aa7d6e2e382  V18__integration_clients_and_inbox.sql        (323 lines)
38c4202de461e174de239c1889b091346df281733fb52f399adaa82e062998c9  V19__planning_shipment_v2.sql                 (118 lines)
91ee8606f378dbf3b4264e864ba070f5fd957b6b330ce1552cf4a4d010f2c03f  V20__shipment_outbox_and_outbound_scope.sql   (96 lines)
d253215f489081cdc1b052a337cfe5cc7abab6a5f4b3e078cc0b943d64de7cc0  V21__master_data_import_batch.sql             (57 lines)
9df20253fd5b30834984115aaa7056ee5c5b9acd4ea384910aaa97b46e234596  V22__audit_event.sql                          (89 lines)
```

Versions are unique and contiguous 1..22. Flyway remains the sole migration owner:
`supabase/migrations/` does not exist, and a test asserts it never will.

**These nine files have never been executed by a PostgreSQL server.** See P1-1.

---

## 4. Tests actually executed

Everything below was run on this machine during Job 14 and the counts are copied from the
real output. Nothing here is inferred.

| Suite | Command | Result |
| --- | --- | --- |
| Backend (Maven Surefire) | `./mvnw -B test` | **671 tests: 0 failures, 0 errors, 329 skipped → 342 executed.** BUILD SUCCESS |
| Frontend unit/component | `npm test` (vitest) | **57 files, 491 tests, 491 passed**, 22.6 s |
| Frontend typecheck | `npm run typecheck` (`tsc -b`) | Pass, no diagnostics |
| Frontend lint | `npm run lint` (oxlint) | **0 errors**, 6 warnings (all `react(only-export-components)` fast-refresh hints) |
| Frontend build | `npm run build` | Pass, 286 modules, 1122 kB JS / 373 kB CSS. One warning: main chunk > 500 kB |
| E2E | `npx playwright test` | **68 tests, 68 passed**, 3.4 min, chromium |

Total executed: **342 backend + 491 frontend unit + 68 E2E = 901 tests, 0 failures.**

### 4.1 The 329 backend tests that did NOT run

All are gated by `@EnabledIf(DockerAvailability#isAvailable)` and were reported as
*skipped*, not passed. Full list:

| Test class | Skipped |
| --- | --- |
| `database.ApplicationDatabaseStartupIntegrationTest` | 1 |
| `database.CanonicalLocationConstraintIntegrationTest` | 2 |
| `database.FleetConstraintIntegrationTest` | 18 |
| `database.FlywayMigrationIntegrationTest` | 4 |
| `database.IntegrationTenancyIsolationIntegrationTest` | 16 |
| `database.LocalSeedIntegrationTest` | 2 |
| `database.MasterDataConstraintIntegrationTest` | 6 |
| `database.MasterDataDestinationFrequencyConstraintIntegrationTest` | 10 |
| `database.MasterDataLocationFrequencyConstraintIntegrationTest` | 9 |
| `database.MasterDataRouteConstraintIntegrationTest` | 14 |
| `database.OrderConstraintIntegrationTest` | 23 |
| `database.PlanningConstraintIntegrationTest` | 29 |
| `database.SchemaExposureIntegrationTest` | 10 |
| `database.ShipmentOutboxTenancyIsolationIntegrationTest` | 6 |
| `database.TenancyConstraintIntegrationTest` | 10 |
| `database.TenantRlsIsolationIntegrationTest` | 5 |
| `fleet.api.CarrierImportApiIntegrationTest` | 8 |
| `fleet.api.FleetApiIntegrationTest` | 3 |
| `fleet.api.VehicleImportApiIntegrationTest` | 12 |
| `fleet.api.VehicleTypeImportApiIntegrationTest` | 8 |
| `iam.infrastructure.IdentityResolutionIntegrationTest` | 10 |
| `masterdata.api.DestinationFrequencyApiIntegrationTest` | 2 |
| `masterdata.api.LocationApiIntegrationTest` | 6 |
| `masterdata.api.LocationFrequencyApiIntegrationTest` | 4 |
| `masterdata.api.LocationImportApiIntegrationTest` | 10 |
| `masterdata.api.OriginZoneApiIntegrationTest` | 2 |
| `masterdata.api.RouteApiIntegrationTest` | 13 |
| `orders.api.OrderApiIntegrationTest` | 23 |
| `orders.api.OrderImportApiIntegrationTest` | 17 |
| `planning.api.PlanningApiIntegrationTest` | 46 |
| `smoke.EndToEndSmokeIntegrationTest` | 13 |
| **Total** | **329** |

Note that `database.MigrationConventionTest` (8 tests) **did** run - but it is static text
analysis of the `.sql` files (naming, contiguity, no destructive DDL, no credentialed role,
no Supabase-schema writes, no tenant seed data, no grants to API roles, no parallel Supabase
history). It never opens a database connection. It is not evidence that the SQL is valid.

---

## 5. Remote database status

**No remote or shared database was modified. No migration was applied anywhere by this
overnight automation.**

Evidence gathered:

- `supabase/.temp/` does not exist → the Supabase CLI was never `link`ed to a remote project.
- `supabase/migrations/` does not exist → no competing migration history.
- `supabase/config.toml` carries `project_id = "tms-by-ebim"`, a local descriptor only.
- No occurrence of `supabase db push`, `--db-url`, `link --project` or any `*.supabase.co`
  connection string anywhere in `tms-overnight-v3/` or `scripts/`.
- Backend datasource is env-driven (`TMS_DB_URL`); `application-local.yml` defaults to
  `jdbc:postgresql://localhost:54322/postgres`, `application-prod.yml` has no default at all.
  No hard-coded host exists in the repository.
- The backend application was never started against any database during the run.
- A native PostgreSQL 18 service is listening on `localhost:5432`. It is a pre-existing local
  install unrelated to this work; nothing tonight connected to it.

If you separately authorized a remote apply outside this pack, that is not reflected here -
within this pack, nothing was applied.

---

## 6. Key flow verification (code/test level)

Legend: **Code** = implementation reviewed and present. **Test** = level at which it is
covered, and whether that coverage actually executed tonight.

| Flow | Code | Executed coverage | Unexecuted coverage |
| --- | --- | --- | --- |
| Tenant/company switch | `CompanyContextController`, `CompanyContext.tsx`, `RequireCompany` | `CompanyContext.test.tsx`, `RequireCompany.test.tsx`, `CompanySelector.test.tsx`, `ApiSecurityTest`, E2E `navigation.spec.ts` | `IdentityResolutionIntegrationTest` (10), `TenancyConstraintIntegrationTest` (10) |
| Location/Store manual CRUD | `LocationController`, `LocationFormDrawer` | `LocationsPage.test.tsx`, `LocationFormDrawer.test.tsx`, `LocationModelTest`, `LocationCompatibilityProjectorTest` | `LocationApiIntegrationTest` (6), `CanonicalLocationConstraintIntegrationTest` (2) |
| Location map coordinates | `LocationPickerMap`, `googleMapsLoader` | `LocationPickerMap.test.tsx`, `googleMapsLoader.test.ts`, E2E `maps.spec.ts` | - |
| Frequency / location eligibility | `LocationEligibilityEvaluator`, `FrequencyCalendar`, `LocationFrequencyPanel` | `LocationEligibilityEvaluatorTest`, `FrequencyCalendarTest`, `LocationFrequencyPanel.test.tsx`, `FrequenciesPage.test.tsx` | `MasterDataLocationFrequencyConstraintIntegrationTest` (9), `LocationFrequencyApiIntegrationTest` (4) |
| Route stops | `RouteController`, `RouteFormDrawer` | `RoutesPage.test.tsx`, `RouteFormDrawer.test.tsx` | `MasterDataRouteConstraintIntegrationTest` (14), `RouteApiIntegrationTest` (13) |
| Carrier / VehicleType / Vehicle | `Carrier/VehicleType/VehicleController`, `EffectiveCapacityResolver` | `EffectiveCapacityResolverTest`, 6 fleet page/drawer test files | `FleetConstraintIntegrationTest` (18), `FleetApiIntegrationTest` (3) |
| **Vehicle double-booking prevention** | `ShipmentTimeRules`, `TripService`, `TripRepository`, exclusion constraint in V16 + V19 | `ShipmentTimeRulesTest` (6, pure domain) | **`PlanningConstraintIntegrationTest` (29), `PlanningApiIntegrationTest` (46) - the DB-level invariant itself is unproven** |
| Manual Order | `OrderController`, `OrderFormDrawer` | `OrderTotalsTest` (13), `OrdersPage.test.tsx`, `OrderFormDrawer.test.tsx` | `OrderApiIntegrationTest` (23), `OrderConstraintIntegrationTest` (23) |
| Order import dry-run / apply | `OrderImportController`, `OrderImportService/Validator/Parser` | `OrderImportParserTest` (14), `OrderImportValidatorTest` (30), `OrderImportTemplateTest` (8), `OrderImportDrawer.test.tsx` | `OrderImportApiIntegrationTest` (17) |
| **Inbound Location/Order API idempotency** | `IntegrationLocationController`, `IntegrationOrderController`, `IntegrationInboxService`, `PayloadHash`, `IntegrationRequest` | `IntegrationApiTenancyTest`, `IntegrationClientTest`, `IntegrationSecretsTest`, `IntegrationAuthenticationServiceTest` | **`IntegrationTenancyIsolationIntegrationTest` (16) - inbox uniqueness/replay at DB level unproven** |
| Planning / Trip assignment and capacity | `TripController`, `PlanningRunController`, `PlanningCapacityService` | `PlanningCapacityServiceTest` (7), `TripShipmentTest` (12), `PlanningBoardPage.test.tsx`, `TripVehicleDrawer.test.tsx`, `CapacityBar.test.tsx`, E2E `planning.spec.ts` | `PlanningApiIntegrationTest` (46), `PlanningConstraintIntegrationTest` (29) |
| Confirmed shipment outbound contract | `IntegrationShipmentController`, `IntegrationShipmentService`, outbox in V20 | `IntegrationShipmentApiTest` | `ShipmentOutboxTenancyIsolationIntegrationTest` (6) |
| Shipment stops map | `StopsMap`, `TripStopMap` | `StopsMap.test.tsx`, `TripStopMap.test.tsx`, E2E `maps.spec.ts` | - |
| ES/EN and responsive UI | 15 locale bundles × 2 languages | `i18n.test.tsx`, `enums.test.ts`, E2E `i18n.spec.ts`, `responsive.spec.ts` (10 viewports, 320→1920), `monochrome.spec.ts`, `ui-review.spec.ts` | - |

### 6.1 i18n parity - verified exact

ES and EN each have the same 15 bundles and the **same key count in every bundle**
(995 keys per language): auth 44, common 188, dashboard 27, dialogs 20, errors 13,
fleet 169, maps 12, masters 140, navigation 36, orders 110, planning 158, security 2,
statuses 56, trips 2, validations 18. No missing or orphan key in either direction.

### 6.2 Integration credential handling - verified

`IntegrationSecrets` generates a 128-bit `clientId` and a 256-bit secret from a CSPRNG,
persists only a SHA-256 digest, compares in constant time, and returns the secret exactly
once at create/rotate. No Supabase service-role key is involved anywhere in the integration
path. This satisfies the "never plaintext, never a service-role key" rule.

---

## 7. Findings

### P0 - none

No defect that breaks a flow, leaks data across tenants, or exposes a secret was found in
application or database code.

### P1-1 - The entire database verification layer is unexecuted, and V14-V22 have never been applied

**What.** 329 of 671 backend tests (49%) are gated on a Docker daemon and were skipped.
That set is not incidental - it is precisely the layer this product's non-negotiable rules
depend on:

- every migration-applies-cleanly assertion (`FlywayMigrationIntegrationTest`);
- every RLS/tenant-isolation assertion (`TenantRlsIsolationIntegrationTest`,
  `TenancyConstraintIntegrationTest`, `IntegrationTenancyIsolationIntegrationTest`,
  `ShipmentOutboxTenancyIsolationIntegrationTest`);
- the schema-exposure and RLS-drift guard (`SchemaExposureIntegrationTest`);
- the vehicle double-booking exclusion constraint (`PlanningConstraintIntegrationTest`);
- the inbound idempotency/replay behaviour (`IntegrationTenancyIsolationIntegrationTest`);
- the full vertical smoke (`EndToEndSmokeIntegrationTest`).

Consequently **the 1439 lines of SQL in V14-V22 have never been parsed or executed by a
PostgreSQL server.** A syntax error, a bad constraint, or a backfill that fails on existing
rows would not have been caught by anything that ran tonight. `MigrationConventionTest`
inspects the files as text only.

**Why this is P1 and not merely an environment note.** Job 12 found a real defect living in
exactly this gap: `SchemaExposureIntegrationTest`'s table constants had drifted for seven
tables across V15-V21 and survived four commits unnoticed *because the test never runs*.
The gap has already hidden one defect; treating it as cosmetic would be dishonest.

**Root cause.** Docker Desktop's client is installed (v29.6.1) but its Linux engine returns
HTTP 500 on `//./pipe/dockerDesktopLinuxEngine`. `wsl -l -v` reports *no installed
distributions* - Docker Desktop's backing WSL distro is missing or deregistered. WSL 2 itself
is healthy (v2.7.10.0, kernel 6.18.33.2).

**Why no fallback was used.** The native PostgreSQL 18 on `localhost:5432` cannot substitute:
it has no `postgis.control`, and migration V1 runs `CREATE EXTENSION postgis`. Pointing the
tests at a shared database is explicitly forbidden by `DockerAvailability`'s contract and by
the project rules, so no workaround was attempted.

**Fix.** Repair Docker Desktop (see §9, step 1), then rerun `./mvnw -B test` and require
`Skipped: 0`. This is a machine-level repair and was deliberately not attempted by unattended
automation.

### P2-1 - Frontend ships as a single 1.12 MB chunk

`npm run build` emits one 1,122 kB JS bundle (302 kB gzipped) and warns about it. Acceptable
for an internal TMS today; worth code-splitting by route before the customer-facing release.
Planning, Import Center and the Maps loader are the natural split points.

### P2-2 - Six oxlint fast-refresh warnings

`AuthContext.tsx`, `ThemeProvider.tsx` (×3), `Pagination.tsx`, `CompanyContext.tsx` export
non-component values alongside components. Zero runtime impact; costs HMR fidelity in dev.
Fix by moving the constants/hooks into sibling modules.

### P3-1 - `EndToEndSmokeIntegrationTest` is the only full vertical proof and it never runs

The 13-assertion smoke (authenticate → masters → fleet → order → plan → confirm) is the one
test that exercises the whole product as a unit. Folded into P1-1, but flagged separately
because once Docker is back it should be the *first* thing run, before the granular suites.

### P3-2 - No automated check that ES/EN bundles stay in parity

Parity is exact today, but it was verified by an ad-hoc script in this job, not by a test.
The locale bundles are generator-written; a small vitest assertion comparing key sets would
make tomorrow's drift impossible.

---

## 8. Required environment variables

Names and purpose only - no values are recorded here, and none belong in the repository.

### Backend (`backend/tms-api/.env`, template in `.env.example`)

| Variable | Notes |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `local` for development |
| `TMS_API_PORT` | Default 8080 |
| `TMS_DB_URL` | Local Supabase stack listens on 54322. Local/disposable only |
| `TMS_DB_USERNAME` | |
| `TMS_DB_PASSWORD` | |
| `TMS_DB_POOL_MAX` | |
| `TMS_FLYWAY_ENABLED` | |
| `TMS_SUPABASE_JWT_ISSUER_URI` | Mandatory. The app refuses to start without it - it never degrades to an unsecured API |
| `TMS_SUPABASE_JWKS_URI` | Mandatory. Public keys only; there is deliberately no JWT signing secret setting |
| `TMS_SUPABASE_JWT_AUDIENCES` | Supabase issues `authenticated` |
| `TMS_CORS_ALLOWED_ORIGINS` | Exact origins, comma separated. Wildcards are rejected at startup |

The backend never needs, and must never be given, a Supabase **service-role key**.

### Frontend (`frontend/tms-web/.env.local`, template in `.env.example`)

| Variable | Notes |
| --- | --- |
| `VITE_API_BASE_URL` | All business data goes through Spring Boot |
| `VITE_SUPABASE_URL` | Auth only in V1 |
| `VITE_SUPABASE_ANON_KEY` | Publishable by design. The service-role key must never appear here |
| **`VITE_GOOGLE_MAPS_API_KEY`** | Browser key, therefore visible in dev tools. **Must** be restricted in Google Cloud Console to your app's HTTP referrers and to exactly two APIs: *Maps JavaScript API* and *Geocoding API*. Leaving it blank is supported - the location drawer degrades to manual latitude/longitude entry instead of failing to load. Never commit a value. |

Full Maps guidance: `docs/integrations/GOOGLE_MAPS.md`.

---

## 9. Manual steps for tomorrow

1. **Restore Docker, then close P1-1.** This is the only thing standing between this branch
   and a green gate.
   - Open Docker Desktop and let it re-provision its WSL distro, or
     *Settings → Troubleshoot → Reset to factory defaults*, or reinstall.
     If it still fails, `wsl --install -d Ubuntu` (needs admin, likely a reboot) then restart
     Docker Desktop and confirm `docker info` returns a server version.
   - Verify: `docker run --rm postgis/postgis:17-3.5 postgres --version`.
   - Then: `cd backend/tms-api && ./mvnw -B test` and **require `Skipped: 0`**.
     Expect 671/671 executed. Treat any failure as the real state of the branch - the DB
     layer has never been exercised, so first-run failures are plausible and are information,
     not noise.
   - Run `smoke.EndToEndSmokeIntegrationTest` first (P3-1).
2. **Review the branch before merging.** 14 commits, ~38 k inserted lines. In particular read
   `docs/security/OVERNIGHT_V3_TENANCY_REVIEW.md` (Job 12's audit) and the nine new migrations.
3. **Do not apply V14-V22 to any shared environment until step 1 passes.** They are unproven SQL.
4. **Provision the Google Maps key** and apply the HTTP-referrer + API restrictions described
   in §8 before anyone uses the location picker outside localhost.
5. **Decide on the push.** Nothing was pushed. `dev` is 14 commits ahead locally.
6. **`tms-overnight-v3/` is untracked on purpose.** Delete it or leave it; do not commit it.

---

## 10. Suggested roadmap

Ordered by dependency, not by appeal.

**Immediately after the gate closes**
- Restore Docker in CI as a hard requirement so `Skipped: 0` is enforced, not hoped for
  (P1-1 existed because a skip reads as a pass at a glance).
- Locale-parity assertion (P3-2); route-level code splitting (P2-1).

**Next functional increment**
- **Automatic planning heuristic.** A greedy/savings assignment over the existing capacity
  model and time rules, surfaced as a *suggestion* the planner accepts or rejects. Reuses
  `PlanningCapacityService` and `ShipmentTimeRules` as they stand. No new dependency.
  Deliberately *not* OR-Tools yet - earn the constraint model first with real data.
- **Drivers.** The one master still missing from the shipment picture: driver, licence,
  availability and assignment to a trip. Small, well-understood, unblocks hours-of-service
  rules later.
- **Tariffs and cost.** Carrier rate cards, cost per shipment, planned-vs-actual. This is
  what turns the planning board from a scheduler into something with a P&L attached.

**After that**
- **Tendering.** Offer a shipment to carriers in preference order with accept/reject and
  expiry. Depends on tariffs and on the outbound contract, both of which now exist in V1 form.
- **Execution tracking.** Stop-level status (arrived/loaded/departed/delivered), PoD capture,
  exception reasons. Should land as a driver-facing surface, not another desktop screen.
- **OR-Tools / true optimisation.** Only once tariffs give a real objective function and
  tracking gives real travel times. An ADR should precede it, per the standing rule.
- **EWM and ERP integration.** The inbound/outbound contracts are already versioned and
  tenant-scoped, so this is adapter work rather than architecture work.

Still deliberately deferred, unchanged: GPS/telematics, Kafka/microservices/event sourcing,
Supabase Realtime, Storage, live map tracking.

---

## 11. Verdict

- P0: **0**
- P1: **1** (P1-1 - database verification layer unexecuted; V14-V22 never applied)
- P2: 2
- P3: 2

The gate requires P0 = 0 **and** P1 = 0.

`JOB_STATUS=BLOCKED`
