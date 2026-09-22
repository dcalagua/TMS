# Deployment

> ## ⚠ NO DEPLOYMENT OF THIS SYSTEM HAS BEEN VERIFIED
>
> Everything below is read from `render.yaml`, the Spring profiles and the build configuration. **It
> is not a record of a deploy anybody performed.** The Phase 1 certification recorded the deploy as
> unverified and nothing since has changed that.
>
> Treat this as *what the configuration says should happen*, and expect the first real deployment to
> find things it does not mention.

---

## 1. What the repository contains

| | |
|---|---|
| `render.yaml` | Service definition. **The frontend service was removed** in a Phase 1 fix — it declared a service Render could not build. Note it declares no `branch:`, so which branch deploys is a dashboard setting |
| `amplify.yml` | Frontend build spec, `appRoot: frontend/tms-web`. Cannot express a rewrite rule or a branch |
| `.github/workflows/ci.yml` | The pre-publication gates: backend compile and full test suite, frontend typecheck/lint/unit/build, E2E against the built bundle. **Never executed** — written for a runner, not runnable here |
| `scripts/promote.sh`, `scripts/ops/`, `scripts/ci/` | Promotion, post-deploy smoke, and the build gates Amplify should call. See `PROMOTION.md` |
| `backend/tms-api` | Spring Boot, Java 21, Flyway migrations bundled |
| `frontend/tms-web` | Vite build, static output |
| Profiles | `local`, `prod`; `application.yml` holds what is common |

## 2. Migrations run on startup

Flyway is the canonical owner of application schema (ADR-002) and runs when the application starts.

**Consequences worth knowing before the first deploy:**

- A failed migration means the application does not start. That is correct and is not a bug to work
  around.
- **Applied migrations are immutable.** Editing one that has run produces a checksum mismatch on the
  next start.
- There is **no** application DDL in `supabase/migrations/` and there must not be.
- The history is `V1 … Vn`, where `Vn` is the last file in `db/migration/` at the deployed commit —
  49 files at the time of writing, and not a number worth restating here, since it goes stale and
  then this page is simply wrong. Count it: `ls backend/tms-api/src/main/resources/db/migration | wc -l`.
- `btree_gist` is required (V41 onwards) and is created by the migration.
- `scripts/promote.sh` prints which migrations a promotion will add, and **refuses** the promotion
  if any already-applied migration was modified or removed.

## 3. Configuration

Secrets come from the environment. `.env.example` carries placeholders and **no real value is in the
repository**, which JOB 15's static guard enforces.

The one setting that carries a security consequence rather than a functional one:

**The application connects as the schema owner and *enters* `tms_app` per company-scoped request.**
`tms_app` is `NOLOGIN` and passwordless (V13, ADR-005), so connecting *as* it is not possible: an
earlier version of this page said the opposite, and a deployment that followed it could not have
started at all. `TenantScopedDataSource` issues `SET ROLE tms_app` on each company-scoped
transaction, and ADR-005's policies apply to that role.

What must therefore be confirmed is not the login role but that the runtime credential **can enter**
the role. V13 grants `tms_app` to whichever role applied the migration; a deployment that splits
migration and runtime across two credentials must also run:

    GRANT tms_app TO "<runtime role>" WITH SET TRUE;

`TenantRuntimeRoleCheck` reports the verdict on every start — see §4.

One consequence worth stating plainly, because it is what made the old instruction look right:
inside a company-scoped transaction, `SELECT current_user, session_user;` **does** return `tms_app`
as `current_user`, with `session_user` being the owner. That is the expected posture. What is false
is expecting the **connection** role to be `tms_app`.

## 4. Readiness, and the one line to read on every start

`/actuator/health/readiness` is the group `readinessState,tenantRuntimeRole`
(`application.yml`, `management.endpoint.health.group.readiness`):

- **`readinessState`** reports UP only **after Flyway has finished**, which is what makes Render's
  `healthCheckPath` a real migration gate: an instance whose schema is still migrating is never
  routed to.
