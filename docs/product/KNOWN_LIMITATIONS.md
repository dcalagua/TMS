# TMS by EBIM — known limitations

Everything a buyer, a reviewer or a demo audience could reasonably discover, written down before
they find it.

- State of the working tree on **2026-08-21**, at migration **V35**.
- Each item is classified **BLOCKER**, **ENVIRONMENT**, **DEFECT**, **GAP** or **CEILING**, and
  says what closing it needs.
- Nothing here is a surprise to the codebase: every item was verified against source in this run.

> The rule this document exists to serve: **do not sell what does not exist, and do not disclaim
> what does.** Both errors cost the same amount of credibility.

---

## 1. ENVIRONMENT — twelve migrations have never been executed

**V24 through V35 have not been run by any PostgreSQL server on the build machine.**

Docker Desktop's Linux engine is unreachable there because its backing WSL distribution is missing,
and the native PostgreSQL on the host cannot substitute — migration V1 runs
`CREATE EXTENSION postgis` and the native install has no `postgis.control`.

The consequence, measured statically in this run:

| | Count |
|---|---|
| Backend test methods declared | 1,243 across 110 classes |
| Of those, gated on Docker and therefore **skipped** | **443 across 31 classes** |
| Runnable without a database | 800 |

The skipped set is exactly the layer that proves the non-negotiable rules: Flyway apply, RLS and
tenant isolation, schema exposure, the double-booking index, the one-accepted-tender index,
inbound idempotency and the full vertical smoke.

**What is true anyway.** Every rule those migrations carry is enforced a second time in Java —
`TripStatus` and its transition table, `OrderTotals`, `DeliveryResult`, `TenderStatus`,
`RateCardSelector`, `TrackingIngestionService` — and each is unit-tested there. That is why the
application is coherent without them. It is not evidence that the SQL parses.

**To close it:** one working Docker installation, then `./mvnw -B test` with `Skipped: 0`. Run
`smoke.EndToEndSmokeIntegrationTest` first. §1 of [`DEMO_SCRIPT.md`](DEMO_SCRIPT.md) makes the first
apply part of demo preparation for exactly this reason.

**Never report a database certification as passing while this stands.**

## 2. BLOCKER for the operator — the backend `.env` may point at a remote project

`backend/tms-api/.env` is untracked and git-ignored, so nothing in this repository can see or fix
it. On this machine it was checked, by variable name and host *class* only with no value read or
printed, and it matched a remote Supabase pooler host with `TMS_FLYWAY_ENABLED=true`.

Spring is configured `baseline-on-migrate: false`, `validate-on-migrate: true`. **Exporting that
file and starting the backend would run the twelve unproven migrations of §1 against a live
project** — including a `NOT NULL` backfill and a foreign-key repoint.

Nothing loads it automatically: there is no dotenv dependency, no `spring.config.import`, and
`scripts/dev-backend.sh` does not source it. It takes a deliberate human gesture. The gesture is an
ordinary one.

**Before starting the backend for any reason:** point `TMS_DB_URL` at a local database, or set
`TMS_FLYWAY_ENABLED=false`.

## 3. ENVIRONMENT — no quality gate could be executed in this session

No test, typecheck, lint or build command could be run while producing this document: the
toolchain (`java`, `./mvnw`, `npm`, `npx playwright`, `docker`) is not approved in the session that
wrote it. Every count in these documents is therefore a **static count of declared test methods**,
not a run result.

That is reported rather than worked around. No suite was estimated, inferred, or copied forward
from a previous report as though it had run.

---

## 4. DEFECTS — three, all in automatic planning, none data-corrupting

All three are in `AutoPlanningService` and were re-verified against source in this run. None of
them writes a wrong row; two of them make the *screen* say something that is not quite true.

### 4.1 The applied report can count one order twice

`AutoPlanningService:141-169`. On `apply`, an order that a concurrent edit refuses is appended to
`unplanned`, while `outcome` reuses `proposal.trips()` **unchanged** — so the same order is still
listed on its proposed trip. The screen can therefore show
`ordersConsidered ≠ planned + unplanned`.

The invariant `assertEveryOrderAccountedFor` runs on the *proposal*, not on the applied outcome.

**Impact:** the database is correct; the report is not. It needs a concurrent planner on the same
run to happen at all.
**Fix:** rebuild the outcome's trip list from what was actually assigned, and re-assert the
invariant after `apply` rather than only after `propose`.

### 4.2 A trip whose every order was refused survives as an empty draft

`AutoPlanningService:141-159`. `created.add(trip)` sits outside the try/catch and outside the order
loop, so it runs unconditionally. A proposed trip that lost every one of its orders to a race is
still created — and it has booked its vehicle for that operating date, so the double-booking index
will refuse a later, real trip for the same truck.

**Impact:** a planner deletes an empty trip. Annoying, visible, not silent.
**Fix:** create the trip lazily, or delete it when it ends with no assignment.

### 4.3 A finder called with a random argument that excludes nothing

