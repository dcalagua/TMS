# Observability

**What TMS emits, what each signal means, and — the part that matters — what none of them tell you.**

---

## 1. Endpoints

| Endpoint | Answers | Exposed |
|---|---|---|
| `/actuator/health` | Is the process up and the database reachable | Yes, `show-details: never` |
| `/actuator/health/liveness` | Should the orchestrator restart me | Yes |
| `/actuator/health/readiness` | Should traffic be routed to me | Yes |
| `/actuator/info` | Which build is this | Yes, `info.env.enabled: false` |
| `/actuator/metrics` | The counters below | Yes, values only |
| `/api/v1/system/info` | Build identification, authenticated | Yes |

**Nothing else is exposed, deliberately.** No `beans`, `env`, `configprops` or `mappings` — those
list the running configuration, which is how a connection string ends up in a screenshot.
`show-details: never` and `info.env.enabled: false` are the two settings that keep it that way; both
have a comment in `application.yml` saying so, and neither should be relaxed to debug something.

**`/actuator/metrics` is not an authorisation boundary.** Anything that can scrape it sees every
value. That is why no metric below carries an amount, a company id, or a carrier's name.

## 2. Business metrics

**Fourteen signals.** Each was added because somebody would need it at 02:00, not to make a
dashboard look full. `MetricCatalogueTest` fails the build if this table and the code disagree in
either direction — it caught two wrong names in the first version of this document.

| Metric | Tags | What a change means |
|---|---|---|
| `tms.appointments.bookings` | `outcome` | Refusals climbing = docks oversubscribed, or opening hours wrong |
| `tms.audit.events` | `outcome` | A drop to zero while traffic continues means the audit trail stopped, which is a security incident |
| `tms.integration.requests` | `outcome`, `provider` | The first place a partner's broken client shows up |
| `tms.notification.raised` | | Notifications actually created |
| `tms.notification.suppressed` | | Deduplicated. A high suppressed:raised ratio means the window is doing its job; **both at zero during business hours means notifications are dead** |
| `tms.routing.lookups` | `outcome` | `miss` climbing = the cache is cold or locations are ungeocoded |
| `tms.routing.provider.calls` | `outcome`, `provider` | Calls that actually left the process |
| `tms.routing.provider.duration` | `provider` | Timer. Latency here becomes planning latency |
| `tms.routing.matrix.duration` | | Timer. One planning run's whole distance resolution |
| `tms.tracking.positions` | `outcome` | Positions accepted or rejected. **Zero is normal** — ADR-007 ships no vendor adapter |
| `tms.tender.waterfall` | | Waterfalls started and ended |
| `tms.tender.waterfall.advances` | | Steps to the next carrier. Zero while waterfalls run means none are advancing — see debt D4, there is no automatic advance |
| **`tms.settlement.decisions`** | `outcome` = `approved` / `rejected` | **JOB 24.** Rejections climbing = a carrier billing off an old tariff, or a tolerance tuned too tight. Invisible in a single "invoices processed" figure |
| **`tms.costing.own_fleet.quotes`** | `outcome` = `costed` / `incomplete` / `no_profile` / `no_vehicle` / `not_own_fleet` | **JOB 24.** A *configuration* signal. `no_profile` climbing after a fleet expansion is somebody having added trucks and not their rates |

### Reading `tms.costing.own_fleet.quotes`

This is the metric most likely to be misread, so:

`no_profile` and `incomplete` are **not errors**. The system is correctly refusing to invent a cost
(V48). But every one of them is a shipment planning could not compare against a carrier's price, so
somebody is deciding with less information than they think. **The ratio is the signal, not the
count.**

## 3. Correlation

Every request carries a correlation id (`CorrelationIdFilter`), it appears in every log line, and it
is echoed on error responses. **When a user reports a problem, ask for the id on the error screen** —
it is the difference between finding their request and grepping a day.

## 4. What the logs will not contain

By design, and enforced:

- **No secrets.** JOB 15 added a static guard that fails the build if one could reach a view.
- **No stack traces to callers.** `include-stacktrace: never`, `include-message: never`.
- **No personal data beyond what the request already carried.** V26 split driver personal data behind
  its own permission and logging does not undo that.

## 5. What none of this tells you

Stated because an observability document that lists only what exists is half a lie.

- **Nothing measures business volume against the 10,000 orders/day target.** There is no counter of
  orders planned per day, and no performance baseline anywhere — see JOB 25.
- **There is no tracing.** One correlation id per request, no spans, no cross-service timing. The
  monolith makes that survivable; it also means a slow request tells you it was slow and not where.
- **There are no alerts.** No thresholds, no routing, no on-call rotation. These metrics are emitted
  and nothing watches them.
- **No custom health indicator exists.** `/actuator/health` reports the process and the database.
  It does **not** know whether integrations are stale, whether routing is degraded, or whether the
  overnight import ran. `IntegrationHealthService` (JOB 13) answers the first of those and is **not**
  wired into the health endpoint, deliberately: a stale partner feed must not take the application
  out of a load balancer.
- **None of it has been observed in a running deployment.** Everything here is read from the code and
  from local runs. See `DEPLOYMENT.md`.
