# TMS by EBIM - authorization model

One line: **roles are granted per membership, permissions are what code checks, and both are
always evaluated inside one selected company.**

## 1. Shape of the model

```
app_user  --<  membership  >--  organization
                   |                 |
                   |                 +-- company        (membership.company_id, NULL = org-wide)
                   |
                   +--<  membership_role  >--  role  --<  role_permission  >--  permission
```

- **`permission`** is an atomic capability written `resource:action`, for example
  `orders.order:manage`. This is what `@PreAuthorize` checks.
- **`role`** is a named bundle of permissions. Roles are granted; permissions are checked.
- **`membership`** ties a user to a tenant scope. Its `company_id` is `NULL` for an
  organization-wide membership and set for a company-scoped one.
- **`Capability`** (Java only, never stored) is a coarse module-level name derived from the
  permissions a caller holds. It exists for menus and buttons; it is never enforced.

The catalogue lives in migrations `V3__iam_reference_data.sql` and
`V5__authorization_catalogue_completion.sql`. The Java `Permission` enum mirrors it one for
one, and `IdentityResolutionIntegrationTest` fails the build if the two ever differ.

## 2. Permission catalogue

32 permissions across 8 resource groups.

| Resource | `read` | `manage` |
|---|---|---|
| `iam.organization` | yes | yes |
| `iam.company` | yes | yes |
| `iam.user` | yes | yes |
| `iam.membership` | yes | yes |
| `masterdata.origin` | yes | yes |
| `masterdata.zone` | yes | yes |
| `masterdata.destination` | yes | yes |
| `masterdata.frequency` | yes | yes |
| `masterdata.route` | yes | yes |
| `fleet.carrier` | yes | yes |
| `fleet.vehicle_type` | yes | yes |
| `fleet.vehicle` | yes | yes |
| `orders.order` | yes | yes |
| `planning.plan` | yes | yes |
| `planning.trip` | yes | yes |
| `monitoring.transport` | yes | **no** |
| `audit.log` | yes | **no** |

`monitoring.transport` and `audit.log` have no `manage` action by design. The transport monitor
observes state other modules own - acting on what it shows is done through orders or planning,
with those modules' permissions. The audit trail is append-only and no role may edit it.

## 3. Capability names

The product brief specifies coarse capability names. They are kept as **derived** names rather
than as a second stored catalogue, because two overlapping catalogues drift and only one of
them would actually be enforced. The mapping:

| Capability | Backed by |
|---|---|
| `MASTER_DATA_VIEW` | `masterdata.origin:read`, `masterdata.zone:read`, `masterdata.destination:read`, `masterdata.frequency:read`, `masterdata.route:read` |
| `MASTER_DATA_MANAGE` | the five `masterdata.*:manage` permissions |
| `ORDERS_VIEW` | `orders.order:read` |
| `ORDERS_MANAGE` | `orders.order:manage` |
| `PLANNING_VIEW` | `planning.plan:read` |
| `PLANNING_MANAGE` | `planning.plan:manage` |
| `TRIPS_VIEW` | `planning.trip:read` |
| `TRIPS_MANAGE` | `planning.trip:manage` |
| `FLEET_VIEW` | `fleet.carrier:read`, `fleet.vehicle_type:read`, `fleet.vehicle:read` |
| `FLEET_MANAGE` | the three `fleet.*:manage` permissions |
| `TRANSPORT_MONITOR_VIEW` | `monitoring.transport:read` |
| `IAM_VIEW` / `IAM_MANAGE` | the four `iam.*:read` / `iam.*:manage` permissions |
| `AUDIT_VIEW` | `audit.log:read` |

**Planning and trips are separate** because they are separate activities: running a planning
session is not the same authority as authoring or altering the trips it produces. A deployment
that wants them to coincide grants both to the same role; a deployment that wants a planner who
may not touch finalised trips can express that. Collapsing them into one permission would make
the second case unexpressible.

Rules:

- a capability is held when the caller holds **at least one** of its permissions, because it
  gates entry to a screen that then enforces its own rules per resource;
- capabilities are computed per company and returned by `GET /api/v1/me` and
  `GET /api/v1/companies/current`;
