# ADR-013 - Dispatch source, and dispatch confirmed by a warehouse system

**Status:** Accepted; decisions confirmed by the product owner on 2026-09-27 (section 13).
Implementation: see `docs/implementation/TMS_EWM_IMPLEMENTATION_REPORT.md`.
**Date:** 2026-09-27
**Migrations:** **V52** (dispatch mode, machine-attributable dispatch, override permission) and **V53**
(external dispatch document, scopes, warehouse event types). Section 12's prerequisites are closed.
**Contract:** `docs/integrations/WAREHOUSE_EXECUTION_V1.md`
**Constrained by:** ADR-001 (React -> Spring Boot -> PostgreSQL), ADR-003 (company scope), ADR-005
(tenant RLS), ADR-007 (a feed informs and never moves a lifecycle, except as decided here),
ADR-009 (order execution lifecycle)
**Evidence:** cross-audit TMS <-> EWM of 2026-09-27; `SplitOrderExecutionCharacterizationTest`

## Context

TMS dispatches a trip in exactly one way today. A person calls
`POST /api/v1/planning/trips/{id}/dispatch` (`TripExecutionService.dispatch`), and it can happen
only from `READY_FOR_DISPATCH`, after five checks:
- the vehicle is operable;
- the driver is valid;
- the accepted carrier owns the vehicle;
- the resources are available;
- the departure is not before `readyAt`.

The database insists the dispatcher is a person: `ck_trip_dispatched_actor_pair` (V25) ties
`actual_departure_at` to `dispatched_by`, a foreign key to `app_user`.

A warehouse system knows better than any person in TMS when a truck actually left. EWM by EBIM emits
an SLS (movement 601, `out_shipment`) that is a frozen record of what went through the gate:
- which load (`CRG-…`) and which units;
- which orders and lines, and how much of each;
- on which plate, with which driver and seal;
- at what time.

The suite wants that fact to reach TMS. The suite also requires that **TMS keeps working with no
warehouse system at all**, and that the two products never share a table, a primary key or a
foreign key.

Three facts from the code shape the decision:

1. **The trip lifecycle has no warehouse states and must not gain any.** `TripStatus` is
   `DRAFT → CONFIRMED → READY_FOR_DISPATCH → IN_TRANSIT → COMPLETED`, plus `CANCELLED`. The
   departure *is* the dispatch (V25). Loading, load-ready and similar events are the warehouse's
   facts, not stages of the trip.
2. **TMS already has the shape of a machine actor on a trip fact.** V31 lets a carrier accept a
   tender over the Integration API: `response_source` plus exactly one of `responded_by` (a person)
   or `responded_by_client` (a credential). No person is invented to satisfy an audit column.
3. **A confirmed trip is frozen except for its driver.**
   - Orders and vehicle change only in `DRAFT` (`TripService.lockedDraftTrip`).
   - The driver changes in `DRAFT`, `CONFIRMED` and `READY_FOR_DISPATCH`
     (`TripService.DRIVER_ASSIGNABLE_STATES`).

## Decision

### 1. Dispatch mode per company

`tms.company_settings` gains `dispatch_confirmation_mode`:

| Mode | Manual dispatch (`/dispatch`) | Dispatch confirmed by an external system |
|---|---|---|
| **`MANUAL`** (default) | As today | **Recorded and reconciled. Never moves the trip** |
| **`EXTERNAL_REQUIRED`** | **Refused** (409 `dispatch-requires-external-confirmation`), unless overridden (section 4) | **Applies the dispatch** (section 3) |
| **`HYBRID`** | Allowed | Allowed. **Whichever arrives first dispatches; the second only reconciles** |

- `MANUAL` is the default, so every existing company behaves exactly as today.
- Deployments of EBIM EWM + TMS are **recommended to start in `HYBRID`**. Operations are never
  stopped by the warehouse's availability, and every SLS is still reconciled.
- The mode is a company setting and not a warehouse setting: TMS has no warehouse scope, and a
  company that runs two warehouses with different systems is not a case today.
- It is read through the existing `CompanySettingsPort`.

### 2. Dispatch source, and where verification lives

`tms.trip` gains:

- `dispatch_source` ∈ `OPERATOR` | `INTEGRATION` | `OPERATOR_OVERRIDE`. It is null until the trip
  departs, and existing departed trips are backfilled with `OPERATOR`.
