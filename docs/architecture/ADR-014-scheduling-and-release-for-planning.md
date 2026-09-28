# ADR-014 - Scheduling and release for planning

**Status:** Accepted, open questions closed by the product owner on 2026-09-27 (section 10).
Implementation: see `docs/implementation/TMS_EWM_IMPLEMENTATION_REPORT.md`.
**Date:** 2026-09-27
**Migrations:** **V54** (`order_hold`, release and hold audit actions).
**Constrained by:** ADR-003 (company scope), ADR-009 (order execution lifecycle),
`docs/domain/FREQUENCIES.md`, `docs/domain/ROUTES.md`, `docs/domain/SHIP_UNITS_AND_ALLOCATION_V1.md`
**Evidence:** cross-audit TMS <-> EWM of 2026-09-27; `OrderUpsertReleaseIntegrationTest`

## Context

An order today goes from `NOT_READY` to `READY_FOR_PLANNING` by one manual action:
`POST /orders/{id}/mark-ready`, or the `markReadyForPlanning` flag on the integration upsert. That
action checks one thing, that at least one of weight, volume or pallets is non-zero, and it is not
audited. Planning then takes every `READY_FOR_PLANNING` order of a run's origin whose `service_date`
equals the run's `planning_date`.

What the planner is **not** told before an order reaches the board:

- **Whether the date is one the destination is served on.** `LocationEligibilityEvaluator` knows,
  but only automatic planning asks it, through `ServiceCalendarPort`, which excludes an order as
  `NOT_SERVICEABLE_ON_DATE`. Manual assignment never asks.
- **Whether the release came too late for that dispatch.** `frequency_weekly_rule.cutoff_time` and
  `lead_time_days` are stored and returned by the evaluator, and **nothing applies them**. This was
  confirmed by search.
- **Which route will carry it.** The route is derived inside planning only (`Corridors.of`), and when
  a destination sits on several routes it is resolved to "the first route by code".
- **Whether anyone has put the order on hold.** Holds do not exist.

The suite asked for a *Scheduling and Release* step. This ADR puts it inside the model TMS already
has, without a second order lifecycle, a new persisted state or a new planning entity.

## Decision

### 1. `READY_FOR_PLANNING` is "released", and nothing else is

- There is no `RELEASED`, `SCHEDULED`, `ELIGIBLE_FOR_RELEASE` or `VALIDATED` status.
  `OrderStatus` is unchanged: `NOT_READY → READY_FOR_PLANNING → …` as ADR-009 left it.
- **Releasing an order *is* the existing transition `NOT_READY → READY_FOR_PLANNING`.**
- Every other answer this ADR introduces is **derived on read** and never stored on the order:
  eligibility, the release deadline and the resolved route.

### 2. `service_date` is the scheduled dispatch date

- `transport_order.service_date` keeps its column name and its current behaviour, and is formalised
  as **`scheduledDispatchDate = service_date`**.
- This is what the code already does: a run takes orders whose `service_date` equals its
  `planning_date`, and automatic planning evaluates the calendar on that date.
- **`lead_time_days` is not used to compute a delivery date.**
- A requested delivery date different from the dispatch date is **not** added now. It becomes a
  nullable `requested_delivery_date` only when an ERP contract actually sends one.

### 3. Lead time and cutoff: when the release window closes

Both come from the frequency rule that serves the order's dispatch date.

| Term | Meaning |
|---|---|
| `lead_time_days` | The minimum notice needed to release or plan the order for that dispatch |
| `cutoff_time` | The latest time of day at which the order may be released for that dispatch |

```
releaseDeadline = (scheduledDispatchDate − leadTimeDays calendar days) at cutoffTime,
                  in the company's time zone (company.time_zone)
```

Example: `service_date = 2026-09-30`, `leadTimeDays = 1`, `cutoff = 16:00` →
`releaseDeadline = 2026-09-29 16:00 America/Lima`.

- The rule is taken for the weekday of `service_date`. A `frequency_exception` for that date wins,
  including `cutoff_time_override` (V24), which is what `FrequencyCalendar.effectiveCutoff` already
  does.
- **Eligibility compares `now` with `releaseDeadline`.** It never looks at the order's `created_at`
  or `updated_at`.
