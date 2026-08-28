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
| `render.yaml` | Service definition. **The frontend service was removed** in a Phase 1 fix — it declared a service Render could not build |
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
- V1–V48 as of JOB 23. `btree_gist` is required (V41 onwards) and is created by the migration.

## 3. Configuration

Secrets come from the environment. `.env.example` carries placeholders and **no real value is in the
repository**, which JOB 15's static guard enforces.

The one setting that carries a security consequence rather than a functional one:

**The application must connect as `tms_app`, not as the schema owner.** ADR-005's row-level security
does not apply to the owning role. Connecting as the owner leaves the application's own scoping as
the only tenant defence, and it will not announce that it has happened.

## 4. Readiness

`/actuator/health/readiness` covers the process and the database. It does **not** know whether
migrations are the expected version, whether integrations are current, or whether the frontend build
matches the API.

## 5. Rollback

**No rollback procedure has been designed or tested.**

What is known: **schema changes are forward-only.** There are no down-migrations, so rolling the
application back to a previous version does not roll the schema back with it. Whether any given
previous version tolerates the current schema is unanswered, per migration.

This is the largest unaddressed operational risk in the system and it is recorded here rather than
in a paragraph that implies a procedure exists.

## 6. Before the first real deployment

Unordered, all unverified:

1. Confirm the application connects as `tms_app` and prove RLS is active.
2. Run the 7 authenticated E2E specs against the real environment — they have **never executed**.
3. Establish a performance baseline (JOB 25) so "slow" can later be measured against something.
4. Decide the rollback story, or accept forward-only and say so.
5. Point something at `/actuator/metrics`. Nothing currently watches it.
