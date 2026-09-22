# Backup and restore (logical: `pg_dump` / `pg_restore`)

**Written 2026-09-15, against candidate `181be3a` (schema V50).** Every command on this page is
one that was executed, on the cluster and versions stated in §1, and the output quoted is the
output that run produced. Where something was not executed, the page says **NOT VERIFIED** instead
of describing it as if it had been.

This page covers a *logical* backup of one database. It does not cover — and does not pretend to
cover — the Supabase platform's own backups or point-in-time recovery (§10).

---

> ## ⚠ Read this before anything else: the dump does not contain `tms_app`
>
> `pg_dump` dumps **one database**. Roles are **cluster-global**, so the runtime role `tms_app`
> (V13) and the membership that lets the backend enter it (`GRANT tms_app TO <owner> WITH SET
> TRUE`) are **not in the dump**. The dump measured for this page contains **0** `CREATE ROLE`,
> **0** `GRANT tms_app TO`, and **155** references to `tms_app` — every policy and every grant.
>
> Restored into a cluster where `tms_app` does not already exist, the result looks like a success
> and is not one:
>
> | What you check | What you see | Whether it means anything |
> |---|---|---|
> | `pg_restore` exit code | `1` | **No** — a *good* restore also exits `1` (§7) |
> | `tms.flyway_schema_history` | 51 rows, fingerprint identical to the source | **No** |
> | Row counts | identical to the source | **No** |
> | Backend startup | `Successfully validated 51 migrations`, `No migration necessary`, `Started TmsApiApplication` | **No** — V13 is not re-run, because history says it already ran |
> | `GET /actuator/health/readiness` at `181be3a` | `{"status":"UP"} HTTP 200` | **No** |
> | `pg_roles` for `tms_app` | **0 rows** | **Yes** |
> | `pg_policies` in `tms` | **0** (source: 74) | **Yes** |
> | Grants to `tms_app` | **none** | **Yes** |
> | `TenantRuntimeRoleCheck` in the startup log | **`ERROR` … `CANNOT enter 'tms_app'`** | **Yes** |
>
> Every company-scoped request then fails, because `TenantScopedDataSource` enters `tms_app` for
> each one. The only signal is one `ERROR` line in the startup log. **A green health check after a
> restore proves nothing about this.** Run the checklist in §8 every time.
>
> The fix, if you are already there, is §6. The way not to get there is §5.

---

## 1. Verification marks

| Claim | Status |
|---|---|
| `pg_dump -Fc` of a V50 database, restored into a new database **on the same cluster** (roles present) gives a state identical to the source, and the backend boots on it with `'tms_app' can be entered` | **VERIFIED** 2026-09-15 — PostgreSQL 17.10 + PostGIS 3.6.2, portable local cluster, port 55433 |
| The same dump restored into a **new cluster** (only the owner role and PostGIS) silently loses `tms_app`, all 74 policies and all grants; the backend boots and readiness answers 200 UP | **VERIFIED** 2026-09-15 — same versions, second local cluster |
| §6 recovery (create the role, grant `SET`, replay the 154 `POLICY` / `ACL` / `DEFAULT ACL` TOC entries) restores a state identical to the source | **VERIFIED** 2026-09-15 — same versions |
| `pg_restore --exit-on-error` stops at the first PostGIS error and leaves the schema empty | **VERIFIED** 2026-09-15 — same versions |
| The `grep` in §6 step 2 reproduces the 154-entry list used in the verified recovery byte for byte | **VERIFIED** 2026-09-15 — `diff` of the regenerated list against the one used, no output |
| §5 (create the role **before** restoring) | **NOT VERIFIED as a sequence.** Its statements are exactly the ones §6 verified; only the order differs. Reasoned, not run |
| `pg_dumpall --roles-only` as the source of the role | **NOT VERIFIED** — never executed |
| Any restore on **QAS / Supabase** | **NOT VERIFIED** — no access was used for it |
| Whether Supabase's platform backup restores cluster-global roles such as `tms_app` | **NOT CONFIRMED** |
| QAS PITR / retained backups | **NOT CONFIRMED** — unchanged from `QAS_DEPLOYMENT_AND_RECOVERY.md` §4 |
| Restoring over a database that already has a `tms` schema (`--clean`) | **NOT VERIFIED** — every verified target was a freshly created, empty database |
| Behaviour on PostgreSQL other than 17.10 or PostGIS other than 3.6.2 | **NOT VERIFIED** |
| `scripts/ops/verify-restore.sh` | **NOT VERIFIED against a database** — syntax-checked only. Its queries are the ones §8 ran; the wrapper is new |

