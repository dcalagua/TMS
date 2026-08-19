# Step 02 - Database, Tenancy and Supabase Foundation

- Date: 2026-08-19
- Attempt: 2 of 3
- Repository root: `/Users/edumorenoccama/Documents/EBIM/TMS`
- Branch: `main`, HEAD at entry: `3200fa7 chore(tms): overnight 01 01 repository bootstrap`
- Working tree at entry: clean

## 1. State inherited, and what attempt 1 left behind

Attempt 1 produced **nothing**. Its log
(`tms-overnight-pack/runtime/logs/02_attempt_1_20260819_025336.log`) contains a single line:

    API Error: 529 Overloaded. This is a server-side issue, usually temporary ...

The working tree was clean, `db/migration/` held only its `.gitkeep`, and no repair was
needed. Nothing from Steps 00/01 was regenerated: the existing `pom.xml`, `application.yml`,
`supabase/config.toml`, docs and scripts were **edited in place**, only where this step
required it (section 8 lists every touched file).

## 2. Environment - the Docker blocker from Step 01 is resolved

Step 01 recorded blocker B1 "Docker daemon not running", and the CLI still fails:

    $ docker info --format '{{.ServerVersion}}'
    failed to connect to the docker API at unix:///Users/edumorenoccama/.docker/run/docker.sock

That is a **CLI context problem, not a dead daemon**. Testcontainers probes other socket
paths and connected on the first try:

    org.testcontainers.DockerClientFactory -- Testcontainers version: 1.21.4
    org.testcontainers.DockerClientFactory -- Docker host IP address is localhost
    org.testcontainers.DockerClientFactory -- Connected to docker:
      Server Version: 29.6.2
      API Version: 1.55
      Operating System: Docker Desktop
      Total Memory: 7935 MB
      Labels: com.docker.desktop.address=unix:///Users/.../com.docker.docker/Data/docker-cli.sock

**Consequence: every database claim in this report is backed by a real PostgreSQL run.**
Nothing here is "compiled but unverified".

For the human: the `docker` CLI itself will keep failing until a context is selected
(`docker context ls`, then `docker context use desktop-linux`). `scripts/check-all.sh` now
says so instead of claiming the daemon is down.

Other tools: Java 21.0.9, Maven Wrapper 3.9.16, Spring Boot 4.0.7, PostgreSQL 17 (test
image `postgis/postgis:17-3.5`, matching `supabase/config.toml` `major_version = 17`).

## 3. What was built

### 3.1 Four migrations, one history

`backend/tms-api/src/main/resources/db/migration/`

| File | Contents |
|---|---|
| `V1__baseline_schema_extensions_and_helpers.sql` | schema `tms` (guarded create), `postgis` extension, `tms.set_updated_at()` trigger function |
| `V2__identity_and_tenancy.sql` | `app_user`, `organization`, `company`, `role`, `permission`, `role_permission`, `membership`, `membership_role` + constraints, indexes, triggers, comments |
| `V3__iam_reference_data.sql` | 29 permissions, 4 roles, 81 role-permission grants. No tenant, user or credential |
| `V4__security_grants_and_rls.sql` | revokes for `PUBLIC` and the Supabase API roles, RLS on all eight tables, schema comment recording the posture |

There is exactly one migration history. `supabase/migrations` does not exist, and
`MigrationConventionTest` fails the build if it ever appears.

### 3.2 Where the schema lives - ADR-004

Application objects are created in the **`tms` schema**, not `public`, because
`supabase/config.toml` exposes only `public` and `graphql_public` through the Data API.
Business tables therefore have no HTTP surface at all. This was a real architectural
decision, so it is recorded as
[`ADR-004-application-schema-and-database-exposure.md`](../architecture/ADR-004-application-schema-and-database-exposure.md)
rather than only as prose.

`application.yml` gained `spring.flyway.schemas/default-schema=tms`, `create-schemas: true`,
`clean-disabled: true`, and `hibernate.default_schema=tms`.

### 3.3 The tenancy model

Implements ADR-003. Full documentation with a Mermaid ERD:
[`docs/database/DATA_MODEL.md`](../database/DATA_MODEL.md).

Decisions worth restating here:

- **`company` is the operational scope.** Business tables from Step 05 on carry `company_id`
  only.
- **`membership` is the one table carrying both `organization_id` and `company_id`**, and
  the migration says why: `company_id IS NULL` means organization-wide scope, and the
  composite FK `(company_id, organization_id) -> company (id, organization_id)` makes
  "the company belongs to this organization" a database guarantee rather than a convention.
- **`app_user` is not tenant-scoped** - a person may work for several organizations, so
  tenancy comes from `membership`.
