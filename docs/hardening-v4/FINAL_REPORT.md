# Hardening V4 - final report

Every number in this document was measured in this session, from this working tree, by running the
command that produces it. Nothing is carried over from the Sellable V4 report; where the two
disagree, this one ran the build.

## 1. Executive summary

The Sellable V4 pack produced a large, coherent body of work and left it in a state nobody could
have verified: 435 uncommitted files, zero commits, and a toolchain the pack could not invoke. When
it was first built here, **the frontend did not compile and the backend test suite failed**.

That is the headline. `npm run build` runs `tsc -b` first, and `tsc -b` reported 130 errors - the
drivers feature (migration V26) had shipped across three modules with no locale keys at all, and
the i18n key type is generated from the Spanish bundle. The frontend had not been buildable since
the pack landed. The backend had three failures and six errors, one of which was a genuine defect
that broke the rate-card list for any carrier-wide agreement.

All of it is fixed. Every suite is green - backend, frontend, and the end-to-end run nobody had
executed either - and the working tree is committed in eight readable commits. The session closed
the four automatic-planning findings, gave orders an honest delivery state, put a real control in
front of the remote-database accident that had already happened twice, exposed the audit trail that
had been write-only since migration V22, and fixed a sidebar that had never scrolled on any desktop
screen the menu did not fit on.

What is not done is the database certification: Docker is unavailable on this host, so 343
Testcontainers tests skip and migrations V24-V35 have never been replayed anywhere. That is an
environment blocker, not a defect, and it is the single reason this session's status is PARTIAL
rather than PASS.

## 2. The baseline that was found

See [`00_BASELINE.md`](00_BASELINE.md) for the full record. In short:

    BRANCH=dev   HEAD=b13e660   LATEST_MIGRATION=V35
    TRACKED_MODIFIED=147   UNTRACKED=288   DELETED=0   TOTAL=435
    COMMITS_FROM_SELLABLE_V4=0

    backend    BUILD FAILURE   1157 run, 3 failures, 6 errors, 343 skipped
    typecheck  FAIL (exit 2)   130 errors
    lint       PASS            13 warnings
    test       FAIL (exit 1)   20 failed | 603 passed
    build      FAIL (exit 2)   blocked by typecheck

Command execution worked in this session - `mvnw`, `npm`, `npx`, `node` and `git` all ran. That is
the difference from the pack, which could write files but not run anything, and it is why the
pack's green claims could not have been true.

## 3. Remote database safety

`backend/tms-api/.env` was present again, byte-identical to the `.env.remote.hold` a previous
session had quarantined on 19 August, with `TMS_DB_URL` pointing at a hosted Supabase project and
`TMS_FLYWAY_ENABLED=true`. Starting the backend would have created the whole schema there without
asking. No value from that file appears anywhere in this repository and no database was contacted.

Two things were done about it.

**Quarantined again.** `.env` was moved back to `.env.remote.hold` (identical content, nothing
lost, nothing deleted).

**Replaced the convention with a control.** `LocalProfileDatabaseGuard` intercepts Flyway's
migration step on the `local` profile, reads the host out of the JDBC URL the datasource was
actually built with, and refuses to migrate anything that is not this machine unless
`TMS_ALLOW_REMOTE_DB=true` is set for that run. It runs before the first statement is issued, so
there is no window in which the schema has already been touched. The host is parsed, not
pattern-matched: `jdbc:postgresql://localhost@db.example.com/tms` is `db.example.com`, and a URL
the guard cannot parse counts as not-local. 14 tests, no Docker needed.

The reasoning, the local/remote ownership split and what to do if a shared project was already
touched are written down in [`../development/DATABASE_SAFETY.md`](../development/DATABASE_SAFETY.md).

    REMOTE_ENV_ACCIDENTAL_FLYWAY_RISK=PASS

## 4. Commits created

Nine, all local, none pushed - the eight below, plus the one carrying this report.

| | |
|---|---|
| `d50c90d` | **feat: establish sellable v4 hardened baseline** - the pack, made green first. Carries the repairs below, the automatic-planning hardening, the order delivery dimension and the database guard, because the tree could not be split into commits that each build until those existed. |
| `4268160` | **feat(audit): a read-only, tenant-scoped audit history** |
| `6832a05` | **test(i18n): fail the build when Spanish and English drift apart** |
| `31ccad3` | **perf(web): load each screen when it is opened** |
| `2046b46` | **test(db): prove row-level security is on without needing a container** |
| `37371e4` | **docs: correct the scheduling notes and write down what TMS already reports** |
| `0099426` | **fix(web): the desktop sidebar could not scroll, so its last entry was unreachable** |
| `a785eb6` | **test(e2e): run the suite against a built bundle instead of the dev server** |

