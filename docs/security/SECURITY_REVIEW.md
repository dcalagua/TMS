# Security review - TMS by EBIM

Date: 2026-08-19
Scope: the complete implementation after Step 11 (IAM, masterdata, fleet, orders, manual
planning), reviewed along the chain `UI -> API client -> Controller -> Service -> Repository ->
DB -> Security -> Tests`.

This is a review record, not a design document. The *design* lives in
[`SECURITY_BASELINE.md`](SECURITY_BASELINE.md), [`AUTHORIZATION_MODEL.md`](AUTHORIZATION_MODEL.md)
and [`RLS_STRATEGY.md`](RLS_STRATEGY.md); this file records what was checked, what was found, what
was fixed and what remains.

Severity: **P0** exploitable now / **P1** exploitable under a plausible condition or a correctness
defect with a security-adjacent blast radius / **P2** hardening with real value / **P3** noted, no
action justified yet.

## 1. Verdict

No P0 finding. No cross-tenant read or write path was found, and no committed secret exists in the
repository. Two P1 findings were fixed (both in planning; both concurrency, not access control) and
one P2 tenant-isolation hardening was applied. The remaining items are documented in section 6 and
none of them is an authorization gap.

## 2. What was checked, and how

| Area | Method | Result |
|---|---|---|
| JWT validation and production fail-safe | read `SupabaseJwtDecoders`, `SupabaseJwtConfig`; `SupabaseJwtDecodersTest` (14 cases) | Pass |
| No service-role key / DB password in the frontend | `git grep` over the whole tree for `service_role`, `eyJhbGciOi`, `.env` files | Pass |
| No committed secrets | `git ls-files` for env/secret/key names; `MigrationConventionTest`, `LocalSeedIntegrationTest` assert it in CI | Pass |
| Company/tenant isolation on every query and mutation | read every repository and every service; see section 3.3 | 1 x P2, fixed |
| IDOR through UUID guessing | every finder is `findByIdAndCompanyId`; `ApiSecurityTest` + each module's API test assert 404 across companies | Pass |
| Permission check per endpoint | enumerated all 62 handler methods against `@PreAuthorize` | Pass (2 documented exceptions) |
| Inactive membership/company/organization/role | read `JdbcIdentityRepository.COMPANY_PERMISSIONS_SQL`; `IdentityResolutionIntegrationTest` | Pass |
| RLS / grants vs documented strategy | catalog diff of `CREATE TABLE` vs `ENABLE ROW LEVEL SECURITY`; `SchemaExposureIntegrationTest` | Pass - 25/25 tables |
| CORS | read `SecurityConfig.corsConfigurationSource` | Pass |
| Error responses not leaking internals | read `ApiExceptionHandler`, `application.yml` `server.error.*` | 1 x P1, fixed |
| Mass assignment / entity binding | grepped every `*Request` record for `companyId`, `id`, `status`, `createdBy`, `active` | Pass - none present |
| Audit actor spoofing | read `AuditActorProvider`; actor is never a method parameter | Pass |
| SQL injection / native queries | 2 native queries, both `nextval` literals; no string-concatenated SQL anywhere | Pass |
| Actuator / OpenAPI exposure | read `application*.yml`, `SecurityConfig`; `DocumentationExposureTest` | Pass |

## 3. Findings

### 3.1 P1 - Inconsistent row-lock ordering between planning operations (fixed)

**`PlanningRunService.confirm` / `.cancel` vs `TripService.moveOrder`.**

`TripRepository.findByIdAndCompanyIdForUpdate` is the serialization point of the planning module,
and its own contract says callers that take more than one trip lock must take them in a
deterministic order. `TripService.moveOrder` obeys it and sorts by trip id. `confirm` and `cancel`
walked `findByPlanningRunIdOrderByTripNumberAsc` and locked in **trip-number** order instead.

Two different orderings over the same rows is the ABBA deadlock. A planner confirming a run while
a colleague moved an order between two of its trips could produce:

| | confirm | move |
|---|---|---|
| t1 | locks trip #1 (id `b…`) | locks trip #2 (id `a…`) |
| t2 | waits for trip #2 | waits for trip #1 |

PostgreSQL breaks the cycle by aborting one transaction, which reached the caller as **HTTP 500**
with a lost plan operation. Not an access-control defect, but a correctness defect on the module's
one advertised concurrency invariant, on the screen a planner keeps open all day.

**Fix.** `PlanningRunService.lockTrips` takes every trip lock in ascending trip-id order - the same
order `moveOrder` uses - and returns the locked entities by id, so both methods still *iterate* in
trip-number order and their user-facing messages are unchanged. Behaviour is otherwise identical.

**Regression test.** `PlanningApiIntegrationTest.confirmDoesNotDeadlockAgainstAConcurrentMove`
fires a confirm and a move at the same barrier and asserts neither ever answers 5xx. A 409 or 404
from the loser is legitimate and accepted.

### 3.2 P1 - Concurrency and constraint failures surfaced as HTTP 500 (fixed)

`ApiExceptionHandler` had no handler for `DataIntegrityViolationException` or
`PessimisticLockingFailureException`, so both fell through to the catch-all and were answered as
`internal-error` (500).