- **`tenantRuntimeRole`** reports DOWN when the connection cannot enter `tms_app`. That instance
  cannot serve a single company-scoped request, so it must not receive traffic.

An earlier version of this page said readiness "covers the process and the database". It never
did: the `db` indicator is not in the group, and that is now a stated decision rather than an
accident - a database outage would take every instance out of rotation at once. Readiness does
**not** know whether migrations are the expected version, whether integrations are current, or
whether the frontend build matches the API.

`TenantRuntimeRoleCheck` logs a line when the context is up, and another whenever its verdict
changes afterwards (it is re-evaluated at most every 30 seconds, and only while readiness is being
probed). It is the answer to "is ADR-005 in force?". Find it in the deploy log by the prefix
`Database roles:`:

| Level | Line | Meaning |
|---|---|---|
| `INFO` | `Database roles: session_user=…, current_user=…. 'tms_app' can be entered, so ADR-005 row level security applies to every company-scoped request.` | the expected posture |
| `ERROR` | `… This connection CANNOT enter 'tms_app', so every company-scoped request will fail …` | broken deployment; the message names the `GRANT` to run (§3) |
| `WARN` | `… The application connected AS the runtime role …` | the deployment took the old §3 literally; Flyway is not running as the owner and `SET ROLE` is a no-op |

It never fails startup, and liveness stays UP, on purpose: restarting creates no role and no GRANT,
and a process that stays alive can still be investigated.

**Readiness is different since 2026-09-15.** The `ERROR` case takes the instance **out of readiness
(HTTP 503)**, so Render keeps the previous instance serving instead of routing traffic to one that
fails every company-scoped request. This page used to say `/actuator/health` answered UP in all three
cases; that was true, and it was the defect. It was found by restoring a `pg_dump` into a fresh
cluster: `tms_app` did not exist, Flyway validated clean, and readiness reported UP. The `WARN` case
stays UP - requests do work in that posture - so that line still has to be **read**.

Verified against PostgreSQL 17.11 on 2026-09-15: with `tms_app` dropped, readiness 503 and liveness
200; `CREATE ROLE tms_app NOLOGIN` while running returned readiness to UP in about 30 seconds without
a restart; with the database stopped, readiness stayed UP from this indicator and answered in under
10 ms, because an unreachable database is not a refused role. Recovery after a restore:
[`BACKUP_AND_RESTORE.md`](BACKUP_AND_RESTORE.md).

## 5. Rollback

**No rollback procedure has been designed or tested.**

What is known: **schema changes are forward-only.** There are no down-migrations, so rolling the
application back to a previous version does not roll the schema back with it. Whether any given
previous version tolerates the current schema is unanswered, per migration.

This is the largest unaddressed operational risk in the system and it is recorded here rather than
in a paragraph that implies a procedure exists.

## 6. Before the first real deployment

Unordered, all unverified:

1. Confirm the `TenantRuntimeRoleCheck` startup line is the `INFO` one in §4 — that the runtime
   credential **can enter** `tms_app`. (The previous wording here, "confirm the application connects
   as `tms_app`", asked for something impossible: the role is `NOLOGIN`.)
2. Perform the two console actions this repository cannot express: the SPA rewrite rule, without
   which every deep link 404s, and the frontend `VITE_*` variables, without which the bundle bakes
   `localhost`. Both, with the exact values, in `docs/operations/PROMOTION.md` §4.
3. Run `scripts/ops/verify-deployment.sh` against the deployed hosts. It is the only thing that
   checks the deployed commit, the active profile, the rewrite and the frontend's API target, and it
   has **never been run against a real environment** — no URL for one exists in this repository.
4. Run the 7 authenticated E2E specs against the real environment — they have **never executed**.
5. Establish a performance baseline (JOB 25) so "slow" can later be measured against something.
6. Decide the rollback story, or accept forward-only and say so.
7. Point something at `/actuator/metrics`. Nothing currently watches it.

## 7. Promotion

Which branch deploys where, the order of build → migrate → deploy → health → smoke, the point at
which each step fails closed, and how "which commit is live?" is answered:
**`docs/operations/PROMOTION.md`**.
