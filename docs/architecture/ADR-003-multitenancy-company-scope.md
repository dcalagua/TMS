# ADR-003 - Multi-tenancy: Organization/Company hierarchy with Company as the operational scope

- Status: Accepted
- Date: 2026-08-19
- Deciders: TMS by EBIM architecture baseline (Step 00)

## Context

TMS is a SaaS product serving multiple customers. A customer (an Organization) may
operate several legal entities or business units (Companies), each with its own origins,
destinations, routes, fleet, orders and trips. Users may belong to more than one company.

Tenancy has to be decided before any business CRUD is written, because retrofitting a
global model into a scoped one touches every table, query, endpoint and test.

Options for physical isolation were: database-per-tenant, schema-per-tenant, or a shared
schema with a tenant discriminator column.

## Decision

### Tenant hierarchy

    organization
        |
        +-- company
              |
              +-- operational business masters and transactions

- **Organization** is the top-level customer/tenant grouping.
- **Company** is the **operational tenant scope**. Business masters (origin, zone,
  destination, frequency, route, carrier, vehicle_type, vehicle) and transactions
  (order, planning_run, trip, assignments) carry `company_id`.
- A table carries `organization_id` **instead of** `company_id` only when the data is
  genuinely organization-level (for example organization settings or org-wide reference
  data). Tables do not carry both without a documented reason recorded in this ADR's
  successor or in the migration itself.

### Identity mapping

    auth.users (Supabase Auth)
        |
        v
    app_user        business profile; holds the Supabase auth user id
        |
        v
    membership      (app_user, organization, company scope, role, active)

- `auth.users` is **never** used as the business profile table. It is Supabase-managed
  and must not be altered by Flyway.
- `app_user` and `membership` are application tables owned by Flyway.
- `membership` is the source of truth for which companies a user may act in and with
  which role.

### Physical isolation

A **single shared schema with `company_id` as the tenant discriminator**, plus
foreign keys, indexes leading with the scope column, and RLS as defense in depth.
Not database-per-tenant, not schema-per-tenant.

### Enforcement

1. Spring Security validates the Supabase JWT.
2. The backend resolves `auth` user id -> `app_user` -> active `membership` rows.
3. The request's effective company scope is derived from those memberships. A
   client-supplied company id is **validated against** the caller's memberships and is
   never trusted on its own.
4. Every repository query for business data filters by the resolved company scope.
   Cross-company reads and writes are impossible through the normal query path.
5. RLS on application tables reinforces the same rule at the database level.
6. Tenant isolation is covered by integration tests: a user from company A must not
   read, update or delete company B data through any endpoint.

## Rationale

1. Company matches the real operational boundary: a planner plans one company's fleet
   against that company's orders.
2. A shared schema keeps migrations, connection pooling and operations simple at the
   target scale (10,000+ orders/day, hundreds of vehicles). Database-per-tenant would
   multiply migration and connection cost with no benefit at this size.
3. A single discriminator column keeps queries and indexes predictable; a two-column
   (`organization_id` + `company_id`) discriminator on every table adds redundancy and
   invites inconsistency between the two values.
4. Resolving scope from `membership` server-side removes the client from the trust path.
5. Keeping `app_user` separate from `auth.users` lets the business profile evolve
   (name, status, preferences, deactivation) without touching a Supabase-managed table
   and keeps the product portable off Supabase Auth if that ever becomes necessary.

## Consequences

Positive:

- One consistent scoping rule across every module, decided before CRUD is written.
- Simple operations: one database, one migration history, one pool.
- Isolation is testable with ordinary integration tests.

Negative / accepted costs:

- A missing `company_id` filter is a cross-tenant leak. Mitigated by: a shared scoped
  base repository/specification, mandatory tenant tests per vertical slice, and RLS as
  a second line of defense.
- "Noisy neighbour" effects are shared-infrastructure effects; acceptable at this scale
  and addressable later with partitioning or read replicas if data volume requires it.
- Organization-level reporting across companies needs an explicit, separately
  authorized use case rather than falling out of the model for free.

## Compliance rules

- Every new business table declares its scope column in the same migration that creates it.
- Every new repository query for business data is scoped, or documents why it is not.
- Every vertical slice ships a cross-tenant isolation test.
- `auth.users` is referenced by id only; it is never extended or altered.
- Company ids arriving from the client are always validated against membership.

## Alternatives considered

- **Database-per-tenant**: rejected - strongest isolation, but migration and connection
  management cost is disproportionate at this scale.
- **Schema-per-tenant**: rejected - similar cost, plus awkward cross-tenant admin
  queries and complex Flyway orchestration.
- **Organization-only scope**: rejected - does not match how customers with several
  legal entities or business units actually operate their transport.
- **Both `organization_id` and `company_id` on every table**: rejected - redundant,
  and creates the possibility of an inconsistent pair.
