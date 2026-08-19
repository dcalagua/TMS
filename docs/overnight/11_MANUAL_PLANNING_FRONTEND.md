# Step 11 - Manual planning frontend

Date: 2026-08-19
Attempt: 1
Result: **PASS**

## 0. State inherited from prior steps

The repository arrived with a clean working tree and Step 10 (`10_MANUAL_PLANNING_BACKEND`,
migration V11) complete and untouched: `router.tsx` still pointed `planning` and `trips` at
`PlaceholderPage`, and no planning API client, page or component existed yet. `docs/overnight/
10_MANUAL_PLANNING_BACKEND.md` section 8 ("Handoff to Step 11") was read end to end before any
code was written, and every one of its eight points shaped a specific decision below:

1. **"The board is one call."** `PlanningBoardPage` calls `GET /planning/runs/{id}` exactly once
   per load/refresh and never loops over trips. A trip's own detail (assignments + stops) is
   fetched only when its card is opened, by `TripDetailDrawer`.
2. **"Never compute capacity in the browser."** `CapacityBar` renders `used`/`limit`/`percentUsed`/
   `exceeded`/`unlimited` exactly as received; it contains no arithmetic beyond clamping a bar's
   *visual* width to 100 for a `percentUsed > 100` edge case, which does not change the text or the
   verdict shown.
3. **Versions.** `PlanningActionRequest`/`TripCreateRequest`/`TripVehicleRequest` all carry the
   version the backend documented (the *run's* version for confirm/cancel/trip-create, the *trip's*
   for vehicle-change/trip-cancel); assignment/move/remove calls carry none, matching
   `docs/domain/PLANNING_MANUAL_V1.md` section 4 exactly.
4. **"Drag-and-drop maps cleanly... a client never sends a position number... every response is the
   updated trip detail."** V1 uses select-plus-button controls instead of drag-and-drop (see
   section 2 below for why), and every mutation in `TripDetailDrawer`/`EligibleOrdersPanel` repaints
   from the endpoint's own response - `queryClient.setQueryData` for the drawer's own trip, a full
   board invalidation for anything that could have changed a sibling trip's counts or capacity.
5. **"Refusals are the interesting states... written to be shown to a planner verbatim."** This is
   the one place this step's implementation extends the existing frontend contract rather than just
   following it - see section 3.
6. **`GET /planning/eligible-orders` filters and pagination.** `EligibleOrdersPanel` fixes
   `originId`/`serviceDate` to the open run (eligibility requires exact equality on both per
   `PLANNING_MANUAL_V1.md` section 5, so exposing them as editable filters would only produce rows
   an assign call then refuses) and exposes `destinationId`/`orderNumber` as the adjustable filters,
   paginated at 10 rows.
7. Noted, not applicable to this step (a `time`-column seeding caveat for future fixtures).
8. **"`router.tsx` points `planning`/`trips` at `PlaceholderPage`."** `planning` and
   `planning/:runId` are now real routes; `trips` is deliberately left as a placeholder - see
   section 5.

## 1. Scope

This step is the **frontend** of manual planning, built strictly against the Step 10 backend
contracts (`PlanningRunController`, `TripController`, their DTOs, `docs/domain/
PLANNING_MANUAL_V1.md`, `docs/domain/CAPACITY_MODEL.md`) - nothing here mocks a capability the
backend does not have. No backend or database file was touched.

Deliberately **not** built, matching the brief and `CLAUDE.md`'s deferred list: no drag-and-drop
(see section 2), no solver/optimizer UI, no GPS/live tracking, no execution/dispatch screens, no
direct Supabase business-data calls (every planning read/write goes through Spring Boot).

## 2. UX decisions made before writing code

- **Two routes, not one.** `/planning` (`PlanningRunsPage`) lists/filters/creates runs;
  `/planning/:runId` (`PlanningBoardPage`) is the board itself. The backend has no "list all runs
  with their boards" endpoint and `GET /planning/runs/{id}` is the board's one call, so a single
  page trying to do both would either fetch every run's board eagerly (violating point 1 above) or
  invent a client-side notion of "the currently open run" with no URL to point at - a planner could
  not bookmark or share a specific plan.
