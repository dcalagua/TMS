# SaaS administration and company settings (V1)

One line: **a customer can be onboarded, configured and staffed from the product, by their own
administrator, without anybody opening a psql prompt.**

That was not true before job 12. The permission catalogue has carried `iam.company:*`,
`iam.user:*` and `iam.membership:*` since migration V3 and **no endpoint had ever checked them**:
`Capability.IAM_VIEW` and `IAM_MANAGE` existed, the sidebar had a "Seguridad" link, and behind it
was a placeholder screen. Selling to a second company meant inserting rows by hand.

## 1. What exists now

| Surface | Endpoint | Permission |
|---|---|---|
| Company profile and settings | `GET /admin/companies/current` | `iam.company:read` |
| Save both | `PUT /admin/companies/current` | `iam.company:manage` + org-wide membership is *not* required |
| Add a company to the organization | `POST /admin/companies` | `iam.company:manage` **and** an active organization-wide membership |
| Who can act here | `GET /admin/users` | `iam.user:read` |
| One membership | `GET /admin/users/{membershipId}` | `iam.user:read` |
| Give somebody access | `POST /admin/users` | `iam.membership:manage` |
| Correct a display name | `PUT /admin/users/{membershipId}` | `iam.user:manage` |
| Replace the roles held here | `PUT /admin/users/{membershipId}/roles` | `iam.membership:manage` |
| End access to this company | `POST /admin/users/{membershipId}/revoke` | `iam.membership:manage` |
| Let somebody back in | `POST /admin/users/{membershipId}/restore` | `iam.membership:manage` |
| The role catalogue | `GET /admin/users/roles` | `iam.membership:read` |

Screens: `Configuración → Compañía` and `Configuración → Usuarios y accesos`, last in the sidebar,
in the trailing slot the single placeholder link used to hold.

**No new permission was minted.** Every endpoint above is guarded by a permission migration V3
already inserted, so no existing installation has to run a grant before its administration screen
works. That is the whole reason the authorization design of this job is a restatement of V3's
grants rather than an addition to them.

## 2. The five rules that make it safe to hand to a customer

### 2.1 A company administrator cannot reach another company

Structural, not checked. Every service takes a `CompanyScope`, which can only be obtained by
server-side resolution against the caller's own active memberships (ADR-003). `iam.company:manage`
held in company A grants nothing whatsoever in company B, because there is no way to name company B.

The one write that reaches outside the current scope is creating a company - there is no scope to
resolve for a company that does not exist yet - and it is guarded separately; see 2.5.

### 2.2 Access is revoked at the membership, never at the profile

`tms.app_user.active` is installation-wide. Switching it off from a company screen would lock the
person out of every organization they work for, including ones this administrator has never heard
of. So `revoke` deactivates `tms.membership` and the profile flag is read-only everywhere in this
module.

The screen shows `userActive` anyway, because an account disabled at installation level is otherwise
an unexplainable "has access but cannot sign in".

