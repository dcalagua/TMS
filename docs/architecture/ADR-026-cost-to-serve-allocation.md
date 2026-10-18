# ADR-026 - Cost to serve: allocating a trip's cost to its orders

- Status: Accepted (design; implementation in overnight-pack phase 70)
- Date: 2026-10-18
- Relates to: ADR-022 (effective volume and floor area as bases), ADR-023 (accruals, approval),
  ADR-024 (subtotal, net of IGV), plan `03_FINANZAS_FLETES.md` F-5, decision D-08 (configurable base,
  **no margin**: there is no sales tariff). Closes debt D10 of the capability map ("no cost per order").
- Migration: **V68** (provisional; `PHASE_MAP.md` registers).

## Context

- A trip's cost is `tms.trip_cost` (V30): `estimated_amount`, `actual_amount`, closed by `closed_at`;
  own-fleet trips are quoted by `costing` (V48) and are not freight payables.
- What a trip carries is the ledger `tms.trip_order_assignment` (V11/V37): per ACTIVE row
  `assigned_weight_kg`, `assigned_volume_m3` (the planning volume after ADR-022), `assigned_pallets`,
  and after ADR-022 `assigned_floor_area_m2`. A split order has one row on each trip.
- Stops: `tms.trip_stop`, each order on one stop of the trip.
- KPIs: `planning/application/KpiService` (`/reporting/kpis`) already reads costs through
  `shared.reference.TripCostAnalyticsPort` (`rates/infrastructure/TripCostAnalyticsAdapter`).

## Decision

1. **Where.** Module `settlement` (it owns approval, the trigger that matters most), reading trips and
   ledgers through `shared.reference` ports (`TripSettlementLookupPort` gains the assignment amounts).
2. **Base, per company.** `company_settings.cost_allocation_basis` (nullable) IN `WEIGHT`,
   `EFFECTIVE_VOLUME`, `FLOOR_AREA`, `PALLETS`, `STOPS`, `EQUAL`; null -> property
   `tms.finance.cost-allocation.default-basis` (`WEIGHT`). `STOPS` splits the cost equally between stops,
   then within a stop by weight; `EQUAL` splits by assignment row.
3. **Which cost.** In order of preference: the approved invoice amount for that trip (subtotal, ADR-024),
   else the closed actual, else the estimate. The run says which (`cost_source` `INVOICED|ACTUAL|ESTIMATED`).
   Own-fleet trips use the `costing` quote when one exists (`OWN_FLEET_QUOTE`); otherwise they are not
   allocated and are counted.
4. **Tables (V68).**
   - `tms.trip_cost_allocation_run`: `trip_id`, `version` (1, 2, ...; `uq (trip_id, version)`), `basis`,
     `cost_source`, `currency`, `cost_amount`, `allocated_amount`, `unallocated_order_count`,
     `unallocated_reason`, `computed_at`, `computed_by` (nullable: system), `trigger`
     (`TRIP_COST_CLOSED|INVOICE_APPROVED|MANUAL`), `superseded_at`. Exactly one current run per trip
     (`uq_trip_cost_allocation_run_current (trip_id) WHERE superseded_at IS NULL`).
   - `tms.trip_cost_allocation`: `run_id`, `trip_id`, `order_id`, `assignment_id`, `basis_quantity`,
     `share numeric(9,6)`, `allocated_amount numeric(14,2)`; or `allocated_amount` null with `reason`
     (`NO_BASIS`) for a row that could not be measured.
   - Both append-only (trigger + `SELECT, INSERT`; `superseded_at` is set by a SECURITY DEFINER function or
     by inserting the next version and letting the current-run index move: the implementation picks the
     former and tests it). RLS per tenant (ADR-005).
5. **Recalculating never overwrites.** A new run (version + 1) supersedes the previous one. Triggers: trip
   cost closed (found by the same kind of sweep as ADR-023 - a closed `trip_cost` newer than the current
   run), invoice approved (same transaction as the approval), and a manual "Recalcular" with
   `settlement.cost-allocation:manage`.
