# JOB 13 - Integration Ops

**RESULT = PASS** · **STOP_CHAIN = false** · **MIGRATION = none**

| | |
|---|---|
| Started | 2026-08-28 05:49 America/Lima |
| Completed | 2026-08-28 05:59 America/Lima |
| HEAD before | `83d3917` |
| Backend, `./mvnw clean test` | **1669 pass, 0 fail, 0 skipped** |
| Frontend, `vitest run` | **82 pass** |
| E2E, `playwright test` | **34 pass, 7 skipped** |
| Typecheck / lint / build | clean |
| Flyway | V1-V43, contiguous, unchanged |
| Retries | 3 attempted, 3 recovered |

---

## What was already there, and what actually wasn't

The inspection came first, and it changed the job. Integration ops turned out to be **largely
built**: outbound has subscriptions, a delivery list, a delivery detail with attempt history, a
single-delivery retry, activate/deactivate, secret rotation and a `SKIP LOCKED` dispatcher safe to
run on every node. Inbound has an integration-client list, a per-client request inbox and typed
outcomes.

Building a second version of any of that would have been busywork. What was missing is the thing
those two lists cannot do: **answer a question**. An operator opening the screen after a bad night
had to page through deliveries to find out whether anything was wrong.

So JOB 13 is one endpoint - `GET /webhooks/health` - and the two signals it carries that the lists
genuinely could not.

## The two signals worth the job

**1. Age, not count.** A webhook queue with a thousand pending deliveries that is draining is
healthy. One with three that have been waiting since Tuesday is not. **A count cannot tell those
apart**, so `oldestPendingAt` sits beside `deliveriesPending` and is what "stuck" actually looks
like.

**2. The failure that looks like silence.** Deactivating a subscription stops deliveries and
discards nothing - events keep queueing, exactly as the deactivate endpoint documents. A partner
switched off "for an hour" during an incident and never switched back on therefore produces **no
errors at all**: no failed deliveries, no error rate, nothing on any existing screen.
`inactiveSubscriptionsWithBacklog` is the only place that shows up.

Beside them: exhausted retries counted apart from pending (they are a work queue, not a statistic),
and inbound `REJECTED` kept apart from `FAILED` - one is the partner's payload being wrong, the
other is TMS not coping, and they are different phone calls.

Inbound counts are **windowed to 24 hours**, not lifetime. A partner that failed a hundred times
last month and has worked all week is working, and a lifetime count would say otherwise forever.

---

## Defect found and fixed: 1, and it is the reason `clean test` is the gate

My cross-entity JPQL for the inactive-subscription count was invalid:

```
SELECT COUNT(DISTINCT d.subscriptionId) FROM WebhookDelivery d, WebhookSubscription s WHERE ...
```

`WebhookDelivery` has no `subscriptionId` field - it holds a `@ManyToOne` association. **`mvnw
compile` passed.** The failure appeared only when Spring validated the query at context startup, and
it took down *every* integration test in the suite: 323 errors from one bad string, because the
repository is on the dependency path of the shipment event publisher.

This is exactly the class of failure the "Maven incremental is not evidence" rule exists for, and it
is the second time this chain has been saved by running `clean test` rather than trusting a compile.
Fixed by traversing the association (`d.subscription.id`), which is what the mapping actually
supports.

## Deliberately not built

* **No bulk retry.** Retrying forty failed deliveries at once is easy to add and easy to regret: the
  reason they failed is usually still true, and a button that re-queues everything turns one broken
  endpoint into forty more attempts against it. The per-delivery retry stays, and the health count
  tells an operator how big the problem is before they act on it.
* **No health for the scheduler itself.** Whether the dispatcher thread is alive is an infrastructure
  question and belongs to the actuator, not to a tenant-scoped API. A company must not learn about
  another company's worker from its own health endpoint.
* **No stored health snapshot.** Nothing here is a fact worth keeping - it describes this minute, and
  a stored copy would be one more thing that can be stale while looking current.

---

## Test counts

Backend **1661 → 1669** (+8). Frontend **79 → 82** (+3). E2E **34 pass / 7 skipped**, unchanged -
the health strip sits on the existing integrations screen.

The frontend test deliberately asserts the *reading rule* rather than the fetch: that a large moving
queue and a small stuck one are not ordered correctly by the count, and are by the age. If somebody
later simplifies the panel to "N pending", that test says what is lost.

---

## Open debt register

| # | Debt | State |
|---|---|---|
| **D1** | Proposal not priced | **CLOSED (JOB 11)** |
| **D2** | Accepted tender vs vehicle owner | **CLOSED (V42)** |
| **D3** | Delivered quantity | **OPEN, formally evaluated** |
| **D4** | No system-actor model | **DEFERRED_WITH_REASON** |
| **D5** | No work assignment | **OPEN** |
| **D6** | No own-fleet cost model | **OPEN** |
| **D7** | Control Tower V1 untested | **OPEN** |

No new debt. The health endpoint is additive and every query is read-only and company-scoped.

---

## Files

**Backend** new `integration.application.IntegrationHealthService` / `IntegrationHealthView`;
changed `WebhookDeliveryRepository` (three read-only queries), `IntegrationRequestRepository` (one),
`WebhookController` (the endpoint)

**Tests** `IntegrationHealthServiceTest` (new, 8)

**Frontend** `integrationsApi` (`IntegrationHealthView`, `fetchIntegrationHealth`),
`OutboundPanel` (the health strip), `integrationHealth.test.ts` (new)

---

**NEXT_JOB** - **JOB 15 - Hardening**, which the brief ranks above JOB 14 (UX).
