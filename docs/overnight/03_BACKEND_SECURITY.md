# Step 03 - Backend Security and API Foundation

Date: 2026-08-19
Attempt: 2 (re-verification of the attempt-1 implementation)
Result: **PASS**

## 0. Why there was a second attempt

Attempt 1 completed the implementation and printed its gate marker wrapped in backticks
(`` `TMS_GATE=PASS` ``). The supervisor matches `grep -q '^TMS_GATE=PASS$'`, so the decorated
line did not match and the step was recorded as BLOCKED even though the work had finished.

Attempt 2 therefore changed no production code. It re-ran the whole suite from a clean state to
confirm the attempt-1 claims independently, and emits the marker as a bare line. The
verification numbers in section 4 come from the attempt-2 run.

One reading note for anyone auditing the surefire output: the per-class `.txt` summaries show
`Tests run: 0` for `ApiSecurityTest`, `SupabaseJwtDecodersTest` and `DocumentationExposureTest`,
because those classes hold their tests in `@Nested` inner classes and the `.txt` writer counts
only direct methods. The XML reports are authoritative and show 24, 14 and 3 tests respectively,
all passing. A green build from those three classes is not an empty build.

## 1. State inherited

Steps 00-02 left an architecture of record, a bootstrapped monorepo, and a Flyway history of
four migrations covering identity, tenancy, the IAM catalogue and the database exposure
posture. The backend had a deny-by-default filter chain with no authentication behind it: every
business endpoint answered 401 because nothing could authenticate, which was the honest
placeholder Step 01 chose rather than leaving a hole.

Step 02's handoff listed six things this step had to honour. All six are implemented and each
is named below where it applies.

## 2. Environment

Docker Desktop was running, so every Testcontainers test executed rather than being skipped.
Java 21.0.9, Maven via the committed wrapper, Spring Boot 4.0.7 (Spring Framework 7.0.8,
Spring Security 7.0.6). No remote database, Supabase project or authentication service was
contacted at any point.

One platform detail shaped an implementation choice: **Spring Boot 4 ships Jackson 3**
(`tools.jackson.*`). The Jackson 2 artifact on the classpath is Flyway's transitive dependency
and has no Spring configuration behind it. A first cut of the security error writer injected
`com.fasterxml.jackson.databind.ObjectMapper` and failed to start. The fix is better than the
original: security rejections are now routed back into the MVC `handlerExceptionResolver`, so
the error document is produced by the same `@RestControllerAdvice` and the same configured
message converters as a controller error - one shape, one place, and no JSON library
referenced in application code at all.

## 3. What was built

### 3.1 Authentication - Supabase JWT resource server

`SupabaseJwtConfig` builds a `NimbusJwtDecoder` over the project's JWKS. Validation is
signature (RS256/ES256) plus expiry with a bounded skew, issuer, audience and the presence of a
subject - assembled explicitly in `SupabaseJwtDecoders.validator` rather than inherited from
defaults, so what is checked is readable in one method.

There is **no signing-secret setting and no way to add one**: verification is JWKS-only, which
is why no secret material exists in the repository, the configuration or a deployment's
environment.

Fail-safe startup, enforced in every profile:

- issuer or JWKS missing/blank -> the context does not start, and the message names
  `TMS_SUPABASE_JWT_ISSUER_URI` and `TMS_SUPABASE_JWKS_URI`;
- under `prod`/`production`/`staging` both URIs must be `https` and non-loopback, so a
  development configuration that reaches a deployment fails at startup instead of trusting a
  local key server;
- a clock skew outside 0-5 minutes is refused.

There is no `@Profile`, no `@ConditionalOnProperty` and no permit-all branch in the main
sources. Tests replace the *key source* and nothing else.

### 3.2 Identity resolution

`JWT.sub -> tms.app_user.auth_user_id -> active memberships -> company scopes`, implemented by
`PrincipalResolutionService` in the `iam` module behind a `PrincipalLoader` port declared in
`shared`. The port keeps the dependency pointing `iam -> shared`, which is what the existing
`ModuleBoundaryTest` requires, and keeps business SQL out of the filter chain.

Two statements per request, both index-driven (`ix_membership_app_user_active`), in one
read-only transaction. Plain JDBC rather than JPA for this path: it runs on every request, so it
must be exactly two statements with no lazy loading, and it is the query that decides who may
act where, so it should be auditable in one place. Business modules from Step 05 use JPA as the
architecture of record specifies.