The baseline commit is larger than the seven that follow it, and that is deliberate rather than
tidy. Splitting a tree that does not compile into commits that each compile means inventing
history; one honest baseline plus separately reviewable work on top of it is what the tree could
actually support. Its message names everything inside it.

Nothing sensitive is committed: `.env` files are ignored and were checked for by name and by
content pattern before each commit. The overnight prompt packs are now named in `.gitignore`, so
`git add -A` cannot sweep them in.

## 5. What was repaired to make the baseline green

### Backend (9 failures)

| Finding | Verdict |
|---|---|
| `RateCardService.toViews` looked a scope target up with a null key. A lookup port answering `Map.of()` throws on a null key, so a single carrier-wide rate card broke the entire list. | **Real defect.** Fixed with a null-guarded lookup. |
| `MigrationConventionTest` matched `insert into tms.company_settings` as a violation of a rule about `tms.company`. | Test bug - substring where a token was meant. V34's backfill is legitimate. |
| `NotificationServiceTest` (×4) - a helper that stubs a mock was called inside another stub's `thenReturn`. | Test bug (`UnfinishedStubbingException`). |
| `NotificationRecorderTest` asserted a byte-exact JSON string against a mapper that orders map entries by key. | Test bug - now asserts the data, which is the claim it was making. |
| `TrackingIngestionServiceTest` expected `RECORDED` for a point 55s after the watermark, with a 60s floor. | Test bug - arithmetically impossible. Fixed to test the boundary deliberately. |

### Frontend (130 type errors, 20 test failures)

- **The drivers feature had no locale keys.** 28 keys across `fleet`, plus the driver-related keys
  in `planning`, `trips` and the `driverLicenseStatus` vocabulary. Added through a generator script
  (`scripts/i18n/upsert_locale_keys_hardening_v4.js`) so both languages gain identical key paths.
- **Stale exhaustive maps.** Two `Record<TripStatus, StatusTone>` still listed the three V1
  statuses; one `Record<TransportEventType, …>` predated tendering and delivery. The status-tone
  catalogue moved from `pages/trips/tripStatus.ts` to `shared/ui/statusTones.ts` - it was already
  imported from three page areas, and the two stale copies in planning existed precisely because it
  was not somewhere planning could reach.
- **`PageParams` was an `interface`,** which TypeScript will not pass where a `Record<string, …>`
  is wanted, so four integration API calls could never have compiled.
- **Test bugs**: es-ES decimal separators in a UI formatted for es-PE (Peru writes `154.43`); a
  problem code (`invalid-request`) that `ApiExceptionHandler` never emits; a `findByRole('status')`
  that resolved against a loading spinner; queries that matched a filter's selected value as
  readily as a table badge; a KPI test awaiting a label that renders while the query is still
  pending.

`enums.test.ts` now covers `driverLicenseStatus` and `orderFulfillmentStatus`. Its absence is
exactly how a whole feature reached the repository with no labels: nothing asserted that vocabulary
had any.

## 6. Automatic planning

All four findings closed, plus the missing test coverage.

**The applied report was built from the proposal, not from the writes.** If `TripService` refused
an order - because another planner took it between the snapshot and the write - the order was added
to `unplanned` while the *proposed* trip list, which still contained it, was returned unchanged. The
planner saw the same order as both planned and unplanned, and the "planned" half was a lie. The
applied result is now assembled from what each `assignOrder` call actually accepted, and
`assertEveryOrderAccountedFor` - which previously guarded only the proposal - now guards the outcome
too.

**A trip that kept nothing survived as an empty draft holding its vehicle.** For the rest of the
day that vehicle was reported busy by a trip carrying nothing, so the next run planned around
capacity it actually had. It is now cancelled through `TripService.cancel`, which is what releases
the booking - and a test asserts the vehicle is free afterwards, not merely that the trip is gone.

**A lost race reported `NO_VEHICLE_AVAILABLE`,** sending a dispatcher to look for a truck when the
answer was "reload the board". It has its own reason now, `TAKEN_WHILE_PLANNING`, with labels in
both languages.