6. **No invented allocation.** Rows whose base is unknown (an order without weight when the base is
   `WEIGHT`) receive nothing; the cost is split among the measurable rows and the rest is **counted**
   (`unallocated_order_count`). If no row is measurable, the run allocates nothing and says so.
   Rounding: shares to 6 decimals; amounts HALF_UP to cents; the rounding remainder goes to the largest
   share so that the sum equals the cost (tested).
7. **Reports.** `GET /api/v1/settlement/cost-to-serve?from=&to=&groupBy=CUSTOMER|DESTINATION|ROUTE` (and
   export CSV/JSON), with cost, cost per kg, per m3 and per order, and the count of unallocated orders.
   "Customer" is `transport_order.customer_reference` (else `customer_name`), free text since V10; no customer
   master is introduced. Permission `settlement.cost-allocation:read`. The screen says plainly that
   there is **no margin because there is no sales tariff**.
8. **Finance KPIs** in `KpiReportView` (new `finance` block): open accrual balance, accrual vs invoice
   variance, average days to payment (approved -> paid, ADR-023), cost per kg.

## Consequences

- An order's cost is always traceable to one run, one basis and one cost source.
- A trip split across two trips (V37) gets cost on both, each from its own trip.

## Not done (named)

Sales tariffs and margin (roadmap item 4), carbon allocation, allocation across multi-leg trips (no
multi-leg model exists).

## Implementation notes (phase 70, V68)

- **V68** `V68__trip_cost_allocation.sql` (the provisional number held). `company_settings.cost_allocation_basis`
  (nullable; null = `tms.finance.cost-allocation.default-basis`, `WEIGHT`), `trip_cost_allocation_run`,
  `trip_cost_allocation`, RLS per tenant, permissions `settlement.cost_allocation:read|manage` (underscore, not hyphen:
  `ck_permission_resource_shape`, as `masterdata.material_profile` in phase 20), audit action `COST_ALLOCATED` on the
  `TRIP` aggregate.
- **Superseding** (section 4): a column-level grant `UPDATE (superseded_at)` for `tms_app` plus the guard trigger
  `tr_trip_cost_allocation_run_guard`, which allows only `superseded_at` NULL -> set, once, and refuses DELETE, also for
  the owner. No SECURITY DEFINER function: the grant and the guard hold the same rule with less surface.
- **Triggers**: `TRIP_EXECUTED` joins the three of section 5 - the first run, on the estimate, when a trip that left has
  none (the plan says "the actual if there is one, else the estimate"). The sweep (`CostAllocationSweep`, every 10 min,
  lookback 62 days over departures) writes `TRIP_EXECUTED` or `TRIP_COST_CLOSED`; approval writes `INVOICE_APPROVED` in
  its own transaction (credit and debit notes too); `POST .../recalculate` writes `MANUAL`. An automatic trigger whose
  result would equal the current run writes nothing; a person's recalculation always writes a version. Once a run rests
  on `INVOICED`, a later close of the trip cost does not move it.
- **Invoiced figure**: the sum of the lines for the trip over every authorised document (`InvoiceStatus.AUTHORISED`),
  so a credit note subtracts, a debit note adds and an accepted penalty counts; floored at 0; in the trip cost's
  currency when billed in it.
- **Own fleet**: allocated from its `trip_cost` (actual or estimate) when it has one. The `costing` quote
  (`OWN_FLEET_QUOTE`) is not consulted yet; without a `trip_cost` the trip is not allocated and is counted by the sweep.
- **What the report groups by** is copied onto the run (shipment, carrier, route label) and its rows (order number,
  customer label, destination label, the ledger measures) when computed, so the report reads `settlement` tables only
  and a version reads as it was.
- **STOPS**: a stop is the order's destination; equal parts between the stops that have at least one weighed order,
  then by weight within the stop.
- **Finance KPIs** (`KpiReportView.finance`, shown with `settlement.accrual:read`): open accrual balance at the end of
  the range; accrual vs invoice for accruals served in the range and consumed (invoiced lines - accrued); average days
  from the first approval to the last payment of the invoices paid in full in the range (company calendar); cost per kg
  of the current allocations in the range.