The query encodes two rules Step 02 handed over:

1. an organization-wide membership (`company_id IS NULL`) expands to every **active** company
   of its organization;
2. **a role with `scope_level = 'ORGANIZATION'` grants nothing on a company-scoped
   membership.** This is the pairing the database cannot express. Without it, attaching
   `ORGANIZATION_ADMIN` to a single-company membership - a plausible administrative slip -
   would silently confer the entire catalogue inside that company.

### 3.3 Company scope contract

**`X-Company-Id`, carrying the company UUID, on every company-scoped endpoint.** Documented in
`docs/api/API_CONVENTIONS.md` with the reasoning: one header instead of a company prefix on
every route means one validation point rather than one per route, and the tenant stays out of
the resource address.

The header selects; `tms.membership` decides:

- `CompanyScopeFilter` runs immediately after the bearer-token filter, resolves the header
  against the principal's membership snapshot, and refuses anything else with 403 before a
  controller, service or repository sees the request;
- **authorities are the permissions of the selected company and of no other.** There is no
  principal-wide union - a union would let a user act in company B with permissions granted in
  company A. Before a company is selected there are no permission authorities at all, so
  company-scoped endpoints deny by default;
- a controller obtains a `CompanyScope` only through `CompanyScopeArgumentResolver`. There is
  no constructor or factory that builds one from a request value, so a use case *cannot* be
  invoked for an unvalidated company. Missing header -> 400, not a guessed default;
- cross-tenant attempts are logged at WARN with actor and requested company; the response does
  not reveal whether the company exists.

### 3.4 Authorization

`@EnableMethodSecurity` with `@PreAuthorize("hasAuthority('<resource>:<action>')")`. The
`Permission` enum mirrors `tms.permission` one for one, and an integration test fails the build
if they drift - so a typo in a `@PreAuthorize` string cannot become an endpoint nobody can
reach, or one everybody can.

Migration **V5** completes the catalogue the step brief requires. V3 could not express three
capabilities, and V1-V4 are applied and immutable, so the rows arrived in a new migration:

| Added | Backs |
|---|---|
| `planning.plan:read` / `planning.plan:manage` | `PLANNING_VIEW` / `PLANNING_MANAGE` |
| `monitoring.transport:read` | `TRANSPORT_MONITOR_VIEW` |

Planning and trips are kept separate because they are separate activities: running a planning
session is not the same authority as authoring or altering the trips it produces. The monitor
has no `manage` action, like `audit.log`.

The brief's coarse names (`MASTER_DATA_VIEW`, `TRIPS_MANAGE`, ...) exist as the `Capability`
enum: **derived** from permissions, returned to the UI, and never enforced. Keeping them
derived rather than stored avoids two overlapping catalogues where only one is actually
checked. The full mapping is in `docs/security/AUTHORIZATION_MODEL.md`; `CapabilityTest`
asserts all eleven required names exist and that no permission is left unmapped.

### 3.5 API foundations

- **RFC 9457 problem details** for every failure, from a filter or a controller, with a stable
  `type` URN, a short `code`, `instance`, `timestamp` and `correlationId`. Twelve catalogued
  codes.
- **Bean Validation mapping** to a `errors[]` array of `{field, message}`, using request-side
  field names.
- **No internals in any error**: no stack trace, exception type, class name or SQL. A 500
  carries a correlation id and nothing else; the cause is in the log under that id. Asserted,
  not assumed.
- **Paging conventions**: `page`/`size`/`sort`, size clamped to 200 server-side, sort
  allow-listed per endpoint (an `ORDER BY` fragment cannot be parameterised). `PageResponse` is
  TMS-owned so the contract does not change with the ORM. **No paging or filter parameter
  accepts a tenant id** - a test asserts the record has no such component.
- **Correlation id**: `X-Correlation-Id` (or `X-Request-Id`) accepted, generated when absent,
  echoed, put in the MDC and in the log pattern. A client value is sanitised to a bounded token
  of safe characters, which blocks header injection and log forging.
- **Audit actor context**: `AuditActorProvider` resolves the acting `app_user`, company,
  organization and correlation id from the security context - never from a payload, because an
  audit trail a client can write is not an audit trail. This is what `created_by`/`updated_by`
  will carry from Step 05.