**`UUID.randomUUID()` stood in for "exclude nothing"** in two double-booking checks. It reads as an
exclusion that happens never to match, and a reader has to reconstruct the argument before they can
believe the line. Replaced by a repository method that asks the question without an exclusion.

`AutoPlanningServiceTest` is new: **19 cases**, running the real `HeuristicPlanningEngine` against an
in-memory trip board that can be told to refuse a named order, with no database and no Spring
context. Every case asserts the input invariant - that each order in the snapshot comes back exactly
once, on a trip or in `unplanned`, never both, and that what the view calls planned is what the
board actually holds.

    AUTOPLANNING_SERVICE_TESTS=PASS
    AUTOPLANNING_NO_DUPLICATE_OUTCOME=PASS
    AUTOPLANNING_NO_EMPTY_TRIPS=PASS
    AUTOPLANNING_INPUT_INVARIANT=PASS

## 7. The order delivery model

`OrderStatus` ends at `PLANNED`, so an order that had been delivered, refused or brought back still
read "Planificado" on the Orders screen. The question this session had to answer first was whether
`OrderStatus` is a planning lifecycle or a fulfilment one.

**It is a planning lifecycle**, and it was left that way. Its four states answer "may this go on a
truck?", the planner's board is built on it, and an order stays `PLANNED` through every delivery
outcome - none of which change whether it was planned. Adding `DELIVERED` to it would make every
planning query carry a fulfilment meaning it never asked for, and the first status a report needed
that planning does not have (`PARTIALLY_DELIVERED`) would have proved the mistake.

So orders gained a **second dimension**: `OrderFulfillmentStatus`, with `PENDING`, `DELIVERED`,
`PARTIALLY_DELIVERED`, `REJECTED`, `FAILED` and `NOT_ATTEMPTED`. Every value is one the domain can
actually compute - the five are a projection of `DeliveryResult`, and `PENDING` means no delivery
row exists.

**Derived, not stored.** `tms.order_delivery` (migration V28) already records what happened to each
order at each stop, and it is the fact. A column on the order would be a second copy, kept in step
by whoever remembered to, and the day the two disagreed there would be no way to say which was
right. It is computed on read through a new `OrderFulfillmentPort`, batched per page like every
other lookup. **No migration was needed** - nothing new is recorded, only something already
recorded is now shown.

The screens show both, labelled, side by side: an order can be `PLANNED` and `REJECTED` at the same
time and both are true.

    ORDER_DELIVERY_STATE=PASS

## 8. Audit trail

The trail has been written on every business action since migration V22 and there was no way to
read it. `GET /audit-events` and a Seguridad → Auditoría screen now expose it.

Read-only is structural rather than a convention: no write verb on the controller, no write method
on the service, no control on the screen - because `tms.audit_event` revokes UPDATE and DELETE from
the runtime role. Tests assert the absence rather than leaving it to whoever adds the next endpoint.

The tenant cannot come from the request. `AuditFilter` has no `companyId` field - asserted
structurally, because the day somebody adds one is the day the tenant becomes something a caller
sends - and the specification takes the company as its own argument.

Filters are the ones V22's two indexes already serve: a date window, an actor, a resource type and
id, an action, a correlation id. Nothing searches `metadata`. Newest first with `id` as the
tie-breaker, so two entries written in the same millisecond cannot appear on two pages.

`audit.log:read` needed no migration: V3 seeds it and grants it to `ORGANIZATION_ADMIN` and
`COMPANY_ADMIN`, and not to `PLANNER`.

**23 tests** (14 service, 9 screen), none needing Docker. The database-level tenancy proof would be
a Testcontainers test and is not written; the service-level proof that the company predicate is
always applied is, and it is the one that can run here.

    AUDIT_READ_API=PASS
    AUDIT_READ_UI=PASS

## 9. Frontend quality

**i18n parity.** A new guard compares key *paths* per namespace in both directions, and fails on a
blank translation. Never by count - two bundles each missing one of the other's keys have identical
counts and are both wrong. 20 namespaces, 1790 keys each side, exact parity.

**Bundle.** Every route was a static import. Measured on `npm run build`:

    FRONTEND_BUNDLE_BEFORE=1,349.09 kB   (gzip 358.43 kB, one chunk)
    FRONTEND_BUNDLE_AFTER=1,000.41 kB    (gzip 294.74 kB, + 38 on-demand chunks)

A 26% reduction in what every user downloads before the login form renders, and the rest arrives
only when opened. Split by feature area, not by component. The largest route chunk is the trip
workspace at 52.66 kB.

