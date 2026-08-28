# JOB 24 — Observability & Operations Completion

**RESULT = PASS** · **MIGRATION = none**

---

## 1. Gates

| Gate | Result |
|---|---|
| `./mvnw clean test` | **1842 pass · 0 fail · 0 error · 0 skipped** (1840 → 1842, **+2**) |
| Frontend | unchanged — no frontend change in this job |
| Flyway | **V1–V48**, unchanged |

## 2. What was actually missing

Observability was **not absent**. Actuator was configured with real security discipline, a
correlation id filter existed, and **twelve business metrics were already emitted**. What was missing:

1. **`docs/operations/` did not exist at all** — flagged in `TMS_ENTERPRISE_READINESS.md` and never
   addressed.
2. **Phase 2's new capabilities emitted nothing.** Settlement (JOB 20) and own-fleet costing
   (JOB 22) had no signal.
3. **Nothing kept a metrics document honest.**

## 3. Two metrics, both chosen for 02:00

| Metric | Tags | Why it earns its place |
|---|---|---|
| `tms.settlement.decisions` | `approved` / `rejected` | A rising **rejection rate** means a carrier is billing off an old tariff or a tolerance is too tight. **Invisible** in a single "invoices processed" figure |
| `tms.costing.own_fleet.quotes` | `costed` / `incomplete` / `no_profile` / `no_vehicle` / `not_own_fleet` | A **configuration** signal. `no_profile` climbing after a fleet expansion is somebody having added trucks and not their rates |

**Neither carries an amount, a company id, or a carrier's name.** `/actuator/metrics` is not an
authorisation boundary — anything scraping it sees every value — so what a company pays its carriers
stays in the database where RLS covers it.

**A second click on an already-approved invoice is not counted.** The metric answers "how many
expenditures were authorised", and the idempotent path authorised nothing. Counting it would
reproduce JOB 20's double-approval defect in the telemetry after fixing it in the data.

**Five outcomes on the quote metric, not two.** "Nobody configured this truck" and "we could not
measure the route" are different jobs for different people, and one `failed` would send both to
whoever read the dashboard first.

## 4. Three operations documents

| | |
|---|---|
| `docs/operations/OBSERVABILITY.md` | Every metric, what a change means, and **§5 "What none of this tells you"** |
| `docs/operations/RUNBOOK_INCIDENTS.md` | Eight failure modes, each traceable to a real constraint or guard |
| `docs/operations/DEPLOYMENT.md` | Opens with **⚠ NO DEPLOYMENT HAS BEEN VERIFIED** |

Every runbook entry describes a failure the system can **actually** have. The two most useful are the
ones that say *do not do the obvious thing*:

- **A constraint violation reaching a user is a missing service refusal — never drop the
  constraint.** It is the thing that stopped the bad data.
- **An unpriced plan is the system refusing to publish a number it cannot stand behind.** Do not
  "fix" it with a placeholder tariff of zero: zero makes that option unbeatable in every comparison,
  which is worse than no number.

## 5. `MetricCatalogueTest`, and what it caught immediately

A metric catalogue is exactly the document that is true when written and wrong three jobs later. The
failure is **silent** and is discovered at 02:00 by whoever needed the signal it promised.

So it is a test, in both directions: every emitted metric must be documented, and the document may
name none that does not exist.

**It failed on the first run against my own document**, and the diagnosis is the point:

- I documented `tms.routing.provider.calls` **as a timer**. It is a counter — the timers are
  `tms.routing.provider.duration` and `tms.routing.matrix.duration`, and I had documented neither.
- I abbreviated two rows as `raised` / `.suppressed`, so two real metrics were undocumented by the
  guard's reading — and by a reader's.

**A hand-written catalogue was wrong within an hour of being written.** That is the case for the
test, made by the test.

## 6. Deliberately not done

| | Why |
|---|---|
| A custom health indicator for integrations | **A stale partner feed must not take TMS out of a load balancer.** `IntegrationHealthService` answers it on its own endpoint and stays out of `/actuator/health` |
| Alert thresholds and routing | There is no alerting destination, no on-call rotation and no environment to alert about. Thresholds nobody receives are configuration theatre |
| Distributed tracing | A modular monolith with a correlation id per request. Spans would be scaffolding for services that do not exist |
| Metrics with amounts or tenant tags | `/actuator/metrics` has no authorisation. This is a disclosure boundary, not an oversight |
| A rollback procedure | **Recorded as the largest unaddressed operational risk instead.** Schema changes are forward-only; there are no down-migrations, and whether a previous application version tolerates the current schema is unanswered per migration. Writing a procedure nobody has executed would be worse than the gap |

## 7. Still true, and stated in the documents

```
NO DEPLOYMENT HAS BEEN VERIFIED
The 7 authenticated E2E specs have never executed
No performance baseline exists (JOB 25)
No alerts exist - every incident starts with a human noticing
No rollback procedure has been designed or tested
```
