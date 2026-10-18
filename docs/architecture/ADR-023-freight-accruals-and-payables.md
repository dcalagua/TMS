# ADR-023 - Freight accruals and payable payment status (TMS does not move money)

- Status: Accepted (design; implementation in overnight-pack phases 55 and 65). D-01 is marked
  "PENDIENTE confirmación" in `DECISIONES.md`: this ADR is written so that a later "TMS pays" decision
  is added on top, not by rewriting it.
- Date: 2026-10-18
- Relates to: V46 (`freight_settlement`, "TMS validates, ERP pays"), ADR-005 (tenant RLS), ADR-018
  (carrier portal, `tms.current_carrier_id()`), ADR-024 (taxes: an accrual is consumed by the
  **subtotal**), ADR-026 (cost to serve), plan `03_FINANZAS_FLETES.md` F-2 and F-4, decisions D-01, D-07.
- Migrations: **V65** (accruals, GL mapping, `accrual_trigger`), **V67** (payment terms, payable
  statuses, payments, portal policies, business outbox events). Provisional; `PHASE_MAP.md` registers.

## Context

From the code at `a57af24`:

- `V46__freight_settlement.sql:13-16` and its section 12: no ledger, no payment, no bank detail, no
  accounting period; the hand-off to the ERP is an **export** (`tms.payable_export`, one per invoice,
  formats `JSON`/`CSV`, only `JsonPayableExportAdapter` exists).
- `settlement/domain/InvoiceStatus.java:19-66`: `RECEIVED -> MATCHING -> MATCHED|DISCREPANCY ->
  UNDER_REVIEW -> APPROVED -> EXPORTED`, plus `REJECTED`; `EXPORTED` and `REJECTED` are terminal.
  Approval is four-eyes (`SettlementService.requireDifferentApprover`, l.418).
- Trip cost: `tms.trip_cost` (V30), one row per trip, `estimated_amount`, `actual_amount`, closed when
  `closed_at` is set (`ck_trip_cost_closed_has_actual`), reopenable. `TripCostService.close/reopen`.
- Trip lifecycle: `TripExecutionService.dispatch` (and `applyWarehouseDispatch` for ADR-013) moves a trip
  to `IN_TRANSIT`; `TripExecutionService.complete` to `COMPLETED`. **A trip cannot be cancelled after
  `IN_TRANSIT`** (`TripStatus` transitions).
- Append-only in settlement is enforced by **grants** (`settlement_approval`, `payable_export`:
  `SELECT, INSERT`; V50 revokes `UPDATE, DELETE`), not by triggers. Trigger precedent:
  `tms.platform_entitlement_append_only()` (V56) used by V58.
- `settlement` is **missing from `ModuleBoundaryTest.BUSINESS_MODULES`**, so the cross-module rule skips
  it silently (it happens to depend only on `shared` today).
- `tms.carrier` (V9) has `tax_id_type/tax_id_value`, no currency, no payment terms.
- Webhooks: `WebhookEventType` mirrors the shipment outbox (`tms.shipment_outbox_event`, `trip_id NOT
  NULL`), so a non-trip event (an invoice) has no outbox to be written to.

## Decision

### 0. Module

Accruals and payables live in **`com.ebim.tms.settlement`**. Phase 50 adds `"settlement"` to
`BUSINESS_MODULES` (with the comment the others carry) before any of this is written, so that the new
code is held to "talk through `shared`". No new `accounting` module: it would need the same ports and
split one money flow across two modules.

### 1. Freight accrual (F-2, D-07) - V65

**`tms.company_settings.accrual_trigger`** text NOT NULL DEFAULT `'DISPATCHED'`, CHECK
`IN ('DISPATCHED','DELIVERED')`. Same shape and reasoning as V52's `dispatch_confirmation_mode`:
the default is the decision (D-07), and `CompanySettings` gains the field. `DELIVERED` means the trip
reached `COMPLETED`.

