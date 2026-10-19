# TMS by EBIM - Repository Instructions

## Product

This repository is TMS by EBIM. TMS is independent from EWM by EBIM and must integrate later only through APIs/events/contracts, never by sharing internal tables.

## Stack

- Database/platform: Supabase/PostgreSQL, PostGIS, RLS, Supabase Auth where applicable.
- Backend: Java 21 + Spring Boot.
- Frontend: React + TypeScript + MUI (see ADR-008).

## Mandatory architecture

Business operations flow through:

    React -> Spring Boot -> PostgreSQL/Supabase

Direct frontend Supabase usage is limited to explicitly approved cases; V1 uses it for authentication only.

## Ownership

Supabase owns platform capabilities: PostgreSQL, PostGIS, Auth, RLS defense in depth, later Storage/Realtime when justified.

Java owns business rules: authorization, tenancy, masters, orders, planning, capacity, concurrency, integrations, jobs, audit use cases and future optimization.

Flyway is the canonical owner of application schema migrations. Do not duplicate application DDL in Supabase migrations.

## Review flow

For every functional module inspect and validate:

    UI -> API client -> Controller -> Service/Use Case -> Repository -> DB -> Security -> Tests

Include RPC/Edge Function explicitly if one is ever introduced.

## Git safety

Never run destructive Git commands. Never force push. Never push unless a human explicitly requests it. Do not stage the overnight pack.

## Database safety

Never mutate a shared/remote database without explicit human authorization. Tests use Testcontainers or disposable local infrastructure. Applied migrations are immutable.

## Security

Do not trust frontend hiding as authorization. Validate Supabase JWT in Spring Security. Resolve App User and Membership server-side. Enforce organization/company ownership in services and repository queries. Use RLS as defense in depth, not as a substitute for backend authorization.

Never print secrets or read real `.env` secret files. Use `.env.example` with placeholders.

## Frontend style

Use MUI as the visual base (ADR-008). Route confirmations and critical feedback through
`confirmDialog` / `promptDialog` (the confirmation with a reason) in `src/lib/ui.tsx` - screens never build their own.
Build reusable enterprise components and responsive dense screens; prefer drawers and side
panels over large modals. Do not reintroduce Bootstrap, SweetAlert2, Tailwind or Ant because a
historical report mentions them.

## Scale target

Design sensibly for 10,000+ orders/day, 100-300 vehicles, multiple companies/warehouses and concurrent users without premature distributed-system complexity.

## Product principle

Simple now + correctly separable + scalable later.

## Repository layout

    frontend/tms-web      React + TypeScript + Vite + MUI
    backend/tms-api       Java 21 + Spring Boot + Flyway (canonical application DDL)
    supabase              Local Supabase platform config; no duplicate application DDL
    docs                  Architecture, ADRs, database, security, overnight reports
    scripts               Local developer/CI helper scripts

## Product roadmap

- `docs/roadmap/ROADMAP.md` - the product roadmap (in Spanish, for the business owner): where the TMS
  stands today, what blocks production (H0), the next 4-6 weeks (H1), the next quarter (H2), vision,
  explicit out-of-scope items (no issuing of electronic documents, no moving money) and the EWM <-> TMS
  dependency table shared verbatim with the EWM roadmap. Supersedes `docs/product/ROADMAP_NEXT.md` as
  the roadmap. Code and ADRs stay in English.

## Architecture references

Authoritative documents live under `docs/architecture/`:

- `TMS_ARCHITECTURE_V1.md` - the V1 architecture of record.
- `OWNERSHIP_MATRIX.md` - per-concern ownership between React, Java and Supabase.
- `ADR-001-layered-architecture.md` - React -> Spring Boot -> PostgreSQL decision.
- `ADR-002-migration-ownership-flyway.md` - Flyway as the single application-schema migration owner.
- `ADR-003-multitenancy-company-scope.md` - Organization/Company tenancy and Company scoping.
- `ADR-004-application-schema-and-database-exposure.md` - application objects live in the
  `tms` schema; the Supabase Data API does not see them; RLS is enabled with no policies.
