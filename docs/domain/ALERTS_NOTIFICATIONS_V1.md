# Alerts and notifications V1

Migration: `V32__notification.sql`. Module: `com.ebim.tms.notification`.
Port other modules raise through: `com.ebim.tms.shared.notification.NotificationPublisher`.
Screen: the bell in the top bar (`NotificationsMenu`), on every page.
API: `GET /api/v1/notifications`, `POST /api/v1/notifications/{id}/read`,
`POST /api/v1/notifications/read-all`.

## 1. What this is, and what it deliberately is not

By V31, TMS knew a great deal that nobody was told. A shipment left ninety-five minutes late and the
fact sat in `tms.trip.actual_departure_at`. A carrier let an offer lapse and the row quietly became
`EXPIRED`. A customer refused a pallet and the delivery record said so on a screen nobody had open.
Every one of those is a phone call somebody should have made that afternoon, and the product's
answer was "open the control tower and look".

V1 answers one question: **what happened in this company that somebody should look at?**

It is **in-app only**. There is no email, no SMS, no WhatsApp and no webhook, and section 10 says
what each would need first. It is also not:

| Not | Because |
|---|---|
| the audit trail (`tms.audit_event`, V22) | that answers "who changed what" for a reader who already knows which shipment they are asking about. It is append-only and immutable. An alert is mutable by definition - it gets acknowledged. |
| the outbox (`tms.shipment_outbox_event`, V20) | that is addressed to a partner system that polls. This is addressed to a person looking at a screen. |
| the trip timeline (`tms.transport_event`, V27) | that is *one shipment's* day, read on that shipment's workspace. This is the whole company's day, read from anywhere. |
| a message store | there is no thread, no reply, no assignment, no mention and no recipient list. |

The same business fact legitimately produces one row in several of these. That is why
`ShipmentEventPublisher` writes the outbox row, the audit row and the timeline entry together, and
why the alert is raised beside them in the same transaction.

## 2. The seven alert types

| Type | Severity | About | Raised when |
|---|---|---|---|
| `TRIP_DELAYED` | WARNING | trip | a shipment is dispatched later than its planned departure |
| `EXCEPTION_OPENED` | WARNING | trip | a problem is reported on a trip, or opened automatically by a skipped/failed stop |
| `TENDER_REJECTED` | WARNING | trip | a carrier answers no |
| `TENDER_EXPIRED` | WARNING | trip | a sent offer's deadline is resolved as lapsed |
| `DRIVER_LICENSE_EXPIRING` | WARNING | driver | a driver is assigned to a trip with a licence expiring within 30 days |
| `TRIP_COMPLETED` | INFO | trip | a shipment is closed out |
| `DELIVERY_FAILED` | CRITICAL | trip | an order is recorded `PARTIAL`, `REJECTED` or `FAILED` at a stop |

Severity is a property of the type (`NotificationType.severity`), never chosen by the raiser and
never configured per company. A caller that could choose would eventually choose differently at two
call sites for one fact, and the panel's colours would stop meaning anything.

`DELIVERY_FAILED` is the only `CRITICAL` one. It is the only type that says a customer did not get
their goods.

`TRIP_COMPLETED` is the only `INFO` one, and it earns its place for a reason that is not "good news
is nice": customer service is asked several times a day whether a load is closed, and the
alternative is refreshing a board.

### 2.1 Why these seven and not more

Every type above is raised by a **business transaction that was going to happen anyway**. Nothing in
this installation ran on a timer when V32 was written - `TripTenderService` says so about tender
expiry and V31 section 1b states the consequence - so an alert that needs a sweep had no honest
source.

*(Migration V35 has since added exactly one scheduled task, the webhook dispatcher. It is a delivery
worker for events other transactions produced, not a sweep over business state, so nothing above
changes: a time-driven alert would still need a sweep nobody has written, and the two candidates
below are still named rather than faked.)*

That constraint shaped the list, and two obvious candidates are deliberately absent:

- **A shipment that has not yet left and is already past its planned time**
  (`DepartureTimeliness.OVERDUE`). There is no event: nothing happens, which is the problem. The
  control tower answers it live on every read, which is where a question with no source belongs.
- **A licence expiring on a driver nobody is currently planning with.** Same shape: it becomes true
  at midnight, with no transaction to hang on. `DRIVER_LICENSE_EXPIRING` fires at *assignment*
  instead, which is both event-driven and the moment somebody can still pick a different driver.

Writing either without a scheduler would mean an alert that appears only when somebody happens to
open a screen - and an alert that fires when you are already looking is not an alert.

## 3. Where each one is raised

`planning` composes every alert it raises in one class, `TripAlertPublisher`. The services keep the
rules; that class keeps the wording contract - which type, what makes it one fact, and which
placeholders its sentence needs.

| Call site | Raises |
|---|---|
| `TripExecutionService.dispatch` → `announce` | `TRIP_DELAYED`, judged with the `DepartureDelay` rule the control tower uses |
| `TripExecutionService.complete` → `announce` | `TRIP_COMPLETED` |
| `TripExceptionService.report` | `EXCEPTION_OPENED` |
| `TripExceptionService.resolve` | *resolves* the alert `report` raised |
| `TripStopExecutionService.transition` | `EXCEPTION_OPENED`, for the exception a skipped/failed stop opens automatically |
| `TripTenderService.publish` → `announce` | `TENDER_REJECTED`, `TENDER_EXPIRED` |
| `TripDeliveryService.record` | `DELIVERY_FAILED`, and *resolves* it when a result is corrected to a full delivery |
| `TripService.updateDriver` | `DRIVER_LICENSE_EXPIRING` |

`markReadyForDispatch` raises nothing, and neither do `TENDER_SENT`, `TENDER_ACCEPTED` or
`TENDER_CANCELLED`. Those are the plan going right. An alert per shipment per ordinary step is how a
bell gets ignored.

### 3.1 The port, and why it cannot fail a business transaction

`ModuleBoundaryTest` forbids `planning` and `fleet` from importing `com.ebim.tms.notification`, the
same way it forbids any module from importing `com.ebim.tms.audit`. The explicit API is
`NotificationPublisher`, whose only implementation is `NotificationRecorder` - the shape
`AuditRecorder` established in V22.

Two guarantees, and the second is the load-bearing one:

1. **Every raise runs in the caller's transaction.** An alert describes something that happened, so
   if the change it describes rolls back, the alert announcing it must roll back too. A bell
   reporting a dispatch that never committed would send a dispatcher to a shipment still in the
   yard.
2. **No raise may fail that transaction.** An alert is a by-product. `NotificationRecorder` inserts
   with `ON CONFLICT DO NOTHING` rather than reading first, precisely so that a duplicate cannot
   surface as a unique violation - which in PostgreSQL aborts the whole transaction, and would
   therefore let a bell entry fail a driver assignment. Argument serialisation is caught and
   degraded for the same reason.

## 4. No rendered text: a type and its arguments

`tms.notification` has **no title column and no message column**. It carries `type` and
`message_args`, a compact JSON bag of placeholders.

The frontend turns the pair into a sentence: `type` selects `notifications:types.<TYPE>.title` and
`.message`, and the arguments are interpolated into it.

A sentence stored server-side is stored in one language and one wording. A tenant that switches from
Spanish to English would read its own history in the language it left behind, and a reworded alert
would leave every row written before the change saying the old thing. Storing the arguments instead
is what makes the alert translatable at read time, which is what "i18n-friendly" has to mean if it
is to mean anything.

It also settles the HTML question by construction: nothing stored here is markup, so nothing stored
here can be injected into a panel.

**What may go in `message_args`:** a shipment number, a count of minutes, a stop sequence, an order
number, a date. **What may not:** a rendered sentence, markup, a secret, or a business detail the
entity's own table already holds.

Two arguments are enum-shaped (`exceptionType`, `result`). They are stored as the contract values
they are (`VEHICLE_BREAKDOWN`, `REJECTED`) and labelled by `useEnumLabels` before interpolation -
the same rule every other screen follows, so a screaming constant never reaches an operator.

