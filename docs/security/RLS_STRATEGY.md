# TMS by EBIM - Row Level Security strategy (V1)

One line: **the application schema has no HTTP surface and no privileges for anyone but the
application role; RLS is enabled everywhere, and business tables are filtered by the tenant of
the current transaction for a non-owner runtime role, so a query that forgets its company
predicate returns one tenant's rows instead of everyone's; Spring Boot remains the
authorization boundary.**

> Updated by **ADR-005**. Sections 2.3, 2.4, 3, 4 and 7 describe the current posture; the
> original V1 posture was RLS enabled with no policies at all, which left the tenant boundary
> resting solely on repository predicates because the backend connects as the schema owner.

## 1. Threat model

| Path | V1 status | Control |
|---|---|---|
| Browser -> Spring Boot -> PostgreSQL | the only business path | JWT validation, membership resolution, scoped repository queries (Step 03) |
| Browser -> Supabase Auth | allowed, authentication only | Supabase Auth |
| Browser -> Supabase Data API (PostgREST) -> business tables | **not open** | `tms` schema is not exposed; `anon`/`authenticated` hold no privilege; RLS denies |
| Browser -> Supabase RPC / Edge Functions | none exist | `edge_runtime.enabled = false` |
| Direct psql by an operator | possible by design | database credentials are an operations control, audited outside this repository |

## 2. The four controls, in order

### 2.1 The schema is not exposed

`supabase/config.toml` exposes `schemas = ["public", "graphql_public"]`. Application tables
live in `tms`, so PostgREST does not serve them at all. This is stronger than relying on a
policy: an endpoint that does not exist cannot be misconfigured.

### 2.2 No privileges for the API roles

Migration `V4__security_grants_and_rls.sql`:

- revokes everything on the schema, its tables, sequences and functions from `PUBLIC`, and
  removes the default `EXECUTE` grant PostgreSQL gives `PUBLIC` on new functions;
- revokes the same from `anon`, `authenticated` and `service_role`, plus their default
  privileges for future objects - guarded by a `pg_roles` lookup, because those roles exist
  only on a Supabase platform and the same file must apply to a plain PostgreSQL test
  container.

Those roles have no grants on a freshly created schema anyway. The revokes exist because a
platform default, a template database or a future operator grant could change that, and
because the intent should be auditable in the schema itself.

### 2.3 RLS enabled, with tenant policies for the runtime role (ADR-005)

Every application table is `ENABLE ROW LEVEL SECURITY`. For the Supabase API roles nothing
changed: they hold neither a privilege nor a policy, so RLS is still a complete deny for them.

What migration `V13` adds is a second line of defense against the backend's own queries:

- `tms_app` is a `NOLOGIN`, passwordless role with DML on the schema. It is **not** the owner,
  so policies apply to it in full. It adds no credential anywhere - it is reachable only
  through `SET ROLE` from the connection that already authenticated.
- Company-scoped business tables carry `p_tenant_company_scope`, `FOR ALL TO tms_app`, with
  `USING` **and** `WITH CHECK` on `company_id = tms.current_company_id()`. Both halves matter:
  a `USING`-only policy would allow an insert into another company and merely hide the row
  afterwards.
- `tms.current_company_id()` reads the `tms.company_id` session setting and returns NULL when
  it was never set. Every comparison against NULL is false, so an unscoped transaction reads
  and writes nothing. It fails closed.
- Identity and authorization-catalogue tables carry `p_backend_managed`, an explicit
  `USING (true)` for `tms_app`. They cannot be keyed on the company, because they are read in
  order to *decide* the company - principal resolution reads `app_user` and `membership`
  before any scope exists. Their tenant rule stays in Spring Boot (ADR-003).

This is not "allow all authenticated" theatre: the policies name a role that only exists
behind an authenticated backend connection, and `SchemaExposureIntegrationTest` fails the
build if any policy ever names another role.

### 2.4 RLS is still not FORCEd, and that is still a decision

`FORCE ROW LEVEL SECURITY` is **not** set, and the backend still connects as the owner. What
changed is that a company-scoped request no longer *stays* the owner: `TenantScopedDataSource`
issues `SET ROLE tms_app` for the work of that request and resets it before the connection
returns to the pool.

