# Scheduling, release and holds

Decision of record: `docs/architecture/ADR-014-scheduling-and-release-for-planning.md` (implementation
notes in its section 11). This page is the operational summary.

## Release

- Releasing an order **is** `NOT_READY -> READY_FOR_PLANNING`. There is no other "released" state.
- Before the transition, the order's eligibility is computed (never stored):

| Eligibility | Reasons | What a person can do |
|---|---|---|
| `BLOCKED` | `MISSING_ORIGIN`, `MISSING_DESTINATION`, `MISSING_CAPACITY`, `ROUTE_NOT_FOUND`, `ROUTE_AMBIGUOUS`, `ACTIVE_BLOCKING_HOLD` | Fix the data or lift the hold. No reason releases it |
| `WARNING` (override) | `CUTOFF_MISSED`, `FREQUENCY_OVERRIDE` | Release with an override reason (1-500 chars), audited |
| `WARNING` (informative) | `ROUTE_NOT_CONFIGURED` | Release as before, no reason |
| `ELIGIBLE` | none | Release |

- `service_date` is the scheduled dispatch date. The release deadline is
  `(service_date - leadTimeDays) at cutoffTime` in the company's zone; no cutoff means end of day, no
  lead time means zero, a frequency exception's cutoff override wins. With a destination calendar
  and a route frequency, both must serve the date and the earlier deadline governs.
- An integration (`markReadyForPlanning`) releases only what needs no reason; otherwise the order
  stays `NOT_READY` and the answer lists `releaseRefusedBy`.
- Bulk release applies the same rule per order and answers 207 when any was refused.

## Holds

- A hold is a row in `tms.order_hold`, never a status. Types: `COMMERCIAL`, `INVENTORY`, `ADDRESS`,
  `CUSTOMER`, `TRANSPORT`, `INTEGRATION`, `MANUAL`, `OTHER`. Blocking by default; a non-blocking hold
  is a note.
- An active blocking hold:
  - blocks release (`ACTIVE_BLOCKING_HOLD`);
  - removes the order from planning candidates (eligible-orders board, automatic planning) and makes
    manual assignment answer 409;
  - on an order already on a trip, **unplans nothing**: the trip cannot be dispatched
    (`ORDER_HOLD_ON_COMMITTED_TRIP` blocker) and the Control Tower shows the advisory of the same
    name. The way out is to lift the hold, or to cancel the trip and replan.
- Lifting a hold requires a reason. Both placing and lifting are audited against the order.
- Placing and lifting need `orders.hold:manage` (ORGANIZATION_ADMIN, COMPANY_ADMIN, PLANNER).

## Planning

- A candidate is `READY_FOR_PLANNING` with no active blocking hold.
- Automatic planning also skips, as `NOT_SERVICEABLE_ON_DATE`, an order whose destination calendar
  or resolved route frequency does not serve the run date - including one a person released over
  the calendar. Manual assignment may take it.
- `CUTOFF_MISSED` controls release only: once a person released a late order, automatic planning may
  use it.

## Screen

*Operación > Programación y Liberación* (`/scheduling`): summary by origin, route and dispatch date,
filters, single and bulk release, holds, reasons panel, and a shortcut that opens (or creates) the
Planning Run for an origin and date.