- `dispatched_by_client`: the integration credential that dispatched, as a composite foreign key to
  `integration_client (id, company_id)`.

`ck_trip_dispatched_actor_pair` is replaced by an **exclusive-or**, exactly as V31 did for tenders.
A departed trip has **either** `dispatched_by` (a person, for `OPERATOR` and `OPERATOR_OVERRIDE`)
**or** `dispatched_by_client` (a credential, for `INTEGRATION`), never both and never neither.

**No value names a product.** There is no `EWM`. The system behind an integration dispatch is
recorded on the external dispatch document (`source_system`) and on its credential, so a third-party
WMS uses the same value, `INTEGRATION`.

**Verification does not live on the trip.** A trip may in future have several dispatch documents
(section 7), so `verification_status` (`UNVERIFIED` | `MATCHED` | `MISMATCH` | `OVERRIDDEN`) belongs
to each **external dispatch** record (V53). The trip's verification is derived on read:
- `UNVERIFIED` when it has no document;
- `MISMATCH` when any current document is `MISMATCH`;
- `MATCHED` otherwise.

### 3. The external dispatch, and what "applies" means

A warehouse system sends `DISPATCH_CONFIRMED` (contract: `WAREHOUSE_EXECUTION_V1.md` §4). TMS
stores it **whole**, as a new `tms.external_dispatch` row holding:
- the raw body;
- its hash;
- `source_system`, `dispatch_reference` (the SLS number) and `revision`;
- a per-order table for reconciliation.

It stores it whatever it then decides to do with it. What happens next depends on the mode and the
trip:

| Trip state | `MANUAL` | `EXTERNAL_REQUIRED` / `HYBRID` |
|---|---|---|
| unknown `transportReference` | `RECORDED_UNMATCHED` | `RECORDED_UNMATCHED` |
| `DRAFT` | recorded, `TRIP_NOT_COMMITTED` | recorded, `TRIP_NOT_COMMITTED`, not applied |
| `CANCELLED` | recorded, `TRIP_CANCELLED` | recorded, `TRIP_CANCELLED`, not applied |
| `CONFIRMED` | `RECONCILED` | **`APPLIED`**: ready and dispatch in one transaction |
| `READY_FOR_DISPATCH` | `RECONCILED` | **`APPLIED`**: dispatch |
| `IN_TRANSIT` / `COMPLETED` | `RECONCILED` | `RECONCILED`: **never a second dispatch** |

When a dispatch is applied:

- **It is performed by the existing service, not beside it.** `TripExecutionService` gains an
  integration-actor path. From `CONFIRMED` it runs the existing ready transition and then the
  dispatch transition in **one** transaction, and publishes both of the existing events
  (`SHIPMENT_READY`, `SHIPMENT_DISPATCHED`). The transition table is unchanged, because
  `CONFIRMED → IN_TRANSIT` stays illegal and two legal steps are taken instead.
- **`occurredAt` is the SLS `actualDispatchAt`.** Back-dating is already permitted; the
  five-minute future tolerance still applies.
- **The five dispatch checks become discrepancies, not refusals.** The truck has already left, and
  refusing the fact would only make TMS wrong about the world. Each failed check is recorded on the
  document.
- **A database constraint is never relaxed to make a fact fit.** If applying the dispatch would
  violate one, the document becomes **`UNAPPLIED`**, a Control Tower advisory asks a person to
  resolve it, and the trip does not move. The known case is
  `ck_trip_departed_carrier_matches_vehicle` (V42), where the accepted carrier has not supplied the
  vehicle.
- **The orders move as today:** `OrderExecutionPropagator.dispatched`, with the integration actor
  and subject to the split-order rules of section 12.
- The trip gets `dispatch_source = INTEGRATION` and `dispatched_by_client` set.

The second arrival in `HYBRID`, a late SLS, and any SLS in `MANUAL` are all detected the same way:
the trip has already departed. `TripExecutionService` already returns early when the trip is
`IN_TRANSIT`, and that is the extension point. `actual_departure_at` is **kept**, and the SLS time
is kept on the document.

### 4. Override

In `EXTERNAL_REQUIRED` a person may still dispatch, because a warehouse system can be down. The
override:

- is the same `/dispatch` endpoint with a mandatory `overrideReason` (1-500 characters);
- requires the new permission **`planning.trip:dispatch-override`**. By default it is granted to
  `COMPANY_ADMIN` and **not** to `PLANNER` (or `VIEWER`). Roles receive permissions explicitly
  (`role_permission`; V25 granted `planning.trip:execute` to each role by name), so
  `ORGANIZATION_ADMIN` gets it only if listed in the V52 grant. **Decided: yes.** A platform super
  administrator reaches it through the existing global semantics, if any apply. The service checks
  the permission, never a role name;
- is **never** grantable to an integration credential, whose authorities are scopes and carry no
  permission (`IntegrationAuthenticationToken`);
- records `dispatch_source = OPERATOR_OVERRIDE`, an `AuditAction.DISPATCH_OVERRIDDEN` with the
  reason, and a `transport_event` with the reason in its notes.

An SLS that arrives after an override reconciles against it like any second arrival. Its document is
marked `OVERRIDDEN` when it matches and `MISMATCH` when it does not.

In `MANUAL` and `HYBRID` an `overrideReason` is not needed and is ignored.

### 5. Reconciliation: planned versus dispatched, without overwriting the plan

The plan is never rewritten by what left:
- no assignment is created or removed;
- no quantity on an assignment changes;
- the vehicle and driver on the trip stay what planning set.

The document is compared with the plan, and each difference is stored on the document and surfaced
as a Control Tower advisory:

| Code | Rule |
|---|---|
| `UNKNOWN_TRANSPORT_REFERENCE` | No trip in the credential's company has this `shipment_number` |
| `TRIP_CANCELLED` / `TRIP_NOT_COMMITTED` | See section 3 |
| `MISSING_ORDER` | An order with an `ACTIVE` assignment on the trip is absent from the SLS |
| `EXTRA_ORDER` | An order in the SLS has no active assignment on the trip, or is unknown |
| `QUANTITY_VARIANCE` | Per line when `lineNumber` and unit agree; per order otherwise. `QUANTITY_UNCOMPARABLE` when neither can be compared |
| `CARRIER_MISMATCH` | The SLS carrier code matches neither the trip carrier's `code` nor its `external_reference` |
| `VEHICLE_MISMATCH` | The normalised plate differs (upper case, no spaces or hyphens) |
| `DRIVER_MISMATCH` | Warning only, because the WMS holds the driver as free text |
| `WAREHOUSE_MISMATCH` | `warehouseCode` differs from the trip origin's `external_reference` |
| `DISPATCH_TIME` | The SLS time and the recorded departure differ by more than **15 minutes**. This is a fixed tolerance, deliberately not a setting yet |
| `UNKNOWN_LOAD` | The `loadReference` differs from the one an earlier document for the same trip carried |

Planned quantities come from what TMS already stores: `trip_order_assignment.assigned_*` and
`transport_order_line`. Dispatched quantities come from the document. **Variance is derived and never
stored.** SKU, lot, serial and handling unit stay inside the stored document. TMS does not normalise
them until a requirement needs to query them.

### 6. Idempotency, revisions and order of arrival

- **Transport idempotency** is the existing inbox: `Idempotency-Key` per company, client and
  operation. The same key with another body returns 409.
- **Business idempotency** is `(company_id, source_system, dispatch_reference, revision)`:
  - the same revision and hash returns `UNCHANGED`;
  - a lower revision than the current one returns `STALE`;
  - a higher revision **supersedes** the current document and reconciles again, but **never
    dispatches again**.
- **Discrepancies are business outcomes and are answered 200.** Only a malformed request or an auth
  failure is 4xx. This is so a warehouse system never dead-letters a true physical fact.
- **Warehouse milestones** (`LOADING_STARTED`, `LOAD_READY`, `LOAD_CANCELLED`) are appended to
  `tms.transport_event` with `source = INTEGRATION`. They never move a lifecycle, which is ADR-007's
  rule applied to the warehouse. Out of order is harmless, because each event carries its own
  business time.
- **There is no `DISPATCH_CANCELLED`.** EWM cannot reverse a 601. A correction is a revision, and
  goods coming back are a return (RMA, movement 653), not an undone dispatch.

### 7. `transportReference`, and cardinality in contract V1

- **`transportReference` is `trip.shipment_number`**, which already exists: unique across the
  installation, stable, never reissued (V19).
- **EWM stores it in `out_shipment_load.external_load_number`**, which already exists for exactly
  this purpose and is frozen into the SLS.