- **A missing cutoff means end of day.** If the applicable rule has no `cutoff_time`, the deadline
  is the end of that calendar day (`00:00` of the next day, exclusive).
- **A missing lead time means zero.**
- **When two frequencies apply** (section 5), each yields a deadline and **the earlier one governs**.
  This follows directly from "both must permit".
- **A relevant ERP change sends the order back to `NOT_READY`** (`TransportOrder.applyChanges`, as
  today), so the next release is judged against the deadline again.
- **A redelivery with no change does not.** `OrderUpsertReleaseIntegrationTest` pins that an
  unchanged upsert answers `UNCHANGED` and keeps both the release and the version.

### 4. Eligibility: `ELIGIBLE`, `WARNING`, `BLOCKED`, derived

A read model, `OrderSchedulingService` in the orders module, produces for each order:

```
{ eligibility: ELIGIBLE | WARNING | BLOCKED,
  reasons: [ { code, severity, detail } ],
  scheduledDispatchDate, releaseDeadline, routeResolution, frequencyCodes }
```

**These are not order states.** They are recomputed every time they are read, and nothing persists
them.

| Severity | Code | Condition |
|---|---|---|
| BLOCKED | `MISSING_ORIGIN` | The origin is not an active location holding the `ORIGIN` role. The column is `NOT NULL`, so this means inactive or role-less |
| BLOCKED | `MISSING_DESTINATION` | The same, for the `DESTINATION` role |
| BLOCKED | `MISSING_CAPACITY` | Weight, volume and pallets are all zero. This is today's `mark-ready` check, unchanged |
| BLOCKED | `ROUTE_NOT_FOUND` | Route resolution returns `NOT_FOUND` (section 6) |
| BLOCKED | `ROUTE_AMBIGUOUS` | Route resolution returns `AMBIGUOUS` (section 6) |
| BLOCKED | `ACTIVE_BLOCKING_HOLD` | At least one active hold with `blocking = true` (section 7) |
| WARNING | `CUTOFF_MISSED` | `now > releaseDeadline` |
| WARNING | `FREQUENCY_OVERRIDE` | An applicable frequency does not serve `service_date`. Releasing anyway overrides the calendar |
| WARNING (informative) | `ROUTE_NOT_CONFIGURED` | The company has **no active route at all for the order's origin** (section 6). Shown, never blocking, and it **does not require an override reason** |

A reason carries `requiresOverride`. `CUTOFF_MISSED` and `FREQUENCY_OVERRIDE` require one;
`ROUTE_NOT_CONFIGURED` does not. The eligibility is still `WARNING` so a planner sees it, but a
release of an order whose only warning is `ROUTE_NOT_CONFIGURED` needs no reason, which is what
keeps a company that has never modelled routes working exactly as before.

`MISSING_CAPACITY` keeps the existing `mark-ready` rule exactly: blocked only when weight, volume
and pallets are **all** unknown.

The result is `BLOCKED` if any reason is blocking, `WARNING` if any reason is a warning, and
`ELIGIBLE` otherwise.

**Consequence of `FREQUENCY_OVERRIDE` that the user must see (approved).** An order released over
the calendar is `READY_FOR_PLANNING` by a human decision and may be assigned **by hand**, but
**automatic planning never selects it for that date**: it keeps excluding non-serviceable
destinations (`NOT_SERVICEABLE_ON_DATE`) and now also routes whose frequency does not run that day.
The release confirmation says so. No lifecycle is added for it.

**`CUTOFF_MISSED` controls release, not serviceability (approved).** Once a person has released an
order late, with a reason, automatic planning may use it.

### 5. Frequencies compose, and neither one outranks the other

The two associations mean different things, and **both must permit `service_date`**:

- `location_frequency` (V15) is the **destination's** service availability;
- `route.frequency_id` (V8) is the **route's** operating availability. It is stored today and has no
  consumer (confirmed by search), and this ADR gives it one.

| Destination calendar | Route frequency | Result |
|---|---|---|
| present | present | Both must run on `service_date` (`FrequencyCalendar.runsOn`), and the earlier release deadline governs |
| present | absent | The destination's decides |
| absent | present | The route's decides |
| absent | absent | **Serviceable, with no deadline.** This keeps today's reading (`ServiceCalendarPort`: "a location with no calendar at all is returned as serviceable… treating silence as a refusal would make automatic planning useless on the day it is turned on"). `CUTOFF_MISSED` and `FREQUENCY_OVERRIDE` cannot arise |