Nothing is ever deleted. The orders and shipments those people created carry their id in
`created_by` with `ON DELETE RESTRICT`, so removal would either fail or erase history (V2's rule).

### 2.3 An organization-wide membership is read-only from a company screen

A membership with `company_id IS NULL` reaches every company of its organization. Revoking one from
company A would remove that person from B and C as well, which is not what the button appears to do.

It is **listed** - hiding it would answer "who can act here" with a list missing the people who have
the most authority - and every write against one is refused twice: by the service, and by the
repository's `WHERE ... AND company_id = :companyId` predicate, which never matches a NULL.

Inviting somebody who already holds an organization-wide membership is refused too. A company-scoped
row beside it is legal (the two unique indexes are partial and do not collide) and would be a second
grant nobody can reason about: the permissions of both would union, and revoking the visible one
would appear to do nothing.

### 2.4 Nobody edits their own access

An administrator cannot revoke their own membership or change their own roles.

The guard is deliberately blunt - "not yourself", rather than "not if it would leave you unable to
administer". The clever version has to reason about organization-wide memberships that also apply to
this company, and a subtly wrong clever version locks a customer out of their own tenant with no way
back through the UI. The cost is that a sole administrator has to invite a second one before
demoting themselves, which is the correct order to do that in anyway.

### 2.5 Creating a company needs the level above the company

`iam.company:manage` is necessary and not sufficient. A COMPANY_ADMIN holds it and is, by V3's own
definition of the role, the administrator of *one* company. The second condition is an **active
organization-wide membership** in the organization, which is what an ORGANIZATION_ADMIN has and a
COMPANY_ADMIN does not.

That condition cannot be an authority expression, because it is a fact about the shape of the
caller's membership rather than about their permissions. It lives in
`CompanyAdministrationService.create`, which asks the database, and answers 403 otherwise.

Nothing grants access to the new company afterwards, and nothing needs to: an organization-wide
membership expands to every company of its organization, so the creator can select it on their next
`/me`. That is also why creating one from a company-scoped membership is refused rather than quietly
followed by a membership insert - a company nobody can see is worse than a request that says no.

`canCreateCompany` travels in the profile response so the button can be hidden. Hiding is UX; the
endpoint re-asks the database.

## 3. Roles

The four system roles of migration V3 are the catalogue. This module **assigns** them; it does not
author them.

A role whose `scope_level` is `ORGANIZATION` is refused on a company-scoped membership rather than
saved, because `JdbcIdentityRepository.COMPANY_PERMISSIONS_SQL` discards that pairing - it would be a
grant an administrator can see and the product does not honour. The picker shows such a role greyed
out with the reason instead of hiding it, so an administrator who cannot find `ORGANIZATION_ADMIN`
in the list is not left concluding the screen is broken.

A membership must hold at least one role. A membership with none produces no permission rows at all,
so the company would not even appear in that person's company selector: the access would look
granted and be invisible.

Per-organization custom roles stay deferred. V2 sketched the schema (`role.organization_id` plus
`UNIQUE (organization_id, code)`) and it is still not built, because a custom-role editor without a
permission picker, an impact preview and a "you are about to lock yourself out" guard is worse than
no editor.

## 4. Inviting somebody

`tms.app_user` is installation-wide - one person may work for several organizations (V2 says so on
the table) - so "invite" has three outcomes:

1. **the email is unknown**: a profile is created, then a membership;
2. **the email already works elsewhere in the installation**: the existing profile is reused and only
   a membership is created. The name in the request is **ignored** - that profile belongs to that
   person, and an administrator of one company does not get to rename them on the strength of knowing
   their address;
3. **the email used to work here**: the membership is reactivated with the roles now chosen.
   `uq_membership_user_organization_company` forbids a second row, and the honest reading of
   "invite somebody who used to work here" is "let them back in".

A profile deactivated at installation level is refused rather than reactivated, and the response says
so: turning it back on would restore that person's access to every other organization at the same
time.

There is no password and no `auth_user_id` in the request. TMS never holds a credential; the mapping
to Supabase Auth is established server-side at first sign-in, which is exactly what V2 made
`app_user.auth_user_id` nullable for.

## 5. Company settings

Three settings, and the rule the table is built to is that **every column has a consumer today**:

| Setting | Applied by | Was |
|---|---|---|
| Default country | `LocationImportValidator`, to an imported row that left `country` blank | the literal `"PE"` |
| Order number prefix | `OrderNumbers.format`, both callers | the constant `"TO-"` |
| Shipment number prefix | `TripService.generateShipmentNumber` | the literal `"SH-"` |

The time zone is edited on the same screen but lives where it always has, on `tms.company`. A second
copy in a settings table could only disagree with `CompanyScope.today()`.

Changing a prefix renumbers nothing and cannot create a duplicate: the digits come from an
installation-wide sequence, so the part that makes a document number unique was never the prefix.
The screen shows a live sample (`TO-00000042`) under the field, because "TO-" is abstract and the
sample is not.

Reads go through `CompanySettingsPort`, a `shared` interface implemented in `iam` - the same shape
`AuditRecorder` uses, and for the same reason: `orders`, `planning` and `masterdata` all consume
these values and `ModuleBoundaryTest` forbids every one of them from importing `com.ebim.tms.iam`.
A missing settings row resolves to the documented defaults rather than to an empty `Optional`,
because every caller is in the middle of a business write and none of them has a sensible way to fail
because a configuration row was absent.

## 6. Auditing

| Act | Aggregate | Action |
|---|---|---|
| Company profile or settings saved | `COMPANY` | `UPDATE`, metadata lists only what changed |
| Company created | `COMPANY` | `CREATE`, recorded against the *creating* company's scope |
| Access granted | `MEMBERSHIP` | `CREATE` (or `ACTIVATE` when a revoked membership is restored) |
| Roles replaced | `MEMBERSHIP` | `ROLES_CHANGED`, metadata carries before and after |
| Access revoked / restored | `MEMBERSHIP` | `DEACTIVATE` / `ACTIVATE` |
| Display name corrected | `APP_USER` | `UPDATE` |

The company-update metadata deliberately omits the tax identifier: it is the one field on that screen
that could be a person's national id in a single-owner company.

The company-creation event is stamped with the creating company's scope, not the new one. The audit
trail is company-scoped (V22) and an event stamped with a company that did not exist when the act
happened would be unreadable from either side; the new company's id and code travel in the metadata,
which is where "what did this act produce" belongs.

## 7. Deliberately not in V1

- **No billing, plan, subscription or seat count.** Nothing in TMS reads any of them, and a plan
  column with no enforcement is a number that lies the first time somebody exceeds it. Metering needs
  a usage source, a billing period and a decision about what happens at the limit.
- **No self-service organization signup.** A company is created inside an organization the caller
  already administers. Signing up a brand new organization from a public form needs email
  verification and an anti-abuse story this product does not have.
- **No company deactivation from the company screen.** Deactivating the company the request is
  scoped to would end the caller's own session mid-request and leave the only screen that could undo
  it unreachable, because `CompanyScope` is resolved from *active* companies. Switching a tenant off
  is an organization-level act and belongs with organization administration, which this job did not
  build.
- **No organization administration screen.** Renaming or deactivating an organization is
  `iam.organization:manage`, which only `ORGANIZATION_ADMIN` holds. The company screen shows
  `organizationActive` so an administrator staring at a company nobody can reach sees the reason
  rather than concluding the company is broken - but fixing it is a screen that does not exist yet.
  This is the largest known gap.
- **No email invitation.** The membership is created and the person signs in with Supabase Auth as
  they always would. Sending mail needs a transport, a template, a bounce policy and a token with an
  expiry - four decisions, none of which an administration screen should make on its way past.
- **No per-organization custom roles** - see section 3.
- **No planning defaults, map defaults or provider keys in settings.** `HeuristicPlanningEngine` takes
  no tunable parameter, the map already centres on the stops it is drawing, and a provider key is a
  deployment secret that must not move into a table an administrator can read.

## 8. Where the code is

```
shared/settings/CompanySettings.java          the value type, with the pre-V34 literals as defaults
shared/settings/CompanySettingsPort.java      the read port three modules depend on
iam/infrastructure/JdbcCompanySettingsAdapter.java   the only implementation
iam/infrastructure/CompanyAdministrationRepository.java
iam/infrastructure/UserAdministrationRepository.java  the two tenant rules, in SQL
iam/application/CompanyAdministrationService.java
iam/application/UserAdministrationService.java        the five rules of section 2
iam/api/CompanyAdministrationController.java
iam/api/UserAdministrationController.java
frontend/tms-web/src/pages/settings/                  the two screens
```

Schema: `V34__company_settings_and_iam_administration.sql`, documented in
`docs/database/DATA_MODEL.md` section 28.