Forcing RLS on the owner was considered and rejected in ADR-005. Flyway runs as the owner, so
forcing would put every future data migration under the policies - a trap rather than a
control - and it would break the integration tests that seed business rows as the owner.
Restricting the runtime connection targets the one that serves untrusted input, and leaves
migrations and test seeds working exactly as before.

The limit is worth stating: an **unscoped** connection is not filtered. Flyway, authentication
and principal-scoped endpoints such as `/api/v1/me` have no company and keep the owner role.
Business tables are reached only through company-scoped request paths, but that is enforced by
Spring Boot, not by the database. The architecture of record still holds: *"RLS is never the
only line of defense for a business rule."*

## 3. Exposure decision per table

No table is exposed through the Data API, and no policy names `anon`, `authenticated`,
`service_role` or `PUBLIC`. What differs per table is which policy `tms_app` gets.

| Table | RLS | Policy for `tms_app` | Reason |
|---|---|---|---|
| `origin`, `zone`, `destination`, `frequency`, `route`, `route_stop`, `carrier`, `vehicle_type`, `vehicle`, `transport_order`, `planning_run`, `trip`, `trip_stop`, `trip_order_assignment` | enabled | `p_tenant_company_scope` on `company_id` | company-scoped business data: the tenant boundary this document exists for |
| `location` (V14), `location_frequency` (V15), `order_import_batch` (V17), `integration_client` and `integration_request` (V18), `shipment_outbox_event` (V20), `import_batch` (V21) | enabled | `p_tenant_company_scope` on `company_id` | same rule, applied by the migration that creates each table |
| `frequency_weekly_rule`, `frequency_exception`, `transport_order_line`, `location_role` (V14), `integration_client_scope` (V18) | enabled | `p_tenant_company_scope` through the parent | no `company_id` of their own; a copy would be denormalised state that can drift |
| `tms.organization` | enabled | `p_backend_managed` | read to resolve the caller's tenant, before a scope exists |
| `tms.company` | enabled | `p_backend_managed` | same; also the list `/api/v1/me` returns |
| `tms.app_user` | enabled | `p_backend_managed` | identity resolution: read *to decide* the company |
| `tms.membership` | enabled | `p_backend_managed` | the authorization source of truth; never client-readable, and unusable as a tenant key because it is what defines the tenant |
| `tms.membership_role` | enabled | `p_backend_managed` | same |
| `tms.role`, `tms.permission`, `tms.role_permission` | enabled | `p_backend_managed` | authorization catalogue, not tenant data |
| `tms.flyway_schema_history` | not enabled | none | Flyway-managed, no business data, no grants outside the owner |

## 4. Roles

| Role | Who | Privileges |
|---|---|---|
| Owner of schema `tms` | the backend connection and Flyway | owns the objects, exempt from unforced RLS |
| `tms_app` | the same backend connection, after `SET ROLE`, for a company-scoped request | DML on `tms`, fully subject to the tenant policies. `NOLOGIN` and passwordless: it cannot be connected to |
| `anon` | unauthenticated Supabase Data API callers | nothing |
| `authenticated` | signed-in Supabase Data API callers | nothing |
| `service_role` | Supabase administrative key | nothing on `tms`; the backend never uses this key |

**Credentials** are never in migrations: a password must not appear in a versioned SQL file,
and provisioning a connectable role is a cluster-level operations concern. For a local stack
the Supabase `postgres` superuser is used; for a managed deployment, provision a dedicated
role that owns the `tms` schema and give the backend only that role.

`tms_app` is not an exception to that rule, because it carries no credential: it is `NOLOGIN`,
has no password, and exists only to be entered with `SET ROLE` from a connection that already
authenticated. Creating it in a migration is what keeps the schema self-contained - the same
history applied to an empty database yields a working, policed schema, which is exactly what
the integration tests assert.

### The non-owner runtime role, as applied

