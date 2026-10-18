# ADR-027 - Tax regimes per country: VAT, fiscal documents and withholdings of received carrier documents

- Status: Accepted (design and implementation, track `tms/tax-intl`)
- Date: 2026-10-18
- Supersedes: **ADR-024 part 2** where the two conflict (the Peruvian columns, codes, defaults and names of V66 and
  V67). ADR-024 part 1 (settlement hardening, V64) and every Peruvian **behaviour** of part 2 stand unchanged.
- Relates to: ADR-023 (accruals and payables), ADR-024, decision D-06 (defaults are configuration, never constants),
  D-01 (TMS records, moves no money), plan `03_FINANZAS_FLETES.md` F-3.
- Migrations: **V65, V66 and V67 rewritten in place** (never applied anywhere; V1-V58 applied and immutable, V62 and
  V68 owned by another track and untouched; no new migration number). V66 is renamed
  `V66__fiscal_documents_tax_regimes_and_penalties.sql`.
- First customer: **PLAPASA** (plastics, Guayaquil, Ecuador, US dollars).

## Context

Phases 60 and 65 (commits `c0c2e44`, `7b46863`, `2cfb855`) shipped Peru only, hard-wired: IGV 18 %, SUNAT document
codes `01`/`07`/`08`, `series` + `correlative`, an 11-digit RUC, a detraction slot and a retention slot on the invoice
(`detraction_*`, `retention_*` columns), `TaxCalculator.FISCAL_CURRENCY = "PEN"`, `tms.finance.pe.*` read by `@Value`,
GL concepts `IGV_CREDIT`/`DETRACCION_PAYABLE`/`RETENCION_PAYABLE`, `PayableBalance = net - detraction - retention`, an
integration contract with `documentType ^(01|07|08)$` and a tax id of at most 11 characters, and a frontend that said
"IGV", "PEN" and "SUNAT". `company_settings.default_country` (V34, default `PE`) existed and no fiscal code read it.

The first customer is in Ecuador, where the VAT is called IVA, the general rate is 15 %, **domestic land freight is
taxed at 0 %**, documents are numbered `establecimiento-puntoEmisión-secuencial` and coded by the SRI (`01`/`04`/`05`),
the RUC has 13 digits and three check-digit rules, and a document can carry **two** withholdings at once (income tax on
the subtotal and, for a VAT-withholding agent, a share of the VAT). The Peruvian model could not represent that.

**Scope boundary (business owner, binding):** TMS **receives** a carrier's document - from a screen or the integration
API - and validates its **shape** (number format, tax-id check digit, breakdown, allowed rate, uniqueness). It does not
issue, sign or send anything to the SRI or SUNAT, and it does not validate a document online. Electronic invoicing is out
of scope; `FiscalDocumentValidationPort` stays disabled and answers `EXTERNAL_DEPENDENCY`, never "valid".

## Decision

### 1. A regime per fiscal country, in the pure domain

`com.ebim.tms.settlement.domain.tax`:

- `TaxRegime` (interface) with `pe.PeruTaxRegime`, `ec.EcuadorTaxRegime`, `generic.GenericTaxRegime`, selected by
  `TaxRegimes.forCountry(country)`: PE and EC have their own; **every other country is generic**. A regime is the
  vocabulary only - VAT label, fiscal currency, the country's code of each `DocumentKind`, the document-number format
  and its normaliser, the tax-id rule and label, the carrier tax-id types comparable to an issuer, and its withholding
  kinds in computing order. **No number lives in a regime.**
- `DocumentKind` (`INVOICE`, `CREDIT_NOTE`, `DEBIT_NOTE`) replaces `DocumentType`: the kind is internal; the country code
  is the regime's and is stored beside it.
- `WithholdingKind`: `PE_DETRACTION`, `PE_RETENTION`, `EC_INCOME_TAX_WITHHOLDING`, `EC_VAT_WITHHOLDING`. What is a **rule
  of the kind** is on the enum - country, base (`TOTAL`/`SUBTOTAL`/`VAT`), scale and rounding (HALF_UP), whether a
  person may override it, which kind excludes it (`PE_RETENTION` is excluded by `PE_DETRACTION`), the threshold
  comparison (strictly above). What is a **number** (enabled, code, rate, threshold) is configuration.
