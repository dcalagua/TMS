# TMS by EBIM - backend security baseline (V1)

One line: **Supabase Auth proves who the caller is, Spring Boot decides what they may do and
in which company, and PostgreSQL RLS is the second line of defense behind both.**

This document describes what is implemented in `backend/tms-api` as of Step 03. The database
half is in [`RLS_STRATEGY.md`](RLS_STRATEGY.md); the permission catalogue is in
[`AUTHORIZATION_MODEL.md`](AUTHORIZATION_MODEL.md); the wire contract is in
[`../api/API_CONVENTIONS.md`](../api/API_CONVENTIONS.md).

## 1. Trust boundaries

| Actor | Trusted for | Never trusted for |
|---|---|---|
| Supabase Auth | proving that a person controls an account, and issuing a signed JWT | roles, permissions, tenancy |
| The browser | user input, UX state | identity, company selection, authorization |
| The JWT | its `sub` claim, after signature/issuer/audience/expiry validation | any other claim, including `role` and `app_metadata` |
| `tms.app_user` / `tms.membership` | identity, tenancy and permissions | - |

The consequence worth stating explicitly: **TMS reads exactly one claim from the token.**
Supabase custom claims are ignored, because they are set outside this application's control
and honouring them would move authorization out of the backend.

## 2. Request pipeline

```
  CorrelationIdFilter                order -105, ahead of Spring Security
      |                              assigns/echoes X-Correlation-Id, puts it in the MDC
      v
  springSecurityFilterChain          order -100
      |
      +-- CORS                       explicit origin allow-list, credentials off
      +-- CSRF                       disabled: bearer tokens, no cookie session
      +-- SessionManagement          STATELESS
      +-- BearerTokenAuthenticationFilter
      |       JwtDecoder             JWKS signature (RS256/ES256)
      |                              + expiry (30s skew) + issuer + audience + subject present
      |       TmsJwtAuthenticationConverter
      |                              sub -> UUID -> PrincipalLoader -> TmsPrincipal
      +-- CompanyScopeFilter
      |       X-Company-Id -> validated against the principal's memberships
      |       authorities := permissions of THAT company
      +-- AuthorizationFilter        anyRequest().authenticated()
      v
  DispatcherServlet
      |
      +-- CompanyScopeArgumentResolver     supplies the validated CompanyScope
      +-- @PreAuthorize                    hasAuthority('<resource>:<action>')
      +-- controller -> use case -> repository
```

Every rejection is turned back into the shared `@RestControllerAdvice` by
`ApiExceptionResponder`, so a 401 raised in the filter chain and a 400 raised in a controller
produce the same document shape.

## 3. Authentication

### 3.1 Configuration

| Property | Environment variable | Notes |
|---|---|---|
| `tms.security.jwt.issuer-uri` | `TMS_SUPABASE_JWT_ISSUER_URI` | mandatory |
| `tms.security.jwt.jwk-set-uri` | `TMS_SUPABASE_JWKS_URI` | mandatory |
| `tms.security.jwt.audiences` | `TMS_SUPABASE_JWT_AUDIENCES` | defaults to `authenticated` |
| `tms.security.jwt.clock-skew` | - | defaults to 30s, capped at 5 minutes |

**There is no signing-secret setting, and there cannot be one.** Verification is JWKS-only, so
no secret material exists in this repository, in the configuration, or in a deployment's
environment. Key rotation at Supabase needs no redeployment; the JWK set is fetched and cached
by `NimbusJwtDecoder`.

If a Supabase project still signs with the legacy shared secret, switch it to asymmetric
signing keys. That is a project setting, not a code change - and the reason the code offers no
alternative is that a symmetric secret has to be distributed to every backend instance, which
is the failure mode this avoids.

### 3.2 Fail-safe startup

`SupabaseJwtDecoders.validate` runs when the `JwtDecoder` bean is created, in **every profile**:

- issuer or JWKS missing or blank -> the context fails to start, with a message naming both
  environment variables. There is no "start without token verification" mode to forget about;