### What the verified environment looked like

It was built to resemble the shape of Supabase's `postgres` role, because that is the property that
makes this failure invisible on a superuser test database:

| Role | `SUPERUSER` | `BYPASSRLS` | `CREATEROLE` | `CREATEDB` |
|---|---|---|---|---|
| `postgres` (cluster superuser; used only for `CREATE DATABASE` and `CREATE EXTENSION postgis`) | t | t | t | — |
| `tms_owner` (the backend's connection role; ran Flyway V1..V50, ran every `pg_dump`/`pg_restore`) | **f** | **f** | **t** | **t** |

PostGIS was created by the superuser **before** Flyway ran, into `public`. The source database held
a small fixed data set (1 organization, 2 companies, 3 carriers, 4 roles, 60 permissions, 168
role-permission rows). The dump was 586 KB, custom format, 1275 TOC entries. None of this is a
performance statement.

## 2. What a TMS database needs that is not inside it

| Object | Scope | In `pg_dump`? | Created by |
|---|---|---|---|
| Schema `tms`, tables, data, `flyway_schema_history` | database | **yes** | Flyway |
| Policies (`CREATE POLICY … TO tms_app`) | database | yes, **but they name `tms_app`** | V13 and later |
| Grants and default privileges to `tms_app` | database | yes, **but they name `tms_app`** | V13, V49, V50 and every table migration |
| Role `tms_app` (`NOLOGIN`) | **cluster** | **no** | V13 |
| `GRANT tms_app TO <connection role> WITH SET TRUE` | **cluster** | **no** | V13 (`CURRENT_USER`) |
| Connection role (`tms_owner` here, `postgres` on Supabase) | **cluster** | **no** | the platform |
| Extension `postgis` | database | as `CREATE EXTENSION IF NOT EXISTS` | a superuser, before Flyway |

A policy or a `GRANT` naming a role that does not exist is not deferred by `pg_restore`: the
statement fails, the error is counted, and the restore carries on. That is the whole mechanism of
the failure above.

## 3. Procedure A — take the dump

Run as the connection role. It needs no superuser.

```
pg_dump -h localhost -p 55433 -U tms_owner -d tms_verify -Fc -f tms_verify.dump
```

Verified output: nothing on stderr, exit `0`.

- `-Fc` (custom format) is required by §6: only an archive format can be listed with `pg_restore
  -l` and replayed selectively with `-L`. A plain SQL dump cannot be.
- **Record the source fingerprint at the same time** (§8, or `scripts/ops/verify-restore.sh`
  against the source), so that "identical to the source" is a comparison and not a memory.
- Keep the file off the database host and treat it as the data it contains: it holds every
  tenant's rows.

## 4. Procedure B — restore into a cluster where `tms_app` already exists

This is the case where the role survives: another database on the same cluster, or the same
cluster after the database was dropped. **Verified.**

As the superuser (the retained setup file for the new-cluster run used exactly this shape; the
same-cluster run produced `DROP DATABASE`, `CREATE DATABASE`, `CREATE EXTENSION` from it):

```
DROP DATABASE IF EXISTS tms_restored;
CREATE DATABASE tms_restored OWNER tms_owner;
\c tms_restored
CREATE EXTENSION postgis WITH SCHEMA public;
```

Then as the connection role — **without `--exit-on-error`** (§7):

```
pg_restore -h localhost -p 55433 -U tms_owner -d tms_restored tms_verify.dump
```

Verified output, in full. The run used a Spanish locale: `pg_restore`'s own words (`Command was`,
`warning: errors ignored on restore`) are translated here, the SQL and the `ERROR` text are verbatim,
and `PG_RESTORE_EXIT` is the exit status as the run echoed it:

```
pg_restore: error: could not execute query: ERROR:  must be owner of extension postgis
Command was: COMMENT ON EXTENSION postgis IS 'PostGIS geometry and geography spatial types and functions';

pg_restore: error: could not execute query: ERROR:  permission denied for table spatial_ref_sys
Command was: COPY public.spatial_ref_sys (srid, auth_name, auth_srid, srtext, proj4text) FROM stdin;
pg_restore: warning: errors ignored on restore: 2
PG_RESTORE_EXIT=1
```

**Those two errors, and only those two, are the expected outcome** when PostGIS was pre-created by a
superuser and the restore runs without one. Both are harmless: the extension's comment already
exists, and `spatial_ref_sys` was already populated by `CREATE EXTENSION` (8500 rows). Then run §8.

Result verified against the source: history 51 rows with identical fingerprint, identical row
counts, `tms_app` present with `set_option = t`, V49 and V50 privileges intact, 74 policies, 71 RLS
tables, `SET ROLE tms_app` works and sees 2 carriers for company A. The backend on it logged
`Successfully validated 51 migrations`, `Schema "tms" is up to date. No migration necessary.`,
`'tms_app' can be entered`, and readiness answered `{"status":"UP"} HTTP 200`.

## 5. Procedure C — restore into a new cluster: create the role first

**NOT VERIFIED as a sequence** — see §1. The statements are the ones §6 verified, run before the
restore instead of after it.

1. As the superuser, provision the database and PostGIS exactly as in §4. The verified new-cluster
   setup file, verbatim:

   ```
   CREATE ROLE tms_owner LOGIN NOSUPERUSER CREATEROLE CREATEDB PASSWORD '<placeholder>';
   CREATE DATABASE tms_restored_fresh OWNER tms_owner;
   \c tms_restored_fresh
   CREATE EXTENSION postgis WITH SCHEMA public;
   SELECT count(*) AS tms_app_exists_before_restore FROM pg_roles WHERE rolname = 'tms_app';
   ```

   Verbatim apart from the password. The last query returned `0`. **If yours does too, do step 2
   before restoring.**

