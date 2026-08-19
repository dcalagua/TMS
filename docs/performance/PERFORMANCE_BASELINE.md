# Performance baseline - TMS by EBIM

Date: 2026-08-19
Scope: the implementation after Step 11, sized against the design target in `CLAUDE.md` -
**10,000+ orders/day, 100-300 vehicles, multiple companies and concurrent users.**

This document records what was measured, on what data, with what result. Numbers that were
measured are marked as such and the measurement conditions are stated; everything else is
described as reasoning, not as a benchmark.

## 1. What "baseline" means here

Two things are in scope, and neither is a load test:

1. **Query shape** - does a read cost work proportional to the *answer*, or proportional to the
   *history*? A page that costs a scan of every order ever created is a page that works in
   development and dies in month four.
2. **Query count** - does rendering N rows cost a fixed number of statements, or N of them? An
   N+1 is invisible at 5 rows and fatal at 300.

Throughput and latency under concurrent load are explicitly **not** measured here. That needs a
deployed environment and is a later step's work.

## 2. Measurement setup (sections 4 and 5)

| | |
|---|---|
| Database | PostgreSQL 16 + PostGIS 3.4, throwaway Docker container, default configuration |
| Schema | migrations V1-V12 applied from zero |
| Data | **900,000** transport orders in one company (90 days x 10,000/day, the design target), 2 companies, 40 origins, 400 destinations |
| Status mix | 828,000 `PLANNED`, 36,000 `CANCELLED`, 18,000 `NOT_READY`, 18,000 `READY_FOR_PLANNING` - most of the table is history, the outstanding pool is the small tail |
| Method | `EXPLAIN (ANALYZE, BUFFERS)`, `ANALYZE` run before measuring, each query measured with and without the V12 indexes in a rolled-back transaction |

The container was removed after measuring. No shared or remote database was touched.

Buffer counts are the honest metric here rather than milliseconds: they do not depend on what else
the laptop was doing. Execution times are reported alongside because the ratio matters, but the
absolute values are a laptop's, not a server's.

## 3. Findings

Severity as in the security review: **P1** real defect at target scale, **P2** worth fixing,
**P3** noted.

### 3.1 P1 - N+1 on the planning board (fixed)

`TripViewAssembler.toViews` read `trip.stops().size()` for each trip. `Trip.stops` is a lazy
`@OneToMany`, so every trip on the board triggered its own `SELECT` against `trip_stop`.

The board is one trip per vehicle. At the design target that is **100-300 trips**, so the one
screen a planner keeps open all day cost 100-300 extra round trips per refresh - and every
mutation on the board refreshes it.

**Measured** (`PlanningApiIntegrationTest`, Hibernate statistics, real PostgreSQL):

| Board size | JDBC statements before | after |
|---|---|---|
| 1 trip | 9 | 9 |
| 5 trips | 13 | 9 |

The cost was exactly `9 + N`. Extrapolated to a full fleet that is **309 statements at 300 trips**,
against a flat 9.

**Fix.** `TripStopRepository.countByTripIds` - one grouped query for the whole board, the same
shape `RouteStopRepository.countByRouteIds` and `TransportOrderLineRepository.countByOrderIds`
already used. Planning was the only module that had drifted from that discipline.

**Regression test.** `PlanningApiIntegrationTest.boardQueryCountDoesNotGrowWithTheNumberOfTrips`
renders a 1-trip and a 5-trip board and asserts the statement counts are **equal**, not merely
close. Verified to fail (13 vs 9) when the fix is reverted, so it is a real guard and not a test
that passes either way.

`TripViewAssembler.toDetail` still counts stops in memory, deliberately: it is one trip and it is
already rendering those stops, so there is nothing to save.

### 3.2 P1 - The eligible-order pool scanned the whole backlog (fixed)

`GET /api/v1/planning/eligible-orders` is the query a planner runs continuously for a whole shift.
It filters `company_id`, `status = 'READY_FOR_PLANNING'`, `origin_id` and `service_date`, ordered by
`service_date, order_number`.

