# Control Tower V1 - the transport day, watched

Migrations: **none**. Module: `com.ebim.tms.planning` (read-only slice). Screen:
`frontend/tms-web/src/pages/control-tower`.

## 1. What this exists for

A transport supervisor asks the same seven questions between 06:00 and 18:00:

1. which trips leave today?
2. which of them are late?
3. which have an open problem?
4. which are on the road?
5. which stops are running past their window?
6. which orders are still unplanned?
7. which vehicles are carrying the most?

Every one of those was answerable before this existed - from the Trips board, from each trip's
workspace, from the planning board, one screen and one click at a time. What was missing is the
*single place* that answers all seven at once, because the cost of assembling them by hand is paid
during exactly the hour when nobody has a spare hand.

**It is not a dashboard.** There is no chart on it. Every number is a count somebody can act on, and
every row links to the shipment it is about; a figure that cannot be clicked through to a decision is
a poster, and posters are what make dashboards stop being read after the second week.

## 2. The one rule everything else follows

**The control tower owns nothing.**

It has no table, no migration, no state and no rule of its own. Every fact it shows already belongs
to something:

| What it shows | Who decides it |
| --- | --- |
| trip lifecycle | `TripStatus` (V11, V25) |
| departure lateness | `DepartureDelay` / `DepartureTimeliness` |
| stop lateness | `StopServiceWindow` |
| capacity utilisation | `TripViewAssembler` + `PlanningCapacityService` (`CAPACITY_MODEL.md`) |
| the shipment header | `TripView` (`SHIPMENT_V2.md`) |
| open problems | `TripException` (V27) |
| the unplanned backlog | `OrderPlanningPort` |

That is the whole design. A monitoring screen that re-derived any of them would become a second,
slowly diverging opinion about the same day - the classic failure of a reporting layer bolted beside
an operational one. Adding a new figure here means finding the module that owns it, not computing it
here.

The consequence: **deleting this feature would cost a screen and no business fact.** Same property
Tracking has (`TRACKING_V1.md`), for the same reason.

## 3. Delay semantics

This is the part that is easy to get wrong quietly, so it is stated in full.

### 3.1 Departure - `DepartureDelay`

Judged from two recorded instants: `trip.planned_departure_at` (what the plan asked for) and
`trip.actual_departure_at` (what happened). Migration V25 keeps them in separate columns precisely so
the gap between them survives a dispatch.

| Verdict | When |
| --- | --- |
| `LATE` | departed, and after the planned instant. `minutes` = actual - planned |
| `ON_TIME` | departed at or before the planned instant. Early is *not* a separate verdict |
| `OVERDUE` | not departed, planned instant has passed. `minutes` = now - planned |
| `SCHEDULED` | not departed, planned instant still ahead |
| `NOT_SCHEDULED` | no planned departure on file - a real gap, not a quiet default |
| `NOT_APPLICABLE` | cancelled, or a departed trip with no departure time (a data anomaly) |

`OVERDUE` covers `DRAFT` as well as `CONFIRMED` and `READY_FOR_DISPATCH`. A trip due out at 08:00
that is still a draft at 09:30 is the worst version of late, and excluding drafts would drop the one
case that most needs somebody.