- under a production profile (`prod`, `production`, `staging`) both URIs must be `https` and
  must not resolve to a loopback address. A development configuration that leaks into a
  deployment therefore fails at startup instead of trusting a local key server;
- a clock skew outside 0-5 minutes is refused, because an unbounded skew makes `exp` decorative.

There is no `@Profile`, no `@ConditionalOnProperty` and no permit-all branch anywhere in the
main sources. Test support replaces the *key source* (`SecurityTestConfiguration` supplies a
decoder over a locally generated RSA key) and nothing else: the filter chain, the claim
validators, the converter, the scope filter and method security under test are the ones that
ship.

### 3.3 Identity mapping

```
JWT.sub (uuid)  ->  tms.app_user.auth_user_id  (active = true)
                ->  tms.membership             (active, organization active, company active)
                ->  tms.membership_role -> tms.role -> tms.role_permission -> tms.permission
```

Resolved by `PrincipalResolutionService` (module `iam`) in a single read-only transaction of
two statements, behind the `PrincipalLoader` port declared in `shared`. The port exists so the
filter chain contains no business query and `shared` never depends on a business module.

Two rules are enforced in that query beyond the obvious joins:

1. an organization-wide membership (`company_id IS NULL`) expands to every **active** company
   of its organization;
2. a role whose `scope_level` is `ORGANIZATION` grants nothing when it is attached to a
   company-scoped membership. The database cannot express that pairing (migration V2 leaves it
   to Java), so without this rule attaching `ORGANIZATION_ADMIN` to a single-company membership
   would silently hand that user the entire catalogue inside that company.

### 3.4 Status codes, and why they differ

| Situation | Status | `code` |
|---|---|---|
| no `Authorization` header | 401 | `unauthenticated` |
| token expired, forged, wrong issuer, wrong audience, unparseable, non-UUID subject | 401 | `invalid-token` |
| valid token, no active `tms.app_user` | **403** | `principal-not-provisioned` |

The third case is 403 rather than 401 on purpose: the caller authenticated correctly and
signing in again cannot help - an administrator has to provision or reactivate the profile.
Answering 401 would send a client into a pointless re-authentication loop.

The 401 responses never distinguish *why* a token failed. Someone probing with forged tokens
learns nothing from the difference between a bad signature and a wrong issuer.

## 4. Tenancy

The company scope contract is `X-Company-Id`; it is specified in
[`../api/API_CONVENTIONS.md#company-scope`](../api/API_CONVENTIONS.md). The security-relevant
properties:

- the header **selects**; `tms.membership` **decides**. `CompanyScopeFilter` refuses any
  company the principal holds no active membership in, before a controller, service or
  repository sees the request;
- authorities are the permissions of the **selected company only**. There is no principal-wide
  union - a union would let a user act in company B with permissions granted in company A;
- a cross-tenant attempt is logged at WARN with the `app_user` id, the requested company id and
  the request line. The response says only "not available to this account", so it does not
  confirm whether the company exists;
- a controller obtains a `CompanyScope` only from `CompanyScopeArgumentResolver`. There is no
  constructor or factory that builds one from a request value, so a use case cannot be invoked
  for a company that was not validated.

## 5. Authorization

`@EnableMethodSecurity` with `@PreAuthorize("hasAuthority('<resource>:<action>')")` on
company-scoped endpoints. Authorities are the permission codes of the selected company; the
`Permission` enum mirrors `tms.permission` one for one and
`IdentityResolutionIntegrationTest` fails the build if the two drift, so a typo in a
`@PreAuthorize` string cannot silently become an endpoint nobody can reach - or one everybody
can.

Denials answer 403 `access-denied` and never name the missing permission; naming it would let a
caller enumerate the authorization model. The permission set the caller *did* hold is logged.

Frontend permission checks are UX only. `GET /api/v1/me` returns permissions and capabilities
per company precisely so the UI can hide what will not work - editing that response in the
browser changes nothing, because every endpoint re-checks server-side.

## 6. Data exposure

