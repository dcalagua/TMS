# TMS by EBIM - Current Capability Map

**Reconstructed from code on 2026-08-28**, at commit `0757afb`, branch `dev`. Rows are kept
current as the chain proceeds; the job that changed one is named in its Next step.

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
| Backend | `./mvnw -B test` | **1312 tests** at JOB 01; **1389** after JOB 02 - 0 failures |
| Frontend typecheck | `npm run typecheck` | **PASS** |
| Frontend lint | `npm run lint` | **PASS** - 0 errors, 17 warnings (pre-existing) |
| Frontend unit | `npm test` | **37 tests** at JOB 01; **42** after JOB 02 - 0 failures |
| Frontend build | `npm run build` | **PASS** - 1.11 MB bundle, chunk-size warning only |
| E2E | `npx playwright test` | **33 passed, 7 skipped** (authenticated smoke skips without credentials) |
| Flyway history | `db/migration` | **V1 - V36**, contiguous. Next available: **V37** |

Docker Desktop was started locally for this run, so the 32 Testcontainers-backed classes ran for
real. No remote database was touched.

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
| 5 | **Transport orders** | IMPLEMENTED | `orders/*`; V10, V17, **V36 execution lifecycle** | `orders` | Order totals, import, `OrderStatusTest`, `OrderPlanningServiceExecutionTest`, smoke steps 14-19 | Company-scoped; row lock on execution transitions | **8 states**: the lifecycle now carries dispatch, the three delivery outcomes and a reopen for a second attempt (ADR-009). Quantities on a partial are JOB 03's |
| 6 | **Order splitting / ship units** | MISSING | No `ship_unit` table or type. `trip_order_assignment.whole_order` exists and V11's unique index is deliberately partial on `whole_order = true`, leaving room | Planning board shows whole orders only | - | - | Introduce ship units and allocation ledger -> **JOB 03** |
| 7 | **Manual planning** | IMPLEMENTED | `planning/TripService`, `TripAssignmentService`, `PlanningCapacityService`, `TripStopPlanner`; V11, V19 | `planning`, `planning/:runId` board | `PlanningApiIntegrationTest`, capacity and stop-sync suites | Company-scoped; optimistic version on runs; unique index for concurrent assignment | None |
| 8 | **Automatic planning** | PARTIAL | `PlanningEngine` port + `HeuristicPlanningEngine` (`HEURISTIC_V1`), `AutoPlanningService` | Proposal reviewable on the board | Pure unit tests (no DB) | Company-scoped through the materialising service | Engine is a pure function with a clean port. **No KPIs, no cost/time objective, no route feasibility** -> **JOB 05** |
| 9 | **Distance / travel time** | MISSING | Only `route.reference_distance_km`, a static master-data column | - | - | - | No routing port, no cache, no provider abstraction -> **JOB 04** |
| 10 | **Rates and costing** | PARTIAL | `rates/*`, `RateCardService`, `TripCostCalculator`; V30, V33 | `rates/rate-cards`, `TripCostCard`, `ActualCostDrawer` | Rate selection and cost calculation suites | Company-scoped | Components are `BASE`, `DISTANCE`, `WEIGHT`, `VOLUME`, `PALLETS`, `MINIMUM_ADJUSTMENT`. **No maximum, stop-off, fuel surcharge, waiting time, toll, accessorial** -> **JOB 06** |
| 11 | **Carrier tendering** | PARTIAL | `TripTenderService`, `TenderStatus` with a real transition table; V31 | `TenderDrawer`, `TripTenderCard` | Tender lifecycle and transition tests | Company-scoped; one active tender enforced | Single-carrier tender. **No ranking, no waterfall, no automatic escalation** -> **JOB 07** |
| 12 | **Trip execution lifecycle** | IMPLEMENTED | `TripExecutionService`, `TripStopExecutionService`, `TripStatus` transition table; V25, V27 | `TripWorkspacePage`, `TripTimeline` | Transition-table unit tests + API integration tests | Company-scoped; atomic state transitions | None |
| 13 | **Delivery result and POD** | IMPLEMENTED | `TripDeliveryService`, `OrderDelivery`, `DeliveryResult`, `DeliveryEvidenceService`; V28, ADR-006, V36 | `DeliveryDrawer`, `DeliveryEvidenceDrawer` | Delivery result constraint and evidence suites, `OrderExecutionPropagatorTest` | Evidence behind `EvidenceStoragePort`, never a public URL | Delivery facts now **drive order status**, recomputed on every correction so the two cannot drift |
| 14 | **Tracking positions** | PARTIAL | `tracking/*`, `TrackingIntakePort`/`TrackingProviderPort` (ADR-007); V29 | `TripTrackingCard` | Sampling rule, position validation, ingestion tests | Provider scope on integration clients | Positions are stored and shown. **No ETA, no geofence, no deviation, no arrival detection** -> **JOB 10** |
| 15 | **Appointments / dock scheduling** | MISSING | No table, no type. `dock` appears only in prose | - | - | - | Location resources, calendars, slots -> **JOB 08** |
| 16 | **Freight audit and settlement** | MISSING | `TripCost` records estimated and actual cost, which is the foundation; there is **no carrier invoice, no match, no discrepancy, no approval, no export** | `ActualCostDrawer` records actual cost only | Cost calculation tests | Company-scoped | Invoice -> match -> approve -> export -> **JOB 11** |
| 17 | **Fleet resource scheduling** | MISSING | No driver availability, shift, vehicle block or maintenance table. V16 prevents vehicle double-booking on overlapping trips, which is the only constraint that exists | - | Double-booking tests | - | Availability model + Planning V2 integration -> **JOB 09** |
| 18 | **Exceptions and control tower** | PARTIAL | `TripException` (trip-scoped, typed, resolvable); `ControlTowerService` with summary, trips, stops, workload views | `control-tower` with panels | Control tower filter and view tests, exception lifecycle tests | Company-scoped | Exceptions are **trip-only**. No generic operational exception across orders, tenders, tracking or invoices; no SLA, no dedup, no assignment -> **JOB 12** |
| 19 | **Integrations (inbound)** | IMPLEMENTED | `integration/*`, 8 controllers, client credentials, scopes, idempotent inbox; V18, V20 | `settings/integrations` | Integration identity, idempotency and scope suites | Machine principals distinct from human; per-client scopes; SSRF protections | Operational surface (retry, DLQ, replay) -> **JOB 13** |
| 20 | **Outbound webhooks** | PARTIAL | `WebhookController`, `WebhookDelivery`, attempts, encrypted secrets; V35 | Subscriptions in `settings/integrations` | Webhook delivery and cipher tests | Secrets encrypted at rest, never returned | **No dead-letter state, no manual replay, no payload inspection UI** -> **JOB 13** |
| 21 | **Notifications** | IMPLEMENTED | `notification/*`; V32 | `NotificationsMenu` | Notification suites | Company- and user-scoped | None |
| 22 | **Audit trail** | IMPLEMENTED | `audit/*`, `AuditAction`; V22 | `security/audit` | Audit query and rendering tests | Company-scoped; actor type recorded | Extend to new modules as they land |
| 23 | **KPIs and reporting** | IMPLEMENTED | `KpiService` with cost, orders, shipments, service, tender, utilization, exceptions views + export | `reporting` | `ReportsPage.test.tsx`, KPI service suites | Company-scoped | Planning KPIs -> **JOB 05** |
| 24 | **Master data import** | IMPLEMENTED | 5 import controllers, `MasterDataImportBatch`; V21 | Import drawers on masters screens | Import batch suites | Company-scoped | None |
| 25 | **Observability** | PARTIAL | Correlation ID in MDC (visible in every log line), structured logging | - | - | No secrets logged | **No Micrometer counters/timers** on planning, routing, tender, integration -> **JOB 15** |

## What this run must not re-create

These already exist and are tested. Extending them is correct; rebuilding them is waste.

- The `PlanningEngine` port. JOB 05 adds an implementation beside `HEURISTIC_V1`; it does not
  restructure planning.
- `TripStatus`, `TenderStatus`, `StopExecutionStatus` and `DeliveryResult`, each with its
  transition rules in the domain and asserted twice (service + entity) with DB CHECKs beneath.
- `OrderDelivery` and the POD evidence port. JOB 02 consumes these facts; it does not re-model them.
- The integration inbox, its idempotency keys and its scope model. JOB 13 adds operations on top.
- `trip_order_assignment.whole_order` and its partial unique index, which V11 wrote specifically so
  that split allocation could arrive later without touching an applied migration.

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