**Lint.** 13 warnings, unchanged from the baseline - the 22 my own lazy imports introduced were
removed by moving them to their own module, which also restores Fast Refresh. The remaining 13 are:

- 6 `react(only-export-components)` in `ThemeProvider`, `AuthContext`, `CompanyContext` and
  `Pagination` - each exports a hook or a constant beside its component, which is the normal shape
  of a context module. Splitting them would move a hook away from the provider it belongs to for a
  dev-server convenience.
- 5 `react(incompatible-library)` - React Compiler on `react-hook-form`. External; not fixable here.
- 2 `react(set-state-in-effect)` in `ControlTowerPage` and `ReportsPage`. These are real code
  smells and are listed as P3 below.

None is a defect and none was suppressed.

**Drift.** `navConfig` still described origins and destinations as V14 compatibility projections;
V23 finished turning them into operational *uses* of one canonical location. Two tendering comments
still claimed nothing runs on a timer, which stopped being true when `WebhookDispatchScheduler`
landed. Both corrected.

### The sidebar has never scrolled on desktop

Adding one menu entry made the last one unreachable, which is how this was found.

Bootstrap ships `.offcanvas-lg .offcanvas-body { overflow-y: visible; flex-grow: 0 }` above the lg
breakpoint - it is undoing the drawer, because at that width the sidebar is a static column and not
an overlay. Two class selectors beat the one in `.tms-sidebar-body`, so the column's own
`overflow-y: auto` never applied on any desktop screen. Measured on a 1440×900 viewport:

    .tms-sidebar-body   clientHeight 776   scrollHeight 1232   overflow-y: visible
    scrollTop = 9999  ->  scrollTop stays 0        (the column cannot scroll)
    the last entry sits at viewport y = 1294, and neither the column nor the page reaches it

Nothing looked wrong while the menu fitted in the window; the overflow was simply drawn past the
bottom of the page. The Configuración group had three items and fitted by luck. A fourth was
enough to put an entry permanently out of reach - not hard to click, *impossible*, on any screen
around 900px tall.

Fixed with a rule at matching specificity, scoped to the sidebar so no other offcanvas is affected,
and the nav's bottom padding moved inside the scroll container where scrolling can reach it. The
E2E navigation suite now walks all nineteen entries including the four in that group, which is what
would have caught this the first time.

**While there:** `capability` on a menu *item* was decorative. Groups were filtered by capability
from the start and items were not, so the integration hub - which has carried its own
`INTEGRATION_VIEW` since job 12 - rendered for anyone who could see the group. Hiding is UX and
never the control, but a menu entry that answers 403 when clicked is a menu that lies. Items are
now filtered the same way groups are.

    I18N_PARITY=PASS
    I18N_PARITY_GUARD=PASS

## 10. Tracking and tendering

**Tracking** is a contract and nothing more, exactly as ADR-007 says: a normalised position record,
`TrackingIntakePort` for feeds that push, `TrackingProviderPort` for those that poll, and
`DisabledTrackingProvider` as the only implementation. There is no vendor adapter and writing one
still needs a concrete customer requirement. Sampling, retention and tenancy are enforced in
`TrackingIngestionService`; the map degrades to a list when a stop has no coordinates.

**Tendering expiry** was audited and left as designed. There is no scheduler materialising a lapse.
Every read reports a sent offer past its deadline as `EXPIRED` (`TripTender.effectiveStatus`), and
every refusal - including the one that rejects a late acceptance - is computed from the effective
status, not from the stored column. So an expired offer cannot be accepted whether or not the table
has caught up; `resolveLapse` materialises it opportunistically on the next successful write, which
is what frees the live slot and puts it in the audit trail. The invariant holds without a worker,
and a worker is not worth adding until something needs the lapse to be visible without a read.

    TRACKING_CONTRACT=PASS
    TRACKING_PROVIDER_ADAPTER=NOT_IMPLEMENTED_BY_DESIGN

## 11. Tenancy and RLS

`Organization → Company → tenant-scoped domain data` is intact. Services take a `CompanyScope`, never
a company id from the client; repository predicates carry it; `ModuleBoundaryTest` and
`LayeringTest` still pass; no filter object anywhere accepts a tenant.