The best index available was `ix_transport_order_company_status`, whose `READY_FOR_PLANNING`
portion is the backlog of *every* origin and *every* future date. The planner's one-origin,
one-date question was answered by combining two broad indexes, visiting 5,000 heap blocks,
discarding 80% of what it read, and then sorting.

**Measured** - 1,000 matching orders inside a 900,000-row table:

| | Before V12 | After V12 |
|---|---|---|
| Plan | BitmapAnd of two indexes -> Bitmap Heap Scan -> top-N heapsort | Index Scan, no sort node |
| Buffers | **5,051** | **28** |
| Heap blocks | 5,000 | 0 |
| Rows read then discarded | 4,000 | 0 |
| Execution time | 30.1 ms | 1.3 ms |

**~180x fewer buffers, ~23x faster** - and, more importantly, the "after" cost is a function of the
25 rows returned rather than of the backlog, so it does not degrade as the table grows.

**Fix.** `V12` adds:

```sql
CREATE INDEX ix_transport_order_planning_pool
    ON tms.transport_order (company_id, origin_id, service_date, order_number)
    WHERE status = 'READY_FOR_PLANNING';
```

Partial for two reasons: no other status can ever appear in this result, and an order **leaves the
index** when it is assigned. The index therefore stays proportional to the work outstanding, not to
the 10,000 orders/day flowing through the table - it measured **2,296 kB** against 5,768 kB for the
non-partial `ix_transport_order_company_status`. The trailing `order_number` turns the default sort
into a plain index read.

### 3.3 P2 - The order list page sorted what it could have read in order (fixed)

`GET /api/v1/orders` filters on company plus, in the common case, a status and a service-date
range, and sorts by `service_date DESC` by default. `ix_transport_order_company_status` filters but
cannot sort; `ix_transport_order_company_service_date` sorts but cannot filter. PostgreSQL picked
the second and applied the status as a filter.

That is harmless when the status is the common one and expensive when it is not - which is exactly
backwards, because the selective statuses are the ones an operator filters by.

**Measured** - `status = 'NOT_READY'` (2% of the table) over a 90-day range:

| | Before V12 | After V12 |
|---|---|---|
| Buffers | **20,275** (3,213 read, 1,392 written) | **28** |
| Rows read then discarded | 20,215 | 0 |
| Execution time | 71.4 ms | 2.3 ms |

**~720x fewer buffers, ~31x faster.** For the *non*-selective case (`status = 'PLANNED'`, 92% of
the table) the two plans are equivalent - 31 buffers / 0.33 ms before against 28 buffers / 0.26 ms
after - which is the expected result and worth stating: this index removes a cliff, it does not
make the common case faster.

**Fix.** `V12` adds `ix_transport_order_company_status_service_date (company_id, status,
service_date DESC)`.

### 3.4 P2 - The planning board's trip read had no ordered index (fixed)

`TripRepository.findByPlanningRunIdOrderByTripNumberAsc` is read by the board and by both run-wide
mutations. `ix_trip_planning_run` answered the `WHERE` and left the `ORDER BY` to a sort node.
`V12` adds `ix_trip_planning_run_number (planning_run_id, trip_number)`.

Not separately measured: at 100-300 trips per run the sort is small, and this index is cheap
insurance on the module's hottest read rather than a fix for an observed problem. Stated as
reasoning, not as a benchmark.

### 3.5 Storage cost of V12

On the 900,000-order table (313 MB total), the two new `transport_order` indexes cost **6,408 kB +
2,296 kB ≈ 8.5 MB**, about **2.8%**. That is the whole price of sections 3.2 and 3.3.

## 4. What was checked and found healthy

**Batched cross-module lookups.** Every port that resolves references for a page -
`OriginLookupPort`, `DestinationLookupPort`, `VehicleLookupPort`, `OrderPlanningPort` - takes a
`Set<UUID>` and answers in one query. A page of orders resolves its origins and destinations in two
queries regardless of page size; a board resolves its vehicles, their types and their carriers in
three.

