# ADR-005 - Tenant Row Level Security through a non-owner runtime role

- Status: Accepted
- Date: 2026-08-20
- Supersedes: the "RLS enabled with no policies" part of ADR-004 decision 4, for business
  tables only. Everything ADR-004 decides about schema placement and Data API exposure stands.

## Context

ADR-003 makes Company the operational tenant scope and puts enforcement in Spring Boot: the
`X-Company-Id` header is validated against the caller's memberships, and every repository
query filters by the resolved scope. ADR-004 enabled RLS on every table with **no policy**,
which for any non-owner role is a complete deny - the honest answer while the only access path
was the backend.

That left one real gap. The backend connects as the schema **owner**, and an owner is exempt
from RLS unless the table is `FORCE`d. So in practice the entire tenant boundary rested on
one thing: every repository query remembering its `company_id` predicate. A single missing
`WHERE` in a future query - a JPQL join, a native report, a `findAll` on a Specification that
was not composed with the scope - would leak across companies with nothing behind it to
notice. `docs/security/RLS_STRATEGY.md` said as much: *"RLS here protects against a path the
backend does not control ... not against the backend itself."*

The product is multi-company by design and heading for 10,000+ orders/day across multiple
customers. A tenant boundary with exactly one line of defense, enforced by developer
discipline on every future query, is not proportionate to that.

## Decision

Business data is filtered by PostgreSQL itself, for a role that is **not** the schema owner.

### The runtime role

Migration `V13` creates `tms_app`:

- `NOLOGIN` and without a password, so it adds no credential to any environment and cannot be
  connected to directly;
- granted DML on the `tms` schema, plus default privileges so tables added later are included;
- not the owner, therefore fully subject to RLS.

The application keeps its existing connection and credentials. `TenantScopedDataSource`
issues `SET ROLE tms_app` on a connection it hands to a company-scoped request, and resets it
before the connection returns to the pool.

### The tenant of a transaction

The company travels in the session setting `tms.company_id`, read by
`tms.current_company_id()`. Policies compare `company_id = tms.current_company_id()`.
When the setting is absent the function returns NULL, every comparison is false, and the
transaction reads and writes nothing - it fails closed.

### Which tables

- **Company-scoped business tables** (17, including the three children scoped through their
  parent) get `p_tenant_company_scope`, `FOR ALL`, with both `USING` and `WITH CHECK`. A
  `USING`-only policy would let a caller insert into another company and then simply not see
  the row: a silent write leak.
- **Identity and authorization catalogue** (`app_user`, `organization`, `company`,
  `membership`, `membership_role`, `role`, `permission`, `role_permission`) get
  `p_backend_managed`, an explicit `USING (true)` for `tms_app` only. They cannot be keyed on
  the company because they are read in order to *decide* the company: principal resolution
  reads `app_user` and `membership` before any scope exists. Their tenant rule stays in Spring
  Boot, where ADR-003 already puts it.
- Policies are granted `TO tms_app` and to nobody else. `anon`, `authenticated` and
  `service_role` still hold neither privilege nor policy, so ADR-004's closed Data API is
  unchanged.

## Alternatives rejected

**`FORCE ROW LEVEL SECURITY` on the owner.** The obvious move, and wrong here for two
concrete reasons. Flyway runs as the owner, so every future data migration would silently
fall under the policies - a trap, not a control. And 15 integration test files seed business
rows as the owner; forcing RLS would replace real assertions with "remember to set the session
variable first" ceremony. Restricting the *runtime* rather than the *owner* targets exactly
the connection that serves untrusted input.

**A separate login role for the backend.** `docs/security/RLS_STRATEGY.md` section 4 sketched
this as the hardening option. It works, but it introduces a second database credential to
provision, rotate and keep out of the repository in every environment. `SET ROLE` obtains the
same restriction with no credential at all.

**Policies keyed on `auth.uid()`,** as sketched in RLS_STRATEGY section 5. Rejected there
already and still rejected: `auth.uid()` does not exist outside Supabase, so the policy could
not be tested on the disposable PostgreSQL the integration tests use, and an untestable policy
is not a control. A session setting written by the backend is testable in plain PostgreSQL,
which is why it is the mechanism here.

## Consequences

- A repository query that forgets its company predicate now returns the scoped company's rows
  instead of every company's. The bug becomes a wrong result inside one tenant rather than a
  cross-tenant disclosure.
- **Unscoped connections keep running as the owner and are not filtered.** That is deliberate
  and is the limit of this control: Flyway, authentication and principal-scoped endpoints such
  as `/api/v1/me` have no company to be scoped to. Business tables are only reached through
  company-scoped request paths, but nothing in the database enforces that - Spring Boot still
  does, and RLS does not replace it.
- Spring Boot authorization remains mandatory and unchanged. No controller, service or
  repository was modified by this ADR; the change is one migration and one `DataSource`
  decorator.
- A new business table must carry its tenant policy in the migration that creates it.
  `SchemaExposureIntegrationTest` fails the build when a company-scoped table has none.
- A future data migration that writes business rows runs as the owner and is therefore not
  filtered. That is convenient and worth stating out loud: such a migration must set
  `company_id` correctly by itself.

## Verification

`TenantRlsIsolationIntegrationTest` proves, against a disposable PostgreSQL and speaking SQL
directly rather than through the API:

1. the schema owner still sees every row, so Flyway and the existing tests are unaffected;
2. an unfiltered `SELECT` under `tms_app` returns only the scoped company's rows;
3. a transaction with no company selected reads nothing;
4. `UPDATE` and `DELETE` cannot reach another company's row even with no company predicate;
5. an `INSERT` naming another company is refused with SQLSTATE `42501`.

`SchemaExposureIntegrationTest` additionally asserts that every company-scoped table carries
the policy, and that no policy names any role other than `tms_app`.