`AutoPlanningService:277-283` passes `UUID.randomUUID()` as the `idNot` argument of
`existsByCompanyIdAndVehicleIdAndPlanningDateAndStatusNotAndIdNot`. It is **correct** — a random
UUID excludes nothing, which is what "is this vehicle busy at all" wants — but it reads as a bug and
it depends on a finder shaped for a different caller.

**Impact:** none.
**Fix:** a finder without the `idNot` argument.

---

## 5. GAPS — real absences, in the order a customer would hit them

### 5.1 An order has no delivered status

`OrderStatus` stops at `PLANNED`. Completing a trip and recording every delivery leaves its orders
`PLANNED`. What was handed over is recorded on the trip side, in `tms.order_delivery`.

This is **the product's largest known modelling gap**, and it is deliberate rather than forgotten:
the orders module owns that lifecycle and giving it a delivered state is its own migration with its
own transitions. V25, V27 and V28 each said so. The table that will feed it exists.

**Consequence today:** "which of my orders were delivered" is answered from the trip, not from the
order list.

### 5.2 The audit trail cannot be read from the product

`tms.audit_event` is append-only and the application role cannot update or delete it. Every
business act writes one. `AUDIT_VIEW` exists as a capability and is granted.

**There is no read endpoint and no screen.** Today it is a SQL query against the table.

**To close:** a controller and a service over an existing table with existing indexes. No schema
change.

### 5.3 No cross-trip view of what went wrong

Per trip: problems, skipped stops, failed deliveries — all present. Across trips — *"every open
problem in the company"*, *"everything that did not arrive today"* — there is no screen. The
database indexes for both exist (`ix_trip_exception_company_open`,
`ix_order_delivery_company_shortfall`). The control tower answers a related but narrower question
for one day.

### 5.4 `/account` is a placeholder

The one route in the product that still resolves to `PlaceholderPage`, reachable from the user menu.
Everything else the sidebar offers is real.

### 5.5 Orders imported from a spreadsheet must be marked ready one at a time

There is no bulk "mark ready for planning". Orders delivered over the integration API can be marked
ready in the same call (`markReadyForPlanning: true`); orders from a CSV or XLSX land `NOT_READY`
and each is marked ready from its own row menu, behind a confirmation.

That is defensible for a handful and tedious for a hundred. It is also why
[`demo-data/README.md`](demo-data/README.md) sends most of the demo's orders through the API.

### 5.6 There is no organization administration screen

A company can be created, configured and staffed from the product. Renaming or deactivating the
*organization* cannot. The company screen shows `organizationActive` so an administrator staring at
an unreachable company sees the reason.

### 5.7 No email anywhere

No invitation mail, no alert delivery, no scheduled report. Alerts are in-app only. Each would need
a transport, a template, a bounce policy and a suppression list.

### 5.8 Tender expiry is resolved lazily

A sent offer past its deadline becomes `EXPIRED` when something next looks at it, not at the moment
it lapses. The KPI report's `expired` count is therefore a **floor**. Exact expiry needs a sweep.

### 5.9 Proof-of-delivery attachments are off by default

`TMS_EVIDENCE_STORAGE_MODE=DISABLED`, and the default is the decision (ADR-006): a deployment that
has not said where a customer's signed delivery note goes must not have somewhere guessed for it.
A local-volume implementation ships and is fine for a single node; it is not an answer for two
instances behind a load balancer, and the Supabase Storage adapter is not written.

Delivery **results** are recorded either way. This only turns the attachments on.

### 5.10 No vendor adapter for tracking

The contract, the sampling policy, the storage and the map are built. **Nothing speaks any
telematics provider's protocol.** Connecting one is an implementation of `TrackingIntakePort` or
`TrackingProviderPort` (ADR-007), which is a real piece of work and not a configuration setting.

### 5.11 Evidence cannot be deleted

There is no deletion path and no retention job, and `DELETE` is withheld from the application role.
That is the honest interim answer — nothing can remove evidence by accident — and it becomes a real
problem the day a customer asks for erasure. Closing it needs a data-protection decision, not a
migration.

---

## 6. What the product is not

Stated once, plainly, so nobody has to infer it from an absence.

| Not | Nearest thing that does exist |
|---|---|
| A route optimiser | A capacity-and-eligibility heuristic producing editable drafts |
| A driver mobile app | Per-stop and per-order execution endpoints a driver app would write to |
| A carrier portal | A carrier-facing tender API |
| A customer portal or a public tracking page | Positions and delivery results on the internal API |
| A billing or invoicing system | Carrier cost, estimated vs actual, with the variance |
| A yard, dock or warehouse system | Nothing. TMS is independent of EWM by design |
| A packaged ERP/SAP connector | A published, versioned, idempotent integration API |
| An ETA engine | Recorded actual times and a service-window comparison |

---

## 7. CEILINGS — where the current design stops scaling

These are accepted and documented, not defects. Design target: 10,000+ orders/day, 100–300
vehicles, multiple companies.