Every RLS enforcement test is a Testcontainers test, so all of them skip here. That is the machine
on which a table gets added without its `ENABLE ROW LEVEL SECURITY` and nothing says so, so a
static guard was added: every `CREATE TABLE tms.x` in the migration history must have a matching
`ENABLE ROW LEVEL SECURITY`. **All 51 tables pass.** It proves the statement is present, not that
the policy admits the right rows - that remains `TenantRlsIsolationIntegrationTest`'s job, and that
one needs a database.

    TENANT_MODEL=PASS
    RLS_STATIC=PASS

## 12. Migrations

    LATEST_MIGRATION=V35

V1-V23 untouched. **V24-V35 were not modified.** They were examined - V34's `company_settings`
backfill was the one that tripped a convention test, and the test was wrong, not the migration - and
no migration needed a correction, so no V36 was written. None of them has been applied to any
database, here or anywhere.

## 13. Tests

Real counts, from the final run of each command.

| Gate | Command | Result |
|---|---|---|
| Backend | `./mvnw.cmd -B test` | **1205 run, 0 failures, 0 errors, 343 skipped** |
| Frontend unit | `npm test` | **674 passed** (75 files) |
| Typecheck | `npm run typecheck` | **PASS** |
| Lint | `npm run lint` | **PASS** (13 warnings) |
| Build | `npm run build` | **PASS** |
| E2E | `npx playwright test --workers=2` | **71 passed, 0 failed** (2.2 min) |

The 343 skips are the Testcontainers suites. They are reported as skipped and never as passed.

Net new tests this session: 19 automatic planning, 14 database guard, 14 audit service, 9 audit
screen, 42 i18n parity, 1 static RLS guard, plus the repairs.

### The E2E suite was not passing either, and now runs on a built bundle

Nobody had run Playwright against the Sellable V4 tree. It was asserting a `Seguridad` entry at
`/admin/security` that the pack had removed, and clicking a "Nuevo destino" that the empty state
now also offers - two failures that had been sitting there.

Then, once the routes were code-split, it began failing two tests per run and *a different two each
time*: the dev server serves unbundled ESM and transforms each module on first request, so a suite
that visits nine screens at six breakpoints made it transform a fresh route graph over and over
while two workers competed for it. Raising the timeouts only moved which tests failed.

`vite preview` removed the cause - the same files a deployment serves, from disk, already bundled:

    dev server   68 passed, 3 failed   5.9 min
    dev server   69 passed, 2 failed   7.5 min   (fresh server, cleared dep cache)
    preview      71 passed, 0 failed   2.2 min

Three times faster, no flakes, and it now exercises what actually ships. The 30s/7s budgets are
back at their original values, because what they were compensating for is gone.

## 14. What is blocked

    DB_CERTIFICATION=BLOCKED_ENVIRONMENT

`docker info` fails on this host - the Desktop Linux engine pipe answers 500. Per the brief, no WSL
was installed, no BIOS setting touched and no Windows feature changed. The consequence is precise
and worth stating plainly:

- **Flyway V1→V35 has never been replayed.** The migrations are syntactically checked by
  `MigrationConventionTest` and reviewed, and that is all. A syntax error, a broken constraint or a
  V24-V35 ordering problem would not have been caught here.
- PostGIS, RLS enforcement, the `tms_app` role, cross-tenant isolation, schema exposure, double
  booking, idempotency and the vertical smoke flow are all unproven on this host.

