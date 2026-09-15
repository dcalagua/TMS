# Promotion: dev → qas → main

> ## ⚠ WHAT IS AND IS NOT VERIFIED HERE
>
> **Verified from the repository:** the gates below exist as executable scripts and a GitHub
> Actions workflow, and the scripts were exercised locally — including against a stand-in HTTP
> server for the smoke and a throwaway local clone for the promotion. Every refusal message you
> see was produced by running them.
>
> **Not verified:** no deployment. This environment has no `gh`, `render`, `aws` or `amplify`
> CLI, no API token for any of them, and **no QAS URL exists anywhere in this repository**. The
> CI workflow has never run on a runner, the console actions in §4 have never been performed from
> here, and no smoke has ever reached a real host. Where this page says a platform does something,
> it is read from that platform's documentation and from this repository's configuration — not
> from an observation.

---

## 1. What deploys, and what actually triggers it

| Piece | Channel | Triggered by |
|---|---|---|
| Backend | Render, service `tms-api`, `backend/tms-api/Dockerfile`, profile `prod` | a push to the branch configured **in the Render dashboard** |
| Frontend | AWS Amplify, `amplify.yml`, `appRoot: frontend/tms-web` | a push to the branch **connected in the Amplify console** |
| Schema | Flyway, inside the backend process, at startup | the backend starting |

**The deploy trigger is a push.** `render.yaml` declares no `branch:` and `amplify.yml` cannot
declare one, so which branch deploys where is a dashboard setting in each console and is not
knowable from this repository. If the two watch different branches, backend and frontend will drift
and §3.6's smoke is the only thing that would notice.

### This is not hypothetical: it is the open HIGH finding

`TMS_QAS_RUNTIME_CERTIFICATION.md` (2026-09-08) records **QAS-H1 — no deployment channel reaches
the QAS database.** The `dev → qas` promotion merged cleanly and `tms.flyway_schema_history` did
not move: it has read V35 since 2026-08-25, and nothing but a booting backend writes that table.
Two promotions have now been pushed and neither produced a boot.

The leading candidate is exactly the setting above — **`main` sits at `0b94fb5`, an old unrelated
commit, and if Render tracks `main` then every promotion to `qas` has been deploying nothing.**
Indistinguishable from here; only the console can say. The other candidates recorded there are a
suspended service, a failing build, and a `TMS_DB_URL` pointing elsewhere.

So the first action of the first real promotion is not a push:

> **Confirm which branch the Render `tms-api` service and the Amplify app each track, and that
> `TMS_DB_URL` names the intended database.** Everything below assumes both answers are the branch
> you are promoting to.

**Nothing in this repository can observe a deploy.** There is no `gh`, `render`, `aws` or `amplify`
CLI and no token for any of them. The observable signals are §3.6's, all plain HTTP against the
deployed hosts, plus `tms.flyway_schema_history` for whoever has database access.

## 2. The gates, and what each one cannot see

| Gate | Runs | Refuses | Blind to |
|---|---|---|---|
| `.github/workflows/ci.yml` → `backend` | GitHub runner | backend that does not compile, or any failing test — Testcontainers included, because the runner has Docker | anything about a deployed environment |
| `ci.yml` → `frontend` | GitHub runner | type errors, lint, failing unit tests, and a build whose `VITE_*` configuration is not publishable | whether the **Amplify** panel has those variables |
| `ci.yml` → `e2e` | GitHub runner | a broken route or console error in the built bundle under `vite preview` | the SPA rewrite — see §4.1 |
| `scripts/ci/check-frontend-env.sh` | Amplify build, pre-build | a build with no/loopback/mis-suffixed `VITE_API_BASE_URL`, or a service-role key in `VITE_SUPABASE_ANON_KEY` | nothing about the backend |
| `scripts/ci/assert-frontend-bundle.sh` | Amplify build, post-build | an artefact whose compiled JS does not contain the configured API URL, or that carries no `release.json` | whether the API URL is *correct*, only that it was compiled in |
| Flyway, `validate-on-migrate: true` | backend startup | an edited applied migration, or a failed migration — the process does not start | nothing; this one is absolute |
| Render `healthCheckPath: /actuator/health/readiness` | Render, per deploy | routing traffic to an instance that has not finished migrating | whether the schema is the version this build expects |
| `scripts/ops/verify-deployment.sh` | a person, after the push | a wrong commit, a wrong profile, an open business endpoint, a frontend pointed elsewhere, a missing rewrite | anything that needs a login — the authenticated E2E specs are separate |

