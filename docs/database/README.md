# Database documentation

**Flyway, under `backend/tms-api/src/main/resources/db/migration`, is the single canonical
owner of application schema migrations** (ADR-002). Supabase must not carry a parallel
migration history for the same application DDL.

Rules:

- applied migrations are immutable - a later change is always a new versioned migration;
- Hibernate never creates or alters schema (`ddl-auto: none`);
- RLS enablement and RLS policies for application tables are Flyway migrations too, as
  defense in depth behind backend authorization;
- the Supabase-managed `auth` and `storage` schemas are never recreated or altered here.

## Documents

| Document | Contents |
|---|---|
| [`DATA_MODEL.md`](DATA_MODEL.md) | Schema of record: entity diagram, tenancy design and its reasons, constraints, indexes, reference data, rules for the next migrations |
| [`MIGRATION_STRATEGY.md`](MIGRATION_STRATEGY.md) | Naming, immutability, schema placement, PostGIS convention, seed policy, Testcontainers setup, rollback stance, per-migration checklist |
| [`../security/RLS_STRATEGY.md`](../security/RLS_STRATEGY.md) | Why the `tms` schema is backend-only, and what RLS does and does not defend |

## Shape in one paragraph

Application objects live in the **`tms` schema**, not in `public`, because the Supabase Data
API only exposes `public` and `graphql_public` - so business tables have no HTTP surface at
all. The baseline creates `organization`, `company`, `app_user`, `membership`,
`membership_role`, `role`, `permission` and `role_permission`, with `company` as the
operational tenant scope (ADR-003). Integration tests provision a disposable PostGIS-enabled
PostgreSQL through Testcontainers and create one database per test class, so every run
proves the history applies to an empty database.
