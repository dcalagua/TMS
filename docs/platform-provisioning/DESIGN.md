# Platform provisioning — design

**What this covers.** How a tenant that MasterAdmin owns becomes an organization, a company and an
administrator inside TMS, and why each decision is the one it is. The audit this is built on is
[`BASELINE.md`](BASELINE.md); the wire contract is [`API_CONTRACT.md`](API_CONTRACT.md).

---

## 1. The boundary

```
MasterAdmin ──ES256 M2M JWT──▶ /internal/platform/v1  ──▶ platform module
                                                            │ TenantOnboardingPort
                                                            ▼
                                                       iam module ──▶ PostgreSQL
```

MasterAdmin never reaches the database. It makes a signed HTTP call, and TMS does the writing
through the same application layer and repositories every other write in this product uses. There is
no Supabase REST call, no `service_role` key, and no second connection path.

## 2. The tenant mapping

TMS has no `tenant` table, and V51 does not invent one for the business to use. What it adds is a
**mapping**: `tms.platform_tenant` says that control-plane tenant *X* is organization *Y* and company
*Z* in TMS.

The mapping is a row, not an assumption, and `controlPlaneTenantId` is never treated as a TMS
identifier. The two id spaces belong to different systems: a control plane may renumber, merge or
reissue in ways TMS will never hear about, and the only relationship that survives that is one
written down and constrained unique.

`externalTenantId` in the response is the id of this mapping row — not the organization's and not the
company's. TMS's *operational* tenant is the company (ADR-003), but promoting either row to "the
tenant" would make the other look secondary, so the mapping has its own identity and the response
returns all three.

## 3. Field mapping, including what is dropped

| Control-plane field | Where it goes | Note |
|---|---|---|
| `controlPlaneTenantId` | `platform_tenant.control_plane_tenant_id` | unique |
| `organization.id` | `organization.id` | supplied, not generated |
| `organization.slug` | `organization.code` | normalised: upper-cased, non-`[A-Z0-9_-]` runs become `-`, must end as `^[A-Z0-9][A-Z0-9_-]{1,31}$` |
| `organization.name` | `organization.name` | |
| `organization.timezone` | `company.time_zone` | TMS keeps the zone on the company; defaults to `UTC` |
| `organization.taxId` | `company.tax_identifier` | only if the company carries none |
| `organization.legalName` | **dropped** | not modelled |
| `organization.countryCode` | **dropped** | not modelled |
| `organization.currency` | **dropped** | not modelled |
| `company.id` | `company.id` | supplied |
| `company.name` | `company.name` | |
| `company.taxId` | `company.tax_identifier` | preferred over the organization's |
| `company.legalName` / `countryCode` / `currency` | **dropped** | not modelled |
| `admin.email` / `fullName` | `app_user` + `membership` + `membership_role` | see §5 |
| `deploymentMode` | `platform_tenant.deployment_mode` | only `SHARED` is accepted |

**Dropped means dropped.** No column was invented to hold a legal name, and nothing was renamed into
a column that means something else. A field TMS does not model is validated for shape, ignored, and
listed here — which is a smaller lie than storing it somewhere nobody reads.

The **company code** is not in the request. It is the organization's code: company codes are unique
per organization, never globally, so one company per tenant at provisioning time cannot clash with
another customer's.

## 4. What a new tenant gets, and what it does not

Created: one organization, one company, and — when asked for — one administrator with an
organization-wide membership carrying `ORGANIZATION_ADMIN`.

Not created: origins, destinations, zones, routes, carriers, vehicles, drivers, rate cards, orders.
No demo data. A tenant arrives empty, which is what an empty tenant looks like; seeding it with
plausible-looking rows would leave a customer deleting fictional depots on their first day.

## 5. The administrator, and the trap that was avoided

`JdbcIdentityRepository` resolves a principal's companies with, in part:

```sql
AND (m.company_id IS NULL OR r.scope_level <> 'ORGANIZATION')
```

