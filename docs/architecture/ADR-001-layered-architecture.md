# ADR-001 - Layered architecture: React -> Spring Boot -> PostgreSQL/Supabase

- Status: Accepted
- Date: 2026-08-19
- Deciders: TMS by EBIM architecture baseline (Step 00)

## Context

TMS needs multi-tenant business rules: tenant isolation, role-based authorization,
order lifecycle, trip planning, capacity validation and concurrency control on
assignment. It targets 10,000+ orders/day, 100-300 vehicles and multiple companies.

Supabase provides managed PostgreSQL, PostGIS, Auth and RLS, and can expose tables
directly to a browser through PostgREST. A Supabase-only ("BaaS") architecture would
let React read and write business tables directly, pushing authorization into RLS
policies and business logic into database functions/triggers/Edge Functions.

## Decision

All business operations flow through:

    React + TypeScript  ->  Java 21 + Spring Boot  ->  PostgreSQL on Supabase

- The frontend calls the Spring Boot REST API for every business read and write.
- The frontend's only direct Supabase usage in V1 is **Supabase Auth**: sign in,
  refresh, sign out, and obtaining the JWT it attaches to backend requests.
- Spring Boot validates the Supabase JWT, resolves `app_user` and `membership`
  server-side, and enforces business authorization and tenant scope in services and
  repository queries.
- RLS is enabled on exposed application tables as defense in depth, not as the
  primary authorization mechanism.
- PostgreSQL enforces invariants it can guarantee cleanly (FKs, unique, check).

We explicitly reject a Supabase-only architecture for business data in V1.

## Rationale

1. **Business rules need a real service layer.** Capacity resolution, order-to-trip
   assignment, and concurrency control are multi-row transactional invariants. They are
   expressible in a service with a transaction far more clearly than in RLS policies or
   large PL/pgSQL triggers.
2. **Authorization must be testable and debuggable.** Java authorization can be unit and
   integration tested with normal tooling. Deeply nested RLS policy logic is hard to test,
   hard to review and hard to reason about as roles multiply.
3. **The client is untrusted.** Direct table access moves the trust boundary into the
   browser and makes every future policy mistake a data breach.
4. **Contract stability.** A versioned OpenAPI contract lets the schema evolve without
   breaking the frontend; direct table access couples the UI to physical columns.
5. **Future work needs a backend anyway.** Background jobs, integrations, audit use
   cases and the deferred OR-Tools optimizer have no home in a BaaS-only design.
6. **Operational control.** Rate limiting, pagination guarantees, N+1 avoidance,
   caching and observability belong in a service we control.

## Consequences

Positive:

- One enforcement point for authorization and tenancy.
- Business logic is version-controlled, reviewable and testable Java.
- The database can evolve behind a stable API contract.
- Room for jobs, integrations and optimization without re-architecting.

Negative / accepted costs:

- More moving parts than a BaaS-only stack; a backend must be deployed and operated.
- Some CRUD is "boilerplate" that PostgREST would have generated for free.
- Two authorization expressions exist (Java primary, RLS defense in depth) and must be
  kept consistent; this is accepted deliberately as defense in depth.

Mitigations:

- Keep the backend a single bounded Spring Boot application. No microservices, no
  Kafka, no event sourcing in V1.
- Keep CRUD thin and consistent; reserve elaboration for planning and capacity.

## Compliance rules

- No React code imports the Supabase client for business tables, PostgREST queries,
  RPC or Edge Functions in V1.
- No business rule exists only in the frontend.
- No business rule exists only in RLS.
- Any exception requires a new ADR that states the authorization model for that path.

## Alternatives considered

- **Supabase-only / BaaS**: rejected - authorization and multi-row invariants would
  live in RLS and database functions, which does not meet testability, reviewability or
  concurrency requirements.
- **Microservices from day one**: rejected - premature distributed complexity for a
  single product with one team; splitting later is possible because module boundaries
  are kept explicit.
- **Backend-for-frontend proxying straight to PostgREST**: rejected - a pass-through
  adds a hop without moving the authorization boundary anywhere useful.