- `ADR-005-tenant-rls-runtime-role.md` - business tables are filtered by the tenant of the
  current transaction for the non-owner `tms_app` role, so a query missing its company
  predicate stops being a cross-tenant leak. Supersedes ADR-004's "no policies" for business
  tables only.
- `ADR-006-evidence-object-storage-port.md` - proof-of-delivery artefacts go to a private object
  store behind `EvidenceStoragePort`, never into a column and never behind a public URL. Disabled
  by default; a local-volume implementation ships for deployments that want it, and Supabase
  Storage becomes one more implementation with no change to any caller.
- `ADR-011-stop-eta-and-geofence-observation.md` - a stop's arrival estimate is computed from the
  routing port, the service time and the window, stamped with the provenance of its weakest leg,
  and absent wherever a leg could not be measured. Geofences inform and move no lifecycle;
  automatic arrival detection stays deferred. Supersedes in part V27's refusal of per-stop planned
  times.
- `ADR-007-tracking-provider-port.md` - vehicle tracking is a normalised contract behind two ports
  (`TrackingIntakePort` for feeds that push, `TrackingProviderPort` for those that poll), with no
  vendor adapter in V1. Positions inform people and never move a lifecycle, so losing them costs a
  map and no business fact.
- `ADR-008-frontend-design-system-mui.md` - MUI is the frontend design system of record. The
  earlier Bootstrap + SweetAlert2 rule is withdrawn; the frontend has carried neither dependency
  for some time and 91 source files import MUI.
- `ADR-010-routing-provider-port.md` - distance and travel time behind `RoutingPort`, with a
  company-scoped cache and a local geodesic estimator that is the whole of routing when no vendor
  is configured. No vendor adapter, following ADR-007. Routing never fails a decision, and an
  estimate stays visibly an estimate even after it has been cached.
- `ADR-009-order-execution-lifecycle.md` - the order lifecycle carries the execution states
  (`IN_EXECUTION`, `DELIVERED`, `PARTIALLY_DELIVERED`, `DELIVERY_FAILED`) beside the derived
  `OrderFulfillmentStatus`, which is unchanged. Its status is recomputed from the delivery rows in
  the same transaction as every change to them, so the two cannot drift; a failed delivery becomes
  reopenable for a second attempt.
- `ADR-012-masteradmin-platform-provisioning.md` - EBIM MasterAdmin creates tenants through
  `/internal/platform-provisioning` (GENERIC contract v1): its own security chain, ES256 public key,
  off by default, one transaction, administrator PREPROVISIONED (no Auth account is created).
  Contract: `docs/platform-provisioning/MASTERADMIN_GENERIC_CONTRACT.md`.
- `ADR-013-dispatch-source-and-external-dispatch-confirmation.md` - per-company dispatch mode
  (`MANUAL` default, `EXTERNAL_REQUIRED`, `HYBRID`); a warehouse system's dispatch (SLS) is stored
  whole, reconciled against the plan without overwriting it, and dispatches the trip only as the
  mode allows, attributed to its credential; override is `planning.trip:dispatch-override`. No
  warehouse state enters `TripStatus`. Implemented with V52/V53.
  Contract: `docs/integrations/WAREHOUSE_EXECUTION_V1.md`.
- `ADR-014-scheduling-and-release-for-planning.md` - `READY_FOR_PLANNING` is "released";
  `service_date` is the scheduled dispatch date; eligibility (`ELIGIBLE`/`WARNING`/`BLOCKED`) is
  derived from route resolution, destination and route frequencies, lead time and cutoff, and holds
  (a separate table). No new order state, no scheduler, no PlanningBatch. An origin with no active
  route is `NOT_CONFIGURED` (informative, never blocking). Implemented with V54.