- `TaxRules` (resolved per company): regime, country, VAT rate, freight VAT rate, allowed VAT rates, one `WithholdingRule`
  per kind. `TaxCalculator.compute(rules, kind, currency, subtotal, tax, total)` returns a **list** of
  `ComputedWithholding`: only an `INVOICE`, only in the regime's fiscal currency (no FX), kinds in order, an excluded kind
  skipped, a zero or missing base yields nothing.
- Tax-id checks live in `com.ebim.tms.shared.tax` (`TaxIdRule` PE_RUC / EC_RUC / FREE_TEXT, `PeruRuc`, `EcuadorRuc`),
  because `fleet` and `integration` may need the same answer and business modules do not depend on each other.

| | Peru | Ecuador | Generic |
|---|---|---|---|
| VAT label | IGV | IVA | IVA/VAT |
| Fiscal currency | PEN | USD | none enforced |
| INVOICE / CREDIT_NOTE / DEBIT_NOTE | 01 / 07 / 08 | 01 / 04 / 05 (SRI) | no codes |
| Number | `F001-123` (series F+3, E001 or 4 digits; correlative 1-8 digits, stored without leading zeros) | `001-001-000000123` (3+3+1..9 digits, establishment/point/sequential not zero, sequential stored with 9 digits) | free text, up to 60 |
| Tax id | RUC, 11 digits, prefix 10/15/17/20, modulo 11 (unchanged) | RUC, 13 digits (rule below) | letters, digits, hyphens, up to 20 |
| Withholdings | detraction (SPOT) **or** retention | income-tax **and/or** VAT withholding | none |
| Breakdown required | in PEN | in USD | never |

**Ecuador's RUC:** province `01`-`24` or `30`; third digit `0`-`5` natural person (the cédula's modulo 10 over digits
1-9 with coefficients 2,1,2,1,2,1,2,1,2, a product above 9 loses 9, check at digit 10), `6` public entity (modulo 11 over
digits 1-8 with weights 3,2,7,6,5,4,3,2, check at digit 9, then a 4-digit establishment starting with 0), `9` private
company (modulo 11 over digits 1-9 with weights 4,3,2,7,6,5,4,3,2, check at digit 10); modulo 11 reads 11 as 0 and 10 as
impossible; `7` and `8` are no type; the last three digits (the establishment) are never `000`. The tests build their
valid examples by computing each check digit.

### 2. Every number is configuration, overridable per company (D-06)

- Deployment defaults: `tms.finance.tax.<pe|ec|generic>.*`, bound by `@ConfigurationProperties`
  (`TaxSettingsProperties`, replacing the `@Value` `tms.finance.pe.*`). The Peruvian environment variables of phase 60
  (`TMS_PE_IGV_RATE`, `TMS_PE_DETRACTION_*`, `TMS_PE_RETENTION_*`) keep working under the new keys.
- Per company: `company_settings.fiscal_country` (null = `default_country`), `vat_rate`, `freight_vat_rate`, and
  `company_tax_withholding_setting` (one row per kind; `enabled`, `code`, `rate`, `threshold_amount`, each nullable).
  **A small table, not columns**, because the withholdings are the regime's list and grow with each country: columns per
  kind would turn every new country into a migration of `company_settings`.
- `FiscalSettingsResolver` resolves the regime from the fiscal country and each number from the company's value, else the
  property. The allowed VAT rates are the regime's configured list plus the company's own general and freight rates.
- `GET /settlement/fiscal-settings` (`settlement.invoice:read`) returns `TaxRegimeView`: regime, country, currency, VAT
  label, rates and allowed rates, whether a breakdown is required, the document kinds with code and label, the number
  pattern/placeholder/hint, the tax-id label/rule/length, `electronicValidation`, the resolved withholding rules with
  their kind rules, and what the company stored. The frontend labels, validates and previews from it and carries no
  rate, code or format of its own.
- `PUT /settlement/fiscal-settings` (new permission **`settlement.tax:manage`**, capability `TAX_REGIME_MANAGE`,
  administrators; seeded in V66) replaces the company's overrides whole. A withholding kind of another country's regime
  is a 400. Audited as **`UPDATE` on the `COMPANY` aggregate** with `section = FISCAL_REGIME` and both versions: V68
  restates the audit vocabulary after V66 and is another track's, so a dedicated action would be dropped by it (a
  dedicated `FISCAL_REGIME_CHANGED` is a one-line follow-up for whoever owns the next migration). The write goes through
  `FiscalSettingsPort` to `iam`'s adapter - the one write a settings port allows, deliberately: the validation is
  settlement's, the table is iam's, and the adapter writes only the fiscal columns and the per-kind rows.
