# TMS - EBIM MasterAdmin provisioning (GENERIC contract v1)

How EBIM MasterAdmin (the suite control plane, product code `tms`) creates a TMS tenant.
Decision record: [ADR-012](../architecture/ADR-012-masteradmin-platform-provisioning.md).
Migration: `V51__platform_provisioning.sql`. Code: `com.ebim.tms.iam.provisioning`.

TMS speaks MasterAdmin's **GENERIC** contract (MasterAdmin `adapter_key = GENERIC`), the suite
standard. It does not speak EWM's `EWM_V1`.

## 1. Endpoints

Served by the existing Spring Boot backend (`tms-api`) - no new service.

| Method | Path | Auth | Scope |
| --- | --- | --- | --- |
| `GET` | `/internal/platform-provisioning/health` | none | - |
| `POST` | `/internal/platform-provisioning/tenants` | ES256 M2M JWT | `tms:tenant:create` |
| `GET` | `/internal/platform-provisioning/tenants/{controlPlaneTenantId}` | ES256 M2M JWT | `tms:tenant:read` |

The prefix has its own security chain (`PlatformProvisioningSecurityConfig`, order
`HIGHEST_PRECEDENCE + 2`). A Supabase user token or a partner integration credential is refused
there, and a MasterAdmin token is refused everywhere else. No CORS. Any other method under the
prefix is denied.

**Health.** `200 {"status":"ok"}` when provisioning is enabled (the public key was parsed at
start-up; a bad key stops the application), `503 {"status":"unavailable"}` when it is disabled.
Fixed body, no configuration, a bearer token sent to it is ignored.

**Disabled (default).** `TMS_PLATFORM_M2M_ENABLED=false`: the chain is still registered, health
answers 503 and every other call answers `503 M2M_NOT_CONFIGURED`.

## 2. M2M token

| Rule | Value |
| --- | --- |
| Algorithm | **ES256 only**, pinned. HS256, RS256 and `none` are refused before any key is used. `kid` is ignored (one configured key). |
| Key | MasterAdmin's P-256 **public** key, PEM SPKI, from `TMS_PLATFORM_M2M_PUBLIC_KEY`. A value containing `PRIVATE KEY` stops start-up. |
| `iss` | `masteradmin.ebim` (`TMS_PLATFORM_M2M_ISSUER`) |
| `aud` | `tms.ebim` (`TMS_PLATFORM_M2M_AUDIENCE`) |
| `sub` | `masteradmin-provisioning` (`TMS_PLATFORM_M2M_SUBJECT`), exact |
| `iat`, `exp` | both required; `iat` not in the future beyond the skew; `exp - iat <= 300 s` (configurable lower, never higher); expired refused |
| `jti` | required, recorded in the audit trail. Not single-use: MasterAdmin reuses one token for its own transport retries of one call; replays of the operation are handled by `Idempotency-Key`. |
| `scope` | required; space-separated string or JSON array; `tms:tenant:create` / `tms:tenant:read` per operation |
| `actor_id`, `actor_role`, `correlation_id` | recorded for audit only. **Never** used for authorization. |
| Clock skew | 30 s (`TMS_PLATFORM_M2M_CLOCK_SKEW`, max 60 s) |

Audience `tms.ebim`: the suite names audiences `<product>.ebim` (`ewm.ebim`, `esupplier.ebim`) and
TMS is registered in MasterAdmin under product code `tms`; TMS had no earlier audience to keep.
Scopes follow the same `<product>:tenant:<verb>` pattern.

## 3. Request (`POST /tenants`)

Headers: `Authorization: Bearer <jwt>`, `Content-Type: application/json`,
`Idempotency-Key` (required, `^[A-Za-z0-9._:-]{8,200}$`; MasterAdmin sends `ma-prov-v<n>-<sha256>`),
`X-Correlation-Id` (echoed, sanitized), `X-MasterAdmin-Contract` (optional; if present must be `v1`).

### Field table

REQUIRED = rejected with 400 when absent/invalid. OPTIONAL = used when present. IGNORED = accepted,
recorded or hashed, no effect on TMS data. UNSUPPORTED = no TMS concept; accepted and ignored.