**There is no grace period in V1, and that is deliberate.** A tolerance ("late means more than
fifteen minutes late") is a commercial policy and no customer has stated one; defaulting to a number
would quietly reclassify real lateness as punctuality in every report built on top of this. Instead
the *magnitude* is always reported, so a screen shows `+3 min` and `+95 min` differently and a person
decides. When a policy arrives it belongs in `DepartureDelay`, beside the rule it changes - not in
the screens that ask.

### 3.2 Stops - `StopServiceWindow`

A stop stores `service_window_end` as a `LocalTime` with no date; an arrival is an instant. Comparing
them means choosing a day and a zone, and there is one defensible answer: **the planning date of the
stop's trip, in the company's own time zone** - the same choice `ShipmentTimeRules` makes for planned
departures. A window closing at 14:00 for a depot in `America/Lima` means 14:00 in Lima; judged in
UTC every afternoon window in the country would read as breached.

Lateness is then measured:

* to the **arrival**, where the vehicle got there - a recorded fact that stops moving;
* to **now**, where it has not - the statement "this has not happened and was due";
* **not at all** for a stop resolved without anybody arriving (`SKIPPED`) - nobody went, so there is
  no lateness to state. A stop with no window can never be late.

### 3.3 Nothing else is inferred

No lateness is derived from a tracking position, from stop progress, from how long a dwell has run,
or from a carrier's history. `TRACKING_V1.md`'s rule holds here too: positions inform people and
move nothing.

## 4. What is scoped by what

The screen has one filter bar: **day**, origin, carrier, status. They do not all reach the same
place, and the screen says so out loud (`controlTower.scopeNote`).

| | day | origin / carrier | status |
| --- | --- | --- | --- |
| KPI strip | yes | no | no |
| the three panels | yes | no | no |
| the operational table | yes | yes | yes |

The counters are the day's **whole** picture. Narrowing them to one carrier would let a filter hide
another carrier's open exception from the one screen that exists to surface it; drilling is what the
table is for. The heading above the strip reads "the whole day" rather than leaving an operator to
discover the rule during an incident.

The **day itself** defaults to today *in the company's zone* (`CompanyScope.today()`), never the
server's date and never the browser's - a tab open at 23:30 in Lima must show the 23rd and not the
24th that UTC already thinks it is. The screen fills its date box in from the server's answer once it
arrives, so the operator can see and step off the day the backend chose.

## 5. The API

Two endpoints, both `GET`, both behind `monitoring.transport:read`.

### `GET /api/v1/monitoring/control-tower`

The whole-day overview: the KPI strip plus three capped panels.

```
{ date, generatedAt, summary, workload[], openExceptions[], outstandingStops[] }
```

`generatedAt` is the instant the server judged the clock-dependent facts against. It is on the wire
because two of them - `tripsOverdue` and `stopsPastWindow` - change on their own, and a tab left open
for an hour must be able to say its verdict is an hour old rather than looking current. The screen
also re-reads this endpoint every 60 s; the table deliberately does not, because rows shifting under
a cursor is worse than a row being a minute old.

The three panels are **capped, not paginated** (20 rows; 5 for the workload), and every cap has its
total beside it in `summary`, so a panel reads as "the worst twenty of forty-seven" and never as
"forty-seven". `outstandingStops` is ordered by planned window end, which within one planning date is
the same order as the instants it resolves to - so the cap discards the *least* urgent rows rather
than arbitrary ones.

`summary.ordersUnplanned` is `null`, not `0`, for a caller who does not hold `orders.order:read`.
Zero would be a claim about a backlog the response was not entitled to look at.

### `GET /api/v1/monitoring/control-tower/trips`

The operational table: `PageResponse<ControlTowerTripView>`, paginated and sorted through the same
contract as `GET /planning/trips`, whose filter and sort allow-list it reuses by delegating to
`TripService.list`.

Each row is a `TripView` plus the monitoring lens: `departureTimeliness` and
`departureDelayMinutes`, `stopsResolved` / `stopsTotal` / `stopsPastWindow`, the next outstanding
stop and when it is due, and the count of open exceptions.

## 6. Permissions

`monitoring.transport:read`, which has been in the catalogue since **V3** and granted to
ORGANIZATION_ADMIN, COMPANY_ADMIN, PLANNER and VIEWER since **V5**. V5's own header describes what it
is for in as many words: *"a read-only transport monitor: the operational overview screen that
dispatch and customer service use."* Until now only Tracking stood behind it.

No new permission was minted, and **no migration was needed**, which is the point: a company that
already grants the monitor gets the screen, and nobody has to re-grant a role for a screen it was
already entitled to. Same reasoning `TrackingController` gives for not inventing
`tracking.position:read`.

There is no `manage` counterpart and there will not be one. Acting on what the tower shows -
dispatching, resolving an exception, planning the backlog - happens on the trip and planning
endpoints with the permissions those require. A customer service account may be entitled to see the
whole day and change none of it.

**What it discloses.** The table rows are `TripView`s, so a caller holding only
`monitoring.transport:read` sees the shipment header, including the driver's name and phone, without
holding `planning.trip:read`. That is the disclosure this permission was always for: the monitor's
next action is "call the driver of SH-00000142". It stops at the header - no order, no line, no
price, no evidence artefact - and the one order-shaped number on it is gated separately, in the
service, on the field it protects rather than on the whole screen.

## 7. Performance

The scale target is 10,000+ orders/day and 100-300 vehicles, which is *hundreds* of trips a day, not
thousands.

The overview costs a fixed number of statements, none of which returns a row per trip: one grouped
status count, two departure counts, two stop counts, one exception count, one assignable-order count,
and then the three panels - each a capped fetch plus its batched lookups. They ride indexes that
already exist: `ix_trip_company_planning_date_status` (V25), `ix_trip_stop_company_unresolved` and
`ix_trip_exception_company_open` (V27, partial on OPEN).

The board costs `TripService.list`'s fixed set plus **one** stop query and **one** grouped exception
count for the page. Nothing here is N+1, and nothing downloads a table for the browser to count.

Two places read rows rather than counting them, and both are bounded deliberately:

* the **workload ranking** loads the day's committed, unfinished trips (capped at 400) because
  utilisation cannot be ordered in SQL - a draft trip's limit lives on the vehicle master, behind
  `VehicleLookupPort`. It ranks on capacity alone (two queries) and only then resolves the full
  shipment header for the five that win.
* the **stops-past-window count** compares a `LocalTime` column against a `LocalTime` cutoff rather
  than converting a time zone per row. That works because every stop counted shares one planning
  date: the service resolves "what local time is it on that date" once - now for today, end-of-day
  for a date already past, and no query at all for a date still ahead.

## 8. Deliberately not in V1

* **No map.** ADR-007 ships no vendor adapter, so on a stock deployment every marker would be a
  vehicle whose position nobody is reporting. The trip workspace already shows what tracking there
  is, per shipment, where it means something.
* **No alerting, no subscriptions, no escalation.** The screen is read; nothing here pages anybody.
  `TripExceptionStatus` explains why TMS has no severity or assignment model to escalate along.
* **No ETA and no predicted lateness.** Both are deferred by decision (`CLAUDE.md`), and an ETA is
  precisely the kind of number that would make this screen look more certain than it is.
* **No date range.** Every number here is "of a day". A range would turn "which trips are late" into
  a report, which is a different product.
* **No cross-company view.** Tenancy is per company, everywhere, always.

## 9. Known gaps

* **P2 - no departure grace period.** Strict `actual > planned`. Deliberate (see 3.1); revisit when a
  customer states a tolerance, and put it in `DepartureDelay` when they do.
* **P2 - the KPI strip ignores origin and carrier.** Deliberate (see 4). If multi-depot operators ask
  for a per-origin strip, the aggregates need the trip specification threaded through them, which is
  a bigger change than it looks: the exception and stop counts reach trips through a subquery.
* **P2 - no integration test.** The domain rules are covered by `DepartureDelayTest` and
  `StopServiceWindowTest`, which need no infrastructure. The endpoints have no
  `@SpringBootTest` slice yet; the existing ones are Testcontainers-gated and Docker is unavailable
  in the current environment, so one would have been written blind and skipped. It belongs beside
  `PlanningApiIntegrationTest` when Docker returns.
