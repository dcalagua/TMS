# TMS by EBIM — what comes next

Ordered by value, with the prerequisite each item actually has. Nothing here is committed to a
date, and nothing here is implied to a customer as existing.

- Baseline: working tree of **2026-08-21**, migration **V35**.
- Next free migration version: **V36**. V1–V35 are immutable.
- Every "why not yet" below is the reason recorded in the module's own contract document, not a
  reconstruction.

---

## 0. Before anything else — not a feature

These three are the difference between a product that is coherent and a product that is *proven*.
None of them is a customer-visible capability, and all three should be done before the next feature.

| # | Item | Effort | Why it comes first |
|---|---|---|---|
| **0.1** | **Run migrations V24–V35 against a real PostgreSQL, once.** Require `Skipped: 0` on the backend suite | One working Docker installation, then a test run | Twelve migrations have never been executed anywhere. 443 declared test cases — RLS isolation, schema exposure, every CHECK and unique index, the vertical smoke — are skipped, not passed. The first apply will surface every error at once, and it is better to find that on a Tuesday than on a demo morning. See [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md) §1 |
| **0.2** | **Make the developer `.env` safe by construction** — a Flyway host guard, or a startup refusal when a non-local `TMS_DB_URL` is paired with `TMS_FLYWAY_ENABLED=true` | Small, and it needs 0.1 to be testable | Today one ordinary gesture — export the file, start the app — applies twelve unproven migrations to a live project. `KNOWN_LIMITATIONS.md` §2 |
| **0.3** | **Fix the three automatic-planning defects** | Small; all three are in one class | `KNOWN_LIMITATIONS.md` §4. Two of them make the on-screen report disagree with itself, which is exactly the kind of thing that costs a planner's trust in the feature |

---

## 1. Next — high value, and nothing structural in the way

### 1.1 Give an order a delivered status

**The product's largest known modelling gap.** `OrderStatus` stops at `PLANNED`; what was handed
over lives on the trip side in `tms.order_delivery`.

Needs: one migration adding the states and their transitions to the orders module, and a rule for
how a partially-delivered order resolves. The feeding table already exists and V25, V27 and V28 each
deferred this deliberately.

Unlocks: an order list that answers "was it delivered", and a far better outbound contract for an
ERP that only ever knew about orders.

### 1.2 An audit read path

`GET /api/v1/audit-events?aggregateType=&aggregateId=`, and a screen behind the `AUDIT_VIEW`
capability that already exists and is already granted.

Needs: a controller and a service over an existing, indexed, append-only table. **No schema change.**

Why it matters commercially: "who changed this shipment, and when" is a question every enterprise
buyer asks in the security review, and the honest answer today is "a SQL query".

### 1.3 The two cross-trip screens

*Every open problem in the company*, and *everything that did not arrive today*. Both are supervisor
questions the product answers per trip and not across them, and both indexes already exist
(`ix_trip_exception_company_open`, `ix_order_delivery_company_shortfall`).

Needs: two read endpoints and two screens. No schema change.

### 1.4 Bulk mark-ready for imported orders

An import of two hundred orders currently needs two hundred confirmations. Either a bulk action on
the Orders screen, or a *mark ready* option on the import itself — which is what the integration API
already offers with `markReadyForPlanning`.

Needs: one endpoint, one selection UI. The completeness check that guards the single-order path is
what it must call per order.

### 1.5 `/account`

The last placeholder route in the product. A profile screen for the person rather than for the
company.

---

## 2. Then — a real customer requirement decides these

Each of these is a genuine feature with a genuine cost, and each is listed with the input it needs
before it can be built honestly rather than approximately.