### CI does not gate a direct push to `qas`

This matters and is easy to get wrong. A push to `qas` **is** the deploy trigger, so CI running on
that push starts at the same moment as the deploy and finishes after it. For these checks to
actually block a bad revision, `qas` must be reached through a **pull request from `dev` with the
`CI` status check required** (Settings → Branches → branch protection). That is a repository
setting this repository cannot assert, and it is not currently known to be configured.

Until it is, the honest position is: **run the gates on `dev` and read them green before
promoting.** `scripts/promote.sh` prints what the push will do but cannot see a CI result — there
is no `gh` CLI here to ask with.

## 3. The sequence, and where each step fails closed

Run in this order. Each step's failure mode is what makes the order the right one.

### 3.1 Validate on `dev`

    scripts/check-all.sh          # locally, where Docker and Node allow it
    # or: read the CI run for the dev commit

**Fails closed:** the workflow's `ci` job requires `backend`, `frontend` and `e2e` all to have
*passed* — a skipped or cancelled job reads as a failure, not a pass.

### 3.2 Promote

    scripts/promote.sh --from dev --to qas          # inspect; changes nothing
    scripts/promote.sh --from dev --to qas --push   # push, which starts the deploy

**Fails closed** — before any push — on: an uncommitted working tree; a local `dev` ahead of
`origin/dev`; a target that is **not a fast-forward** (somebody committed onto the deployed
branch); and a **migration that was modified or removed** relative to what `qas` already has,
because Flyway would then refuse to boot. It never force-pushes.

It also prints the new migrations that will apply on the next boot. They are forward-only: there
are no down-migrations anywhere in this project.

### 3.3 Build

