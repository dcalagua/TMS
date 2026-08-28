# TMS OVERNIGHT JOB 01 RESULT

RESULT:
PASS

STOP_CHAIN:
false

## Objective

Reconstruct the functional truth of TMS by EBIM from code rather than from prior reports, publish
it as a capability map, correct the authoritative documentation that had drifted, and establish a
measured test baseline for the jobs that follow.

No large features were to be implemented, and none were.

## Initial diagnosis

Working tree clean at `0757afb` on `dev`. No `TMS_OVERNIGHT_MASTER_LOG.md` existed, so this is a
fresh chain starting at JOB 01, not a resume.

The system is far larger and healthier than a first reading of the historical documents suggests:

- 635 main and 124 test Java files across 11 modules, 41 REST controllers.
- 35 contiguous Flyway migrations, V1 through V35. **Next available: V36.**
- 67 frontend page files across 10 areas, 22 routed screens.

One material contradiction was found between the governing documentation and the code, and several
capabilities the master prompt treats as future work turned out to already exist.

## Existing functionality reused

Discovered already built and tested, and therefore **not** to be rebuilt by later jobs:

- **`PlanningEngine` port** with `HeuristicPlanningEngine` already naming itself `HEURISTIC_V1`.
  JOB 05's "keep V1, add V2 beside it" is a one-implementation change, not a restructuring.
- **`trip_order_assignment.whole_order`** with a partial unique index scoped to
  `status = 'ACTIVE' AND whole_order`. V11 wrote it that way on purpose so split allocation could
  arrive later; JOB 03 can add ship units without touching an applied migration.
- **`OrderDelivery` / `DeliveryResult`** (V28) already model delivered, partial, rejected, failed
  and not-attempted per order per stop, with POD evidence behind `EvidenceStoragePort` (ADR-006).
  JOB 02 must *consume* these facts, not re-model them.
- **`TripStatus`, `TenderStatus`, `StopExecutionStatus`** each carry an explicit transition table in
  the domain, asserted at the service and entity layers with DB CHECK constraints beneath.
- **Integration inbox** with client credentials, scopes and idempotency keys (V18, V20). JOB 13 adds
  operations on top of it and rebuilds nothing.

## Architecture/design

No architectural change was made to running code. One decision record was written:

**`docs/architecture/ADR-008-frontend-design-system-mui.md`** - MUI is the design system of record;
the Bootstrap + SweetAlert2 rule is withdrawn.

This ADR was owed. `TMS_RUNTIME_DIAGNOSIS.md` flagged the contradiction on 2026-08-25 and left the
choice open. The evidence is unambiguous: `package.json` declares neither `bootstrap` nor
`sweetalert2`; 91 files import `@mui/`; the single occurrence of "bootstrap" in `src/` is the Google
Maps *bootstrap loader*; the single occurrence of "SweetAlert2" is a comment in `src/lib/ui.tsx`
recording that `confirmDialog` replaced it. Reverting would mean rewriting 91 files to arrive at a
UI that behaves identically, and the stated goal of the original rule - reusable enterprise
components, dense responsive screens - is already met. The rule named a means; the means changed and
the end did not.

## Database migrations

**None created.** JOB 01 is a baseline job. Flyway history verified contiguous V1-V35; V36 is the
next available number and is reserved for the first job that needs schema.

No applied migration was read for modification, and no remote database was contacted.

## Backend changes

**None.** No production Java was modified.

## Frontend changes

**None.** No production TypeScript was modified.

## Security and tenant isolation

Reviewed, not changed. Company scope is resolved server-side from the JWT and membership; RLS via
the `tms_app` runtime role (ADR-005) sits underneath as defense in depth. `ApiSecurityTest`,
`IdentityResolutionIntegrationTest` and the company-scope suites all pass. Machine principals are
distinguishable from human ones through the integration client model.

No new entity was introduced, so no new tenancy question was opened.

## Audit / observability

Reviewed. `AuditEvent` (V22) records actor, action, entity, company and outcome, and the audit
screen reads it. Correlation IDs appear in every log line via MDC.

Gap recorded for JOB 15: **no Micrometer counters or timers** on planning, routing, tender or
integration paths. Structured logging exists; metrics do not.

## Tests executed

Backend:
PASS: `./mvnw -B test` - **1312 tests, 0 failures, 0 errors, 0 skipped**. BUILD SUCCESS in 1m40s.
FAIL: none.

Frontend:
PASS: `npm run typecheck` clean; `npm run lint` 0 errors (17 pre-existing warnings, exit 0);
`npm test` **37 tests across 4 files**, 0 failures; `npm run build` succeeds (1.11 MB bundle, only
the pre-existing chunk-size advisory).
FAIL: none.

