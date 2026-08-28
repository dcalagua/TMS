-- ===========================================================================
-- V43 - When the vehicle is expected at each stop, and where a location is
-- ===========================================================================
--
-- See docs/architecture/ADR-011-stop-eta-and-geofence-observation.md. The short version, because
-- this migration reverses something an earlier one refused on the record.
--
-- ---------------------------------------------------------------------------
-- 1. Why V27 said no, and why that answer has expired
-- ---------------------------------------------------------------------------
--
-- V27 refused per-stop planned times and gave a reason rather than a preference:
--
--     "There is nothing to put in them: TMS has no ETA engine, and route optimisation is deferred
--      by decision. Two columns holding an arrival time nobody computes would read as a plan the
--      actual times could be judged against, and there would be no such plan."
--
-- That objection was about INPUTS. All three now exist:
--
--   * V38 (ADR-010) gives per-leg driving distance and duration behind RoutingProviderPort, with
--     the provenance of each estimate.
--   * V14 has carried location.service_time_minutes since the canonical location model.
--   * V11 has carried each stop's service_window_start / service_window_end.
--
-- Departure + driving + service time + the window a site will actually receive in IS an arrival
-- time. Every term is stored. None is invented. So the columns V27 refused now have something to
-- put in them, and this migration adds exactly those and nothing more.
--
-- Route optimisation stays deferred: this schedules the planner's sequence and never proposes a
-- better one.
ALTER TABLE tms.trip_stop ADD COLUMN eta_arrival_at    timestamptz;
ALTER TABLE tms.trip_stop ADD COLUMN eta_departure_at  timestamptz;
ALTER TABLE tms.trip_stop ADD COLUMN eta_source        text;
ALTER TABLE tms.trip_stop ADD COLUMN eta_calculated_at timestamptz;
-- Whether the schedule has this stop arriving after its window closes. Stored rather than derived
-- because it is a property of the calculation and not of the row: recomputing it later against a
-- window somebody has since widened would quietly erase the warning a planner acted on.
ALTER TABLE tms.trip_stop ADD COLUMN eta_misses_window boolean NOT NULL DEFAULT false;

-- The ETA is computed and STAMPED, not derived on read - the same decision V30 made for cost
-- lines, for the same reason: a number a person saw and acted on has to stay reproducible after
-- the master data behind it changed.
ALTER TABLE tms.trip_stop ADD CONSTRAINT ck_trip_stop_eta_complete CHECK (
    (eta_arrival_at IS NULL AND eta_departure_at IS NULL AND eta_source IS NULL
        AND eta_calculated_at IS NULL AND eta_misses_window = false)
    OR (eta_arrival_at IS NOT NULL AND eta_departure_at IS NOT NULL AND eta_source IS NOT NULL
        AND eta_calculated_at IS NOT NULL));

-- A stop cannot be scheduled to leave before it arrives. The engine builds departure from arrival,
-- so this is the backstop for a raw data fix rather than for a code path.
ALTER TABLE tms.trip_stop ADD CONSTRAINT ck_trip_stop_eta_ordered CHECK (
    eta_arrival_at IS NULL OR eta_departure_at >= eta_arrival_at);

-- What the WEAKEST leg feeding this stop was.
--
-- The direction is the point. JOB 04 shipped a defect where serving a cached row overwrote
-- FALLBACK with CACHE, and a straight-line guess became indistinguishable from a measured road
-- once stored. Provenance degrades along a chain and never upgrades: one straight-line leg makes
-- every stop after it FALLBACK, because that is what those arrival times are actually built on.
ALTER TABLE tms.trip_stop ADD CONSTRAINT ck_trip_stop_eta_source CHECK (
    eta_source IS NULL OR eta_source IN ('MEASURED_ROUTE', 'FALLBACK'));

COMMENT ON COLUMN tms.trip_stop.eta_arrival_at IS
    'When the vehicle is expected here (V43, ADR-011). NULL means NO ESTIMATE - a leg on the way '
    'could not be measured, so this stop and every stop after it have none. Deliberately not a '
    'guess and not the previous stop''s time: a schedule with one missing leg silently absorbed '
    'would show plausible arrival times of which several are wrong, with nothing saying which.';

COMMENT ON COLUMN tms.trip_stop.eta_source IS
    'MEASURED_ROUTE only when EVERY leg up to and including this stop was measured; FALLBACK the '
    'moment one was a straight line (V43). Never upgrades along the chain - see V38 and the '
    'RoutingSource.CACHE defect it records.';

COMMENT ON COLUMN tms.trip_stop.eta_misses_window IS
    'The schedule has the vehicle arriving after service_window_end (V43). The engine records the '
    'arrival it computed and raises this: quietly moving an arrival to make a window fit would '
    'turn a schedule that does not work into one that appears to.';

-- ---------------------------------------------------------------------------
-- 2. Where a location is, as a circle
-- ---------------------------------------------------------------------------
--
-- Nullable, and NULL means "this location has no geofence" rather than "a geofence of zero
-- metres" - the distinction V22 drew for capacity overrides and this follows.
ALTER TABLE tms.location ADD COLUMN geofence_radius_m integer;

ALTER TABLE tms.location ADD CONSTRAINT ck_location_geofence_radius CHECK (
    geofence_radius_m IS NULL OR (geofence_radius_m BETWEEN 25 AND 20000));

COMMENT ON COLUMN tms.location.geofence_radius_m IS
    'The radius, in metres, of a circle around this location (V43, ADR-011). NULL is "no '
    'geofence", not "zero". Lower bound 25m because consumer GPS is not more accurate than that '
    'and a tighter circle would produce a feature that never fires; upper bound 20km because a '
    'circle larger than that stops distinguishing this site from the next town. '
    'ADR-007 STILL HOLDS: a position inside this circle informs a person and MOVES NO LIFECYCLE. '
    'There is no column on tms.trip_stop that a geofence writes and no transition it enables.';

-- No geofence table, and no stored "vehicle entered" rows.
--
-- A geofence crossing is a function of tms.tracking_position (V29) and this radius, both of which
-- are already stored, so a third table would be a derived record that can disagree with the two
-- it came from. And TMS ships no vendor adapter to fill tracking_position (ADR-007), so on most
-- installations there is nothing to derive: the feature is inert by design rather than broken, and
-- a table accumulating nothing would suggest otherwise.

-- ---------------------------------------------------------------------------
-- 3. Deliberately NOT here
-- ---------------------------------------------------------------------------
--
--   * No automatic arrival detection. ADR-007's rule is not weakened: positions inform people and
--     never move a lifecycle. A detector writing ARRIVED onto a stop would make a business fact
--     depend on a feed nothing currently supplies, and would make the record say a driver arrived
--     because a device was near - a different claim, and the one that gets disputed.
--     actual_arrival_at goes on being written by a person.
--   * No ETA recomputation trigger, and no background job. A trip whose stops or vehicle change
--     has a stale ETA until somebody asks for a new one, and eta_calculated_at is how a reader
--     tells. A loop that recomputed on its own would need an actor to attribute the write to, and
--     that is open debt D4 - inventing a principal to satisfy an audit column is what JOB 07
--     refused.
--   * No planned_arrival_at as a commitment. This is an estimate, named eta_ throughout so no
--     column reads as a promise made to a customer.
