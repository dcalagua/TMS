# Which database am I about to migrate?

Starting the backend runs Flyway. Flyway does not ask which database it is pointed at, and nothing
in the startup banner says. This page is about making that question answerable before it matters,
and about the control that now answers it for you.

## The accident this is written for

A `backend/tms-api/.env` was found in the working tree with `TMS_DB_URL` pointing at a hosted
Supabase project and `TMS_FLYWAY_ENABLED=true`. It was quarantined; a day later it was back,
byte-identical. Nobody did anything reckless - the file is git-ignored, so it survives every branch
switch, and `./scripts/dev-backend.sh` looks exactly the same whichever database it names.

Had the application been started, Flyway would have created `V1`–`V35` in a shared project. There
would have been no prompt, no dry run and no undo: applied migrations are immutable
(ADR-002), so the recovery is a support ticket, not a command.

## The control

`LocalProfileDatabaseGuard` replaces Flyway's migration step on the `local` profile. Before the
first statement is issued, it reads the host out of the JDBC URL the datasource was actually built
with, and:

- **this machine** (`localhost`, `127.0.0.1`, `::1`, `0.0.0.0`, `host.docker.internal`) - Flyway
  runs as usual;
- **anything else** - startup fails with a message naming `TMS_DB_URL` and this page.

The host is parsed, not pattern-matched. `jdbc:postgresql://localhost@db.example.com/tms` is
`db.example.com`, and a URL the guard cannot parse counts as *not* local - "I could not tell" and
"it is safe" are different answers.

To migrate a non-local database from the local profile on purpose, set `TMS_ALLOW_REMOTE_DB=true`
for that one command. It is left commented out in `.env.example` so that turning it on is always a
decision, and the application logs a warning naming the host when it is used.

The guard is scoped to `local`. On `prod`, migrating a remote database is the entire job.

## What owns what

| | Local | Remote (staging, production, a shared Supabase project) |
|---|---|---|
| Who may run Flyway | any developer, freely | a deployment, deliberately |
| How the schema changes | `./mvnw spring-boot:run`, or the test container | the release pipeline, or a human who decided to |
| What protects it | this guard | the guard is off (`prod` profile); the protection is that nobody has the credentials by accident |
| Cost of a mistake | drop the container | applied migrations are immutable - the fix is a new migration, agreed with whoever owns the data |

`supabase/` holds platform configuration only. Application DDL lives in
`backend/tms-api/src/main/resources/db/migration` and nowhere else (ADR-002), so
`supabase db push` is never how a TMS table is created.

## Rules that no tooling enforces

These are conventions, and they are here because the guard cannot see them.

- **Never point a `.env` at a shared database and leave it there.** If you need a remote read, use
  a separate file you pass explicitly, and delete it when you are done.
- **Never run `supabase db push`, `supabase migration up` or `psql` against a shared project** from
  a development machine without saying so to whoever owns it first.
- **Never edit an applied migration.** If `V27` is wrong and it has run somewhere, the fix is
  `V36`. Editing it makes every deployed checksum invalid and Flyway refuses to start.
- **Never commit a `.env`.** `backend/tms-api/.gitignore` covers it; that is a backstop for the
  habit, not a substitute.

## If you think you have already done it

Do not try to undo it with another migration written in a hurry.

1. Stop the application.
2. Read `tms.flyway_schema_history` on the affected database - it lists exactly what ran and when.
3. Tell whoever owns that data before changing anything else.

The history table is the record of what happened. It is more useful than any reconstruction from
memory, and it is the first thing anybody helping will ask for.
