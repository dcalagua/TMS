# ADR-024 - Carrier documents: settlement hardening, Peruvian taxes (IGV, SPOT, retention), CPE, credit and debit notes, penalties

- Status: Accepted (design; implementation in overnight-pack phases 50 and 60)
- Date: 2026-10-18
- Relates to: V46 (freight settlement), ADR-023 (accruals and payables), plan `03_FINANZAS_FLETES.md`
  F-1 and F-3, decision D-06 (Peruvian defaults, **configurable per company, never fixed in code**;
  percentages to be confirmed with each customer's accountant).
- Migrations: **V64** (F-1: charge codes, match basis) and **V66** (F-3: tax breakdown, CPE, notes,
  detraction, retention, fiscal settings). Provisional; `PHASE_MAP.md` registers.

## Context

From the code at `a57af24` (`V46__freight_settlement.sql`, `com.ebim.tms.settlement`):

- `carrier_invoice`: `invoice_number`, `invoice_date`, `currency`, `total_amount numeric(14,2)` with
  **`ck_carrier_invoice_total_nonnegative (total_amount >= 0)`**, `uq_carrier_invoice_number (company_id,
  carrier_id, invoice_number)`. No tax breakdown, no document type, no series.
- `carrier_invoice_line`: `trip_id` (nullable), `description`, `quantity`, `unit_amount`, `line_amount`
  (no sign CHECK), no charge code.
- `FreightMatcher.match` (static) compares per trip the **sum** of lines with the trip's expected cost;
  a second line of the same trip on the same invoice is `DUPLICATE_INVOICE`. `SettlementService:326`
  creates every discrepancy with `invoice_line_id = null` although `FreightMatcher.InvoiceLine` carries
  the line id. `toleranceFor` (l.340) reads `tolerance_policy` (carrier, else company, else `Tolerance.NONE`),
  but **no endpoint writes `tolerance_policy`**: the seeded `settlement.tolerance:manage` guards nothing.
- `PayableExport.Format { JSON, CSV }`, `ck_payable_export_format` admits both, only
  `JsonPayableExportAdapter` exists.
- `tms.trip_cost_component` (V30/V39) holds the estimate/actual breakdown by `RateComponent` (12 values;
  `LOADING_METERS` joins with ADR-022).
- Carrier RUC is `carrier.tax_id_type = 'RUC'` + `tax_id_value` (V9, `uq_carrier_company_tax_id`).
- A grep for IGV, detracción, CPE, SUNAT, credit note, tax amount finds only comments.

## Decision - part 1: settlement hardening (F-1, phase 50, V64)

1. **Module boundary first.** `settlement` joins `ModuleBoundaryTest.BUSINESS_MODULES`.
2. **Tolerances.** `GET|POST|PUT /api/v1/settlement/tolerance-policies[/{id}]` and deactivate, guarded by
   the existing `settlement.tolerance:manage` (read with `settlement.invoice:read`). The V46 table and its
   partial unique indexes (one active per company, one active per carrier) are used as they are; no
   migration. Screen "Tolerancias" under Liquidación.
3. **Charge codes.** `carrier_invoice_line.charge_code` text NULL, CHECK in `FREIGHT` (all-in), the
   `RateComponent` names that a carrier bills (`BASE`, `DISTANCE`, `WEIGHT`, `VOLUME`, `PALLETS`,
   `LOADING_METERS`, `STOP_OFF`, `FUEL_SURCHARGE`, `WAITING_TIME`, `TOLL`, `OTHER_ACCESSORIAL`), and
   `PENALTY`. Null reads as `FREIGHT` (every V46 line).
4. **What it is compared against, said.** `freight_match.expected_basis` text (`ACTUAL` when
   `trip_cost.closed_at` is set, else `ESTIMATED`), shown on screen and in the export.
5. **Matching rule.**
   - The **trip total** still decides `MATCHED` / `DISCREPANCY`, with the tolerance (V46 semantics).
   - Lines of one trip with **different** charge codes are summed for that total; they are not duplicates.
     `DUPLICATE_INVOICE` is now: the same (trip, charge code) twice on one invoice, or the same trip and
     charge code on another non-rejected invoice of the carrier.
   - When the trip total is out of tolerance and the lines carry component codes, the discrepancies are
     raised **per component** (`LINE_AMOUNT`, expected = the trip cost component of that name; the
     adjustments `MINIMUM_ADJUSTMENT`/`MAXIMUM_ADJUSTMENT` are compared under `BASE`) with
     **`invoice_line_id` set**. Within tolerance, the per-component comparison is shown but opens nothing,
     so approval is not blocked by a difference the tolerance accepted.
   - `FreightMatchResult.Discrepancy` gains `lineId`; `SettlementService:326` passes it.
   - `PENALTY` lines are excluded from the expected-cost comparison (ADR part 2, item 6).
6. **CSV export.** `CsvPayableExportAdapter` (RFC 4180, UTF-8 without BOM, comma-separated, decimal
   point, ISO dates, one row per invoice line with header fields repeated), golden file test. The JSON and CSV
   carry the same fields; after V65/V66 they add GL accounts and tax columns.
7. **Settlement KPIs** in `KpiService` (existing `/reporting/kpis`, `monitoring.transport:read`) and
   `KpiReportView.settlement`: invoice cycle time (received -> approved, median and p90), % of invoices
   with a discrepancy, amount observed (sum of positive differences rejected or accepted) and saved, per
   carrier.

### Implementation notes - part 1 (phase 50, V64)

- `V64__settlement_charge_codes_and_match_basis.sql`: `carrier_invoice_line.charge_code` (+CHECK, index
  `(company_id, trip_id, charge_code)`), `freight_match.expected_basis` (`ACTUAL`/`ESTIMATED`/`MIXED`, plus
  `ck_freight_match_basis_needs_expected`), action `TOLERANCE_POLICY_CHANGED` and aggregate `TOLERANCE_POLICY`.
- `expected_basis` admits **`MIXED`** for an invoice whose shipments are partly closed and partly open (item 4 named
  two values; an invoice spans shipments). The per-line basis is `ACTUAL` or `ESTIMATED`.
- The **component breakdown is the estimate's**: TMS records an actual as one amount. A component line is compared with
  the estimate's component (`BASE` = `BASE` + `MINIMUM_ADJUSTMENT` + `MAXIMUM_ADJUSTMENT`; a component the estimate does
  not charge is a known 0); an all-in `FREIGHT` line with the shipment's figure (actual if closed) less the other lines'
  components. With no breakdown, component expectations are null and an out-of-tolerance shipment is raised once at
  shipment level.
