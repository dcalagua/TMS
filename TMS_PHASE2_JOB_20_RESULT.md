# Phase 2 — JOB 20: Freight Audit & Settlement V1

```
RESULT=      PASS
STOP_CHAIN=  false

STARTED_AT=   2026-08-28 10:37 America/Lima
COMPLETED_AT= 2026-08-28 11:25 America/Lima
```

## SETTLEMENT_CAPABILITY_PROOF

```
CARRIER_INVOICE=     YES
MATCHING=            YES
TOLERANCE=           YES
DISCREPANCY=         YES
APPROVAL=            YES
EXPORT=              YES
UI=                  YES
TENANT_TESTS=        YES
CONCURRENCY_TESTS=   YES
```

| Pillar | Evidence |
|---|---|
| CarrierInvoice | `tms.carrier_invoice` + `carrier_invoice_line`; entity, repository, `POST /settlement/invoices` |
| Matching | `FreightMatcher` (pure function, 15 tests); `tms.freight_match`; `POST /{id}/match` |
| Tolerance | `tms.tolerance_policy`; `Tolerance` (8 tests) - **both worked examples from the brief pass** |
| Discrepancy | `tms.freight_discrepancy`, six typed reasons, resolve endpoint |
| Approval | `tms.settlement_approval`, append-only, `decided_by` NOT NULL, own permission |
| Export | `tms.payable_export`, `PayableExportPort` + JSON adapter, idempotent by unique constraint |
| UI | `/settlement` list + `InvoiceWorkspaceDrawer`, in the menu and in the E2E smoke |
| Tenant tests | 3 (cross-company read/match/approve/export, foreign carrier, foreign trip) |
| Concurrency tests | 2 (two exports → one obligation; two approvals → one decision) |

**None of this existed before this job.** Verified by inspection at the start: no `CarrierInvoice`, no
invoice table, no matching, no tolerance, no discrepancy, no approval, no export anywhere.

## BASELINE

Backend 1712 / 0 / 0 · Flyway V1–V45 · next free **V46**.

## DOMAIN_DECISIONS

**1. The boundary is the design: TMS validates, ERP pays.** No ledger, no payment, no bank detail,
no accounting period. `/export` produces an artifact and records that it was handed over. V30 already
said this on `trip_cost.actual_reference`; V46 makes the invoice a document TMS can reason about
rather than a free-text reference.

**2. Nothing duplicates `trip_cost`.** V30 already holds both figures matching needs — estimated (with
the winning card snapshotted) and actual. Settlement reads them through `TripCostLookupPort` and
**never writes back**. Two owners of "what this shipment cost" is precisely how two numbers come to
disagree, and V30's close/reopen already governs when that figure may change.

**3. Unknown is never zero** — carried from V45 into the money. A shipment nobody estimated has no
expected figure, and reading it as `0.00` would report the whole invoice as an overcharge and send an
auditor to argue with a carrier who did nothing wrong. So:
`MatchStatus.UNMATCHABLE` exists as a third value, `expected_amount` is nullable, and
`ck_freight_match_unknown_is_not_matched` refuses to store an unknown as MATCHED.

**4. No path from a discrepancy to payable skips a person.** Four layers, deliberately:
the transition table (`DISCREPANCY` cannot reach `APPROVED`), `requireNoOpenDiscrepancies`,
`settlement_approval.decided_by` NOT NULL, and `requireAppUserId` refusing machines. **An unattended
approval is not merely forbidden — it cannot be represented.** Debt D4's refusal applied where it
matters most.

**5. Six permissions, not one.** Whoever keys an invoice must not be able to approve their own — the
oldest control failure in accounts payable. PLANNER gets `read` and `match`; only administrators get
`approve` and `export`.

**6. Either tolerance bound, not both.** 3% of a 40-unit invoice is pennies, so without an absolute
floor every rounding difference becomes a queue nobody reads; a flat bound on a 40,000-unit invoice
is noise. `Tolerance.NONE` is the default, because a company that has not said what it will accept
has not authorised anything.

**7. An undercharge is judged like an overcharge.** A carrier billing *less* than agreed is as much a
sign the two systems disagree, and an audit that only looked upward would miss every case where TMS
is the one that is wrong.

## MIGRATIONS

```
V46__freight_settlement.sql
```

Seven tables, RLS + tenant policy + grants on each, six permissions seeded with role grants, five
audit actions and one aggregate type.

## BACKEND / FRONTEND / DATABASE / SECURITY

