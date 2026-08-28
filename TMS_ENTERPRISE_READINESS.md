# TMS by EBIM — Enterprise Readiness Assessment

**Assessment date:** 2026-08-28
**Code branch:** dev
**Assessment scope:** Local automated certification only
**Deployment certification:** NOT PERFORMED
**Shared environment writes:** NONE

**Assessed at commit:** `ab2b0b6` · working tree clean · nothing pushed
**Method:** every row below is derived from the source tree, the Flyway history, the test suites and
the JOB 01–16 RESULT files. Where the older `docs/architecture/TMS_CURRENT_CAPABILITY_MAP.md`
disagrees with this document, the disagreement is named rather than smoothed over — that map was
written at commit `0757afb`, before JOBs 08–16 landed, and is stale in specific places identified in
§7.

## Executive Result

**PRODUCTION READY WITH LIMITATIONS — for QAS code promotion only.**

The platform, tenancy, domain model and automated test discipline are strong and genuinely
enterprise-grade. The gap between this system and an enterprise TMS is **not** architectural: it is
that **freight audit and settlement do not exist**, **delivered quantity is not captured**, and
**nothing has ever been run against a real environment**.

This assessment does **not** claim OTM parity, full enterprise parity, or production certification.
None of the three has been demonstrated, and two of them have not been attempted.

## Automated Evidence

- Backend: 1684 pass / 0 fail / 0 skipped
- Frontend unit: 97 pass
- E2E: 34 pass / 7 skipped
- Typecheck: PASS
- Lint: PASS
- Frontend build: PASS
- Flyway: V1–V43 contiguous
- Working tree at certification: clean

**Confirmed against the sources before writing.** All figures match `TMS_OVERNIGHT_16_RESULT.md`
(re-certified 06:22 after debt D7 was closed) and the final gate re-run at 06:24. Two notes on
provenance:

- `TMS_OVERNIGHT_MASTER_LOG.md` records the *progression* 1585 → 1674 → 1684 across the session. The
  1684 figure is the current one; earlier figures in per-JOB entries are historical and correct for
  their moment.
- `docs/architecture/TMS_CURRENT_CAPABILITY_MAP.md` still shows `1585` and `V1–V41`. It was written
  before JOBs 09–16 and has not been regenerated. **That file is stale, not wrong-at-the-time.**

The **7 skipped E2E specs** are the authenticated ones. They are skipped by an explicit environment
condition, not by a disabled assertion, and the count has not moved during the whole chain.

---

## Capability Matrix

Classification measures **capability**, not implementation quality. A well-built partial capability
is still PARTIAL.

`ENTERPRISE READY` · `PRODUCTION READY WITH LIMITATIONS` · `PARTIAL` · `EXPERIMENTAL` ·
`NOT IMPLEMENTED`

### Platform / Architecture

| Capability | Status | Automated Evidence | Remaining Gap | Severity | Recommended Next Phase |
|---|---|---|---|---|---|
| Modular monolith architecture | ENTERPRISE READY | `ModuleBoundaryTest`, `LayeringTest`; 11 modules, cross-module access only via `shared.reference` ports | None | — | Hold the line as modules grow |
| Tenant isolation | ENTERPRISE READY | Composite FKs `(id, company_id)`; `TenancyConstraintIntegrationTest`; `TenantScopedRepositoryTest` (JOB 15) refuses bare-id reads and unscoped own-id finders | Enforcement is static + DB; no runtime penetration testing | LOW | Cross-tenant negative tests in QAS |
| RLS | ENTERPRISE READY | ADR-005; `SchemaExposureIntegrationTest` verifies RLS enabled, policy present and grants correct on **every** table | Defence in depth only — never the authorization | — | None |
| IAM / RBAC | ENTERPRISE READY | `ApiSecurityTest`, `IdentityResolutionIntegrationTest`, `CapabilityTest`; JWT validated server-side, membership resolved server-side | No SSO/SCIM provisioning | LOW | Only if a customer requires it |
| Machine-to-machine authentication | ENTERPRISE READY | Integration clients with credentials, per-client scopes, machine principal distinct from human; `EndpointContractTest` refuses mixing the two tenancy models | None material | — | None |
| Audit | PRODUCTION READY WITH LIMITATIONS | `audit/*`, V22; `AuditVocabularyMigrationTest` guards Java-enum ↔ DB `CHECK` drift | Coverage is per-module and grew ad hoc; no retention or export policy | MEDIUM | Define retention; confirm every write path that must be audited is |
| Flyway migration discipline | ENTERPRISE READY | V1–V43 contiguous, no duplicates; **each of V36–V43 touched by exactly one commit** — no applied migration rewritten | None | — | None |
| API / OpenAPI | PRODUCTION READY WITH LIMITATIONS | springdoc 3.0.3 published; `EndpointContractTest` asserts every endpoint is permission-guarded and company-scoped | No published versioning or deprecation policy; no consumer contract tests | MEDIUM | Version + deprecation policy before external consumers |

### Master Data

| Capability | Status | Automated Evidence | Remaining Gap | Severity | Recommended Next Phase |
|---|---|---|---|---|---|
| Companies | ENTERPRISE READY | V2–V5, V34; company-scope suites | None | — | None |
| Locations | ENTERPRISE READY | V6, V14, V23 canonical unification; eligibility suites | None | — | None |
| Zones | ENTERPRISE READY | V6–V8; zone suites | None | — | None |
| Routes | ENTERPRISE READY | V8, V15, V24; route + reference-distance suites | None | — | None |
| Frequencies | ENTERPRISE READY | V7, V15; calendar, exception and cutoff tests | None | — | None |
| Carriers | ENTERPRISE READY | V9; per-company tax-id uniqueness | None | — | None |
| Vehicle Types | ENTERPRISE READY | V9; capacity defaults | None | — | None |
| Vehicles | ENTERPRISE READY | V9, V16; `EffectiveCapacityResolver`, double-booking index | None | — | None |
| Drivers | ENTERPRISE READY | V26; licence status judged against the shipment's date | None | — | None |

