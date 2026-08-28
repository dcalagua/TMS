# ADR-011 - Stop ETA, and geofences that inform rather than decide

**Status:** Accepted
**Date:** 2026-08-28
**Migration:** V43
**Supersedes in part:** the "Deliberately NOT here" note in V27 that refused per-stop planned times
**Constrained by:** ADR-007 (tracking provider port), ADR-010 (routing provider port)

## Context

Two things sit on the deferred list in `CLAUDE.md`: "ETA calculation, geofencing and automatic
arrival detection remain deferred." This ADR moves one of them and leaves the other two where they
are, and the difference between those cases is the whole substance of the decision.

### Why ETA was deferred, and what changed

V27 refused to add `planned_arrival_at` per stop, and gave a specific reason rather than a
preference:

> There is nothing to put in them: TMS has no ETA engine, and route optimisation is deferred by
> decision. Two columns holding an arrival time nobody computes would read as a plan the actual
> times could be judged against, and there would be no such plan.

That objection was about **inputs, not appetite**. It has since stopped being true:

* **V38 / ADR-010** gave TMS per-leg travel distance and duration behind `RoutingProviderPort`, with
  a cache and an explicit provenance (`MEASURED_ROUTE`, `FALLBACK`, and whether a row was served
  from cache).
* **V14** has carried `location.service_time_minutes` since the canonical location model landed.
* **V11** has carried each stop's `service_window_start` / `service_window_end`.

Departure time plus per-leg driving time plus per-stop service time plus the window a site will
actually receive in is an arrival time. Every term is stored, none is invented. The thing V27 said
did not exist now does.

### Why geofencing and automatic arrival detection do not move

ADR-007 states the rule this ADR will not weaken: **positions inform people and never move a
lifecycle**, so losing them costs a map and no business fact. It also ships **no vendor adapter**,
by decision, pending a concrete customer requirement.

Both of those still hold. An automatic arrival detection that wrote `ARRIVED` onto a stop would:

1. make a business fact depend on a feed that, today, nothing supplies - ADR-007 has no adapter, so
   the detector would sit waiting on an empty table; and
2. make the shipment record say a driver arrived somewhere because a device was near it, which is a
   different claim, and the one that gets disputed.

So the geofence in V43 is a **circle around a location and nothing more**, and what it produces is
an *observation* - "a reported position of this shipment fell inside the geofence of stop 3 at
09:12" - which a person reads. It changes no status, satisfies no completion rule, and no service
consults it before allowing a transition.

## Decision

### 1. Stops carry a computed ETA, with the provenance of the estimate it came from

`tms.trip_stop` gains `eta_arrival_at`, `eta_departure_at`, `eta_source` and `eta_calculated_at`.

The ETA is **computed and stamped**, not derived on read, for the reason V30 stored cost lines
rather than recalculating them: a number a person saw and acted on must still be reproducible after
the master data behind it changed.

`eta_source` records what the *weakest* leg feeding this stop was - `MEASURED_ROUTE` only when every
leg up to and including it was measured, `FALLBACK` the moment one was a straight line. That
direction is deliberate: JOB 04 shipped a defect where serving a cached row overwrote `FALLBACK`
with `CACHE` and a straight-line guess became indistinguishable from a measured road. Provenance
degrades along the chain; it never upgrades.

### 2. An unmeasurable leg ends the chain

A stop whose incoming leg could not be measured gets **no ETA at all**, and neither does any stop
after it. Not a guess, not the previous stop's time, not a zero.

This is the single most important rule in V43. A schedule with one missing leg silently absorbed
would show eight plausible arrival times of which five are wrong, and nothing on the screen would
say which. A visible gap is a worse-looking board and a truthful one.

### 3. The ETA respects the window it is arriving into, and says when it cannot

If the vehicle would arrive before `service_window_start`, the ETA for *departure* accounts for the
wait: the truck is there, and the site is not open, and the next leg does not begin until it is.
If the vehicle would arrive after `service_window_end`, the arrival time is recorded as computed and
the stop is flagged as missing its window. **The engine never quietly moves an arrival to make a
window fit** - that would turn a schedule that does not work into one that appears to.

### 4. The engine is a pure function

`StopScheduleEngine` takes a departure instant, the legs, the service times and the windows, and
returns the schedule. No repository, no clock, no randomness - the same shape ADR's on planning
engines established, and for the same reason: an ETA that cannot be reproduced from its inputs
cannot be defended when somebody disputes it.

### 5. Geofences are on the location, and produce observations

`tms.location.geofence_radius_m` - nullable, and null means the location has no geofence rather than
a geofence of zero. Evaluation is great-circle distance against `tms.tracking_position` rows that
already exist; TMS ships no vendor adapter to fill that table (ADR-007), so on most installations
there is nothing to evaluate and the feature is inert by design rather than broken.

An observation is **read-only output**. There is no column on `trip_stop` that a geofence writes,
no transition it enables and no completion rule it satisfies.

## Consequences

**Good.** A dispatcher can see when a shipment is expected at each stop, and whether that is a
measured road or a straight-line guess. A stop that cannot be scheduled says so. Nothing about
execution changes: `actual_arrival_at` is still written by a person, and is still the only arrival
TMS treats as a fact.

**Costly.** Every trip whose stops or vehicle change has a stale ETA until it is recomputed, and
V43 recomputes on explicit request rather than on every write - a background recomputation loop
would need the system-actor model debt D4 has been holding open since JOB 07.

**Still deferred.** Route optimisation (choosing the sequence) is untouched: this schedules the
planner's sequence and does not propose a better one. Automatic arrival detection stays deferred,
and this ADR is the reason it is now *harder* to add casually - the geofence exists, and the rule
that it may not move a lifecycle is written down beside it.
