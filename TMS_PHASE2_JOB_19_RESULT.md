# Phase 2 — JOB 19: Delivered Quantity V1

```
RESULT=      PASS
STOP_CHAIN=  false

STARTED_AT=   2026-08-28 09:47 America/Lima
COMPLETED_AT= 2026-08-28 10:09 America/Lima
```

## OBJECTIVE

Close **D3** — the debt carried since V28 and formally evaluated in Phase 1 JOB 10. Delivery
recorded an *outcome* and no amount.

## BASELINE

Backend 1688 / 0 / 0 · Flyway V1–V44 · next free **V45**.

## DOMAIN_DECISIONS

### 1. Quantities land in two places, because two different questions are being asked

**The summable question** — *how much of this shipment moved* — is asked by the V37 allocation
ledger, by cost allocation and by the order lifecycle. It is answered in the three measures a
vehicle is constrained by. `SHIP_UNITS_AND_ALLOCATION_V1.md` states why those are the only summable
ones: adding 40 boxes to 3 drums gives a number that means nothing.

**The operational question** — *which product did the customer refuse* — cannot be answered in kilos
at all. It needs the line and the line's own unit.

So the measures go on `order_delivery`, where the V37 ceiling can be enforced against them, and the
per-product truth goes in `order_delivery_line`. **Neither is derived from the other**, and the
second is optional: plenty of deliveries are settled on weight alone.

### 2. Absent is not zero — the decision the whole feature rests on

Every column is nullable and **null means NOT RECORDED**. There is **no back-fill**.

Back-filling zeros would have asserted "nothing was delivered" for every delivery in the
installation's history — the single most damaging thing this migration could do, and it would have
looked like data. Recording an outcome without amounts stays legitimate, which is what every
pre-V45 row did.

This is carried all the way out: `DeliveryQuantities.NOT_RECORDED`, a null `quantities` block on the
API, and a form that sends `null` rather than zeros when the operator types nothing.

### 3. Attempted is stored, not derived

What a driver loaded may be less than what a planner allocated. *"We tried to deliver 8 of the 10
allocated"* is a different sentence from *"we delivered 8 of 10"*, and deriving attempted would
erase the difference.

### 4. `delivered + refused <= attempted`, not `=`

Goods can be attempted and neither delivered nor refused — left on the vehicle because the dock
closed, carried back to the depot. That difference is `outstanding()`: what a second attempt would
carry. A real operational state, not an accounting error to forbid.

Checked **per measure**, never netted: a shortfall in pallets is not cancelled by a surplus in kilos.

### 5. What was deliberately not modelled

**No `DAMAGED` / `MISSING` / `RETURNED`.** The brief allows them only if they can be modelled without
inventing semantics, and they cannot yet: *damaged* is a claim needing evidence and a liability
owner, *returned* is a movement needing a return trip, *missing* is a dispute rather than a
quantity. All three are refusals as far as the customer's account is concerned, which `refused_*`
records.

**No cross-attempt total column.** "How much has this order received in all" is a SUM over attempts;
storing it would be a second answer that can drift — the same reason V37 stores allocations and not
a remaining balance.

## MIGRATIONS

```
V45__delivered_quantity.sql
```

Nine nullable measures + five CHECKs on `order_delivery`; `order_delivery_line` with RLS, grants and
composite tenant FKs; `uq_transport_order_line_id_order` so a line result can be pinned to its order.

## BACKEND

`DeliveryQuantities` (new value object, holds the invariant) · `OrderDelivery` (+9 columns,
`quantities()`, `recordQuantities`) · `DeliveryResultRequest` (+optional block, compact constructor
for the outcome-only case) · `TripDeliveryService` (+`requireWithinAllocation`) ·
`OrderDeliveryView` (+`DeliveryQuantitiesView` with server-derived `outstanding`) ·
`TripViewAssembler` · `OrderFulfillmentAdapter` (lifecycle now derived from amounts when present)

### Lifecycle integration

`OrderFulfillmentAdapter` now derives status from **the sum of delivered across every attempt**,
judged against the order's own demand — because an order that received 60 on Monday and 40 on
Tuesday has received all of it, and the latest row alone cannot say so.

**Backward compatible by construction:** orders whose attempts recorded no amounts keep the
outcome-only reading they have always had. Proven by 1700 tests passing before a single new
assertion was added.

