# Step 00 - Preflight and Architecture Baseline

- Date: 2026-08-19
- Attempt: 1 of 3
- Repository root: `/Users/edumorenoccama/Documents/EBIM/TMS`
- Host: macOS 26.5.2 (build 25F84), arm64

## 1. Observed repository state (before this step)

| Item | Observed |
|---|---|
| Git repository | Yes, already initialized |
| Branch | `main` |
| HEAD | **No commits yet** (`fatal: ... does not have any commits yet`) |
| Working tree status | Clean (`git status --porcelain` empty) |
| Remote | `origin` -> `git@github.com:dcalagua/TMS.git` (fetch + push) |
| Tracked files | None |
| Root contents | `.git/`, `tms-overnight-pack/` only |
| `.gitignore` | Absent |
| `.git/info/exclude` | Already contained `tms-overnight-pack/` |

No pre-existing backend, frontend, Supabase, docs or scripts directories existed.
No prior overnight artifacts existed: `runtime/progress.tsv` was empty,
`runtime/blocked.log` was empty, and `runtime/logs/00_attempt_1_*.log` was 0 bytes.
Two stale "stuck launcher/supervisor" logs from an earlier aborted start exist under
`runtime/history/`; they contain no completed step output.

**Conclusion: this is an effectively empty repository. Nothing prior needed preserving,
and nothing was overwritten or regenerated.**

## 2. Tool versions available

Verified directly in this step (not copied from the pack preflight file):

| Tool | Version | Notes |
|---|---|---|
| `git` | 2.50.1 | OK |
| `java` | OpenJDK 21.0.9 (2025-10-21) | OK, meets Java 21 requirement |
| `javac` | 21.0.9 | OK |
| `node` | v22.17.0 | OK |
| `npm` | 10.9.2 | OK, registry `https://registry.npmjs.org/` |
| `docker` (CLI) | 29.6.2 | CLI present, **daemon not running** (see blockers) |
| `supabase` CLI | 2.114.0 | Present |
| `claude` | 2.1.223 | Present |
| `psql` | PostgreSQL 18.4 | Client only; no local server assumed |
| `mvn` | **not installed** | Use the Maven wrapper (`./mvnw`) - see blockers |
| `JAVA_HOME` | **unset** | Java is on `PATH`; export if a tool needs it |

Free disk on the working volume: ~94 GiB.

## 3. Decisions made in this step

1. **Git**: the repository already exists; no `git init` was needed. The pre-existing
   `origin` remote was **left untouched** (not removed, not renamed) and **nothing was
   pushed or fetched**. No commit was created in this step.
2. **Pack exclusion**: `tms-overnight-pack/` was already listed in `.git/info/exclude`.
   Verified and left as is. The product `.gitignore` was **not** created or polluted for
   the pack's sake; a real product `.gitignore` belongs to Step 01.
3. **`CLAUDE.md`**: created at the repository root from
   `tms-overnight-pack/templates/CLAUDE.md`. No pre-existing `CLAUDE.md` existed, so
   nothing project-specific had to be preserved. Added sections for the repository
   layout, pointers to the architecture documents, local environment notes
   (missing Maven, unset `JAVA_HOME`, Docker daemon state) and the deferred-scope list.
4. **Architecture record**: created
   `docs/architecture/TMS_ARCHITECTURE_V1.md`,
   `docs/architecture/OWNERSHIP_MATRIX.md`,
   `docs/architecture/ADR-001-layered-architecture.md`,
   `docs/architecture/ADR-002-migration-ownership-flyway.md`,
   `docs/architecture/ADR-003-multitenancy-company-scope.md`.
5. **Component layout recorded** (to be created in Step 01, no meaningful existing
   structure to adapt to):

        frontend/tms-web
        backend/tms-api
        supabase
        docs
        scripts

6. **Architecture decisions recorded** (details in the documents above):
   - React -> Spring Boot -> PostgreSQL/Supabase for all business operations;
   - direct frontend Supabase usage limited to **authentication only** in V1;
   - Supabase owns platform, PostgreSQL, PostGIS, Auth, RLS defense in depth;
   - Java owns business authorization, tenancy, CRUD/use cases, order lifecycle,
     planning, capacity, concurrency, jobs, integrations and audit use cases;
   - **Flyway is the single owner of application DDL**, including indexes, constraints,
     extensions and RLS policies; no duplicate Supabase migration history for application
     tables; Supabase-managed `auth`/`storage` schemas stay Supabase-managed;
   - applied migrations are immutable;
   - EWM is an external product: future API/event integration only, no shared internal
     tables and no cross-product foreign keys.