**Grouped counts, not per-row counts.** `RouteStopRepository.countByRouteIds`,
`TransportOrderLineRepository.countByOrderIds`, `TripRepository.countByPlanningRunIds`,
`TripOrderAssignmentRepository.countByPlanningRunIds` and `loadByTripIds`, and now
`TripStopRepository.countByTripIds`. Section 3.1 was the one place this discipline had lapsed.

**Capacity never reloads order detail.** `CapacityLoad` comes from a `SUM`/`GROUP BY` over active
`trip_order_assignment` rows. No capacity path loads a `TransportOrder`, and none loads an order
*line* - the header's `total_*` snapshot exists precisely so it never has to.

**Page size is capped server-side.** `PageQuery.MAX_SIZE = 200`; an oversized `size` is clamped,
not rejected. `sort` is validated against a per-endpoint allow-list.

**No eager fetching.** Every `@ManyToOne` is explicitly `LAZY` and every `@OneToMany` is lazy by
default. No entity graph drags in a collection a caller did not ask for. `open-in-view` is off, so
a lazy load outside a transaction fails loudly instead of silently issuing queries during view
rendering.

**Frontend loads pages, not tables.** Every list page paginates against the server and uses
`keepPreviousData` so paging does not blank the table. No page fetches all rows of anything.

**No quadratic frontend computation.** The only nested lookups are over a trip's own stops and over
the seven days of a weekly rule.

**Identity resolution.** Two indexed statements per authenticated request, driven by
`ix_membership_app_user_active`. Not cached, deliberately - see the security review, section 6,
item 1.

**Indexes.** All 25 tables have `company_id` indexed; `transport_order`, `planning_run` and `trip`
carry composite indexes on the columns their list endpoints filter by; the two PostGIS `location`
columns have GiST indexes; the hot partial indexes (`ix_trip_order_assignment_trip_active`,
`uq_trip_order_assignment_open_whole_order`, and now `ix_transport_order_planning_pool`) cover only
live rows.

## 5. Known scale ceilings - accepted, not defects

| Ceiling | Bound | Why it is accepted |
|---|---|---|
| `GET /planning/runs/{id}` returns every trip of the run | ~fleet size, 100-300 | The board is one call by design (`docs/overnight/10_MANUAL_PLANNING_BACKEND.md`). After 3.1 it is a fixed 9 statements at any size. Paginating it would break the board's design for no gain at V1 volumes. |
| `GET /masterdata/frequencies/{id}/exceptions` is unpaginated | calendar exceptions per frequency | Tens of rows in normal use. Paginating changes the response shape and therefore the frontend contract - a versioned change, not a hardening patch. |
| Lookup dropdowns fetch `size: 200` | 200 options | Correct for masters at V1 volumes, and the currently-selected value is always injected so an edit form never loses it. A company with thousands of destinations would silently see only the first 200 by code. **The right fix is a server-side typeahead**, not a bigger page - recorded here so it is chosen deliberately rather than discovered. |
| `orderNumber` filters use `LIKE '%x%'` | full scan of the filtered set | No btree can serve a leading wildcard. Acceptable because it is always combined with `company_id`; a trigram index is the answer if it ever becomes a real pattern. |

## 6. Changed in this review

- `V12__performance_indexes.sql` (new migration; V1-V11 untouched) - 3.2, 3.3, 3.4.
- `TripStopRepository` (new), `TripViewAssembler` - 3.1.
- `PlanningApiIntegrationTest.boardQueryCountDoesNotGrowWithTheNumberOfTrips` - the guard for 3.1.

## 7. How to re-measure

The N+1 guard runs in the normal suite (`./mvnw test`, Docker required). Sections 3.2 and 3.3 are
reproduced by applying V1-V12 to a throwaway PostGIS container, seeding 900,000 orders with the
status mix in section 2, running `ANALYZE`, and comparing `EXPLAIN (ANALYZE, BUFFERS)` for the two
queries with the V12 indexes present and dropped inside a rolled-back transaction.
