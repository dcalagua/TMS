# Capacity model (v2)

How TMS decides whether a load fits a vehicle, and what percentage of it is used. Introduced by
step 10 (manual planning); the planning flow around it is
[`PLANNING_MANUAL_V1.md`](PLANNING_MANUAL_V1.md). **v2 (V62, ADR-022, overnight phase 40)** adds the
floor and the height; the arithmetic that produces them is
[`LOAD_GEOMETRY_V1.md`](LOAD_GEOMETRY_V1.md).

Five dimensions, always in the same units: **weight in kilograms**, **volume in cubic metres**,
**pallets** (which may be fractional), **floor area in square metres** and **stack height in
metres**. The unit is part of every column and field name in the chain - `max_weight_kg`,
`planning_volume_m3`, `assigned_floor_area_m2`, `snapshot_max_stack_height_m` - so no layer can
silently mix tons with kilograms.

| Dimension | Used (load) | Limit | Combined over orders by |
|---|---|---|---|
| weight | `assigned_weight_kg` | effective `max_weight_kg` | sum |
| volume | `assigned_volume_m3` = the order's **planning volume** (effective where a line was measured, declared otherwise) | effective `max_volume_m3` | sum |
| pallets | `assigned_pallets` | effective `max_pallets` | sum |
| floor | `assigned_floor_area_m2` | `vehicle_type.length_m x width_m x fill factor` | sum |
| height | `assigned_stack_height_m` (the order's tallest stack) | `vehicle_type.height_m` | **maximum** - a load is as tall as its tallest stack |

The fill factor is the vehicle type's `load_floor_fill_factor`, else the company's
`company_settings.load_floor_fill_factor`, else the property
`tms.planning.geometry.default-floor-fill-factor` (0.85 in `application.yml`); fleet resolves it
(`EffectiveCapacityResolver`) and planning never re-derives it. A vehicle type without `length_m`,
`width_m` or `height_m` (every type until someone enters them) has **no floor or height limit**, so
nothing changes for it. An order with no measured line has zero floor, no height and
`planning_volume_m3 = total_volume_m3`, so nothing changes for it either.

A unit taller than the vehicle is refused before any of this as `ITEM_TALLER_THAN_VEHICLE`
(`CapacityLimits.admitsUnitHeight`); it is a different statement from "the stack is too tall".

## Where each number comes from

```
used   = SUM over tms.trip_order_assignment WHERE status = 'ACTIVE'   (the database)
limit  = fleet's EffectiveCapacityResolver, or the trip's frozen snapshot
verdict= PlanningCapacityService, inside the transaction that writes
```

**Used** is summed over the *assignment* rows, never over the order headers behind them (the height
is their `MAX`). Since V37 an order may be split across trips by typed amounts
([`SPLIT_ORDER_EXECUTION.md`](SPLIT_ORDER_EXECUTION.md)), and every capacity calculation already
reads the slice each row carries. Since V62 a slice also states its floor (`floorAreaM2`; omitted, it is
the pending floor in the largest proportion the stated measures take of their pending, so the slice
that completes any measure completes the floor and the order converges to `PLANNED`) and carries the
order's tallest stack. Against a vehicle with a height, the rows are re-stacked for that height
(`VehicleLoads`, ADR-022 §5); the ledger keeps the reference-height figures (`DECISIONS_LOG.md`).

**Limits** come from `fleet` and only from `fleet`: `EffectiveCapacityResolver` resolves each
dimension independently (the vehicle's own override if it has one, otherwise its vehicle type's
default), and planning reads the result through `VehicleLookupPort`. There is exactly one resolver
in the codebase, per `docs/architecture/OWNERSHIP_MATRIX.md`, "Capacity checks".

**The verdict** is `PlanningCapacityService`'s, and nobody else's. It is a stateless, repository-free
component - the same shape as `EffectiveCapacityResolver` - so a unit test can exercise every edge
without a database (`PlanningCapacityServiceTest`).

## Live while draft, frozen when confirmed

| Trip status | Source | Where the limits come from |
|---|---|---|
| `DRAFT`, no vehicle | `NONE` | nothing - every dimension is unlimited |
| `DRAFT`, with a vehicle | `LIVE` | the vehicle's current effective capacity, re-read on every check |
| `CONFIRMED` | `SNAPSHOT` | `trip.snapshot_max_weight_kg` / `_volume_m3` / `_pallets` and, since V62, `snapshot_max_floor_area_m2` / `snapshot_max_stack_height_m` / `snapshot_load_width_m`, frozen at confirmation |

While a plan is a draft, live is right: correcting a vehicle type's capacity in fleet must
immediately affect the plan being built on it. Once a plan is confirmed, live would be wrong: a
fleet edit made a week later would silently rewrite what the plan was validated against and make
an audit of it irreproducible. Confirmation therefore copies the three numbers onto the trip, and
the database enforces the coherence of that in both directions:

- `ck_trip_confirmed_is_complete` - a *committed* trip (`CONFIRMED`, `READY_FOR_DISPATCH`,
  `IN_TRANSIT` or `COMPLETED`) has a vehicle, a departure and the three V11 snapshot values. **Not**
  widened by V62: a vehicle type without dimensions still confirms, and its floor and height stay
  unlimited after confirmation;
- `ck_trip_draft_has_no_snapshot` - a `DRAFT` trip has none of them, the three V62 columns included.

So "is this trip reading live or frozen capacity?" is answerable from the row alone, and the API
says which one it used in every capacity response (`source`).

Migration V25 restated both. V11's pair said "`CONFIRMED`" where it meant "the plan is binding",
because `CONFIRMED` was the only such state; the execution states
([`TRIP_EXECUTION_V1.md`](TRIP_EXECUTION_V1.md)) carry the same frozen snapshot, and so does a
trip cancelled *after* it was confirmed. `TripViewAssembler.summarize` therefore asks
`Trip.hasCapacitySnapshot()` - "was this plan ever made binding?" - rather than comparing the
status against a list it would have to keep extending.

`PlanningApiIntegrationTest.confirmationFreezesCapacityAndLocks` shrinks the vehicle type after
confirmation and asserts the confirmed trip still reports what it was validated against.

## Null and zero are different answers

This is the edge the whole model turns on.

| Limit | Meaning | `percentUsed` | `remaining` | `exceeded` |
|---|---|---|---|---|
| `null` | **unlimited** - no vehicle is attached yet, or (floor, height) its type has no such dimension | `null` | `null` | always `false` |
| `0` | **a real limit of nothing** - e.g. a tanker's pallets | `null` | `limit - used` | `used > 0` |
| `> 0` | a normal limit | `used / limit * 100`, one decimal | `limit - used` | `used > limit` |

- Treating a zero limit as "unlimited" would let a bulk-liquid vehicle be loaded with pallets.
- Treating "unlimited" as zero would make a vehicle-less draft trip unusable - and a planner
  routinely sketches a trip before deciding which truck runs it.
- Reporting `0%` for a zero limit would read as "plenty of room"; reporting `100%` would read as
  "full". Both are lies, so the percentage is `null` and the client renders "n/a" rather than a bar.
- **No code path divides by a limit without checking it first.** The single division in
  `PlanningCapacityService.dimension` is guarded by `limit.signum() == 0`.

An unknown contribution counts as **zero used**, never null: an order whose weight is not known
adds nothing, because "unknown" and "none" are indistinguishable at the point a truck is loaded,
and a null would silently disable the limit for the whole trip.

The boundary is inclusive: `used == limit` fits, `used > limit` does not.

## Where the checks fire

| Operation | What is checked |
|---|---|
| assign an order | current active load **+ the order** against the trip's limits |
| move an order | the **target** trip's current load + the order; the source is only released if the target accepts |
| change the vehicle | everything **already on the trip** against the *new* vehicle - a downgrade that no longer fits is refused, and the trip keeps the vehicle it had |
| confirm a run | every trip revalidated from scratch, then frozen |

Every one of them runs inside the transaction that would perform the write, while the caller holds
the trip's row lock. A refusal therefore leaves nothing behind - including the move case, where the
source assignment must survive a target that has no room
(`PlanningApiIntegrationTest.rejectedMoveLeavesTheSourceUnchanged`).

A refusal names **every** dimension that failed, not just the first: a planner who is over on both
weight and pallets needs to know both before choosing another vehicle. Since V62 the message names
`floor area` and `stack height` too, and every capacity view reports `floor`, `height` and the
`bindingDimension` (`WEIGHT|VOLUME|FLOOR|HEIGHT|PALLETS`, the highest percentage) - which is also
reported on every trip an engine proposes.

## The frontend is never trusted

No endpoint accepts a client-supplied used amount or capacity verdict. `AssignOrderRequest` carries
an order id and, for a split, the slice's amounts (weight, volume, pallets, floor) - never more than
is pending, refused by the service and again by `ck_transport_order_not_over_allocated`. The whole
order's amounts and every line's geometry are computed server-side. A browser may render a
utilisation bar; it cannot decide that a truck is full, and sending a hand-crafted request that
claims otherwise changes nothing.