The route frequency is only consulted when route resolution returns `RESOLVED`. A blocked order has
no route to consult.

**Automatic planning honours both (approved 2026-09-27).** Its destination filter keeps working
through `ServiceCalendarPort`, and an order whose resolved route has a frequency that does not run on
the date is excluded as well (`NOT_SERVICEABLE_ON_DATE`). Manual assignment asks neither: the release
was the human decision.

### 6. Route resolution: `RESOLVED`, `NOT_FOUND`, `AMBIGUOUS`, `NOT_CONFIGURED`

A new `RouteResolutionPort` in `shared.reference` resolves one order at a time, and in batch for a
board:

- **Candidates** are the company's **active** routes whose origin is the order's origin, that have at
  least one stop, and that contain the order's destination as a stop. These are the same inputs
  `Corridors.of` uses today, read through the same `RouteTemplate`.
- **`NOT_CONFIGURED`** when the company has **no active route with a stop from the order's origin
  at all** (approved 2026-09-27). The company does not use the route master for that origin, so
  nothing is blocked: `routeCode` is null and the order carries the informative
  `ROUTE_NOT_CONFIGURED`.
- **`RESOLVED`** when there is exactly one candidate. It returns the route id, code and the
  destination's position on it.
- **`NOT_FOUND`** when there are none.
- **`AMBIGUOUS`** when there are several. It returns all of them, and **never picks one silently.**
- **The route is not persisted on the order.** `transport_order` gains no `route_id`, because a
  route edited in the master data is picked up at the next read.

**`Corridors` keeps its tie-break for the planning engines.** "The first route by code" is how both
engines group orders into corridors, and `PlanningEngineComparisonTest` relies on the two engines
grouping identically. Changing that is a planning decision, not a release decision. Since an
`AMBIGUOUS` order can no longer be released, new ambiguity reaches the engines only through orders
released before this ADR. The extraction moves the candidate query behind the port and leaves the
engine's grouping rule where it is.

**Rollout consequence.** `ROUTE_NOT_FOUND` and `ROUTE_AMBIGUOUS` are blocking, but **only where the
origin has routes**. A company, or an origin, with no active route answers `NOT_CONFIGURED` and
keeps releasing as before (section 10, question 1).

### 7. Holds are separate from the status

A hold never changes `OrderStatus`. A new table **`tms.order_hold`** (V54, not started) holds 0..N
rows per order:

| Column | Notes |
|---|---|
| `hold_type` | `COMMERCIAL`, `INVENTORY`, `ADDRESS`, `CUSTOMER`, `TRANSPORT`, `INTEGRATION`, `MANUAL`, `OTHER` |
| `reason_code`, `reason` | Code and free text, up to 500 characters |
| `source` | `OPERATOR` or `INTEGRATION` |
| actor | Exactly one of a person (`created_by`) or a credential (`created_by_client`), as in V31 |
| `blocking` | Default `true` |
| `created_at` | |
| `released_at`, `released_by` / `released_by_client`, `release_reason` | Set when the hold is lifted |

- Placing and lifting a hold are audited (`ORDER_HOLD_PLACED`, `ORDER_HOLD_RELEASED`) against
  `TRANSPORT_ORDER`.
- Nothing existing could be reused. `trip_exception` is trip-scoped and person-only, and no hold or
  blocking concept exists on orders.

**Planning candidates are**
`OrderStatus = READY_FOR_PLANNING AND no active blocking hold`, applied in all three places that
choose orders:
- `OrderPlanningService.searchAssignable` (the board);
- `AutoPlanningService.snapshot`;
- `TripService.assignOrder`.

Eligibility is shown on the board, but it is not a planning filter. Releasing was the human
decision, and planning does not second-guess it.

**A hold placed on an order already `PLANNED` or `IN_EXECUTION` never unplans it silently.** It
raises the Control Tower advisory `ORDER_HOLD_ON_COMMITTED_TRIP`. While the trip has not left, it is
also a **dispatch blocker** in the shared dispatch-readiness evaluator (ADR-013 §12), which can be
overridden. Because a confirmed trip's orders cannot change (ADR-013 §9), the way out is to lift the
hold, or to cancel and replan the trip.