## 5. Read is an act of the company, not of the user

`read_at` / `read_by` mean *somebody here has seen this*. There is no row per recipient and no read
receipt per user.

That is a decision, and it has a cost that is stated rather than hidden: two dispatchers share one
badge, and the second one does not see the alert the first has already acknowledged.

It is the right cost for what this is. An operational alert is **work to be done once** - one person
calls the customer whose delivery failed - and a per-user inbox would show the same job to five
people and give each of them a private way to dismiss it. Acknowledging is how a team says "I have
got this", which is the question a dispatch desk actually asks.

`resolved_at` is a different statement and is set independently: the *condition* behind the alert
closed. Today only `EXCEPTION_OPENED` and `DELIVERY_FAILED` can be resolved, by the service that
raised them. A resolved alert stays on the board, greyed - "this happened and was dealt with" is
more useful than silence. There is no `resolved_by`: who closed it is already on
`tms.trip_exception.resolved_by`, and a second copy could only disagree.

**What changing this would take:** a `tms.notification_read (notification_id, app_user_id, read_at)`
table and a per-user count in the summary query. Nothing has to move for that - which is exactly why
`read_at` lives on this row instead of a fan-out of one row per eligible user, a shape that could
not be walked back.

## 6. Who may see which alert

**No permission of its own.** `NotificationController` carries no `@PreAuthorize` - the only
controller in TMS that does not - and the reason is that the bell is a permanent control in the top
bar that no role can hide. Answering 403 to it on every page load would be worse than showing an
empty panel. Authentication and a resolved company scope are still required.

Minting `notification.alert:read` would also answer the wrong question. The risk here is not "may
this account open the panel", it is "may this account be told that driver DR-004's licence expires
on the 12th".

So the disclosure is decided **per alert type**, against permissions that already exist and already
mean the thing being disclosed:

| Types | Permission |
|---|---|
| `TRIP_DELAYED`, `TRIP_COMPLETED`, `EXCEPTION_OPENED`, `DELIVERY_FAILED` | `monitoring.transport:read` |
| `TENDER_REJECTED`, `TENDER_EXPIRED` | `planning.tender:read` |
| `DRIVER_LICENSE_EXPIRING` | `fleet.driver:read` |

`NotificationService` filters the feed, the unread count, the single acknowledgement **and**
mark-all-read by `NotificationType.visibleTo(scope.permissions())`. An account holding none of the
three gets `{"unreadCount": 0, "notifications": []}`, and one holding only
`monitoring.transport:read` cannot clear a licence alert out from under the fleet desk.

This reuses existing permissions for the reason `ControlTowerController` gives about
`monitoring.transport:read` rather than `monitoring.control_tower:read`: a new permission would give
every installation another grant to make before a bell worked at all.

## 7. Dedupe keys, and why they are the interesting part

`dedupe_key` is what makes a fact **one** fact. It is unique per company
(`uq_notification_company_dedupe`) and is always composed through `NotificationType.dedupeKey`.

| Type | Keyed on | So that |
|---|---|---|
| `TRIP_DELAYED` | the trip | a dispatch retried after a timeout rings once |
| `TRIP_COMPLETED` | the trip | same |
| `EXCEPTION_OPENED` | the **exception** | a day with a breakdown and two refused docks is three alerts, not one |
| `TENDER_REJECTED` / `TENDER_EXPIRED` | the **tender** | a shipment refused twice rings twice - two refusals is a harder problem than one |
| `DELIVERY_FAILED` | the **delivery** | one alert per order per stop, corrected in place with the row |
| `DRIVER_LICENSE_EXPIRING` | the driver **and their expiry date** | planning the same person onto six trips this week is one warning; a renewed licence that later runs down again is a new one |

A key that is too coarse loses alerts silently. A key that is too fine turns a re-read of the same
lapsed tender into a bell that never stops. Neither failure throws anything anywhere, which is why
`TripAlertPublisherTest` spends most of its assertions on exactly this - including the pairing test
that resolving computes the same key raising did.

