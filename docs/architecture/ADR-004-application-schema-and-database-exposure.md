# ADR-004 - Application objects live in the `tms` schema, with a closed database exposure posture

- Status: Accepted
- Date: 2026-08-19
- Deciders: TMS by EBIM, Step 02 (database, tenancy and Supabase foundation)
- Related: ADR-002 (Flyway owns application DDL), ADR-003 (Organization/Company tenancy)

## Context

The first Flyway migrations had to choose where application tables live. On a Supabase
platform this is not a cosmetic choice:

- the Supabase Data API (PostgREST) serves the schemas listed in `config.toml`
  (`api.schemas`, by default `public` and `graphql_public`);
- the `public` schema also receives platform objects, extension objects and anything a
  future dashboard action creates;
- Supabase grants `anon` and `authenticated` privileges on `public` by convention, and RLS
  policies are the usual mechanism for keeping those roles honest.

TMS V1 has exactly one supported path to business data: React -> Spring Boot -> PostgreSQL
(ADR-001). The browser talks to Supabase for authentication only. So the database question
is not "which policies make the Data API safe" but "should the Data API see these tables at
all".

## Decision

### 1. Application objects live in a dedicated `tms` schema

Every Flyway-created table, index, trigger and function is in `tms`, never in `public`.
Flyway's history table lives there too (`spring.flyway.default-schema=tms`), and JPA is
configured once with `hibernate.default_schema=tms`.

`public` keeps only shared platform objects, such as the PostGIS extension.

### 2. The schema is not exposed through the Data API

`supabase/config.toml` keeps `api.schemas = ["public", "graphql_public"]`. `tms` is absent,
so PostgREST has no route to business tables. Adding `tms` to that list requires a new ADR.

### 3. No privileges for `PUBLIC` or the Supabase API roles

Migration V4 revokes everything on the schema, its tables, sequences and functions from
`PUBLIC`, and - guarded by a `pg_roles` lookup so the file also applies to a plain
PostgreSQL test container - from `anon`, `authenticated` and `service_role`, including
default privileges for future objects.

### 4. RLS is enabled on every application table, with no policies

`ALTER TABLE ... ENABLE ROW LEVEL SECURITY` on all eight baseline tables and on every table
added later, in the migration that creates it. No policy is defined: for any non-owner role
this is a complete deny, which is the truthful representation of "V1 opens no direct
database path".

`FORCE ROW LEVEL SECURITY` is not set. The backend connects as the role that owns the
schema and stays exempt, because Spring Boot - not the database - is the authorization
boundary for business rules.

## Rationale

1. **Removing a surface beats securing it.** A table PostgREST cannot route to cannot be
   exposed by a policy mistake, a forgotten `GRANT`, or a dashboard toggle.
2. **Defense in depth is still real.** If someone later exposes the schema or grants a role
   access, RLS with no policies denies by default. The failure mode is an outage, not a
   cross-tenant leak.
3. **No security theatre.** A permissive `authenticated` policy would look compliant while
   authorizing a path the product does not open, and it could not be tested honestly
   (`auth.uid()` does not exist outside Supabase).
4. **Portability.** A self-contained `tms` schema with no dependency on Supabase-managed
   objects keeps the option of running TMS on plain PostgreSQL, which is also what the
   integration tests do.
5. **Clean ownership.** `public` belongs to the platform; `tms` belongs to Flyway. Drift is
   easy to see.

## Consequences

Positive:

- business tables have no HTTP surface at all;
- the exposure decision is one line of config plus one migration, both reviewable;
- tests can assert the whole posture (`SchemaExposureIntegrationTest`);
- future spatial and business migrations inherit the placement without further thought.

Negative / accepted costs:

- every environment must configure the schema for Flyway and Hibernate; a tool run without
  `-Dflyway.schemas=tms` would create objects in the wrong place;
- ad-hoc queries need a qualified name or an adjusted `search_path`;
- the Supabase Studio table editor is less convenient for `tms` than for `public` - which is
  acceptable, since hand edits are out of process anyway (ADR-002);
- if the Data API is ever needed, real policies must be written and tested first; the design
  sketch and its preconditions are recorded in `docs/security/RLS_STRATEGY.md`.

## Compliance rules

- New tables are created in `tms` and get `ENABLE ROW LEVEL SECURITY` in the same migration.
- No migration grants privileges to `anon`, `authenticated` or `service_role`.
- No RLS policy is added without an ADR that states the access path it authorizes and the
  tests that prove tenant isolation on that path.
- `api.schemas` in `supabase/config.toml` is not extended without an ADR.
- Backend authorization remains mandatory regardless of the database posture.

## Alternatives considered

- **Tables in `public` with RLS policies for `authenticated`**: rejected - it authorizes a
  path V1 does not open, the policies could not be tested outside Supabase, and one missing
  policy on one table is a cross-tenant leak.
- **Tables in `public` with RLS enabled and no policies**: rejected - equivalent denial, but
  it leaves the tables routable by PostgREST and mixed with platform objects, so a single
  future grant re-opens them.
- **Schema per tenant**: rejected in ADR-003 for tenancy reasons; unrelated to exposure.