2. As the connection role (it has `CREATEROLE`):

   ```sql
   CREATE ROLE tms_app NOLOGIN;
   GRANT tms_app TO tms_owner WITH SET TRUE;
   ```

   Replace `tms_owner` with **the role the backend connects as**. V13 grants to `CURRENT_USER`,
   which in this architecture is the same role; a deployment that splits Flyway's role from the
   runtime role must grant it to the runtime role (`docs/security/RLS_STRATEGY.md` §4).

   `WITH SET TRUE` is not optional. On PostgreSQL 16+ the creator of a role gets `ADMIN OPTION`
   but not `SET`, so without it every company-scoped request fails with `permission denied to set
   role` (V13's own comment). The verified state after this step shows **two** membership rows for
   `tms_owner` — one `admin_option = t`, one `set_option = t` — which is also exactly what the
   source database has.

3. Restore as in §4. The expected output is the same two PostGIS errors and nothing else.

4. Run §8.

**Why not `pg_dumpall --roles-only`:** it would carry `tms_app`, but it dumps *every* role on the
source cluster. On a managed platform that includes roles the platform owns and that a
non-superuser cannot create on the target, and a non-superuser may not be able to read role
passwords at all (`--no-role-passwords` exists for that). Writing the two statements above is
smaller and exact. This reasoning was not tested; `pg_dumpall` was never run.

## 6. Recovery — the database was already restored without `tms_app`

Symptoms: §8 shows `tms_app` missing and 0 policies; or the restore printed
`ERROR: role "tms_app" does not exist` (the verified new-cluster restore printed it **153** times,
for **155** errors in total with the two PostGIS ones); or the startup log has the
`TenantRuntimeRoleCheck` `ERROR`.

**Do not follow the log line's suggested fix on its own.** The `ERROR` names
`GRANT tms_app TO "tms_owner" WITH SET TRUE;`, which is correct when the role exists and the grant is
missing. After a restore into a new cluster the role itself does not exist — the verified run, which
logged at `DEBUG`, shows `SET ROLE tms_app was refused during the startup check` with
`ERROR: role "tms_app" does not exist` just before the `ERROR` line; at `INFO` you will not see that
cause — so that grant fails, and even once
the role exists **the policies and grants are still missing**. Creating the role repairs membership;
it does not recreate a single policy.

Verified procedure, with the backend stopped or not serving:

1. **Recreate the role and its membership**, as the connection role (verified file, verbatim apart
   from the comment):

   ```sql
   CREATE ROLE tms_app NOLOGIN;
   GRANT tms_app TO tms_owner WITH SET TRUE;
   ```

   Verified output: `CREATE ROLE`, `GRANT ROLE`.

2. **List the archive and keep only the entries that name the role.** Use the *same dump file*
   that was restored:

   ```
   pg_restore -l tms_verify.dump > c6-toc.txt
   grep -E '^[0-9]+; [0-9]+ [0-9]+ (ACL|DEFAULT ACL|POLICY) ' c6-toc.txt > c6-toc-acl-policy.txt
   ```

   Verified: `c6-toc.txt` 1286 lines (1275 entries plus header); `c6-toc-acl-policy.txt` **154**
   lines — 74 `POLICY tms`, 77 `ACL tms`, 1 `ACL -` (the schema), 2 `DEFAULT ACL tms`. Check your
   count against the number of policies on the source plus its ACL entries before going on; if the
   filter matches nothing, stop — the listing format is not the one this regex was written for.

   Of those 154, one entry (`ACL tms FUNCTION set_updated_at()`) names no `tms_app` and had already
   restored; replaying it produced no error.

3. **Replay only those entries**, as the connection role:

   ```
   pg_restore -h localhost -p 55433 -U tms_owner -d tms_restored_fresh -L c6-toc-acl-policy.txt tms_verify.dump
   ```

   Verified output: nothing on stderr, `PG_RESTORE_EXIT=0`.

   The list files and the exit status of steps 2–3 are retained from the verified run; the two
   command lines are not, and are written here as the only invocations that produce those files
   (`pg_restore -l` output format; `-L` with that list against that database).

4. **Run §8, then start the backend** and look for the `INFO` line.

Verified result: history fingerprint, row counts, membership (two rows, as in the source), V49 and
V50 privileges, **74 policies**, 71 RLS tables and the `SET ROLE` isolation check all identical to
the source.

NOT VERIFIED: replaying the list into a database where *some* policies already exist (a partial
restore). `CREATE POLICY` has no `IF NOT EXISTS`, so expect `already exists` errors for those
entries.

## 7. Reading `pg_restore`: the exit code cannot tell a good restore from a broken one

| Restore | Errors | Exit |
|---|---|---|
| Same cluster, roles present (good) | 2 — the PostGIS pair | **1** |
| New cluster, no `tms_app` (broken) | 155 — the PostGIS pair + 153 × `role "tms_app" does not exist` | **1** |
| §6 replay | 0 | 0 |

So automation must **not** gate on `pg_restore`'s exit status, and must not treat "non-zero" as a
failure either. Capture stderr and classify it: the two PostGIS errors above are expected; **any
other `ERROR` is a failed restore**, and `role "tms_app" does not exist` in particular means §6.
Then run §8, which is the actual gate.

### The `--exit-on-error` trap

```
pg_restore … --exit-on-error …
```

Verified output:

```
CREATE DATABASE
CREATE EXTENSION
pg_restore: error: could not execute query: ERROR:  must be owner of extension postgis
Command was: COMMENT ON EXTENSION postgis IS 'PostGIS geometry and geography spatial types and functions';

PG_RESTORE_EXIT=1
```

It stops at `COMMENT ON EXTENSION postgis`, which the archive restores right after the extensions and
before any function or table, and leaves the `tms` schema **empty**. With PostGIS pre-installed and no superuser, a restore
with `--exit-on-error` never gets past that point, so it is not a stricter mode of this procedure; it
is a procedure that cannot succeed.

`--exit-on-error` (`-e`) is a switch. `pg_restore` 17.10 also accepts the spelling
`--exit-on-error=false` without complaint (checked offline on 2026-09-15 with
`pg_restore --exit-on-error=false -l tms_verify.dump`, exit 0); it was reported by the verifying run
to behave as the switch. **Do not pass it in any form.**

Adding `--single-transaction` (which implies `--exit-on-error`) was not tried; by that implication it
would fail the same way.

## 8. Post-restore checklist

Run against the **source before dumping** and against the **target after restoring**, and compare.
Every query below is read-only; the `SET ROLE` check is inside a transaction that is rolled back.
This is the script the verified runs used (`c6-state.sql`), unabridged in its queries:

```sql
-- 1. Flyway history: count, and a fingerprint of version:checksum:success in order
SELECT count(*) AS migrations, max(installed_rank) AS max_rank,
       md5((SELECT string_agg(version || ':' || coalesce(checksum::text,'null') || ':' || success::text,
                              ',' ORDER BY installed_rank) FROM tms.flyway_schema_history)) AS history_md5
  FROM tms.flyway_schema_history;

-- 2. The runtime role exists, and the connection role may SET to it
SELECT r.rolname, r.rolcanlogin FROM pg_roles r WHERE r.rolname = 'tms_app';
SELECT m.rolname AS member, g.rolname AS granted_role, am.admin_option, am.set_option, am.inherit_option
  FROM pg_auth_members am JOIN pg_roles g ON g.oid = am.roleid JOIN pg_roles m ON m.oid = am.member
 WHERE g.rolname = 'tms_app';

-- 3. V49 grants (expected t, t, t)
SELECT has_sequence_privilege('tms_app','tms.transport_order_number_seq','USAGE') AS v49_order_seq_usage,
       has_sequence_privilege('tms_app','tms.planning_run_number_seq','USAGE')    AS v49_run_seq_usage,
       has_sequence_privilege('tms_app','tms.shipment_number_seq','USAGE')        AS v49_shipment_seq_usage;

-- 4. V50 revocations (expected f, f, f) and two ordinary grants (expected t, t)
SELECT has_table_privilege('tms_app','tms.shipment_outbox_event','UPDATE') AS v50_outbox_update_should_be_f,
       has_table_privilege('tms_app','tms.settlement_approval','DELETE')   AS v50_approval_delete_should_be_f,
       has_table_privilege('tms_app','tms.role','INSERT')                  AS v50_role_insert_should_be_f,
       has_table_privilege('tms_app','tms.role','SELECT')                  AS role_select_should_be_t,
       has_table_privilege('tms_app','tms.carrier','INSERT')               AS carrier_insert_should_be_t;

-- 5. Policies and RLS
SELECT count(*) AS policies FROM pg_policies WHERE schemaname = 'tms';
SELECT count(*) FILTER (WHERE relrowsecurity) AS rls_tables
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'tms' AND c.relkind = 'r';

-- 6. Enter the role and read under a company scope
BEGIN;
SET LOCAL ROLE tms_app;
SET LOCAL tms.company_id = '<a company id that exists in this database>';
SELECT current_user AS in_role, count(*) AS carriers_visible FROM tms.carrier;
ROLLBACK;
```

(The verified script also counted fixture rows by their test codes; those counts are specific to the
test data and are omitted here.)

What the good restore returned, at V50:

| Check | Good (source, §4, §6 result) | Broken (§6 symptom) |
|---|---|---|
| migrations / max_rank / history_md5 | 51 / 50 / `f987f059199c7b2e2d1de90b0a694e93` | **identical** — not a discriminator |
| `tms_app` row | `tms_app`, `rolcanlogin = f` | **no row** |
| membership rows for the connection role | 2: `admin_option = t`; `set_option = t, inherit_option = t` | **none** |
| V49 | `t t t` | `ERROR: role "tms_app" does not exist` |
| V50 | `f f f t t` | `ERROR: role "tms_app" does not exist` |
| policies | **74** | **0** |
| rls_tables | 71 | 71 — not a discriminator |
| `SET LOCAL ROLE tms_app` | `in_role = tms_app`, only that company's rows | `ERROR: role "tms_app" does not exist` |

`history_md5` is specific to the V1..V50 checksums; a different migration set gives a different value,
which is why the check is *source equals target*, not *equals this constant*. The same holds for 74
and 71.

`scripts/ops/verify-restore.sh` runs these queries, fails on the discriminating ones, and compares
against a source fingerprint if you give it one. **It has not been run against a database** (§1).

### Then the backend log

After §8 passes, start the backend against the restored database and find these lines, in this order:

```
o.f.core.internal.command.DbValidate     : Successfully validated 51 migrations
o.f.core.internal.command.DbMigrate      : Schema "tms" is up to date. No migration necessary.
com.ebim.tms.TmsApiApplication           : Started TmsApiApplication
 INFO … c.e.t.s.security.TenantRuntimeRoleCheck  : Database roles: session_user=tms_owner, current_user=tms_owner. 'tms_app' can be entered, so ADR-005 row level security applies to every company-scoped request.
```

**The first three appear on a broken restore too.** Only the fourth discriminates. The broken restore
logged instead:

```
ERROR … c.e.t.s.security.TenantRuntimeRoleCheck  : Database roles: session_user=tms_owner, current_user=tms_owner. This connection CANNOT enter 'tms_app', so every company-scoped request will fail with 'permission denied to set role'. …
```

A migration count other than the source's, or any `DbMigrate` line applying a migration, means the
backend build does not match the dump's schema version — stop and read §9.

### Readiness is not the gate

At `181be3a`, `GET /actuator/health/readiness` returned `{"status":"UP"} HTTP 200` on the broken
restore. A later build may report the role check in readiness; until you have confirmed that the
build you are running does, **readiness UP is not evidence that a restore is good**. The `INFO` line
and §8 are.

## 9. Code rollback on a V49/V50 schema

`QAS_DEPLOYMENT_AND_RECOVERY.md` §2 says reverting the backend to an older build is *expected, not
verified*, and argued it for V44–V48. V49 and V50 change the argument in one direction only — they
remove privileges from `tms_app` — so the question is whether an older build ever uses one of them.

**Conclusion: a build from before V49 should still run on a V50 schema. REASONED, NOT VERIFIED — no
older build was started against a V50 database.**

The reasoning:

1. **V49 only adds.** Three `GRANT USAGE, SELECT` on sequences. An older build calls `nextval` on
   those sequences, so it can only gain from them; where the V13 default privilege had not fired
   (a rebuild under a different role), V49 *repairs* shipment numbering for the older build too.

2. **V50 §3 is behaviour-neutral.** `CREATE OR REPLACE FUNCTION tms.set_updated_at()` has the same
   body, now with `SET search_path = pg_catalog, pg_temp`. The body references only `now()` and
   `NEW.updated_at`. Same OID, so the 34 triggers are unchanged.

3. **V50 §1–§2 revoke verbs, and no pre-V49 build uses them.** The revoked set is: `UPDATE`/`DELETE`
   on `shipment_outbox_event`, `webhook_delivery_attempt`, `settlement_approval`, `payable_export`;
   `DELETE` on `notification`, `company_settings`, `webhook_delivery`, `tender_waterfall`,
   `tender_waterfall_candidate`, `appointment`, `order_delivery_line`; `INSERT`/`UPDATE`/`DELETE` on
   `role`, `permission`, `role_permission`.
   - V50's commit (`0edc18f`) records an audit of the Java side at that point: no repository method,
     `@Modifying` query, JDBC statement, dirty-checked setter or `orphanRemoval` collection issues any
     revoked verb.
   - The last pre-V49 commit is `7bcc400`. Between it and `0edc18f` the only change under
     `backend/tms-api/src/main` besides the two migrations is the new `TenantRuntimeRoleCheck`,
     which issues `SET ROLE`/`RESET ROLE` and no DML (`git diff --stat 6329317^ 0edc18f --
     backend/tms-api/src/main`). So the audit applies to `7bcc400` unchanged.
   - Checked at `7bcc400` for this page: the only raw DML naming one of those tables is
     `NotificationRepository`'s `UPDATE Notification … SET readAt, readBy`, and `notification` keeps
     `UPDATE`. The only `deleteBy…` near them is `ResourceCalendarRepository.deleteByResourceId`,
     which targets a master table V41 deliberately left with `DELETE`. A `git log -G` over the whole
     history of the entity and repository files for those tables, looking for `delete`,
     `orphanRemoval`, `@Modifying`, `.clear()` and `remove(`, finds commits that add only the `orphanRemoval = true` mapping on `TenderWaterfall` and comments stating the
     no-delete rule; nothing clears that collection.
   - Older still: a build from before a table's migration does not know the table exists.

4. **Flyway will not refuse the older build — by default.** The older build has fewer migration
   files than the history table records. `validate-on-migrate: true` is set, and
   `ignore-migration-patterns` is **not** set in `application.yml`, so Flyway's default applies,
   which ignores *future* migrations (applied, newer than any the build contains). Reasoned from
   configuration and Flyway's documented default; not observed.

5. **Two caveats this does not remove.** §2's V44–V48 caveat still stands for builds older than V44.
   And the rollback is a code rollback only: nothing here un-applies V49 or V50, and nothing should
   (`QAS_DEPLOYMENT_AND_RECOVERY.md` §3).

If a rolled-back build does hit a revoked verb, the failure is loud, not silent: SQLSTATE `42501`
(`permission denied for table …`) on a company-scoped request, because that request runs as
`tms_app`. Requests that run as the owner outside a company scope (`WebhookDispatchScheduler`) are
not affected by `tms_app`'s grants at all.

## 10. What this page does not cover

- **Supabase platform backups and PITR.** Whether the QAS project has either is **not confirmed**, and
  no platform restore has been performed. Nothing on this page describes one.
- **Whether a Supabase platform restore brings back `tms_app`.** A platform restore of the whole
  instance may well include cluster roles; a restore into a *new* project may not. **Not confirmed** —
  if it is ever done, §8 is the check, and §6 is the repair.
- **QAS itself.** The verified procedure ran on a local cluster. On Supabase the connection role is
  `postgres` rather than `tms_owner`, `CREATE DATABASE` and extension provisioning work differently,
  and none of that was exercised. Substitute names carefully; do not assume the output will match.
- **Version skew.** Every run used `pg_dump`/`pg_restore` 17.10 against server 17.10. A dump taken
  with an older `pg_dump` than the server, or restored into a different major version, was not tried.
- **Restore duration at production volume.** The verified database was a few hundred kilobytes.
