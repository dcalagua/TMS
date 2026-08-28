# Delivered quantity - formal evaluation of open debt D3

*Required by JOB 10, to be closed before JOB 11 (Settlement) if Settlement depends on it.
Read with `docs/domain/PROOF_OF_DELIVERY_V1.md`.*

---

## The debt, stated exactly

> **D3** - Delivery records an *outcome*, not a delivered *quantity*. `PARTIAL` implies no
> demonstrable amount. It must not be inferred from ordered / allocated / planned.

`tms.order_delivery` (V28) records what happened to one order's goods at one stop: `DELIVERED`,
`PARTIAL`, `REJECTED`, `FAILED`, `NOT_ATTEMPTED`. A `PARTIAL` row says *some of it was taken and
some was not*, and says nothing about how much.

---

## 1. Is this a defect?

**No.** It is a gap, and the distinction matters for what to do about it.

Nothing in the system claims to know a delivered quantity. No column holds one, no view reports
one, no calculation consumes one. Every place a quantity could have been silently invented, an
earlier decision refused it:

* V27 refused per-order delivered/refused quantities on stop execution, naming the reason: recording
  *what was handed over* is an order-level model with its own quantities and its own disputes.
* V28 added the outcome and stopped there.
* `DeliveryResult.PARTIAL` documents itself as "some of it was taken and some was not", which is
  precisely the claim the data supports and no more.

So the register entry is correct as a **missing capability**, not as a wrong answer being given. A
defect would be a number that is present and untrustworthy; this is a number that is absent and
known to be.

## 2. Can it be inferred? No, and this is the load-bearing part

The three candidates, and why each is a different fact:

| Candidate | What it actually means |
|---|---|
| **Ordered quantity** | What the customer asked for. Says nothing about what left the warehouse |
| **Allocated quantity** (V37) | What a planner committed to a shipment. A planning intent |
| **Planned quantity** | What the plan assumed would be loaded |

A `PARTIAL` delivery is *by definition* the case where the delivered amount differs from all three.
Using any of them as the delivered quantity would produce a number that is **exactly wrong in
exactly the case it is needed** - and it would look like a measurement.

That is worse than the gap, because a settlement built on it would be defensible-looking and
undefendable. **The inference is prohibited and this evaluation does not change that.**

## 3. Does Settlement (JOB 11) depend on it?

This is the question JOB 10 was asked to answer, so it gets a direct one.

**Not for the rating model as it stands.** Every `RateComponent` in V30/V39 prices the *shipment*
and not the handover: distance, stops, weight, volume, pallets, waiting time, minimum and maximum
adjustments. All of those are properties of what was carried and where, and all are already stored.
A carrier is paid for running the shipment, and running it is what those components measure.

**It would be needed for two things that are not in scope:**

1. **Charging by delivered unit** - a rate expressed per pallet *actually handed over* rather than
   per pallet carried. No rate card component expresses this today.
2. **Crediting a customer for a shortfall** - which is an invoicing concern on the order side, and
   TMS does not invoice customers.

So Settlement can be built, and be correct, without closing D3. **D3 does not block JOB 11.**

## 4. What closing it would actually require

Not one column. The reason this has not been done casually:

* **A quantity per order line, not per order.** An order of three products delivered short in one of
  them is the ordinary partial delivery, and a single per-order number cannot say which. That means
  a delivered-quantity row per line, which is a table.
* **A unit that matches the line's.** Pallets, kilos and units are not interchangeable, and the
  line's own unit is the only defensible one.
* **A refused/returned counterpart.** Delivered plus refused rarely equals ordered - goods are
  damaged, split, or returned later - so a single "delivered" figure that is expected to reconcile
  will be reconciled wrongly.
* **A constraint that delivered never exceeds what was carried**, which needs the allocation of
  V37 as its ceiling.
* **Evidence.** A disputed quantity is settled by a signed note or a photograph, which is
  `EvidenceStoragePort` (ADR-006) - disabled by default and deliberately not in the row.

That is an order-side model with its own migration, its own document and its own disputes. It is
**D1's neighbour, not JOB 10's afterthought.**

## 5. Verdict

| | |
|---|---|
| **State** | **OPEN**, deliberately, and correctly recorded |
| **Is it a defect?** | No - a missing capability, with nothing claiming to have it |
| **Blocks JOB 11 (Settlement)?** | **No.** The rate model prices the shipment, not the handover |
| **May it be inferred meanwhile?** | **No.** Ordered, allocated and planned are each a different fact, and each is wrong in precisely the `PARTIAL` case |
| **What it needs** | A per-line delivered-and-refused model with units, an allocation ceiling and evidence - its own migration and its own document |

**Recommendation:** leave D3 open. Do not close it inside Settlement, and do not let Settlement
create a delivered quantity as a side effect. When a concrete requirement arrives - a customer
paying per delivered pallet, or a credit-note flow - it gets its own job and its own ADR.
