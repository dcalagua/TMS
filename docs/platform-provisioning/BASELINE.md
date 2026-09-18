# Platform provisioning — baseline

**What this is.** The state of TMS by EBIM as audited on 2026-09-18, before any platform-provisioning
code was written. Everything below was read out of the repository at `07a26dc`; nothing here is
carried over from another product. Where the MasterAdmin contract assumes something TMS does not
have, this page says so rather than inventing it.

Audited at HEAD `07a26dc` on branch `dev`. Last applied migration: **V50**. Next free: **V51**.

---

## 1. Tenant model, as the schema actually defines it

TMS has **no table called `tenant`**. The tenant is the pair organization → company, and the
*operational* tenant scope is the **company** (ADR-003).

```
organization ──1:N──> company ──┐
     │                          ├──> every business table carries company_id
     └──< membership >── app_user
```

### `tms.organization` (V2)

| Column | Type | Notes |
|---|---|---|
| `id` | uuid | `DEFAULT gen_random_uuid()` — accepts a caller-supplied id |
| `code` | text | **unique**, `CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$')` |
| `name` | text | not blank |
| `active` | boolean | default true |
| `created_at` / `updated_at` / `created_by` / `updated_by` | | `*_by` are FKs to `app_user` |

**There is no `legal_name`, `tax_id`, `country_code`, `currency` or `time_zone` on the
organization.** This matters for the mapping (§6).

### `tms.company` (V2)

| Column | Type | Notes |
|---|---|---|
| `id` | uuid | `DEFAULT gen_random_uuid()` |
| `organization_id` | uuid | FK to organization |
| `code` | text | unique **per organization**, same shape check as organization code |
| `name` | text | not blank |
| `tax_identifier` | text | nullable, not blank when present |
| `time_zone` | text | **not null, default `'UTC'`** |
| `active` | boolean | default true |

`uq_company_id_organization (id, organization_id)` exists so `membership` can carry a composite
foreign key: "the company belongs to the same organization" is a database guarantee, not an
application convention.

**No `legal_name`, `country_code` or `currency` on the company either.**

### `tms.app_user` (V2)

| Column | Type | Notes |
|---|---|---|
| `id` | uuid | |
| `auth_user_id` | uuid | **nullable**, unique — the Supabase `auth.users` id |
| `email` | text | **unique**, `CHECK (email = lower(btrim(email)))` plus a shape check |
| `full_name` | text | not blank |
| `active` | boolean | default true |

`auth_user_id` being nullable is the single most important fact for admin provisioning: a TMS user
row can exist **before** the person has a Supabase identity, and be linked later. That makes a
preprovisioned admin possible without inventing a password (§Phase 13 of the brief).

### `tms.membership` / `tms.membership_role` (V2)

`membership` binds `app_user` to an `organization` and optionally to a `company`
(`company_id` is nullable — an organization-level membership). `membership_role` attaches roles.

### Roles (V3, `tms.role`)

Seeded, `system_managed`: **`ORGANIZATION_ADMIN`** and **`COMPANY_ADMIN`** (scope levels
`ORGANIZATION` and `COMPANY`), plus `PLANNER` and `VIEWER`. `ORGANIZATION_ADMIN` is described in
V3 as "everything in its own organization".

---

## 2. Authentication today

Two chains exist, and they do not overlap.

| Chain | Path | Credential | Principal |
|---|---|---|---|
| Human | everything not matched below | Supabase-issued JWT, verified against the Supabase JWKS | `TmsAuthenticationToken` → `app_user` → `membership` → `CompanyScope` |
| Machine | `/integration/**` | integration client credentials (`IntegrationAuthenticationFilter`) | `MachineAuthentication`, scope authorities, company resolved from the credential |

`SecurityConfig` is the catch-all: deny-by-default, `anyRequest().authenticated()`, no form login,
no HTTP Basic, no session. `IntegrationSecurityConfig` declares
`securityMatcher("/integration/**")` at `@Order(HIGHEST_PRECEDENCE + 1)`, with `anonymous`
disabled and no CORS.

Authorities are checked with method security: the idiom is
`@PreAuthorize("hasAuthority('integration.order:write')")` on the machine side and a permission
authority on the human side.

**`/internal/**` is unused.** `grep -rn '"/internal' src/main/java` returns nothing, so the path
the brief asks for is free and cannot collide with an existing matcher.

---

## 3. Database ownership

Java owns the schema through Flyway (ADR-002) and reaches PostgreSQL over JDBC. The application
connects as the **schema owner** and enters the non-owner role `tms_app` per company-scoped request
(`TenantScopedDataSource`, ADR-005); `tms_app` is `NOLOGIN` and passwordless, so nothing connects
*as* it. Row level security filters by the tenant of the current transaction and is defence in
depth, never the only control.

There is **no Supabase REST / PostgREST / `service_role` usage anywhere in the backend**, and
none must be introduced: provisioning writes through the same application layer and repositories
as every other write.

Consequence for provisioning: creating an organization happens **before any company exists**, so it
cannot run inside a company scope. It therefore runs on the owner connection, like Flyway and the
principal-resolution path already do.

---

## 4. Idempotency, as the product already does it

`tms.integration_request` (V18) is the existing inbox:

