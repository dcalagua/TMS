# ADR-021 - Operational suspension of a tenant by EBIM MasterAdmin (`lifecycle.v1`)

- Status: Accepted (local implementation on `dev`; not deployed)
- Date: 2026-10-13
- Relates to: ADR-001 (React -> Spring Boot -> PostgreSQL), ADR-003 (organization/company tenancy),
  ADR-005 (tenant RLS runtime role), ADR-012 (MasterAdmin provisioning), ADR-017 and its D-14
  amendment (commercial entitlements), `docs/platform-provisioning/LIFECYCLE.md`, MasterAdmin
  `docs/platform-provisioning/LIFECYCLE_CONTRACT.md`.
- Deviates from: `API_CONTRACT.md`/`DESIGN.md` section 9 ("no suspend, no activate") and ADR-017's
  "a full operational shutdown is a separate policy that does not exist yet". This ADR is that policy.

## Context

MasterAdmin suspends a customer for non-payment (or manually, or at the end of a contract) and
reactivates it when the debt is settled. D-14 ruling 1 (2026-09-29) fixed that `appActive=false` in
the entitlements snapshot withdraws the commercial surface only - trucks keep leaving - and left "the
full operational shutdown" as a separate, explicit policy. MasterAdmin now sends it on a channel of its
own, `lifecycle.v1`: declarative, versioned per tenant, with its own scopes, `ACTIVE` or `SUSPENDED`
with a reason.

`DESIGN.md` section 9 had refused suspend/activate because "suspending one means deciding what happens
to a trip already on the road". The decision below makes that call explicitly.

## Decision

1. **Receiver.** `com.ebim.tms.iam.lifecycle` accepts
   `PUT|GET /internal/platform-provisioning/tenants/{controlPlaneTenantId}/lifecycle` on the MasterAdmin
   chain of ADR-012 (same key, issuer, audience, subject, switch), scopes `tms:lifecycle:write|read`,
   distinct from provisioning and entitlements. Version rules, error codes and body are the contract's;
   the environment is `TMS_ENTITLEMENTS_ENVIRONMENT` and the single-use `jti` store is V56's (one
   deployment, one environment and one replay window for every MasterAdmin channel).
2. **Own durable state (V58).** `tms.platform_tenant_lifecycle` (one row per tenant; none = ACTIVE) and
   an append-only audit, owner-only like V51/V56. The row only moves forward and is never deleted.
   `tms.organization.active` / `tms.company.active` are **not** reused: they take the company out of
   the caller's scope (no reading, no `/me`), which a suspension must not do.
3. **What SUSPENDED means: writes stop, nothing else.** A single filter,
   `shared.security.TenantSuspensionFilter`, in both tenant chains right after the company scope is
   bound, refuses every company-scoped request whose method is not GET/HEAD/OPTIONS with `423`
   `tenant-suspended` (`error: TENANT_SUSPENDED`, `reason`). Reads, exports, login and `/me` continue;
   data is never touched; `ACTIVE` resumes everything without intervention. A trip already on the road
   therefore keeps its state; nobody in the customer's organization (office, carrier portal, partner
   API) can record further changes until reactivation. That is the answer to DESIGN section 9.
4. **Separate from commerce and from RBAC.** The port `TenantOperationGate` is independent of
   `CommercialAccessGate`; D-14 stands (`CommercialAccess.operationAllowed()` is still `true` always).
   No role or permission lifts a suspension.
5. **Runtime role.** The filter reads `tms.tenant_lifecycle_current_company()` (SECURITY DEFINER, no
   parameter, keyed on `tms.current_company_id()`) on `tms_app`; `/me` reads
   `tms.tenant_lifecycle_of_organizations(uuid[])` with the caller's own organizations. It fails closed.
6. **No database trigger.** TMS writes only through Java (ADR-001), so the filter is the whole
   enforcement; `TenantSuspensionCoverageTest` keeps every write route behind it. A future path that
   bypasses Java would need a guard raising SQLSTATE `PT423`, mapped to the same 423.
7. **UI is UX.** `/me` and `/companies/current` carry `operationalStatus`; the web shows a fixed
   «Cuenta suspendida» banner («regularice el pago con su proveedor» for `NON_PAYMENT`) and maps
   `tenant-suspended` to a clear message. The carrier outbox keeps 423-refused reports pending.

## Consequences

- One indexed function call per company-scoped **write**; reads cost nothing extra.
- The webhook dispatcher (the only scheduled job) is not gated: it delivers events recorded before the
  suspension to the customer's own endpoint. Gating its cross-tenant claim query is a follow-up that
  needs a PostgreSQL to validate.
- MasterAdmin being down changes nothing: the last applied state keeps deciding, without expiry.
- `PlatformScopes` stays constant-based (no per-scope environment variable), like the other TMS scopes.