- **capabilities are never enforced.** `@PreAuthorize` always names a `Permission`.

## 4. System roles

Four roles, all `system_managed`. Per-organization custom roles are a later migration
(`role.organization_id` plus `UNIQUE (organization_id, code)`); nothing in V1 needs them.

| Role | `scope_level` | Grants |
|---|---|---|
| `ORGANIZATION_ADMIN` | `ORGANIZATION` | the whole catalogue (32) |
| `COMPANY_ADMIN` | `COMPANY` | everything except `iam.organization:manage` (31) |
| `PLANNER` | `COMPANY` | reads the operational catalogue; manages orders, planning and trips (16) |
| `VIEWER` | `COMPANY` | read-only across the operational catalogue (13) |

Tenant isolation is **not** a permission. `ORGANIZATION_ADMIN` holding
`iam.organization:manage` means "may administer *its own* organization"; the scope comes from
the membership, never from the role.

## 5. Scope resolution

For a request that selected company `C`, the effective permission set is the union of the
permissions granted by every active membership that reaches `C`:

- the company-scoped membership for `C`, if any;
- any organization-wide membership of `C`'s organization.

A row contributes only when the membership, its organization, its company and its role are all
`active`, and:

> **a role with `scope_level = 'ORGANIZATION'` contributes nothing on a company-scoped
> membership.**

That last rule is the pairing the database cannot enforce (migration V2 records it as a Java
rule). Without it, granting `ORGANIZATION_ADMIN` on a single-company membership would quietly
confer the entire catalogue inside that company - a plausible administrative mistake with a
large blast radius. It is covered by
`IdentityResolutionIntegrationTest.organizationRoleOnCompanyMembershipGrantsNothing`.

The reverse pairing is allowed: a `COMPANY`-level role on an organization-wide membership
grants its permissions in every company of that organization, which is what "an org-wide
planner" means.

## 6. How authorization is applied

```java
@GetMapping("/current")
@PreAuthorize("hasAuthority('iam.company:read')")
public CompanyAccessView current(CompanyScope scope) {
    return companyContextService.describe(scope);
}
```

Three independent gates, all server-side:

1. **`CompanyScope` parameter** - supplied only if `X-Company-Id` named a company the caller
   holds an active membership in. There is no other way to obtain one.
2. **`@PreAuthorize`** - evaluated against authorities that are the permissions of *that*
   company. Before a company is selected there are no permission authorities at all, so a
   company-scoped endpoint denies by default.
3. **Scoped repository queries** - a use case receives a resolved `CompanyScope`, never a
   company id from the request, so it cannot be pointed at another tenant.

Conventions for the modules that follow:

- name the permission after the resource the endpoint touches, not after the screen;
- `read` for anything that returns data, `manage` for create/update/deactivate;
- a new resource adds its permissions in the migration that creates its tables, adds the
  matching `Permission` constants, and grants them to the system roles in the same migration;
- every company-scoped endpoint takes a `CompanyScope` parameter and carries a `@PreAuthorize`
  (enforced over the whole controller layer by `EndpointContractTest` - see section 9);
- every vertical slice ships a cross-tenant isolation test (ADR-003 compliance rule).

## 7. The administration surface (job 12, migration V34)

Sections 2 and 4 above describe the catalogue as of migration V5; later migrations added
`fleet.driver:*` (V26), `rates.*` (V30), `planning.tender:*` (V31) and `planning.trip:execute`
(V25), and the counts in those tables were not updated with them. **`Permission` and `Capability`
in `com.ebim.tms.shared.security` are the source of truth**, and `PermissionCatalogueIntegrationTest`
proves the enum matches the database.

What job 12 changed is not the catalogue - it minted **no new permission** - but who finally checks
it. `iam.company:*`, `iam.user:*` and `iam.membership:*` had existed since V3 with no endpoint behind
them; there are now ten, under `/admin/companies` and `/admin/users`. Full contract in
`docs/domain/SAAS_ADMINISTRATION_V1.md`. The parts that belong here:

