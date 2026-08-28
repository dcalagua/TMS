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
`confirmDialog` / `confirmWithReason` in `src/lib/ui.tsx` - screens never build their own.
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

Database and security detail lives in `docs/database/DATA_MODEL.md`,
`docs/database/MIGRATION_STRATEGY.md` and `docs/security/RLS_STRATEGY.md`.

Read these before changing schema, security or module boundaries. If an implementation must deviate, add a new ADR instead of silently diverging.

## Local environment notes

- Maven is not installed globally; use the Maven wrapper (`./mvnw`) committed with the backend.
- `JAVA_HOME` is not exported in the shell profile; Java 21 is on `PATH`. Set `JAVA_HOME` if a tool requires it.
- The Docker daemon may be stopped. Testcontainers-based integration tests require Docker Desktop to be running; when it is unavailable, document it as an environment blocker instead of claiming tests passed.

## Deferred by decision (do not introduce early)

OR-Tools/route optimization, EWM integration, ERP integration, Kafka/microservices/event sourcing, Supabase Realtime, Storage, and live map tracking. Add them only when a concrete requirement and an ADR justify them.

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