- Per-line comparisons use the same tolerance; when the shipment is out but no line is, one shipment-level `LINE_AMOUNT`
  is raised so nothing out of tolerance passes without a row.
- An absolute tolerance applies only to invoices in its own currency (`TolerancePolicy.toleranceFor`); against another
  currency only the percentage applies.
- `PENALTY` lines are left out of the comparison and out of the compared header total already in phase 50 (the sign
  CHECK and the approval listing stay in phase 60).
- CSV export: `CsvPayableExportAdapter`, chosen by `POST .../export?format=CSV` (default JSON); the first export fixes
  the format. Golden file `src/test/resources/settlement/payable-export-golden.csv` (exempt from EOL normalisation).
- KPIs: `KpiReportView.settlement`, see `docs/domain/KPIS_REPORTING_V1.md` §12.

## Decision - part 2: Peruvian taxes and CPE (F-3, phase 60, V66)

1. **Fiscal settings per company.** `company_settings` gains nullable `igv_rate numeric(5,2)`,
   `detraction_enabled boolean`, `detraction_code text`, `detraction_rate numeric(5,2)`,
   `detraction_threshold_amount numeric(14,2)`, `retention_agent boolean`, `retention_rate numeric(5,2)`.
   A null resolves to the property default (`tms.finance.pe.igv-rate=18.00`,
   `detraction-code=027`, `detraction-rate=4.00`, `detraction-threshold-pen=400.00`,
   `retention-rate=3.00`, `retention-agent=false`, `detraction-enabled=true`) through
   `FiscalSettingsResolver`. No rate is a literal in Java or SQL.
2. **Breakdown on the document.** `carrier_invoice` gains `subtotal_amount numeric(14,2)`,
   `igv_rate numeric(5,2)`, `igv_amount numeric(14,2)`; CHECK `ck_carrier_invoice_tax_breakdown`: either
   all three null (pre-V66 rows and non-PEN documents without breakdown) or
   `abs(subtotal_amount + igv_amount - total_amount) <= 0.01`. The service **requires** the breakdown for
   `PEN` documents created after V66 and keeps it optional for other currencies (stored when it comes).
   **No FX.**