**`tms.freight_accrual`** - one per trip and currency:

| Column | Rule |
|---|---|
| `id`, `company_id`, `trip_id` (composite FK), `carrier_id` (nullable for own fleet, composite FK) | `uq_freight_accrual_trip_currency (trip_id, currency)` |
| `currency` char(3) | `^[A-Z]{3}$` |
| `status` | `OPEN`, `PARTIALLY_SETTLED`, `SETTLED`, `REVERSED` |
| `basis` | `ESTIMATED` or `ACTUAL` - what the current balance is based on |
| `service_date` date | the accounting date: the trip's departure (or completion) **in the company time zone** (`tms.company.time_zone`) |
| `accrued_amount`, `consumed_amount`, `reversed_amount` numeric(14,2) | running totals, maintained only by the service in the same transaction as the entry; `ck_freight_accrual_amounts` (all >= 0) |
| `version`, `created_at`, `updated_at` | |

**`tms.freight_accrual_entry`** - the movements, **append-only by trigger** (`tr_freight_accrual_entry_append_only`
raising on UPDATE/DELETE, function in V65 following V56) **and** by grant (`SELECT, INSERT` only):

| Column | Rule |
|---|---|
| `entry_type` | `ACCRUE`, `ADJUST`, `CONSUME`, `REVERSE` |
| `amount` numeric(14,2) | signed: ACCRUE > 0, CONSUME < 0, REVERSE < 0, ADJUST != 0 (`ck_freight_accrual_entry_sign`) |
| `period` date | first day of the accounting month the entry belongs to |
| `source_type` / `source_key` | `TRIP_DISPATCHED`, `TRIP_COMPLETED`, `TRIP_COST_ACTUAL`, `INVOICE_APPROVED`, `MANUAL`; the key is the trip id, the trip cost version or the invoice id |
| `carrier_invoice_id` | set for CONSUME |
| `reason` | required for MANUAL and REVERSE |
| `created_by` (nullable: system) | |

`uq_freight_accrual_entry_source (accrual_id, entry_type, source_type, source_key)` **is** the
idempotency of every automatic movement: a repeat is a unique violation caught and treated as done.

**How it is born - a sweep, not a listener.** `AccrualSweep` (a scheduled job with a row-claiming query,
`FOR UPDATE SKIP LOCKED`, default every 5 minutes, `tms.settlement.accrual.sweep-interval`) looks for
trips of every company with `actual_departure_at` (or `COMPLETED` for `DELIVERED`) and no accrual, and
creates them. The question "which trips should be accrued and are not" is a query over facts that are
already committed, so nothing can be lost - an in-memory listener can lose a fact, and a same-transaction
hook would make a dispatch fail because accounting could not price it. The sweep is also the report
"trips without accrual" (below).

- **Amount:** the trip's `actual_amount` if `trip_cost` is closed, otherwise `estimated_amount`
  (`TripCostLookupPort`). Own-fleet trips (V48) are not freight payables and are **not** accrued.
- **No inventing:** a trip with no `trip_cost` row or no computable estimate gets **no** accrual; it is
  listed in "Viajes sin provisión" with the reason (`NO_TRIP_COST`, `NOT_CALCULABLE`, `NO_CARRIER`).
- **Adjust:** when `trip_cost` closes with an actual that differs from the accrued balance and the accrual
  is still `OPEN`, the sweep writes `ADJUST` for the difference (`source_type TRIP_COST_ACTUAL`, key =
  `trip_cost.version`), `basis = ACTUAL`.
- **Consume:** `SettlementService.approve` consumes in the **same transaction** (same module): for each
  trip on the approved invoice, `CONSUME` = the invoice lines of that trip, **net of IGV** (ADR-024; before
  phase 60 the line amount). Balance zero or below -> `SETTLED` and the variance (accrued - invoiced) is
  visible; above zero -> `PARTIALLY_SETTLED`.