- **No migration on either side.**
- **It is an opaque, company-scoped reference.** The company chooses its prefix (V34), so no route
  or validator may constrain its shape. Phase 0 C removed the `SH-\d+` route patterns without
  replacing them with any other format check.

**Contract V1 cardinality is 1 TMS trip = 1 active EWM load = 1 SLS.** The reason is EWM's unique
index `ux_outld_externo (tenant_id, external_load_number) WHERE status <> 'CANCELLED'`. TMS keys
`external_dispatch` by the SLS, not by the trip, so its own model already holds N documents per trip.
**This ADR does not claim that EWM supports N loads per trip.** Supporting that later needs one of
two things: a `transportLoadReference` in the contract, or EWM relaxing that index. Either is a
contract V2 decision.

### 8. Plan confirmed is not SLS confirmed

Both products have an event called `SHIPMENT_CONFIRMED`, and they mean opposite ends of the process:

| Product | `SHIPMENT_CONFIRMED` means |
|---|---|
| TMS | "the transport plan is committed" |
| EWM | "the SLS was issued: it left" |

**Neither internal name crosses the boundary.** The contract speaks `TRANSPORT_PLAN_CONFIRMED` for
the first and `DISPATCH_CONFIRMED` for the second. Any connector translates, and forwarding a native
event name to the other product is a contract violation.

### 9. Changes after a trip is confirmed

This ADR changes no editing rule. The rules as they stand are the contract:

- **Orders and vehicle** of a confirmed trip cannot change. Changing them means cancelling the trip,
  which publishes `SHIPMENT_CANCELLED` / `TRANSPORT_PLAN_CANCELLED`, and replanning, which issues a
  **new** `shipment_number`. The WMS cancels the old load if it has not started loading. From
  `LOADING` onwards that is its operational exception, never an automatic cancellation.
- **The driver** may still change in `CONFIRMED` and `READY_FOR_DISPATCH`. That change will produce
  `SHIPMENT_CHANGED` / `TRANSPORT_PLAN_UPDATED`. It is reserved since V20 and has no producer yet.

### 10. Cancellation across the boundary

| Moment | TMS | WMS (EWM) |
|---|---|---|
| Before planning | Order cancel, as today | Its own ORR cancellation |
| Planned, trip still `DRAFT` | Remove from trip, then cancel. A partially allocated order is refused until unassigned (Phase 0 B) | Nothing has been sent |
| Trip confirmed (plan sent) | Cancel the trip, which publishes `TRANSPORT_PLAN_CANCELLED` | Cancels a `DRAFT`/`PLANNED` load |
| Picking or loading started | TMS does not know unless milestones arrive. Cancelling the trip is still allowed and still published | **No automatic cancellation.** It is the WMS's operational exception |
| Dispatched | Not a cancellation. It becomes rejection, return, redelivery or reverse logistics, which is ADR-009's reopen and the WMS's RMA | Not a cancellation |

TMS has no inbound order-cancel operation today. Adding one is a separate change and is not in
this ADR.

### 11. TMS stays independent

- **With no WMS, nothing changes.** `MANUAL` is the default, and no code path waits for a warehouse
  system.
- **In `EXTERNAL_REQUIRED` a WMS outage costs one reasoned override per truck**, never a stopped
  operation.
- **TMS never reads or writes a WMS table.** A WMS never learns a TMS primary key. The only shared
  values are business references: `shipment_number`, the ERP order key and the warehouse code.

### 12. Prerequisites before V52

1. **The split-order execution rules (`docs/domain/SPLIT_ORDER_EXECUTION.md`, R1-R3) are approved
   and implemented.** An SLS will routinely confirm trips that carry part of an order, and today
   such a dispatch is refused (scenario 1) and closes out incorrectly (scenario 2). Applying external
   dispatches on top of that would turn a latent defect into a daily one.
2. **The shared dispatch-readiness evaluator is extracted.** Today `ControlTowerService.blockers`
   re-implements two of `dispatch()`'s five checks. The external path must evaluate the *same* list,
   to record discrepancies instead of refusing.

### 13. Decisions confirmed on 2026-09-27

1. **Prerequisites closed.** R1-R3 of `SPLIT_ORDER_EXECUTION.md` are implemented, and
   `DispatchReadiness` is the single evaluator used by `dispatch()`, the Control Tower and the
   external path.