This was written as a future hardening option; ADR-005 applied it, with one change that
matters. The role is **not** a second login: `tms_app` is `NOLOGIN` and has no password, so
nothing connects as it and no credential has to be provisioned, rotated or kept out of the
repository. The backend keeps its existing connection and reaches the role through
`SET ROLE`, which `TenantScopedDataSource` issues per company-scoped request.

Role creation is therefore back inside a migration, and `MigrationConventionTest` was narrowed
to match the real rule: a migration may create a role, but never one carrying a password and
never one that can log in.

## 5. If the Data API is ever opened (design sketch, not applied)

Should a future ADR justify exposing a table through PostgREST, the policies must be
correct on their own. The shape they would take:

```sql
-- Resolve the caller's business identity from the validated Supabase JWT.
CREATE FUNCTION tms.current_app_user_id() RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = tms, pg_temp AS $$
    SELECT u.id FROM tms.app_user u WHERE u.auth_user_id = auth.uid() AND u.active
$$;

-- A company-scoped business table is readable only inside the caller's active memberships.
CREATE POLICY p_company_scope_select ON tms.<table>
FOR SELECT TO authenticated
USING (EXISTS (
    SELECT 1 FROM tms.membership m
    WHERE m.app_user_id = tms.current_app_user_id()
      AND m.active
      AND (m.company_id = <table>.company_id
           OR (m.company_id IS NULL AND m.organization_id = (
                 SELECT c.organization_id FROM tms.company c WHERE c.id = <table>.company_id)))
));
```

Requirements before any of that is written:

1. an ADR stating why the backend path is insufficient;
2. write policies as well as read policies - a read-only policy plus an open `INSERT` is a
   hole;
3. tests that a user of company A cannot read or write company B through the Data API;
4. a review of `SECURITY DEFINER` and `search_path` on every helper function;
5. the Spring Boot authorization stays mandatory regardless.

It is not written now because `auth.uid()` does not exist outside Supabase, and a policy
that cannot be tested is not a control.

## 6. What this does not replace

RLS is defense in depth. The authorization that actually decides business outcomes is:

1. Spring Security validates the Supabase JWT (Step 03);
2. the backend resolves `app_user` and active `membership` rows server-side;
3. a client-supplied company id is validated against those memberships, never trusted;
4. every repository query filters by the resolved company scope;
5. cross-tenant access is proven impossible by tests in each vertical slice.

Frontend hiding is a UX hint and never an authorization control.

## 7. Verification

`TenantRlsIsolationIntegrationTest` proves the tenant policies actually bite, speaking SQL
directly rather than through the API - the point being that the control must hold for a query
the backend never wrote:

- the schema owner still sees every row, so Flyway and the other integration tests are
  unaffected by the policies;
- an unfiltered `SELECT` under `tms_app` returns only the scoped company's rows;
- a transaction that set no company reads nothing, rather than falling back to everything;
- `UPDATE` and `DELETE` with no company predicate cannot touch another company's row;
- an `INSERT` naming another company is refused with SQLSTATE `42501`.

`SchemaExposureIntegrationTest` runs against a disposable PostgreSQL and asserts:

- every application table has RLS enabled, exactly the expected list;
- every company-scoped table carries `p_tenant_company_scope`;
- **structurally**, every table carrying a `company_id` column carries that policy - asked of
  `pg_attribute` rather than of a hand-written constant, with `membership` the single declared
  exception (it defines the tenant, so it cannot be keyed on one). This is the assertion that
  does not go stale: a table added by a future migration that forgets its policy fails here
  without anyone remembering to update a list;
- no table is left with RLS enabled and no policy at all, which would deny every row to
  `tms_app` and break the feature on exactly the deployments where the runtime role is entered;
- no policy names any role other than `tms_app`, so the Data API stays closed;
- no table is `FORCE`d, so the owning application role keeps working by design;
- `PUBLIC` has no `USAGE` on the schema, no `SELECT` on any table, no `EXECUTE` on
  `tms.set_updated_at()`;
- after creating `anon` and `authenticated` roles (as Supabase would), neither has schema or
  table privileges, and `SET ROLE anon; SELECT ... FROM tms.organization` is refused with
  SQLSTATE `42501`;
- no application table exists in `public`, where the Data API looks.