New `settlement` module (domain / application / infrastructure / api); `TripCostLookupPort` answered
by `rates`, `TripSettlementLookupPort` answered by `planning`. Frontend: `settlementApi.ts`,
`SettlementPage`, `InvoiceWorkspaceDrawer`, nav entry, route, enum labels, status tones.

**Approval and export carry no UPDATE or DELETE grant** — a record of a decision that can be edited is
not a record, and an export that can be deleted is an obligation somebody can make disappear.

## TENANT_TESTS

Cross-company read, match, approve and export all 404. An invoice cannot name another company's
carrier (400). A line cannot bill another company's shipment — refused at insert by
`fk_carrier_invoice_line_trip_company`.

## CONCURRENCY_TESTS

Two simultaneous exports → **exactly one** `payable_export` row. Two simultaneous approvals →
**exactly one** `APPROVED` row.

## TESTS_FOCUSED

`FreightMatcherTest` (15) · `ToleranceTest` (8) · `InvoiceStatusTest` (8) ·
`SettlementApiIntegrationTest` (13) · `settlement.test.ts` (6)

```
BACKEND_CLEAN_PASS=  1756
BACKEND_CLEAN_FAIL=  0
FRONTEND_PASS=       107
FRONTEND_FAIL=       0
E2E_PASS=            35
E2E_FAIL=            0
E2E_SKIPPED=         7
ACCESSIBILITY=       not addressed (JOB 26)
PERFORMANCE=         not addressed (JOB 25)
RETRIES=             0
DEFECTS_FOUND=       6
DEFECTS_FIXED=       6
```

## DEFECTS

**1. Two approval rows for one expenditure.** `twoApprovalsOneDecision` found it.
`transitionTo` returns silently when already in the target state — right for a transition, wrong for
what follows it, because the approval row was still inserted. **One expenditure authorised twice is
exactly what an approval record exists to make impossible.** Approve and reject are now no-ops when
the decision has been taken.

**2. I wrote V46's audit aggregate list from memory.** It omitted three values the enum has
(`MASTER_DATA_IMPORT_BATCH`, `ORDER_IMPORT_BATCH`, `SHIPMENT`) and invented several it does not.
`AuditVocabularyMigrationTest` caught it. The list is now **generated from the enum**, not
transcribed.

**3. Jackson 2 import** where this project uses Jackson 3 (`tools.jackson`). Compiled; failed at
context startup.

**4–6. Three fixture defects, each an existing invariant my seed data ignored:** a trip with a
carrier needs a vehicle; an estimate needs the card that produced it; a card that charges nothing is
not an agreement. **The constraints were right every time.**

**A non-defect worth recording:** `foreignTripIsUnmatched` was written expecting a foreign trip to be
accepted and then matched as UNMATCHABLE. It is refused at *insert* instead — the isolation is
stronger than the test assumed, which is the right direction to be surprised in. The test now
asserts the stronger truth, and the service refuses first with a readable sentence.

**A process note:** one `./mvnw` in this job ran from a subdirectory and printed nothing, which I
briefly read as success. Re-run from the module root. The same "narrower gate certifies nothing"
lesson, in a new disguise.

## OPEN_DEBTS

```
D1 RESOLVED · D2 RESOLVED · D3 RESOLVED · D4 DEFERRED_WITH_REASON (reinforced here)
D5 OPEN → JOB 21 · D6 OPEN → JOB 22 · D7 RESOLVED · D8 RESOLVED · D9 OPEN → JOB 26
```

**Cost allocation is deliberately not built.** The brief allows it now that V45 supplies delivered
quantity, and it would have been the wrong thing to add: allocating an invoice across orders needs a
strategy per company, and the honest V1 is to surface the figures rather than distribute them on a
rule nobody chose. Recorded as **D10**.

## FILES_CHANGED

```
A  db/migration/V46__freight_settlement.sql
A  settlement/{domain×13, application×9, infrastructure×6, api×1}
A  shared/reference/{TripCostLookupPort, TripSettlementLookupPort}
A  rates/infrastructure/TripCostLookupAdapter, planning/infrastructure/TripSettlementLookupAdapter
A  4 test classes; A frontend settlementApi.ts, SettlementPage, InvoiceWorkspaceDrawer, settlement.test.ts
M  Permission, Capability, AuditAction, AuditAggregateType, TripCostRepository,
   SchemaExposureIntegrationTest, TenancyConstraintIntegrationTest, enums.ts, statusTones.ts,
   navConfig.tsx, lazyRoutes.tsx, App.tsx, e2e/support/modules.ts
```

```
NEXT_JOB= 21 — Work Assignments & Resource Sequencing (D5). Next migration V47.
```