- `idempotency_key`, `payload_hash` with `CHECK (payload_hash ~ '^[0-9a-f]{64}$')` (SHA-256),
- `payload_snapshot` (retained only when explicitly enabled — raw payloads carry personal data),
- `status` in `('SUCCEEDED','PARTIAL','REJECTED','FAILED')`, `http_status`,
- `response_body` — the response as returned, so a replay answers identically instead of
  re-executing,
- `error_summary` — "sanitised: a short business summary, never an exception message, a stack frame
  or SQL",
- `correlation_id`, `received_at`, `completed_at`, `duration_ms`.

The rule already enforced by `IntegrationRequestExecutor`: same key + same payload replays the
stored response; same key + **different** payload is refused
(`ProblemType.IDEMPOTENCY_KEY_REUSED`, HTTP 409). Uniqueness is closed by a database index, not by
the read-then-write check alone.

## 5. Error contract and audit

`ProblemType` (`shared/api`) is an enum of RFC 7807 problem types with a stable `code`, rendered by
`ApiExceptionHandler` as `application/problem+json`. Existing members include `UNAUTHENTICATED`,
`INVALID_TOKEN`, `VALIDATION_FAILED`, `CONFLICT`, `IDEMPOTENCY_KEY_REUSED`, `RESOURCE_NOT_FOUND`.
It carries no database CHECK, so adding members is a code-only change.

`tms.audit_event` is company-scoped and its `action` column is constrained by
`ck_audit_event_action`, which has been widened by V25, V26, V28 and V30. `AuditRecorder` requires a
`CompanyScope`. **Neither fits provisioning**, which has no company scope at the moment the
organization is created — so the provisioning record has to be its own audit trail.

## 6. Where the MasterAdmin contract and TMS disagree

| MasterAdmin field | TMS | Consequence |
|---|---|---|
| `organization.slug` | `organization.code`, upper-case `[A-Z0-9_-]`, max 32 | needs normalisation, and can fail validation |
| `organization.legalName` | — | not modelled |
| `organization.taxId` | — on organization; `company.tax_identifier` exists | belongs on the company |
| `organization.countryCode` | — | not modelled |
| `organization.currency` | — | not modelled |
| `organization.timezone` | — on organization; `company.time_zone` exists | belongs on the company |
| `company.legalName` | — | not modelled |
| `company.countryCode` / `currency` | — | not modelled |
| `company.id`, `organization.id` | both tables accept a supplied uuid | usable as the internal id |
| `admin.email` / `fullName` | `app_user` | `auth_user_id` stays null until the person signs in |
| `controlPlaneTenantId` | nothing equivalent exists | **requires a new mapping table** |
| `deploymentMode: SHARED` | no concept of deployment mode | recorded on the provisioning row, drives nothing in V1 |

Fields TMS does not model are **not** invented as columns. The design page states what is stored,
what is recorded verbatim for traceability, and what is accepted and ignored.

## 7. Guards any new code has to satisfy

- `ModuleBoundaryTest` — modules are free of cycles and `shared` never depends on a business
  module; its `BUSINESS_MODULES` list must name a new module, or the rule passes vacuously over it.
- `LayeringTest` — controllers live in `..api..`, use cases in `..application..`, repositories in
  `..infrastructure..`; controllers must not reach repositories or persistence APIs.
- `EndpointContractTest` — every handler carries `@PreAuthorize` unless it is in a short, explicit
  allowlist.
- `MigrationConventionTest` — contiguous versions, every table enables RLS and carries a policy,
  every sequence is granted to `tms_app` by name, every function pins its `search_path`, and no
  table is ever `FORCE ROW LEVEL SECURITY`.
- `PersistedEnumConstraintTest` — a Java enum persisted to a column with a CHECK must match it.

## 8. Environment on the audited machine

- Java 21, Maven through `./mvnw` only.
- **Docker is not installed** (no Docker Desktop, no podman). The test harness added in rc2 accepts
  a local disposable PostgreSQL through `TMS_TEST_DB_URL` / `TMS_TEST_DB_USER` /
  `TMS_TEST_DB_PASSWORD`, which is how the integration tests run here. H2 is not used and must not
  be: the schema depends on PostGIS, `EXCLUDE USING gist`, partial unique indexes and RLS.
- Node 18 on the PATH; the frontend toolchain needs ≥ 20.19, so frontend gates run with a portable
  Node 22.
- `.github/workflows/ci.yml` was **deleted** in `07a26dc`. There is no CI to rely on; gates are run
  locally and reported as such.
- The QAS public key is present at `C:\EBIM\keys\tms\qas\masteradmin-public.pem` (178 bytes,
  `BEGIN/END PUBLIC KEY`, contains no private material). It is outside the repository and stays
  outside it.

## 9. Configuration convention

Properties are namespaced `tms.<area>.<name>` and bound with `@ConfigurationProperties`; each value
in `application.yml` reads an environment variable with an explicit default, e.g.
`issuer-uri: ${TMS_SUPABASE_JWT_ISSUER_URI:}`. Secrets never carry a literal default. This lets the
brief's `EBIM_MASTERADMIN_M2M_*` names be the operational interface while the Java side keeps the
project's own property namespace; the mapping is written out in `SECURITY.md`.