Two consequences, one operational and one security-adjacent:

- a client had no way to tell "retry, someone beat you to it" from "the server is broken", so the
  correct client behaviour (reload and retry) was unreachable;
- a 500 is the response an operator escalates. Turning an ordinary write race into an incident is
  how genuine incidents get lost in the noise.

Every service already catches these around its *own* `saveAndFlush` and rethrows a specific
`ConflictException`; what was missing was the backstop for a violation that only surfaces at the
transaction's own commit-time flush.

**Fix.** Both are now mapped to `409 conflict`. The detail stays generic - a constraint name and
the offending values are schema information a caller has no business reading - and the full
exception goes to the log at WARN with the correlation id. The specific per-service messages still
win, because they are caught first.

### 3.3 P2 - Batched cross-module lookups filtered the tenant in Java, not in SQL (fixed)

Four `findAllInCompany` implementations - `OriginLookupAdapter`, `DestinationLookupAdapter`,
`OrderPlanningService` and `VehicleLookupService` (vehicles, types and carriers) - called
`findAllById(ids)` and then discarded rows of other companies with a Java `filter`.

The result was correct: no other tenant's data could reach a response. But the *query* was not
tenant-scoped, which is a real weakening of defence in depth. It meant another tenant's rows were
loaded into the persistence context on every batched lookup, one refactor away from being used;
and it made those five call sites the only ones in the codebase that did not honour the rule every
repository's own Javadoc states - "every finder is scoped by `companyId` - no exceptions".

**Fix.** Six repositories gained `findByIdInAndCompanyId(Collection<UUID>, UUID)` and all four
adapters now use it. The company predicate is in the SQL; a row of another tenant is never read.
Same number of queries, same results, no API change.

### 3.4 Verified, no action - the checks that passed

**JWT.** Issuer and JWKS URI are mandatory in every profile; there is no configuration path that
starts the API with verification disabled and no setting that accepts a shared signing secret. In
`prod`/`production`/`staging` both URIs must be `https` and must not resolve to a loopback address,
so a development profile that reaches a deployment fails at startup rather than trusting a local
key server. Only RS256/ES256 are accepted. Expiry (bounded skew ≤ 5 min), issuer, audience and a
non-blank subject are all validated - a merely well-signed token is not accepted.

**Secrets.** The only tracked env files are `backend/tms-api/.env.example` and
`frontend/tms-web/.env.example`, both placeholders. `frontend/src/shared/config/env.ts` falls back
to `local-development-anon-key-placeholder`, not to a real key. The backend has no service-role key
setting at all - it needs only the public JWKS to verify tokens.

**Tenancy.** The company is never taken from the request body. `CompanyScopeFilter` resolves the
`X-Company-Id` header against the principal's membership snapshot *before* any controller runs, and
a company the caller holds no active membership in is refused there with a logged warning carrying
both ids. Controllers receive a `CompanyScope`, never a `UUID companyId`; `PageQuery` deliberately
has no tenant parameter. Below that, every table carries `company_id`, every finder is scoped by
it, and `orders`/`trip_order_assignment` add composite foreign keys `(id, company_id)` so the
database itself refuses a cross-company reference.

**IDOR.** Guessing another company's UUID yields 404, not 403 - the resource is not revealed to
exist. This is asserted per module (`OrderApiIntegrationTest`, `PlanningApiIntegrationTest`,
`FleetApiIntegrationTest`, the masterdata tests) with a real second company, not mocked.

**Permissions.** All 62 handler methods were enumerated. Every business endpoint carries
`@PreAuthorize` with a `resource:action` authority, and the authorities in the security context are
the permissions of the *selected* company and of no other. The two endpoints without one are
deliberate and correct: `GET /api/v1/me` is principal-scoped and returns only the caller's own
profile and company list, and `GET /api/v1/system/info` is the unauthenticated liveness endpoint
that returns no tenant or user data. Composite requirements are used where a response mixes
concerns - reading a trip needs `planning.trip:read` **and** `orders.order:read`.

**Inactive access.** A deactivated membership, organization, company or role grants nothing: all
four `active` flags are predicates of the single company-permission query. An organization-wide
role attached to a company-scoped membership also grants nothing, which is the one tenancy rule the
database cannot express and which is enforced in that query.

**RLS and grants.** All 25 application tables have RLS enabled with no policies - a complete deny
for any non-owner role. `PUBLIC`, `anon`, `authenticated` and `service_role` are revoked from the
schema, its tables, its sequences and its functions, plus default privileges.
`SchemaExposureIntegrationTest` derives its list from `pg_class` rather than a hard-coded one, so a
table added in a future migration without RLS fails the build.

**CORS.** Exact-origin allow-list, empty by default; a wildcard throws at startup. Credentials are
not allowed - the session is a bearer token in a header, not a cookie, so allowing them would add
CSRF exposure for no benefit.

**Error responses.** `server.error.include-message/stacktrace/binding-errors` are all `never`. The
500 document carries a correlation id and nothing else. A missing permission is never told *which*
permission was required, which would let a caller map the authorization model; the log line says.
A rejected token is never told *why* verification failed.