### Orders

| Capability | Status | Automated Evidence | Remaining Gap | Severity | Recommended Next Phase |
|---|---|---|---|---|---|
| Transport Orders | ENTERPRISE READY | V10, V17, V36; totals, import, status suites | None | — | None |
| Order lifecycle | ENTERPRISE READY | 8 states incl. execution outcomes (ADR-009, V36); status recomputed from delivery rows in the same transaction so the two cannot drift | None | — | None |
| Ship Units | PARTIAL | `OrderAmounts`/`OrderAllocation`; `docs/domain/SHIP_UNITS_AND_ALLOCATION_V1.md` | **A ship unit here is a portion of demand in weight/volume/pallets, not an identified handling unit.** No `ship_unit` table, no barcode, no nesting. This is a documented decision, not an oversight — but it is not the enterprise definition | MEDIUM | Only if a customer needs handling-unit identity (labels, nesting, SSCC) |
| Partial allocation | ENTERPRISE READY | V37 ledger + `ck_transport_order_not_over_allocated`; 7 split tests incl. a real concurrency race | None | — | None |
| Delivery reconciliation | PRODUCTION READY WITH LIMITATIONS | V28, V36; `OrderExecutionPropagatorTest`; outcomes drive order status, recomputed on every correction | Reconciles **outcomes**, not amounts | HIGH (see D3) | Blocked on delivered quantity |
| Delivered quantity | NOT IMPLEMENTED | `docs/domain/DELIVERED_QUANTITY_EVALUATION.md` (JOB 10 formal evaluation) | No amount is captured on delivery. `PARTIAL` asserts "some was taken" and nothing more. **Must not be inferred** from ordered / allocated / planned — each is wrong in exactly the `PARTIAL` case | HIGH | Per-line delivered + refused model with units, an allocation ceiling and evidence. Its own migration and ADR — **debt D3** |

### Planning

| Capability | Status | Automated Evidence | Remaining Gap | Severity | Recommended Next Phase |
|---|---|---|---|---|---|
| Manual planning | ENTERPRISE READY | V11, V19; `PlanningApiIntegrationTest`, capacity and stop-sync suites | None | — | None |
| Planning Engine V1 | ENTERPRISE READY | `HeuristicPlanningEngine`; pure function, unit-tested | None | — | Remains the default by decision |
| Planning Engine V2 | PRODUCTION READY WITH LIMITATIONS | `PlanningEngineV2Test` (22), `PlanningEngineComparisonTest` (5 head-to-head) | Not the default; no operational evidence at scale | MEDIUM | Promote only after QAS comparison on real data |
| Routing Matrix | ENTERPRISE READY | V38, ADR-010; `RoutingServiceTest` (21), cache constraint tests | **No vendor adapter** — by decision | LOW | Add an adapter when a customer supplies a provider |
| Distance | ENTERPRISE READY | Local geodesic estimator + company-scoped cache with DB-generated grid columns | Straight-line unless a vendor is configured — and it says so | LOW | Vendor adapter |
| Travel Time | ENTERPRISE READY | Same port; per-leg duration with provenance | Same as distance | LOW | Vendor adapter |
| Planning KPIs | ENTERPRISE READY | `PlanningKpis`; utilisation null-not-zero when no vehicle declares a limit | None | — | None |
| Planning total cost | ENTERPRISE READY | `ProposalPricerTest` (11, **6 of them refusals**); priced through the same port, selector and calculator a tender and an invoice use | Never a partial total, never an FX conversion, own fleet deliberately unpriced (**D6**) | LOW | Own-fleet internal cost model |
| Unplanned reasons | ENTERPRISE READY | `UnplannedReason` typed per cause, not one "could not plan" | None | — | None |
| Resource constraints | PRODUCTION READY WITH LIMITATIONS | `PlanningShift`; V42 availability readable by planning via `ResourceAvailabilityPort` | Planning **reads** availability; it does not yet **plan around** multi-shipment sequencing (**D5**) | MEDIUM | Work assignment model |

### Rating

| Capability | Status | Automated Evidence | Remaining Gap | Severity | Recommended Next Phase |
|---|---|---|---|---|---|
| Rate Cards | ENTERPRISE READY | V30, V39; card form + selector tests | None | — | None |
| Rate qualification | ENTERPRISE READY | `RateCardSelector`; scope incl. LANE (V39), validity dates, vehicle type | None | — | None |
| Rate components | ENTERPRISE READY | 12 components with ordered arithmetic; `RateEngineV2CalculatorTest` (15) | None | — | None |
| Accessorials | ENTERPRISE READY | `WAITING_TIME`, `TOLL`, `OTHER_ACCESSORIAL`, plus MIN/MAX adjustment on the total | Free-form accessorial catalogue is not customer-configurable | LOW | Only on demand |
| Cost breakdown | ENTERPRISE READY | `trip_cost_component` per line, persisted; `TripCostCard` renders it generically | None | — | None |
| Cost provenance | ENTERPRISE READY | `CostQuantitySource` per line; `MEASURED_ROUTE` vs `ROUTE_REFERENCE`; the V38 `CACHE`-laundering defect is fixed and guarded | None | — | None |
| Rate snapshotting | ENTERPRISE READY | Estimate snapshotted from the winning card; cards deactivated and never deleted so a snapshot keeps resolving | None | — | None |
| Own-fleet costing | NOT IMPLEMENTED | Absent by decision — own fleet has no rate card and is **not priced at zero** | Fuel, driver hours, depreciation | MEDIUM | **Debt D6** — needs its own model, not a rate card |