- `ADR-018-carrier-document-delivery-tracking.md` - carrier portal and document delivery tracking.
  A carrier user is a membership with `carrier_id` holding only `CARRIER` roles (`CARRIER_OPERATOR`);
  they see the trips assigned to their carrier once the warehouse dispatch (ADR-013) arrives, in
  Java and through restrictive RLS policies keyed on `tms.current_carrier_id()`. Each document on a
  trip is tracked `PENDING` -> `DELIVERED` (photo mandatory) / `REJECTED` / `NOT_DELIVERED` (reason),
  every portal write carries an `Idempotency-Key`, and the office confirms the signed copy's return.
  Nothing moves an order or a trip. Supersedes ADR-016 and ADR-015 section 5. Implemented with V57.
- `ADR-019-carrier-offline-capture-and-shipment-tracking.md` - the carrier portal works without
  signal: a hand-written service worker caches the shell (never `/api`), trips are kept in IndexedDB
  per user, and reports (with photos) queue in an outbox replayed with their `Idempotency-Key`.
  Plus `GET /api/v1/monitoring/shipment-tracking`: the shipments the warehouse actually dispatched,
  with stops, documents and a map; not a live fleet map. Amends ADR-018 section 7.
- `ADR-020-evidence-in-supabase-storage.md` - evidence (POD photos, carrier document photos and
  signatures) may live in a private Supabase Storage bucket (`tms-evidence`, no policies), written and
  read by the backend only with the `service_role` key, mode `SUPABASE` of `EvidenceStoragePort`.
  Same keys as the volume store, no URLs. Lifts the Storage deferral for evidence only.
- `ADR-017-commercial-entitlements.md` - MasterAdmin pushes a versioned `ebim.entitlements/v1`
  snapshot to `/internal/platform-provisioning/tenants/{id}/entitlements` (scopes
  `tms:entitlements:write|read`); TMS stores it durably (V56) and enforces only `appActive`, from
  the local copy, through a `CommercialAccessGate` separate from RBAC. No permission is a paid
  capability; the sellable registry is empty. Mode seeded SHADOW. Numbered 017 because 013-016 are
  taken on unmerged branches. Contract: `docs/platform-provisioning/ENTITLEMENTS.md`.
- `ADR-021-operational-tenant-lifecycle.md` - MasterAdmin suspends/reactivates a tenant through
  `PUT|GET /internal/platform-provisioning/tenants/{id}/lifecycle` (`lifecycle.v1`, scopes
  `tms:lifecycle:write|read`, V58). While `SUSPENDED`, `TenantSuspensionFilter` (user and partner
  chains) refuses every company-scoped write with `423 tenant-suspended`; reads, login, `/me` and the
  MasterAdmin channel stay open; data is never touched. Separate from commercial access (D-14) and
  from RBAC. Contract: `docs/platform-provisioning/LIFECYCLE.md`.
- `ADR-022-material-physical-profile-and-load-geometry.md` - the warehouse owns a material's physical
  profile (`material_profile`, V59, written only through the integration API); an order line takes a
  snapshot of it unless the ERP sent its own block; stackable and nestable goods are planned by
  effective volume, floor area and stack height (V62), and rates may charge per loading metre (V63).
- `ADR-023-freight-accruals-and-payables.md` - freight accruals with a GL mapping, consumed on
  approval (V65), and the payable's payment status, due dates and payments (V67). TMS records what was
  paid and moves no money (D-01).
- `ADR-024-peru-tax-and-carrier-documents.md` - settlement hardening (charge codes, match basis,
  tolerances, V64) and Peruvian taxes: IGV breakdown, SPOT detraction, retention, CPE numbering,
  credit/debit notes and penalties (V66); percentages are company configuration, never code. Part 2
  superseded in part by ADR-027.
- `ADR-025-warehouse-sync-v1-1.md` - warehouse contract v1.1: material profiles, plan and POD to the
  warehouse, order cancellation both ways (each side holds or alerts, neither cancels by itself),
  reconciliation. Section 7 (dock appointments) is only proposed. Contract:
  `docs/integrations/WAREHOUSE_EXECUTION_V1.md` §7.
