# TMS - MasterAdmin operational lifecycle (`lifecycle.v1`)

Receiver of EBIM MasterAdmin's suspend / reactivate instruction. Decision record: ADR-021. Normative
contract: MasterAdmin `docs/platform-provisioning/LIFECYCLE_CONTRACT.md` (sections 2-5).

## What it does

MasterAdmin suspends a tenant (non-payment, manual, terminated) and reactivates it when the debt is
settled. TMS stores the last state it was told, durably, and enforces it from that local copy only:

| | `SUSPENDED` | `ACTIVE` (or never set) |
| --- | --- | --- |
| Writes (POST/PUT/PATCH/DELETE) of the organization - web, carrier portal, partner API | `423 tenant-suspended` | allowed |
| Reads and exports (GET/HEAD/OPTIONS) | allowed | allowed |
| Login, `/api/v1/me`, `/api/v1/companies/current` | allowed; they carry the state for the banner | allowed |
| MasterAdmin channel (`/internal/platform-provisioning/**`: tenant creation, entitlements, lifecycle) | **always open** | open |
| Data | never deleted or rewritten | - |

It is **not** the entitlements channel. Under D-14 ruling 1, `appActive=false` withdraws the
commercial surface and never stops operation (ADR-017); this is the separate operational policy that
amendment left open. It is not RBAC either: no role lifts a suspension.

## Routes

Same prefix, security chain, ES256 key, issuer/audience/subject and switch as provisioning and
entitlements (`MASTERADMIN_GENERIC_CONTRACT.md`, `ENTITLEMENTS.md`).

| Method and path | Scope | Answer |
| --- | --- | --- |
| `PUT /internal/platform-provisioning/tenants/{controlPlaneTenantId}/lifecycle` | `tms:lifecycle:write` | `200 {controlPlaneTenantId, productCode, appliedVersion, state, reason, appliedAt, replayed}` |
| `GET /internal/platform-provisioning/tenants/{controlPlaneTenantId}/lifecycle` | `tms:lifecycle:read` | `200 {controlPlaneTenantId, productCode, appliedVersion, state, reason, appliedAt}`; never set: `appliedVersion: null, state: ACTIVE` |

Headers: `Authorization: Bearer <jwt>`, `Content-Type: application/json`,
`X-MasterAdmin-Contract: lifecycle.v1` (optional; any other value is refused), `X-Correlation-Id`,
`Idempotency-Key` (informational - idempotency is by version and state).

Body (`lifecycle.v1` section 3): `contract`, `controlPlaneTenantId` (= path), `productCode` (`tms`),
`environment`, `version` (integer >= 1), `state` (`ACTIVE`|`SUSPENDED`), `reason`
(`NON_PAYMENT`|`MANUAL`|`TERMINATED` with `SUSPENDED`, `null` with `ACTIVE`), `effectiveAt`
(ISO-8601 with offset; stored, informative - TMS applies on receipt), `idempotencyKey`,
`correlationId` (<= 200 chars, stored for audit). Unknown fields, duplicated keys, wrong types: 400.

Version rules (one transaction, per-tenant advisory lock, every outcome audited):

| Case | Answer |
| --- | --- |
| no row, or higher version | applied, `200 replayed:false` |
| same version, same state and reason | `200 replayed:true`, nothing changes |
| same version, other state or reason | `409 {error: VERSION_CONFLICT, appliedVersion}` |
| lower version | `409 {error: STALE_LIFECYCLE, appliedVersion}` |

Errors: `{error, message, appliedVersion?}` with the contract's stable codes, never the request.
`400 INVALID_LIFECYCLE` (shape, contract header, non-JSON Content-Type, body tenant != path),
`400 PRODUCT_MISMATCH`, `400 WRONG_ENVIRONMENT`, `401 UNAUTHENTICATED` (missing or invalid token),
`401 JTI_REPLAYED`, `403 INSUFFICIENT_SCOPE`, `404 TENANT_NOT_PROVISIONED`, `409 STALE_LIFECYCLE` /
`VERSION_CONFLICT`, `503 M2M_NOT_CONFIGURED` (surface switched off).

Scopes are separate from provisioning and entitlements: a provisioning or entitlements token gets
`403 INSUFFICIENT_SCOPE` here, and a lifecycle token cannot create a tenant or write entitlements.
Any other method on the route is denied by the chain (`anyRequest().denyAll()`).

## Configuration

No new variable. The receiver reuses:

| Variable | Use |
| --- | --- |
| `TMS_PLATFORM_M2M_ENABLED`, `TMS_PLATFORM_M2M_PUBLIC_KEY`, `TMS_PLATFORM_M2M_ISSUER` / `AUDIENCE` / `SUBJECT` / `MAX_TOKEN_LIFETIME` / `CLOCK_SKEW` | the MasterAdmin chain, as for provisioning and entitlements |
| `TMS_ENTITLEMENTS_ENVIRONMENT` (`DEV`/`QAS`/`DEMO`/`PRD`) | the environment this deployment answers for, on every MasterAdmin channel. Empty refuses every PUT with `WRONG_ENVIRONMENT` |

The scopes are constants (`PlatformScopes.LIFECYCLE_WRITE|READ` = `tms:lifecycle:write|read`), like
every other TMS platform scope: TMS has no per-scope variable, and MasterAdmin is configured to match.

MasterAdmin integration for TMS (base URL `https://<tms-api>/internal/platform-provisioning`, the
same base as `entitlements_path=/tenants/{controlPlaneTenantId}/entitlements`):

