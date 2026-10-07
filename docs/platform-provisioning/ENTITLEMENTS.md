# TMS - MasterAdmin commercial entitlements (`ebim.entitlements/v1`)

Receiver of the EBIM Commercial Control Plane snapshot. Decision record: ADR-017. Normative
contract: FIX-ENT-v1, vendored and pinned in
`backend/tms-api/src/test/resources/contracts/entitlements-v1/` (`CHECKSUMS.sha256` SHA-256
`7aab413a145b0e9a165c5f02be4bfda17f886a4b46bc2a557eec3eeaed1f65d5`).

## Routes

Same prefix, security chain, ES256 key and switch as provisioning (`MASTERADMIN_GENERIC_CONTRACT.md`).

| Method and path | Scope | Answer |
| --- | --- | --- |
| `PUT /internal/platform-provisioning/tenants/{controlPlaneTenantId}/entitlements` | `tms:entitlements:write` | `200 {appliedVersion, appliedChecksum, appliedAt, status, unknownCapabilities, replayed}` |
| `GET /internal/platform-provisioning/tenants/{controlPlaneTenantId}/entitlements` | `tms:entitlements:read` | `200 {controlPlaneTenantId, productCode, appliedVersion, appliedChecksum, appliedAt, status, unknownCapabilities, enforcementMode}` |
| `GET /internal/platform-provisioning/entitlements/manifest` | `tms:entitlements:read` | `ENTITLEMENTS_MANIFEST.json`, byte for byte |

Headers: `Content-Type: application/json`, `X-MasterAdmin-Contract: entitlements.v1`,
`X-Correlation-Id`, `Idempotency-Key` (= `snapshot.idempotencyKey`; informational - idempotency is
by version and checksum). A provisioning-only token gets `403 INSUFFICIENT_SCOPE`; an entitlements
token cannot create a tenant.

Errors: `{error, message, appliedVersion?}`, never the snapshot. `401 UNAUTHENTICATED` (missing or
invalid token), `401 JTI_REPLAYED` (a `jti` is accepted once on these routes), `403
INSUFFICIENT_SCOPE`, `404 TENANT_NOT_PROVISIONED`, `409 STALE_SNAPSHOT` / `VERSION_CONFLICT`
(with `appliedVersion`), `413 SNAPSHOT_TOO_LARGE` (canonical > 64 KB), `415
UNSUPPORTED_MEDIA_TYPE`, `422 SNAPSHOT_INVALID` / `ENVIRONMENT_MISMATCH` / `CHECKSUM_MISMATCH` /
`UNSUPPORTED_CONTRACT_VERSION`, `503 M2M_NOT_CONFIGURED` (surface switched off).

## Mapping

- `controlPlaneTenantId` -> `tms.platform_provisioning_request` (V51) -> TMS **organization** (the
  tenant, ADR-003). A tenant without that row is `404`.
- `appActive` -> the commercial SaaS surface of the organization (see below). It never withholds
  operation (D-14 ruling 1, 2026-09-29).
- `capabilities[]`, `limits[]`, `allowances[]`: TMS registers **none** (manifest: `tms.core`
  baseline only). Any code received is stored, listed in `unknownCapabilities`, status
  `APPLIED_WITH_WARNINGS`, and never granted.
- `planCode`, `external`, `sources`, `aiCredits`: stored with the snapshot, not acted on.

## Enforcement

`tms.platform_entitlement_mode` (product row `PRODUCT`, optional row per `controlPlaneTenantId`).

| Mode | Commercial surface of an organization provisioned by MasterAdmin | Operation |
| --- | --- | --- |
| `LEGACY` | legacy (snapshot ignored) | allowed |
| `SHADOW` (seeded by V56) | legacy; `appActive=false` recorded in `platform_entitlement_shadow_diff` | allowed |
| `DUAL_READ` | snapshot decides; no snapshot yet -> legacy, warning logged | allowed |
| `PRIMARY` | snapshot decides; no snapshot -> nothing commercial granted | allowed |

**D-14 ruling 1 (2026-09-29): `appActive=false` withdraws the commercial SaaS capabilities, not the
application.** When the deciding snapshot says `false` (reason `APP_INACTIVE`),
`CommercialEntitlementService.capabilityEnabled` is `false` and `limit` is empty for every sellable
code. Company-scoped requests keep working. The baseline `tms.core` is not commercial, and nothing
operational is gated on it. A full operational shutdown is a separate, explicit policy that does
not exist yet:
- `CommercialAccessFilter` (after `CompanyScopeFilter` and `IntegrationAuthenticationFilter`) is the
  hook it would use; today the production gate never refuses.
- The `403 commercial-access-suspended` problem type and its web copy remain for that policy.
- The checksum-pinned FIX-ENT-v1 README still says "bloquea el acceso operativo"; ADR-017's
  amendment records the TMS reading.

Organizations MasterAdmin never provisioned resolve `NOT_UNDER_CONTROL_PLANE` in every mode, so a
product-wide `PRIMARY` does not change them. MasterAdmin is never called: if it is down, the last
applied snapshot keeps deciding, without expiry.

## Operator (not executed by the program)

Before GATE C nothing is configured anywhere. After it, per environment:

1. `TMS_ENTITLEMENTS_ENVIRONMENT=DEV|QAS` (empty refuses every snapshot).
2. The provisioning surface must already be on (`TMS_PLATFORM_M2M_ENABLED=true`,
   `TMS_PLATFORM_M2M_PUBLIC_KEY`).
3. In MasterAdmin, the TMS integration: `entitlements_path=/tenants/{controlPlaneTenantId}/entitlements`,
   `entitlements_manifest_path=/entitlements/manifest`, scopes `tms:entitlements:write` /
   `tms:entitlements:read`.
4. Apply V56 through Flyway (the backend at start-up) - never by hand.
5. Mode changes, only after the cut-over review (D-14; ruling 2, 2026-09-29, allows `PRIMARY` in DEV
   once parity and security are green), one step at a time:

```sql
-- product-wide
UPDATE tms.platform_entitlement_mode
   SET mode = 'DUAL_READ', reason = '<ticket / decision>', updated_by = '<operator>', updated_at = now()
 WHERE scope_key = 'PRODUCT';
-- or one tenant (starts from the product's mode)
INSERT INTO tms.platform_entitlement_mode (scope_key, mode, reason, updated_by)
VALUES ('<controlPlaneTenantId>', 'DUAL_READ', '<ticket / decision>', '<operator>');
```

A jump of more than one step, or a `DELETE`, is refused by trigger; every change is recorded in
`tms.platform_entitlement_mode_event`. Rolling back is the same statement one step down.