- **OpenAPI**: the bearer scheme is a *global* security requirement, matching the
  deny-by-default chain; the company header is documented on the scoped endpoint.
- **Health**: only `health` and `info` exposed; `show-details: never`; `info.env` disabled.
  `/actuator/info` moved from public to authenticated - build metadata is not something an
  anonymous caller needs. `/actuator/health` stays public for probes.

### 3.6 Endpoints delivered

| Endpoint | Scope | Guard |
|---|---|---|
| `GET /api/v1/me` | principal | authenticated |
| `GET /api/v1/companies/current` | company | `X-Company-Id` + `iam.company:read` |

`/me` returns the profile, the selectable companies, and per company the permission codes and
derived capabilities - one call, so the frontend needs no round trip per company switch.
`/companies/current` is deliberately small: it exists as the working template every
company-scoped endpoint in Steps 05+ copies, and as the thing the isolation tests attack.

### 3.7 Architecture rules

`LayeringTest` (ArchUnit) now fails the build on:

- a `@RestController` depending on a repository, or on `jakarta.persistence`/`spring-jdbc` -
  the controller-to-repository bypass, which is how a query without a company filter gets
  written;
- controllers outside `..api..`, `@Service` outside `..application..`, `*Repository` outside
  `..infrastructure..`;
- `..application..` depending on `..api..`, so a use case stays callable from a job;
- any `@Autowired` member - constructor injection only.

`ModuleBoundaryTest` from Step 01 is unchanged and still passes: `shared` does not depend on
`iam`, and the modules remain acyclic.

## 4. Verification

Re-run in attempt 2. The sandbox refused to execute `scripts/check-all.sh` as a single
invocation, so its five stages were run individually - the same commands the script issues:

```
./mvnw -B verify                 backend      113 tests, 0 failures, 0 errors, 0 skipped
npm run typecheck                frontend     clean (tsc -b)
npm run lint                     frontend     clean (oxlint)
npm test                         frontend     2 files, 5 tests passed
npm run build                    frontend     built in 279ms
```

Docker Desktop was running, so the Testcontainers classes executed rather than being skipped:
the run started a `postgis/postgis:17-3.5` container and Flyway applied V1..V5 to it. Zero
skipped tests is therefore a real number, not an artefact of an absent daemon.

Backend test breakdown (counts taken from the surefire XML, which is authoritative for
`@Nested` classes):

| Class | Tests | Covers |
|---|---|---|
| `ApiSecurityTest` | 24 | the whole security contract through the real filter chain |
| `IdentityResolutionIntegrationTest` | 10 | the tenancy SQL against a disposable PostgreSQL |
| `SupabaseJwtDecodersTest` | 14 | fail-safe startup and production hardening |
| `PagingConventionsTest` | 10 | size clamping, sort allow-list, no tenant in filters |
| `LayeringTest` | 7 | layering and injection rules |
| `CapabilityTest` | 6 | capability/permission mapping completeness |
| `AuditActorProviderTest` | 4 | actor context |
| `DocumentationExposureTest` | 3 | OpenAPI public in development, authenticated in production |
| `SystemInfoControllerTest` | 2 | the one public business endpoint, and deny-by-default |
| `ModuleBoundaryTest` (Step 01) | 3 | module acyclicity, `shared` independent of business modules |
| Step 02 database tests | 30 | migrations, tenancy constraints, schema exposure, local seed |

The step brief asked for eight specific cases. Each maps to a named test:

| Required case | Test |
|---|---|
| unauthenticated request rejected | `unauthenticatedIsRejected` |
| invalid JWT rejected | `forgedSignatureIsRejected`, `expiredTokenIsRejected`, `wrongIssuerIsRejected`, `wrongAudienceIsRejected`, `malformedTokenIsRejected`, `nonUuidSubjectIsRejected` |
| valid mapped user accepted | `validPrincipalIsAccepted` |
| inactive user/membership rejected | `unprovisionedPrincipalIsRejected`; `deactivatedUserDoesNotResolve`, `revokedMembershipGrantsNothing` |
| company membership isolation | `companyOfAnotherOrganizationIsRejected`, `siblingCompanyWithoutMembershipIsRejected`, `isolationIsSymmetric`, `companiesOfAnotherOrganizationAreNeverReturned` |
| missing permission rejected | `permissionsDoNotLeakBetweenCompanies` |
| correct permission accepted | `permittedCallerIsAccepted` |
| error response shape | `errorShapeIsUniform`, `errorsDoNotLeakInternals` |

