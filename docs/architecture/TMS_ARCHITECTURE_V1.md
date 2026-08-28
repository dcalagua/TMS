# TMS by EBIM - Architecture V1

Status: accepted baseline (Step 00)
Date: 2026-08-19
Scope: V1 foundation through Manual Planning

## 1. Purpose

TMS by EBIM is a transport management system covering master data (origins, zones,
destinations, frequencies, routes), fleet (carriers, vehicle types, vehicles), orders,
and manual planning of trips. It is a multi-tenant SaaS product designed for
10,000+ orders/day, 100-300 vehicles, and multiple companies operating concurrently.

The guiding principle is: **simple now + correctly separable + scalable later.**

## 2. Runtime architecture

Business operations follow exactly one path:

    React + TypeScript (frontend/tms-web)
              |
              |  HTTPS / JSON, Supabase-issued JWT in Authorization header
              v
    Java 21 + Spring Boot (backend/tms-api)
              |
              |  JDBC, pooled, application DB role
              v
    PostgreSQL on Supabase (+ PostGIS, RLS)

The only frontend-to-Supabase call path allowed in V1 is **authentication**:
the browser talks to Supabase Auth to sign in, refresh and sign out, and to obtain
the JWT it then sends to Spring Boot. The frontend does **not** read or write
business tables through the Supabase client, PostgREST, RPC, or Edge Functions in V1.

Any future exception (for example Storage-signed uploads or Realtime consumption)
requires a new ADR that states the authorization model for that path.

## 3. Component layout

    frontend/tms-web      React + TypeScript + Vite + MUI
    backend/tms-api       Java 21 + Spring Boot, Flyway migrations, OpenAPI contract
    supabase              Local Supabase config (config.toml, seed for local only)
    docs                  Architecture, ADRs, database, security, overnight reports
    scripts               Local developer/CI helper scripts

This layout is created in Step 01. It is a plain monorepo with two independently
buildable applications - not a multi-module framework and not microservices.

## 4. Ownership boundaries

### 4.1 Supabase / PostgreSQL owns

- Managed PostgreSQL platform, connection endpoints, backups.
- PostGIS capability for geospatial columns and indexes.
- Supabase Auth as the identity provider and JWT issuer (`auth.users`, JWKS endpoint).
- Row Level Security as **defense in depth**.
- Storage and Realtime **later**, only when a concrete operational use case justifies it.
- Enforcement of data invariants that PostgreSQL can guarantee cleanly
  (foreign keys, unique constraints, check constraints, not-null, exclusion).

### 4.2 Java / Spring Boot owns

- Business authorization: who may do what, in which organization and company.
- Tenant enforcement: every business query is scoped server-side.
- Master-data CRUD and use cases with validation.
- Order lifecycle.
- Planning and trip lifecycle, order-to-trip assignment.
- Capacity resolution and validation, and concurrency control on assignment.
- Audit use cases.
- Integrations and background jobs.
- Future optimization / OR-Tools integration (explicitly deferred).
- The OpenAPI contract and backend observability.

Spring Boot performs full authorization even though its database role may bypass RLS.
RLS is never the only line of defense for a business rule.

### 4.3 React owns

- Presentation, data entry, dense operational screens, client-side UX validation.
- Supabase Auth session handling and token attachment.
- UI affordances (hiding buttons) as **hints only**, never as authorization.

## 5. Migration ownership

**Flyway, under `backend/tms-api`, is the single owner of application schema migrations.**

Flyway owns:

- application tables, columns and data types;
- primary keys, foreign keys, unique and check constraints;
- indexes, including GiST indexes on PostGIS columns;
- extensions required by application tables (for example `postgis`, `pgcrypto`);
- RLS enablement and RLS policies on application tables;
- database views and any database function the application depends on;
- reference/seed data that is part of the schema contract.

Flyway does **not** own the Supabase-managed `auth` and `storage` schemas. Those stay
managed by Supabase and are never recreated or altered by application migrations.

