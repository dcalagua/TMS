-- TMS by EBIM - V19 planning/shipment V2: the two things a planned trip needs before it can be
-- published as a Shipment to an external system, and nothing else.
--
-- Job 07's shipment header asks a confirmed trip to expose company, shipment number, planning
-- run/date, origin, carrier, vehicle, plate, vehicle type, planned departure, ordered stops,
-- associated orders, status, capacity snapshot and current utilisation. Almost all of that is
-- already reachable from V11/V16 and is therefore NOT duplicated here - the read model resolves
-- it (see docs/domain/SHIPMENT_V2.md, "Derived, not stored"):
--
--   * company              - tms.trip.company_id (V11)
--   * planning run + date  - tms.trip.planning_run_id / tms.trip.planning_date (V11/V16)
--   * origin               - tms.planning_run.origin_id; a trip deliberately has no origin of
--                            its own, so trip and run can never disagree (V11's reasoning)
--   * carrier              - tms.trip.carrier_id, already stored explicitly rather than derived
--                            from the vehicle, so moving a vehicle between carriers cannot
--                            rewrite who was planned to run a confirmed trip (V11)
--   * vehicle/plate/type   - tms.vehicle + tms.vehicle_type (V9), resolved live for display
--   * stops/orders         - tms.trip_stop / tms.trip_order_assignment (V11)
--   * capacity snapshot    - tms.trip.snapshot_max_* (V11)
--   * stop coordinates     - tms.destination.latitude/longitude (V7); a stop stores no copy,
--                            because a corrected store coordinate must reach an open plan
--                            immediately and a wrong frozen one would be undetectable
--
-- Two things genuinely cannot be derived, and both are added here:
--
--   1. shipment_number - a stable, installation-wide identifier for the trip as an *external*
--      artefact. tms.trip.trip_number is unique only inside its planning run ("trip 2 of PL-17"),
--      which is the right identity for the board and a useless one for an outbound integration
--      or a printed manifest. Same idiom as plan_number (V11) and order_number (V10): a
--      system-generated sequence, so DATA_MODEL.md section 9 rule 9's cross-tenant-leak concern
--      about globally unique business keys does not apply - nobody types or guesses it.
--
--   2. route_id - the optional link from a planned shipment back to the master tms.route it was
--      built from. Nullable and non-authoritative on purpose; see the column comment.
--
-- Deliberately NOT here:
--   * no planned arrival/service time per stop - tms.trip has one optional planned_departure_at
--     and no travel-time model; storing a per-stop ETA would be inventing the routing that
--     CLAUDE.md defers by decision (OR-Tools). V16 already documents the same limitation for the
--     double-booking rule.
--   * no stored used-weight/volume/pallet totals on tms.trip - they are one grouped SUM over
--     active tms.trip_order_assignment rows, and a stored copy is a second source of truth that
--     a concurrent assignment can leave stale. See CAPACITY_MODEL.md, "Where each number comes
--     from".
--   * no contiguity trigger on tms.trip_stop.sequence - V11's header rules out triggers carrying
--     planning logic, so "sequences are 1..N with no gaps and no duplicates" stays a Java
--     invariant (Trip.renumber + Trip.assertStopSequenceIntegrity, exercised by
--     PlanningApiIntegrationTest) on top of the declarative uq_trip_stop_trip_sequence.

-- ---------------------------------------------------------------------------
-- Shipment number sequence
-- ---------------------------------------------------------------------------
CREATE SEQUENCE tms.shipment_number_seq AS bigint INCREMENT BY 1 START WITH 1 NO CYCLE;

COMMENT ON SEQUENCE tms.shipment_number_seq IS
    'Feeds tms.trip.shipment_number. Global, not company-scoped, exactly like '
    'tms.planning_run_number_seq and tms.transport_order_number_seq: a system-generated opaque '
    'number nobody types carries none of rule 9''s cross-tenant enumeration risk.';

-- ---------------------------------------------------------------------------
-- tms.trip.shipment_number
-- ---------------------------------------------------------------------------
ALTER TABLE tms.trip ADD COLUMN shipment_number text;

-- nextval() is volatile, so this draws one distinct value per existing row rather than one
-- value reused for all of them. Cancelled trips are numbered too: a shipment number identifies
-- the trip, not its outcome, and a gap in the series would be harder to explain than a
-- cancelled shipment that has one.
UPDATE tms.trip
SET shipment_number = 'SH-' || lpad(nextval('tms.shipment_number_seq')::text, 8, '0');

ALTER TABLE tms.trip ALTER COLUMN shipment_number SET NOT NULL;

-- The default is what makes "every trip has a shipment number" true of the *table* and not only
-- of the one service that writes it. TripService.generateShipmentNumber() still assigns the value
-- explicitly - Hibernate always names the column, so this default never fires from the
-- application - but a raw INSERT (a data fix, a test fixture, a future bulk import written in
-- SQL) now gets a collision-free number instead of failing the NOT NULL. Same sequence, same
-- format, one source of truth.
ALTER TABLE tms.trip ALTER COLUMN shipment_number
    SET DEFAULT 'SH-' || lpad(nextval('tms.shipment_number_seq')::text, 8, '0');

ALTER TABLE tms.trip ADD CONSTRAINT uq_trip_shipment_number UNIQUE (shipment_number);
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_shipment_number_not_blank
    CHECK (btrim(shipment_number) <> '');

COMMENT ON COLUMN tms.trip.shipment_number IS
    'The trip''s identity as an external Shipment: stable, installation-wide unique, assigned '
    'once at creation and never reissued. tms.trip.trip_number stays the planner-facing identity '
    '*inside* one planning run ("trip 2 of PL-00000017") and is not usable outside it. Formatted '
    'by TripService as SH- plus the zero-padded value of tms.shipment_number_seq.';

-- ---------------------------------------------------------------------------
-- tms.trip.route_id - the master route this shipment was built from
-- ---------------------------------------------------------------------------
ALTER TABLE tms.trip ADD COLUMN route_id uuid;

-- MATCH SIMPLE (default): satisfied while route_id IS NULL, enforced otherwise - the same idiom
-- tms.trip.vehicle_id (V11) and tms.destination.zone_id (V7) use. The composite half is rule 6's
-- tenant guarantee: a shipment of company A can never point at a route of company B, whatever
-- the service layer does.
ALTER TABLE tms.trip ADD CONSTRAINT fk_trip_route FOREIGN KEY (route_id)
    REFERENCES tms.route (id) ON DELETE RESTRICT;
ALTER TABLE tms.trip ADD CONSTRAINT fk_trip_route_company FOREIGN KEY (route_id, company_id)
    REFERENCES tms.route (id, company_id);

CREATE INDEX ix_trip_route ON tms.trip (route_id) WHERE route_id IS NOT NULL;

COMMENT ON COLUMN tms.trip.route_id IS
    'Optional suggestion/template link to the master tms.route a planner built this shipment '
    'from - never a constraint on it. V8 already framed a route as "a named, reusable sequence a '
    'planner can point a Trip at later", and this is that pointer. Three things it deliberately '
    'does NOT mean, all of them enforced by their absence rather than by a check: the shipment''s '
    'stops are not required to equal the route''s stops, are not required to stay equal after '
    'the route master is edited, and are never rebuilt from the route - tms.trip_stop always '
    'follows the trip''s own active assignments (V11). Applying a route reorders the stops the '
    'shipment already has into the route''s relative order and stops there. See '
    'docs/domain/SHIPMENT_V2.md, "Route master interaction".';