No test contacts an authentication service. Tokens are minted and verified with an RSA keypair
generated in the test JVM, and the decoder applies the **production** claim validators, so what
passes here is the logic that ships.

Each required case was confirmed in attempt 2 against the executed-test list in the surefire
XML, not against the source, so the mapping above names tests that actually ran.

## 5. Things worth flagging

1. **The web tests stub identity resolution; the integration test exercises the real SQL.**
   Either half alone would be a weak proof - a stub that agrees with a wrong query proves
   nothing - so both are present and the permission enum is asserted against the migrated
   catalogue to tie them together.
2. **`TenancyConstraintIntegrationTest` was edited**, not because it was wrong but because V5
   changed the reference-data counts it asserts (29 -> 32 permissions, 81 -> 92 grants). The
   comment in the test records why. No migration was modified; V1-V4 remain byte-identical.
3. **Two hardening rules are profile-conditional, and that is worth knowing before deploying.**
   `public-documentation` defaults to `true` and is set to `false` in `application-prod.yml`,
   and the https/non-loopback JWKS rules apply under `prod`/`production`/`staging` only. A
   deployment that forgets to activate the profile therefore serves OpenAPI anonymously and
   accepts a plaintext JWKS URI. What it cannot do is start without JWT verification at all -
   the issuer and JWKS are mandatory in *every* profile - so this is reduced defence in depth,
   not an authentication bypass. Step 12 should add a startup assertion that a non-loopback
   deployment is running with a production profile.

## 6. Constraint compliance

| Constraint | How |
|---|---|
| never push, never deploy | nothing was pushed; no deployment exists |
| never mutate a remote/shared database | only Testcontainers databases, created and destroyed in-run |
| no real secrets | `.env.example` placeholders only; JWKS-only verification means there is no secret to hold |
| no destructive Git operations | none run |
| Flyway is the only migration owner | V5 added under `db/migration`; `supabase/migrations` still does not exist and `MigrationConventionTest` proves it |
| applied migrations immutable | V1-V4 untouched; the new catalogue rows are a new version |
| Java owns business logic and authorization | authorization is Spring Security + services; RLS unchanged and still policy-free |
| React talks to Spring Boot | no frontend change in this step |
| TMS independent from EWM | nothing added that references EWM |
| vertical slice checked end to end | `/api/v1/companies/current`: UI contract -> controller -> use case -> repository -> DB -> security -> tests |
| do not claim untested passes | every count above comes from the run recorded in section 4 |

## 7. Blockers

None. Docker was available in both attempts, so no test was skipped.

Attempt 2 confirmed independently, rather than by trusting the attempt-1 report:

- V1-V4 are unmodified in `git status` and only V5 is new, so migration immutability holds;
- `supabase/` contains `README.md`, `config.toml` and `seeds` only - no competing migration
  history for the same DDL;
- no literal secret appears in `application*.yml` or `.env.example`; the only password settings
  are `${TMS_DB_PASSWORD}` (no default under `prod`) and a local-only disposable default;
- the one anonymous business endpoint returns name, version, status, active profiles and a
  timestamp, and nothing tenant- or user-derived;
- the security filter chain has exactly three `permitAll` entries - actuator health, the
  conditional documentation paths, and system info - followed by `anyRequest().authenticated()`;
- the test setup replaces only the `JwtDecoder` key source and the `PrincipalLoader`; the chain,
  claim validators, company-scope filter and method security under test are the production beans.

## 8. Files

Added - backend main (46 files):

```
shared/api/        ApiHeaders, ProblemType, ApiProblems, ApiExceptionHandler,
                   ApiExceptionResponder, InvalidRequestException, ResourceNotFoundException,
                   PageQuery, PageResponse
shared/security/   Permission, Capability, CompanyScope, TmsPrincipal, PrincipalLoader,
                   TmsAuthenticationToken, TmsJwtAuthenticationConverter,
                   UnprovisionedPrincipalException, TmsSecurityProperties,
                   SupabaseJwtDecoders, SupabaseJwtConfig, TmsAuthenticationEntryPoint,
                   TmsAccessDeniedHandler, CompanyScopeFilter, CompanyScopeArgumentResolver,
                   CompanyScopeRequiredException, CompanyScopeInvalidException,
                   CompanyScopeDeniedException
shared/web/        CorrelationId, CorrelationIdFilter, WebConfig
shared/audit/      AuditActor, AuditActorProvider
iam/api/           MeController, CompanyContextController
iam/application/   PrincipalResolutionService, MeService, CompanyContextService,
                   MeView, UserView, OrganizationView, CompanyAccessView, CompanyAccessViews
iam/domain/        AppUserProfile, CompanyPermissionRow
iam/infrastructure/ IdentityRepository, JdbcIdentityRepository
```