### Tendering

| Capability | Status | Automated Evidence | Remaining Gap | Severity | Recommended Next Phase |
|---|---|---|---|---|---|
| Carrier selection | ENTERPRISE READY | `CarrierRankingTest` (7); quotes carriers that do not own the vehicle — which is what subcontracting is | Different currencies are not comparable and the ranking says so | — | None |
| Tender lifecycle | ENTERPRISE READY | V31; `TenderStatusTest`, 3 partial unique indexes as concurrency backstops | None | — | None |
| Tender waterfall | ENTERPRISE READY | V40; `TenderWaterfallTest` (12) | None | — | None |
| Accepted carrier invariant | ENTERPRISE READY | V42; `TripAcceptedCarrierTest`, `PlanningConstraintIntegrationTest` (+3); refused in service, aggregate **and** `ck_trip_departed_carrier_matches_vehicle` | None — **debt D2 closed** | — | None |
| Automatic tender scheduler | NOT IMPLEMENTED | Absent by decision; `requireAppUserId` refuses machines | A waterfall advances when a dispatcher advances it | MEDIUM | **Debt D4** — needs a first-class system-actor model, not a fake user |

### Fleet

| Capability | Status | Automated Evidence | Remaining Gap | Severity | Recommended Next Phase |
|---|---|---|---|---|---|
| Vehicle availability | ENTERPRISE READY | V42; `ResourceAvailabilityIntegrationTest` incl. a real two-thread race on one truck | None | — | None |
| Driver availability | ENTERPRISE READY | V42; same suite; guarded by `fleet.driver:*` so workshop clerks cannot read medical absences | None | — | None |
| Resource scheduling | PARTIAL | V42 availability + `ex_*_unavailability_no_overlap` EXCLUDE constraints | Availability is a **layer**, not a scheduler | MEDIUM | Work assignment (**D5**) |
| Work Assignments | NOT IMPLEMENTED | Named in V42's "deliberately NOT here" | Cannot sequence several shipments onto one driver-and-vehicle pair with travel time between them | MEDIUM | **Debt D5** — needs a scheduling model, a rebalancing story and a screen |
| Maintenance blocks | ENTERPRISE READY | `UnavailabilityReason` MAINTENANCE / REPAIR / INSPECTION; reason must fit the resource | No link to an asset-management system | LOW | Only on demand |
| Driver shifts | PRODUCTION READY WITH LIMITATIONS | V42 `driver_shift`, minutes-since-local-midnight; round-trip asserted both ways | Weekly rule only; **no overnight shift** (two rows on two days, by decision); not consumed by planning yet | MEDIUM | Feed shifts into the work assignment model |

### Appointments

| Capability | Status | Automated Evidence | Remaining Gap | Severity | Recommended Next Phase |
|---|---|---|---|---|---|
| Dock resources | ENTERPRISE READY | V41; `LocationResourceService` | None | — | None |
| Calendars | ENTERPRISE READY | V41 opening hours as minutes-of-day — a `time` column was being zone-shifted | No holiday calendar beyond explicit closures | LOW | Only on demand |
| Blocked slots | ENTERPRISE READY | V41 `resource_blocked_slot` | None | — | None |
| Appointment lifecycle | ENTERPRISE READY | `AppointmentStatusTest` (27); 7 states with a transition table | None | — | None |
| Concurrent double-book prevention | ENTERPRISE READY | `EXCLUDE USING gist` + `btree_gist`; **two real threads race for one door and exactly one wins** | None | — | None |
| TMS/WMS integration boundary | PRODUCTION READY WITH LIMITATIONS | `AppointmentTripPort`; **no WMS table, column or view created** | The boundary is a port with no counterparty implemented | MEDIUM | Contract with EWM when that integration is commissioned |

### Execution

| Capability | Status | Automated Evidence | Remaining Gap | Severity | Recommended Next Phase |
|---|---|---|---|---|---|
| Trips | ENTERPRISE READY | V11, V19, V25; transition table asserted in service and entity with DB CHECKs beneath | None | — | None |
| Stops | ENTERPRISE READY | V27; per-stop execution states and events | None | — | None |
| Dispatch guards | ENTERPRISE READY | Licence, vehicle operability, **carrier-owns-the-vehicle** (V42) and **resource availability** (V42), each refused in three layers | None | — | None |
| Delivery | PRODUCTION READY WITH LIMITATIONS | V28; `DeliveryResult` with 5 outcomes; shortfall must be explained | Outcome only — see delivered quantity | HIGH | **D3** |
| POD | PRODUCTION READY WITH LIMITATIONS | ADR-006 `EvidenceStoragePort`; local-volume implementation | **Disabled by default**; no object store configured; no signature capture | MEDIUM | Commission a store; decide signature format |
| Delivery quantity | NOT IMPLEMENTED | See Orders | — | HIGH | **D3** |
| Re-delivery | ENTERPRISE READY | V36 — a failed delivery becomes reopenable for a second attempt (ADR-009) | None | — | None |

### Tracking