- **No FK to `auth.users`.** `app_user.auth_user_id` is a unique, nullable uuid. Flyway must
  not depend on the Supabase-managed `auth` schema, TMS stays portable, and the integration
  tests run on plain PostgreSQL where `auth` does not exist.
- **Deletes never erase history.** Every FK is `ON DELETE RESTRICT` except two pure
  configuration links (`role_permission.role_id`, `membership_role.membership_id`), which
  cascade. Long-lived rows are deactivated with `active`.
- **Actor columns only where an actor exists**: `role` and `permission` have none, because
  their rows come from a migration.
- **`updated_at` is stamped by a trigger**, so it cannot be spoofed by the writer.

**Refinement of ADR-003, stated openly:** ADR-003 sketched a single role on the membership
row; this step implements `membership_role` as a link table, because one person routinely
holds several roles in the same company and a single role column would force duplicate
membership rows that the unique indexes correctly forbid. The ADR decision - membership is
the server-side source of truth for tenancy and roles - is unchanged; only its cardinality
is refined. Documented in `DATA_MODEL.md` section 3.4.

### 3.4 RLS and exposure

[`docs/security/RLS_STRATEGY.md`](../security/RLS_STRATEGY.md) is the document of record.
Posture:

1. `tms` is not in `api.schemas`, so PostgREST cannot route to it;
2. `PUBLIC`, `anon`, `authenticated` and `service_role` are revoked from the schema, its
   tables, sequences and functions, including default privileges for future objects (the
   Supabase-role block is guarded by a `pg_roles` lookup so the same file applies to a plain
   PostgreSQL container);
3. RLS is **enabled on all eight tables with no policies** - a complete deny for any
   non-owner role, which is the honest representation of "V1 opens no direct database path".
   No permissive `authenticated` policy was added to look compliant;
4. `FORCE ROW LEVEL SECURITY` is deliberately **not** set: the backend owns the tables and
   must keep working, and Spring Boot is the authorization boundary (architecture 4.2).

A design sketch for real policies - `tms.current_app_user_id()` over `auth.uid()` plus
membership - is in the strategy document together with the five preconditions that must be
met before any policy is written.

### 3.5 PostGIS

V1 runs `CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public`. It is a no-op where the
platform already ships it and deterministic on a fresh database. Verified for real:
`postgis_lib_version()` answers on a database created from `template1` inside the test
container, so the statement genuinely executes rather than short-circuiting.

No spatial column exists yet - Origin/Destination geography columns belong to Steps 05/06.
`MIGRATION_STRATEGY.md` records the rule those migrations must follow: resolve the extension
schema at runtime instead of hardcoding `public.geography`, so the same file works on a
Supabase project that installed PostGIS in `extensions`.

### 3.6 Seed policy

Migrations carry the authorization catalogue only. The demo tenant lives in
`supabase/seeds/local_dev_seed.sql`: one organization, two companies, three users,
memberships and roles, no password and no key, re-runnable through `ON CONFLICT DO NOTHING`.
It is **not** wired into `supabase db reset` - `config.toml` now sets `db.seed.enabled =
false`, because the schema is created by Flyway and a reset-time seed would hit tables that
do not exist yet. `LocalSeedIntegrationTest` applies it to a throwaway database so it cannot
rot against schema changes.

### 3.7 Test infrastructure

`pom.xml` imports the Testcontainers BOM explicitly (Spring Boot 4 does not manage it) and
adds `org.testcontainers:junit-jupiter` and `:postgresql` in test scope.

Version choice, made by querying Maven Central metadata rather than assumption:
`versions:display-property-updates` reports 2.0.5 as newest and **1.21.4 as newest 1.x**.
1.21.4 is pinned, and the property carries the reason: 2.0.x changes API surface, and a
version whose API the committed tests actually exercise is preferable to a blind major
upgrade in an unattended run. Upgrading is a task for a session that can iterate on
container runs.

Container-backed classes are gated by
`@EnabledIf("com.ebim.tms.database.DockerAvailability#isAvailable")`, so on a machine without
Docker they are **skipped and reported as skipped** - never silently dropped, never falling
back to another database. Each class creates its own database inside the shared container,
so "applies to an empty database" is a real assertion.

## 4. Verification - commands run and their output

All executed from `backend/tms-api` unless stated otherwise.

    $ ./mvnw -B clean verify

    Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0 -- SchemaExposureIntegrationTest
    Tests run: 1,  Failures: 0, Errors: 0, Skipped: 0 -- ApplicationDatabaseStartupIntegrationTest
    Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0 -- MigrationConventionTest
    Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- FlywayMigrationIntegrationTest
    Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- TenancyConstraintIntegrationTest
    Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- LocalSeedIntegrationTest
    Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- SystemInfoControllerTest
    Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- ModuleBoundaryTest

    Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
    BUILD SUCCESS   (17.958 s)