| Ceiling | Where it binds | Accepted because |
|---|---|---|
| `GET /planning/runs/{id}` returns every trip of the run | Bounded by trips per run, roughly fleet size | The board is deliberately one call. Splitting it would break that design for no benefit at V1 volumes |
| `GET /masterdata/frequencies/{id}/exceptions` is unpaginated | Response size | Company-scoped and tenant-safe. Paginating changes the frontend contract |
| KPI range capped at 92 days | One request's cost | It is also what keeps the chart readable — 365 columns is not a chart |
| Tracking read clamped to 2,000 positions | Map trail length | The map draws a recent trail, not a forensic reconstruction |
| Import capped at 2 MB and a bounded row count | One upload | Above that, an upload stops being a delivery and becomes a migration |
| Integration batch default 200, hard ceiling 1,000 | One request | Same reason |
| The principal is resolved with two indexed queries per authenticated request | Latency | A cache here would hold *authorization* state and needs an explicit invalidation story first — a cache that keeps a revoked membership alive for 60 seconds is a security regression, not an optimisation |
| The frontend ships as a single large chunk | First load | Carried, and unverified in this session because no build could be run (§3) |

Measured detail: [`../performance/PERFORMANCE_BASELINE.md`](../performance/PERFORMANCE_BASELINE.md).

---

## 8. Documentation that has drifted

Found while assembling this pack. Each is a statement in a module document that a later migration
made untrue. None affects behaviour; all of them would mislead a reader.

| Where | Says | Actually |
|---|---|---|
| `../domain/TRIP_EXECUTION_V1.md` §12 | "No GPS, no telematics, no live position. Deferred by decision" | Migration **V29** and ADR-007 added position intake, storage and a map. The bullet predates them |
| `../domain/KPIS_REPORTING_V1.md` §10 | "nothing in this installation runs on a timer" | Migration **V35** added the webhook dispatcher, the one `@Scheduled` task in the product |
| `../domain/ALERTS_NOTIFICATIONS_V1.md` §2.1 | "Nothing in this installation runs on a timer" | Same |
| `../domain/CARRIER_TENDERING_V1.md` §11 | "No UI for binding a credential to a carrier… the whole module is API-only" | The Integration Hub (`/settings/integrations`) does both. `IntegrationClientDrawer` shows the carrier field exactly when the tender scope is ticked |
| `../integrations/INBOUND_API_V1.md` §6.2 | A batch response example with `"status": "DRAFT"` | `OrderStatus` has no `DRAFT`. An order with no `markReadyForPlanning` is `NOT_READY` |
| `../integrations/API_CONTRACTS.md` §2 | The credential self-check is `GET /integration/v1/me` | It is **`GET /integration/v1/ping`** (`IntegrationIdentityController`). `INBOUND_API_V1.md` had it right; the register did not. This is the one that would actually have cost a partner an hour |
| `frontend/tms-web/src/shared/ui/navConfig.ts:34-37` | Origins and Destinations are "compatibility projections" of migration V14 | Migration **V23** retired the projections. They are filtered views of the canonical location |
| `../README.md` | Indexes 27 documents | The `domain/` and `integrations/` directories have grown well past that list |

The first six are corrected in this job. The last two are left deliberately: one is production
frontend source outside this job's scope (carried as **P2-7** since the job 00 audit), and the other
is a re-index better done once, deliberately, than as a side effect of a demo pack.

---

## 9. Where each item is discussed at length

| Item | Document |
|---|---|
| Order lifecycle | [`../domain/ORDER_LIFECYCLE_V1.md`](../domain/ORDER_LIFECYCLE_V1.md) |
| Audit trail | [`../domain/AUDIT_TRAIL_V1.md`](../domain/AUDIT_TRAIL_V1.md) §7 |
| Execution and stops | [`../domain/TRIP_EXECUTION_V1.md`](../domain/TRIP_EXECUTION_V1.md) §12 |
| Delivery and evidence | [`../domain/PROOF_OF_DELIVERY_V1.md`](../domain/PROOF_OF_DELIVERY_V1.md) §11 |
| Tracking | [`../domain/TRACKING_V1.md`](../domain/TRACKING_V1.md) §9, ADR-007 |
| Rates | [`../domain/RATES_COSTING_V1.md`](../domain/RATES_COSTING_V1.md) §8 |
| Tendering | [`../domain/CARRIER_TENDERING_V1.md`](../domain/CARRIER_TENDERING_V1.md) §11 |
| KPIs | [`../domain/KPIS_REPORTING_V1.md`](../domain/KPIS_REPORTING_V1.md) §10-11 |
| Alerts | [`../domain/ALERTS_NOTIFICATIONS_V1.md`](../domain/ALERTS_NOTIFICATIONS_V1.md) §10 |
| Administration | [`../domain/SAAS_ADMINISTRATION_V1.md`](../domain/SAAS_ADMINISTRATION_V1.md) §7 |
| Webhooks | [`../integrations/WEBHOOKS_V1.md`](../integrations/WEBHOOKS_V1.md) §11 |
| The published surface | [`../integrations/API_CONTRACTS.md`](../integrations/API_CONTRACTS.md) §6 |