- `ADR-026-cost-to-serve-allocation.md` - a trip's cost allocated to its orders on a configurable
  basis, versioned, reported by customer, destination and route (V68). No margin: there is no sales
  tariff.
- `ADR-027-tax-regimes-per-country.md` - taxes of received carrier documents by the company's fiscal
  country (`company_settings.fiscal_country`, else `default_country`): Peru, Ecuador and a generic
  regime for any other country. VAT, document codes, number format, tax-id check and withholdings as a
  list (`carrier_invoice_withholding`); every rate is `tms.finance.tax.<country>.*` overridable per
  company (`settlement.tax:manage`). TMS receives and checks shape; it issues nothing to SRI/SUNAT.
  Supersedes ADR-024 part 2 where they conflict (V65-V67 rewritten in place).
- `ADR-028-logistics-documents-received-international.md` - logistics documents are **received, never
  issued**: TMS does not generate, sign, send or validate invoices or remission guides with the SRI or
  SUNAT. The catalogue is international (V69): generic `REMISSION_GUIDE` (GRE_* stay as Peruvian
  aliases), nullable fiscal columns (country, authority code, clave de acceso, transport dates, carrier,
  plate, origin) and `logistics_document_recipient`. Only shape is validated (EC: 3-3-9 number,
  49-digit clave). Amends ADR-015 decision 1. Contract: `docs/integrations/INBOUND_API_V1.md` §15.

Database and security detail lives in `docs/database/DATA_MODEL.md`,
`docs/database/MIGRATION_STRATEGY.md` and `docs/security/RLS_STRATEGY.md`.

Read these before changing schema, security or module boundaries. If an implementation must deviate, add a new ADR instead of silently diverging.

## Local environment notes

- Maven is not installed globally; use the Maven wrapper (`./mvnw`) committed with the backend.
- `JAVA_HOME` is not exported in the shell profile; Java 21 is on `PATH`. Set `JAVA_HOME` if a tool requires it.
- The Docker daemon may be stopped. Testcontainers-based integration tests require Docker Desktop to be running; when it is unavailable, document it as an environment blocker instead of claiming tests passed.

## Deferred by decision (do not introduce early)

OR-Tools/route optimization, ERP integration, Kafka/microservices/event sourcing, Supabase Realtime, Storage (except evidence, ADR-020), and live fleet map tracking. Add them only when a concrete requirement and an ADR justify them.

EWM integration has left this list: v1 was merged into `dev` on 2026-09-28 (PR #13) under ADR-013
and `docs/integrations/WAREHOUSE_EXECUTION_V1.md` - signed webhook + plan pull towards the warehouse,
`dispatch-confirmations` and `warehouse-milestones` back. It stays contract-only: no shared table, no
foreign key, no warehouse state in `TripStatus`. Extending it needs the contract doc updated, not a
new ADR, unless a principle of ADR-013 changes.

A map is not live tracking: `/shipment-tracking` (ADR-019) draws on Leaflet the shipments the
warehouse actually dispatched, with their stops and documents, and the trip screens draw stored
positions. A live fleet map that follows vehicles in real time is what stays deferred.

GPS/telematics has left this list under ADR-007, and only as far as the ADR goes: TMS owns a
normalised position contract, its storage and its sampling policy, and ships **no vendor adapter**.
Writing one against a specific telematics provider still needs a concrete customer requirement.

Stop ETA has left this list under ADR-011, because the objection to it expired rather than being
overruled: V27 refused per-stop planned times on the grounds that "there is nothing to put in
them", and V38/ADR-010 (per-leg driving time), V14 (service time) and V11 (service windows) have
since supplied every term. TMS computes an arrival estimate on request, stamps it with the
provenance of the weakest leg behind it, and leaves a visible gap wherever a leg could not be
measured. Route optimisation - choosing the sequence rather than scheduling it - stays deferred.

Geofencing is a circle on a location and nothing more (ADR-011). **Automatic arrival detection
remains deferred**, and ADR-007's rule is not weakened by the circle existing: a position informs a
person and never moves a lifecycle. `actual_arrival_at` is written by whoever arrived.
