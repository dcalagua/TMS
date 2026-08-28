# QAS promotion package

**Commit `0366e6c` · 50 commits on local `dev`, none pushed · Flyway V1–V48**

What a human needs in order to decide whether this code goes to QAS, and what to do the moment it
arrives.

---

## 1. The decision

```
READY_FOR_QAS_CODE_PROMOTION = YES
READY_FOR_PRODUCTION         = NO
```

**Why YES for QAS.** Every gate a local machine can run is green and was re-run from clean after the
last commit. The remaining unknowns are all of a kind that **only a real environment can resolve** —
the deploy, the authenticated E2E specs, capacity, rollback. Holding the code back does not reduce
that risk; it delays learning it.

**Why NO for production.** Those unknowns are still unknown, and two decisions are outstanding
(D9, D10).

## 2. What arrives

| | |
|---|---|
| Backend | Java 21 / Spring Boot 4, **1844 tests**, Flyway V1–V48 |
| Frontend | React 19 / MUI, **136 unit**, **38 E2E** (+7 skipped) |
| Migrations new since Phase 1 | **V44–V48** — enum guard, delivered quantity, settlement, work assignment, own-fleet costing |
| New permissions | 2 (`costing.own_fleet:read` / `:write`) — **catalogue is 60, asserted exactly** |
| New docs | `docs/operations/` (3), `docs/frontend/ACCESSIBILITY.md`, `docs/domain/` (2) |

## 3. Do these first, in this order

### 3.1 Prove the application connects as `tms_app`

**The single highest-value check in this document.**

ADR-005's row-level security **does not apply to the schema-owning role**. If the application
connects as the owner, RLS is silently inert and the application's own scoping is the only tenant
defence left. Nothing will announce this.

```sql
SELECT current_user, session_user;   -- from an application connection
```

Expect `tms_app`. Anything else: **stop and fix before any data is loaded.**

### 3.2 Confirm migrations reached V48

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
```

A failed migration means the application did not start. That is correct behaviour, not a bug to work
around.

### 3.3 Run the 7 authenticated E2E specs

**They have never executed, in either phase.** They are the largest single block of unproven
verification in the project.

```
E2E_BASE_URL=<qas-url> npx playwright test
```

### 3.4 Point something at `/actuator/metrics`

Fourteen signals are emitted and **nothing watches them**. `docs/operations/OBSERVABILITY.md` says
what each means. The two most likely to say something early:

- `tms.costing.own_fleet.quotes{outcome="no_profile"}` — trucks with no rates configured
- `tms.integration.requests{outcome}` by provider

## 4. Functional checklist

Scenarios worth walking in QAS, with what "correct" looks like. **Several correct answers are the
system refusing to answer** — those are marked ⚠ because they are the ones most likely to be
reported as bugs.

| # | Scenario | Expected |
|---|---|---|
| 1 | Plan a day, confirm, dispatch | Shipment reaches IN_TRANSIT; control tower shows it |
| 2 | Dispatch a shipment whose accepted carrier does not own the vehicle | **Refused** (D2, V42) |
| 3 | Record a partial delivery | Quantities stored at two grains; status derived in the same transaction |
| 4 | Record a delivery **without** quantities | ⚠ Stored as **NULL, not zero**, and reports say "not recorded" |
| 5 | Receive an invoice matching expected cost | Matched, approvable |
| 6 | Receive an invoice above tolerance | Discrepancy raised; approval **blocked** until resolved |
| 7 | **The person who keyed an invoice tries to approve it** | ⚠ **Refused.** Somebody else must authorise |
| 8 | The person who keyed it **rejects** it | ⚠ **Allowed** — refusing to pay commits nothing |
| 9 | Approve the same invoice twice | Second click is a no-op; **exactly one approval row** |
| 10 | Export an approved invoice twice | Idempotent — same reference returned |
| 11 | Build a driver's day with two shipments too close together | **Refused**, naming the reposition shortfall |
| 12 | Build a day where a leg cannot be measured | ⚠ **Refused as `ROUTING_UNKNOWN`** — not treated as zero |
| 13 | Two dispatchers build one truck's day at once | Exactly one wins |
| 14 | Configure an own-fleet cost profile and quote a trip | Breakdown with quantity, rate, amount and **source** per line |
| 15 | Quote a trip on a truck with **no** profile | ⚠ **No cost at all** — *not* a cost of zero |
| 16 | Quote a trip whose route cannot be measured, on a per-km profile | ⚠ **No comparable total**; partial subtotal shown and labelled |
| 17 | Compare a carrier price with an own-fleet cost | Both shown, both **labelled** price vs internal cost |
| 18 | Compare across two currencies | ⚠ **`INCOMPARABLE_CURRENCY`** — no FX invented |
| 19 | Open the control tower with an unresolved discrepancy | Appears under **advisories**, not blockers; links to Settlement |
| 20 | Cross-tenant: company B reads company A's anything | **404 / empty**, never data |

### Scenarios that are N/A rather than failed

- **Automatic arrival detection** — deferred by ADR-007/011. A geofence informs; a person records
  arrival.
- **Automatic tender advancement** — debt D4. No system actor exists, deliberately.
- **Cost allocation across orders** — debt D10. No policy selected.
- **Live map tracking, route optimisation, EWM/ERP integration** — deferred by decision.

**A test cannot fail against a capability nobody built.**

## 5. Known limitations to brief testers on

Otherwise these get reported as defects:

1. **Unknown is never zero.** Missing distance, quantity, ETA, reposition or cost is reported as
   *absent*. Screens say so. This is the most-repeated design rule in the system.
2. **Own-fleet reposition distance is not charged**, only its time — V47 froze minutes, not
   kilometres. Understates a multi-trip day, deliberately in the direction that does not flatter own
   fleet.
3. **A total that mixes a carrier price with an own-fleet cost is labelled**, because the first has
   margin and the second does not.
4. **Control tower has three separate counts** — blockers, exceptions, advisories. They are not
   summed and must not be read as one number.
5. **The primary button is a darker green than before** (JOB 26). It was 2.83:1 against white and AA
   needs 4.5:1.

## 6. What QAS cannot tell you either

- **Capacity.** JOB 25 proved the control tower's query count does not grow with the day. It measured
  **no** write path, **no** concurrency and **no** volume. Nothing anywhere says 10,000 orders/day.
- **Accessibility beyond two screens.** No screen reader, no keyboard-only pass, nobody with a
  disability has used it.
- **Rollback.** Forward-only schema, no down-migrations, no tested procedure. **The largest
  unaddressed operational risk in the system.**

## 7. Two decisions waiting on a human

**D10 — cost allocation.** TMS can say what a trip cost. It cannot say how that cost is shared
between the orders on it, because nobody has chosen the rule. Delivered quantity, weight, volume and
pallets are all defensible and all different. **This was deliberately not decided in code**, and it
blocks per-order profitability reporting.

**D9 — accessibility target.** A conformance level and a date, or an explicit decision not to claim
one.

## 8. Promotion is a human action

Nothing in this package has been pushed. `git log origin/dev..HEAD` is 50 commits. **Review them, and
push if you agree.**
