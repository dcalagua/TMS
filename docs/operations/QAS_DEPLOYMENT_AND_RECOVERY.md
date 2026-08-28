# QAS deployment and recovery

**Written for the V36→V48 promotion, 2026-08-28.** Every capability below is one that has actually
been observed. Where a capability could not be confirmed, it says so instead of describing a
procedure nobody has run.

---

## 1. The channels, and what is observable from here

| Piece | Channel | Observable to an engineer without console access? |
|---|---|---|
| Backend | Render, service `tms-api`, Docker, profile `prod` | **No** — no CLI, no API key, no URL in the repository |
| Frontend | AWS Amplify, `amplify.yml` | **No** — same |
| Database | Supabase project `tms-by-ebim`, us-east-1 | **Yes** — SQL and logs |

**The database is the only observable channel**, and it is enough to answer the question that
matters: *did a deployed backend actually start against this database?*

`spring.flyway.enabled` is `true` under `prod` with no variable to switch it off, and the readiness
probe reports UP only after Flyway finishes. So:

> **`tms.flyway_schema_history` advancing from V35 to V48 is direct, non-repudiable evidence that a
> real backend booted against this database.** Nothing else writes that table.

It is a better signal than a deploy dashboard, because it proves the application ran rather than
that a container was built.

## 2. Code rollback

**Possible, with one condition.**

Reverting the backend to a build older than this promotion is safe **only while that build tolerates
schema V48**. It is not automatically true: V44–V48 add tables and columns a previous build does not
know about, which is fine, but they also add **constraints an older service layer may violate** —
`ck_own_fleet_cost_profile_*`, the `EXCLUDE` constraints in V47, and V44's enum CHECK.

A build from before V44 does not write to those tables at all, so in practice a rollback to the
pre-promotion image is expected to work. **Expected, not verified** — nobody has run it.

## 3. Database rollback

**There is none, and none should be attempted.**

- No down-migrations exist, anywhere in the project.
- `flyway clean` is disabled in configuration (`clean-disabled: true`) and must stay disabled.
- **`flyway repair` must never be used to make a checksum error disappear.** It hides divergence
  rather than resolving it; that is what produced the V23 incident this environment was rebuilt for.

Schema is **forward-only**. A mistake in a migration is corrected by a *new* migration.

## 4. Recovery, using what this environment actually has

QAS's real recovery property is that **it is recreatable**, and that has been exercised — the whole
environment was rebuilt on 2026-08-25 rather than patched, and `docs/environments/QAS.md` records
the procedure that worked:

```
1. DROP SCHEMA tms CASCADE;        -- only `tms`; auth/storage/realtime untouched
2. Start the backend with SPRING_PROFILES_ACTIVE=prod against QAS
   -> Flyway applies V1..V48 and writes a fresh history
3. Run supabase/seeds/qas_seed.sql
4. Check: latest = 48, failed = 0
```

Step 2 deliberately uses **the real deployment mechanism, not a SQL client**: what validates the
environment is that *the backend* starts, not that the statements are accepted.

`tms_app`, the `postgis` extension and the `auth.users` accounts survive a `DROP SCHEMA tms` because
they live outside that schema, and the three migrations that create them are idempotent.

### Point-in-time restore

**Not confirmed.** Whether this Supabase project has PITR or retained backups was not verified, and
no restore has been performed. It is recorded as unknown rather than assumed.

## 5. Risk position

| Environment | Position |
|---|---|
| **QAS** | **Acceptable.** The data is disposable and prefixed `QAS-`, the environment is recreatable by a procedure that has been executed, and no production data may exist here |
| **PRD** | **Blocker, unchanged.** A forward-only schema with no tested restore is not an acceptable position for production, and PRD does not exist yet — when it does it will be a *different* Supabase project |

## 6. If the promotion has to be abandoned

In order:

1. **Do not attempt to un-apply V36–V48.** See §3.
2. Roll the *service* back to the previous image if the application must stop, accepting §2's caveat.
3. If the schema is genuinely wrong, **rebuild** (§4). It is faster and more certain than repair, and
   this environment is designed to tolerate it.
4. Correct the defect on `dev`, with a new migration if schema is involved, and promote again.

**Never edit an applied migration**, and never open a SQL client against QAS to "just fix" a table:
the moment the schema stops being the product of `V1..Vn`, this document and the history table are
both lying.
