# TMS by EBIM - migration strategy

Decision of record: **Flyway, inside `backend/tms-api`, is the single owner of application
schema migrations** (ADR-002). This document is the operational form of that decision.

## 1. Layout and naming

    backend/tms-api/src/main/resources/db/migration/
        V1__baseline_schema_extensions_and_helpers.sql
        V2__identity_and_tenancy.sql
        V3__iam_reference_data.sql
        V4__security_grants_and_rls.sql

Rules, enforced by `MigrationConventionTest` (which needs no database, so it runs anywhere):

- file names match `V<n>__<lower_snake_case>.sql`;
- versions are unique and contiguous from 1, so ordering is never ambiguous;
- no migration contains `DROP TABLE/SCHEMA/DATABASE`, `TRUNCATE` or role management
  (`CREATE/ALTER/DROP ROLE`) - roles and credentials are an operations concern;
- no migration issues DDL against `auth`, `storage` or `realtime`;
- no migration inserts tenant data (`organization`, `company`, `app_user`, `membership`,
  `membership_role`) or a credential;
- no migration grants privileges to `anon` or `authenticated`;
- `supabase/migrations` must not exist.

## 2. Immutability

An applied migration is never edited - not to fix a typo, not to add a column. The change
is always a new version. Flyway's `validate-on-migrate: true` turns a violation into a
failed startup rather than silent drift.

`baseline-on-migrate` is `false`: a non-empty schema must be migrated deliberately, never
baselined by accident. `clean-disabled` is `true` in the application and in the test Flyway
configuration, so no process the application starts can wipe a database.

## 3. Schema placement

Everything lives in the `tms` schema:

```yaml
spring:
  flyway:
    schemas: tms
    default-schema: tms
    create-schemas: true
  jpa:
    properties:
      hibernate.default_schema: tms
```

Flyway creates the schema and keeps `flyway_schema_history` inside it. V1 also creates the
schema defensively (guarded by a `pg_namespace` lookup, so no warning is emitted on the
normal path) which keeps the file replayable by another tool.

Running migrations without starting the API:

    ./mvnw flyway:migrate \
        -Dflyway.url=jdbc:postgresql://localhost:54322/postgres \
        -Dflyway.user=postgres -Dflyway.password=<local-only> \
        -Dflyway.schemas=tms -Dflyway.defaultSchema=tms

## 4. Extensions and PostGIS

V1 runs `CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public`.

- On the `postgis/postgis` test image and on a Supabase project that already enabled the
  extension, the statement is a no-op - `IF NOT EXISTS` ignores the schema clause.
- On a database where PostGIS is genuinely absent, it is installed in `public`, which keeps
  a fresh environment deterministic.

**Rule for the spatial migrations of Steps 05/06:** do not hardcode `public.geography`.
Resolve the extension schema and put it on the search path for the duration of the
migration, so the same file works on a Supabase project that installed PostGIS in
`extensions`:

```sql
DO $$
DECLARE ext_schema text;
BEGIN
    SELECT n.nspname INTO ext_schema
    FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
    WHERE e.extname = 'postgis';
    PERFORM set_config('search_path', 'tms, ' || quote_ident(ext_schema) || ', public', false);
END;
$$;
```

`gen_random_uuid()` is core PostgreSQL 13+, so `pgcrypto` is not installed.

## 5. Reference data versus fixtures

| Kind | Example | Where |
|---|---|---|
| Schema-contract reference data | roles, permissions, role-permission grants | Flyway migration (V3) |
| Local development fixtures | demo organization, companies, users, memberships | `supabase/seeds/local_dev_seed.sql`, applied by hand |
| Test fixtures | rows a test needs | test code only |

Production migrations contain no demo user, no tenant and no production-like secret. The
local seed is re-runnable (`ON CONFLICT DO NOTHING`) and is exercised by
`LocalSeedIntegrationTest`, so it cannot rot silently against schema changes.

## 6. Testing

Integration tests use Testcontainers with `postgis/postgis:17-3.5` (PostgreSQL 17, matching
`supabase/config.toml` `major_version = 17`). Override with
`-Dtms.test.postgres.image=<image>`; the image must provide PostGIS.

Each test class creates **its own database** inside the shared container, so "apply the
whole history to an empty database" is a real assertion. Tests never touch a shared or
remote database, and there is no code path that would let them.

What the database tests prove:

1. the whole history applies to an empty database, in order, with no failure;
2. `validate()` passes and a second `migrate()` executes nothing;
3. replaying the history into a second empty database produces an identical fingerprint of
   columns, constraints, indexes, triggers and reference rows;
4. PostGIS is installed and usable;
5. the tenancy constraints refuse what they must refuse;
6. the RLS and grant posture is what `docs/security/RLS_STRATEGY.md` claims;
7. the real Spring Boot context boots with datasource, JPA and Flyway together.

When no Docker daemon is reachable, the container-backed classes are **skipped and reported
as skipped** through `@EnabledIf(DockerAvailability#isAvailable)`. They are never silently
dropped and never fall back to another database.

## 7. Rollback

There are no `U` (undo) migrations. Recovery is forward-only:

- a failed deploy is fixed with a new versioned migration;
- destructive corrections (dropping a column, renaming with data movement) are written as
  an explicit new migration, reviewed as a change of its own, and tested on a restored copy;
- point-in-time recovery is the platform's job (Supabase backups), not Flyway's.

This is deliberate: undo scripts are rarely exercised and give false confidence.

## 8. Supabase relationship

- `supabase/config.toml` sets `db.migrations.enabled = false`, so `supabase db push` and
  `supabase db reset` cannot apply a parallel history.
- `db.seed.enabled = false`, because a reset would seed before Flyway has created anything.
- `supabase db diff` may be used to *inspect* drift; the fix is always a new Flyway
  migration, never an edit to an applied one or a Supabase migration file.
- Schema changes made by hand in Studio are out of process and will be overwritten or will
  cause a validation failure.

## 9. Checklist for a new migration

1. New version number, next in sequence, descriptive snake-case name.
2. Business tables: `company_id NOT NULL` + FK + index leading with it (ADR-003).
3. `created_at`, `updated_at`, `set_updated_at` trigger, actor columns where an actor exists.
4. Explicit constraint names (`pk_`, `fk_`, `uq_`, `ck_`, `ix_`), `ON DELETE RESTRICT`
   unless the row is pure configuration.
5. `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` for every new table, in the same migration.
6. `COMMENT ON` for anything whose reason is not obvious from its name.
7. Tests: constraints, tenant isolation, and the migration replay suite stays green.
8. Never edit an applied file.