```
lifecycle_path        = /tenants/{controlPlaneTenantId}/lifecycle
lifecycle_write_scope = tms:lifecycle:write
lifecycle_read_scope  = tms:lifecycle:read
```

## Storage (V58)

| Table / function | Content |
| --- | --- |
| `tms.platform_tenant_lifecycle` | one row per MasterAdmin tenant: `organization_id`, `state`, `reason`, `applied_version`, `applied_at`, `effective_at`, idempotency key, correlation id, M2M subject and `jti`. No row = ACTIVE. FK to V51's provisioning row and to the organization. A guard trigger: the version only moves forward, tenant keys are immutable, and the row is **never deleted** (that would silently reactivate) |
| `tms.platform_tenant_lifecycle_audit` | append-only trail of every PUT outcome (`APPLIED`, `REPLAYED`, `STALE_LIFECYCLE`, `VERSION_CONFLICT`) |
| `tms.tenant_lifecycle_current_company()` | `SECURITY DEFINER`, no parameter, keyed on `tms.current_company_id()`: the state of the caller's OWN organization. Used by the filter on `tms_app` |
| `tms.tenant_lifecycle_of_organizations(uuid[])` | `SECURITY DEFINER`: state/reason/time of the given organizations, for `/me`. Java passes only the caller's own organizations |

Exposure as V51/V56: RLS enabled, deny-all policy for `tms_app`, every privilege revoked from
`tms_app`, `anon`, `authenticated` and `service_role`; both functions `EXECUTE` for `tms_app` only,
pinned `search_path`. The receiver writes on the owner connection (the M2M caller is never
company-scoped). The single-use `jti` store is V56's `platform_entitlement_jti`, shared on purpose:
a token spent on one MasterAdmin contract route cannot be replayed on another.

`tms.organization.active` / `tms.company.active` are **not** used: they remove the company from the
caller's scope altogether (no reading, no `/me` entry), which is not what a suspension is.

## Enforcement

`shared.security.TenantSuspensionFilter`, registered in both tenant chains right after the company
scope is bound (`SecurityConfig` after `CompanyScopeFilter`; `IntegrationSecurityConfig` after
`IntegrationAuthenticationFilter`), behind the port `shared.security.TenantOperationGate`
(implemented by `iam.lifecycle.application.TenantLifecycleService`):

- GET/HEAD/OPTIONS pass without consulting the gate;
- an unscoped request passes (every business write needs a company scope:
  `TenantSuspensionCoverageTest` fails if a write route under `/api/v1` takes no `CompanyScope`, or if a
  write route lives outside `/api/v1`, `/integration` and the MasterAdmin prefix);
- otherwise a suspended organization gets `423` `application/problem+json` with
  `code: tenant-suspended`, `error: TENANT_SUSPENDED` and `reason`;
- a gate that cannot answer refuses the write (fail closed).

Separate from `CommercialAccessFilter` (D-14): the commercial gate still never refuses on a
commercial fact.

No database guard. Every TMS write goes React -> Spring Boot -> PostgreSQL (ADR-001) and the web
never writes through Supabase, so the contract's Java rule (one filter after authentication) is the
whole enforcement; there is no business-table trigger and TMS never raises SQLSTATE `PT423`. If one
is ever added (for a path that bypasses Java), it must raise `PT423` and the API must map it to the
same `423 TENANT_SUSPENDED`.

### Exemptions and what is not covered

| Path | Why |
| --- | --- |
| `/internal/platform-provisioning/**` | the channel that reactivates; it is a different chain and carries no suspension filter |
| `/actuator/health`, `GET` system info | anonymous, read-only |
| Reads, `/api/v1/me`, `/api/v1/companies/current` | the contract keeps reads and login; `/me` is where the banner comes from |
| **Webhook dispatcher** (`WebhookDispatchScheduler`, the product's only scheduled job) | not gated. It delivers to the customer's own endpoint events that were recorded **before** the suspension (no write can create new ones while suspended). Its claim query is a cross-tenant JPA `FOR UPDATE SKIP LOCKED` that could not be validated against PostgreSQL in this change (no Docker); gating it is a candidate for a follow-up |
| Carrier-portal outbox (browser) | its reports get `423`, which the outbox treats like a 5xx: they stay PENDING on the phone with backoff and go out by themselves after reactivation |

## UI (UX only)

`GET /api/v1/me` adds `operationalStatus {state, reason, appliedAt}` to each company
(`/api/v1/companies/current` too). The web shows a fixed **«Cuenta suspendida»** banner above every
screen (`AppLayout`, and the carrier `PortalLayout`); with `NON_PAYMENT` it says «regularice el pago con
su proveedor». A `423 tenant-suspended` maps to a clear message and refreshes `/me`. Write buttons
are not disabled one by one: the server refuses them with that message.

## How to test

```bash
cd backend/tms-api
./mvnw test -Dtest='PlatformLifecycleApiTest,TenantSuspensionFilterTest,TenantSuspensionSecurityTest,TenantSuspensionCoverageTest,IntegrationApiTenancyTest*'
# With Docker (Testcontainers PostGIS): V58 + JdbcLifecycleRepository against the whole Flyway history
./mvnw test -Dtest='PlatformLifecycleIntegrationTest,SchemaExposureIntegrationTest'

cd frontend/tms-web
npm run lint && npm run typecheck && npm test && npm run build
```

Certification against MasterAdmin (contract section 7): configure the integration above, suspend
from the Centro de la suite, check that a write answers `423 tenant-suspended` and the banner
appears while reads continue, then settle the debt and check that writes resume.
