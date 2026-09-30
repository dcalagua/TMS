# ADR-012 - EBIM MasterAdmin provisions TMS tenants through a machine-only endpoint

- Status: Accepted (local implementation; not deployed)
- Date: 2026-09-22
- Relates to: ADR-001 (React -> Spring Boot -> PostgreSQL), ADR-002 (Flyway owns DDL), ADR-003
  (organization/company tenancy), ADR-004/ADR-005 (schema exposure, tenant RLS),
  `docs/platform-provisioning/MASTERADMIN_GENERIC_CONTRACT.md`.

## Context

Until now a TMS tenant existed only because somebody ran SQL: `supabase/seeds/local_dev_seed.sql`
and `qas_seed.sql` insert the organization, companies, profiles and memberships by hand, and the
in-product screens can add a company or a user only from inside an existing organization. EBIM
MasterAdmin - the suite's control plane - now sells and provisions products, and calls each one
through a common contract (GENERIC v1): an ES256 machine token, an `Idempotency-Key`, one standard
body, one standard response.

## Decision

1. **Hosted in the existing backend**, in the `iam` module (`com.ebim.tms.iam.provisioning`),
   because what it writes - organization, company, profile, membership - is identity and tenancy,
   which `iam` owns. No new service, no Edge Function (ADR-001 keeps business writes in Java).
2. **A third security chain** for `/internal/platform-provisioning/**`, beside the user chain and
   the partner-integration chain, with the same separation argument `IntegrationSecurityConfig`
   makes: no credential of one surface is accepted by another. Scopes are enforced structurally in
   the chain (unlisted methods are denied) and again with `@PreAuthorize`.
3. **ES256 with a configured public key**, no JWKS, algorithm pinned, TTL <= 300 s. The private key
   never exists on the TMS side. Off by default.
4. **Organization = tenant.** `externalTenantId` is the organization id; MasterAdmin's
   `tenantCode` becomes the organization code. MasterAdmin's company becomes the first company, or a
   default one is created when it sends none - TMS cannot operate without a company.
5. **PREPROVISIONED administrator.** An organization-wide `ORGANIZATION_ADMIN` membership on a
   profile with `auth_user_id NULL`. TMS does not create Supabase Auth accounts and never activates
   a login as a side effect of a provisioning call.
6. **Owner connection, not `tms_app`.** The caller has no company scope, so `TenantScopedDataSource`
   leaves the connection as the owner - the same situation as `PrincipalResolutionService`. The two
   V51 tables deny `tms_app` entirely and are write-once by trigger.
7. **Idempotency in the database**: unique key, unique MasterAdmin tenant, SHA-256 of the
   normalized request, advisory locks for concurrent retries. One transaction or nothing.

## Consequences

- MasterAdmin can create a TMS tenant without SQL; seeds stay for local and QAS demo data.
- Someone still has to link the administrator's Auth account before they can sign in. Automating
  that (invite through Supabase Auth Admin API) would need the service-role key in the backend,
  which ADR-004 and the M2M standard reject; it stays a separate, deliberate decision.
- The GENERIC body has no time zone; TMS derives one only for single-zone countries and otherwise
  starts in UTC, visibly (`resources.companyTimeZone`).
- `organization.code` (MasterAdmin's slug) is not used: two product tenants of one MasterAdmin
  organization would collide on it, while `tenantCode` is unique.