**Mass assignment.** Every `@RequestBody` binds to a dedicated `*Request` record. Not one of them
declares `companyId`, `id`, `status`, `createdBy`, `updatedBy` or `active` - server-owned fields
are not in the client's vocabulary. No JPA entity is ever bound to a request.

**Audit actor.** `AuditActorProvider` reads the `SecurityContext`. The actor is deliberately not a
service-method parameter, so no caller can choose a different one, and there is no header or body
field that influences it. A write with no authenticated actor throws rather than writing a null
`created_by`.

**SQL injection.** No string-concatenated SQL exists. `JdbcIdentityRepository` uses named
parameters; JPA repositories use derived queries, JPQL with `@Param`, or Specifications. The only
two native queries are `SELECT nextval('tms.…_seq')` with no interpolation. The one place a client
value could reach SQL that no ORM can parameterise - the `sort` parameter, which becomes an ORDER
BY - is validated against a per-endpoint allow-list and refused with 400 if unknown.

**Actuator and OpenAPI.** Only `health` and `info` are exposed, `health` with `show-details: never`
in the default profile. `/actuator/health` is the only public actuator path; `/actuator/info` needs
authentication. Nothing that lists beans, environment variables, configuration properties or HTTP
mappings is enabled. `springdoc` is public in development and falls through to
`anyRequest().authenticated()` in production - asserted both ways by `DocumentationExposureTest`.

## 4. Frontend

React holds no authorization. `hasPermission` in `CompanyContext` hides controls the caller cannot
use, and every one of those actions is independently refused by `@PreAuthorize` on the server -
the API tests prove the server refusal directly, not through the UI.

Supabase is reached from the browser for authentication only: the only `supabase.*` calls in the
whole `src` tree are `auth.getSession`, `auth.onAuthStateChange`, `auth.signInWithPassword` and
`auth.signOut`. Every business read and write goes through `httpClient` to Spring Boot. Only
`VITE_*` values reach the bundle, and the anon/publishable key is the only Supabase key present.

## 5. Test evidence

Security behaviour is asserted, not assumed: `ApiSecurityTest` (17 cases across authentication,
error documents and permissions), `SupabaseJwtDecodersTest` (14), `DocumentationExposureTest`,
`SchemaExposureIntegrationTest` (6), `TenancyConstraintIntegrationTest` (10),
`IdentityResolutionIntegrationTest`, `CapabilityTest`, `AuditActorProviderTest`, plus the
cross-company cases inside every module's API integration test.

## 6. Remaining gaps - deliberately not closed tonight

| # | Severity | Gap | Why it is left, and what closing it needs |
|---|---|---|---|
| 1 | P2 | The principal is resolved with two SQL queries on **every** authenticated request (`JdbcIdentityRepository`). | Not a correctness or security problem, and the queries are indexed and small. A cache here would hold *authorization* state, so it needs an explicit invalidation story for "membership revoked" before it is safe - a cache that keeps a revoked membership alive for 60 seconds is a security regression, not an optimisation. Needs a deliberate design decision, not an overnight one. |
| 2 | P2 | `GET /masterdata/frequencies/{id}/exceptions` returns an unpaginated `List`. | Company-scoped and tenant-safe; the exposure is response size, not data. Paginating changes the response shape and therefore the frontend contract - a versioned change, not a hardening patch. |
| 3 | P2 | `GET /planning/runs/{id}` returns every trip of the run. | Intentional: the board is one call. Bounded by trips per run (~fleet size, 100-300). Documented as a scale ceiling in `docs/performance/PERFORMANCE_BASELINE.md` rather than changed, because splitting it would break the board's single-call design for no benefit at V1 volumes. |
| 4 | P3 | `FORCE ROW LEVEL SECURITY` is not set; the backend connects as the table owner. | Deliberate and already documented in `RLS_STRATEGY.md`. Making the backend a non-owner role requires either real policies or `BYPASSRLS`, plus a deployment change - it is a hardening *option*, not a defect. |
| 5 | P3 | `created_by`/`updated_by` foreign keys have no index. | Only matters for deleting an `app_user`, and app users are deactivated, never deleted (V2). Adding six indexes to support an operation the model forbids is cost with no benefit. |

## 7. Changed in this review

- `PlanningRunService` - `lockTrips`, used by `confirm` and `cancel` (3.1).
- `ApiExceptionHandler` - handlers for `DataIntegrityViolationException` and
  `PessimisticLockingFailureException` (3.2).
- `OriginRepository`, `DestinationRepository`, `VehicleRepository`, `VehicleTypeRepository`,
  `CarrierRepository`, `TransportOrderRepository` - `findByIdInAndCompanyId` (3.3).
- `OriginLookupAdapter`, `DestinationLookupAdapter`, `OrderPlanningService`,
  `VehicleLookupService` - use it (3.3).
- `PlanningApiIntegrationTest` - `confirmDoesNotDeadlockAgainstAConcurrentMove` (3.1).

No migration was edited. No behaviour visible to a correctly-behaving client changed.
