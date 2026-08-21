# KPIs & Reporting V1 - the operation, measured

Owner: `com.ebim.tms.planning.application.KpiService`.
API: `KpiController`. Screen: `frontend/tms-web/src/pages/reporting/ReportsPage.tsx`.
Schema touched: migration V33 (one column).

## 1. What this exists for

The control tower answers "what is happening right now" (`CONTROL_TOWER_V1.md`). This answers
"how did we do", over a span of operating days, and it is the screen a transport manager shows to
somebody who is deciding whether to keep the contract.

That second sentence is the design constraint. Every figure here is going to end up in a slide, so
the whole report is built around one rule:

> **A number is only shown when it was measured. Everything else is a dash.**

An operation that never recorded a departure has no on-time-departure percentage. Rendering that as
0% accuses it of never being punctual; rendering it as 100% congratulates it for the absence of
evidence. Both are numbers somebody can quote, and both are wrong. `KpiRate` is the one place that
decision is made, and it returns `null`.

## 2. The range

Two optional query parameters, `from` and `to`, both **inclusive**, both resolved by `KpiRange`:

| Input | Result |
| --- | --- |
| neither | the last **30** days, ending on the company's own today |
| `to` only | 30 days ending on `to` |
| `from` only | `from` up to the company's own today |
| both | as given |
| `to` before `from` | **refused** (400), not swapped |
| more than **92** days | **refused** (400), naming the cap, not truncated |

"The company's own today" is `CompanyScope.today()` - the company's time zone, never the server's
and never the browser's. A report opened at 23:30 in `America/Lima` ends on the 23rd, not on the
24th that UTC already thinks it is. Same rule the control tower's date follows.

The 92-day cap is the only bound on the report's cost. Every statement behind it is
range-predicated, so the cap is what stops one request asking the database for a company's whole
history - and it is also the reason the chart stays readable, because 365 columns is not a chart.

**Which date.** Shipments, stops, deliveries, exceptions and tenders are all dated by the **trip's
planning date** - the operating day the shipment belongs to. Orders are dated by their **service
date** - the day the customer is owed the goods. Nothing is dated by when a row was typed. A
delivery recorded at 00:20 on a shipment that left the previous evening belongs to that shipment's
day, and a tender placed on Friday for Monday's load is counted against Monday.

## 3. The formulas

Every percentage below is `null` when its denominator is zero. Every one is 0-100 except where the
table says otherwise.

### 3.1 Shipments

| Figure | Definition |
| --- | --- |
| `trips` | shipments planned in the range, **cancelled ones included** |
| `tripsCancelled` | of those, withdrawn |
| `tripsRun` | `trips - tripsCancelled`. The denominator of everything about how the day went |
| `tripsCompleted` | closed out with every stop resolved |
| `byStatus` | the whole lifecycle breakdown, padded so every `TripStatus` is present |
| `departuresMeasured` | shipments carrying **both** a planned and an actual departure |
| `departuresLate` | of those, `actual > planned`, strictly, with **no grace period** |
| `onTimeDeparturePercent` | `(departuresMeasured - departuresLate) / departuresMeasured` |
| `completionPercent` | `tripsCompleted / tripsRun` |

`departuresMeasured` is the point of the whole section. A shipment nobody recorded a departure for
is not an on-time departure; leaving it in the denominator would make an operation look better the
less it recorded. The no-grace-period rule is `DepartureDelay`'s, not this report's.

### 3.2 Service

Stops are what the **vehicle** did; deliveries are what the **goods** did. They do not add up to
each other and are not meant to - a stop serving three orders can be `COMPLETED` with one of them
`REJECTED`. See `DeliveryResult`.

| Figure | Definition |
| --- | --- |
| `stops` / `stopsCompleted` / `stopsSkipped` / `stopsFailed` | stops on non-cancelled shipments in the range, by outcome |
| `serviceWindowsMeasured` | stops carrying **both** a recorded arrival and a promised window |
| `serviceWindowsMissed` | of those, arrived after the window closed |
| `onTimeServicePercent` | `(measured - missed) / measured` |
| `deliveriesRecorded` | order-level outcomes recorded against the range's shipments |
| `deliveriesDelivered` | `DeliveryResult.DELIVERED` |
| `deliveriesShort` | `PARTIAL`, `REJECTED` or `FAILED` (`DeliveryResult.isShortfall`) |
| `deliveriesNotAttempted` | `NOT_ATTEMPTED` - outside the shortfall, deliberately |
| `deliverySuccessPercent` | `deliveriesDelivered / deliveriesRecorded` |