Render builds the image (`-DskipTests`, deliberately — §2's CI owns the test gate). Amplify runs
`npm ci`, the environment gate, `npm run build`, the release stamp and the artefact assertion.

**Fails closed:** in Amplify, at `check-frontend-env.sh` if the panel is missing
`VITE_API_BASE_URL` — which without this gate silently bakes `http://localhost:8080/api/v1` into
the bundle — and again at `assert-frontend-bundle.sh` if the value did not reach the compiler. In
Render, at `npm`-equivalent compile failure or a non-zero `mvnw package`. A failed build does not
replace the running instance.

### 3.4 Migrate

Flyway runs **inside the backend process, at startup**. There is no separate migrate step and no
`TMS_FLYWAY_ENABLED` to switch it off: `application-prod.yml` fixes `enabled: true`.

**Fails closed, hard:** a failed migration or a checksum mismatch means the application does not
start. Render's health check never passes, the new instance is never routed to, and the previous
one keeps serving. This is the single strongest gate in the system and it is not one this
repository added — it is a property of running Flyway on the startup path.

### 3.5 Deploy / health

**Fails closed:** `healthCheckPath: /actuator/health/readiness` in `render.yaml`. Readiness reports
UP only after Flyway finishes, so Render cannot send traffic to an instance whose schema is still
migrating. Amplify's own step is an upload; its gate was §3.3.

### 3.6 Smoke — the last gate, and the only one that sees the real thing

    scripts/ops/verify-deployment.sh \
      --api https://<qas-api-host> \
      --web https://<qas-frontend-host> \
      --commit "$(git rev-parse dev)"

**Fails closed** on any of: readiness not UP; liveness not UP; `/api/v1/system/info` unreachable;
the active profile not `prod`; **the reported commit not the promoted one**; `/api/v1/orders`
answering anything but 401/403 to an anonymous caller; the frontend's `release.json` absent, on a
different commit, or naming an API URL that is not the backend just checked; or a deep link not
serving the application document.

Non-zero exit means **do not promote further and do not announce the release.**

### 3.7 `main`

`main` is 102 commits behind `dev` and no environment is provisioned from it. `scripts/promote.sh
--to main` works and refuses the same things, but there is nothing at the other end yet. PROD will
be a **different** Supabase project (`docs/environments/QAS.md`), and until it exists a promotion
to `main` is a bookkeeping act, not a deployment.

## 4. The two things this repository cannot express, and the exact console actions

### 4.1 The SPA rewrite rule — **not declared anywhere, and 25 E2E assertions depend on it**

`amplify.yml` has no vocabulary for rewrites; only the console (or the Amplify API) does. There is
no `_redirects`, no `200.html` and no rewrite rule committed in this repository, and there is no
way to check from here whether one exists in the console.

What breaks without it: Amplify serves `dist/` as static files, so `/masters/locations` — a path
that exists only inside the React router — is answered **404**. The application works from the home
page and breaks on every reload and every pasted link. `frontend/tms-web/e2e/navigation.spec.ts`
encodes exactly this: 24 specs over `ALL_MODULES` assert `response.status() === 200`, plus one for
an unknown route — and all 32 specs in the file need the document served at all. They pass in CI
because `vite preview` does SPA fallback by itself, which is precisely why CI cannot substitute for
the smoke in §3.6.

**Console action.** Amplify → the app → *Hosting* → **Rewrites and redirects** → *Edit* → add one
rule with **Target address** `/index.html` and **Type** `200 (Rewrite)`. The **Source address** is
this, and it is deliberately in a code block rather than a table cell so it can be copied
byte-for-byte — `scripts/ops/verify-deployment.sh` prints the identical string when this check
fails, and the two must not drift:

```
</^[^.]+$|\.(?!(css|gif|ico|jpg|jpeg|js|json|map|png|txt|svg|webp|avif|woff|woff2|ttf|eot|xml|webmanifest)$)([^.]+$)/>
```

Read it as: *rewrite any path with no dot in it, and any path whose extension is not one of these,
to `index.html` with a 200.*

Three things about that list are load-bearing:

- **`json` must stay in it.** `release.json` is how §3.6 reads which commit is live. Rewritten to
  `index.html` it would return HTML with a 200 and the smoke would report a missing manifest.
- **`map` can stay in it.** `vite.config.ts` no longer emits source maps (`sourcemap: false`, since
  2026-09-15), so a request for `<chunk>.js.map` finds no file - and with `map` in this list it gets
  an honest 404 rather than `index.html` with a 200. It was needed while maps were published, and
  removing it now would buy nothing.
- **`svg` must stay in it** — `public/favicon.svg` is the only asset served from the root.

Do **not** use the simpler `/<*>` → `/index.html` 200 rewrite. It also rewrites genuinely missing
assets, so a mis-hashed `.js` chunk returns an HTML document with a 200 status and the browser
fails on a module type mismatch instead of on an honest 404.

**Verify it** with §3.6, or by hand:

    curl -s -o /dev/null -w '%{http_code}\n' https://<frontend>/masters/locations   # want 200
    curl -s https://<frontend>/release.json | head -1                               # want JSON, not HTML

### 4.2 The frontend environment variables

`VITE_*` values are **compiled into the bundle**, not read at runtime. Changing one in the console
therefore requires a new build — "Redeploy this version" is enough; a restart is not, and there is
nothing to restart.

Amplify → App settings → **Environment variables**, for the branch being deployed:

    VITE_API_BASE_URL        https://<qas-api-host>/api/v1     <- ending in /api/v1
    VITE_SUPABASE_URL        https://<project-ref>.supabase.co
    VITE_SUPABASE_ANON_KEY   <anon/publishable key>            <- never the service-role key
    VITE_GOOGLE_MAPS_API_KEY <optional; empty degrades to manual lat/lng>

Set per **branch**, not only at app level, if the app has more than one branch connected: a
variable scoped to the wrong branch is indistinguishable from a missing one, and
`assert-frontend-bundle.sh` exists to catch exactly that.

## 5. Release identity — how "which commit is live?" is answered

Nothing answered this before. `version` is the Maven version, `0.1.0-SNAPSHOT` on every revision
ever built; the consoles report build ids, not revisions, and are unreachable from here.

| Where | How it is read | Where the value comes from |
|---|---|---|
| Backend | `GET /api/v1/system/info` → `commit` (public, no token) | `TMS_RELEASE_COMMIT`, else `RENDER_GIT_COMMIT` (Render sets it automatically), else `build.commit` from `build-info.properties` |
| Frontend | `GET /<frontend>/release.json` → `commit` | `TMS_RELEASE_COMMIT`, else `GITHUB_SHA`, else `AWS_COMMIT_ID` (Amplify sets it), else `RENDER_GIT_COMMIT`, else `git rev-parse HEAD` |
| CI | the `frontend` job prints `dist/release.json` | `GITHUB_SHA` |

Two deliberate refusals:

- **The backend reports `null`, never `"unknown"` and never the Maven version, when the build
  recorded no commit.** An operator must be able to tell "this build did not record its revision"
  from "this build is the wrong revision"; a placeholder collapses those into one answer.
- **`write-release-manifest.sh` fails rather than writing `"commit": "unknown"`.** A manifest that
  cannot name a revision is worse than no manifest, because it looks like an answer.

On the current channels neither needs configuration: Render injects `RENDER_GIT_COMMIT` and
Amplify injects `AWS_COMMIT_ID`. `TMS_RELEASE_COMMIT` is for any other platform, and for baking the
SHA into the jar at `docker build` time so it survives being run somewhere else.

**Public by design, and the trade is worth naming.** The commit id is served to anonymous callers
on both hosts. In a private repository it identifies a revision to someone who already has the
repository and is opaque to everyone else. That is judged an acceptable price for being able to
answer the first question of every incident without console access. `release.json` also carries the
API base URL, which is already readable inside the bundle. **Never add anything to either that is
not already public.**

## 6. When the smoke fails

| Symptom from `verify-deployment.sh` | Most likely cause | Next step |
|---|---|---|
| readiness not UP | migration failed, or the database is unreachable | Render deploy log for the Flyway error. **Never** `flyway repair`, and never edit an applied migration — `docs/operations/QAS_DEPLOYMENT_AND_RECOVERY.md` §3 |
| readiness not UP, Flyway **clean** | the connection cannot enter `tms_app` — the log line starting `Database roles:` says `CANNOT enter` | run the `GRANT` that line names. After a restore into a fresh cluster the role itself is missing: `docs/operations/BACKUP_AND_RESTORE.md` §6. See `DEPLOYMENT.md` §4 |
| `system/info` unreachable but readiness UP | wrong host or a path prefix in front of the service | check `TMS_CORS_ALLOWED_ORIGINS` and the service URL; the endpoint needs no token |
| profile is not `prod` | `SPRING_PROFILES_ACTIVE` not set on the service | Render env vars. Under any other profile the strict JWT rules and the closed documentation are not in force |
| **commit mismatch** | the deploy did not pick up this revision: queued build, failed build with the old instance still serving, or a push to a branch the console does not watch | Render/Amplify build history; §1 |
| backend and frontend on different commits | two independent channels, one of them behind | redeploy the lagging one; they are never atomic |
| `release.json` missing | Amplify build has no stamp step | the `amplify.yml` change in §4/§5 |
| `apiBaseUrl` is localhost or absent | `VITE_API_BASE_URL` missing from the panel for **this branch** | §4.2, then **rebuild** |
| deep links 404 | the rewrite rule | §4.1 |
| `/api/v1/orders` returns 200 | **security incident, not a smoke failure** | `docs/operations/RUNBOOK_INCIDENTS.md` §3; stop and capture |

Abandoning a promotion: `docs/operations/QAS_DEPLOYMENT_AND_RECOVERY.md` §6. The short version is
that the *service* can be rolled back and the *schema* cannot, and that QAS's real recovery
property is that it can be rebuilt.

## 7. What still has no gate

Recorded here rather than left to be discovered.

1. **Branch protection on `qas` is not known to be configured**, and without it §2's checks run
   alongside the deploy instead of before it.
2. **The 7 authenticated E2E specs have never executed against a real environment.** They skip
   themselves visibly without `E2E_USER_EMAIL` / `E2E_USER_PASSWORD`, which is the right default,
   but it means the smoke in §3.6 covers no logged-in screen.
3. **No rollback has ever been performed**, and a build older than the current schema is only
   *expected* to tolerate it.
4. **Nothing watches `/actuator/metrics`.** The smoke is a moment, not monitoring.
5. **The CI workflow has never run.** It was written against GitHub's documented runner images —
   Docker present on `ubuntu-latest`, Node and Java from the setup actions — and could not be
   executed from the machine it was written on, which has Node 18 and no Docker. Expect the first
   run to find something.