| Capability | Status | Automated Evidence | Remaining Gap | Severity | Recommended Next Phase |
|---|---|---|---|---|---|
| GPS tracking | PARTIAL | V29, ADR-007; `tracking_position`, sampling and validation tests | **No vendor adapter** — by decision. On an installation without one the table is empty and the feature is inert *by design*, not broken | MEDIUM | Adapter needs a concrete customer requirement |
| Routing-based ETA | ENTERPRISE READY | V38 legs feed the schedule | None | — | None |
| Stop ETA | ENTERPRISE READY | V43, ADR-011; `StopScheduleEngineTest` (14, pure function) + 6 end-to-end; **an unmeasurable leg ends the chain and every later stop has no estimate** | Recomputed on request only — no background job, and the reason is D4 | LOW | Background recomputation once a system actor exists |
| Geofencing | PARTIAL | V43 `location.geofence_radius_m`, 25 m–20 km, with API and UI | **Observational only.** No stored crossings; evaluation depends on `tracking_position`, which is empty without a vendor adapter | LOW | Adapter first |
| Route deviation | NOT IMPLEMENTED | No code | Nothing detects a vehicle off its plan | LOW | Needs a tracking feed first |
| Automatic lifecycle transitions | NOT IMPLEMENTED | **Deliberately absent.** ADR-007: a position informs a person and never moves a lifecycle; `actual_arrival_at` is written by whoever arrived | — | — | Keep deferred. See §8 |

### Settlement

**This is the largest capability gap in the product, and the one most likely to be misread.**

`docs/architecture/TMS_CURRENT_CAPABILITY_MAP.md` row 16 records settlement as MISSING and points at
"JOB 11". JOB 11 was titled *Settlement* but delivered **proposal pricing** (closing debt D1) — its
own RESULT file says so plainly. **Freight audit and settlement were never built.** Verified by
inspection: no `CarrierInvoice`, no `carrier_invoice` table, no matching, no discrepancy, no
approval, no export anywhere in `src/main/java` or `db/migration`.

| Capability | Status | Automated Evidence | Remaining Gap | Severity | Recommended Next Phase |
|---|---|---|---|---|---|
| Carrier Invoice | NOT IMPLEMENTED | None — no entity, no table, no endpoint | Everything | HIGH | A settlement module of its own |
| Matching | NOT IMPLEMENTED | None | Estimate vs actual vs invoice | HIGH | Follows the invoice |
| Tolerances | NOT IMPLEMENTED | None | Per-carrier or per-component thresholds | HIGH | Follows matching |
| Discrepancies | NOT IMPLEMENTED | None | Typed, workflowed differences | HIGH | Follows matching |
| Approval | NOT IMPLEMENTED | None | Who may approve a variance, and up to what | HIGH | Follows discrepancies |
| Voucher / export | NOT IMPLEMENTED | None | No accounting hand-off (KPI CSV export exists and is a different thing) | HIGH | Follows approval |
| Cost allocation | NOT IMPLEMENTED | None | Trip cost is not allocated back to orders or customers | MEDIUM | Needs delivered quantity (D3) to be defensible |
| *(foundation)* Estimated + actual trip cost | ENTERPRISE READY | V30, V33, V39; `TripCost` with close/reopen and audit actions | This is the foundation settlement would be built on — it is real and it is not settlement | — | — |

### Control Tower

| Capability | Status | Automated Evidence | Remaining Gap | Severity | Recommended Next Phase |
|---|---|---|---|---|---|
| Operational blockers | ENTERPRISE READY | `ControlTowerBlockersTest` (7); every row is a **hard stop** that makes `dispatch` refuse, asked at each shipment's own planned departure | None | — | None |
| Permission-aware KPIs | ENTERPRISE READY | `ControlTowerSummaryTest` (10); unplanned backlog is **null not zero** without `orders.order:read`, and the query is not even run | None — **debt D7 closed** | — | None |
| Exception visibility | PARTIAL | `TripException` typed and resolvable; panel capped with the true total beside it | Exceptions are **trip-only**. No generic exception across orders, tenders, tracking or invoices | MEDIUM | Generic operational exception model |
| ETA advisory information | PARTIAL | ETA and out-of-window shown on the shipment | Not surfaced on the control tower — deliberately, to keep the blocker panel to hard stops | LOW | A separate advisory panel if operators ask |
| Appointment advisory information | NOT IMPLEMENTED | Absent by decision — a missed dock booking does not stop a departure | No appointment signal on the tower | LOW | A separate advisory panel |
| Exception workflow | PARTIAL | Open/resolve on a trip exception | No SLA, no dedup, no assignment, no escalation | MEDIUM | Follows the generic exception model |

### Integrations

| Capability | Status | Automated Evidence | Remaining Gap | Severity | Recommended Next Phase |
|---|---|---|---|---|---|
| M2M clients | ENTERPRISE READY | V18, V20; credentials, rotation, per-client scopes, SSRF protections | None | — | None |
| Idempotency | ENTERPRISE READY | Idempotency keys on inbound writes; repeating a key replays the first response | None | — | None |
| Inbox / outbox | ENTERPRISE READY | `integration_request` inbox with typed outcomes; `shipment_outbox_event` | None | — | None |
| Webhooks | ENTERPRISE READY | V35; encrypted secrets, signature headers, attempt history, `SKIP LOCKED` dispatcher safe on every node | None | — | None |
| Integration health | ENTERPRISE READY | `IntegrationHealthServiceTest` (8); **age not count**, and inactive subscriptions holding a backlog — the failure that looks like silence | None | — | None |
| Retry | PRODUCTION READY WITH LIMITATIONS | Automatic backoff + per-delivery manual retry | **No bulk retry, by decision** — the cause is usually still true and re-queueing forty turns one broken endpoint into forty attempts | LOW | Only with a rate limiter |
| Dead letter | PARTIAL | `FAILED` is the terminal exhausted state and is counted separately as a work queue | No distinct dead-letter store, no quarantine, no age-out | MEDIUM | Formalise if volumes justify it |
| Replay | PARTIAL | Per-delivery retry replays one delivery; idempotency keys replay one inbound response | No range or subscription-wide replay; no payload inspection UI | MEDIUM | Follows dead letter |
| Correlation ID | ENTERPRISE READY | MDC-propagated, on outbound headers and on the inbound request record | None | — | None |