`supabase/` in this repository holds local platform configuration only. There must be
**no** `supabase/migrations` history that duplicates application DDL. If a Supabase
migration directory is ever needed for platform-only concerns, it must be documented
and must not touch application tables.

Applied migrations are immutable. Any later change is a new versioned migration.

See `ADR-002-migration-ownership-flyway.md`.

## 6. Multi-tenancy

    organization
        |
        +-- company
              |
              +-- origin, zone, destination, frequency, route
              +-- carrier, vehicle_type, vehicle
              +-- order
              +-- planning_run, trip

Identity mapping:

    auth.users (Supabase)
        |
        v
    app_user            business profile, references the Supabase auth user id
        |
        v
    membership          (app_user, organization, company scope, role)

`auth.users` is never used as the business profile table. `app_user` and `membership`
are application tables owned by Flyway.

**Company is the operational tenant scope.** Business masters and transactions carry
`company_id`. Tables do not carry both `organization_id` and `company_id` unless a
specific, documented reason requires organization-level scope (for example
organization-wide settings or cross-company reference data).

Server-side, every request resolves: Supabase JWT -> `app_user` -> active
`membership` -> effective company scope. Repository queries are always filtered by
that resolved scope; the company id is never taken from a client-supplied value
without validating it against the caller's memberships.

See `ADR-003-multitenancy-company-scope.md`.

## 7. Security model

1. Supabase Auth issues the JWT. Spring Security validates it against the Supabase
   JWKS/issuer as a resource server.
2. Spring Boot resolves `app_user` and `membership` server-side on every request.
3. Business authorization is enforced in services and in repository queries.
4. RLS is enabled on exposed application tables as defense in depth; the `anon` role
   has no access to business tables.
5. The frontend never becomes an authorization boundary.
6. Secrets live only in untracked local env files; the repository contains
   `.env.example` placeholders only.

Detailed baseline is produced in Step 03 under `docs/security/SECURITY_BASELINE.md`.

## 8. Geospatial model direction

Origin and Destination expose `latitude` / `longitude` in APIs and forms. The database
prefers a PostGIS generated `geography`/`geometry` column plus a GiST index, so basic
CRUD in Java does not depend on complex spatial ORM mapping. Coordinate range checks
exist both as API validation and as database check constraints.

## 9. Domain direction decided now, implemented later

These are recorded so later steps do not have to retrofit the model:

- **Frequency**: a frequency header plus weekly rules and explicit exceptions - not five
  boolean columns. The V1 UI may still present Monday-Sunday toggles.
- **Route**: a master Route (company, origin, code/name, optional zone, active flag,
  ordered stops referencing destinations, optional weekly operating rules, reference
  distance/duration) is **not** a calculated Trip route. A Trip carries its own stops
  and snapshots.
- **Order to Trip**: an explicit assignment aggregate (`trip_order_assignment` plus line
  allocations), never only a `trip_id` column on the order. V1 UI may assign whole
  orders, but the model preserves future split capability.
- **Capacity**: Vehicle Type provides default capacities; Vehicle may override them. One
  backend service resolves effective capacity. Trip utilization is computed from active
  allocations; any persisted summary is updated transactionally by the mutating service.
- **Audit**: append-only data in the database, audit use cases in Java.

## 10. EWM boundary

EWM by EBIM is a **separate product**. TMS integrates with it later only through APIs,
events and explicit contracts, using external identifiers.

Hard rules:

- no shared internal database tables between TMS and EWM;
- no cross-product foreign keys (for example, Origin is never FK-bound to an EWM
  warehouse table - it carries an optional external reference code instead);
- no shared migration history;
- no direct database access from one product into the other.

## 11. Explicitly deferred

OR-Tools and route optimization, GPS/telematics, EWM integration, ERP integration,
mass upload, full driver scheduling, proof of delivery, freight settlement/costing,
live map tracking, sophisticated holiday calendars, Kafka/microservices/event sourcing,
Supabase Realtime and Storage.

Each of these requires a concrete requirement and an ADR before it enters the codebase.
