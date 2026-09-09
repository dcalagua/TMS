# Developer scripts

macOS is the primary workstation; the scripts are POSIX/bash 3.2 compatible and run on
Linux and on Windows through Git Bash or WSL.

## Local development

Small, safe helpers. They never touch a remote environment, never push, and never mutate a
shared database.

| Script | Purpose |
|---|---|
| `check-all.sh` | Every local check: frontend typecheck, lint, tests, build, then backend `clean verify` |
| `backend-build.sh` | `./mvnw clean verify` and report the packaged jar |
| `backend-test.sh` | Backend tests only |
| `frontend-build.sh` | Frontend typecheck, lint and production build |
| `frontend-test.sh` | Frontend tests only |
| `dev-backend.sh` | Run the API on the `local` profile at http://localhost:8080 |
| `dev-frontend.sh` | Run the Vite dev server at http://localhost:5173 |
| `lib.sh` | Shared helpers; sourced by the others, not executed |

## Release gates — `ci/`

Run by `.github/workflows/ci.yml` **and** by the Amplify build phase, from the same files, so
what CI proves and what the deploy enforces cannot diverge. They take no credential and print
no secret.

| Script | Refuses |
|---|---|
| `ci/check-frontend-env.sh` | a build with no / loopback / mis-suffixed `VITE_API_BASE_URL` — which `env.ts` would silently replace with `http://localhost:8080/api/v1` — or a service-role key in `VITE_SUPABASE_ANON_KEY`. Run **before** `npm run build` |
| `ci/write-release-manifest.sh` | writing `dist/release.json` at all when no commit can be resolved. Reads `TMS_RELEASE_COMMIT`, `GITHUB_SHA`, `AWS_COMMIT_ID`, `RENDER_GIT_COMMIT` or `git rev-parse HEAD`, in that order |
| `ci/assert-frontend-bundle.sh` | an artefact whose compiled JavaScript does not contain the configured API URL, or that carries no usable `release.json`. Run **after** the build |

`TMS_ALLOW_LOCAL_API_BASE_URL=1` relaxes the loopback and http rules, for local builds only.

## Promotion and post-deploy — `promote.sh`, `ops/`

| Script | Purpose |
|---|---|
| `promote.sh` | `dev → qas → main`, fast-forward only. Prints the commit that will become live and the forward-only migrations that will apply; refuses a dirty tree, an unpushed source, a non-fast-forward, and any **modified or removed** migration. Never pushes without `--push`, and never force-pushes |
| `ops/verify-deployment.sh` | The post-deploy smoke, over plain HTTP. Checks readiness (the migration gate), liveness, the **deployed commit** against what was promoted, the active profile, that business endpoints still refuse anonymous callers, that the published bundle targets this backend, and that SPA deep links are served. Non-zero exit means do not promote further |

Both are documented end to end, with the point at which each step fails closed, in
`docs/operations/PROMOTION.md`. Neither has ever been run against a real environment — no
environment URL exists in this repository.

## Local start

Three terminals, in this order:

    # 1. platform (optional but recommended; needs Docker Desktop running)
    supabase start

    # 2. backend - runs Flyway migrations at startup, serves http://localhost:8080
    ./scripts/dev-backend.sh

    # 3. frontend - serves http://localhost:5173
    ./scripts/dev-frontend.sh

Before the first backend run, copy the env templates and adjust them:

    cp backend/tms-api/.env.example backend/tms-api/.env
    cp frontend/tms-web/.env.example frontend/tms-web/.env.local

Both copies are git-ignored. The templates contain placeholders only; never commit real
credentials.

## Notes

- Maven is not required globally: the backend ships the Apache Maven Wrapper (`./mvnw`),
  which downloads the pinned Maven version on first use.
- Testcontainers-based integration tests need a running Docker daemon. `check-all.sh`
  warns when Docker is unavailable instead of pretending those tests ran. GitHub-hosted
  `ubuntu-latest` runners have one, which is why the backend suite is a CI gate even where it
  cannot run locally.
- The frontend toolchain needs Node 20.19+/22.12+ (`.nvmrc` pins 22). On Node 18, `tsc -b`
  works but Vite 8, Vitest 4 and oxlint do not start at all.