## 8. The API

`GET /api/v1/notifications?limit=20` answers with the badge and the panel together:

```json
{
  "unreadCount": 12,
  "notifications": [
    {
      "id": "…",
      "type": "TRIP_DELAYED",
      "severity": "WARNING",
      "entityType": "TRIP",
      "entityId": "…",
      "entityLabel": "SH-00000042",
      "messageArgs": { "shipmentNumber": "SH-00000042", "minutes": 95 },
      "occurredAt": "2026-08-20T09:35:00Z",
      "readAt": null,
      "resolvedAt": null
    }
  ]
}
```

One endpoint and not two, unlike the control tower's overview/board split: those two refresh on
different triggers, these two never do. `unreadCount` counts the whole history and not the page - a
badge that counted the page would say "20" forever on a desk that had let a hundred pile up.

`limit` defaults to 20 and is **capped** at 40 rather than refused when it is larger: this is a
top-bar control a browser polls, and failing it over a query parameter would take the bell off every
page. There is no pagination, deliberately - paging a bell turns a glanceable control into a screen,
and the genuinely old alert is answered by the board it came from.

Both `POST` verbs answer with the refreshed feed rather than with the one alert they touched, so the
badge cannot paint a stale count for a frame. Both are idempotent: a repeat keeps the first reader,
because "who picked this up" and "who looked at it most recently" are different questions.

`entityLabel` is snapshotted at raise time, so the panel renders without joining two other modules'
tables - the coupling the port exists to avoid - and an alert about a shipment outlives changes to
it.

## 9. The screen

The bell lives in the top bar and shows a count, not a dot: "something happened" is not actionable,
and the difference between 1 and 40 is the difference between glancing and stopping what you are
doing. It caps at `99+` so a neglected desk cannot push the bar's layout around.

Each row carries an icon **by type** and a tone **by severity**, so "what happened" and "how bad" are
read from two channels instead of competing for one. `INFO` gets no tone at all: a completed shipment
that looked like a warning would be the fastest way to teach people to ignore the panel.

Unread is a weight and a rule down the left edge, never a filled row - a panel of coloured blocks is
unreadable at six entries. Clicking a row acknowledges it and navigates: `TRIP` to `/trips/{id}`,
`DRIVER` to `/fleet/drivers`. The acknowledgement is fire-and-forget; the operator is already on
their way, and a failure leaves the alert unread, which is the safe direction to fail in.

**Polled, not pushed** - once a minute, and only while the tab is in front. TMS has no realtime
channel, and adding one for a bell would be a platform decision made by a top-bar control. A minute
is well inside the time it takes to act on any of these alerts, and the panel refetches when it is
opened regardless.

## 10. Deliberately NOT here

- **No email, SMS, WhatsApp or webhook.** External delivery needs a per-user channel preference, a
  bounce/retry policy and a suppression list. None of those is a decision this module should make on
  its way past. When one arrives it is an adapter reading this table, not a column added to it - and
  the outbox already shows what that shape looks like.
- **No scheduler, and therefore no time-driven alert.** See 2.1. The two that would need one are
  named there rather than faked.
- **No per-user inbox.** See 5, including what changing it would take.
- **No per-company severity policy.** Severity belongs to the type. A per-tenant override is a
  configuration product, and no customer has asked for one.
- **No snooze, no assignment, no escalation.** `tms.trip_exception` declined all three in V27 for the
  same reason: no rule in TMS reads any of them, so each would be a field somebody fills in and
  nothing acts on.
- **No retention job, and no `DELETE` grant.** An alert history the application can silently erase
  stops being evidence of what the operation was told. Purging is a maintenance task for the schema
  owner. At the stated scale the table grows by thousands of rows a day and is years from being a
  problem; a `DELETE` grant added early is what would make it one.
- **No alert created from a screen.** There is no `POST /notifications`. A screen that could invent
  an alert would be a second source of operational truth, and it would let anybody put a red badge on
  somebody else's desk.