7. **Initial domain ownership recorded** (not implemented in this step):
   Organization -> Company; `app_user` + `membership` map Supabase `auth.users` to
   business identity and authorization scope; Company owns operational business masters
   and transactions unless an explicit documented reason says otherwise.
8. **No application code generated.** Per the step instruction, scaffolding is Step 01's
   job. This step produced documentation and git-safety verification only.

## 4. Constraint compliance

| Constraint | Status |
|---|---|
| No `git push` | Respected - no network git command was run at all |
| No remote/shared Supabase mutation | Respected - no Supabase command was run |
| No deploy | Respected |
| No secret files read or printed | Respected - none exist; none were opened |
| No destructive Git commands | Respected - only `status`, `branch`, `log`, `remote`, `config` reads |
| No Supabase-only architecture | Respected - ADR-001 explicitly rejects it |
| No OR-Tools yet | Respected - recorded as deferred |
| No MUI as primary frontend library | Respected - Bootstrap + SweetAlert2 recorded |
| Flyway as single migration owner | Respected - ADR-002 |
| Work only inside this repository | Respected |

## 5. Blockers and risks for later steps

### B1 - Docker daemon is not running (affects Steps 02, 03, 12, 13)

`docker --version` succeeds (29.6.2) but the daemon socket is unreachable:

    failed to connect to the docker API at unix:///Users/<user>/.docker/run/docker.sock:
    ... connect: no such file or directory

Impact: **Testcontainers integration tests and Flyway replay evidence cannot run**, and
`supabase start` (local stack) cannot run, until Docker Desktop is started. This is an
environment blocker, not an architecture blocker.

Mitigation: unit tests, compilation and static checks still run. Any step that cannot
produce Testcontainers evidence must say so explicitly rather than claim tests passed.
Starting Docker Desktop requires a human on this machine (it is a GUI application);
the overnight run will not attempt it.

### B2 - Maven is not installed (affects Step 01 onward)

`mvn` is not on `PATH`. The backend must ship a committed Maven wrapper (`mvnw`,
`mvnw.cmd`, `.mvn/wrapper/`). Step 01 must obtain the wrapper as part of scaffolding
(for example from a Spring Initializr archive) rather than assuming a global Maven.

### B3 - `JAVA_HOME` is unset (minor)

Java 21 is on `PATH`, which the Maven wrapper can use. Some tooling still expects
`JAVA_HOME`; export it if a build step fails for that reason.

### B4 - Network reachability unverified

An outbound connectivity check (Spring Initializr, npm registry, Maven Central) was
**denied by the sandbox permission layer in this step and therefore not performed**.
Step 01 is the first step that requires downloading dependencies; if the environment is
offline, Step 01 will fail at dependency resolution and must report that exactly.

### B5 - Remote `origin` is configured

`origin` points at `git@github.com:dcalagua/TMS.git`. It was deliberately left in place.
Every later step must remain push-free. Local commits, if enabled by the supervisor, are
checkpoints only.

### B6 - No commits exist yet

`HEAD` is unborn. Any tooling that assumes a baseline commit (diff-based review, `git
stash`, checkpoint scripts) may behave unexpectedly until Step 01 creates the first commit.

### B7 - No Supabase project credentials in scope

No Supabase URL, anon key or database URL is configured, and none may be read from real
secret files. Steps 02-04 must work against a **local/disposable** PostgreSQL (Docker
required, see B1) and must ship `.env.example` placeholders only.

## 6. Files created by this step

    CLAUDE.md
    docs/architecture/TMS_ARCHITECTURE_V1.md
    docs/architecture/OWNERSHIP_MATRIX.md
    docs/architecture/ADR-001-layered-architecture.md
    docs/architecture/ADR-002-migration-ownership-flyway.md
    docs/architecture/ADR-003-multitenancy-company-scope.md
    docs/overnight/00_BASELINE.md

No files were modified or deleted. No commit was made.

## 7. Handoff to Step 01

Step 01 should:

- create the monorepo layout (`frontend/tms-web`, `backend/tms-api`, `supabase`,
  `scripts`) and a real product `.gitignore`;
- scaffold Spring Boot (Java 21) **with a committed Maven wrapper** (B2);
- scaffold React + TypeScript + Vite + Bootstrap + SweetAlert2 (no MUI);
- add Flyway to the backend and create **no** `supabase/migrations` application DDL;
- verify a compile/build baseline and report honestly if network or Docker blocks it.
