# TMS - MasterAdmin tenant inventory and tenant link (`tenant-inventory.v1`)

Receiver of EBIM MasterAdmin's tenant inventory (list) and tenant link ("adopt an existing tenant").
Normative contract: MasterAdmin `docs/platform-provisioning/TENANT_INVENTORY_CONTRACT.md` (sections 3-5
for the list, section 8 for the link). Migration: V76.

## Why

MasterAdmin only knows the tenants it created. Organizations that existed in TMS before the control
plane (QAS, demo customers) are invisible to it, and when one is "registered manually" in MasterAdmin it
has no provisioning row here - so the status GET, `entitlements.v1` and `lifecycle.v1` answer 404 for it.
The list makes them visible; the link gives them the provisioning row they lack, without touching them.

## Routes

Same prefix, security chain, ES256 key, issuer/audience/subject, switch (`TMS_PLATFORM_M2M_ENABLED`) and
environment (`TMS_ENTITLEMENTS_ENVIRONMENT`) as provisioning, entitlements and lifecycle.

| Method and path | Scope (configuration) | Answer |
| --- | --- | --- |
| `GET /internal/platform-provisioning/tenants?limit=1..500&cursor=<opaque>` | `TMS_PLATFORM_M2M_INVENTORY_READ_SCOPE` (`tms:tenants:list`) | `200 {contract, productCode, environment, items[], nextCursor}` |
| `PUT /internal/platform-provisioning/tenants/{controlPlaneTenantId}/link` | `TMS_PLATFORM_M2M_TENANT_LINK_SCOPE` (`tms:tenants:link`) | `200 {contract, productCode, environment, controlPlaneTenantId, externalTenantId, status: LINKED\|ALREADY_LINKED}` |

`GET /tenants` (list), `GET /tenants/{id}` (status) and `POST /tenants` (create) are three different
matchers of the chain; none of them overlaps another.

### Scopes are configuration, and unset means closed

Unlike `tms:tenant:*`, `tms:entitlements:*` and `tms:lifecycle:*` (fixed in `PlatformScopes`), the two
scopes come from `tms.platform-inventory.read-scope` / `link-scope`:

| Variable | Also accepted (suite-wide name) | Intended value |
| --- | --- | --- |
| `TMS_PLATFORM_M2M_INVENTORY_READ_SCOPE` | `EBIM_MASTERADMIN_M2M_INVENTORY_READ_SCOPE` | `tms:tenants:list` |
| `TMS_PLATFORM_M2M_TENANT_LINK_SCOPE` | `EBIM_MASTERADMIN_M2M_TENANT_LINK_SCOPE` | `tms:tenants:link` |

- Unset (the default): the route answers `503 {"error":"INVENTORY_NOT_CONFIGURED"}` /
  `503 {"error":"TENANT_LINK_NOT_CONFIGURED"}` to every authenticated MasterAdmin token, and `401`
  without one. The same happens when `TMS_ENTITLEMENTS_ENVIRONMENT` is not one of DEV/QAS/DEMO/PRD.
- A value that is not a single scope, that is one of the fixed TMS scopes, or that both routes share is
  **not used** (the route stays closed) and is logged as an error at start-up - it never stops the
  application. Listing, adopting and creating are separate grants.

## List

One item per `tms.organization` - the tenant boundary (ADR-003) - including demo, smoke and internal ones.
Keyset pagination by organization id; `limit` defaults to 200; `nextCursor` is opaque (base64url) and
`null` on the last page.

| Field | Value |
| --- | --- |
| `externalTenantId` | organization id - the same value `POST /tenants` returns as `externalTenantId` |
| `externalOrganizationId` | organization id (as on create) |
| `externalCompanyId` | the company of its provisioning row; otherwise its **oldest** company (what a link records, so it does not change on linking); `null` when it has none |
| `controlPlaneTenantId` | the MasterAdmin tenant of its provisioning row (created or linked), else `null` |
| `code`, `name` | organization code and name |
| `status` | `INACTIVE` (organization deactivated) > `SUSPENDED` (lifecycle.v1 applied) > `ACTIVE` |