A membership that names a company **discards** roles whose scope level is `ORGANIZATION`. So
attaching `ORGANIZATION_ADMIN` to a company-scoped membership would have produced rows that look
correct and an administrator with **no permissions at all**. The membership is therefore
organization-wide (`company_id IS NULL`), which also grants every active company of the organization
— the intent for a tenant's first user.

No password is ever invented. `app_user.auth_user_id` is nullable, so the row exists without a
Supabase identity and the person claims it when they first sign in:

- `PREPROVISIONED` — a new TMS user row was created, unlinked.
- `LINKED` — a user with that email already existed **and belonged nowhere**, so it was given the
  membership instead of creating a duplicate.
- `NOT_REQUESTED` — the request carried no administrator.

An email that already belongs to a user **with** a membership is refused with `ADMIN_EMAIL_CONFLICT`.
Moving somebody between tenants changes what data they can see; that needs a person, not an API.

## 6. Idempotency, concurrency, atomicity

**One row per key.** `tms.platform_provisioning` is unique on `idempotency_key`.

- same key + same payload, previous call succeeded → the stored response is replayed, HTTP 200,
  `replayed: true`. Nothing is written.
- same key + different payload → `IDEMPOTENCY_CONFLICT`, HTTP 409. The comparison is a SHA-256 over
  the canonical re-serialisation, so whitespace, key order and unknown fields do not make a retry
  look like a new request.
- same key + previous call **failed** → the row is re-armed and the work is attempted again. A
  transient failure must not turn a key into a tombstone.
- different key + same `controlPlaneTenantId` → `TENANT_ALREADY_PROVISIONED`, HTTP 409.

**Concurrency** is handled twice over. A transaction-scoped advisory lock keyed on the control-plane
tenant makes two simultaneous calls queue, so the second finds the first's committed work and
replays it. Underneath, `uq_platform_tenant_control_plane`, `uq_platform_tenant_organization`,
`uq_platform_tenant_company` and `uq_platform_provisioning_idempotency_key` would refuse a duplicate
anyway. The lock is what turns a constraint violation into an ordinary replay.

**Atomicity** is one transaction for the organization, the company, the administrator, the
membership, the role, the mapping and the success record. A partial tenant is worse than none: the
control plane would believe a customer exists while nobody can administer it. The **failure** record
is written afterwards in a separate transaction (`REQUIRES_NEW`) — written inside the business
transaction it would roll back with the failure it exists to explain.

## 7. Audit

The provisioning row *is* the audit trail: the control-plane tenant, the key, the payload hash, the
outcome, the ids produced, the correlation id, the token's `sub` and `jti`, and the control plane's
`actor_id` / `actor_role`.

It is deliberately not `tms.audit_event`. That table is company-scoped and `AuditRecorder` requires a
`CompanyScope`; at the moment an organization is created there is no company to scope to, so an entry
there would either be impossible or would have to invent a scope.

`actor_id` and `actor_role` **authorise nothing**. Authorisation is the token's scope and only the
token's scope. A control plane that lies about which of its humans pressed the button gains no
privilege here — it only makes its own audit trail wrong.

## 8. Retention

The request body is **not** stored by default, only its hash. It carries the tenant administrator's
email and the company's registration details, and copying personal data into an append-only table is
a decision an operator makes (`TMS_PLATFORM_RETAIN_REQUEST_SNAPSHOT`), not a default — the same
stance `tms.integration` takes for inbound partner payloads.

## 9. What V1 does not do

No `DELETE`, no suspend, no activate **on this contract**. Deleting a tenant means deleting a
customer's transport history; suspending one means deciding what happens to a trip already on the
road. Both are design conversations, and an endpoint that quietly implemented either would be making
them by default.

Suspension has since been decided explicitly (2026-10-13, ADR-021): MasterAdmin's separate
`lifecycle.v1` channel suspends and reactivates a tenant; while suspended, its writes are refused with
`423 tenant-suspended` and everything else - reads, exports, login, data - is untouched, so a trip on
the road keeps its state. See [`LIFECYCLE.md`](LIFECYCLE.md). Deletion is still not offered.