| GENERIC field | Status | TMS mapping / rule |
| --- | --- | --- |
| `masterAdmin.tenantId` | REQUIRED | UUID. **controlPlaneTenantId**. Unique in `tms.platform_provisioning_request`; one TMS tenant per MasterAdmin tenant. |
| `masterAdmin.productCode` | REQUIRED | must be `tms` |
| `masterAdmin.contractVersion` | OPTIONAL | if present must be `v1` |
| `masterAdmin.requestId`, `masterAdmin.correlationId` | IGNORED | not hashed (volatile per request) |
| `tenantCode` | REQUIRED | upper-cased -> `tms.organization.code`; must match `^[A-Z0-9][A-Z0-9_-]{1,31}$` (max 32 chars). Refused, not truncated. |
| `tenantName` | OPTIONAL | fallback for the organization name |
| `adminEmail` | REQUIRED | lower-cased -> `tms.app_user.email`. Existing **active** profile is reused; a **deactivated** one is `409 ADMIN_EMAIL_CONFLICT`. |
| `organization` | REQUIRED (object) | |
| `organization.displayName` | REQUIRED* | -> `tms.organization.name` (*or `legalName`, or `tenantName`) |
| `organization.legalName` | OPTIONAL | name fallback; hashed |
| `organization.code` | IGNORED | MasterAdmin's organization slug; `tenantCode` is the TMS key (one organization slug may hold several product tenants) |
| `organization.countryCode` | OPTIONAL | ISO alpha-2; company country fallback |
| `organization.taxId` | OPTIONAL | company `tax_identifier` when `company` is null |
| `company` | OPTIONAL (object or null) | null -> a default company is created (code = tenant code, name = organization name) |
| `company.code` | OPTIONAL | upper-cased -> `tms.company.code` (same shape rule); default = tenant code |
| `company.name` | OPTIONAL | -> `tms.company.name`; default = organization name |
| `company.countryCode` | OPTIONAL | ISO alpha-2 -> `tms.company_settings.default_country`, and the company time zone (see below) |
| `company.taxId` | OPTIONAL | -> `tms.company.tax_identifier` |
| `company.currency` | UNSUPPORTED | validated (ISO 4217 shape) and hashed; TMS companies have no currency (rate cards carry their own) |
| `tenantType`, `environment`, `deploymentMode` | IGNORED | recorded on the provisioning row |
| `plan.code`, `plan.name` | UNSUPPORTED | TMS has no plans/licensing; `plan.code` recorded |

Derived, not sent by MasterAdmin (and therefore not hashed):

- **Company time zone** (`tms.company.time_zone`): the country's IANA zone when it has exactly one
  (PE, CO, BO, PY, UY, VE, PA, CR, GT, SV, HN, NI, DO), otherwise `UTC`. Reported in
  `resources.companyTimeZone`; an administrator can change it on the company settings screen.
- **Administrator display name**: the mailbox part of `adminEmail` (`full_name` is mandatory).

### What one successful call creates (one transaction)

`tms.organization` + `tms.company` + `tms.company_settings` (if a country was given) +
`tms.app_user` (unless reused) + organization-wide `tms.membership` (`company_id NULL`) +
`tms.membership_role` `ORGANIZATION_ADMIN` + `tms.platform_provisioning_request` + a `CREATED` row in
`tms.platform_provisioning_audit`. Any failure rolls all of it back.

No demo data, no master data, no Supabase Auth user.

### AUTH_BOOTSTRAP = PREPROVISIONED

`app_user.auth_user_id` stays NULL. The administrator cannot sign in until an operator creates or
invites the Supabase Auth account and links it (today: the same `UPDATE ... FROM auth.users` by
email that `supabase/seeds/qas_seed.sql` uses). `resources.adminStatus` is `PREPROVISIONED` until
then and `ACTIVE` afterwards (read live on GET).

## 4. Response

`201` first create, `200` replay, `200` GET. Body:

```json
{
  "status": "ACTIVE",
  "controlPlaneTenantId": "50000000-0000-4000-a000-00000000000a",
  "externalTenantId": "<tms.organization.id>",
  "externalOrganizationId": "<tms.organization.id>",
  "externalCompanyId": "<tms.company.id>",
  "resources": {
    "organizationCode": "ALPHA-TMS",
    "companyCode": "ALPHA-01",
    "companyTimeZone": "America/Lima",
    "adminStatus": "PREPROVISIONED",
    "adminProfileReused": false,
    "organizationActive": true,
    "companyActive": true
  },
  "rawReference": "<tms.platform_provisioning_request.id>",
  "provisionedAt": "2026-09-22T06:00:00Z",
  "replayed": false,
  "correlationId": "..."
}
```

- `status` is always `ACTIVE`: provisioning is synchronous and the rows are committed. It says
  nothing about the administrator's login (`resources.adminStatus`).
- `externalTenantId` = TMS **organization** id, because the organization is the tenant boundary
  (ADR-003). Repeated as `externalOrganizationId`.
- `resources` holds flat scalars only (MasterAdmin keeps up to 25, no objects).
- `replayed` is absent on GET.

## 5. Idempotency and conflicts

Hash = SHA-256 of a fixed-order encoding of the normalized functional fields (`ProvisionTenantCommand`),
excluding the key, correlation id, `requestId`, the token and derived values.

| Situation | Answer |
| --- | --- |
| New key, new tenant | `201`, created |
| Same key, same hash | `200 replayed:true`, same ids, nothing written but a `REPLAYED` audit row |
| Same key, different hash | `409 IDEMPOTENCY_CONFLICT` |
| Other key, tenant already provisioned, same hash | `409 TENANT_ALREADY_PROVISIONED` |
| Other key, tenant already provisioned, different hash | `409 TENANT_CONFLICT` |
| Organization code already exists in TMS (not provisioned) | `409 TENANT_CONFLICT` (never adopted) |
| Admin email on a deactivated profile | `409 ADMIN_EMAIL_CONFLICT` |