- error documents carry no stack trace, exception type, class name or SQL. A 500 carries a
  correlation id and nothing else; the cause is in the server log under that id
  (`ApiSecurityTest.errorsDoNotLeakInternals`);
- `server.error.include-message/stacktrace/binding-errors` are all `never`;
- `/actuator/health` is public but `show-details: never`, so no database URL, disk path or
  vendor version is rendered. `/actuator/info` now requires authentication;
- only `health` and `info` are exposed. `env`, `beans`, `configprops`, `mappings` are not;
- `management.info.env.enabled: false`, so no `info.*` property can publish a secret;
- OpenAPI and Swagger UI are public in development and authenticated in production
  (`tms.api.public-documentation`, `false` in `application-prod.yml`).

## 7. Transport and browser hardening

- `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
  HSTS with `includeSubDomains`;
- CORS origins are an explicit allow-list, empty by default; wildcards are rejected at startup;
  credentials are **not** allowed, because the session is a bearer token in a header rather
  than a cookie, so allowing them would add CSRF exposure for no benefit;
- allowed request headers are `Authorization`, `Content-Type`, `Accept`, `X-Company-Id`,
  `X-Correlation-Id`, `X-Request-Id`; only `X-Correlation-Id` is exposed to the browser;
- `X-Correlation-Id` from a client is sanitised to a bounded token of safe characters before it
  reaches a log line or a response header, which blocks header injection and log forging.

## 8. What the tests prove

| Claim | Test |
|---|---|
| unauthenticated requests are refused | `ApiSecurityTest.unauthenticatedIsRejected` |
| forged, expired, wrong-issuer, wrong-audience and malformed tokens are refused | `ApiSecurityTest.Authentication` |
| a valid token with no TMS profile is refused with 403 | `ApiSecurityTest.unprovisionedPrincipalIsRejected` |
| a valid mapped user is accepted | `ApiSecurityTest.validPrincipalIsAccepted` |
| a company the caller is not a member of is refused | `ApiSecurityTest.companyOfAnotherOrganizationIsRejected`, `siblingCompanyWithoutMembershipIsRejected` |
| permissions do not leak between the caller's own companies | `ApiSecurityTest.permissionsDoNotLeakBetweenCompanies` |
| the required permission grants access | `ApiSecurityTest.permittedCallerIsAccepted` |
| every error is one RFC 9457 shape, with no internals | `ApiSecurityTest.ErrorShape` |
| a deactivated user or membership grants nothing | `IdentityResolutionIntegrationTest` |
| an organization-level role on a company membership grants nothing | `IdentityResolutionIntegrationTest.organizationRoleOnCompanyMembershipGrantsNothing` |
| the Java permission enum matches the migrated catalogue | `IdentityResolutionIntegrationTest.permissionEnumMatchesTheDatabaseCatalogue` |
| missing auth configuration stops startup; production rejects a local configuration | `SupabaseJwtDecodersTest` |
| controllers cannot bypass the service layer to reach a repository | `LayeringTest` |

No test contacts an authentication service, and no signing key exists outside the JVM that
generated it.

## 9. Known limits of this baseline

1. **Identity is resolved on every request** - two indexed statements, no cache. Correct and
   simple; if it ever shows up in a profile, cache it with an explicit, short TTL and an
   invalidation path for membership changes. Do not cache it "for a while" without one.
2. **No refresh, sign-out or session revocation handling in the backend.** A token stays valid
   until it expires; deactivating a user takes effect on the next request because the
   membership query runs per request, but the token itself is not revoked. Supabase owns the
   session lifecycle.
3. **No rate limiting or brute-force protection** on the API. It belongs at the edge
   (gateway/CDN) rather than in application code; it is not implemented here and should not be
   assumed.
4. **RLS is enabled with no policies and is not FORCEd**, so the owning application role is
   exempt. That is deliberate and reasoned in `RLS_STRATEGY.md`; it means Spring Boot really is
   the authorization boundary, and RLS protects against paths the backend does not control.