**How lateness at a stop is decided.** A stop stores its window as a bare local time with no date;
the date is its trip's and the zone is the company's. The comparison is therefore
`actual_arrival_at > (planning_date + service_window_end) AT TIME ZONE <company zone>`, made in SQL,
per row, which handles a daylight-saving change correctly. The control tower gets away with a
single local cutoff because it asks about one date at a time; a range cannot, because two dates in
it can sit on either side of a clock change.

### 3.3 Exceptions

| Figure | Definition |
| --- | --- |
| `exceptions` | problems raised against the range's shipments |
| `open` / `resolved` | of those, by state now |
| `per100Trips` | `exceptions / tripsRun`, x100 - **not a percentage**, and it may exceed 100, because one shipment can carry three problems |

### 3.4 Utilisation

Summed, then divided - **never** the average of the per-shipment percentages:

```
weightPercent = SUM(assigned_weight_kg) / SUM(snapshot_max_weight_kg)
```

The two differ whenever the fleet is mixed. A day with one full van and one empty articulated truck
is not 50% utilised; it is however much of the total tonnage went out. Averaging percentages would
let the van's 100% cancel the truck's 10%.

**Over which shipments.** Only those that carry a capacity snapshot - confirmed or beyond - and only
those not cancelled; and per dimension, only those whose snapshot for that dimension is stated. A
draft's limit lives on the vehicle master, which `planning` may reach only through
`VehicleLookupPort` (`CAPACITY_MODEL.md`), so including drafts would mean a cross-module join or a
denominator with holes in it. `utilization.trips` says how many shipments the three percentages are
actually about, so a screen showing 82% over eleven of a quarter's four hundred shipments can say
so.

### 3.5 Orders

The product's planning invariant, reported:

```
inputOrders = planned + unplanned
unplanned   = readyToPlan + notReady
```

Cancelled orders sit outside all of it. An order somebody withdrew was never work the plan failed to
cover, and counting it would make a company's coverage look worse every time it tidied its backlog.
`notReady` counts as unplanned deliberately: a demand nobody has released is still a demand nobody
has moved, and excluding it would let a company report 100% planned over a backlog it never looked
at.

`plannedPercent = planned / inputOrders`.

### 3.6 Tendering

Counted in **attempts**, not in shipments. A shipment refused by two carriers and taken by a third
is three attempts and one shipment; a rate over shipments would report that as 100% and hide the two
refusals, which are the whole reason anybody looks at this.

```
answered          = accepted + rejected
acceptancePercent = accepted / answered
rejectionPercent  = rejected / answered
```

An unanswered offer is not a refusal and a lapsed one is not a decision the carrier made, so neither
is in the denominator.

`expired` is a **floor**, not an exact figure: expiry is applied when a tender is next touched
rather than by a sweep (migration V31, section 1b), so an offer that lapsed this morning may still
be sitting in `awaitingResponse`. The two rates are unaffected either way.

### 3.7 Cost

One entry **per currency**, and no grand total. TMS holds no rates of exchange and refuses to invent
one, so a company paying two carriers in two currencies gets two rows and adds them up nowhere.

```
variance        = comparableActual - comparableEstimated
variancePercent = variance / comparableEstimated
```

`comparable*` covers only the shipments carrying **both** an estimate and an actual, and
`tripsComparable` travels beside it. Subtracting the actuals of three shipments from the estimates
of forty produces a large negative number that reads as a saving and is nothing of the kind.

Cancelled shipments are counted: one that was already invoiced cost real money.

## 4. The daily series

One row per day in the range, **including the days nothing happened on**. A series built only from
the days that produced rows would draw a quiet Sunday and a busy Monday at the same spacing and hide
the gap, which is the one thing an operations chart must not do.