- **the grants of V3 are the authorization design of that surface, restated.** ORGANIZATION_ADMIN
  holds everything; COMPANY_ADMIN holds everything except `iam.organization:manage`, so it
  administers its own company and the people in it and cannot rename the organization above it;
  PLANNER and VIEWER hold `iam.company:read` alone, so they can read the company screen and are
  refused by the people screen at the endpoint, not merely hidden from it by the menu;
- **reading the person and granting authority are two permissions on purpose.** Correcting a display
  name is `iam.user:manage`; giving, changing or revoking access is `iam.membership:manage`. An
  installation can let a supervisor keep the directory tidy without letting them hand out authority;
- **one condition in that surface is not a permission at all.** Creating a company reaches outside
  the scope the request resolved, so `iam.company:manage` is necessary and not sufficient: the
  service additionally requires an active organization-wide membership. That is a fact about the
  shape of the caller's membership rather than about their permissions, so it cannot be an authority
  expression and lives in the service, which asks the database and answers 403 otherwise;
- **tenancy is still not a permission.** A company administrator cannot reach another company because
  `CompanyScope` is resolved from their own active memberships, exactly as section 5 describes - not
  because of anything the administration endpoints check.

## 8. What is deliberately not in V1

- **per-organization custom roles** - schema direction recorded above, no requirement yet;
- **record-level or field-level authorization** (for example "this planner may only see zone
  X"). Company is the only scope; a finer one needs a requirement and an ADR;
- **delegation, impersonation or service accounts.** A machine caller would need its own
  authentication path, not a borrowed user token;
- **permission grants directly on a membership**, bypassing roles. Roles are the only grant
  mechanism, which keeps "who can do what" answerable by reading four rows.

## 9. Endpoint contract enforcement (job 15)

Section 6's convention was written down and followed by hand for eleven jobs. Job 15 checked it
mechanically for the first time, over all 182 handler methods, and found one deviation:
`WebhookController.eventTypes` carried its `@PreAuthorize` and documented `X-Company-Id` as
required, but did not declare the `CompanyScope` parameter that makes the header required in fact.

The consequence was not a hole. An unscoped token carries no permission authorities at all
(`TmsAuthenticationToken`), so a caller omitting the header was refused - just with
`403 access-denied` rather than the `400 company-scope-required` every other company-scoped
endpoint answers. It worked in the product because the browser client always sends the header. That
is precisely why a convention needs a test: the failure mode is invisible from the UI and only
appears to whoever integrates against the API next.

`EndpointContractTest` now holds three rules over every `@RestController`:

1. **a `@PreAuthorize`-guarded user-facing handler declares a `CompanyScope` parameter.**
   Machine-to-machine handlers are excluded, because their tenant comes from the credential and
   never from a header (`IntegrationAuthenticationFilter`). They are recognised by taking an
   `IntegrationPrincipal` rather than by their package - `WebhookController` lives in
   `integration.api` and is a browser endpoint;
2. **every handler is permission-guarded or named in `UNGUARDED_BY_DESIGN`** with the sentence that
   justifies it. Six are: the public service-identification endpoint, `/me`, the three notification
   endpoints (the alert bell is a top-bar control no role can hide, and the disclosure is filtered
   per alert type inside `NotificationService`), and the integration credential self-check;
3. **no handler declares both a `CompanyScope` and an `IntegrationPrincipal`.** They are two
   tenancy models on two security chains, and a method claiming both belongs to neither.

What the audit did **not** change, and why:

- **no rate limiting was added.** Integration secrets carry 256 bits of entropy and are compared in
  constant time against a stored digest, so the credential endpoint is not brute-forceable; what a
  limiter would defend is request volume, which is an infrastructure concern and belongs in front of
  the application. Adding an in-process counter would suggest a protection the second instance does
  not share. It needs a concrete requirement and an ADR, like everything else on the deferred list;
- **child-of-aggregate queries keep taking only the parent id** (`TripStopRepository.findByTripIds`
  and friends). The parent was already resolved company-scoped, RLS covers the `tms_app` role
  underneath (ADR-005), and adding a redundant predicate to one of them and not the rest would make
  the convention harder to read rather than safer.
