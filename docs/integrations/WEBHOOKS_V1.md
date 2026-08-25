# TMS by EBIM - Outbound Webhooks v1

Push delivery of this company's published events to an endpoint the customer owns, signed and
retried, with every attempt recorded.

Introduced by job 13. It is the "next step" `docs/integrations/OUTBOUND_SHIPMENT_V1.md` §8 named
and deliberately left unbuilt: the transactional outbox that migration V20 created is unchanged,
and this adds a dispatcher in front of it. **A partner who polls keeps polling.** Nothing about
`GET /integration/v1/shipments/events` changes, and the two mechanisms describe the same facts
with the same identifiers, so moving from one to the other costs neither a gap nor a duplicate.

- Configuration: `/api/v1/webhooks`, from the Integration Hub in the browser
- Delivery: `POST` from TMS to the customer's URL, HTTPS only
- Signature: HMAC-SHA-256, `X-TMS-Signature: t=<unix>,v1=<hex>`
- Schema: Flyway migration `V35__integration_webhooks.sql`
- Off unless configured: see [§9](#9-configuration)

---

## 1. What this is, and what it is not

A webhook subscription is **an address the customer owns**, not a partner credential. It is
deliberately *not* attached to an `integration_client`:

- a credential is how a partner authenticates **into** TMS;
- a subscription is where TMS sends data **out**.

The receiving system may have no reason to ever call TMS at all - an ERP that only wants to know
when a shipment was confirmed needs an endpoint, not a key. Coupling the two would have forced
every receiver to hold an inbound credential it never uses.

They are separate permissions for the same reason, and the reason is that they fail in opposite
directions. Mismanaging a credential lets somebody write orders into this company. Mismanaging a
subscription sends this company's shipment numbers to an address of the administrator's choosing.

| | Inbound credential | Webhook subscription |
|---|---|---|
| Table | `tms.integration_client` (V18) | `tms.webhook_subscription` (V35) |
| Permissions | `integration.client:read` / `:manage` | `integration.webhook:read` / `:manage` |
| Secret | SHA-256 hash, never recoverable | **encrypted**, recoverable by the server ([§5](#5-the-signing-secret)) |
| Direction | partner → TMS | TMS → partner |

**This is not an ESB.** There is no transformation, no routing language, no fan-in, no ordering
guarantee beyond "each event is attempted in the order it was queued". A receiver that needs more
than "something happened, go and read it" reads the shipment.

---

## 2. Tenancy

A subscription belongs to exactly one company, set when it is created and changed by no operation.
The company comes from the administrator's own `CompanyScope`, never from the request body, so
creating a subscription for another tenant is not an operation that exists.

Every delivery carries the company of the subscription it belongs to, and
`fk_webhook_delivery_subscription_company` makes a delivery of company A under a subscription of
company B impossible in the database - the same composite-foreign-key idiom V18 uses between
`integration_request` and `integration_client`.

Row Level Security applies to every browser-side read of these tables (ADR-005). The **dispatcher**
is the documented exception: it runs on a background thread with no security context, so its
connection is never switched to `tms_app` and RLS does not filter it. That is what lets one worker
drain every company's queue instead of needing a scheduled job per tenant, and it is why the
dispatcher's writes are made against the company id carried on each row rather than against an
ambient scope there is none of. See `TenantScopedDataSource`.

---

## 3. The event envelope

Every delivery is a `POST` with this body:

```json
{
  "apiVersion": "v1",
  "id": "3f0a0e2c-9c4b-4b1e-9a6f-0f6b1c2d3e4a",
  "type": "SHIPMENT_CONFIRMED",
  "occurredAt": "2026-08-21T10:15:30Z",
  "companyId": "6b1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
  "resource": {
    "type": "shipment",
    "id": "9a6f0f6b-1c2d-3e4a-5b6c-7d8e9f0a1b2c",
    "reference": "SH-00001234"
  }
}
```

**It carries no business detail on purpose.** It says what happened, to which thing, and when; the
receiver then calls `GET /integration/v1/shipments` and acts on what TMS believes *now*. This is
the same decision `tms.shipment_outbox_event` made in V20 for the polling feed, and it buys two
things:

1. **No stale copy.** A retry three hours later carries a three-hour-old snapshot if the snapshot
   is embedded. A receiver acting on it would act on quantities that have since changed.
2. **No personal data on the wire.** A webhook target is a URL an administrator typed. Customer
   names and addresses do not go to it.

`id` is the published fact's own id - the outbox row's id, the same value the polling feed returns.
It is stable across every attempt and every redelivery, and it is what a receiver deduplicates on.

`apiVersion` is in the body as well as implied by the contract because receivers store these and
read them back later, out of a queue or a dead-letter table, at which point the URL is gone.

**Compatibility rule:** fields may be added within `v1`; a receiver must ignore what it does not
recognise. A field is never removed or given a new meaning. A breaking change is a new version.

### Event types

The vocabulary is exactly the change feed's, and `GET /api/v1/webhooks/event-types` serves it so a
type added by a migration appears in the picker without a frontend release.

| Type | When |
|---|---|
| `SHIPMENT_CONFIRMED` | The plan became a committed shipment |
| `SHIPMENT_READY` | Loaded and ready to leave |
| `SHIPMENT_DISPATCHED` | It left |
| `SHIPMENT_COMPLETED` | It finished |
| `SHIPMENT_CANCELLED` | It was cancelled |
| `SHIPMENT_CHANGED` | Reserved; nothing produces it yet (see `OUTBOUND_SHIPMENT_V1.md` §6) |
| `DELIVERY_RESULT_RECORDED` | What was handed over at a stop was recorded, or corrected |
| `TENDER_SENT` / `TENDER_ACCEPTED` / `TENDER_REJECTED` / `TENDER_EXPIRED` / `TENDER_CANCELLED` | The five tender transitions (`docs/domain/CARRIER_TENDERING_V1.md`) |

A subscription selecting nothing receives nothing, so the API refuses one: "everything" is spelled
by selecting every type, never by selecting none.

---

## 4. Headers

| Header | Value |
|---|---|
| `Content-Type` | `application/json; charset=utf-8` |
| `X-TMS-Event-Id` | The `id` from the body, so a receiver can deduplicate before parsing |
| `X-TMS-Event-Type` | The `type` from the body, so a receiver can route before parsing |
| `X-TMS-Delivery-Id` | This delivery's own id - quote it in a support request |
| `X-TMS-Delivery-Attempt` | `1` on the first try, `2` on the first retry, and so on |
| `X-TMS-Signature` | `t=<unix seconds>,v1=<hex HMAC-SHA-256>` - see below |
| `X-Correlation-Id` | The dispatcher pass's trace id, which also appears on every TMS log line for it |
| `User-Agent` | `TMS-by-EBIM-Webhooks/1.0` |

---

## 5. The signing secret

### Verifying a delivery

The signed material is `"<t>.<raw request body>"` - the timestamp from the header, a full stop, and
the body **exactly as received**, before any JSON parsing or re-serialisation.

```
signature = hex( HMAC-SHA-256( secret, t + "." + rawBody ) )
```

A receiver should:

1. read `t` and `v1` from `X-TMS-Signature`;
2. reject the delivery if `t` is more than a few minutes from its own clock (five is the usual
   choice) - this is the replay window;
3. recompute the HMAC over `t + "." + rawBody` and compare **in constant time**;
4. only then parse the body.

The timestamp is inside the signed material precisely so that step 2 cannot be defeated by editing
the header: changing `t` breaks the MAC. Signing the body alone would produce a signature that
stays valid forever, and anyone who captured one delivery could replay it at any later moment.

The `v1=` prefix exists so a second algorithm can be introduced by sending both for a while rather
than by breaking every receiver on one deployment day.

### Why the secret is encrypted rather than hashed

Every other secret in TMS is hashed and never recovered - `IntegrationSecrets` explains at length
why, and that remains the rule wherever the server only has to answer *"is this the right value"*.

A webhook signature is the case where it cannot be the rule: the server **computes** an HMAC from
the secret on every send, so a value it cannot read is a value it cannot sign with. The choice is
between encrypting the secret and having no signatures at all, and unsigned webhooks are worth very
little - a receiver that cannot tell TMS from anyone who learned its URL is not authenticating the
sender.

So it is AES-256-GCM under one deployment key (`tms.integration.webhooks.secret-key`), fresh IV per
encryption, authenticated so that ciphertext edited in the database fails to decrypt rather than
silently producing a wrong secret. **What this defends against is stated plainly and not more:** a
database dump alone, a stolen backup, a read-only SQL injection. It does not defend against an
attacker who already holds the application's configuration, and no design that keeps the server able
to sign could. Keep the key in the secret store, not beside the connection string.

The secret is shown **once**, by the response that creates or rotates it. Afterwards only its last
four characters are ever rendered.

### Rotation has no grace window

Deliberately unlike an inbound credential's rotation, which keeps the superseded secret working for
seven days. The asymmetry follows from the direction: TMS *verifies* inbound secrets, so it can
afford to accept two at once; TMS *produces* webhook signatures, and sending two would mean the
receiver had to accept either - which is precisely the property a rotation removes.

The receiver's own migration path is to accept both secrets on their side for as long as their
deployment needs, which is theirs to schedule.

---

## 6. Delivery, retries and suspension

### The three phases

Nothing that touches the network holds a database transaction.

1. **Fan-out** - in the *same* transaction as the business change. `ShipmentEventPublisher` writes
   the outbox row and then, through `EventFanoutPort`, one `PENDING` delivery per interested
   subscription. Two indexed inserts. If the confirmation rolls back, so do the obligations.
2. **Claim** - a short transaction locks a batch of due rows with `SELECT … FOR UPDATE SKIP LOCKED`,
   leases each of them past the length of one attempt, copies out what the send needs, and commits.
3. **Send, then record** - the HTTP call happens with no transaction and no lock held; one short
   transaction per delivery then writes the attempt row and applies the outcome.

The alternative - one transaction around the whole batch - is what a first draft usually looks like,
and it means a receiver that accepts the connection and then goes quiet holds a lock for its entire
timeout while every other delivery waits behind it.

`SKIP LOCKED` plus the lease is also what makes the dispatcher safe on **every** node without a
leader election: two instances take disjoint batches, and a node dying mid-attempt simply lets its
lease expire. The cost is that such a delivery may be attempted twice, which is why the contract is
at-least-once and the receiver deduplicates on the event id.

### What each response means

| Response | Outcome | Retried |
|---|---|---|
| `2xx` | `DELIVERED` | – |
| `5xx` | `RETRYABLE_FAILURE` | yes |
| `408`, `425`, `429` | `RETRYABLE_FAILURE` | yes |
| any other `4xx`, including `410 Gone` | `PERMANENT_FAILURE` | no |
| `3xx` | `PERMANENT_FAILURE` | no - see below |
| timeout, refused connection, DNS failure, TLS failure | `RETRYABLE_FAILURE` | yes |

**Redirects are never followed.** Following one on a signed POST would re-send this company's data,
with its signature, to a location no administrator approved - the receiver re-opening the hole
[§7](#7-what-a-target-url-may-be) exists to close.

**Response bodies are discarded.** TMS records the status line. Nothing a receiver writes can reach
a TMS log or an error field, and an endpoint that answers with a megabyte of HTML costs nothing.

### The retry ladder

Exponential from `retry-base-delay`, doubling, capped at `retry-max-delay`, for at most
`max-attempts` attempts. With the shipped defaults - one minute, doubling, capped at thirty, six
attempts - a delivery is tried at roughly **0, 1, 3, 7, 15 and 45 minutes** after the event and then
marked `FAILED`. That spans a normal deployment window on the receiving side without turning a
permanently dead endpoint into a week of traffic.

**No jitter, deliberately.** Jitter spreads a herd of clients retrying in lockstep against one
server. Here the retrying client is one dispatcher and the servers are as many as there are
customers, so there is no herd - and what jitter would cost is a schedule that cannot be asserted in
a test or explained to an integrator asking why their retry arrived when it did.

### Suspension

After `suspend-after-consecutive-failures` (default **10**) deliveries in a row have exhausted their
attempts, the subscription is switched off and says so on the screen. The count is of *exhausted
deliveries*, not attempts, which is the difference between "this endpoint has been dead for hours
across many events" and "this endpoint was restarting during a deployment". Any delivered attempt
resets it to zero.

Reactivating clears both the reason and the count. **Nothing is discarded while an endpoint is off:**
events keep queueing and are released when it comes back, which is what an operator expects from a
pause. A queued delivery whose subscription is inactive is simply pushed further out rather than
failed.

### Retrying by hand

`POST /api/v1/webhooks/deliveries/{id}/retry` puts a finished delivery back in the queue - what an
operator presses once the receiving side is fixed. It refuses a delivery that is still pending, and
one whose subscription is inactive.

The attempt count is **not** reset. Attempt numbers stay unique and monotonic, so the attempt log
reads as one history, and a delivery whose schedule was already exhausted buys exactly one more
attempt per press rather than a fresh ladder of six against an endpoint that has already refused it
six times.

---

## 7. What a target URL may be

A webhook target is a URL supplied by a user that **the server** then fetches. That is the
definition of a server-side request forgery primitive, and the addresses worth forging a request to
are exactly the ones a company administrator should never be able to reach: the cloud provider's
instance metadata endpoint at `169.254.169.254`, an internal admin port, a database's HTTP
interface, a neighbouring service that trusts anything from inside the network.

`WebhookTargetPolicy` therefore refuses:

- anything that is not `http`/`https`, and `http` unless the deployment opted in;
- credentials in the URL (they would be stored and echoed into error messages);
- a fragment (never sent on the wire, so accepting one stores something that silently does nothing);
- a host that resolves to loopback, link-local, RFC 1918, carrier-grade NAT (`100.64/10`), IPv6
  unique local (`fc00::/7`), multicast or the wildcard address;
- a host that will not resolve at all - refused rather than saved, because an endpoint TMS cannot
  resolve now is an endpoint every delivery would fail against.

The check runs when the subscription is saved **and again immediately before each send**, which is
what closes the obvious hole: a hostname that resolves publicly at save time and to an internal
address an hour later. It still cannot close the last millisecond between the check and the socket
connect, because the JDK's HTTP client resolves the name itself. A deployment that needs that
guarantee puts an egress proxy in front of the application, which is where a network control belongs
anyway. Stating the residual risk is part of the control.

---

## 8. What is stored, and what an operator can see

| Table | Holds |
|---|---|
| `tms.webhook_subscription` | Name, target, selected events, encrypted secret, health counters |
| `tms.webhook_subscription_event` | One row per selected event type |
| `tms.webhook_delivery` | One event owed to one subscription: status, attempt count, next attempt, last outcome, and the **frozen payload** |
| `tms.webhook_delivery_attempt` | Every HTTP call, in order: when, how long, what came back |

`uq_webhook_delivery_subscription_event` is idempotency at the only layer that can guarantee it: one
event reaches one subscription once. A fan-out that ran twice collides here instead of
double-delivering.

The payload is stored rather than rebuilt per attempt so that a retry is **byte-identical** to the
first try - which is what keeps the signature verifiable and the receiver's deduplication honest.

Neither deliveries nor attempts are ever deleted or edited by the application; there is no `DELETE`
grant on either. Retention is an operations concern, exactly as it is for `tms.integration_request`.

The Integration Hub's **Outbound** tab shows all of it: the endpoints with their health, the delivery
log filterable by endpoint and status, and - per delivery - every attempt and the exact bytes that
were sent. That last screen is what a "you never sent us that shipment" conversation is settled from.

---

## 9. Configuration

Under `tms.integration.webhooks`. **There is no `enabled` flag:** the feature is on exactly when
`secret-key` is set, because without a key TMS cannot store a signing secret and therefore cannot
have a subscription to deliver to. A boolean beside it could only create a state where the feature
says it is on and every call fails. A deployment that leaves it unset keeps the polling change feed
and loses nothing else; the API answers `503` with
`urn:tms:problem:feature-not-configured` if somebody tries to create a subscription.

| Setting | Default | Notes |
|---|---|---|
| `secret-key` | *(unset)* | ≥32 characters of high-entropy material from the secret store. Losing it does not lose the subscriptions, only their secrets: rotating each one re-issues under the new key |
| `max-attempts` | `6` | Clamped to 12 |
| `retry-base-delay` | `1m` | |
| `retry-max-delay` | `30m` | Raised to the base delay if configured below it |
| `request-timeout` | `10s` | A receiver needing longer is doing work inside the request instead of acknowledging and queueing |
| `batch-size` | `50` | A bound on one pass, not a throughput setting - the pass simply runs again |
| `poll-interval` | `15s` | The floor on how late a first delivery can be |
| `suspend-after-consecutive-failures` | `10` | Exhausted deliveries, not attempts |
| `allow-insecure-targets` | `false` | **Development only.** Accepts `http://`. A body carries operational data and a signature is not encryption |
| `allow-private-network-targets` | `false` | **Development only.** Accepts internal addresses - see [§7](#7-what-a-target-url-may-be) |

The application logs its webhook policy on startup, and warns loudly if either of the last two is on.

---

## 10. Tests

| Test | Proves | Needs Docker |
|---|---|---|
| `WebhookEventTypeTest` | The subscribable vocabulary still matches what the outbox publishes - the guard on two deliberately duplicated enums | no |
| `WebhookSecretsTest` | The signature scheme is what this document says: format, determinism, timestamp binding, verification | no |
| `WebhookSecretCipherTest` | Round trip, fresh IV per encryption, wrong key fails, edited ciphertext is detected, weak key refused | no |
| `WebhookBackoffTest` | The retry ladder, its cap and its exhaustion | no |
| `WebhookAttemptOutcomeTest` | Which HTTP answers are retried, including 410 and redirects | no |
| `WebhookTargetPolicyTest` | The SSRF control, including the metadata address and the less obvious private ranges | no |
| `WebhookSubscriptionTest` / `WebhookDeliveryTest` | Event-type diffing, suspension and reactivation, delivery state transitions, manual requeue | no |
| `WebhookDispatchServiceTest` | Headers, signature over the sent bytes, outcome classification, the send-time target re-check, batch behaviour | no |
| `IntegrationsPage.test.tsx` | The hub renders both directions, hides what a caller may not manage, never shows a usable secret | no |
| Fan-out and queue persistence | One delivery per interested subscription, in the confirmation's transaction; `SKIP LOCKED` claiming | **yes** (Testcontainers) |

The split is deliberate: every rule an integrator depends on is asserted without a database, because
a machine with no Docker must still be able to prove the contract has not changed.

---

## 11. What v1 deliberately does not do

- **No per-event filtering beyond the type.** "Only shipments for customer X" is a rule TMS would
  have to evaluate per delivery, and a receiver can drop what it does not want.
- **No ordering guarantee.** Deliveries are attempted in queue order, but a retry of an earlier
  event can land after a later one. Receivers order by `occurredAt`, which is the business fact's own
  time.
- **No dead-letter replay in bulk.** A failed delivery is retried one at a time from the screen. A
  bulk replay is a straightforward addition on top of the same table when somebody needs it.
- **No inbound callbacks.** TMS signs what it sends; it does not verify signatures on anything it
  receives, because the inbound API authenticates with a credential instead.
- **No non-shipment events.** The tables are generic - `event_id`, `event_type`, `resource_*` - so a
  future family costs no migration to the delivery side, but today the only publisher is the shipment
  outbox.
