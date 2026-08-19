# TMS by EBIM - Row Level Security strategy (V1)

One line: **the application schema has no HTTP surface and no privileges for anyone but the
application role; RLS is enabled everywhere with no policy so that any accidental exposure
denies instead of leaks; Spring Boot remains the authorization boundary.**

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

### 2.3 RLS enabled, deliberately without policies

Every application table is `ENABLE ROW LEVEL SECURITY`. No policy is defined.

For any role that is not the table owner this is a complete deny - which is the correct and
honest answer while the only supported access path is the backend. Adding a permissive
"allow all authenticated" policy would claim an access path V1 does not open, and would be
security theatre. `MigrationConventionTest` and `SchemaExposureIntegrationTest` fail the
build if a policy appears without this document changing.

### 2.4 RLS is not FORCEd, and that is a decision

`FORCE ROW LEVEL SECURITY` is **not** set. The backend connects as the role that owns these
tables, and an owner is exempt from RLS unless it is forced. Forcing it with no policies
would lock out the application.

That is consistent with the architecture of record (section 4.2): *"Spring Boot performs
full authorization even though its database role may bypass RLS. RLS is never the only line
of defense for a business rule."* RLS here protects against a path the backend does not
control - a misconfigured Data API, a stray `authenticated` grant, a future exposure - not
against the backend itself.

## 3. Exposure decision per table

| Table | Exposed through the Data API | RLS | Policies | Reason |
|---|---|---|---|---|
| `tms.organization` | no | enabled | none | tenant data, backend-only |
| `tms.company` | no | enabled | none | tenant data, backend-only |
| `tms.app_user` | no | enabled | none | identity/profile data, backend-only |
| `tms.membership` | no | enabled | none | the authorization source of truth; must never be client-readable |
| `tms.membership_role` | no | enabled | none | same |
| `tms.role` | no | enabled | none | authorization catalogue; served through the API when a screen needs it |
| `tms.permission` | no | enabled | none | same |
| `tms.role_permission` | no | enabled | none | same |
| `tms.flyway_schema_history` | no | not enabled | none | Flyway-managed, no business data, no grants outside the owner |

## 4. Roles

| Role | Who | Privileges |
|---|---|---|
| Owner of schema `tms` | the backend connection and Flyway | owns the objects, exempt from unforced RLS |
| `anon` | unauthenticated Supabase Data API callers | nothing |
| `authenticated` | signed-in Supabase Data API callers | nothing |
| `service_role` | Supabase administrative key | nothing on `tms`; the backend never uses this key |

Role creation and passwords are **not** in migrations: they are cluster-level operations
concerns, and a credential must never appear in a versioned SQL file. For a local stack the
Supabase `postgres` superuser is used; for a managed deployment, provision a dedicated role
that owns the `tms` schema and give the backend only that role.

### Hardening option: a non-owner runtime role

If the backend must run as a role that does *not* own the schema, then a new migration has
to grant it privileges and either mark it `BYPASSRLS` or add policies for it. Until such a
migration exists, do not create that role and point the backend at it - it would read zero
rows, which fails closed but breaks the application.

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

`SchemaExposureIntegrationTest` runs against a disposable PostgreSQL and asserts:

- every application table has RLS enabled (8 tables, exactly the expected list);
- no policy exists in the `tms` schema;
- no table is `FORCE`d, so the owning application role keeps working by design;
- `PUBLIC` has no `USAGE` on the schema, no `SELECT` on any table, no `EXECUTE` on
  `tms.set_updated_at()`;
- after creating `anon` and `authenticated` roles (as Supabase would), neither has schema or
  table privileges, and `SET ROLE anon; SELECT ... FROM tms.organization` is refused with
  SQLSTATE `42501`;
- no application table exists in `public`, where the Data API looks.
