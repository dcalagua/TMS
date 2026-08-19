-- TMS by EBIM - V1 baseline.
--
-- Creates the application schema, the platform extensions the product has already
-- committed to, and the shared helpers every later migration relies on.
--
-- Ownership (ADR-002): this file, and every other file in db/migration, is the single
-- canonical source of application DDL. Supabase carries no parallel history for it, and
-- the Supabase-managed `auth` and `storage` schemas are never created or altered here.

-- ---------------------------------------------------------------------------
-- Application schema
-- ---------------------------------------------------------------------------
-- Every application table lives in `tms`, never in `public`. Reasons:
--   1. Security: the Supabase Data API (PostgREST) exposes only the schemas listed in
--      supabase/config.toml -> [api].schemas = ["public", "graphql_public"]. Keeping
--      application tables out of `public` means they have no HTTP surface at all, which
--      is stronger than relying on RLS policies alone (see V4 and docs/security/RLS_STRATEGY.md).
--   2. Clarity: Supabase platform objects and TMS business objects never share a namespace.
--
-- Flyway is configured with default-schema=tms, so it creates the schema and owns its
-- history table there. The guarded statement below keeps the migration self-contained when
-- it is replayed by another tool against an empty database, without emitting a "schema
-- already exists" warning on the normal path.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'tms') THEN
        EXECUTE 'CREATE SCHEMA tms';
    END IF;
END;
$$;

COMMENT ON SCHEMA tms IS
    'TMS by EBIM application schema. Owned by Flyway (ADR-002). Not exposed through the '
    'Supabase Data API; all access goes through the Spring Boot backend.';

-- ---------------------------------------------------------------------------
-- Extensions
-- ---------------------------------------------------------------------------
-- PostGIS is prepared now, not later: the architecture of record (TMS_ARCHITECTURE_V1
-- section 8) commits Origin and Destination to a generated geography column plus a GiST
-- index in Steps 05/06. Enabling the extension in the baseline means those migrations add
-- columns only, and the test/local images are proven to support it from the first run.
--
-- `IF NOT EXISTS` makes this a no-op when the platform already ships PostGIS:
--   * the postgis/postgis test image installs it during initdb;
--   * a Supabase project may already have it enabled in the `extensions` schema.
-- `WITH SCHEMA public` only takes effect when the extension is genuinely absent, which
-- keeps a fresh database deterministic. Later spatial migrations must therefore resolve
-- the extension schema instead of hardcoding it - see docs/database/MIGRATION_STRATEGY.md.
--
-- gen_random_uuid() is core PostgreSQL from version 13 on, so no pgcrypto is needed.
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;

-- ---------------------------------------------------------------------------
-- Shared helpers
-- ---------------------------------------------------------------------------
-- updated_at is maintained by the database so that it is correct regardless of which
-- writer produced the change (JPA, a repair script, a future job). The WHEN clause on the
-- triggers keeps no-op updates from bumping the timestamp.
CREATE OR REPLACE FUNCTION tms.set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION tms.set_updated_at() IS
    'BEFORE UPDATE trigger function: stamps updated_at with the transaction timestamp.';
