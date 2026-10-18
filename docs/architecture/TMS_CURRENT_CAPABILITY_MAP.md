# TMS by EBIM - Current Capability Map

**Reconstructed from code on 2026-08-28**, originally at commit `0757afb`, and **reconciled at
commit `729f155` after JOBS 01-16 and the certification repair** (Phase 2, JOB 17). Branch `dev`.
Rows are kept current as the chain proceeds; the job that changed one is named in its Next step.
**Reconciled again from code at commit `40aad53`** (2026-10-18, overnight pack phase 05, branch
`feature/tms-enterprise-overnight` cut from `dev` at `40de0a5`), covering V44-V58.

> **What phase 05 (2026-10-18) corrected.** Between `729f155` and `40de0a5` fifteen migrations
> (V44-V58), eight ADRs (ADR-012 to ADR-021) and the TMS ⇄ EWM integration v1 (merged into `dev` on
> 2026-09-28, PR #13, `0553f16`) landed, and this file was not touched. It had gone stale in five
> ways: (1) row 16 still said settlement was **MISSING** and pointed at "Phase 2 JOB 20" - V46
> delivered it as v1, with the gaps now named in the row; (2) the baseline still read `1684` /
> `97` / `V1-V43`; (3) rows 8, 10, 13, 17 and 25 still carried debts D3, D5 and D6 and a missing
> `docs/operations/` that V45, V47, V48 and the operations docs have since closed; (4) the platform
> truth counted 11 modules and 41 controllers; (5) thirteen capabilities had no row at all - they
> are rows 26-38. Each change is named in place ("**CORRECTION (phase 05)**"), not silently
> overwritten; the JOB 17 text is kept below as the record of what JOB 17 found.

> **What JOB 17 corrected.** This file was written before JOBS 09-16 landed and had gone stale in
> three ways: the gate numbers still read `1585` / `V1-V41`; six capability rows still pointed at
> jobs that have since completed; and row 16 implied that JOB 11 would deliver settlement, which it
> did not. Each is corrected below and the correction is named, not silently overwritten.

This document is derived from the source tree, the Flyway history and the test suites - **not**
from earlier reports. Where a historical document disagrees with this one, this one is right and
the historical document is a record of what was true when it was written.

## How to read this

- **IMPLEMENTED** - the vertical slice exists and is exercised by tests: UI (where a UI is owed),
  controller, service, repository, schema, tenant scoping.
- **PARTIAL** - a real, working foundation exists but the capability as an enterprise TMS would
  define it is not complete. The gap is named.
- **MISSING** - no table, no domain type, no endpoint. Not a stub: genuinely absent.

"Tests" names the evidence, not a count. "Security" states how tenancy is enforced for that
capability.

## Baseline measured 2026-10-18 (overnight pack phase 00, commit `40de0a5`)

**CORRECTION (phase 05):** the table that follows this one is the JOB 17 baseline at `729f155`,
kept as the record. The numbers in force are these:

| Gate | Command | Result |
|---|---|---|
| Backend | `./mvnw -B test` (Testcontainers `postgis/postgis:17-3.5`) | **2324 run, 1 failure, 0 errors, 2 skipped** - 4 min 37 s |
| Frontend typecheck | `npm run typecheck` | **PASS** (0 errors) |
| Frontend lint | `npm run lint` | **PASS** - 0 errors, 21 warnings (`only-export-components`, `set-state-in-effect`) |
| Frontend unit | `npm test` | **308/308** in 46 files |
| Flyway history | `db/migration` | **V1 - V58**, contiguous, no duplicates (hashes in `docs/roadmap/2026-10-enterprise/MIGRATION_HASHES.txt`). Next available: **V59** |

- **The one failure is known and not a product defect:**
  `PlatformLifecycleIntegrationTest.versionRules` fails deterministically since `190245f` (V58). The
  test wraps `service.apply(...)` in its own `TransactionTemplate` and builds `LifecycleApplyWriter`
  with `new`, so the rejection's audit row is rolled back with the template; in production the
  writer's proxy commits it first. Diagnosis in `docs/roadmap/2026-10-enterprise/OVERNIGHT_LOG.md`
  (phase 00).
- **Skipped by design** (need real services): `SupabaseEvidenceStorageLiveTest`,
  `MasterAdminMailboxBridgeTest`.
- `docs/roadmap/2026-10-enterprise/00_ESTADO_ACTUAL.md` section 1 quotes `2114` / `189 in 29 files`:
  those are the last runs before V56-V58 and the carrier portal, superseded by the table above.
- Frontend build and E2E were not re-run in phase 00; the last recorded figures are JOB 17's below.

### JOB 17 baseline (`729f155`, kept as the record)

| Gate | Command | Result |
|---|---|---|
| Backend | `./mvnw -B clean test` | **1312** J01 → **1585** J08 → **1674** J15 → **1684** current - 0 failures, 0 skipped throughout |
| Frontend typecheck | `npm run typecheck` | **PASS** |
| Frontend lint | `npm run lint` | **PASS** - 0 errors, 17 warnings (pre-existing) |
| Frontend unit | `npm test` | **37** J01 → **60** J08 → **97** current - 0 failures |
| Frontend build | `npm run build` | **PASS** - 1.11 MB bundle, chunk-size warning only |
| E2E | `npx playwright test` | **34 passed, 7 skipped** (authenticated smoke skips without credentials) |
| Flyway history | `db/migration` | **V1 - V43**, contiguous, no duplicates. Next available: **V44** |

Docker Desktop was started locally for these runs, so the Testcontainers-backed classes ran for
real. No remote database was touched at any point in JOBS 01-16.

**Certification status:** `READY FOR QAS CERTIFICATION` · `NOT PRODUCTION CERTIFIED`. Nothing has
been deployed and no shared environment has been read from or written to. See
`TMS_ENTERPRISE_READINESS.md`.

**CORRECTION (phase 05):** the paragraph above is JOB 17's. Since then `dev` has been promoted to a
QAS environment (Render + Supabase, per `docs/operations/QAS_DEPLOYMENT_AND_RECOVERY.md`), but **no
document verifies which Flyway version QAS reached**; `origin/qas` was 12 commits behind `dev` on
2026-10-18 (missing V58 and ADR-020). There is **no production environment** and **no CI workflow**
in the repository until phase 05 wrote `.github/workflows/ci.yml` (written, not yet run - nothing is
pushed). Status stays `NOT PRODUCTION CERTIFIED`.

## Platform truth

| Concern | Reality in code | Note |
|---|---|---|
| Frontend design system | **MUI 9** (`@mui/material`, `@mui/icons-material`), **144** source files import it (91 at JOB 17) | Bootstrap and SweetAlert2 are **fully absent** from `package.json` and from `src/`. `confirmDialog` in `src/lib/ui.tsx` is a native MUI dialog. Recorded in **ADR-008**. Maps are **Leaflet** (`src/shared/maps/`), merged in `39b75fc` |
| Architecture | React -> Spring Boot -> PostgreSQL, enforced by `ModuleBoundaryTest` | Supabase is used from the frontend for authentication only. Supabase Storage is reached **from the backend only**, for evidence (ADR-020) |
| Backend shape | Modular monolith, **14 modules** + `shared` (appointments, audit, costing, fleet, iam, integration, masterdata, notification, orders, planning, rates, routing, settlement, tracking), **992 main / 229 test** Java files, **61** REST controllers | **CORRECTION (phase 05):** was "11 modules, 635 / 124, 41". No microservices, no broker. `iam` now holds three machine-facing sub-modules for MasterAdmin (`provisioning`, `entitlements`, `lifecycle`) on their own security chain |
| Tenancy | Company scope resolved server-side; RLS via `tms_app` runtime role (ADR-005); V50 removed privileges the schema already said `tms_app` did not have | Defense in depth, not a substitute for service authorization. V57 adds **RESTRICTIVE** carrier policies keyed on `tms.current_carrier_id()` |
| Security chains | User (Supabase JWT), partner (`/integration/v1`, client credentials + scopes), MasterAdmin (`/internal/platform-provisioning`, ES256, off by default) | `TenantSuspensionFilter` (ADR-021) and `CommercialAccessFilter` (ADR-017) sit on the tenant chains, separate from RBAC; `TenantSuspensionCoverageTest` pins that every chain carries the first |

## Capability matrix

| # | Capability | Status | Backend | Frontend | Tests | Security | Next step |
|---|---|---|---|---|---|---|---|
| 1 | **IAM, tenancy, permissions** | IMPLEMENTED | `iam/*`, `MeController`, `UserAdministrationController`, `CompanyAdministrationController`; V2-V5, V34 | `settings/users`, `security`, `CompanySelector` | `ApiSecurityTest`, `IdentityResolutionIntegrationTest`, company-scope suites | JWT validated in Spring Security; membership resolved server-side; RLS backstop | None for this run |
| 2 | **Master data - Locations** | IMPLEMENTED | `masterdata/LocationController`; V6, V14, V23 canonical unification | `masters/locations`, `origins`, `destinations` | Location eligibility + canonical unification suites | Company-scoped repository queries | None |
| 3 | **Zones, Frequencies, Routes** | IMPLEMENTED | `ZoneController`, `FrequencyController`, `RouteController`; V6-V8, V15, V24 | `masters/zones`, `frequencies`, `routes` | Frequency calendar, exception and cutoff tests | Company-scoped | None |
| 4 | **Fleet - carriers, vehicles, types, drivers** | IMPLEMENTED | `fleet/*`; V9, V16, V26 | `fleet/*` (4 screens) | Fleet master + double-booking tests | Company-scoped; external reference uniqueness per company | Availability/shifts are **MISSING** - see #17 |
| 5 | **Transport orders** | IMPLEMENTED | `orders/*`; V10, V17, **V36 execution lifecycle** | `orders` | Order totals, import, `OrderStatusTest`, `OrderPlanningServiceExecutionTest`, smoke steps 14-19 | Company-scoped; row lock on execution transitions | **8 states**: the lifecycle now carries dispatch, the three delivery outcomes and a reopen for a second attempt (ADR-009). Quantities on a partial are the ship-unit ledger's (JOB 03) |
| 6 | **Order splitting / partial allocation** | IMPLEMENTED | `OrderAmounts`/`OrderAllocation`, `OrderPlanningPort.allocate`; **V37** allocation ledger with `ck_transport_order_not_over_allocated` | `SplitAssignDrawer`, pending figures on the planning board | `OrderAmountsTest`, 7 split tests in `PlanningApiIntegrationTest` incl. a real concurrency race, DB constraint tests | Company-scoped; order row lock + DB CHECK | One order, several trips, never duplicated. **Delivered quantities** are deferred - see the doc's section 9 |
| 7 | **Manual planning** | IMPLEMENTED | `planning/TripService`, `TripAssignmentService`, `PlanningCapacityService`, `TripStopPlanner`; V11, V19 | `planning`, `planning/:runId` board | `PlanningApiIntegrationTest`, capacity and stop-sync suites | Company-scoped; optimistic version on runs; unique index for concurrent assignment | None |
| 8 | **Automatic planning** | IMPLEMENTED | `PlanningEngines` registry, `HEURISTIC_V1` + **`PLANNING_V2`** (ship-unit aware, distance-sequenced, shift-constrained), `PlanningKpis` | Engine selector + KPI block in `AutoPlanDrawer` | `PlanningEngineV2Test` (22), `PlanningEngineComparisonTest` (5, head-to-head), `PlanningEnginesTest` | Company-scoped through the materialising service | Default stays `HEURISTIC_V1` by decision. **Cost KPI now priced** through `CarrierQuotationPort` (JOB 11, debt D1 closed) - never a partial total, never an FX conversion, own fleet deliberately unpriced (**D6**) **CORRECTION (phase 05):** own fleet is no longer unpriced - V48 prices it as an *internal cost estimate* through `OwnFleetProposalCostingPort`, never compared as if it were a carrier price (row 28) |
| 9 | **Distance / travel time** | IMPLEMENTED | `routing` module, `RoutingPort`, `RoutingProviderAdapter`, local geodesic estimator, cache; **V38** (ADR-010) | `TripRouteCard` on the trip workspace | `GeodesicDistanceTest`, `RoutingServiceTest` (21), `RoutingServiceIntegrationTest`, `RoutingCacheConstraintIntegrationTest`, `TripRoutingServiceTest`, smoke step 13b | Company-scoped cache + RLS; verified by `SchemaExposureIntegrationTest` | **No vendor adapter** by decision (ADR-010). Consumed by planning today; rating/ETA attach to the same port |
| 10 | **Rates and costing** | IMPLEMENTED | `rates/*`; V30, V33, **V39**: 12 components, ordered arithmetic, LANE scope, measured-distance provenance | `rates/rate-cards` form + generic breakdown on `TripCostCard` | `RateEngineV2CalculatorTest` (15), lane selection tests, existing suites | Company-scoped; lane targets validated against active masters | **Proposal pricing delivered** by JOB 11 (`ProposalPricer`, 11 tests, 6 of them refusals). Own-fleet internal costing is still absent by decision -> **D6**. **CORRECTION (phase 05):** D6 closed by V48 (row 28). Still absent: rate tiers/breaks, a fuel index (fuel is a fixed %), and any **sell** rate |
| 11 | **Carrier tendering** | IMPLEMENTED | `TripTenderService` (V31) + **`TenderWaterfallService`, `CarrierRanking`, `CarrierQuotationPort`; V40** | `TenderWaterfallCard`, `TenderDrawer`, `TripTenderCard` | `CarrierRankingTest` (7), `TenderWaterfallTest` (12), existing tender suites | Company-scoped; trip row lock; 3 partial unique indexes | Ranked waterfall A→B→C works. **No background scheduler** (**D4**, deferred with reason - no fake system actor). Accepting still does not reassign the vehicle, but the resulting mismatch is now **representable and blocking**: V42 `accepted_carrier_id` + `ck_trip_departed_carrier_matches_vehicle` (JOB 09, **debt D2 closed**) |
| 12 | **Trip execution lifecycle** | IMPLEMENTED | `TripExecutionService`, `TripStopExecutionService`, `TripStatus` transition table; V25, V27 | `TripWorkspacePage`, `TripTimeline` | Transition-table unit tests + API integration tests | Company-scoped; atomic state transitions | None |
| 13 | **Delivery result and POD** | IMPLEMENTED | `TripDeliveryService`, `OrderDelivery`, `DeliveryResult`, `DeliveryEvidenceService`; V28, ADR-006, V36 | `DeliveryDrawer`, `DeliveryEvidenceDrawer` | Delivery result constraint and evidence suites, `OrderExecutionPropagatorTest` | Evidence behind `EvidenceStoragePort`, never a public URL | Delivery facts now **drive order status**, recomputed on every correction so the two cannot drift. **CORRECTION (phase 05):** delivered quantities exist since **V45** (`order_delivery_line`, `DeliveryQuantities`, debt D3 closed): per measure on the delivery and per line in the line's own unit, `delivered + refused <= attempted`, absent never read as zero. Evidence may now live in Supabase Storage (row 36). `DELIVERY_RESULT_RECORDED` is still ignored by EWM, so the POD does not travel back to the warehouse |
| 14 | **Tracking positions** | PARTIAL | `tracking/*`, `TrackingIntakePort`/`TrackingProviderPort` (ADR-007); V29 | `TripTrackingCard` | Sampling rule, position validation, ingestion tests | Provider scope on integration clients | Positions are stored and shown. **Stop ETA and geofences delivered** by JOB 10 (V43, ADR-011) - an unmeasurable leg ends the chain and every later stop has no estimate. Geofence is **observational only**; **route deviation and automatic arrival detection remain absent by decision** (ADR-007: a position informs and never moves a lifecycle) **CORRECTION (phase 05):** the frontend draws positions and stops on **Leaflet** maps (`VehicleTrackMap`, `TripStopMap`). Still no vendor adapter and no live fleet map; the dispatched-shipment map is row 35 |
| 15 | **Appointments / dock scheduling** | IMPLEMENTED | `appointments` module, 4 tables, **V41** with `EXCLUDE USING gist` making double-booking impossible | `/appointments` dock board + booking drawer | `AppointmentStatusTest` (27), `AppointmentServiceIntegrationTest` (22) incl. a real two-dispatcher race | 4 company-scoped tables + RLS; composite FKs pin doors to their company | Opening hours stored as minutes-of-day - a `time` column was being zone-shifted. **No WMS table shared**, by design |
| 16 (JOB 17, superseded) | ~~Freight audit and settlement~~ | ~~MISSING~~ | `TripCost` records estimated and actual cost, which is the foundation; there is **no carrier invoice, no match, no tolerance, no discrepancy, no approval, no export** - verified by inspection at `729f155` | `ActualCostDrawer` records actual cost only | Cost calculation tests | Company-scoped | **CORRECTION (JOB 17):** this row previously pointed at "JOB 11". **JOB 11 was titled *Settlement* but delivered proposal pricing** (closing debt D1) and never built freight audit. Settlement is scheduled for **Phase 2 JOB 20** |
| 16 | **Freight audit and settlement** | IMPLEMENTED (v1, gaps named) | `settlement/*`: `SettlementController` (`/settlement/invoices`: create, read, `match`, `review`, discrepancy `resolve`, `approve`, `reject`, `export`), `SettlementService`, pure `FreightMatcher`; **V46** (7 tables: `carrier_invoice`, `carrier_invoice_line`, `freight_match`, `freight_discrepancy`, `settlement_approval`, `tolerance_policy`, `payable_export`) | `/settlement` (`SettlementPage`) | `FreightMatcherTest`, `SettlementApiIntegrationTest` | Company-scoped + RLS; only a person approves (`requireAppUserId`, `decided_by NOT NULL`), four eyes (`requireDifferentApprover`), never past an open discrepancy; TMS validates, the ERP pays - no ledger, no payment | **CORRECTION (phase 05):** was MISSING pointing at "Phase 2 JOB 20"; V46 delivered v1. **Gaps read from the code at `40aad53`:** (a) `tolerance_policy` is read by `SettlementService.toleranceFor` but **nothing writes it** - no endpoint, no UI - so every company runs `Tolerance.NONE`; (b) a second invoice line for the same trip is raised as `DUPLICATE_INVOICE` by `FreightMatcher` (one line per trip); (c) `freight_discrepancy.invoice_line_id` is always written `NULL` (`SettlementService:326`); (d) `PayableExport.Format.CSV` is declared with no adapter - only `JsonPayableExportAdapter`, no GL account, cost centre or tax; (e) **absent**: accrual, payments (excluded on purpose, V46), IGV/detracción/retención/CPE SUNAT, credit notes and penalties (`total_amount >= 0`), cost per order/customer (debt D10). Phase plan: `docs/roadmap/2026-10-enterprise/03_FINANZAS_FLETES.md` |
| 17 | **Fleet resource scheduling** | PARTIAL | **V42 delivered** driver and vehicle unavailability (two partial `EXCLUDE` constraints), maintenance reasons and weekly `driver_shift` stored as minutes-of-day; readable by planning through `ResourceAvailabilityPort` | Availability drawer on the vehicle and driver screens | `ResourceAvailabilityIntegrationTest` incl. a real two-thread race on one truck | Company-scoped; reads and writes split across `fleet.vehicle:*` / `fleet.driver:*` so workshop clerks cannot read medical absences | Availability is a **layer, not a scheduler**. Sequencing several shipments onto one driver-and-vehicle pair is **debt D5** -> **Phase 2 JOB 21**. **CORRECTION (phase 05):** D5 closed by **V47** - several shipments onto one driver-and-vehicle pair is row 27. Availability itself is unchanged |
| 18 | **Exceptions and control tower** | PARTIAL | `TripException` (trip-scoped, typed, resolvable); `ControlTowerService` with summary, trips, stops, workload views | `control-tower` with panels | Control tower filter and view tests, exception lifecycle tests | Company-scoped | **JOB 12 added the blocker panel** - shipments that cannot depart, each row a hard stop that makes `dispatch` refuse - and JOB 16 closed debt D7 by covering the V1 summary. Exceptions are still **trip-only**: no generic operational exception across orders, tenders, tracking or invoices, no SLA, no dedup, no assignment -> **Phase 2 JOB 23** |
| 19 | **Integrations (inbound)** | IMPLEMENTED | `integration/*`, 8 controllers, client credentials, scopes, idempotent inbox; V18, V20 | `settings/integrations` | Integration identity, idempotency and scope suites | Machine principals distinct from human; per-client scopes; SSRF protections | **JOB 13 added integration health** (age not count; inactive subscriptions holding a backlog). A formal dead-letter store and range replay are still absent. **CORRECTION (phase 05):** now **12** controllers under `integration/api`, including `IntegrationWarehouseController` (`dispatch-confirmations`, `warehouse-milestones`, row 30) and `IntegrationDocumentController` (`logistics-documents`, row 32). Dead-letter store and range replay still absent |
| 20 | **Outbound webhooks** | PARTIAL | `WebhookController`, `WebhookDelivery`, attempts, encrypted secrets; V35 | Subscriptions in `settings/integrations` | Webhook delivery and cipher tests | Secrets encrypted at rest, never returned | Per-delivery manual retry and a health summary exist (JOB 13). **No distinct dead-letter store, no range replay, no payload inspection UI**; bulk retry is refused by decision |
| 21 | **Notifications** | IMPLEMENTED | `notification/*`; V32 | `NotificationsMenu` | Notification suites | Company- and user-scoped | None |
| 22 | **Audit trail** | IMPLEMENTED | `audit/*`, `AuditAction`; V22 | `security/audit` | Audit query and rendering tests | Company-scoped; actor type recorded | Extend to new modules as they land |
| 23 | **KPIs and reporting** | IMPLEMENTED | `KpiService` with cost, orders, shipments, service, tender, utilization, exceptions views + export | `reporting` | `ReportsPage.test.tsx`, KPI service suites | Company-scoped | Planning KPIs delivered by JOB 05 and priced by JOB 11. Settlement KPIs will follow **Phase 2 JOB 20**. **CORRECTION (phase 05):** no settlement KPI exists in `KpiService` even though V46 landed (row 16); still open |
| 24 | **Master data import** | IMPLEMENTED | 5 import controllers, `MasterDataImportBatch`; V21 | Import drawers on masters screens | Import batch suites | Company-scoped | None |
| 25 | **Observability** | PARTIAL | Correlation ID in MDC, structured logging, Micrometer on tracking/audit/notification/integration and **routing** (V38) | - | Metric assertions in `RoutingServiceTest` | No secrets logged; `SecretExposureTest` (JOB 15) refuses a secret reaching any view | Planning, rating, tender and settlement metrics still absent, and **`docs/operations/` does not exist** - no runbook, no alert catalogue, no SLO -> **Phase 2 JOB 24**. **CORRECTION (phase 05):** `docs/operations/` **exists** (`OBSERVABILITY.md`, `RUNBOOK_INCIDENTS.md`, `DEPLOYMENT.md`, `PROMOTION.md`, `QAS_DEPLOYMENT_AND_RECOVERY.md`, `BACKUP_AND_RESTORE.md`, `PERFORMANCE_BASELINE.md`) and `MetricCatalogueTest` pins the catalogue; Micrometer now also covers settlement, own-fleet costing, tender waterfall and appointments. No CI ran any of it before phase 05 |
| 26 | **Persisted-enum guard** | IMPLEMENTED | **V44**: the one `@Enumerated(STRING)` column with no CHECK (`trip_cost.rate_card_scope`) | - | `PersistedEnumConstraintTest` compares every persisted enum with its CHECK | - | Added by Phase 2 JOB 18. Keeps every new enum column honest |
| 27 | **Work assignment** (one driver + vehicle, several shipments a day) | IMPLEMENTED | `fleet/WorkAssignmentController` (`/fleet/work-assignments`: list, read, create, `PUT`, `confirm`, `cancel`), `WorkAssignmentService`; **V47** (`work_assignment`, `work_assignment_trip`) | `/work-assignments` | `WorkAssignmentTest`, `WorkAssignmentApiIntegrationTest` | Company-scoped + RLS. Organises existing trips only - **never a route past a dispatch guard**; each trip keeps its status and guards | Closes debt D5. No sequencing optimiser (route optimisation stays deferred) |
| 28 | **Own-fleet internal cost** | IMPLEMENTED (estimate only) | `costing/*`: `OwnFleetCostingController`, `OwnFleetCostProfileService`, `OwnFleetCostCalculator`, `OwnFleetProposalCostingAdapter` behind `shared/reference/OwnFleetProposalCostingPort`; **V48** (`own_fleet_cost_profile`) | `/costing/own-fleet` | `OwnFleetCostCalculatorTest`, `OwnFleetProfileResolverTest`, `OwnFleetCostingApiIntegrationTest` | Company-scoped + RLS | Closes debt D6: a **cost**, never presented as a carrier **price**. Only an estimate - there is no actual own-fleet cost (fuel, tolls, hours) captured |
| 29 | **Tenant provisioning by MasterAdmin** | IMPLEMENTED | `iam/provisioning/*` (`PlatformProvisioningController`, own `PlatformProvisioningSecurityConfig`); **V51** (`platform_provisioning_request`, `platform_provisioning_audit`); ADR-012 | - (machine channel) | `PlatformProvisioningApiTest`, `PlatformProvisioningDisabledApiTest`, `PlatformProvisioningServiceTest` | ES256 public key, own chain, **off by default**; one transaction; administrator PREPROVISIONED (no Auth account created) | Contract `docs/platform-provisioning/MASTERADMIN_GENERIC_CONTRACT.md` |
| 30 | **Dispatch mode and external (warehouse) dispatch** | IMPLEMENTED | `planning/ExternalDispatchService`, pure `DispatchReconciler`; `IntegrationWarehouseController` (`POST /integration/v1/dispatch-confirmations`, `POST /integration/v1/warehouse-milestones`); `TripWarehouseController`; **V52** (`company_settings.dispatch_confirmation_mode`, `trip.dispatch_source`), **V53** (`external_dispatch`, `external_dispatch_order`, `warehouse_milestone`); ADR-013 | Company settings (mode), trip workspace "Despacho de almacén" card, Control Tower advisories | `WarehouseExecutionApiIntegrationTest`, `DispatchReconcilerTest`, `DispatchReadinessTest`, `DispatchSourceMigrationIntegrationTest` | Own integration scopes, inbox idempotency + business key `ewm-dispatch:<SLS>:r<rev>`; override needs `planning.trip:dispatch-override`; departure names exactly one actor (person or credential) | TMS ⇄ EWM v1 **merged 2026-09-28** (PR #13). The plan is never rewritten; variance is derived on read. Open gaps are `00_ESTADO_ACTUAL.md` section 3 (EWM drops weight/volume/windows, ignores POD, no order cancellation across, plate in `trailer_number`, no `updatedSince` pull) |
| 31 | **Scheduling, release and order holds** | IMPLEMENTED | `orders/OrderSchedulingController` (`/orders/scheduling`, `/orders/scheduling/summary`, `/orders/release`, `/orders/{id}/scheduling`), `OrderReleaseService`, `OrderHoldController` (`/orders/{id}/holds`); **V54** (`order_hold`, three audit actions, `orders.hold:manage`); ADR-014 | `/scheduling` (`SchedulingReleasePage`) | `SchedulingAssessmentTest`, `ReleaseDeadlineTest`, `SchedulingReleaseIntegrationTest`, `OrderUpsertReleaseIntegrationTest` | Company-scoped + RLS | `READY_FOR_PLANNING` **is** "released"; eligibility (`ELIGIBLE`/`WARNING`/`BLOCKED`) derived on read, never stored. No scheduler, no new order state |
| 32 | **Logistics documents** (invoice, delivery note, GRE) | IMPLEMENTED (reference only) | `IntegrationDocumentController` (`/integration/v1/logistics-documents`), `JdbcLogisticsDocumentAdapter` behind `LogisticsDocumentPort`; `TripDocumentController` (read on a trip); **V55** (`logistics_document`, `logistics_document_order`, many to many); ADR-015 | Documents on the trip workspace | `LogisticsDocumentApiIntegrationTest` | Company-scoped + RLS; integration scope | Records what the ERP issued and never moves an order or a trip. TMS **does not emit** a GRE transportista or any CPE |
| 33 | **Commercial entitlements** | IMPLEMENTED (`appActive` only) | `iam/entitlements/*` (`PlatformEntitlementsController`, `ApplyEntitlementSnapshotService`), `shared/security/CommercialAccessGate` + `CommercialAccessFilter`; **V56** (6 tables, mode seeded SHADOW); ADR-017 | - (machine channel) | `PlatformEntitlementsApiTest`, `PlatformEntitlementsIntegrationTest`, `PlatformEntitlementsContractFixturesTest`, `CommercialAccessGateAdapterTest`, `CommercialAccessFilterTest`, `CommercialAccessSecurityTest` | Scopes `tms:entitlements:write|read`; decisions read the local copy only (MasterAdmin down never stops trucks); separate from RBAC | Sellable registry is **empty**: no permission is a paid capability. Contract `docs/platform-provisioning/ENTITLEMENTS.md` |
| 34 | **Carrier portal and document delivery tracking** | IMPLEMENTED | `PortalTripController` (`/portal/trips`, `/{tripId}`, document `report` multipart, evidence content), `DocumentDeliveryController` (`/document-deliveries`, summary, detail, office `return`), `DocumentDeliveryTracker`; **V57** (`membership.carrier_id`, role `CARRIER_OPERATOR`, `document_delivery` + orders/events/evidence, `tms.current_carrier_id()`); ADR-018 | `/portal` (`PortalLayout`, `PortalTripPage`), `/document-deliveries` monitor | `CarrierDocumentDeliveryIntegrationTest`, portal component tests | Carrier membership holds only `CARRIER` roles; Java scoping + **RESTRICTIVE** RLS on `current_carrier_id()`; every portal write carries `Idempotency-Key` | Trips appear once the warehouse dispatch (row 30) arrives. `PENDING` -> `DELIVERED` (photo mandatory) / `REJECTED` / `NOT_DELIVERED`; nothing moves an order or a trip. Supersedes ADR-016 |
| 35 | **Carrier offline capture and shipment tracking map** | IMPLEMENTED | `ShipmentTrackingController` (`GET /monitoring/shipment-tracking`, `/summary`), `ShipmentTrackingQueries`; ADR-019 | `public/sw.js` (hand-written service worker, shell only, never `/api`), `pages/portal/offline/*` (IndexedDB per user, outbox replayed with its `Idempotency-Key`), `/shipment-tracking` with a Leaflet map | `outboxEngine.test.ts`, `PortalLayout.test.tsx`, `DocumentReportDrawer.test.tsx` | Company-scoped; the outbox replays as the same user and key | The map shows **shipments the warehouse actually dispatched**, with stops and documents. It is **not** a live fleet map (still deferred) |
| 36 | **Evidence object storage** | IMPLEMENTED | `shared/storage/EvidenceStoragePort` with `DisabledEvidenceStorage` (default), `LocalFilesystemEvidenceStorage`, `SupabaseEvidenceStorage`; ADR-006, ADR-020 | POD and carrier document photo drawers | `LocalFilesystemEvidenceStorageTest`, `SupabaseEvidenceStorageTest`, `EvidenceStorageConfigSupabaseTest`, `EvidenceStoragePropertiesTest`; `SupabaseEvidenceStorageLiveTest` skipped by design | Private bucket `tms-evidence` with no policies, reached **only by the backend** with `service_role`; same keys across stores; no public URL | Lifts the Storage deferral for evidence only |
| 37 | **Operational tenant lifecycle (suspension)** | IMPLEMENTED | `iam/lifecycle/*` (`PlatformLifecycleController`, `PlatformLifecycleService`, `LifecycleApplyWriter`), `shared/security/TenantSuspensionFilter`; **V58** (`platform_tenant_lifecycle`, `_audit`); ADR-021 | `TenantSuspendedBanner` ("Cuenta suspendida"), `tenant-suspended` error | `PlatformLifecycleApiTest`, `TenantSuspensionFilterTest`, `TenantSuspensionSecurityTest`, `TenantSuspensionCoverageTest`; **`PlatformLifecycleIntegrationTest.versionRules` red** (test harness defect, see baseline) | Scopes `tms:lifecycle:write|read`; while `SUSPENDED` every company-scoped write gets `423 tenant-suspended` on user and partner chains; reads, login and `/me` stay open; no data touched | Separate from commercial access (row 33) and from RBAC. Fix the red test's harness. Contract `docs/platform-provisioning/LIFECYCLE.md` |
| 38 | **Runtime role least privilege** | IMPLEMENTED | **V49** (explicit grant on `shipment_number_seq`), **V50** (REVOKEs the schema had promised in prose; fixed `search_path` on functions) | - | `SchemaExposureIntegrationTest`, `TenantRlsIsolationIntegrationTest` | `tms_app` holds only what each table needs; documents keep DELETE only where a re-send rewrites links | Every new table must declare its grants explicitly, not rely on default privileges |
| 39 | **Material physical profile and order-line snapshot** | IMPLEMENTED (phase 20; geometry not yet computed) | `masterdata` `MaterialProfile`, `MaterialProfileIntakeService` (port `shared.reference.MaterialProfileIntakePort`), `MaterialProfileLookupAdapter` (port `MaterialProfileLookupPort`), `MaterialProfileController` (`GET /api/v1/masterdata/material-profiles`, `/{id}`); `integration` `IntegrationMaterialProfileController` (`PUT /integration/v1/material-profiles/{materialCode}`, `POST …/batch`); `orders` `OrderLinePhysicalResolver` (one copy rule for form, order API and import); `shared.geometry` `PhysicalBlock`, `StackMode`, `UnitsOfMeasure`; **V59** (`material_profile`, snapshot columns on `transport_order_line`, scope, permission, audit); ADR-022 §1-2 | `/masters/material-profiles` "Perfiles de material" (read only, list + drawer) | `MaterialProfileApiIntegrationTest`, `OrderLineMaterialSnapshotIntegrationTest`, `OrderLinePhysicalResolverTest`, `PhysicalBlockTest`, `MaterialProfilesPage.test.tsx` | RLS `p_tenant_company_scope`, no DELETE grant; scope `integration.material:write`; permission `masterdata.material_profile:read` (ORGANIZATION_ADMIN, COMPANY_ADMIN, PLANNER, VIEWER) | The warehouse owns the profile (D-02); TMS never edits it. Stacks, effective volume, floor and height capacity are ADR-022 §3-7 (phase 40, V62). The bulk import template carries no physical columns; imported lines get the profile copy only. Domain: `docs/domain/MATERIAL_PROFILE_V1.md` |
| 40 | **Order cancellation with the warehouse (both ways)** | IMPLEMENTED (phase 35) | `orders` `OrderService.cancel` publishes `ORDER_CANCELLED` through `shared.reference.BusinessEventPublisher` (impl. `integration` `BusinessEventOutboxAdapter`, table `business_outbox_event`, same webhook fan-out); `OrderPublicationService` (port `OrderPublicationPort`) behind `GET /integration/v1/orders/{orderNumber}` and `GET /integration/v1/orders?status=&updatedSince=`; `IntegrationWarehouseOrderEventController` `POST /integration/v1/warehouse-order-events` → `WarehouseOrderEventRecorder` → `OrderHoldPort.placeFromIntegration` (`OrderHold.byIntegration`); **V61** (`business_outbox_event`, `warehouse_order_event`, two scopes, `ORDER_CANCELLED` subscribable, order feed index); ADR-025 §4 | The hold appears in the scheduling drawer as "Puesta por integración" with its reason code (`WAREHOUSE_CANCELLED`, `WAREHOUSE_SHORT_CLOSED`) | `OrderCancellationSyncIntegrationTest` (9), `WarehouseOrderEventRulesTest` (3), `WebhookEventTypeTest`, `enums.test.ts` | RLS `p_tenant_company_scope` on both tables, SELECT/INSERT only; scopes `integration.order:read`, `integration.warehouse-order-event:write` | Neither side cancels by itself (D-03): TMS holds, the warehouse alerts. A hold on a committed trip blocks dispatch and raises `ORDER_HOLD_ON_COMMITTED_TRIP` instead of a trip exception (DECISIONS_LOG fase 10). Contract `WAREHOUSE_EXECUTION_V1.md` §7.4-7.5 |

## What must not be re-created

*Written during JOBS 01-08 and still true. Extending these is correct; rebuilding them is waste.*

- The `PlanningEngine` port. JOB 05 adds an implementation beside `HEURISTIC_V1`; it does not
  restructure planning.
- `TripStatus`, `TenderStatus`, `StopExecutionStatus` and `DeliveryResult`, each with its
  transition rules in the domain and asserted twice (service + entity) with DB CHECKs beneath.
- `OrderDelivery` and the POD evidence port. JOB 02 consumes these facts; it does not re-model them.
- The integration inbox, its idempotency keys and its scope model. JOB 13 adds operations on top.
- `trip_order_assignment.whole_order` and its partial unique index, which V11 wrote specifically so
  that split allocation could arrive later without touching an applied migration. JOB 03 used it
  exactly as intended: the ledger needed a ceiling, not a new table.

*Added in phase 05 (2026-10-18) for what V45-V58 built.*

- `FreightMatcher` and the V46 tables. Tolerance editing, multi-line invoices, CSV export, accrual and
  taxes extend settlement; they do not start a second invoice model.
- `ExternalDispatch` + `DispatchReconciler`. More warehouse fields arrive as additive contract
  fields and reconciliation lines, never as a second dispatch path or a warehouse state in
  `TripStatus`.
- `EvidenceStoragePort`. A new store is one more implementation; no caller changes.
- `TenantSuspensionFilter` and `CommercialAccessGate`: one filter per policy on every tenant chain,
  kept apart from RBAC and from each other.

## Documentation corrected in phase 05 (2026-10-18)

| File | Was | Now |
|---|---|---|
| this file | Reconciled at `729f155`, V1-V43, settlement MISSING | Reconciled at `40aad53`, V1-V58, rows 26-38, row 16 IMPLEMENTED v1 with its gaps |
| `CLAUDE.md` | "EWM integration" and "live map tracking" in *Deferred by decision* | EWM integration v1 merged (ADR-013, `WAREHOUSE_EXECUTION_V1.md`); the Leaflet map of dispatched shipments exists; a live fleet map stays deferred |
| `docs/implementation/TMS_EWM_IMPLEMENTATION_REPORT.md` | "Nothing was merged" | Dated note at the top: merged 2026-09-28; body left as the record |
| `docs/integrations/WAREHOUSE_EXECUTION_V1.md` | `SHIPMENT_CHANGED` producer and three additive fields "planned" | Dated note at the top: both exist; body left as the record |
| `.github/workflows/ci.yml` | did not exist | backend `./mvnw -B test`, frontend typecheck/lint/test/build; written, not run (nothing pushed) |

## Documentation corrected in JOB 17

| File | Was | Now |
|---|---|---|
| `CLAUDE.md` | "Bootstrap as the visual base", "Avoid MUI" | MUI 9, per ADR-008 |
| `README.md` | Bootstrap + SweetAlert2 | MUI |
| `docs/architecture/TMS_ARCHITECTURE_V1.md` | Bootstrap + SweetAlert2 | MUI |
| `docs/product/ARCHITECTURE_OVERVIEW.md` | Bootstrap | MUI |
| `docs/product/SELLABLE_CAPABILITIES.md` | "Bootstrap-based design system" | MUI-based |

Historical step reports under `docs/overnight*/`, `docs/hardening-v4/` and `docs/reviews/` were
**deliberately left alone**. They are dated records of what was true when written, and rewriting
them would destroy the audit trail rather than correct it. ADR-008 is where the change of record
lives.