Added - migration, tests, docs:

```
db/migration/V5__authorization_catalogue_completion.sql
test/.../iam/api/ApiSecurityTest.java
test/.../iam/infrastructure/IdentityResolutionIntegrationTest.java
test/.../shared/security/{TestJwts,TestPrincipals,StubPrincipalLoader,
                          SecurityTestConfiguration,SupabaseJwtDecodersTest,CapabilityTest}.java
test/.../shared/api/{PagingConventionsTest,DocumentationExposureTest}.java
test/.../shared/audit/AuditActorProviderTest.java
test/.../architecture/LayeringTest.java
docs/security/SECURITY_BASELINE.md
docs/security/AUTHORIZATION_MODEL.md
docs/api/API_CONVENTIONS.md
docs/overnight/03_BACKEND_SECURITY.md
```

Modified:

```
shared/security/SecurityConfig.java        resource server, method security, CORS, headers
shared/security/PublicApiPaths.java        documentation exposure switch
shared/config/TmsApiProperties.java        public-documentation flag
shared/config/OpenApiConfig.java           global bearer requirement, company header
application.yml / -local / -prod / -test   JWT, CORS, log pattern, actuator, docs exposure
backend/tms-api/.env.example               JWT and CORS placeholders, no secret
test/.../database/{PostgresTestDatabase,DockerAvailability}.java   made reusable across packages
test/.../database/TenancyConstraintIntegrationTest.java            V5 reference-data counts
test/.../shared/api/SystemInfoControllerTest.java                  new security beans
README.md                                  API surface table
docs/README.md, docs/security/README.md    index entries
```

## 9. Handoff to Step 04 (frontend foundation)

1. **Sign-in is the only direct Supabase call.** The browser obtains an access token from
   Supabase Auth and sends it as `Authorization: Bearer <token>`. Business data comes from
   Spring Boot only.
2. **After sign-in, call `GET /api/v1/me`.** It returns the profile, the companies to offer in
   the switcher, and per company the permission codes plus derived capability names. Build the
   company switcher and the menu from that single response.
3. **Send `X-Company-Id` on every company-scoped call**, with the id of the selected company.
   Persist the selection client-side, but treat 403 `company-scope-forbidden` as "the selection
   is stale" - re-fetch `/me` and ask the user to choose again.
4. **Handle errors by `code`, not by `detail`.** The twelve codes are listed in
   `docs/api/API_CONVENTIONS.md`. `401 unauthenticated`/`invalid-token` -> refresh or sign in;
   `403 principal-not-provisioned` -> a message telling the user to contact an administrator,
   not a retry loop.
5. **Send `X-Correlation-Id`** and surface the `correlationId` from an error document in the
   SweetAlert2 dialog for a 500. It is the only thing that connects a user's report to a log line.
6. **Permission checks in React are UX only.** Hide what will not work; never treat hiding as a
   control. Every endpoint re-checks.
7. **Set `TMS_CORS_ALLOWED_ORIGINS`** to the dev server origin (`http://localhost:5173` is the
   `local` default). The list is empty by default and wildcards are refused at startup.
8. **Backend list endpoints will use** `?page&size&sort=property,asc;other,desc` and return
   `{content, page, size, totalElements}`. Build the table component against that shape now.

## 10. Result

The backend authenticates Supabase-issued tokens against the published JWKS with no secret
anywhere, refuses to start if that configuration is missing, resolves identity and tenancy
server-side from `app_user` and `membership`, validates every company selection against active
membership before a controller runs, evaluates permissions inside the selected company only,
and answers every failure with one RFC 9457 shape that leaks nothing. 113 backend tests pass,
including cross-tenant isolation proved both through the HTTP surface and against a real
PostgreSQL with the real migrations applied.