Integration:
PASS: the 32 Testcontainers-backed classes ran for real. Docker Desktop was down at the start of the
session and was started locally, so nothing had to be declared blocked. Each class builds its own
database inside the container and applies the full V1-V35 history to it, which makes "the history
applies cleanly to an empty database" a genuine assertion.
FAIL: none.

E2E:
PASS: `npx playwright test` - **33 passed, 7 skipped**. The suite builds the bundle and serves it
with `vite preview`, so it exercises what actually ships.
FAIL: none.

## Environment blocked gates

**None.** Docker Desktop was unavailable at session start and was started locally, which converted
the one expected blocker into a passing gate. No remote environment (QAS, PRD, Supabase hosted,
Render, Amplify) was read or written.

The 7 skipped E2E tests are the authenticated smoke, which correctly skips without
`E2E_USER_EMAIL` / `E2E_USER_PASSWORD`. That is a designed skip against a real environment, not a
blocked gate, and skipped is reported as skipped rather than counted as passed.

## Issues discovered

1. **Documentation contradicted the code on the frontend stack.** `CLAUDE.md` instructed "Use
   Bootstrap as the visual base... Avoid MUI as the primary library" while the product is built on
   MUI. As the governing instruction file, this was the highest-severity drift found: it would have
   pushed every later job's frontend work in the wrong direction.
2. **Order lifecycle is four states** (`NOT_READY`, `READY_FOR_PLANNING`, `PLANNED`, `CANCELLED`)
   while rich delivery outcomes already exist beside it and do not feed it. An order that was
   partially delivered still reads `PLANNED`.
3. **No routing abstraction.** The only distance in the system is `route.reference_distance_km`, a
   static master-data column. Planning, ETA and sequencing all need a real one.
4. **Five capabilities are genuinely absent**, not stubbed: appointments/dock scheduling, ship
   units, fleet availability and shifts, carrier invoices and settlement, geofences.
5. **Exceptions are trip-scoped only.** `TripException` cannot represent an unplanned order, a
   rejected tender or a freight discrepancy.
6. **Tendering is single-carrier.** The lifecycle is sound but there is no ranking and no waterfall.
7. **No Micrometer metrics** anywhere.

## Issues fixed

1. Wrote **ADR-008** and corrected the five authoritative documents that stated the wrong frontend
   stack: `CLAUDE.md` (three places, including the "Frontend style" rule and the reference list),
   `README.md`, `docs/architecture/TMS_ARCHITECTURE_V1.md`,
   `docs/product/ARCHITECTURE_OVERVIEW.md`, `docs/product/SELLABLE_CAPABILITIES.md`.
2. Published `docs/architecture/TMS_CURRENT_CAPABILITY_MAP.md`, a 25-row matrix derived from the
   tree with a named next step for each partial or missing capability.

Historical step reports under `docs/overnight*/`, `docs/hardening-v4/` and `docs/reviews/` were
**deliberately left unedited**. They are dated records of what was true when written; rewriting them
would destroy the audit trail rather than correct it. ADR-008 is where the change of record lives.

## Remaining risks

- **Low.** The baseline is fully green, so any red in a later job is attributable to that job. This
  is the most valuable property JOB 01 could establish and it holds.
- The frontend bundle is a single 1.11 MB chunk with no code splitting. Not a correctness risk;
  noted for JOB 14/15.
- Frontend unit coverage is thin - 4 test files against 67 page files. JOB 14 owns this.
- 17 lint warnings are pre-existing and non-blocking (`only-export-components`, one
  `set-state-in-effect` in `AppLayout`).

## Main files changed

    docs/architecture/TMS_CURRENT_CAPABILITY_MAP.md   new - the capability matrix
    docs/architecture/ADR-008-frontend-design-system-mui.md   new - MUI as design system of record
    CLAUDE.md                                        corrected frontend stack, style rule, layout, ADR list
    README.md                                        corrected repository layout
    docs/architecture/TMS_ARCHITECTURE_V1.md         corrected repository layout
    docs/product/ARCHITECTURE_OVERVIEW.md            corrected the stack diagram
    docs/product/SELLABLE_CAPABILITIES.md            corrected capability 69
    TMS_OVERNIGHT_01_RESULT.md                       new - this file
    TMS_OVERNIGHT_MASTER_LOG.md                      new - the chain log

No production code in `backend/tms-api/src` or `frontend/tms-web/src` was touched.

## Local commit

One local commit, documentation only. No push.

## Recommended next job

**JOB 02 - Order Lifecycle V2.** It is the correct next step for a reason beyond sequence: the
delivery facts it needs (`OrderDelivery`, `DeliveryResult`, POD evidence) already exist and are
tested, so the job is about deriving order status from facts already being recorded rather than
building a new subsystem. It also unblocks JOB 03, which needs a lifecycle that can express
"partially delivered" before ship units mean anything.

The first migration of the chain will be **V36**.