23 of those 35 run inside Docker-gated classes against `postgis/postgis:17-3.5`; 12 need no
container.

Frontend, untouched by this step, re-checked anyway:

    $ npm --prefix frontend/tms-web run typecheck     # tsc -b, clean
    $ npm --prefix frontend/tms-web test              # Test Files 2 passed, Tests 5 passed

### 4.1 What the database tests actually assert

| Test | Assertion |
|---|---|
| `FlywayMigrationIntegrationTest.migrationsApplyFromAnEmptyDatabase` | 4 versioned migrations apply in order, all `SUCCESS`, history lands in `tms.flyway_schema_history`, nothing flyway-related appears in `public` |
| `.migrateIsIdempotentAndValidates` | `validate()` passes; a second `migrate()` executes 0 migrations; current version is the highest script |
| `.replayIsDeterministic` | two independent empty databases produce an identical fingerprint of columns, constraints, indexes, triggers and reference rows |
| `.postgisExtensionIsInstalled` | `pg_extension` contains `postgis`; `postgis_lib_version()` answers |
| `TenancyConstraintIntegrationTest` (10) | company code unique per organization and free across organizations; membership cannot bridge two organizations (23503); one membership per user/company and per organization-wide scope (23505); organizations, companies and users carrying history cannot be deleted (23503); membership deletion cascades only its role links; email normalization and shape (23514) and uniqueness (23505); one auth identity maps to one profile; code shape checks; `updated_at` overwritten by the trigger and advancing across transactions; seeded catalogue counts |
| `SchemaExposureIntegrationTest` (6) | RLS enabled on exactly the eight application tables; zero policies; nothing `FORCE`d; `PUBLIC` has no schema/table/function privilege; freshly created `anon` and `authenticated` roles have none either, and `SET ROLE anon; SELECT FROM tms.organization` is refused with SQLSTATE 42501; no application table exists in `public` |
| `ApplicationDatabaseStartupIntegrationTest` | the real Spring Boot context boots with datasource + JPA + Flyway against PostgreSQL, migrates into `tms`, and finds the seeded roles - closing the gap Step 01 could only smoke-test with persistence excluded |
| `LocalSeedIntegrationTest` (2) | the local seed applies to a migrated database, is re-runnable, produces the expected membership graph, and contains no credential |
| `MigrationConventionTest` (7) | naming, contiguous versions, no destructive or role DDL, no `auth`/`storage`/`realtime` DDL, no tenant data or credential in migrations, no grants to API roles, `supabase/migrations` does not exist |

### 4.2 Two real defects found and fixed during the run

1. `updatedAtIsMaintainedByTrigger` failed: `now()` is the **transaction** timestamp, so a
   row inserted and updated in one transaction has `updated_at = created_at`. The trigger is
   correct; the test was wrong. It now proves the property that matters - the trigger
   overwrites a writer-supplied `updated_at` - plus a second test that commits and shows the
   value advancing between transactions.
2. `CREATE SCHEMA IF NOT EXISTS tms` logged a WARN on every fresh database, because Flyway
   creates the schema first. Replaced by a guarded `DO` block that stays self-contained
   without the noise.

Both were fixed before anything was committed; no migration file has been applied to any
persistent environment, so migration immutability is intact.

## 5. Constraint compliance

| Constraint | Status |
|---|---|
| No `git push`, no deploy | Respected - no network git command was run; this step created no commit |
| No remote/shared Supabase mutation | Respected - no `supabase link`, `db push`, `db reset` or remote command. The only databases touched are throwaway Testcontainers databases |
| No real secrets read or printed | Respected - no `.env` exists; the only credential strings in the repository are container-local test values and placeholders |
| No destructive Git commands | Respected - only `status` |
| Work only inside the repository | Respected |
| Inspect before changing | Respected - existing pom/config/docs were edited surgically, nothing regenerated |
| Flyway is the only migration owner | Respected - one history in `db/migration`; `supabase/migrations` absent and asserted absent by a test; `db.migrations.enabled = false` |
| Supabase = platform, Java = business logic | Respected - the database enforces integrity; authorization decisions stay in Spring Boot |
| React business calls through Spring Boot | Unchanged by this step; frontend untouched |
| TMS independent from EWM | Respected - no cross-product table, FK or identifier |
| Applied migrations immutable | Respected - see 4.2; nothing has been applied anywhere persistent |
| Vertical slice check | Not applicable yet: this step has no UI/API surface. The DB -> Security -> Tests portion is covered; UI -> API -> Controller -> Service -> Repository begins in Step 03 |
| Tests claimed only if run | Respected - every number in section 4 is copied from an actual run |
| Bounded implementation | Respected - 8 tables, 4 migrations, 6 test classes, no ORM entities or repositories invented ahead of Step 03 |