- A document **freezes the regime it was received under** (`carrier_invoice.fiscal_country`): changing the company's
  country later relabels, revalidates and recomputes nothing already received.

### 3. Ecuador's defaults - all to VERIFY WITH THE CUSTOMER'S ACCOUNTANT

| Default | Value | Reference |
|---|---|---|
| General IVA | 15 % | Ley Orgánica para Enfrentar el Conflicto Armado Interno (2024): 15 % since **2024-04-01** (was 12 %) |
| Freight IVA | **0 %** | LRTI art. 56: domestic land transport of goods is taxed at 0 % |
| Allowed IVA rates | 0, 5, 15 | the rates in force (5 % applies to some goods/services) |
| Income-tax withholding (retención en la fuente de IR) | **on**, code **310** "transporte privado de pasajeros o servicio público o privado de carga", **1 %** of the subtotal | **SRI Resolution NAC-DGERCGC26-00000009 (in force since 2026-03-01) replaced the withholding table: the 1 % must be confirmed** |
| VAT withholding (retención de IVA) | **off** (only a designated VAT-withholding agent withholds), **70 %** of the VAT for services | NAC-DGERCGC20-00000061: 30 % goods, 70 % services, 100 % in specific cases |

Peru keeps exactly today's behaviour: IGV 18 % (allowed 18 and 0), freight previewed at 18 %, detraction code 027 at 4 %
on PEN totals **strictly above** S/ 400 in whole soles, retention 3 % to the cent only for a retention agent and never
where detraction applies. All of them were already "verify with the accountant" (ADR-024).

A carrier exempt from a withholding (Ecuador's RIMPE negocio popular, Peru's "buen contribuyente") is set to zero by hand
with a reason (`withholdingOverrides`, audited `RETENTION_OVERRIDDEN` - "retención" is what both countries call a
withholding - with the kind). Peru's detraction is not overridable.

### 4. Schema (V65, V66, V67 rewritten in place)

- **V65** `gl_account_mapping`: concepts `FREIGHT_EXPENSE`, `FREIGHT_ACCRUAL`, `VAT_CREDIT`, `WITHHOLDING_PAYABLE`,
  `CARRIER_PAYABLE`, plus `withholding_kind` (only on `WITHHOLDING_PAYABLE`; null = every kind without its own mapping),
  in the active-scope unique index. The resolver tries the kind first at each scope, then the generic one.
- **V66** `company_settings.fiscal_country | vat_rate | freight_vat_rate`; `company_tax_withholding_setting` (RLS,
  `p_tenant_company_scope`, full grant - it is replaced by a PUT); on `carrier_invoice`: `fiscal_country` (snapshot),
  `document_kind`, `fiscal_document_code`, `fiscal_document_number` (normalised), `issuer_tax_id`, `subtotal_amount`,
  `tax_rate`, `tax_amount`, `referenced_invoice_id`; unique `uq_carrier_invoice_fiscal_document (company_id,
  fiscal_country, issuer_tax_id, document_kind, fiscal_document_number)`; `carrier_invoice_withholding` (kind, code,
  base, base_amount, rate, computed_amount, amount, override_reason, certificate_number, certificate_date; one per
  kind per document; RLS; `SELECT, INSERT, UPDATE` for `tms_app`, no `DELETE`; a trigger freezes every figure and lets
  only the certificate change). **The CHECKs state only what holds in every country** (shapes, the breakdown adds up
  within 0.01, amounts not negative, a withholding never above its base, an override carries a reason, a certificate
  date has its number, a note references an invoice); which number, code or tax id is valid is the regime's question,
  answered in Java where the refusal can name the digit. Kind lists are CHECKed like every vocabulary of this schema
  (`PersistedEnumConstraintTest`); a new country restates them.
