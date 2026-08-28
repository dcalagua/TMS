# TMS by EBIM - Current Capability Map

**Reconstructed from code on 2026-08-28**, originally at commit `0757afb`, and **reconciled at
commit `729f155` after JOBS 01-16 and the certification repair** (Phase 2, JOB 17). Branch `dev`.
Rows are kept current as the chain proceeds; the job that changed one is named in its Next step.

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

## Baseline measured this session

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

## Platform truth

| Concern | Reality in code | Note |
|---|---|---|
| Frontend design system | **MUI 9** (`@mui/material`, `@mui/icons-material`), 91 source files import it | Bootstrap and SweetAlert2 are **fully absent** from `package.json` and from `src/`. `confirmDialog` in `src/lib/ui.tsx` is a native MUI dialog. Recorded in **ADR-008** |
| Architecture | React -> Spring Boot -> PostgreSQL, enforced by `ModuleBoundaryTest` | Supabase is used from the frontend for authentication only |
| Backend shape | Modular monolith, 11 modules, 635 main / 124 test Java files, 41 REST controllers | No microservices, no broker |
| Tenancy | Company scope resolved server-side; RLS via `tms_app` runtime role (ADR-005) | Defense in depth, not a substitute for service authorization |

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
| 8 | **Automatic planning** | IMPLEMENTED | `PlanningEngines` registry, `HEURISTIC_V1` + **`PLANNING_V2`** (ship-unit aware, distance-sequenced, shift-constrained), `PlanningKpis` | Engine selector + KPI block in `AutoPlanDrawer` | `PlanningEngineV2Test` (22), `PlanningEngineComparisonTest` (5, head-to-head), `PlanningEnginesTest` | Company-scoped through the materialising service | Default stays `HEURISTIC_V1` by decision. **Cost KPI now priced** through `CarrierQuotationPort` (JOB 11, debt D1 closed) - never a partial total, never an FX conversion, own fleet deliberately unpriced (**D6**) |
| 9 | **Distance / travel time** | IMPLEMENTED | `routing` module, `RoutingPort`, `RoutingProviderAdapter`, local geodesic estimator, cache; **V38** (ADR-010) | `TripRouteCard` on the trip workspace | `GeodesicDistanceTest`, `RoutingServiceTest` (21), `RoutingServiceIntegrationTest`, `RoutingCacheConstraintIntegrationTest`, `TripRoutingServiceTest`, smoke step 13b | Company-scoped cache + RLS; verified by `SchemaExposureIntegrationTest` | **No vendor adapter** by decision (ADR-010). Consumed by planning today; rating/ETA attach to the same port |
| 10 | **Rates and costing** | IMPLEMENTED | `rates/*`; V30, V33, **V39**: 12 components, ordered arithmetic, LANE scope, measured-distance provenance | `rates/rate-cards` form + generic breakdown on `TripCostCard` | `RateEngineV2CalculatorTest` (15), lane selection tests, existing suites | Company-scoped; lane targets validated against active masters | **Proposal pricing delivered** by JOB 11 (`ProposalPricer`, 11 tests, 6 of them refusals). Own-fleet internal costing is still absent by decision -> **D6** |
| 11 | **Carrier tendering** | IMPLEMENTED | `TripTenderService` (V31) + **`TenderWaterfallService`, `CarrierRanking`, `CarrierQuotationPort`; V40** | `TenderWaterfallCard`, `TenderDrawer`, `TripTenderCard` | `CarrierRankingTest` (7), `TenderWaterfallTest` (12), existing tender suites | Company-scoped; trip row lock; 3 partial unique indexes | Ranked waterfall A→B→C works. **No background scheduler** (**D4**, deferred with reason - no fake system actor). Accepting still does not reassign the vehicle, but the resulting mismatch is now **representable and blocking**: V42 `accepted_carrier_id` + `ck_trip_departed_carrier_matches_vehicle` (JOB 09, **debt D2 closed**) |
| 12 | **Trip execution lifecycle** | IMPLEMENTED | `TripExecutionService`, `TripStopExecutionService`, `TripStatus` transition table; V25, V27 | `TripWorkspacePage`, `TripTimeline` | Transition-table unit tests + API integration tests | Company-scoped; atomic state transitions | None |
| 13 | **Delivery result and POD** | IMPLEMENTED | `TripDeliveryService`, `OrderDelivery`, `DeliveryResult`, `DeliveryEvidenceService`; V28, ADR-006, V36 | `DeliveryDrawer`, `DeliveryEvidenceDrawer` | Delivery result constraint and evidence suites, `OrderExecutionPropagatorTest` | Evidence behind `EvidenceStoragePort`, never a public URL | Delivery facts now **drive order status**, recomputed on every correction so the two cannot drift |
| 14 | **Tracking positions** | PARTIAL | `tracking/*`, `TrackingIntakePort`/`TrackingProviderPort` (ADR-007); V29 | `TripTrackingCard` | Sampling rule, position validation, ingestion tests | Provider scope on integration clients | Positions are stored and shown. **Stop ETA and geofences delivered** by JOB 10 (V43, ADR-011) - an unmeasurable leg ends the chain and every later stop has no estimate. Geofence is **observational only**; **route deviation and automatic arrival detection remain absent by decision** (ADR-007: a position informs and never moves a lifecycle) |
| 15 | **Appointments / dock scheduling** | IMPLEMENTED | `appointments` module, 4 tables, **V41** with `EXCLUDE USING gist` making double-booking impossible | `/appointments` dock board + booking drawer | `AppointmentStatusTest` (27), `AppointmentServiceIntegrationTest` (22) incl. a real two-dispatcher race | 4 company-scoped tables + RLS; composite FKs pin doors to their company | Opening hours stored as minutes-of-day - a `time` column was being zone-shifted. **No WMS table shared**, by design |
| 16 | **Freight audit and settlement** | MISSING | `TripCost` records estimated and actual cost, which is the foundation; there is **no carrier invoice, no match, no tolerance, no discrepancy, no approval, no export** - verified by inspection at `729f155` | `ActualCostDrawer` records actual cost only | Cost calculation tests | Company-scoped | **CORRECTION (JOB 17):** this row previously pointed at "JOB 11". **JOB 11 was titled *Settlement* but delivered proposal pricing** (closing debt D1) and never built freight audit. Settlement is scheduled for **Phase 2 JOB 20** |
| 17 | **Fleet resource scheduling** | PARTIAL | **V42 delivered** driver and vehicle unavailability (two partial `EXCLUDE` constraints), maintenance reasons and weekly `driver_shift` stored as minutes-of-day; readable by planning through `ResourceAvailabilityPort` | Availability drawer on the vehicle and driver screens | `ResourceAvailabilityIntegrationTest` incl. a real two-thread race on one truck | Company-scoped; reads and writes split across `fleet.vehicle:*` / `fleet.driver:*` so workshop clerks cannot read medical absences | Availability is a **layer, not a scheduler**. Sequencing several shipments onto one driver-and-vehicle pair is **debt D5** -> **Phase 2 JOB 21** |
| 18 | **Exceptions and control tower** | PARTIAL | `TripException` (trip-scoped, typed, resolvable); `ControlTowerService` with summary, trips, stops, workload views | `control-tower` with panels | Control tower filter and view tests, exception lifecycle tests | Company-scoped | **JOB 12 added the blocker panel** - shipments that cannot depart, each row a hard stop that makes `dispatch` refuse - and JOB 16 closed debt D7 by covering the V1 summary. Exceptions are still **trip-only**: no generic operational exception across orders, tenders, tracking or invoices, no SLA, no dedup, no assignment -> **Phase 2 JOB 23** |
| 19 | **Integrations (inbound)** | IMPLEMENTED | `integration/*`, 8 controllers, client credentials, scopes, idempotent inbox; V18, V20 | `settings/integrations` | Integration identity, idempotency and scope suites | Machine principals distinct from human; per-client scopes; SSRF protections | **JOB 13 added integration health** (age not count; inactive subscriptions holding a backlog). A formal dead-letter store and range replay are still absent |
| 20 | **Outbound webhooks** | PARTIAL | `WebhookController`, `WebhookDelivery`, attempts, encrypted secrets; V35 | Subscriptions in `settings/integrations` | Webhook delivery and cipher tests | Secrets encrypted at rest, never returned | Per-delivery manual retry and a health summary exist (JOB 13). **No distinct dead-letter store, no range replay, no payload inspection UI**; bulk retry is refused by decision |
| 21 | **Notifications** | IMPLEMENTED | `notification/*`; V32 | `NotificationsMenu` | Notification suites | Company- and user-scoped | None |
| 22 | **Audit trail** | IMPLEMENTED | `audit/*`, `AuditAction`; V22 | `security/audit` | Audit query and rendering tests | Company-scoped; actor type recorded | Extend to new modules as they land |
| 23 | **KPIs and reporting** | IMPLEMENTED | `KpiService` with cost, orders, shipments, service, tender, utilization, exceptions views + export | `reporting` | `ReportsPage.test.tsx`, KPI service suites | Company-scoped | Planning KPIs delivered by JOB 05 and priced by JOB 11. Settlement KPIs will follow **Phase 2 JOB 20** |
| 24 | **Master data import** | IMPLEMENTED | 5 import controllers, `MasterDataImportBatch`; V21 | Import drawers on masters screens | Import batch suites | Company-scoped | None |
| 25 | **Observability** | PARTIAL | Correlation ID in MDC, structured logging, Micrometer on tracking/audit/notification/integration and **routing** (V38) | - | Metric assertions in `RoutingServiceTest` | No secrets logged; `SecretExposureTest` (JOB 15) refuses a secret reaching any view | Planning, rating, tender and settlement metrics still absent, and **`docs/operations/` does not exist** - no runbook, no alert catalogue, no SLO -> **Phase 2 JOB 24** |

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

## Documentation corrected in this job

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