Identity and state only: no users, emails, tax ids, amounts or counts.

## Link

Body: `{"externalTenantId":"<organization id from the list>","requestId":"<uuid>"}`; optional
`controlPlaneTenantId` (must equal the path) and `productCode` (must be `tms`); other fields ignored.
Headers as for lifecycle (`X-MasterAdmin-Contract: tenant-inventory.v1` optional, any other value refused).

Effect: one row in `tms.platform_provisioning_request` with `origin = 'LINKED'` (V76), the organization's
oldest company, no administrator columns, `idempotency_key = 'link:<controlPlaneTenantId>'`. Nothing else:
no organization, company, user, membership or role is created or changed. After it, `GET /tenants/{id}`
(with `resources.linked: true` instead of `adminStatus`), entitlements and lifecycle resolve the tenant
exactly as one MasterAdmin created. It takes the same advisory lock as tenant creation for that
`controlPlaneTenantId`.

| Case | Answer |
| --- | --- |
| first time | `200 status: LINKED` |
| same pair again (also a tenant MasterAdmin created, with its own pair) | `200 status: ALREADY_LINKED` |
| organization mapped to another `controlPlaneTenantId`, or `controlPlaneTenantId` mapped to another organization | `409 LINK_CONFLICT` |
| `externalTenantId` is not an organization of this deployment | `404 EXTERNAL_TENANT_NOT_FOUND` |
| bad body, path not a UUID, `requestId` not a UUID, non-JSON | `400 INVALID_LINK_REQUEST` |

## Errors

`{error, message}` like entitlements and lifecycle; the message never echoes the request.

| HTTP | `error` |
| --- | --- |
| 400 | `INVALID_INVENTORY_QUERY`, `INVALID_LINK_REQUEST` |
| 401 | `UNAUTHENTICATED`, `JTI_REPLAYED` (the `jti` is single-use, shared with entitlements/lifecycle) |
| 403 | `INSUFFICIENT_SCOPE` |
| 404 | `EXTERNAL_TENANT_NOT_FOUND` |
| 409 | `LINK_CONFLICT` |
| 500 | `INVENTORY_FAILED`, `TENANT_LINK_FAILED` |
| 503 | `INVENTORY_NOT_CONFIGURED`, `TENANT_LINK_NOT_CONFIGURED`, `M2M_NOT_CONFIGURED` |

## Audit

`tms.platform_provisioning_audit`, operations `LIST_TENANTS` (`FOUND`, `REJECTED`, `ERROR`) and
`LINK_TENANT` (`LINKED`, `ALREADY_LINKED`, `REJECTED`, `CONFLICT`, `ERROR`); for a link, `idempotency_key`
holds MasterAdmin's `requestId`. Never the token or the body.

## Operator steps when this reaches `qas`

1. Flyway applies V76 on start-up (additive: one column, relaxed NOT NULLs guarded by a check, two
   widened audit checks).
2. Render (tms-api): set `TMS_PLATFORM_M2M_INVENTORY_READ_SCOPE=tms:tenants:list` and
   `TMS_PLATFORM_M2M_TENANT_LINK_SCOPE=tms:tenants:link`. `TMS_ENTITLEMENTS_ENVIRONMENT` must already be `QAS`.
3. MasterAdmin: `select platform.configure_inventory_integration('<tms integration>', '/tenants', 'tms:tenants:list');`
   and the link scope where §8 configures it.
4. "Sincronizar ahora": the TMS row shows "Inventario: N clientes".

## Code

`iam/inventory` (`PlatformTenantInventoryController`, `PlatformTenantInventoryService`,
`JdbcTenantInventoryRepository`), `iam/provisioning/security/PlatformInventoryProperties` and
`PlatformInventoryAccess`. Tests: `PlatformTenantInventoryApiTest`, `PlatformTenantInventoryNotConfiguredApiTest`,
`PlatformTenantInventoryServiceTest`, `PlatformTenantInventoryIntegrationTest` (Docker).
