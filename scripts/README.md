# Developer scripts

Small, safe helpers for local development. They never touch a remote environment, never
push, and never mutate a shared database.

macOS is the primary workstation; the scripts are POSIX/bash 3.2 compatible and run on
Linux and on Windows through Git Bash or WSL.

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
  warns when Docker is unavailable instead of pretending those tests ran.
