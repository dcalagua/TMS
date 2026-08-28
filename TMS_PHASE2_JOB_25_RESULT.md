# JOB 25 — Performance Harness & Baseline

**RESULT = PASS** · **MIGRATION = none**

---

## 1. Gates

| Gate | Result |
|---|---|
| `./mvnw clean test` | **1844 pass · 0 fail · 0 error · 0 skipped** (1842 → 1844, **+2**) |
| Flyway | **V1–V48**, unchanged |

## 2. The decision this job turns on

**The harness asserts query counts. It does not assert durations.**

A duration measured on a laptop, in Docker, against a container seconds old varies by a factor of
three between runs on the same machine. A test asserting on it fails on a busy afternoon and passes
on a quiet one, and everybody learns to re-run it rather than read it. **It would be a test of the
laptop.**

A query count is deterministic, and it is what actually breaks at volume. An N+1 is invisible at ten
rows and fatal at ten thousand — it does not degrade gradually, it degrades **in proportion to the
data**, which is exactly the failure a 10,000-orders/day target implies. Counting statements catches
it on a fixture of sixty.

Wall-clock is recorded in `docs/operations/PERFORMANCE_BASELINE.md` as information for a human.
Nothing asserts on it.

## 3. The measurement

`ControlTowerScalePerformanceTest` — the screen a supervisor keeps open all day, so the one where an
N+1 costs most.

| Fixture | Shipments | Stops | Statements |
|---|---|---|---|
| Quiet day | 5 | 40 | **27** |
| Busy day | 60 | 480 | **27** |

**Twelve times the shipments, the same number of queries.** The read path is batched end to end.

The assertion allows `small + 4` rather than exact equality — a panel that legitimately does one
extra lookup when it has rows to look up is a real difference between a quiet day and a busy one and
is not proportional to either. **An N+1 here would add fifty-five statements, not four.**

A second test proves every panel is capped at 60 shipments and 480 stops, **and that the totals
behind the caps are real** — so a capped panel is never a silent truncation.

## 4. What the fixture taught me

Building it walked straight into five constraints, and every one was right:

| Constraint | What it refused |
|---|---|
| `ck_trip_committed_requires_confirmed_at` | An `IN_TRANSIT` shipment nobody confirmed |
| `ck_trip_confirmed_is_complete` | A confirmed shipment with no capacity snapshot |
| `ck_trip_dispatched_actor_pair` | A departure with nobody who dispatched it |
| `ck_trip_ready_requires_timestamp` | A shipment in transit that was never made ready |
| `fk_trip_stop_destination` | A stop pointing at `tms.destination` — a later migration repointed it at `tms.location` |

**Five attempts to seed a plausible-looking shipment, five refusals, and the fixture is now correct
because of them.** This is the three-layer invariant design working on somebody who knew the schema
and still got it wrong — which is the case for pushing invariants to the database, made against me.

## 5. One incidental finding

The first fixture opened a **fresh connection per statement** and took **63 seconds** to seed ~1,100
rows. One held connection took it to about two.

Not a finding about TMS — the application pools connections and never does this — but it is the same
shape as the N+1 the test exists to catch, demonstrated at 30× rather than in theory.

## 6. What is explicitly NOT claimed

> **Nothing here says TMS handles 10,000 orders/day.**

- **No load test. No concurrency measurement. No production-shaped environment.**
- **Write paths unmeasured** — auto-planning most of all, which is `O(orders × vehicles)`.
- **Database at volume unmeasured** — no table here exceeds a few thousand rows, so index behaviour
  and planner choices at millions are unknown.
- **RLS cost unmeasured.** ADR-005 filters every business table; that has a price nobody has priced.
- **Lock contention unmeasured.** The settlement and work-assignment races are proven *correct*;
  their *cost* is not.

The scale target remains a design constraint honoured in the schema and the query patterns, and it
remains **unmeasured**. `PERFORMANCE_BASELINE.md` §6 lists what would close each gap, in the order
that would find the most.

## 7. Why no separate load-testing tool

No Gatling, no k6, no JMeter. Adding one would produce a script that runs against nothing — there is
no deployed environment to point it at — and a repository artefact implying a capability nobody has.

**When an environment exists, the first thing to run is the query-count assertions against a seeded
volume database.** Counts that stay flat there mean something. That is recorded as step 1 of §6
rather than pre-built.