- **Close the remainder / reverse:** a trip cannot be cancelled after dispatch, so there is no automatic
  reversal today. A person with `settlement.accrual:manage` closes a `PARTIALLY_SETTLED` accrual
  (REVERSE of the balance, reason required) or reverses an `OPEN` one (service not billable). If a future
  rule allows cancelling a dispatched trip, the sweep reverses it (`source_type TRIP_CANCELLED`) without
  schema change beyond the CHECK.

**`tms.gl_account_mapping`** (company, `concept` IN `FREIGHT_EXPENSE`, `FREIGHT_ACCRUAL`, `IGV_CREDIT`,
`DETRACCION_PAYABLE`, `RETENCION_PAYABLE`, `CARRIER_PAYABLE`; `account_code`; optional `cost_center_code`;
`scope_type` `COMPANY|ORIGIN|ROUTE` + `scope_id`; `active`). One active row per (company, concept, scope).
The most specific scope wins (ROUTE > ORIGIN > COMPANY). **Without a mapping the export warns** and leaves
the account empty; it never invents one.

**Reports and export** (`GET /api/v1/settlement/accruals`, `/accruals/{id}`, `/accruals/aging?asOf=`,
`/accruals/unaccrued?from=&to=`, `/accruals/journal?period=YYYY-MM&format=JSON|CSV`): open balance at a
date, aging buckets 0-30 / 31-60 / 61-90 / >90 days from `service_date`, by carrier and currency;
journal of the period (accruals, adjustments, consumptions, reversals) with the mapped accounts. **There is
no general ledger**: it is a file for the ERP, and its generation is audited (`ACCRUAL_JOURNAL_EXPORTED`).

Permissions (seeded, V46 shape): `settlement.accrual:read` (ORGANIZATION_ADMIN, COMPANY_ADMIN, PLANNER,
VIEWER), `settlement.accrual:export` and `settlement.accrual:manage` (ORGANIZATION_ADMIN, COMPANY_ADMIN).
GL mapping is edited with `settlement.accrual:manage`. Audit: aggregate `FREIGHT_ACCRUAL`, actions
`ACCRUAL_CREATED`, `ACCRUAL_ADJUSTED`, `ACCRUAL_CONSUMED`, `ACCRUAL_REVERSED`, `ACCRUAL_JOURNAL_EXPORTED`,
`GL_MAPPING_CHANGED`. Screen "Provisiones" (list with period/carrier/currency filters, detail with
movements, export), plus the unaccrued list.

### Implementation notes - section 1 (phase 55, V65)

- `V65__freight_accruals_and_gl_mapping.sql`: `company_settings.accrual_trigger`, `freight_accrual`,
  `freight_accrual_entry` (append-only by `tr_freight_accrual_entry_append_only` and by grant), `gl_account_mapping`
  (partial unique index `uq_gl_account_mapping_active_scope`), partial indexes on `trip (company_id,
  actual_departure_at|actual_completion_at, id)` for the sweep, three permissions, six actions, aggregates
  `FREIGHT_ACCRUAL` and `GL_ACCOUNT_MAPPING`. No business rows.
- **Module:** `settlement`, as section 0 says. Trip facts arrive through a new `shared.reference.AccruableTripLookupPort`
  answered by `planning` (native SQL over `tms.trip` only); costs through `TripCostLookupPort`, whose summary gains
  `closedAt`. `ModuleBoundaryTest` is green.
- **The sweep** (`AccrualSweep`, timer `AccrualSweepScheduler`, `tms.settlement.accrual.sweep-enabled|sweep-interval|lookback`,
  defaults `true`/`5m`/`31d`) asks one cross-tenant question on the owner connection (companies with a trip executed
  within the lookback, or with an open accrual) and then works **each company under `SystemJobAuthentication`**
  (`shared.security`): the connections switch to `tms_app` bound to that company, so RLS covers the job, and the audit
  names the machine actor `system:accrual-sweep`. Each trip is accrued in its own transaction; a concurrent node's
  unique violation is caught and counted as done. Adjustments claim open accruals `FOR UPDATE SKIP LOCKED`.