3. **CPE identity.** `document_type` text NOT NULL DEFAULT `'01'` CHECK IN (`01` factura, `07` nota de
   crédito, `08` nota de débito); `series` (`^(F[A-Z0-9]{3}|E001|[0-9]{4})$`), `correlative`
   (`^[0-9]{1,8}$`), `issuer_ruc` (`^(10|15|17|20)[0-9]{9}$`). Unique
   `uq_carrier_invoice_cpe (company_id, issuer_ruc, document_type, series, correlative) WHERE series IS NOT NULL`.
   `invoice_number` stays (it becomes `series-correlative` when a CPE identity is given). Validation in
   Java: RUC **check digit modulo 11** (weights 5,4,3,2,7,6,5,4,3,2; `11 - sum mod 11`, 10 -> 0, 11 -> 1),
   and `issuer_ruc` equals the carrier's RUC when the carrier has one (422 `issuer-ruc-mismatch`).
4. **No online SUNAT validation.** `CpeValidationPort` with the only implementation
   `DisabledCpeValidationAdapter`, which answers `EXTERNAL_DEPENDENCY` - **never "valid"**. Shown as such.
5. **Credit and debit notes.** A note is its own `carrier_invoice` row (`07`/`08`) with
   `referenced_invoice_id` (composite FK, same carrier, CHECK: required iff type is 07/08). **Amounts are
   stored positive, as SUNAT issues them; the sign comes from the type** (`effectiveSign()` = -1 for 07).
   Therefore `ck_carrier_invoice_total_nonnegative` stays as it is: nothing requires a negative total.
   (The phase-60 prompt allowed dropping it "only where a credit note requires it"; it does not.)
   A note goes through the same receive -> match -> four-eyes approval; it is matched against the
   referenced invoice's lines, and the referenced invoice's **net** (`total - approved 07 + approved 08`)
   is what payables (ADR-023) and the export use. A 07 on an already `EXPORTED` invoice is exported as its
   own document.
6. **Penalties and discounts.** A line with `charge_code = PENALTY` and **negative** `line_amount`, with
   `penalty_reason` (required) and an optional `penalty_reference_type` (`TRIP_EXCEPTION`,
   `DELIVERY_FAILED`, `LATE_ARRIVAL`, `OTHER`) + `penalty_reference_id`. CHECK
   `ck_carrier_invoice_line_sign`: `line_amount < 0` only when `charge_code = 'PENALTY'`. A penalty is
   excluded from the expected-cost comparison and must be accepted by the approver (it is listed as its
   own item in the approval view). The penalty is the shipper's deduction recorded on the carrier's
   document; TMS does not issue documents.
7. **Detraction (SPOT).** On the invoice: `detraction_code`, `detraction_rate`, `detraction_amount`,
   `detraction_constancy_number`, `detraction_constancy_date`. Computed when the company has detraction
   enabled, the currency is PEN and `total_amount > threshold` (strictly above, as the SPOT rule reads; the
   comparison operator is a constant named and tested), `detraction_amount = round(total x rate, 0)`
   (SUNAT deposits are in whole soles; the rounding mode is HALF_UP and named). The constancy number/date
   is informed by the ERP (ADR-023 payment message) or typed by a person; **TMS computes, never pays**.
8. **Retention.** When the company is a retention agent and **no detraction applies** to the document (the
   two are exclusive), `retention_rate` and `retention_amount = round(total x rate, 2)` are stored. Whether
   a carrier is a "buen contribuyente" (exempt) is not modelled: a person can set the amount to zero with a
   reason (audited).
9. **Accrual consumption.** An approved document consumes the accrual by its **subtotal** (IGV is fiscal
   credit, not expense). Pre-V66 documents without breakdown consume by total.
10. **Export.** JSON and CSV gain `documentType`, `series`, `correlative`, `issuerRuc`, `subtotal`,
    `igvRate`, `igvAmount`, `detraction*`, `retention*`, `referencedDocument`, and the GL accounts of
    ADR-023 (`FREIGHT_EXPENSE`, `IGV_CREDIT`, `DETRACCION_PAYABLE`, `RETENCION_PAYABLE`, `CARRIER_PAYABLE`).
11. **Audit.** Actions `CREDIT_NOTE_RECEIVED`, `DEBIT_NOTE_RECEIVED`, `PENALTY_ACCEPTED`,
    `RETENTION_OVERRIDDEN`, `TOLERANCE_POLICY_CHANGED`.

## Consequences

- Every V46 invoice remains valid: new columns are nullable or defaulted (`document_type '01'`), and the
  matcher reads null `charge_code` as `FREIGHT`.
- The rounding and threshold rules are named constants with tests, because they are where an accountant
  will disagree first (D-06 "verify with the customer's accountant").

## Not done (named)

Online SUNAT validation (`CpeValidationPort` stays disabled - EXTERNAL_DEPENDENCY), issuing any electronic
document (TMS receives; GRE transportista emission is roadmap item 3), FX, "buen contribuyente" padrón,
reconciliation of detraction constancies against the Banco de la Nación.