- **V67** `carrier_payment` and `carrier_payment_request`: the four detraction/retention columns become
  `reported_withholdings` - the report kept whole, JSON in a text column (V18/V35/V53's reasoning), never summed, CHECKed
  to be an array. The certificate goes to the document's withholding. Carrier portal policies cover
  `carrier_invoice_withholding` like the lines.

### 5. Application and contracts

- Receive: the regime of the company is read once; the fiscal number and the issuer tax id come together or not at all
  (`documentNumber`, `issuerTaxId`), are validated and normalised by the regime, and the issuer must be the carrier's own
  id when the carrier's id type is the regime's (422 **`issuer-tax-id-mismatch`**, was `issuer-ruc-mismatch`). The
  stated VAT rate must be one the regime allows. `invoice_number` becomes the normalised number (a note's is prefixed by
  its code: `07-F001-123`, `04-001-001-000000123`).
- Matching and accrual consumption stay **by subtotal**. Notes still compute no withholding and consume nothing.
- `PayableBalance = net - sum of withholdings - paid`; the payables report sums `carrier_invoice_withholding`.
- Views: `FiscalView` (fiscal country, regime, VAT label, kind and code, number, issuer, breakdown, `withholdings[]`,
  total, notes, `electronicValidation`), `PayableView` and the carrier portal carry `withholdings[]`; payment views carry
  the reported withholdings.
- Integration `PayablePaymentV1`: `invoice.{carrierTaxId (<= 20), documentType (country code or kind), documentNumber}`,
  `withholdings[{kind, amount, certificateNumber, certificateDate}]` (contract `INBOUND_API_V1.md` §14). Never released:
  V67 is unapplied, so the Peruvian `series`/`correlative`/`detraction` fields were removed rather than kept as aliases.
- Export JSON and CSV (golden file updated deliberately): `fiscalCountry, documentKind, fiscalDocumentCode,
  effectiveSign, documentNumber, issuerTaxId, subtotalAmount, taxRate, taxAmount, withholdings, withholdingTotalAmount,
  referenced document, netAmount` and the GL accounts of `FREIGHT_EXPENSE`, `VAT_CREDIT`, `WITHHOLDING_PAYABLE` (every
  kind), `CARRIER_PAYABLE`; each withholding carries its own account. In CSV the list is one cell: entries separated by
  `;`, fields by `|` in the order `kind|code|base|baseAmount|rate|amount|certificateNumber|certificateDate|glAccount`.
- `CpeValidationPort` is renamed `FiscalDocumentValidationPort` (`DisabledFiscalDocumentValidationAdapter`), still
  `EXTERNAL_DEPENDENCY`, never "valid".

### 6. Negative invoice lines (review finding on V66)

`ck_carrier_invoice_line_sign` (negative only on a `PENALTY` line, with its reason) would have aborted V66 on any
database holding a negative line written under V46, which accepted them (a carrier's discount keyed as its own line). It
is now **`NOT VALID`**: existing rows keep what they say, every row written from V66 on is checked. **Semantics:** a
negative line is the shipper's deduction (`PENALTY`); a carrier's discount is not keyed as a negative line, because the
electronic documents of Peru and Ecuador carry a discount as an attribute of the line and print the line net - which is
what `line_amount` records. No `DISCOUNT` charge code is introduced: it would have needed its own place in the matcher's
duplicate and component rules for a case the documents do not present.

### 7. Frontend

Invoice form, workspace, payables, carrier portal and GL mapping read the `TaxRegimeView`: labels, document codes,
number placeholder and pattern, tax-id label and check (`fiscalRules.ts` mirrors PE and EC RUC rules), allowed rates,
VAT preview with the freight rate, withholding preview with the server's rule. `fmtMoney` has no default currency (the
document's is passed). Company settings has a "Régimen fiscal" card (country, VAT and freight VAT, each withholding),
editable with `TAX_REGIME_MANAGE`. Spanish strings with English entries (`lib/i18n.taxRegimes.ts`).

## Consequences

- A company is taxed by the country it declares, with Peru unchanged and Ecuador complete for received freight
  documents; any other country works with no withholdings and no enforced currency until a regime is written for it.
- Adding a country = a `TaxRegime` implementation, its `WithholdingKind`s, a `tms.finance.tax.<country>` block and a
  migration restating the kind lists. No schema change to the invoice.
- `PayablePaymentV1` changed shape before its first release.

## Not done (named)

Emitting, signing or sending any electronic document or withholding certificate to the SRI or SUNAT (out of scope by the
business owner); online validation of a received document (`EXTERNAL_DEPENDENCY`); FX; Ecuador's per-carrier regime
(RIMPE, contribuyente especial) as master data - today an override with a reason; withholding thresholds by cumulative
amounts per supplier; a dedicated audit action for fiscal-regime changes (see section 2); a regime for Colombia, Mexico or
any third country (they use the generic one).