This is the single reason the session's status is PARTIAL. On a machine with Docker, `./mvnw.cmd -B
verify` is the whole certification and `DB_TEST_SKIPPED` should come back 0.

## 15. Findings

**P0: 0. P1: 0 open.**

Three P1s were found and all three are fixed: the frontend was unbuildable, `RateCardService` threw
on any carrier-wide rate card, and the desktop sidebar could not scroll, which put its last entry
permanently out of reach on a 900px-tall screen. A fourth - the remote `.env` - is neutralised and
now has a control in front of it.

**P2 (3 open)**

1. **The initial bundle is still 1,000 kB.** What is left is React, the router, the query client,
   Supabase's auth client and *both* language bundles. Splitting the locales by active language is
   worth about 130 kB and was left for its own change because it touches application boot.
2. **The audit read path has no database-level tenancy test.** The service-level proof exists and
   runs here; the Testcontainers proof that the query actually returns one company's rows does not
   exist yet. Write it with the rest of the DB suite.
3. **Migrations V24-V35 have never been replayed.** Carried from §14 because it is a real risk to
   any deployment, not only a gap in this session.

**P3 (3 open)**

1. **`vite.config.ts` sets `build.sourcemap: true` with no comment.** The production build emits
   4.9 MB of source maps, which publishes the entire frontend source to anyone who opens
   dev tools on a deployed origin. That may be intended; it is not written down anywhere, and it
   should be one or the other.
2. **Two `react(set-state-in-effect)` warnings** in `ControlTowerPage` and `ReportsPage`. Filter
   state synchronised in an effect where it could be derived during render.
3. **`.env.remote.hold` and `.env.test-user` remain on disk.** Git-ignored and inert, but they are
   why `.env` came back once already. Deleting them is a human decision, not one to take here.

## 16. Readiness

    DEMO_READY=YES
    REMOTE_DEPLOYMENT_READY=NO
    PRODUCTION_READY=NO

**Demo.** Both suites are green, the frontend builds, the E2E suite drives real screens, and the
product now tells the truth about delivered orders instead of showing them as merely planned.
Everything demonstrable runs against a local database.

**Remote deployment: no,** and for one reason. Nothing has proved that V1→V35 applies to an empty
PostgreSQL. Until `./mvnw.cmd -B verify` has run somewhere with Docker, a deployment would be
finding out during the deployment. That is the only blocker of this kind - the code, the security
model and the tenancy are in the state they claim to be.

**Production: no.** Beyond the certification, a production deployment wants the migration replay
proven, an operational decision on source maps, and a first load under a megabyte.

**No database was touched.** This session was not authorised to apply anything remotely and did
not: `REMOTE_DB_MUTATED=NO`, and nothing was pushed.

## 17. Next work, in order

1. **Certify the database.** On a machine with Docker: `./mvnw.cmd -B verify`, and expect
   `DB_TEST_SKIPPED=0`. Everything else on this list is smaller than this one.
2. **Write the audit trail's Testcontainers tenancy test**, beside the ones that already exist for
   integration and outbox isolation.
3. **Split the locale bundles by active language.** ~130 kB, and the last easy win in the entry
   chunk.
4. **Decide about source maps** and write the decision into `vite.config.ts` either way.
5. **Then grow again.** Nothing on this list is a feature, which is the point: the pack's work is
   now consolidated, tested, traceable and committed, and the next module starts from a baseline
   that somebody has actually run.

## 18. Gates

```
SELLABLE_V4_BASELINE_PRESERVED=PASS
REMOTE_ENV_ACCIDENTAL_FLYWAY_RISK=PASS
COMMAND_EXECUTION_AVAILABLE=PASS

AUTOPLANNING_SERVICE_TESTS=PASS
AUTOPLANNING_NO_DUPLICATE_OUTCOME=PASS
AUTOPLANNING_NO_EMPTY_TRIPS=PASS
AUTOPLANNING_INPUT_INVARIANT=PASS

ORDER_DELIVERY_STATE=PASS

AUDIT_READ_API=PASS
AUDIT_READ_UI=PASS

I18N_PARITY=PASS
I18N_PARITY_GUARD=PASS

FRONTEND_BUNDLE_BEFORE=1349.09 kB
FRONTEND_BUNDLE_AFTER=1000.41 kB

TRACKING_CONTRACT=PASS
TRACKING_PROVIDER_ADAPTER=NOT_IMPLEMENTED_BY_DESIGN

TENANT_MODEL=PASS
RLS_STATIC=PASS

LATEST_MIGRATION=V35

BACKEND_TESTS=1205
BACKEND_FAILURES=0
BACKEND_ERRORS=0
BACKEND_SKIPPED=343

FRONTEND_TESTS=674
FRONTEND_FAILURES=0

E2E_TESTS=71
E2E_FAILURES=0

TYPECHECK=PASS
LINT=PASS
BUILD=PASS

DB_CERTIFICATION=BLOCKED_ENVIRONMENT
REMOTE_DB_MUTATED=NO
PUSH_PERFORMED=NO

COMMITS_CREATED=9

P0=0
P1=0
P2=3
P3=3

DEMO_READY=YES
REMOTE_DEPLOYMENT_READY=NO
PRODUCTION_READY=NO

FINAL_STATUS=PARTIAL
```

`FINAL_STATUS=PARTIAL` and not `PASS` for one reason and one only: `DB_CERTIFICATION` is blocked
because Docker is unavailable on this host, so migrations V1-V35 have never been replayed. Every
gate that could be executed was executed and passed. No number above was carried over from an
earlier report; each is the output of the command named beside it in section 13.
