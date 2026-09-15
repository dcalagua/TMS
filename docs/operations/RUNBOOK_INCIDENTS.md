# Incident runbook

**Scope: failures this system can actually have.** Every entry below is traceable to a real
constraint, guard or port in the code. Nothing here describes a procedure nobody has performed —
where that is the case, it says so instead.

---

## 0. Before anything

1. **Get the correlation id** from the user's error screen. Every request has one and it is in every
   log line for that request.
2. **`/actuator/health`** — is it the process, or is it one operation?
3. **Do not restart first.** Almost nothing here is fixed by a restart, and a restart destroys the
   in-flight evidence.

---

## 1. "Everything is 500" / the application will not start

**Most likely: an invalid JPQL query.** Spring validates every repository method at context startup,
so one bad query takes down the whole application rather than one endpoint.

This has happened **twice** in development (JOB 13, JOB 23) and both times `mvnw compile` passed
first. The signature in the log:

```
Could not resolve attribute 'x' of 'com.ebim.tms.…'
Error creating bean with name 'someRepository'
```

**Fix:** the query, not the entity. **Never** relax the entity mapping to make a query compile.

**Prevention that already exists:** `./mvnw clean test` catches it. An incremental compile does not.

## 2. "The database rejected something and the user saw a 500"

TMS deliberately pushes invariants down to constraints, so a service bug surfaces as a constraint
violation rather than as bad data. That is working as intended, but a 500 means a service failed to
give the readable refusal first.

| Constraint in the message | What it means | Where the readable refusal should be |
|---|---|---|
| `ex_appointment_*` | Two bookings for one door | `AppointmentService` |
| `ex_own_fleet_profile_*` | Overlapping cost profiles (V48) | `OwnFleetCostProfileService.save` |
| `uq_work_assignment_*` | Two dispatchers built one resource's day (V47) | `WorkAssignmentService` |
| `uq_payable_export_invoice` | Double export (V46) | `SettlementService.export`, which is idempotent |
| `ck_*` | A three-layer invariant reached the last layer | The service that should have refused |

**Fix:** add the readable refusal. **Never drop the constraint** — it is the thing that stopped the
bad data.

## 3. "A user in company A can see company B's data"

**Treat as a security incident.** Stop, capture the correlation id and the exact request, do not
"fix and move on".

Defence in depth means three things must have failed together:

1. A service took a `CompanyScope` and ignored it.
2. A repository finder was not company-scoped — `TenantScopedRepositoryTest` exists to make this
   impossible to introduce, and caught a real one in JOB 22.
3. RLS did not filter (ADR-005) — the request ran without ever **entering** `tms_app`.

Point 3 is the one worth checking first, and the check is a log line, not a connection setting.
**The application connects as the schema owner and enters `tms_app` per company-scoped request**
(`TenantScopedDataSource`): `tms_app` is `NOLOGIN` and passwordless (V13), so it is not a login
role and an earlier version of this runbook asked for something impossible. A request that did
not enter the role is filtered by layers 1 and 2 alone, because **row level security does not apply
to a table's owner** unless the table has `FORCE ROW LEVEL SECURITY` - and no TMS table does, on
purpose (`MigrationConventionTest.rowLevelSecurityIsNeverForcedOnTheOwner`).

An earlier version of this paragraph said the owner is exempt because it has `BYPASSRLS`. That is
the wrong mechanism, and it matters: revoking `BYPASSRLS` would change nothing. Verified on
2026-09-15 against a local PostgreSQL 17 cluster with an owner that has **neither** SUPERUSER nor
BYPASSRLS: as `tms_app` a query saw only its own company, and the same query as the owner saw every
company. Whether the QAS connection role also carries `BYPASSRLS` is not verified here; either way
the owner is not filtered.

Find `TenantRuntimeRoleCheck`'s line in the startup log — prefix `Database roles:` — and read the
level:

- **`INFO` … `'tms_app' can be entered`** → RLS was available to every company-scoped request, so
  this incident is layer 1 or 2. Go back to the service and the repository finder.
- **`ERROR` … `CANNOT enter 'tms_app'`** → the runtime credential lacks the grant. Every
  company-scoped request should be failing outright, not leaking; if it is leaking instead, that is
  a second defect. The message names the fix: `GRANT tms_app TO "<runtime role>" WITH SET TRUE;`
- **`WARN` … `connected AS the runtime role`** → Flyway is not running as the owner and `SET ROLE`
  is a no-op.

If you are at a SQL client rather than a log: `SELECT current_user, session_user;` returns
`current_user = tms_app` **inside a company-scoped transaction**, with `session_user` the owner.
That is the healthy answer. Expecting the *connection* role to be `tms_app` is the mistake.

See `docs/operations/DEPLOYMENT.md` §3–§4 and `docs/security/RLS_STRATEGY.md` section 4.

## 4. "Integrations look broken"

`GET /api/v1/integration/health` (JOB 13) reports **age, not count** — deliberately, because a
partner who sent nothing all morning has a bigger problem than one with three failures.

- Check `tms.integration.requests` by `provider` tag.
- A single provider stale = their problem, usually credentials.
- All providers stale = ours.

**This does not affect `/actuator/health`, on purpose.** A stale partner feed must not take TMS out
of a load balancer.

## 5. "Planning says it cannot price a plan"

Not a fault. Read the reason:

| Reason | Meaning | Fix |
|---|---|---|
| `NO_AGREEMENT_FOR_SOME_TRIP` | A carrier has no rate card | Configure the tariff |
| `OWN_FLEET_NOT_COSTABLE` | Our own truck, and no profile or no measurable input (V48) | Configure the cost profile, or geocode the stops |
| `MIXED_CURRENCIES` | Two currencies in one plan | Nothing to fix — TMS will not invent an FX rate |
| `NO_TRIPS` | The engine placed nothing | Look at the unplanned reasons |

**A missing total is the system refusing to publish a number it cannot stand behind.** Do not
"fix" it by configuring a placeholder tariff of zero: a zero makes that option unbeatable in every
comparison, which is a worse failure than no number.

## 6. "The control tower is showing alarming counts"

Three separate counts, and they are not interchangeable:

- **`blockedShipments`** — trucks that cannot depart. **Act now.**
- **`openExceptions`** — a person reported a problem (V27). Somebody already knows.
- **`openAdvisories`** — worth knowing, stops nothing (JOB 23). Often a rounding difference.

**Never sum them.** They answer different questions, which is why they are three panels.

## 7. Migrations

**Applied migrations are immutable.** If Flyway reports a checksum mismatch, somebody edited a
migration that had already run.

- **Do not run `flyway repair` to make the error go away.** That hides the divergence; it does not
  resolve it.
- **Do not `db reset` anything shared.**
- The fix is a **new** migration that reconciles the difference, plus finding out how the old file
  came to be edited.

## 8. "It is deployed, but the browser shows nothing"

Four different faults present identically — a screen that never loads — and they are told apart in
under a minute by two unauthenticated requests. Run the smoke first and it names which one:

    scripts/ops/verify-deployment.sh --api https://<api> --web https://<web> --commit <sha>

| What you see | What it is | Fix |
|---|---|---|
| Every screen fails, dev tools show calls to `localhost:8080` | `VITE_API_BASE_URL` was missing from the Amplify panel for this branch, so `env.ts` baked its loopback default. **A restart cannot fix this** — the value is compiled into the bundle | set the variable, **rebuild**. `PROMOTION.md` §4.2 |
| The home page works; a reload or a pasted link gives a 404 from the host | the SPA rewrite rule is missing. Amplify serves `dist/` statically and `/masters/locations` is a route that exists only in the React router | the console rewrite in `PROMOTION.md` §4.1 |
| A fix you deployed is not there | the deploy did not pick up the revision, or backend and frontend are on different commits — they deploy through two independent channels and are never atomic | compare `GET /api/v1/system/info` → `commit` with `GET /<web>/release.json` → `commit` |
| Nothing at all responds, and `tms.flyway_schema_history` has not moved | **no backend booted.** Nothing else writes that table | the console. This is QAS-H1; see `PROMOTION.md` §1 and `QAS_DEPLOYMENT_AND_RECOVERY.md` §1 |

The last row is the one that has actually happened, twice. **A merged promotion is not a
deployment**, and the only thing that ever noticed the difference was that history table.

## 9. "We restored the database, health is green, and nothing company-scoped works"

**Most likely: the restore did not bring `tms_app` back.** `pg_dump` dumps one database; roles
are cluster-global. Restored into a cluster that never had `tms_app`, every `CREATE POLICY … TO
tms_app` and every `GRANT … TO tms_app` fails, `pg_restore` exits `1` — exactly as a good restore
with PostGIS does — Flyway validates clean because history says V13 already ran, and readiness
answers UP. Verified on a local PostgreSQL 17.10 cluster on 2026-09-15.

The signature:

- In the startup log, `TenantRuntimeRoleCheck` at **`ERROR`** — `CANNOT enter 'tms_app'` (§3).
- `SELECT count(*) FROM pg_roles WHERE rolname = 'tms_app';` → `0`
- `SELECT count(*) FROM pg_policies WHERE schemaname = 'tms';` → `0`
- Or run `scripts/ops/verify-restore.sh -- <psql connection arguments>` (read-only).

**Fix: [`BACKUP_AND_RESTORE.md` §6](BACKUP_AND_RESTORE.md#6-recovery--the-database-was-already-restored-without-tms_app).**
The `GRANT` that the `ERROR` line suggests is **not enough on its own** here: the role itself is
missing, and once it exists the 74 policies and the grants are still missing. Recreate the role,
then replay the `POLICY` / `ACL` / `DEFAULT ACL` entries from the same dump.

**Do not** re-run Flyway, `flyway repair`, or re-apply V13 by hand to recreate the policies: history
already records V13, and a schema whose policies came from a SQL client is not the product of
`V1..Vn` (§7).

## 10. What this runbook cannot tell you

- **No deployment has been verified.** See `DEPLOYMENT.md` — the procedures there are read from
  configuration, not from a performed deploy.
- **There are no alerts**, so every incident here starts with a human noticing.
- **There is no performance baseline**, so "it feels slow" cannot currently be answered with a
  number (JOB 25).
- **No restore has been executed on QAS or on any Supabase project.** A logical `pg_dump` /
  `pg_restore` has been executed on a local cluster (`BACKUP_AND_RESTORE.md`); Supabase platform
  backups and PITR are unconfirmed.
- **No code rollback has been executed.** Its expected safety, including on V49/V50, is reasoned in
  `QAS_DEPLOYMENT_AND_RECOVERY.md` §2 and `BACKUP_AND_RESTORE.md` §9.