| Feature | Needs first | Reason it is not built |
|---|---|---|
| **Driver mobile app** | A decision about offline behaviour and a device-authentication story | The model is already per-stop and per-order, so this writes to endpoints that exist. It is a client, not a redesign |
| **Customer portal / public tracking** | A tokenised, non-authenticated read contract, and a data-protection decision about what a link exposes | Positions and delivery results exist; the surface does not |
| **A telematics adapter** | A named provider and a customer who needs it | ADR-007 shipped the contract deliberately without a vendor. Writing one is an implementation of one interface |
| **Route optimisation** | A distance/time matrix — which means a routing provider, which is a deferred decision | Without a matrix a "solver" optimises a straight-line fiction. Deferred by decision in `CLAUDE.md` |
| **ETA, geofencing, automatic arrival** | Route optimisation above, and a conversation about moving accountability for the delivery record from a person to a box on a windscreen | `TRACKING_V1.md` §9 |
| **Carrier waterfall tendering** | An ordered carrier preference per lane, **and a scheduler to advance it when an offer lapses** | `CARRIER_TENDERING_V1.md` §11. The scheduling half now has a precedent — the webhook dispatcher (V35) — so this is less blocked than it was |
| **Accessorials, fuel index, break tables** | A measured input per surcharge: per-stop wait times, a toll table, a published index feed | `RATES_COSTING_V1.md` §8. A surcharge nobody measures is a hand-filled column, which is a spreadsheet with extra steps |
| **Cost per order / per km** | An allocation rule (by weight? by pallet? by drop?), and a single source of distance | Both are commercial decisions per company and must not be baked into the schema |
| **Sell-side pricing and invoicing** | A customer agreement model — a different table, not a `direction` column on the carrier one | `RATES_COSTING_V1.md` §8 |
| **Email — invitations, alerts, scheduled reports** | A transport, a template store, a bounce policy, a suppression list, and expiring tokens | Named in three module documents. It is one platform decision that unlocks all three |
| **Organization administration** | Nothing structural — it is `iam.organization:manage`, which only `ORGANIZATION_ADMIN` holds | `SAAS_ADMINISTRATION_V1.md` §7 calls it the largest gap in that module |
| **Billing, plans, seats** | A usage source, a billing period, and a decision about what happens at the limit | A plan column with no enforcement is a number that lies the first time somebody exceeds it |
| **Supabase Storage for evidence** | The platform decision itself | ADR-006 shipped the port for this. The adapter is a day's work behind it |
| **Evidence retention and erasure** | A data-protection policy | `PROOF_OF_DELIVERY_V1.md` §11. Becomes urgent the day a customer asks for erasure |
| **EWM / ERP product integration** | A concrete customer and a contract | Deferred by standing decision. TMS integrates through APIs and events, never shared tables |

---

## 3. Deliberately not on this roadmap

Listing them is the point — each was considered and declined, and re-proposing one should require
new information rather than a new meeting.

| Not planned | Why |
|---|---|
| Kafka, microservices, event sourcing | The scale target does not need a distributed system, and the module ports mean a later split is a deployment change rather than a rewrite |
| Supabase Realtime / WebSockets | A feed that updates once a minute does not justify a socket |
| Multi-carrier simultaneous tendering / bidding | Needs a rule for what happens when two accept — first, cheapest, or the planner chooses — which is a commercial policy per company, not a schema decision |
| Counter-offers and negotiation threads | A carrier answers yes or no; a different price is `response_notes` and a second attempt |
| Per-company severity policy for alerts | Severity belongs to the alert type. A per-tenant override is a configuration product |
| Snooze, assignment, escalation, SLA clocks on exceptions | No rule in TMS reads any of them, so each would be a field somebody fills in and nothing acts on |
| Excel export of the KPI report | The CSV is read by every spreadsheet and every finance system, and the spreadsheet library is deliberately confined to the import path |
| Stored KPI snapshots, rollups, materialized views | Every figure is an aggregate over at most a quarter of one company's shipments. A pre-aggregated copy buys nothing measurable and introduces the one failure a KPI screen cannot afford: a number that disagrees with its own rows |
| A zone scope on rate cards | A trip serves many destinations, so "which zone is this trip in" has no true answer. Charging by zone needs a per-stop cost model |
| Dimensions on vehicle types beyond what exists | Already added in V9 and consumed; adding more before a rule reads them is premature modelling |

---

## 4. How to decide what is next

Three questions, in order. They are the product principle — *simple now, correctly separable,
scalable later* — turned into something you can apply in a meeting:

1. **Is there a real customer requirement, or is it a feature we can imagine wanting?** Every row in
   §2 waits on this and says so.
2. **Can we prove it from data we already hold?** If the answer needs a number nobody measures, the
   measurement is the feature and the report is the follow-up. This is why there is no accessorial
   catalogue and no cost per kilometre.
3. **Does it need a new architectural decision?** If so, the ADR comes first —
   `docs/architecture/` — and not after the code. ADR-006 and ADR-007 are both examples of an ADR
   deliberately shipping a *boundary* while leaving the implementation for the day a customer needs
   it. That pattern is available again.