Concurrency: `pg_advisory_xact_lock` on the key then on the tenant; unique indexes as the backstop
(a lost race answers `409 TENANT_CONFLICT`, nothing kept).

## 6. Error codes

Body: `{"code","message","error":{"code","message","details":[{"field","issue"}]},"correlationId"}`
(`details` only for 400). Same vocabulary as eSupplier's GENERIC implementation.

| Code | HTTP |
| --- | --- |
| `UNAUTHENTICATED` | 401 (no token) |
| `INVALID_M2M_TOKEN` | 401 (signature, alg, iss, aud, sub, exp, lifetime, jti, no scope - never says which) |
| `MISSING_SCOPE` | 403 |
| `ACCESS_DENIED` | 403 (method not offered under the prefix) |
| `IDEMPOTENCY_KEY_REQUIRED`, `INVALID_IDEMPOTENCY_KEY`, `INVALID_REQUEST` | 400 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 |
| `PROVISIONING_NOT_FOUND` | 404 |
| `IDEMPOTENCY_CONFLICT`, `TENANT_ALREADY_PROVISIONED`, `TENANT_CONFLICT`, `ADMIN_EMAIL_CONFLICT` | 409 |
| `PROVISIONING_FAILED` | 500 (rolled back; MasterAdmin may retry) |
| `M2M_NOT_CONFIGURED` | 503 |

## 7. Audit

`tms.platform_provisioning_audit`, append-only (trigger refuses UPDATE/DELETE, even to the owner):
operation, result (`CREATED`/`REPLAYED`/`FOUND`/`REJECTED`/`CONFLICT`/`ERROR`), HTTP status, code,
controlPlaneTenantId, Idempotency-Key, provisioning id, correlation id, `sub`, `jti`, `actor_id`,
`actor_role`. Rejections are written in their own transaction, after the provisioning one rolled
back. Never stored: the token, the Authorization header, the body. Token-level rejections (401/403
raised by the security chain) are not written to the table; they are visible in the logs.

Both V51 tables: RLS enabled, deny-all policy for `tms_app`, no grants to `tms_app` or the Supabase
API roles. Only the backend's owning connection reads or writes them.

## 8. Configuration (names only)

| Variable | Default | Secret |
| --- | --- | --- |
| `TMS_PLATFORM_M2M_ENABLED` | `false` | no |
| `TMS_PLATFORM_M2M_PUBLIC_KEY` | - | not secret, but deployment-managed (secret store) |
| `TMS_PLATFORM_M2M_ISSUER` | `masteradmin.ebim` | no |
| `TMS_PLATFORM_M2M_AUDIENCE` | `tms.ebim` | no |
| `TMS_PLATFORM_M2M_SUBJECT` | `masteradmin-provisioning` | no |
| `TMS_PLATFORM_M2M_MAX_TOKEN_LIFETIME` | `300s` | no |
| `TMS_PLATFORM_M2M_CLOCK_SKEW` | `30s` | no |

The private key never exists on the TMS side. On the MasterAdmin side it is a server secret
(for example `TMS_QAS_M2M_PRIVATE_KEY`), referenced by name from the credential profile.

## 9. MasterAdmin integration row (to configure later - not done)

| MasterAdmin field | Value |
| --- | --- |
| product | `tms` (`20000000-0000-4000-a000-000000000003`) |
| integration type / `adapter_key` | `HTTP_M2M` / `GENERIC` |
| `base_url` | the TMS API origin of the environment (e.g. `https://<tms-api-qas-host>`) |
| `create_path_template` | `/internal/platform-provisioning/tenants` |
| `status_path_template` | `/internal/platform-provisioning/tenants/{controlPlaneTenantId}` |
| `health_path_template` | `/internal/platform-provisioning/health` |
| `allowed_hosts` | the TMS API host only |
| `contract_version` | `v1` |
| issuer / audience / subject | `masteradmin.ebim` / `tms.ebim` / `masteradmin-provisioning` |
| algorithm / TTL | `ES256` / `120` s |
| `create_scope` / `read_scope` | `tms:tenant:create` / `tms:tenant:read` |
| credential `secret_ref` | name of the P-256 private key secret in MasterAdmin (PKCS#8 PEM) |

MasterAdmin's GENERIC codec currently uses only `PROVISION`; the status endpoint is ready for when
GENERIC gains `GET_STATUS`.

## 10. Rollout checklist (human-authorized, per environment)

1. Generate a P-256 key pair for the environment; the private key goes to MasterAdmin's secret
   store only; the public key to `TMS_PLATFORM_M2M_PUBLIC_KEY`.
2. Deploy a build containing V51 (Flyway applies it at start-up).
3. Set `TMS_PLATFORM_M2M_ENABLED=true`; confirm `GET /internal/platform-provisioning/health` is 200.
4. Configure the MasterAdmin integration row (section 9); run its health check and a GET_STATUS on a
   not-yet-provisioned tenant (`404 PROVISIONING_NOT_FOUND` proves signature, iss, aud and scope).
5. Provision one test tenant; link the administrator's Auth account; verify sign-in.