The headline cards are **summed from these rows**, not queried again, so a card and the column under
it cannot disagree. That is the failure mode dashboards have and it is worth the sentence.

Each row carries: `trips`, `tripsCancelled`, `tripsCompleted`, `departuresMeasured`,
`departuresLate`, `onTimeDeparturePercent`, `deliveriesRecorded`, `deliveriesDelivered`,
`deliverySuccessPercent`, `exceptions`, `exceptionsOpen`.

## 5. The API

### `GET /api/v1/reporting/kpis`

Query: `from`, `to` (both optional, ISO dates). Returns `KpiReportView`: the seven sections and the
daily series.

### `GET /api/v1/reporting/kpis/export`

Same query. Returns `text/csv; charset=UTF-8` with a UTF-8 BOM and CRLF endings, named
`tms-kpis-<from>-to-<to>.csv`, `Cache-Control: no-store`.

It exports **the daily table and nothing else**. The daily rows are the only part of this report
that is a table; the sections are cards, and a CSV whose first fifteen lines were label/value pairs
and whose remainder was a grid would be a file nobody can pivot. Everything on a card is a total of
a column that is in the file.

Its headers are the JSON field names, **not translated**. A CSV is the machine-readable copy: it is
pasted into a finance system and diffed against last month's. Localised headers would make the same
report a different document depending on who pressed the button, and a saved pivot table would break
the first time somebody switched the UI to English. The screen is where language belongs.

## 6. Permissions

Both endpoints are behind **`monitoring.transport:read`** and no new permission was minted. That
permission has meant "see where the transport is" since migration V3, and this is the same
disclosure asked over a quarter instead of over a day. Minting `reporting.kpi:read` beside it would
give a company two permissions for one idea and force every seeded role to be re-granted before a
screen they were already entitled to would open - the same reasoning `ControlTowerController` and
`TrackingController` give.

Three sections are about something other than transport and are answered with **`null`** for a
caller who does not hold the permission that owns them:

| Section | Permission | Absent when not held |
| --- | --- | --- |
| `orders` | `orders.order:read` | `null` |
| `tenders` | `planning.tender:read` | `null` |
| `cost` | `rates.trip_cost:read` | `null` |

`null` and not `0`, and not a 403 either. Zero would be the response asserting an empty backlog it
was not allowed to look at; a 403 would take a whole screen away over one card. The checks live in
`KpiService`, on the fields they protect. A denied section costs no query.

**What the report discloses**: counts and percentages, and nothing that names a shipment, an order, a
customer, a driver or a carrier. A caller cannot use it to learn what one shipment cost or who
refused it, which is what makes reusing a monitoring permission over a quarter of history
defensible.

## 7. Performance

The report costs **eight statements**, regardless of how long the range is, and none of them returns
a row per shipment:

1. trips grouped by day and state, with the two departure counts (`TripRepository`)
2. deliveries grouped by day and result (`OrderDeliveryRepository`)
3. exceptions grouped by day and state (`TripExceptionRepository`)
4. tenders grouped by state (`TripTenderRepository`) - skipped when not entitled
5. utilisation, one aggregate row (`TripRepository`, native)
6. stop outcomes and window punctuality, one aggregate row (`TripStopRepository`, native)
7. the order backlog grouped by state (`OrderPlanningPort` -> `orders`) - skipped when not entitled
8. cost grouped by currency (`TripCostAnalyticsPort` -> `rates`) - skipped when not entitled

The export costs the three that feed the daily series. All of it runs in one read-only transaction,
so the shipment counts and the cost totals they are compared against come from one snapshot.