### Quality

| Capability | Status | Automated Evidence | Remaining Gap | Severity | Recommended Next Phase |
|---|---|---|---|---|---|
| Backend automated testing | ENTERPRISE READY | **1684 pass / 0 fail / 0 skipped**; Testcontainers against real PostgreSQL + PostGIS; real concurrency races for dock booking, order split and resource blocking | None | — | None |
| Frontend component testing | PRODUCTION READY WITH LIMITATIONS | 97 pass; component tests assert the **sentences that make a screen honest**, not layout | Coverage is deepest on what was built last; older screens are thinner | MEDIUM | Extend to the older screens |
| E2E | PRODUCTION READY WITH LIMITATIONS | 34 pass — whole-menu smoke, console-error and failed-request assertions | Unauthenticated surface only | MEDIUM | Run the authenticated set |
| Authenticated E2E | NOT IMPLEMENTED *(as executed evidence)* | 7 specs exist and are **skipped** by an explicit environment condition | They have never run here. Whatever they would prove is unproven | HIGH | First task in QAS |
| Architecture guards | ENTERPRISE READY | `ModuleBoundaryTest`, `LayeringTest`, `EndpointContractTest`, `NativeQueryQuotingTest`, `TenantScopedRepositoryTest`, `PersistenceMappingTest`, `SecretExposureTest`; catalogued in `docs/security/STATIC_GUARDS.md` | None | — | None |
| Enum / CHECK consistency protection | PARTIAL | `AuditVocabularyMigrationTest` covers the audit vocabulary — the one that drifts most | No general guard that every enum column's `CHECK` lists exactly its Java enum's values | MEDIUM | **Debt D8** |
| Accessibility testing | NOT IMPLEMENTED | **None anywhere in the project** | No axe, no keyboard navigation, no focus management, no contrast checks. MUI defaults are *not* evidence | MEDIUM | **Debt D9** — see §12 |
| Performance / load testing | NOT IMPLEMENTED | None | The 10,000 orders/day target is honoured in schema and query patterns and **has never been measured** | HIGH | See §11 |

### Operations

| Capability | Status | Automated Evidence | Remaining Gap | Severity | Recommended Next Phase |
|---|---|---|---|---|---|
| Structured logging | ENTERPRISE READY | Correlation ID in MDC; no secrets logged | None | — | None |
| Metrics | PARTIAL | Micrometer + actuator; counters on tracking, audit, notification, integration and routing | **Planning, rating and tender metrics are absent** | MEDIUM | Instrument the remaining modules |
| Observability | PARTIAL | Logging + partial metrics + `/actuator` | **No `docs/operations/` directory exists at all** — no runbook, no alert catalogue, no dashboards, no SLOs | HIGH | Write the runbook before QAS sign-off |
| Security guards | ENTERPRISE READY | `docs/security/STATIC_GUARDS.md`; secret-exposure, tenancy and mapping guards in the build | No external penetration test | MEDIUM | Pen test before production |
| Performance baseline | NOT IMPLEMENTED | **`docs/operations/PERFORMANCE_BASELINE.md` does not exist** | No baseline of any kind | HIGH | See §11 |
| Deployment verification | NOT IMPLEMENTED | Nothing deployed by this chain. The pre-existing record (`0757afb`) already stated the deploy was unverified | Unchanged | HIGH | QAS deploy + smoke |
| QAS certification | NOT IMPLEMENTED | Never performed | — | HIGH | See §10 |

---

## Open Domain / Technical Debts

Reconciled against `TMS_OVERNIGHT_MASTER_LOG.md` (the authoritative register) and verified against
code where a claim was checkable.

### D1 — `PlanningKpis.totalCost` was always null

- **STATUS:** RESOLVED (JOB 11)
- **IMPACT:** A planner could not compare two engines on cost.
- **WHY IT IS STILL OPEN:** It is not. Closed with `ProposalPricerTest` (11 tests, 6 of them
  refusals), priced through the same port, selector and calculator a tender and an invoice use.
- **BLOCKS QAS?** No · **BLOCKS PRD?** No
- **RECOMMENDED CLOSURE:** Done.

### D2 — An accepted tender could contradict the vehicle on the shipment

- **STATUS:** RESOLVED (V42, JOB 09)
- **IMPACT:** A shipment could be commercially agreed with one carrier while carrying another's
  truck, with nothing in the schema saying which was true.
- **WHY IT IS STILL OPEN:** It is not. `accepted_carrier_id` records who agreed, `carrier_id` keeps
  meaning the vehicle's owner, and a mismatch **prevents departure** — refused in the service, the
  aggregate and `ck_trip_departed_carrier_matches_vehicle`.
- **BLOCKS QAS?** No · **BLOCKS PRD?** No
- **RECOMMENDED CLOSURE:** Done.

### D3 — Delivery records an outcome, not a delivered quantity

- **STATUS:** **OPEN**, formally evaluated (JOB 10, `docs/domain/DELIVERED_QUANTITY_EVALUATION.md`)
- **IMPACT:** A `PARTIAL` delivery asserts that some goods were taken and cannot say how many.
  Customer credit notes, per-delivered-unit charging and cost allocation are all impossible.
- **WHY IT IS STILL OPEN:** It is a **missing capability, not a defect** — nothing in the system
  claims to know a delivered quantity. It must **not** be inferred from ordered, allocated or
  planned amounts, because a `PARTIAL` delivery is by definition the case where the delivered amount
  differs from all three; using any of them would be *exactly wrong in exactly the case it is needed*
  and would look like a measurement. Closing it properly is a per-order-**line** model with units, a
  refused/returned counterpart, an allocation ceiling and evidence — a table, not a column.
