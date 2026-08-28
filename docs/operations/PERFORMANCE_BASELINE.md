# Performance baseline

**JOB 25.** What has been measured, what the numbers mean, and — the important part — what they
cannot tell you.

---

## 1. What this is not

> **This is not a capacity model, and nothing here says TMS handles 10,000 orders/day.**

Every figure below was taken on a developer laptop, inside Docker, against a PostgreSQL container
that had been running for seconds. That environment is not a server, and a duration measured on it
varies by a factor of three between runs on the same machine.

**No load test has been run. No concurrency has been measured. No production-shaped environment
exists to measure.** The scale target in `CLAUDE.md` remains a design constraint honoured in the
schema and the query patterns, and it remains **unmeasured**.

## 2. What is actually asserted

**Query counts, not durations.**

A test that asserted "the control tower responds in under 300 ms" would fail on a busy afternoon and
pass on a quiet one, and everybody would learn to re-run it rather than read it. It would be a test
of the laptop.

A query count is **deterministic**, and it is the thing that actually breaks at volume. An N+1 is
invisible at ten rows and fatal at ten thousand — it does not degrade gradually, it degrades **in
proportion to the data**, which is precisely the failure a 10,000-orders/day target implies. Counting
statements catches it on a fixture of sixty.

## 3. The measurement

`ControlTowerScalePerformanceTest`, on the screen a supervisor keeps open all day and reloads
constantly — the one where an N+1 costs the most.

| Fixture | Shipments | Stops | Statements issued |
|---|---|---|---|
| Quiet day | 5 | 40 | **27** |
| Busy day | 60 | 480 | **27** |

**Twelve times the shipments, the same number of queries.** The read path is batched end to end: the
panels resolve their destinations and vehicles in one lookup each rather than per row.

The assertion allows `small + 4` rather than demanding exact equality — a panel that legitimately
does one extra lookup when it has any rows to look up is a real difference between a quiet day and a
busy one, and it is not proportional to either. **An N+1 at this fixture size would add fifty-five
statements, not four.**

### Panels are capped

Sixty shipments and 480 stops behind them, and every panel returns at most its cap (20, or 5 for
workload). The totals behind the caps are real, so a capped panel is never a silent truncation —
`summary.outstandingStops` exceeds the rows returned, and the screen says "the worst twenty of
forty-seven".

## 4. Wall-clock, recorded as information only

Nothing asserts on these. They are here so a later reader has something to compare against and can
see the order of magnitude.

| | |
|---|---|
| Fixture seed (~1,100 inserts, one connection) | ~2 s |
| Whole test class including Spring context | ~8.6 s |

**A note on the seed.** The first version opened a fresh connection per statement and took **63
seconds**. One held connection took it to about two. That is not a finding about TMS — the
application pools connections and never does this — but it is a good demonstration of how badly
per-row overhead scales, which is the same shape as the N+1 this test exists to catch.

## 5. What is still unmeasured

Stated plainly, because a baseline document that lists only what was measured implies the rest is
fine.

- **Concurrency.** Every measurement is single-threaded. Lock contention on the pessimistic locks
  (settlement approval, work assignment, trip dispatch) is unmeasured.
- **Write paths.** Auto-planning, order import and settlement matching are all unmeasured. Planning
  is the one most likely to matter — it is `O(orders × vehicles)` in the engine.
- **The database at volume.** No table here has more than a few thousand rows. Index behaviour,
  planner choices and RLS overhead at millions of rows are unknown.
- **RLS cost.** ADR-005 filters every business table for `tms_app`. That has a price and it has not
  been measured.
- **Anything at all in a deployed environment.** See `DEPLOYMENT.md`.

## 6. What would close these gaps

In the order that would find the most:

1. **A seeded volume database** — 30 days × 10,000 orders — and re-run the same query-count
   assertions. Counts that stay flat there mean something.
2. **`EXPLAIN ANALYZE` on the ten heaviest reads** against that database, looking for sequential
   scans the fixture is too small to reveal.
3. **A concurrency test on the pessimistic locks** — the settlement and work-assignment races are
   already proven correct; their cost is not.
4. **A real environment**, at which point durations start meaning something.
