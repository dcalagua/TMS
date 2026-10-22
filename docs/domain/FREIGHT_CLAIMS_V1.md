# Freight claims and the incident centre V1

**Migration V71 · evaluation item 5 · roadmap item 7**

A claim is what the shipper asks a carrier for when a shipment went wrong: chairs that arrived broken,
crates that never arrived, a delivery a day late. Until V71 TMS recorded the fact (a PARTIAL delivery,
a trip exception) and nothing about what was claimed, who agreed and whether it was ever recovered;
that lived in a spreadsheet.

TMS **moves no money** (D-01). A claim is recovered through concepts settlement already has: a
`PENALTY` line on the carrier's invoice, or a `CREDIT_NOTE` the carrier sends (V66). TMS issues neither.

## 1. The model

| Table | What it is |
|---|---|
| `tms.freight_claim` | One claim against the carrier of one trip: number `RC-000001`, type, status, incident date, description, claimed amount and currency; once decided, the resolution, accepted amount, recovery method and reason; once closed, the carrier document that recovered it. |
| `tms.freight_claim_line` | Optional parts of a claim: an order line, a quantity, an amount. With lines, the claimed amount is their sum. Rewritten whole while the claim is `OPEN`. |
| `tms.freight_claim_evidence` | Photos and documents, metadata only; bytes behind `EvidenceStoragePort` (ADR-006 / ADR-020). Append-only. |

A claim always names a **trip**, and its carrier is **the trip's** - never sent by the client, so a
claim cannot be raised against a carrier that did not run the shipment. An own-fleet trip (no carrier)
and a `DRAFT` trip cannot be claimed. It may also name the order, the `order_delivery` it came from
and the `trip_exception` it came from; all are composite FKs `(id, company_id)` (V71 adds
`uq_trip_exception_id_company`).

Types: `DAMAGE`, `SHORTAGE`, `LOSS`, `DELAY`, `WRONG_DELIVERY`, `OTHER`.

## 2. Lifecycle

```
OPEN -> UNDER_REVIEW -> ACCEPTED | PARTIALLY_ACCEPTED | REJECTED -> CLOSED
OPEN | UNDER_REVIEW -> CANCELLED (with a reason)
```

- `OPEN`: editable (terms and lines), evidence can be added. **Submit** freezes it.
- `UNDER_REVIEW`: somebody with `settlement.claim:decide` decides.
  - **Accept** takes the whole claimed amount and a recovery method.
  - **Partial agreement** accepts more than zero and less than claimed, and says why.
  - **Reject** says why and recovers nothing.
- **Four eyes**: whoever opened the claim cannot accept it, in whole or in part - the rule settlement
  applies to approving an invoice (V46) and confirming a payment (V67). It is held in `FreightClaim`
  and again in the database (`ck_freight_claim_resolution_terms`: `decided_by <> opened_by`).
  Rejecting moves nothing, so the opener may reject (the asymmetry of `CarrierPaymentRequest#reject`).
- `CLOSED`: an accepted claim is closed **by the document that recovered it**
  (`settlement_invoice_id`, composite FK to the carrier's invoice): for `PENALTY`, an invoice of the
  carrier carrying a penalty line that references the claim; for `CREDIT_NOTE`, a credit note of the
  carrier in the claim's currency. Neither may be rejected. A rejected claim closes on nothing. The
  `resolution` column survives the close.

`resolution`, amounts and actors are tied together by CHECKs (`ck_freight_claim_lifecycle`,
`_resolution_terms`, `_closure`, `_cancellation`), so a state the code did not design is a rolled-back
request, never a row.

## 3. From the facts to a claim

`GET /settlement/claims/draft?orderDeliveryId= | tripExceptionId= | tripId=` reads, through
`TripIncidentLookupPort` (answered by `planning`), what was recorded and writes nothing: trip, carrier,
order, delivery result and notes, the order lines with refused quantities (V45), exception type and
notes, a suggested type (PARTIAL -> SHORTAGE, REJECTED -> DAMAGE, a delay exception -> DELAY) and the
claims already open on the trip. The screen prefills the form; the person adds what TMS does not know -
how much a chair is worth. The trip workspace offers «Abrir reclamo» on PARTIAL / REJECTED / FAILED
deliveries and on exceptions; the incident centre offers it on exceptions.

## 4. Recovery through the carrier's invoice

- `GET /settlement/claims/penalty-suggestions?carrierId&currency` (`settlement.invoice:manage`) lists
  the accepted claims recovered by `PENALTY`, not closed, in that currency, not already deducted on a
  live (non-rejected) document - each as the PENALTY line it would become.
- The invoice form shows them; **«Agregar penalidad»** adds the line (`penaltyReferenceType =
  FREIGHT_CLAIM`, `penaltyReferenceId = claim`). Nothing is applied until the invoice is saved.
- `SettlementService.receive` checks every such line (`FreightClaimService.requireRecoverablePenalties`,
  claim row locked): a PENALTY line on an invoice, the claim's carrier and currency, the claim's trip,
  no more than accepted, and once. The penalty then follows the V66 path (a `PENALTY` discrepancy the
  approver accepts).

## 5. The incident centre

`GET /incidents` (`settlement.claim:read` **and** `planning.trip:read`) lists open trip exceptions and
claims together, filtered by status group (`OPEN` / `CLOSED` / `ALL`), kind, carrier, type and period,
with the age in the company's calendar days and ageing buckets (0-7, 8-30, 31-60, >60). Oldest open
first. A merged list is capped (default 200, max 500 per source) and says `truncated` rather than paging
a union. Resolving an exception stays planning's endpoint.

## 6. Security

- Permissions: `settlement.claim:read` (four office roles), `:manage` (administrators and PLANNER - the
  dispatcher opens claims), `:decide` (the two administrators). Capabilities `CLAIMS_VIEW`,
  `CLAIMS_MANAGE`, `CLAIMS_DECIDE`.
- RLS: `p_tenant_company_scope` on the three tables and a RESTRICTIVE `p_carrier_scope` that hides
  them from every carrier transaction - the portal does not see claims.
- Grants: no `DELETE` on the claim; evidence is `SELECT, INSERT` only.
- Audit: `CLAIM_OPENED`, `CLAIM_UPDATED`, `CLAIM_SUBMITTED`, `CLAIM_ACCEPTED`,
  `CLAIM_PARTIALLY_ACCEPTED`, `CLAIM_REJECTED`, `CLAIM_CLOSED`, `CLAIM_CANCELLED`,
  `CLAIM_EVIDENCE_ADDED` on aggregate `FREIGHT_CLAIM`.

## 7. Not in V1

- The carrier portal does not see or answer claims.
- No automatic close when the penalty's invoice is approved: closing is a person's act naming the document.
- No claim value from the order (TMS has no goods value); the amount is typed.
- The carrier scorecard does not yet count claims (built separately).