- **BLOCKS QAS?** **No.** Nothing in QAS depends on it, and the system does not pretend to have it.
- **BLOCKS PRD?** **Yes, for any customer who invoices by delivered unit or issues shortfall
  credits.** No, for a customer who only needs proof of what happened.
- **RECOMMENDED CLOSURE:** Its own job and its own ADR, driven by a concrete commercial requirement.
  Do **not** let a future settlement module create a delivered quantity as a side effect.

### D4 — No system-actor model, so no automatic tender advancement

- **STATUS:** DEFERRED_WITH_REASON *(verified unchanged in the master log)*
- **IMPACT:** A tender waterfall advances when a dispatcher advances it. An expired offer does not
  automatically roll to carrier B overnight.
- **WHY IT IS STILL OPEN:** `requireAppUserId` refuses machines **by design** — an offer is a
  commercial commitment and the audit trail must name who made it. No fake user, hardcoded UUID or
  anonymous principal was introduced, and that refusal was upheld three times during the chain
  (JOB 07, then again as the reason the ETA has no background recomputation in JOB 10).
- **BLOCKS QAS?** No · **BLOCKS PRD?** No — it is an operational convenience, not a correctness gap.
- **RECOMMENDED CLOSURE:** A first-class system actor: a distinct principal type with its own
  identity, its own audit representation and its own permission set, so an automated action is
  attributable and distinguishable from a person's. See §8.

### D5 — No work assignment model

- **STATUS:** **OPEN** (raised JOB 09)
- **IMPACT:** Several shipments cannot be sequenced onto one driver-and-vehicle pair with travel
  time between them. A day is planned per shipment, not per resource.
- **WHY IT IS STILL OPEN:** Deliberate. V42 delivered the availability layer such a scheduler would
  be built on; shipping a table nothing writes to would have been scaffolding.
- **BLOCKS QAS?** No · **BLOCKS PRD?** No — it limits planning sophistication, not correctness.
- **RECOMMENDED CLOSURE:** A scheduling model, a rebalancing story and a screen. Feed `driver_shift`
  into it.

### D6 — No internal cost model for own fleet

- **STATUS:** **OPEN** (raised JOB 11)
- **IMPACT:** A plan using own fleet reports **no total cost** rather than a wrong one. Own fleet is
  deliberately not priced at zero, which would have made any plan using it unbeatable.
- **WHY IT IS STILL OPEN:** Fuel, driver hours and depreciation are a different model with different
  inputs. Mixing a carrier's price with an own-fleet estimate compares two unlike numbers.
- **BLOCKS QAS?** No · **BLOCKS PRD?** No, unless the customer runs own fleet **and** needs plan
  costing — then it is a real limitation to disclose.
- **RECOMMENDED CLOSURE:** Its own cost model and its own decision about comparability.

### D7 — Control Tower V1 had no backend tests

- **STATUS:** RESOLVED (post-certification, 06:22)
- **IMPACT:** The summary counts, the panel capping and the permission rule were entirely uncovered.
- **WHY IT IS STILL OPEN:** It is not. `ControlTowerSummaryTest` (10 tests) covers the status
  roll-up, the window cutoff across past/present/future days, tenancy on every count, and the rule
  worth the exercise: **the unplanned backlog is `null` not `0`** for a caller without
  `orders.order:read`, and the query is not even run.
- **BLOCKS QAS?** No · **BLOCKS PRD?** No
- **RECOMMENDED CLOSURE:** Done.

### D8 — No guard that each enum column's `CHECK` matches its Java enum

- **STATUS:** **OPEN** (raised JOB 15)
- **IMPACT:** Adding a value to a persisted enum without the matching migration produces a runtime
  constraint violation on the first write that uses it — in production, not in a test.
- **WHY IT IS STILL OPEN:** `AuditVocabularyMigrationTest` covers the vocabulary that drifts most.
  Generalising it across ~46 enum columns is a larger piece of work than JOB 15's slot allowed.
- **BLOCKS QAS?** No · **BLOCKS PRD?** No — it is a guard against a future mistake, not a present
  defect. Mitigated meanwhile by `PersistenceMappingTest` (every enum is stored by name).
- **RECOMMENDED CLOSURE:** Enumerate `@Enumerated` columns by reflection, read each table's `CHECK`
  from `information_schema`, assert set equality.

### D9 — No accessibility testing anywhere

- **STATUS:** **OPEN** (raised JOB 14)
- **IMPACT:** Unknown accessibility posture. No claim can be made either way.
- **WHY IT IS STILL OPEN:** Larger than a job slot. A meaningful axe pass wants every authenticated
  screen, which needs the 7 skipped E2E specs running first — so D9 is **downstream of a real
  environment**.
- **BLOCKS QAS?** No · **BLOCKS PRD?** **Potentially yes** where a public-sector or accessibility
  regulation applies. This must be a commercial decision, not a technical one.
- **RECOMMENDED CLOSURE:** See §12.

**Summary: 3 resolved (D1, D2, D7) · 1 deferred with reason (D4) · 5 open (D3, D5, D6, D8, D9).**
No debt was removed for being inconvenient.

---

## Architecture Decisions Requiring Awareness

### ADR-011 — Stop ETA and geofence observation

**What changed.** Stop ETA moved from *deferred* to *implemented*. `CLAUDE.md` had deferred "ETA
calculation, geofencing and automatic arrival detection"; ADR-011 moves **exactly one** of the three
and `CLAUDE.md` was updated to say so rather than left contradicting the code.

**Why the move is defensible.** V27 refused per-stop planned times with a reason, not a preference:
*"there is nothing to put in them."* That objection was about **inputs**, and every input now exists
— V38/ADR-010 supplies per-leg driving time with provenance, V14 supplies service time, V11 supplies
service windows. Departure + driving + service + window is an arrival time with every term stored and
none invented.

