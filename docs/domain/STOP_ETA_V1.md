# Stop ETA and geofences - V1

*Migration V43. The decision and its justification are `docs/architecture/ADR-011-stop-eta-and-geofence-observation.md`;
this is how the thing behaves.*

---

## 1. What an ETA is here

Departure time, plus per-leg driving time, plus per-stop service time, plus the window the site will
actually receive in. Every term is stored and none is invented:

| Term | Where it comes from |
|---|---|
| Departure | `trip.planned_departure_at` (V11) |
| Driving time per leg | `RoutingPort` / the `travel_estimate` cache (V38, ADR-010) |
| Service time per stop | `location.service_time_minutes` (V14), through `StopServicePort` |
| Window per stop | `trip_stop.service_window_start/end` (V11) |

The arithmetic is `StopScheduleEngine`, a **pure function** - no repository, no clock, no
randomness. An arrival time that cannot be reproduced from its inputs cannot be defended when
somebody disputes it, and a test that needs Docker to check the arithmetic is a test nobody runs
while they are changing the arithmetic.

---

## 2. The three rules

### Rule 1 - an unmeasurable leg ends the chain

A stop whose incoming leg has no travel time gets **no estimate**, and neither does any stop after
it. Not a guess, not the previous stop's time, not zero.

This is the rule the whole feature is judged by. A schedule that silently absorbed one missing leg
would show eight plausible arrival times of which five are wrong, and **nothing on the board would
say which five**. A visible gap looks worse and is true.

It is asserted twice: in the engine's unit tests, and end to end through the API, where the
fixture's coordinate-less origin makes the whole run come back unscheduled rather than invented.

### Rule 2 - provenance degrades and never upgrades

`eta_source` is what the **weakest** leg feeding the stop was measured over. One straight-line leg
makes every stop after it `FALLBACK`, because that is what those arrival times are genuinely built
on. A later measured leg does not repair the estimate it was added to.

There is no `CACHE` value, deliberately. V38 records the defect that makes it absent: serving a
cached row once overwrote `FALLBACK` with `CACHE`, and a straight-line guess became
indistinguishable from a measured road the moment it was stored. Whether an estimate came from a
cache is a fact about the *lookup*; what it was measured over is a fact about the *number*.

### Rule 3 - a window is never made to fit

* **Arriving early is waiting.** The truck is there, the site is not open, the next leg does not
  start until it is. The wait is reported on its own, because a route that only works because a
  truck idles two hours at stop one is a route somebody should look at.
* **Arriving late is flagged, not moved.** The computed arrival is recorded as computed and
  `eta_misses_window` is raised. Quietly rescheduling to the next morning would turn a route that
  does not work into one that appears to.

Lateness is judged on the **arrival**, not on the wait-adjusted service start: a vehicle turning up
after closing has missed the window, and waiting overnight is not what a schedule means.

The window is resolved against the **arrival's own local date**, so a run that crosses midnight
compares against the stop's next morning. Using the shipment's planning date would put the window a
day behind the truck.

---

## 3. Computed on request, and stamped

`POST /planning/trips/{id}/eta`, guarded by `planning.trip:manage` **or** `planning.trip:execute` -
both halves of the day look at a shipment, and this writes no business fact.

**Stamped, not derived on read**, the way V30 stored cost lines: a time a person saw and acted on
must stay reproducible after the master data behind it changed. A shipment whose stops or vehicle
changed has a stale estimate until somebody asks again, and `eta_calculated_at` is how a reader
tells.

**No background job**, and that is a consequence of an open debt rather than an oversight: a loop
recomputing on its own needs an actor to attribute the write to, and `requireAppUserId` rejects
machines by design (D4, JOB 07). Inventing a principal to satisfy an audit column is what that
refusal was about.

**No planned departure, no schedule.** The endpoint refuses with a sentence naming what to do.
Falling back to `now()` would produce a board whose arrival times changed every time somebody
refreshed it.

---

## 4. Geofences

`location.geofence_radius_m` - a circle, in metres. Null is **no geofence**, not a geofence of zero.
Bounds are 25m to 20km: consumer GPS is not accurate below 25m, so a tighter circle would be a
feature that never fires, and one over 20km stops distinguishing this site from the next town.

Set through its own endpoint (`PUT /masterdata/locations/{id}/geofence`) rather than as a field on
the location body, because a geofence is configured once when a site is set up and an address
correction should not re-send a radius nobody looked at. A null radius **clears** it - which is why
it travels in a body and not as a query parameter, that being unable to tell "clear it" from "not
supplied".

### What a geofence does not do

**ADR-007 is not weakened: a position informs a person and moves no lifecycle.**

There is no column on `trip_stop` that a geofence writes, no transition it enables, and no
completion rule it satisfies. `actual_arrival_at` goes on being entered by whoever arrived.

Two reasons, and both are load-bearing:

1. **The feed does not exist.** ADR-007 ships no vendor adapter, by decision, so
   `tms.tracking_position` is empty on every installation that has not written one. A detector
   would sit waiting on an empty table - the feature is inert by design rather than broken, and a
   table accumulating "vehicle entered" rows would suggest otherwise.
2. **It is a different claim.** "A device was near this warehouse" and "the driver arrived" are not
   the same sentence, and the second is the one that gets disputed.

There is deliberately **no geofence table**. A crossing is a function of `tracking_position` and the
radius, both already stored; a third table would be a derived record that can disagree with the two
it came from.

---

## 5. Deliberately not built

* **Route optimisation.** This schedules the planner's sequence and never proposes a better one.
  Still deferred (`CLAUDE.md`).
* **Automatic arrival detection.** See section 4. Still deferred, and now *harder* to add casually:
  the geofence exists, and the rule that it may not move a lifecycle is written beside it.
* **An ETA as a commitment.** Every column is named `eta_`, so none reads as a promise made to a
  customer.
* **Per-stop time zones.** One zone for the whole run - the first stop's, falling back to the
  company's. A shipment crossing a zone boundary has its later windows read in the first stop's
  zone. Peru has one zone and this is exact there; it is named here rather than hidden, and the fix
  is one method.