- **Lookback and "accrue now":** the automatic pass reads trips executed within the lookback; older ones stay in the
  unaccrued report as `PENDING_SWEEP` and a person with `settlement.accrual:manage` runs the same sweep over any range of
  up to a year with `POST /settlement/accruals/sweep?from=&to=`.
- **ADJUST key:** `tms.trip_cost` has no version column, so the key is the trip cost's **closing instant**; a reopen
  clears it, so each close is a distinct key. An equal actual books nothing and only moves `basis` to `ACTUAL`.
- **Order independence:** an accrual born after an invoice for its trip was approved consumes that invoice on birth
  (same transaction), so the balance does not depend on which of the two happened first.
- **Consume** is the sum of the invoice's lines for the trip (phase 60 has not landed: line amount, not subtotal). A
  `REVERSED` accrual is not consumed. Closing a `PARTIALLY_SETTLED` remainder leaves it `SETTLED`; reversing an `OPEN`
  one leaves it `REVERSED`.
- **Aging:** the balance at a date is summed from the movements: the birth is effective on the service date, every later
  movement at the instant it was booked. Only a positive balance is open; a negative one is a variance.
- **Journal:** `GET /settlement/accruals/journal?period=YYYY-MM&format=JSON|CSV` (`AccrualJournal`, pure; golden file
  `src/test/resources/settlement/accrual-journal-golden.csv`). ACCRUE/ADJUST+ debit `FREIGHT_EXPENSE` credit
  `FREIGHT_ACCRUAL`; ADJUST-/REVERSE the opposite; CONSUME debit `FREIGHT_ACCRUAL` credit `CARRIER_PAYABLE` (consumption
  above the accrual is not split; the accrual's negative balance is the variance). The CSV carries no warning rows; the
  `X-Journal-Warnings` header counts them. Every generation is audited against the company id.
- **GL mappings:** `GET|POST /settlement/gl-mappings`, `PUT /{id}` (account and cost centre only), `POST /{id}/deactivate`;
  retired, never deleted.
- **Capabilities:** `ACCRUAL_VIEW|EXPORT|MANAGE`. Screen «Provisiones» (`/settlement/accruals`): list, detail with
  movements and reverse/close with a reason, aging, unaccrued trips with "accrue now", journal, GL accounts. The company
  settings screen edits the trigger.
- **No TRUNCATE trigger:** `MigrationConventionTest` forbids the word in a migration; `tms_app` was never granted it
  (V13's default privileges are the four DML verbs).

### 2. Payable payment status (F-4, D-01) - V67

TMS records what the ERP reports. It holds no bank account, produces no bank file and executes nothing.

- **Terms.** `tms.carrier.payment_terms_days` integer (nullable, `0..365`), else
  `company_settings.default_payment_terms_days` (nullable), else property
  `tms.settlement.default-payment-terms-days` (30, D-06). `carrier_invoice.due_date` = `invoice_date + terms`,
  set at **approval** and frozen.
- **Statuses after `EXPORTED`.** `InvoiceStatus` gains `SCHEDULED`, `PARTIALLY_PAID`, `PAID`, `ON_HOLD`;
  `ck_carrier_invoice_status` is restated. `EXPORTED` stops being terminal. Transitions (domain table + test):

  | From | To |
  |---|---|
  | `EXPORTED` | `SCHEDULED`, `PARTIALLY_PAID`, `PAID`, `ON_HOLD` |
  | `SCHEDULED` | `PARTIALLY_PAID`, `PAID`, `ON_HOLD` |
  | `PARTIALLY_PAID` | `PARTIALLY_PAID`, `PAID`, `ON_HOLD` |
  | `ON_HOLD` | `SCHEDULED`, `PARTIALLY_PAID`, `PAID` (reason required to enter and leave) |
  | `PAID` | terminal |

  `carrier_invoice` gains `paid_amount` numeric(14,2) NOT NULL DEFAULT 0, `on_hold_reason`.
- **`tms.carrier_payment`** - append-only (trigger + grants): `carrier_invoice_id`, `payment_reference`
  (`uq_carrier_payment_reference (company_id, carrier_invoice_id, payment_reference)`), `paid_on` date,
  `amount` > 0, `currency` (must equal the invoice's: **no FX**), `detraction_constancy_number`,
  `detraction_constancy_date`, `detraction_amount`, `retention_amount`, `source` `INTEGRATION|OPERATOR`,
  `recorded_by_client` xor (`recorded_by` + `confirmed_by`, different people).
- **ERP channel.** `POST /integration/v1/payable-payments` (scope **`integration.payable-payment:write`**,
  operation `PAYABLE_PAYMENT`, `Idempotency-Key`). The invoice is named by business key (carrier tax id +
  document type + series + correlative, or `invoiceNumber` + `carrierCode` for pre-CPE invoices). A payment
  that exceeds the open balance (`total - credit notes + debit notes - detraction - retention - paid`) is
  **422** `payment-exceeds-balance`; a currency mismatch is 422 `payment-currency-mismatch`. Contract in
  `docs/integrations/INBOUND_API_V1.md` (new section, phase 65).
- **Manual channel, four eyes.** `tms.carrier_payment_request` (mutable: `PENDING|CONFIRMED|REJECTED`);
  confirming inserts the `carrier_payment` row; the confirmer must differ from the requester (the
  `requireDifferentApprover` rule). Permissions `settlement.payment:record` and `settlement.payment:confirm`.
- **Reports.** Aging of payables and upcoming due dates (`/settlement/payables/aging`, `/due?within=`);
  a Control Tower card "Facturas vencidas / por vencer".
- **Carrier portal "Mis facturas".** `GET /api/v1/portal/invoices[/{id}]`, permission `portal.invoice:read`
  (granted to `CARRIER_OPERATOR`), Java filter on `CompanyScope.carrierId()` **and** a restrictive policy
  `p_carrier_scope` (`tms.current_carrier_id() IS NULL OR carrier_id = tms.current_carrier_id()`) on
  `carrier_invoice`, `carrier_invoice_line` (via EXISTS on the invoice) and `carrier_payment`. Shows status,
  due date, paid amount, detraction; never another carrier's row, never discrepancy internals.
- **Webhooks.** `INVOICE_APPROVED`, `PAYABLE_EXPORTED`, `PAYABLE_PAID`. They are not trip events, so they use
  **`tms.business_outbox_event`**, introduced by V61 for `ORDER_CANCELLED` (ADR-025 section 4; if phase 35
  did not land, V67 creates it with the same definition) and widened by V67 to `resource_type
  CARRIER_INVOICE`. Rows are written in the same transaction as the fact (`SELECT, INSERT` only) and fanned
  out through the existing `EventFanoutPort` into `tms.webhook_delivery` (which is resource-agnostic). The envelope is
  `WEBHOOKS_V1.md`'s with `resource.type = "carrier-invoice"`. `ck_webhook_subscription_event_type` is
  restated with the new names.

## Consequences

- An accrual never blocks a dispatch, and a dispatch never waits for accounting.
- The accrual balance is reproducible from its entries alone (`SUM(amount)` = balance), checked by a test.
- Recording a payment changes a status in TMS and nothing else; if D-01 is reversed ("TMS pays"), payment
  execution is a new port behind `carrier_payment`, not a change to this model.

## Not done (named)

Bank files, payment execution, bank accounts, general ledger, FX, period locking (a closed month is the
ERP's concern; TMS dates entries and never edits them).