Zero delivered still consults the outcome, because amounts cannot say *why* nothing arrived — a
refusal, a failure and an unattempted stop are three different conversations.

## FRONTEND

`planningApi` (+`DeliveryQuantitiesView`/`Request`) · `DeliveryDrawer` (six optional fields, a
warning, and the blank-is-not-zero rule) · `TripWorkspacePage`.

Volume is not asked for on the form — a dock operator counts pallets and weighs, and does not
compute cubic metres — so it travels as 0 beside a real weight and pallet count. **The server
validates each measure separately**, so a 0 in volume cannot mask a shortfall in the others.

## DATABASE / SECURITY / TENANT_TESTS

`order_delivery_line` is company-scoped with RLS, a tenant policy, and **no DELETE grant** (matching
`order_delivery`: what was signed for is corrected, never erased). Two cross-tenant negative tests —
a line from another company's order, and a line whose company differs from its delivery's — both
refused by composite FK.

## CONCURRENCY_TESTS

The cross-attempt ceiling runs under the pessimistic trip lock `TripDeliveryService` already takes,
so two simultaneous recordings serialise. The row-level invariant is enforced by
`ck_order_delivery_not_over_delivered` regardless of path.

## TESTS_FOCUSED

| Suite | Tests | Covers |
|---|---|---|
| `DeliveryQuantitiesTest` | 12 | Absence vs zero, the invariant per measure, outstanding, round-trip |
| `DeliveredQuantityConstraintIntegrationTest` | 12 | Every CHECK, both cross-tenant cases, the line-in-order FK, one-result-per-line |
| `EndToEndSmokeIntegrationTest` step 16 | — | **Amounts through the real API**, with `outstanding` derived server-side |
| `DeliveryDrawer.test.tsx` | 4 | **Blank sends null, not zeros** |

```
BACKEND_CLEAN_PASS=  1712
BACKEND_CLEAN_FAIL=  0
FRONTEND_PASS=       101
FRONTEND_FAIL=       0
E2E_PASS=            34
E2E_FAIL=            0
E2E_SKIPPED=         7
ACCESSIBILITY=       not addressed (JOB 26)
PERFORMANCE=         not addressed (JOB 25)
RETRIES=             0
DEFECTS_FOUND=       3
DEFECTS_FIXED=       3
```

## DEFECTS

1. **My constraint fixture broke three existing rules** — `ck_order_delivery_actor_xor`,
   `ck_order_delivery_operator_is_person` and `ck_order_delivery_shortfall_requires_notes`. The
   constraints were right; the fixture was lazy. Fixed by making it as legal as a real delivery.
2. **A type error only the build caught.** `existing={null}` in my new component test —
   `tsc --noEmit -p tsconfig.app.json` does not cover test files, `npm run build` does. Another
   instance of the rule this chain keeps proving: **the narrower gate certifies nothing**.
3. **A bean cycle**, resolved with `@Lazy` on `OrderPlanningPort` in `OrderFulfillmentAdapter` — the
   orders module already depends on planning's fulfilment view, so eager injection made the two
   require each other at construction. Narrowest fix that keeps the port boundary intact.

## OPEN_DEBTS

```
D1 RESOLVED · D2 RESOLVED
D3 RESOLVED  ← this job
D4 DEFERRED_WITH_REASON · D5 OPEN → JOB 21 · D6 OPEN → JOB 22
D7 RESOLVED · D8 RESOLVED · D9 OPEN → JOB 26
```

**D3 = RESOLVED.** All four questions the brief posed are answerable — attempted, delivered, refused,
outstanding — at two grains, with the ceiling enforced, corrections not double-counted, and
**nothing inferred**.

## FILES_CHANGED

```
A  db/migration/V45__delivered_quantity.sql
A  planning/domain/DeliveryQuantities.java
A  database/DeliveredQuantityConstraintIntegrationTest.java
A  planning/domain/DeliveryQuantitiesTest.java
A  frontend .../DeliveryDrawer.test.tsx
M  OrderDelivery, DeliveryResultRequest, TripDeliveryService, OrderDeliveryView,
   TripViewAssembler, OrderFulfillmentAdapter, SchemaExposureIntegrationTest,
   EndToEndSmokeIntegrationTest, planningApi.ts, DeliveryDrawer.tsx, TripWorkspacePage.tsx
```

```
NEXT_JOB= 20 — Freight Audit & Settlement V1. Next migration V46.
```
