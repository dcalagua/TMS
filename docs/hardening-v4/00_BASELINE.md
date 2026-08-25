# Hardening V4 - baseline

Measured at the start of the hardening session, against the working tree as it stood, before any
file was changed. Everything here was re-counted from `git` and from a real build; nothing is
carried over from the Sellable V4 report.

## Repository state

    BRANCH=dev
    HEAD=b13e660 docs(domain): frequency, route, fleet, order and planning contracts, plus a V2 domain map
    LATEST_MIGRATION=V35
    TRACKED_MODIFIED=147
    UNTRACKED=288
    DELETED=0
    STAGED=0
    TOTAL_WORKING_TREE_ENTRIES=435
    COMMITS_FROM_SELLABLE_V4=0

Migrations `V24`–`V35` exist as files and have never been applied to any database.

## Toolchain actually available

    java     21.0.7 (on PATH)
    node     v24.18.0
    npm      11.16.0
    maven    via ./mvnw.cmd (no global install)
    docker   UNAVAILABLE - the daemon answers 500 on the Desktop Linux engine pipe

Command execution works in this session: `mvnw`, `npm`, `npx`, `git` and `node` all ran. That is
the difference from the Sellable V4 pack, which could write files but not run anything - and it is
why that pack's green claims could not have been true.

## Gate results on the untouched baseline

Both suites were run before any change, and **neither passed**.

### Backend - `./mvnw.cmd -B test`

    BUILD FAILURE
    Tests run: 1157   Failures: 3   Errors: 6   Skipped: 343

The 343 skips are the Testcontainers suites; Docker is unavailable, so they cannot run here.
The nine real failures were:

| Test | Cause |
|---|---|
| `MigrationConventionTest.migrationsContainNoTenantOrUserData` | The guard used `contains("insert into tms.company")`, which matches `insert into tms.company_settings`. A test bug: V34's backfill is legitimate. |
| `RateCardServiceTest.allowsASuccessor`, `.differentAgreementsCoexist` | **Production bug.** `RateCardService.toViews` looked a scope target up with a null key; a lookup port returning `Map.of()` throws on that, so any carrier-wide rate card broke the whole list. |
| `NotificationServiceTest` (×4) | `UnfinishedStubbingException` - a helper that stubs a mock was called inside the `thenReturn(...)` of another stub. |
| `NotificationRecorderTest.argumentsAreStoredAsData` | Asserted a byte-exact JSON string; the mapper orders map entries by key. |
| `TrackingIngestionServiceTest.continues_from_what_is_already_stored...` | The fixture's second point was 55s after the watermark and the floor is 60s, so the expected `RECORDED` was arithmetically impossible. |

### Frontend

    TYPECHECK  FAIL (exit 2) - 130 errors
    LINT       PASS (warnings only)
    TEST       FAIL (exit 1) - 20 failed | 603 passed (623)
    BUILD      FAIL (exit 2) - blocked by the same typecheck

The typecheck failure is the headline finding. The V4 pack shipped the whole driver feature
(migration V26) across `fleet`, `planning` and `trips` **without adding a single locale key for
it**, and the i18n key type is generated from the Spanish bundle - so every `t('drivers.…')` was a
compile error. Because `npm run build` runs `tsc -b` first, the frontend had not been buildable
since the pack landed. Alongside it: two `Record<TripStatus, …>` maps still listing only the three
V1 statuses, a `Record<TransportEventType, …>` missing the tender and delivery events, and
`PageParams` declared as an `interface`, which TypeScript refuses to pass where a
`Record<string, …>` is wanted.

## What this means for the baseline commit

The Sellable V4 tree could not be committed as a baseline in the state it was found: it did not
build and its tests did not pass. Every failure above was fixed first - each one either a real
defect or a test asserting something the product never does - and the baseline commit was made
only once both suites were green. The fixes are listed in `FINAL_REPORT.md`.

## Remote database risk found here

`backend/tms-api/.env` was present again, byte-identical to the `.env.remote.hold` a previous
session had quarantined on 19 August, with `TMS_DB_URL` pointing at a remote Supabase host and
`TMS_FLYWAY_ENABLED=true`. See `docs/development/DATABASE_SAFETY.md`. No value from that file is
reproduced anywhere in this repository, and no database was contacted.
