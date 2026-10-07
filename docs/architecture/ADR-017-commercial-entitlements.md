# ADR-017 - Commercial entitlements from EBIM MasterAdmin, enforced apart from RBAC

- Status: Accepted (local implementation; not deployed). Amended 2026-09-29 by D-14 (see the end)
- Date: 2026-09-28
- Relates to: ADR-003 (organization/company tenancy), ADR-005 (tenant RLS runtime role), ADR-012
  (MasterAdmin provisioning), `docs/platform-provisioning/ENTITLEMENTS.md`, EBIM Commercial Control
  Plane spec/plan (MasterAdmin repository, `docs/superpowers/{specs,plans}/2026-09-27-*`).
- Numbering: ADR-013 to ADR-016 are taken on unmerged branches (`docs/adr-013-014-warehouse-execution`,
  `feature/tms-ewm-integration-v1`); this one takes the next free number to avoid a collision.

## Context

EBIM MasterAdmin is becoming the suite's canonical commercial control plane: what a customer
contracted (plans, add-ons, limits, AI credits) lives there and is pushed to each product as a
versioned snapshot, `ebim.entitlements/v1`. Every product keeps a durable local copy and enforces
from it, so no user action depends on MasterAdmin being reachable.

TMS has almost no commercial infrastructure: no plans, no licensing (`plan.code` is recorded and
ignored by provisioning, ADR-012), no AI feature, no meter. Its `Permission` catalogue is RBAC -
what a member may do inside a company - and `Capability` is a UX grouping of permissions.

## Decision

1. **A receiver, not a billing system.** `com.ebim.tms.iam.entitlements` accepts
   `PUT/GET /internal/platform-provisioning/tenants/{controlPlaneTenantId}/entitlements` and
   `GET /internal/platform-provisioning/entitlements/manifest` on the MasterAdmin chain of ADR-012,
   with scopes of their own (`tms:entitlements:write|read`). Provisioning is unchanged.
2. **Durable last-good snapshot (V56).** The whole verified document is stored per MasterAdmin
   tenant with version and checksum; the GET returns what is stored, never what was asked. Stale,
   conflicting, malformed, wrong-environment and bad-checksum snapshots are refused exactly as the
   FIX-ENT-v1 golden fixtures require; `jti` is single-use on these routes; every outcome is audited
   append-only. No price, amount, currency or credential is accepted or stored.
3. **The only commercial fact TMS acts on is `appActive`, and it is commercial, not operational**
   (amended by D-14 ruling 1, 2026-09-29). When the snapshot decides and says `false`,
   `CommercialAccess.commercialActive` is `false` and `CommercialEntitlementService` grants no
   sellable capability and no commercial limit; the organization keeps operating TMS -
   `CommercialAccess.operationAllowed()` is `true` for every decision and the gate never refuses on
   a commercial fact. A full operational shutdown is a separate, explicit policy that does not exist
   yet; `CommercialAccessGate`/`CommercialAccessFilter` stay as the hook it would use (and still fail
   closed). This is **not** RBAC: no role or permission changes it, and it grants nothing a
   permission withholds.
4. **No permission becomes a paid capability.** The sellable registry (`TmsCapabilityRegistry`) is
   empty; the manifest declares only the `tms.core` baseline. `CommercialEntitlementService` can
   answer capabilities and limits by organization, fails closed for any code it does not register,
   and treats an absent limit as "none granted", never "unlimited" - so a sellable added later
   (manifest + registry + MasterAdmin together) is enforced from day one.
5. **Enforcement modes** `LEGACY -> SHADOW -> DUAL_READ -> PRIMARY`, product-wide with optional
   per-tenant override, moved one step at a time by an operator (a trigger enforces it and records
   the history). V56 seeds `SHADOW`: snapshots are stored and compared (`appActive=false` becomes a
   recorded difference) and decide nothing. `DUAL_READ` lets the snapshot decide and falls back to
   legacy when none has arrived; `PRIMARY` grants nothing commercial without one (operation
   continues). Organizations MasterAdmin never provisioned (`NOT_UNDER_CONTROL_PLANE`) are outside
   its authority in every mode, so a product-wide `PRIMARY` leaves them exactly as legacy.
6. **Runtime role.** The access check runs after the company scope is bound, on `tms_app`, which
   can read none of the V56 tables. `tms.commercial_access_current_company()` - `SECURITY DEFINER`,
   no parameter, keyed on `tms.current_company_id()`, pinned `search_path`, `EXECUTE` for `tms_app`
   only - returns the three facts of the caller's own organization and of no other. The decision
   stays in Java (`CommercialAccess`).
7. **Shared port.** `shared.security.CommercialAccessGate` is what both chains consult
   (`ModuleBoundaryTest`: `shared` may not depend on `iam`). Absent, the chains behave as before.
   A gate that cannot answer refuses (fail closed).
8. **No usage emitter.** TMS has no approved meter and no AI feature; nothing is metered.

## Consequences

- A company-scoped request costs one indexed function call. Acceptable at the stated scale; a cache
  would delay a change of commercial state and is not introduced.
- Moving a tenant to `DUAL_READ`/`PRIMARY` is an operator decision after GATE C and the cut-over
  review (D-14), not a deployment side effect.
- `appActive=false` stops neither requests nor scheduled background work (webhook dispatch,
  outbox): it withdraws the commercial surface only (D-14 ruling 1).
- Grants scoped to explicit MasterAdmin company ids cannot be mapped to TMS companies and are
  treated as not granted.

## Amendment - D-14 (human decision, DEV/LOCAL only, 2026-09-29)

- **Ruling 1: `appActive=false` withdraws the commercial SaaS capabilities, not the application.**
  Until phase 12, TMS refused every company-scoped request with `403 commercial-access-suspended`
  (it read the contract README section 2, "`false` -> el SaaS bloquea el acceso operativo", and the
  plan section 11.4). The ruling supersedes that reading for TMS.
  - The vendored FIX-ENT-v1 README keeps that sentence: it is checksum-pinned
    (`EntitlementsFixturesPinTest`) and is not edited. This ADR is the record that TMS reads it as
    "withdraws the commercial surface".
  - The fixtures themselves are unaffected: `06-app-inactive.json` is still APPLIED and stored.
  - The web copy for `commercial-access-suspended` (`frontend/tms-web/src/shared/api/problemMessages.ts`)
    stays. It is harmless: the backend no longer emits that code on a commercial fact, and it still
    covers a future operational-shutdown policy.
- **Ruling 2 (2026-09-29): entitlements may move to `PRIMARY` in DEV** once product parity and
  security prerequisites are green, with no invented plan, price, quota or limit value.
  - In TMS the move is the documented operator statement, one step at a time with a reason, at
    `PRODUCT` scope.
  - Unmapped/legacy organizations resolve `NOT_UNDER_CONTROL_PLANE` in every mode, so they are not
    affected (`CommercialEntitlementServiceTest.productPrimaryDoesNotReachUnmappedOrganizations`).
  - TMS has no legacy entitlement write path: `tms_app` can neither read nor write the V56 tables
    (`PlatformEntitlementsIntegrationTest.runtimeRoleIsLockedOut`).
  - Proven end to end against MasterAdmin in X-07 (phase D14). No shared or remote database was changed.
