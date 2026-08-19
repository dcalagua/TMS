# Supabase - local platform configuration

This directory configures the **local** Supabase stack used for development. It is not a
deployment descriptor, and nothing in this repository may be used to mutate a shared or
remote Supabase project.

## What Supabase owns

- Managed PostgreSQL (with PostGIS available) and connection endpoints.
- Supabase Auth as the identity provider and JWT issuer.
- Row Level Security as **defense in depth**.
- Storage and Realtime **later**, only when a concrete requirement and an ADR justify it.

## What Supabase does not own

**Application schema migrations.** Flyway, under `backend/tms-api/src/main/resources/db/migration`,
is the single canonical owner of application DDL - tables, constraints, indexes, extensions,
RLS enablement and RLS policies (ADR-002).

Consequences enforced here:

- there is **no** `supabase/migrations` directory, and one must not be created for
  application tables;
- `db.migrations.enabled = false` in `config.toml`, so `supabase db reset` and
  `supabase db push` cannot apply a parallel migration history;
- `supabase db diff` may be used to *inspect* drift, but the fix is always a **new**
  Flyway migration, never an edit to an applied one.

The Supabase-managed `auth` and `storage` schemas stay Supabase-managed and are never
recreated or altered by Flyway.

## Local configuration choices

| Setting | Value | Why |
|---|---|---|
| `project_id` | `tms-by-ebim` | Distinguishes this stack on a shared workstation |
| `db.migrations.enabled` | `false` | Flyway owns application DDL (ADR-002) |
| `auth.enabled` | `true` | Authentication is the one direct frontend-to-Supabase path in V1 |
| `auth.site_url` | `http://localhost:5173` | Vite dev server |
| `realtime.enabled` | `false` | Explicitly deferred capability |
| `storage.enabled` | `false` | Explicitly deferred capability |
| `edge_runtime.enabled` | `false` | V1 has no Edge Functions; business logic lives in Spring Boot |
| `db.seed.enabled` | `false` | The schema is created by Flyway, not by the CLI, so a reset-time seed would hit tables that do not exist yet |
| `api.schemas` | `["public", "graphql_public"]` | Application tables live in the `tms` schema, which is therefore **not** served by the Data API |

## Starting the local stack

Requires Docker Desktop to be running.

    # from the repository root
    supabase start      # first run downloads container images
    supabase status     # prints local URLs, ports and local-only keys
    supabase stop       # stops the stack (add --no-backup to discard local data)

Default local coordinates:

| Service | URL |
|---|---|
| API gateway (Auth, Data API) | http://localhost:54321 |
| PostgreSQL | `postgresql://postgres:postgres@localhost:54322/postgres` |
| Studio | http://localhost:54323 |
| Inbucket (local mail) | http://localhost:54324 |

Those credentials belong to a throwaway local container. They are development defaults,
not secrets, and they must never be reused for a shared database.

## Applying the schema with Flyway

The Supabase CLI creates an **empty** application schema. Flyway fills it:

    # 1. start the platform
    supabase start

    # 2. migrate it from the backend
    cd backend/tms-api
    ./mvnw spring-boot:run          # Flyway runs at startup on the `local` profile

    # or run migrations without starting the API:
    ./mvnw flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:54322/postgres \
                          -Dflyway.user=postgres -Dflyway.password=postgres \
                          -Dflyway.schemas=tms -Dflyway.defaultSchema=tms

The backend reads its connection settings from the environment (`TMS_DB_URL`,
`TMS_DB_USERNAME`, `TMS_DB_PASSWORD`); see `backend/tms-api/.env.example`.

### Where the tables land

Application objects are created in the **`tms` schema**, never in `public`. Since the Data
API exposes only `public` and `graphql_public`, business tables have no HTTP surface at all.
On top of that, `anon`, `authenticated` and `service_role` are revoked from the schema and
Row Level Security is enabled on every table with no policy, so any accidental exposure
denies rather than leaks. See `docs/security/RLS_STRATEGY.md` and `docs/database/DATA_MODEL.md`.

## Local development data

`supabase/seeds/local_dev_seed.sql` creates one demo organization, two companies, three
users and their memberships. It is **not** a migration and is **not** run by
`supabase db reset` (`db.seed.enabled = false`): apply it by hand after Flyway has created
the schema.

    psql "postgresql://postgres:postgres@localhost:54322/postgres" \
         -f supabase/seeds/local_dev_seed.sql

It contains no password and no key; the Supabase Auth users are created in Studio or with
the CLI, and the backend maps them to `tms.app_user` at first sign-in (Step 03).

## Frontend usage boundary

In V1 the browser talks to Supabase **only** to sign in, refresh and sign out, and to
obtain the JWT it sends to Spring Boot. It does not read or write business tables through
the Supabase client, the Data API, RPC or Edge Functions. Any exception requires a new ADR
that states the authorization model for that path.

## Local Supabase CLI is optional

The Supabase CLI is not required to build or test this repository. Any PostgreSQL 17
instance with PostGIS works for local development, and integration tests use
Testcontainers rather than this stack.