- **Select-plus-button instead of drag-and-drop**, for assign, move and stop reordering. The step
  brief explicitly allows this ("Drag-and-drop is optional. Do not add it if it risks correctness or
  accessibility."). A `<select>` plus a button is keyboard-operable and screen-reader-announcable
  with zero extra work, gives the exact same server round-trip drag-and-drop would, and does not
  require inventing optimistic client-side reordering state that could drift from what the backend
  actually persisted (`docs/domain/PLANNING_MANUAL_V1.md` section 7: stops are backend-maintained,
  not client-authored).
- **The eligible-orders origin/date filters are fixed, not editable**, per point 6 above. This is a
  deliberate narrowing of "eligible-order filters" from the brief: the two filters that *are*
  editable (destination, order number) are exactly the two eligibility does not pin to the run.
- **`EligibleOrdersPanel`'s assign control and `TripDetailDrawer`'s move control are both
  select-plus-button, sharing the same shape** (`assignTargets`/`moveTargets` local maps keyed by
  order id) so a planner learns the interaction once. Both default the select to the first eligible
  target trip so a one-trip run needs only one click.
- **The stop editor re-seeds its local order only when the *set* of served destinations changes**,
  not on every refetch (`TripDetailDrawer`, the `serverStopsKey` effect). A refetch triggered by an
  unrelated action (another planner assigning an order to a different trip) must not silently
  discard a manual reorder in progress; a refetch that changed which destinations are served (an
  assignment on *this* trip was added/removed) must.
- **A trip's move/remove/vehicle/cancel controls disappear once its own status is not `DRAFT`**,
  independent of the run's status - a `CONFIRMED` run's trips are all `CONFIRMED` too, so this one
  check is sufficient and mirrors the backend's own per-trip guard rather than trusting the run's
  status alone.

## 3. Extending, not just following, the error-message contract

`docs/api/API_CONVENTIONS.md` section 4 says a client must "branch on `code`, never on `detail`,"
and the existing `problemMessages.ts` documents `detail` as "prose meant for logs" - every other
screen (`OrdersPage`, the masters CRUD pages) shows only the generic per-`code` copy.

Planning's own domain documents say something more specific for this one module:
`docs/domain/CAPACITY_MODEL.md`, "The frontend is never trusted" and `docs/overnight/
10_MANUAL_PLANNING_BACKEND.md` section 8 point 5 both state that a capacity/eligibility refusal's
`detail` "names every dimension that failed" / "is written to be shown to a planner verbatim
(SweetAlert2), not swallowed." Checking the actual exception mapping confirmed why this matters:
`PlanningCapacityService`, `TripAssignmentService`, `PlanningRunService` and `TripService` all throw
either `ConflictException` (409, `code: "conflict"`) or `InvalidRequestException` (400,
`code: "malformed-request"`) for every planning-specific refusal - capacity, stale version, wrong
trip status, wrong origin/date, already-assigned. Because `code` is the same for all of these very
different situations, the generic `MESSAGES['conflict']` copy ("This change conflicts with another
update. Reload and try again.") would be actively wrong for "Trip 2 would exceed capacity: weight
1200.00/1000.00 kg" - it hides the one thing the planner needs to act on.

`describePlanningError` was added to `shared/api/problemMessages.ts` (not a new file, to keep one
place owning error copy) to prefer `error.problem.detail` for exactly `conflict`/`malformed-request`
on planning calls, falling back to `describeApiError` for everything else (401/403/500, where
`detail` is still not meant for a screen). This does not weaken the "never branch on `detail`"
rule - no code here parses or pattern-matches `detail`, it only decides *whether to display* it
after already branching on `code` - and it does not touch `describeApiError` itself, so no other
screen's copy can drift because of this change.

## 4. Vertical slice

    PlanningRunsPage / PlanningBoardPage
      -> EligibleOrdersPanel, TripCard, TripDetailDrawer, TripVehicleModal,
         PlanningRunFormModal, CreateTripModal
      -> shared/api/planningApi.ts (typed client, mirrors every planning DTO 1:1)
      -> PlanningRunController / TripController  (Step 10, unmodified)
      -> PlanningRunService / TripService / TripAssignmentService / PlanningCapacityService
      -> tms.planning_run / tms.trip / tms.trip_stop / tms.trip_order_assignment
      -> @PreAuthorize + CompanyScope (Step 10, unmodified)
      -> the tests in section 6

Screens and components added:

| File | Responsibility |
|---|---|
| `shared/api/planningApi.ts` | Every planning DTO/enum and one function per endpoint, 1:1 with the backend records read directly from `planning/application/*.java` |
| `shared/ui/components/CapacityBar.tsx` | The reusable capacity bar: unlimited / real-zero-limit / normal-limit rendered as three distinct states, never inventing a percentage |
| `pages/planning/PlanningRunsPage.tsx` | List/filter/paginate runs; create; open a run's board |
| `pages/planning/PlanningRunFormModal.tsx` | Create a run (origin + planning date + notes) |
| `pages/planning/PlanningBoardPage.tsx` | Header (plan #, origin, date, status, confirm/cancel/new-trip actions) + hosts the two panes |
| `pages/planning/EligibleOrdersPanel.tsx` | Paginated/filtered eligible orders, assign-to-trip control |
| `pages/planning/CreateTripModal.tsx` | Create a trip inside the open run (vehicle and departure optional) |
| `pages/planning/TripCard.tsx` | One trip's board-row card: vehicle/carrier, counts, three capacity bars |
| `pages/planning/TripDetailDrawer.tsx` | Trip detail: assignments (remove, move-to), stop sequence editor, vehicle button, cancel-trip |
| `pages/planning/TripVehicleModal.tsx` | Set/swap a trip's vehicle + planned departure |

Every one of these was checked against the actual Step 10 response shape by reading the DTO source
(`planning/application/*View.java`, `*Request.java`) before `planningApi.ts` was written, not
inferred from the domain docs alone.

## 5. Permission gating

Read from `docs/domain/PLANNING_MANUAL_V1.md` section 9 and applied as UI-only gating (the backend
`@PreAuthorize` annotations are the actual authorization, unchanged by this step):

| Screen control | Permission checked |
|---|---|
| "New run" (`PlanningRunsPage`) | `planning.plan:manage` |
| "Confirm plan" / "Cancel plan" (`PlanningBoardPage`) | `planning.plan:manage` **and** `planning.trip:manage`, matching the two `@PreAuthorize` clauses on `confirm`/`cancel` |
| "New trip", assign, remove, move, vehicle, stops, trip-cancel | `planning.trip:manage` |

`PlanningRunsPage`/`PlanningBoardPage` do not additionally gate on `PLANNING_VIEW`/`TRIPS_VIEW`
capabilities themselves - consistent with every existing masters/orders page, which relies on
`Sidebar`/`navConfig` to hide the menu entry and on the backend to answer 403 for a caller who
navigates to the URL directly. Hiding a button is UX, not authorization, per `CLAUDE.md` - the
backend re-checks every call regardless of what the frontend shows.

`Capability.TRIPS_VIEW`/`TRIPS_MANAGE` already existed (Step 10) but the `/trips` nav item is left
pointing at `PlaceholderPage`: `TripController` has no "list all trips" endpoint (only
`GET /trips/{id}` and `/{id}/capacity`), and a trip only has meaning inside the run that contains
it, so a standalone trips list has nothing to call without inventing a backend behavior that does
not exist - explicitly against this step's own instruction ("Do not mock nonexistent backend
behavior in production code").

## 6. Tests

Run this session with `npx vitest run` (`frontend/tms-web`):

    Test Files  36 passed (36)
         Tests  219 passed (219)

53 of those 219 are new, across 8 new test files:

| Requirement from the brief | Test(s) |
|---|---|
| eligible-order pagination/filter | `EligibleOrdersPanel.test.tsx`: pins `originId`/`serviceDate` to the run and paginates at 10; `PlanningRunsPage.test.tsx` covers the run list's own pagination/filtering the same way `OrdersPage.test.tsx` does |
| create trip | `CreateTripModal.test.tsx`: no-vehicle create sends `vehicleId: null` and the run's version; selecting a vehicle sends its id |
| assign order happy path | `EligibleOrdersPanel.test.tsx` "assigns an order to the selected trip and reports the updated trip detail" |
| overcapacity 409 display | `EligibleOrdersPanel.test.tsx` and `TripDetailDrawer.test.tsx` (move) and `TripVehicleModal.test.tsx` (downgrade) all assert the backend's `detail` is shown **verbatim**, not the generic conflict copy |
| move between trips | `TripDetailDrawer.test.tsx` "moves an order to the selected sibling trip" and the paired rejection test asserting the source order is still shown afterwards |
| conflict/stale refresh behavior | `PlanningBoardPage.test.tsx` "shows the backend refusal verbatim when confirmation fails an incomplete trip"; `CreateTripModal.test.tsx`'s stale-run-version rejection; every mutating component sends the version the backend documented (asserted per call) |
| capacity bars | `CapacityBar.test.tsx`: unlimited, real-zero-limit (with and without an exceeded load), normal within-capacity, normal exceeded - five cases matching `CAPACITY_MODEL.md`'s table exactly |
| confirm flow | `PlanningBoardPage.test.tsx` "confirms the plan only after the dialog is accepted, sending the run version" (SweetAlert2 `confirmAction` mocked, both the decline and accept paths asserted) |
| permission gating | `PlanningRunsPage.test.tsx`, `PlanningBoardPage.test.tsx`, `EligibleOrdersPanel.test.tsx`, `TripDetailDrawer.test.tsx` each assert manage controls are hidden for a caller without the relevant permission |

Also covered, beyond the brief's list: trip cancellation (confirm-dialog decline/accept, sends the
trip's version), the stop-reorder editor (Save disabled until the order actually changes, sends the
full destination list in the new order), the vehicle modal pre-selecting a trip's current vehicle
when swapping, and board navigation (open a run, create-and-navigate, back-link).

One implementation bug was found by running these tests, not by reading the code: `TripVehicleModal`
initially left react-hook-form's `defaultValues.vehicleId` pointed at the trip's current vehicle
before the async vehicle list had loaded, so the browser silently reset the `<select>` to blank once
the list arrived (an uncontrolled-input timing issue, the same class of bug `OrderFormModal`/
`RouteFormModal` solve with their `withCurrentValue` helper). Fixed the same way: `withCurrentVehicle`
prepends the trip's already-known vehicle id/code/plate synchronously, from props, before the network
response exists at all.

### Typecheck and build

    npx tsc -b                    # clean, 0 errors
    npx oxlint                    # 2 pre-existing warnings in AuthContext.tsx/CompanyContext.tsx
                                   # (react/only-export-components), neither touched by this step;
                                   # no new warnings
    npm run build                 # succeeds; dist/assets/index-*.js 881.62 kB (236.27 kB gzip),
                                   # one pre-existing "chunk larger than 500 kB" advisory unrelated
                                   # to this step (no code-splitting was requested or attempted)

### What was not run, and why

Per this step's own instruction ("Run typecheck, unit tests and production build"), those three
gates were run and are all green (above). A live dev-server-plus-browser walkthrough against a
running backend was **not** performed in this unattended session: no browser-automation tool is
available in this environment, and CLAUDE.md's environment notes flag that the Docker-dependent
backend stack is not always available. Docker was in fact running at the time of this step, so a
manual full-stack smoke test was possible in principle, but starting Spring Boot, local Supabase and
the Vite dev server and driving them without a browser tool was not attempted rather than partially
claimed. The 219-test suite exercises every user-facing interaction this step adds (clicks, form
submissions, SweetAlert2 confirm/decline branches, API success/error branches) against realistic
mocked responses shaped exactly like the real DTOs, which is the verification actually performed and
reported here - not a substitute claimed to be a browser test.

## 7. Constraint compliance

| Constraint | How |
|---|---|
| never push, never deploy | nothing was pushed, committed or staged; no deployment exists |
| never mutate a remote/shared database | no database of any kind was touched - this step is frontend-only |
| no real secrets | none read or created |
| no destructive Git operations | none run |
| Flyway is the only migration owner | no migration file was added or edited |
| business data through Spring Boot | every planning read/write in `planningApi.ts` calls the Step 10 REST API; no direct Supabase business-data call was added |
| hiding is UX, not authorization | every permission check in this step only hides a button; the backend's `@PreAuthorize` (Step 10, unmodified) remains the actual gate |
| do not mock nonexistent backend behavior | `/trips` stays a placeholder (no list-all-trips endpoint exists); no client-side capacity math; no invented endpoint or field |
| do not claim untested passes | every number in section 6 comes from a run executed this session |
| deferred-by-decision items untouched | no drag-and-drop, no solver/optimizer UI, no GPS/live tracking, no execution/dispatch screens |

## 8. Files

Added:

```
frontend/tms-web/src/shared/api/planningApi.ts
frontend/tms-web/src/shared/ui/components/CapacityBar.tsx
frontend/tms-web/src/shared/ui/components/CapacityBar.test.tsx
frontend/tms-web/src/pages/planning/PlanningRunsPage.tsx (+ .test.tsx)
frontend/tms-web/src/pages/planning/PlanningRunFormModal.tsx (+ .test.tsx)
frontend/tms-web/src/pages/planning/PlanningBoardPage.tsx (+ .test.tsx)
frontend/tms-web/src/pages/planning/EligibleOrdersPanel.tsx (+ .test.tsx)
frontend/tms-web/src/pages/planning/CreateTripModal.tsx (+ .test.tsx)
frontend/tms-web/src/pages/planning/TripCard.tsx (+ .test.tsx)
frontend/tms-web/src/pages/planning/TripDetailDrawer.tsx (+ .test.tsx)
frontend/tms-web/src/pages/planning/TripVehicleModal.tsx (+ .test.tsx)
docs/overnight/11_MANUAL_PLANNING_FRONTEND.md
```

Modified:

```
frontend/tms-web/src/app/router.tsx
  planning -> PlanningRunsPage, planning/:runId -> PlanningBoardPage (both were PlaceholderPage);
  trips left unchanged (see section 5)
frontend/tms-web/src/shared/api/problemMessages.ts
  added describePlanningError - see section 3; describeApiError itself is untouched
frontend/tms-web/src/shared/ui/components/index.ts
  exports CapacityBar alongside the existing shared components
```

## 9. Result

A planner can now open or create a manual planning run, see its eligible orders (paginated,
filtered, pinned to the run's origin and date), create trips, assign/move/remove orders through
explicit clear-action controls, watch each trip's three backend-computed capacity bars update
immediately after every mutation, reorder a trip's stops, assign or swap a vehicle, and confirm or
cancel the plan through a SweetAlert2-confirmed action that sends the version the backend requires.
Every capacity/eligibility/concurrency refusal the backend can produce is surfaced to the planner
using the backend's own message rather than a generic "conflict" string, which required extending
(not bypassing) the existing error-message contract in one documented, narrowly-scoped place. 219
frontend tests pass, 53 of them new; typecheck and the production build are both clean; one real
timing bug (an uncontrolled `<select>` losing its pre-selected value to an async fetch) was found by
running the tests and fixed using the codebase's own established pattern for it.

TMS_GATE=PASS