**What did not change.**

- **Geofence is observational.** A circle on a location and nothing more. No stored crossings, no
  column on `trip_stop` written from it, no transition it enables.
- **A GPS position does not move a lifecycle.** ADR-007's rule is intact. `actual_arrival_at` is
  written by whoever arrived.
- **Automatic arrival detection remains deferred**, and is now *harder* to add casually — the
  geofence exists and the rule that it may not move a lifecycle is written beside it.
- **ADR-007 remains in force** in full, including "no vendor adapter".

**Classification: ACCEPTABLE FOR CURRENT ARCHITECTURE.** The evidence continues to support it: the
ETA is computed by a pure function, stamped with the provenance of its weakest leg, and **absent
wherever a leg could not be measured** rather than guessed.

*This is a technical assessment. It is not a substitute for human architectural approval, which has
not been given.* If the decision is unwanted, ADR-011 is the single thing to reverse.

### Tender actor model

**Current state.**

- There is **no background tender advancement**. A waterfall moves from carrier A to carrier B when
  a dispatcher moves it.
- `requireAppUserId` refuses machine principals **by design**. An offer to a carrier is a commercial
  commitment and the audit trail must name who made it.
- **No fake system actor, hardcoded UUID or anonymous principal was created**, and that refusal was
  reaffirmed twice more during the chain.
- The integration path (a carrier answering over the API) records the acceptance without a person
  and deliberately **leaves `updated_by` alone** rather than overwriting a real name with null.

**What automating it correctly would require.** Not a flag — an actor model:

1. A first-class **system principal type** distinct from both human and integration-client, with its
   own identity and its own representation in `audit_event.actor_type`.
2. A **permission set for it**, so an automated advance is authorised rather than exempt.
3. A **policy record** — which waterfall, on whose authority, under what expiry — so an automatic
   offer traces back to a human decision to configure it.
4. **Idempotency and leader safety**, since a scheduler running on every node must advance a
   waterfall exactly once.

Until those exist, manual advance is the honest behaviour, not a missing feature.

### Carrier / vehicle semantics

Two fields that look similar and mean different things. Anyone reading a shipment must know both.

```
carrier_id
= carrier associated with the assigned vehicle
  (set by Trip.assignVehicle; the meaning it has always had)

accepted_carrier_id
= carrier that accepted the commercial tender
  (V42; null on almost every shipment, and null means
   "nothing contradicts carrier_id", not "unknown")
```

They may legitimately disagree — that is what subcontracting looks like before the truck is sorted
out — and the invariant is:

```
a mismatch prevents departure
```

The shipment may be planned, costed, edited and cancelled in that state. It may **not** depart.
Enforced in three layers: `TripExecutionService` (with a sentence a dispatcher can act on),
`Trip.dispatch`, and `ck_trip_departed_carrier_matches_vehicle`.

There is deliberately **no "resolve" action**: assigning one of the accepting carrier's vehicles is
what clears it, because the thing that fixes it is the thing that was missing.

---

## Explicitly Not Certified

**Read this section before quoting any other part of this document.**

### Deployment

```
No deployment was performed.
```

Nothing in this chain was deployed anywhere. The pre-existing repository record already stated the
deploy was unverified; that is unchanged.

### QAS

```
No QAS runtime certification was performed.
```

No shared environment was read from or written to. No Supabase hosted project was modified.

### Authenticated E2E

```
7 authenticated E2E tests were skipped because they require a real environment.
```

They are skipped by an explicit environment condition, not by a disabled assertion. **Whatever they
would prove is unproven by this assessment.**

### Performance

```
10,000 orders/day has not been load-tested.
```

The target is honoured in the schema (indexes, partial unique indexes, batched lookups, bounded
scans) and in the query patterns. **It has never been measured at any volume.** There is no
performance baseline document; `docs/operations/` does not exist.

### Accessibility

```
No formal accessibility verification exists.
```

No axe, no keyboard navigation tests, no focus-management tests, no contrast checks. MUI's defaults
are a reasonable starting posture and **are not evidence**.

### Production

```
PRD readiness has NOT been certified.
```

---

## QAS Readiness

```
READY_FOR_QAS_CODE_PROMOTION = YES
READY_FOR_PRODUCTION         = NO
```

These are deliberately different answers, and the reasoning differs.

**Why QAS promotion is recommended.**

| Criterion | Finding |
|---|---|
| Clean automated tests | 1684 / 97 / 34 with **0 failures and 0 skipped** in the backend; typecheck, lint and build clean |
| Flyway continuity | V1–V43 contiguous; each of V36–V43 in exactly one commit — no applied migration rewritten |
| Tenant / security | Composite FKs + RLS + service-layer scoping, now with static guards refusing bare-id reads and secret exposure |
| Open criticals | **None.** No open debt is a correctness defect in shipped behaviour |
| Open highs | D3 (delivered quantity) is a *missing capability the system does not claim to have*; performance and authenticated E2E are **unproven, not failing** |
| Skipped authenticated E2E | Exactly what QAS exists to resolve |
| Deployment verified | No — **which is the reason to promote to QAS, not a reason to withhold** |

The remaining unknowns are of a kind that a local build **cannot** resolve. Holding the code back
would not reduce risk; it would only delay learning.

**Why production is not recommended.**

1. **The deploy has never been verified** anywhere.
2. **No performance evidence at any volume**, against a stated 10,000 orders/day target.
3. **Settlement does not exist.** For a customer expecting freight audit, this is not a limitation —
   it is a missing product area.
4. **Delivered quantity does not exist** (D3), which blocks per-unit invoicing and shortfall credits.
5. **No accessibility evidence** (D9), which may be a compliance question depending on the customer.
6. **Observability is partial** and there is no runbook, alert catalogue or SLO.