2. **`transportReference` = `trip.shipment_number`**, opaque and company-scoped; EWM stores it in
   `out_shipment_load.external_load_number`. No migration for it on either side.
3. **Cardinality V1 is 1 trip = 1 active EWM load = 1 SLS**, while `external_dispatch` allows N
   documents per trip. EWM's unique index is not relaxed and 1:N is not announced.
4. **Two different "source systems".** The top-level `sourceSystem` of a `DISPATCH_CONFIRMED` is the
   *producer* of the dispatch (EWM by EBIM publishes `EWM_EBIM`); `orders[].externalSource` is the
   *ERP namespace* of each order (`SAPB1_PE`, `SAPS4_PE`, `JDE_ECUACORRIENTE`, …) and must equal
   `transport_order.external_source` and EWM's `out_order.source_system` **byte for byte**. TMS never
   normalises one into the other.
5. **Time tolerance** is a fixed 15 minutes, not a setting. `DISPATCH_TIME` is informative and never
   rejects the document.
6. **`UNKNOWN_LOAD`** joins the discrepancy codes: the document names a `loadReference` different from
   the one an earlier document for the same trip used.
7. **External dispatch and blockers.** A valid external document is always recorded; TMS tries to
   apply it; operational blockers from `DispatchReadiness` become discrepancies; only a database
   invariant that genuinely cannot hold (e.g. V42's carrier-vehicle constraint) leaves it `UNAPPLIED`
   with an advisory. Data is never falsified to fit.
8. **Manual dispatch in `EXTERNAL_REQUIRED`** without override is `409` with problem code
   `dispatch-requires-external-confirmation`; with the permission and a non-blank `overrideReason` it
   dispatches as `OPERATOR_OVERRIDE`.
9. **HYBRID concurrency** takes the trip's pessimistic row lock on both paths, so exactly one
   dispatch happens; the second arrival reconciles.
10. **Warehouse milestones** arrive with public types `LOADING_STARTED`, `LOAD_READY`, `LOAD_CANCELLED`
    and are stored as `transport_event` types `WAREHOUSE_LOADING_STARTED`, `WAREHOUSE_LOAD_READY`,
    `WAREHOUSE_LOAD_CANCELLED`; an applied or reconciled dispatch document also appends
    `WAREHOUSE_DISPATCH_CONFIRMED`. All with `source = INTEGRATION`; none moves a lifecycle.
11. **Control Tower advisories** added: `DISPATCH_MISMATCH`, `AWAITING_WAREHOUSE_DISPATCH`,
    `EXTERNAL_DISPATCH_UNMATCHED`, `ORDER_HOLD_ON_COMMITTED_TRIP`. Machine facts do not use
    `trip_exception`.

## Consequences

- One new setting and two new trip columns. The external document and its per-order table are the
  only new tables.
- `TripStatus`, `OrderStatus`, the outbox, the webhook tables and the inbox are unchanged.
- A person can see, per trip, how it departed (`dispatch_source`) and whether the warehouse agreed
  (derived verification). Any reviewer can read what the warehouse actually said (stored document).
- A company can adopt a WMS gradually, `MANUAL → HYBRID → EXTERNAL_REQUIRED`, and fall back without
  a migration.
- Cost: the integration path now dispatches, so the "only a person dispatches" invariant becomes
  "a person or a named credential dispatches, and the database says which".

## Alternatives rejected

- **Warehouse states in `TripStatus`** (`LOADING`, `READY_FOR_WAREHOUSE`, `DISPATCHED`, `CLOSED`…).
  They would make the trip's lifecycle wait on another product's events, and a missing event would
  strand a trip.
- **`dispatch_source = EWM`.** It names a vendor in a core column, and would need a migration for
  every WMS a customer brings.
- **Verification on the trip.** It is wrong the day a trip has two documents.
- **Refusing an external dispatch when a check fails.** The truck is gone, and refusing the fact only
  makes TMS wrong.
- **Storing the SLS in `integration_request.payload_snapshot`.** It is off by default, truncated at
  64 KB, and stores the re-serialised Java record, not the bytes received.
- **Using `trip_exception` for discrepancies.** It is person-only (`reported_by NOT NULL`) and has no
  order link. Control Tower advisories already carry this kind of derived signal
  (`SETTLEMENT_DISCREPANCY_OPEN`).
