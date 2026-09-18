# Platform provisioning — API contract

**Product:** TMS by EBIM
**Technology:** Java 21 / Spring Boot
**Control plane:** EBIM MasterAdmin

| | |
|---|---|
| Create endpoint | `POST /internal/platform/v1/tenants` |
| Get endpoint | `GET /internal/platform/v1/tenants/{controlPlaneTenantId}` |
| Issuer | `masteradmin.ebim` |
| Audience | `tms.ebim` |
| Algorithm | `ES256` |
| Create scope | `tms:tenant:create` |
| Read scope | `tms:tenant:read` |
| Max token lifetime (`exp - iat`) | `300` seconds |

V1 has **no** delete, suspend or activate.

## Required headers

| Header | On | Notes |
|---|---|---|
| `Authorization: Bearer <jwt>` | both | ES256, see [`SECURITY.md`](SECURITY.md) |
| `Idempotency-Key` | create | required; max 255 characters |
| `X-Correlation-Id` | both | optional. Generated if absent, echoed on the response, stored on the provisioning row |

## Create — request

```json
{
  "controlPlaneTenantId": "3f1d…-uuid",
  "organization": {
    "id": "a1b2…-uuid",
    "slug": "cliente",
    "name": "Cliente",
    "legalName": "Cliente SAC",
    "taxId": "20123456789",
    "countryCode": "PE",
    "currency": "PEN",
    "timezone": "America/Lima"
  },
  "company": {
    "id": "c3d4…-uuid",
    "name": "Cliente",
    "legalName": "Cliente SAC",
    "taxId": "20123456789",
    "countryCode": "PE",
    "currency": "PEN"
  },
  "admin": { "email": "admin@cliente.com", "fullName": "Administrador" },
  "deploymentMode": "SHARED"
}
```

`admin` is optional. `deploymentMode` must be `SHARED`.

**Product-specific behaviour worth knowing before you send this:**

- `organization.slug` becomes the TMS organization **code**: upper-cased, anything outside
  `[A-Z0-9_-]` collapsed to `-`, and it must end up matching `^[A-Z0-9][A-Z0-9_-]{1,31}$`. A slug
  that cannot survive that is refused rather than truncated.
- `organization.timezone` is stored on the **company** (`America/Lima`, IANA). Defaults to `UTC`.
- `taxId` is stored on the company; the company's value wins over the organization's.
- `legalName`, `countryCode` and `currency` are **accepted and not stored** — TMS models none of
  them. The full table is in [`DESIGN.md`](DESIGN.md) §3.
- The company's **code** is not supplied: it is the organization's code.

## Create — response

`201 Created` the first time, `200 OK` when a key is replayed.

```json
{
  "provisioningId": "…",
  "status": "ACTIVE",
  "replayed": false,
  "controlPlaneTenantId": "…",
  "externalTenantId": "…",
  "externalOrganizationId": "…",
  "externalCompanyId": "…",
  "resources": {
    "organizationCode": "CLIENTE",
    "companyCode": "CLIENTE",
    "timeZone": "America/Lima"
  },
  "adminProvisioningStatus": "PREPROVISIONED",
  "deploymentMode": "SHARED",
  "createdAt": "2026-09-18T18:35:29.400Z"
}
```

`externalTenantId` is the id of the TMS-side mapping row, not the organization's or the company's —
both of those are returned beside it. `adminProvisioningStatus` is `PREPROVISIONED`, `LINKED` or
`NOT_REQUESTED`.

## Get — response

The same document, read back from the mapping and the provisioning record. It carries **no business
data**: no orders, no routes, no vehicles, no counts. `404` when no tenant is provisioned for that
identifier.

## Errors

RFC 7807 `application/problem+json`. Every body carries `type`, `title`, `status`, `detail`, a stable
machine `code`, a `timestamp` and the `correlationId`. Stack traces, SQL and constraint names are
never included.

| `code` | HTTP | When |
|---|---|---|
| `invalid-m2m-token` | 401 | malformed, expired, unsigned, wrong algorithm, wrong key, missing `sub`/`jti`/`scope`, lifetime over the maximum |
| `invalid-issuer` | 401 | verified, but not from `masteradmin.ebim` |
| `invalid-audience` | 401 | verified, but not minted for `tms.ebim` |
| `unauthenticated` | 401 | no bearer token at all |
| `missing-scope` | 403 | authenticated, but the token grants no scope for this operation |
| `idempotency-key-required` | 400 | create without `Idempotency-Key`, or one over 255 characters |
| `idempotency-conflict` | 409 | same key, different payload |
| `tenant-already-provisioned` | 409 | that `controlPlaneTenantId` already maps to a TMS tenant |
| `tenant-conflict` | 409 | an organization or company id/code in the request is already in use |
| `admin-email-conflict` | 409 | the administrator email belongs to a user of another tenant |
| `validation-failed` | 400 | a field is missing or unusable — an unnormalisable slug, a bad time zone, a deployment mode this release does not implement |
| `provisioning-failed` | 500 | provisioning ran and could not finish. **Nothing was created**, and the same key may be retried |
| `feature-not-configured` | 503 | this deployment has no control-plane key configured |

TMS's own partner API uses `idempotency-key-reused` for the equivalent of `idempotency-conflict`.
The two surfaces are kept independent on purpose: a change to one must not silently alter the other's
published contract.

## Idempotency and retries

- Same key, same body, after success → the original response, `200`, `replayed: true`. Nothing is
  written twice.
- Same key, different body → `409 idempotency-conflict`.
- Same key, after a failure → the call is attempted again. A transient failure does not burn the key.
- Two simultaneous calls with one key → one does the work, the other replays it. Exactly one tenant,
  one mapping, one provisioning record.
- A failed call leaves **nothing** behind: no organization, no company, no user.

## QAS environment variables

| Variable | Value |
|---|---|
| `EBIM_MASTERADMIN_M2M_ENABLED` | `true` |
| `EBIM_MASTERADMIN_M2M_ISSUER` | `masteradmin.ebim` |
| `EBIM_MASTERADMIN_M2M_AUDIENCE` | `tms.ebim` |
| `EBIM_MASTERADMIN_M2M_ALGORITHM` | `ES256` |
| `EBIM_MASTERADMIN_M2M_MAX_TOKEN_LIFETIME` | `300s` |
| `EBIM_MASTERADMIN_M2M_CREATE_SCOPE` | `tms:tenant:create` |
| `EBIM_MASTERADMIN_M2M_READ_SCOPE` | `tms:tenant:read` |
| `EBIM_MASTERADMIN_M2M_PUBLIC_KEY_B64` | base64 of the public PEM — **secret/env, never a file path** |
| `TMS_PLATFORM_RETAIN_REQUEST_SNAPSHOT` | `false` unless retention is wanted |

The public key is configured as a value, not a path: `C:\EBIM\keys\...` is a local convenience for
generating the base64 and has no meaning on a server.