---

## Proposed QAS Certification Checklist

To be executed **in a real environment**, by a person, before any production discussion.

### Positive path

- [ ] Login / Auth
- [ ] Company / Tenant isolation
- [ ] Order creation
- [ ] Ship Unit split *(partial allocation across two trips)*
- [ ] Planning V2
- [ ] Routing
- [ ] Rate quotation
- [ ] Planning total cost
- [ ] Tender creation
- [ ] Tender accept / reject
- [ ] Carrier / vehicle dispatch guard
- [ ] Dock appointment
- [ ] Vehicle availability
- [ ] Trip dispatch
- [ ] Tracking
- [ ] ETA
- [ ] Delivery
- [ ] POD
- [ ] Settlement — **cannot be executed; the capability does not exist.** Record as N/A, not as failed
- [ ] Control Tower
- [ ] Integration inbound
- [ ] Integration idempotency
- [ ] Webhook outbound
- [ ] Audit

### Negative path — the scenarios that prove the invariants

- [ ] Cross-tenant access denied *(another company's id on every read path)*
- [ ] Over-allocation denied *(allocate more than the order's remaining demand)*
- [ ] Dock double booking denied *(two overlapping bookings on one door, ideally concurrently)*
- [ ] Carrier / vehicle mismatch cannot depart *(accept a tender from carrier B on carrier A's truck, then try to dispatch)*
- [ ] Expired / rejected tender handled *(offer expires; waterfall does not advance on its own — expected, see D4)*
- [ ] Invoice discrepancy surfaced — **cannot be executed; settlement does not exist.** Record as N/A
- [ ] Unauthorized Control Tower metrics hidden / null *(a user without `orders.order:read` sees the screen and `null` — not `0` — for the unplanned backlog)*

### Additionally recommended, given this assessment

- [ ] Run the **7 skipped authenticated E2E specs** — the single highest-value QAS action
- [ ] Verify the deploy itself, which has never been verified
- [ ] Confirm ETA behaves with a real routing configuration, including the **unmeasurable-leg gap**
- [ ] Confirm geofence is inert without a tracking feed *(expected: no crossings, nothing moves)*

---

## Performance — Next Phase

**Not executed in this repair, by scope.** The minimum recommended plan:

### Volumes

```
100 orders
500 orders
1,000 orders
5,000 orders
10,000 orders/day operational profile
```

### Metrics to capture at each volume

```
planning duration
database query count
CPU
memory
routing cache hit rate
rating duration
unplanned orders
trip count
planning quality KPIs
```

### Where this system is most likely to bend first

Named from the code, so the first run measures the right things rather than discovering them:

- **The routing matrix.** `AutoPlanningService` resolves an N×N matrix before the engine runs. Cache
  hit rate is the figure that decides whether planning is fast or quadratic.
- **`ControlTowerService` workload ranking.** Utilisation cannot be ordered in SQL, so candidates are
  ranked in Java behind a `WORKLOAD_SCAN_LIMIT` of 400. Confirm real days stay inside it.
- **`ProposalPricer`.** One rate-card selection per proposed trip. Measure at 10,000 orders.
- **The webhook dispatcher.** `SKIP LOCKED` is designed for concurrent nodes; measure drain rate
  against burst production.
- **Planning KPI aggregation** over a full day's trips.

---

## Accessibility — D9 remains visible

**No accessibility verification exists, and MUI is not a substitute for it.** MUI's components carry
sensible defaults, and the component tests written in JOB 14 rely on real form labels
(`getByLabelText` only works because the labels exist) — but neither of those is an accessibility
audit, and neither should be quoted as one.

Recommended next stage, **not implemented in this corrective job**:

```
axe-core
keyboard navigation
focus management
screen reader smoke tests
contrast
form labels
drawer/modal focus trapping
```

Sequencing note: a meaningful pass needs every authenticated screen, which needs the 7 skipped E2E
specs running first. **D9 is downstream of a real environment**, which is one more reason QAS
promotion is the right next step.

---

## Final Assessment

```
ARCHITECTURE=              ENTERPRISE READY
DOMAIN_MODEL=              PRODUCTION READY WITH LIMITATIONS
AUTOMATED_TESTING=         ENTERPRISE READY
TENANT_SECURITY=           ENTERPRISE READY
DATABASE_MIGRATIONS=       ENTERPRISE READY
FRONTEND=                  PRODUCTION READY WITH LIMITATIONS
INTEGRATIONS=              PRODUCTION READY WITH LIMITATIONS
OPERABILITY=               PARTIAL
PERFORMANCE_EVIDENCE=      NOT IMPLEMENTED
ACCESSIBILITY_EVIDENCE=    NOT IMPLEMENTED
DEPLOYMENT_EVIDENCE=       NOT IMPLEMENTED

OVERALL_ENTERPRISE_READINESS=   PRODUCTION READY WITH LIMITATIONS
QAS_PROMOTION_RECOMMENDATION=   PROMOTE
PRODUCTION_RECOMMENDATION=      DO NOT PROMOTE
```

**Reading of the above.** The engineering is strong where it has been built and honest about where it
has not. `DOMAIN_MODEL` is held at *production ready with limitations* rather than *enterprise ready*
for two specific reasons — **settlement does not exist** and **delivered quantity is not captured** —
not because the model that does exist is weak. `OPERABILITY` is `PARTIAL` because metrics stop short
of planning, rating and tendering and there is no runbook.

The three `NOT IMPLEMENTED` lines at the bottom are all the same kind of thing: **evidence that a
local build cannot produce**. They are the agenda for QAS, and they are the reason production is not
on the table yet.