Two of the eight are native SQL, and each says why in its own javadoc: the utilisation aggregate
needs a `WITH` clause to reach trip grain before joining (JPQL has none, and a plain join would
multiply each trip's limit by the number of orders on it), and the stop aggregate needs
`AT TIME ZONE` per row.

## 8. Why it lives in `planning`

Trips, stops, deliveries, exceptions and tenders are all planning's, so a `reporting` module would
reach five of its seven sources through new ports - a boundary with nothing on the other side of it,
which is the argument `ControlTowerService` already makes for itself. The two that are not
planning's are read exactly as the rest of planning reads other modules: through
`shared.reference` ports.

- `TripCostAnalyticsPort` (new, V33) - implemented by `rates.infrastructure.TripCostAnalyticsAdapter`.
- `OrderPlanningPort.backlogTotals` (new method) - implemented by `orders.application.OrderPlanningService`.

## 9. Migration V33

One column: `tms.trip_cost.planning_date`, denormalized from `tms.trip` at creation and never
updated (its source is itself immutable), plus `ix_trip_cost_company_planning_date`.

Without it, a cost total over a date range is
`FROM tms.trip_cost c JOIN tms.trip t ON t.id = c.trip_id WHERE t.planning_date BETWEEN ...` - which
is `rates` reading `planning`'s table, the SQL form of the dependency `ModuleBoundaryTest` forbids
in Java. The alternative, passing a set of trip ids across the port, is tens of thousands of UUIDs
at the stated scale. The column is the same denormalization V16 rule 7 made onto `tms.trip` itself.

## 10. Deliberately not in V1

- **No per-carrier or per-origin cut.** It is the most-requested second report and it is a second
  report, not a filter on this one: a carrier breakdown needs `carrier_id` on `tms.trip_cost` (see
  V33 section 4) and a screen of its own, and adding either ahead of a customer asking would be a
  column nobody maintains and a tab nobody opens.
- **No filters below the date range.** The control tower's asymmetry - filters narrow the table but
  never the headline numbers - is the right rule and it needs stating on the screen that has it.
  This screen has no filter that could hide anything, and V1 keeps it that way rather than
  reopening the question for a cut nobody has asked for.
- **No scheduled or emailed reports.** That needs a scheduler, a recipient list and a bounce policy.
  Migration V35 added the first and only scheduled task in the product - the webhook dispatcher - so
  the scheduling half is no longer hypothetical; the recipient list, the templates and the bounce
  policy still are, and they are the part that decides whether this is honest to ship.
- **No Excel export.** The CSV is read by every spreadsheet and every finance system. A styled
  workbook would add Apache POI to a read path for formatting a machine is going to strip anyway,
  and `ModuleBoundaryTest.spreadsheet_library_stays_inside_the_import` is deliberate about where
  that library is allowed to be.
- **No stored snapshots, no rollup tables, no materialized views.** Every figure is an aggregate
  over at most a quarter of one company's shipments. A pre-aggregated copy would buy nothing
  measurable and would introduce the one failure mode a KPI screen cannot afford: a number that
  disagrees with the rows it claims to summarize.
- **No cost per kilometre and no cost per order.** TMS has one source of distance - a route master's
  reference distance (`RouteTemplateLookupPort.findReferenceDistanceKm`) - which is null for every
  shipment not built from a corridor, and a cost-per-km computed over the subset that has one would
  be a different fleet's number every month. Cost per order needs an allocation rule across a
  shipment's orders, which is a per-stop cost model and a bigger feature (V30, `rate_card.scope`).

## 11. Known gaps

- **Utilisation excludes drafts**, so a company that plans far ahead sees a figure about its
  confirmed shipments only. `utilization.trips` makes the scope visible; widening it needs a
  capacity lookup for unconfirmed trips, which is a cross-module read the capacity model
  deliberately routes through a port one trip at a time.
- **`expired` tenders are a floor**, for the reason section 3.6 gives. Exact expiry needs a sweep,
  and there is no scheduler.
- **On-time *arrival* at the destination is not measured against a promise**, only against the
  service window. There is no planned-arrival-per-stop field in V1 (`TripStop` carries a window, not
  an ETA), and ETA calculation is deferred by decision (ADR-007).
- **The report is not certified against a database.** Every aggregate here is exercised by unit
  tests over stubs, and the SQL itself - the two native statements especially - has not been run:
  Docker/Testcontainers is unavailable in the build environment (see the repository's
  `DB_CERTIFICATION=BLOCKED_ENVIRONMENT` note). That is the highest-value thing to do the moment a
  database is reachable.
