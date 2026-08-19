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

Contents arrive with Step 02 (database and tenancy foundation):

- schema overview and entity relationships;
- the Organization/Company tenancy model and how `company_id` scoping is applied;
- indexing and PostGIS conventions;
- how integration tests provision a disposable PostgreSQL through Testcontainers.