### 8. Release is manual, and it is audited

- **Paths.** `POST /orders/{id}/mark-ready` stays the release. It evaluates §4 first:
  - `BLOCKED` is refused with 409, and the response carries the reasons;
  - `WARNING` requires an `overrideReason` (1-500 characters) in the request **when any of its
    warnings requires an override**, and is refused without one;
  - `ELIGIBLE` releases as today.
- **Audit.** Every release is audited as `ORDER_RELEASED` (new action, V54), with metadata: the
  eligibility, the warning codes and the reason when one was given. The release is not audited today.
- **Integration.** The upsert's `markReadyForPlanning` flag keeps working for `ELIGIBLE` orders, and
  for `WARNING` orders none of whose warnings requires an override (in practice
  `ROUTE_NOT_CONFIGURED`). A machine cannot give a reason, so any other `WARNING` or `BLOCKED` order
  stays `NOT_READY`, and the upsert result names the reasons.
- **Bulk release** (`POST /orders/release`) is **in scope** (approved 2026-09-27). It answers `200`
  when every item was released and `207` when any was refused, with one result per item, and it
  calls the same single-order rule for each item. One `overrideReason` may accompany the batch and
  applies to every item that needs one.
- **The screen.** *Scheduling and Release* groups and filters by origin, derived route, dispatch
  date, frequency, customer, priority, eligibility and holds. It opens or creates the existing
  **Planning Run** for an origin and date. It is a screen over these endpoints, not a new engine.

### 9. No scheduler, no Planning Batch

- **No automatic release** ("release Lima Sur 24 h before dispatch"). TMS has one scheduled task
  (webhook delivery), and debt **D4** (no system actor for background jobs,
  `TMS_ENTERPRISE_READINESS.md`) still stands. Phase one computes eligibility, and a person releases.
- **No `PlanningBatch` entity.** The Planning Run (origin + date) is the batch. A route filter on the
  eligible-orders query, derived through `route_stop`, is enough to plan route by route.

## Consequences

- One new table (`order_hold`) and three audit actions. `OrderStatus`, `transport_order`, the
  Planning Run and the planning engines are unchanged.
- `route.frequency_id` gains its first consumer, and the release window gains its first enforcement.
- A release now leaves an audit trail with the reason it was allowed.
- Companies with incomplete route master data must complete it before releases resume. This is
  deliberate, and it needs the readiness report before rollout.

## Alternatives rejected

- **New order states for scheduling or release.** A second lifecycle beside ADR-009's, where every
  state is derivable from data TMS already holds.
- **Persisting `route_id` or eligibility on the order.** They go stale the moment the master data
  changes, and a stored answer then disagrees with the computed one.
- **"Destination calendar wins over route frequency."** The two answer different questions, and a
  route that does not run that day cannot carry the order however open the store is.
- **Picking the first route when several match.** It is silently wrong for exactly the orders a
  planner most needs to look at.

## 10. Questions closed by the product owner (2026-09-27)

1. **A company or origin with no active route at all** is not blocked. Route resolution answers
   `NOT_CONFIGURED`, `routeCode` is null, the order shows the informative `ROUTE_NOT_CONFIGURED`,
   and no override reason is required for it. Where the origin has active routes: 0 compatible is
   `ROUTE_NOT_FOUND`, 1 is `RESOLVED`, more than 1 is `ROUTE_AMBIGUOUS`, and none is ever picked
   silently. `route_id` is not persisted on `transport_order`.
2. **Automatic planning honours the route's frequency** where a route resolves, in addition to the
   destination's. With only one of the two, that one decides; with neither, the order stays
   serviceable (compatibility).
3. **`FREQUENCY_OVERRIDE` means "the destination's and/or the route's calendar does not normally
   serve the requested `service_date`"**. It is a warning; a person may release with a reason and it
   is audited; manual assignment may use it; automatic planning does not select it for that date.
4. **Cutoff.** `service_date` is the scheduled dispatch date; `releaseDeadline = (service_date −
   leadTimeDays calendar days) at cutoffTime` in the company's zone, never from `created_at` or the
   ERP's `updated_at`. `now > releaseDeadline` is the warning `CUTOFF_MISSED`; a person may release
   with a reason, and automatic planning may then use the order.