## 6. Blockers

None blocking this step.

| Item | Status |
|---|---|
| B1 Docker (from Step 01) | **Resolved in practice.** The daemon is up and Testcontainers uses it. The `docker` CLI needs `docker context use desktop-linux`; this is a workstation convenience issue, not a build blocker |
| Testcontainers 2.0.x | Deliberately not adopted; see 3.7 |
| Supabase local stack | Not started. Not needed: tests use Testcontainers, and starting it would only exercise the same migrations |

## 7. Files created and modified

Created:

    backend/tms-api/src/main/resources/db/migration/V1__baseline_schema_extensions_and_helpers.sql
    backend/tms-api/src/main/resources/db/migration/V2__identity_and_tenancy.sql
    backend/tms-api/src/main/resources/db/migration/V3__iam_reference_data.sql
    backend/tms-api/src/main/resources/db/migration/V4__security_grants_and_rls.sql
    backend/tms-api/src/test/java/com/ebim/tms/database/DockerAvailability.java
    backend/tms-api/src/test/java/com/ebim/tms/database/MigrationScripts.java
    backend/tms-api/src/test/java/com/ebim/tms/database/PostgresTestDatabase.java
    backend/tms-api/src/test/java/com/ebim/tms/database/MigrationConventionTest.java
    backend/tms-api/src/test/java/com/ebim/tms/database/FlywayMigrationIntegrationTest.java
    backend/tms-api/src/test/java/com/ebim/tms/database/TenancyConstraintIntegrationTest.java
    backend/tms-api/src/test/java/com/ebim/tms/database/SchemaExposureIntegrationTest.java
    backend/tms-api/src/test/java/com/ebim/tms/database/ApplicationDatabaseStartupIntegrationTest.java
    backend/tms-api/src/test/java/com/ebim/tms/database/LocalSeedIntegrationTest.java
    supabase/seeds/local_dev_seed.sql
    docs/architecture/ADR-004-application-schema-and-database-exposure.md
    docs/database/DATA_MODEL.md
    docs/database/MIGRATION_STRATEGY.md
    docs/security/RLS_STRATEGY.md
    docs/overnight/02_DATABASE_FOUNDATION.md

Modified:

    CLAUDE.md                                     ADR-004 and the database/security docs in the reference list
    backend/tms-api/pom.xml                       Testcontainers BOM + two test dependencies
    backend/tms-api/src/main/resources/application.yml   Flyway schema settings, clean-disabled, hibernate.default_schema
    supabase/config.toml                          db.seed.enabled = false, with the reason
    supabase/README.md                            tms schema, seed instructions, flyway CLI flags
    docs/README.md, docs/database/README.md, docs/security/README.md   index entries
    scripts/check-all.sh                          accurate Docker message

`backend/tms-api/src/main/resources/db/migration/.gitkeep` was left in place.

## 8. Handoff to Step 03 (backend security foundation)

1. `app_user`, `membership`, `membership_role`, `role`, `permission` and `role_permission`
   exist with the shapes documented in `DATA_MODEL.md`. Map JPA entities to them without
   repeating the schema name - `hibernate.default_schema=tms` is set.
2. The tenancy resolution flow to implement is: validated JWT -> `auth_user_id` ->
   `tms.app_user` -> active `tms.membership` rows -> effective company scope. The index
   `ix_membership_app_user_active` exists for exactly that query.
3. A client-supplied company id must be validated against those memberships (ADR-003).
4. Role codes are `ORGANIZATION_ADMIN`, `COMPANY_ADMIN`, `PLANNER`, `VIEWER`; permission
   codes are `resource:action` strings, generated in the database.
5. `role.scope_level` tells whether a role belongs on an organization-wide or company-scoped
   membership. That pairing is a Java rule; no trigger enforces it.
6. Do not add RLS policies to make something work. If a path needs them, it needs an ADR
   first (ADR-004 compliance rules).
7. Every new business table: `company_id NOT NULL` + FK + leading index, `ENABLE ROW LEVEL
   SECURITY` in the same migration, and a cross-tenant isolation test.

## 9. Result

One Flyway history of four migrations applies to an empty PostgreSQL, validates, replays
deterministically and boots the real Spring Boot context; tenant ownership is coherent and
enforced by the database as well as by design; the application schema is invisible to the
Supabase Data API and denied to `anon`, `authenticated` and `PUBLIC`; demo data is outside
the migration history and tested; 35 backend tests pass with no remote database touched.
