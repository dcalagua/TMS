-- TMS by EBIM - V11 manual planning: planning runs, trips, trip stops and the explicit
-- order-to-trip assignment aggregate.
--
-- Follows V6-V10's shape unchanged (docs/database/DATA_MODEL.md section 13): company_id NOT NULL
-- with an FK and a leading index, actor columns, RLS enabled in this same migration, the
-- composite-FK tenant guarantee (rule 6) for every reference into another company-scoped table,
-- rule 7's denormalized company_id on children that themselves reference a company-scoped table,
-- rule 8's DEFERRABLE position constraint for the reorderable stop list, rule 9's exception for a
-- system-generated number (plan_number, the reasoning section 12.1 documents for order_number),
-- rule 11's version column on everything two planners can edit in separate round trips, and rules
-- 12-13 (a partial unique index for the open-assignment invariant, and closing a superseded row
-- instead of overwriting it), which this migration is the first to need - see section 14.
--
-- The permissions this module needs (planning.plan:read/manage from V5, planning.trip:read/manage
-- from V3) already exist and are already granted - nothing to add there.
--
-- Deliberately NOT here (see docs/domain/PLANNING_MANUAL_V1.md):
--   * no solver/optimizer state of any kind - manual planning only (OR-Tools is deferred by
--     decision), though `mode` already distinguishes MANUAL from a future AUTOMATIC run;
--   * no loading/dispatch/execution states - EWM and transport execution are separate scopes;
--   * no trigger carrying planning logic. Every invariant here is either a declarative
--     constraint or Java in com.ebim.tms.planning.application.

-- ---------------------------------------------------------------------------
-- Prerequisite from V9: tms.vehicle needs the composite-FK target every other company-scoped
-- table already has (rule 6). V9 gave carrier and vehicle_type theirs; vehicle never needed one
-- because nothing referenced it yet. tms.trip does.
-- ---------------------------------------------------------------------------
ALTER TABLE tms.vehicle ADD CONSTRAINT uq_vehicle_id_company UNIQUE (id, company_id);

-- ---------------------------------------------------------------------------
-- Planning run number sequence.
-- ---------------------------------------------------------------------------
-- Same idiom as tms.transport_order_number_seq (V10): a system-generated, immutable identifier
-- nobody types or guesses, so a global (not company-scoped) uniqueness constraint carries none of
-- rule 9's cross-tenant leak risk. PlanningRunService.generatePlanNumber() formats 'PL-' || the
-- zero-padded value; the sequence lives in the database so a raw SQL insert can still obtain a
-- collision-free number without depending on application code.
CREATE SEQUENCE tms.planning_run_number_seq AS bigint INCREMENT BY 1 START WITH 1 NO CYCLE;

-- ---------------------------------------------------------------------------
-- tms.planning_run - one planning session for a company/origin/planning date
-- ---------------------------------------------------------------------------
CREATE TABLE tms.planning_run (
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid        NOT NULL,
    plan_number         text        NOT NULL,
    -- The depot/warehouse every trip in this run departs from. A trip does NOT repeat it: trips
    -- inherit the run's origin, so the two can never diverge (see tms.trip's comment).
    origin_id           uuid        NOT NULL,
    -- The service/planning date this run plans for. An order is assignable to this run only when
    -- its service_date equals this value (PLANNING_MANUAL_V1.md, "Eligibility").
    planning_date       date        NOT NULL,
    -- MANUAL today; the column exists so an automatic run is a new value here rather than a new
    -- table. No solver exists and none is implied - see the migration header.
    mode                text        NOT NULL DEFAULT 'MANUAL',
    status              text        NOT NULL DEFAULT 'DRAFT',
    notes               text,
    confirmed_at        timestamptz,
    confirmed_by        uuid,
    cancelled_at        timestamptz,
    cancelled_by        uuid,
    cancel_reason       text,
    -- Rule 11: two planners can open the same run in separate round trips.
    version             bigint      NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_by          uuid,
    CONSTRAINT pk_planning_run PRIMARY KEY (id),
    CONSTRAINT fk_planning_run_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- Composite-FK target for tms.trip (rule 6).
    CONSTRAINT uq_planning_run_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_planning_run_number UNIQUE (plan_number),
    CONSTRAINT ck_planning_run_number_not_blank CHECK (btrim(plan_number) <> ''),
    CONSTRAINT fk_planning_run_origin FOREIGN KEY (origin_id)
        REFERENCES tms.origin (id) ON DELETE RESTRICT,
    CONSTRAINT fk_planning_run_origin_company FOREIGN KEY (origin_id, company_id)
        REFERENCES tms.origin (id, company_id),
    CONSTRAINT ck_planning_run_mode CHECK (mode IN ('MANUAL', 'AUTOMATIC')),
    CONSTRAINT ck_planning_run_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_planning_run_notes_not_blank CHECK (notes IS NULL OR btrim(notes) <> ''),
    -- The two terminal transitions stamp who/when, and only when they actually happened: the
    -- same "both or neither" shape ck_transport_order_window_pair uses (V10).
    CONSTRAINT ck_planning_run_confirmed_pair
        CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL)),
    CONSTRAINT ck_planning_run_cancelled_pair
        CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    CONSTRAINT ck_planning_run_cancel_reason_requires_cancelled
        CHECK (cancel_reason IS NULL OR status = 'CANCELLED'),
    CONSTRAINT ck_planning_run_cancel_reason_not_blank
        CHECK (cancel_reason IS NULL OR btrim(cancel_reason) <> ''),
    CONSTRAINT fk_planning_run_confirmed_by FOREIGN KEY (confirmed_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_planning_run_cancelled_by FOREIGN KEY (cancelled_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_planning_run_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_planning_run_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_planning_run_company ON tms.planning_run (company_id);
CREATE INDEX ix_planning_run_company_date ON tms.planning_run (company_id, planning_date);
CREATE INDEX ix_planning_run_company_status ON tms.planning_run (company_id, status);
CREATE INDEX ix_planning_run_origin ON tms.planning_run (origin_id);

-- At most one *open* (DRAFT) run per company/origin/planning date: two planners building two
-- competing drafts for the same depot and day would fight over the same eligible orders, and the
-- winner would be whoever confirmed last. Partial on purpose - once a run is CONFIRMED or
-- CANCELLED, planning the same origin/date again (a re-plan) is legitimate and unblocked.
CREATE UNIQUE INDEX uq_planning_run_open_scope
    ON tms.planning_run (company_id, origin_id, planning_date)
    WHERE status = 'DRAFT';

COMMENT ON TABLE tms.planning_run IS
    'One manual planning session for a company/origin/planning date (migration V11): the '
    'container the trips of that day hang from. Lifecycle DRAFT -> CONFIRMED | CANCELLED, '
    'documented in docs/domain/PLANNING_MANUAL_V1.md. Carries no counters or totals - every '
    'summary a screen needs is one grouped query away (see section "No stored counters").';
COMMENT ON COLUMN tms.planning_run.mode IS
    'MANUAL in V1. The column exists so a future automatic run is a value here rather than a '
    'second table; no solver exists and OR-Tools remains deferred by decision.';

CREATE TRIGGER tr_planning_run_set_updated_at
    BEFORE UPDATE ON tms.planning_run
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.planning_run ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- tms.trip - one vehicle's planned journey inside a planning run
-- ---------------------------------------------------------------------------
-- Deliberately has no origin_id of its own: a trip departs from its run's origin, so the
-- "company/origin consistency" the step brief asks for is structural rather than a constraint
-- that could ever be violated. company_id is denormalized from the run (rule 7) because trip
-- itself references company-scoped tables (vehicle, carrier) and must carry rule 6's guarantee.
CREATE TABLE tms.trip (
    id                      uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id              uuid          NOT NULL,
    planning_run_id         uuid          NOT NULL,
    -- 1-based, assigned by TripService from the run's current maximum, unique per run. Not
    -- reorderable (a trip's number is its identity inside the run, unlike a stop's position), so
    -- unlike uq_trip_stop_trip_sequence this constraint is NOT deferrable.
    trip_number             integer       NOT NULL,
    -- Optional while DRAFT: a planner may sketch a trip before deciding which vehicle runs it.
    -- Required to confirm (ck_trip_confirmed_is_complete below).
    vehicle_id              uuid,
    -- Reference, not a copy of the carrier's data: resolved from the vehicle at assignment time
    -- and stored explicitly so a later change to the vehicle master (a vehicle moved to another
    -- carrier) cannot silently rewrite who was planned to run this trip.
    carrier_id              uuid,
    planned_departure_at    timestamptz,
    status                  text          NOT NULL DEFAULT 'DRAFT',
    -- Capacity strategy (docs/domain/CAPACITY_MODEL.md, "Live while DRAFT, snapshot at
    -- confirmation"): while the trip is DRAFT these are NULL and every capacity check resolves
    -- the vehicle's *current* effective capacity live, so a fleet correction is picked up
    -- immediately. Confirmation freezes the three numbers here, so a later fleet edit can never
    -- retro-invalidate a confirmed plan or make an audit of it irreproducible.
    snapshot_max_weight_kg  numeric(10,2),
    snapshot_max_volume_m3  numeric(10,3),
    snapshot_max_pallets    integer,
    capacity_snapshot_at    timestamptz,
    confirmed_at            timestamptz,
    confirmed_by            uuid,
    cancelled_at            timestamptz,
    cancelled_by            uuid,
    cancel_reason           text,
    version                 bigint        NOT NULL DEFAULT 0,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    created_by              uuid,
    updated_by              uuid,
    CONSTRAINT pk_trip PRIMARY KEY (id),
    CONSTRAINT fk_trip_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- Composite-FK target for tms.trip_stop and tms.trip_order_assignment (rule 6).
    CONSTRAINT uq_trip_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_trip_run_number UNIQUE (planning_run_id, trip_number),
    -- RESTRICT, not CASCADE: a planning run is never deleted (no endpoint deletes one - see
    -- DATA_MODEL.md section 3.5, "deletes never erase history"); cancellation is the terminal
    -- state, and a cancelled run keeps its trips for audit.
    CONSTRAINT fk_trip_planning_run FOREIGN KEY (planning_run_id)
        REFERENCES tms.planning_run (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_planning_run_company FOREIGN KEY (planning_run_id, company_id)
        REFERENCES tms.planning_run (id, company_id),
    CONSTRAINT ck_trip_number_positive CHECK (trip_number >= 1),
    -- MATCH SIMPLE (default): satisfied while vehicle_id IS NULL, enforced otherwise - the same
    -- idiom vehicle.carrier_id (V9) and destination.zone_id (V7) use.
    CONSTRAINT fk_trip_vehicle FOREIGN KEY (vehicle_id)
        REFERENCES tms.vehicle (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_vehicle_company FOREIGN KEY (vehicle_id, company_id)
        REFERENCES tms.vehicle (id, company_id),
    CONSTRAINT fk_trip_carrier FOREIGN KEY (carrier_id)
        REFERENCES tms.carrier (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_carrier_company FOREIGN KEY (carrier_id, company_id)
        REFERENCES tms.carrier (id, company_id),
    -- A carrier without a vehicle would be a plan for "somebody's truck": refused.
    CONSTRAINT ck_trip_carrier_requires_vehicle CHECK (carrier_id IS NULL OR vehicle_id IS NOT NULL),
    CONSTRAINT ck_trip_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    -- What "confirmed" means, expressed declaratively rather than trusted to the service: a
    -- confirmed trip has a vehicle, a departure time and the frozen capacity it was validated
    -- against. TripService checks the same things first with caller-facing messages; this is the
    -- backstop that makes an incoherent row impossible even from raw SQL.
    CONSTRAINT ck_trip_confirmed_is_complete CHECK (
        status <> 'CONFIRMED' OR (
            vehicle_id IS NOT NULL
            AND planned_departure_at IS NOT NULL
            AND snapshot_max_weight_kg IS NOT NULL
            AND snapshot_max_volume_m3 IS NOT NULL
            AND snapshot_max_pallets IS NOT NULL
            AND capacity_snapshot_at IS NOT NULL)),
    -- ...and the converse: only a confirmed trip carries a capacity snapshot, so "is this trip
    -- reading live or frozen capacity" is answerable from the row alone.
    CONSTRAINT ck_trip_snapshot_requires_confirmed CHECK (
        status = 'CONFIRMED' OR (
            snapshot_max_weight_kg IS NULL
            AND snapshot_max_volume_m3 IS NULL
            AND snapshot_max_pallets IS NULL
            AND capacity_snapshot_at IS NULL)),
    CONSTRAINT ck_trip_snapshot_weight_positive
        CHECK (snapshot_max_weight_kg IS NULL OR snapshot_max_weight_kg > 0),
    CONSTRAINT ck_trip_snapshot_volume_positive
        CHECK (snapshot_max_volume_m3 IS NULL OR snapshot_max_volume_m3 > 0),
    CONSTRAINT ck_trip_snapshot_pallets_nonnegative
        CHECK (snapshot_max_pallets IS NULL OR snapshot_max_pallets >= 0),
    CONSTRAINT ck_trip_confirmed_pair CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL)),
    CONSTRAINT ck_trip_cancelled_pair CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    CONSTRAINT ck_trip_cancel_reason_requires_cancelled
        CHECK (cancel_reason IS NULL OR status = 'CANCELLED'),
    CONSTRAINT ck_trip_cancel_reason_not_blank CHECK (cancel_reason IS NULL OR btrim(cancel_reason) <> ''),
    CONSTRAINT fk_trip_confirmed_by FOREIGN KEY (confirmed_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_cancelled_by FOREIGN KEY (cancelled_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_trip_company ON tms.trip (company_id);
CREATE INDEX ix_trip_planning_run ON tms.trip (planning_run_id);
CREATE INDEX ix_trip_vehicle ON tms.trip (vehicle_id) WHERE vehicle_id IS NOT NULL;
CREATE INDEX ix_trip_carrier ON tms.trip (carrier_id) WHERE carrier_id IS NOT NULL;

COMMENT ON TABLE tms.trip IS
    'One vehicle''s planned journey inside a tms.planning_run (migration V11). Inherits the '
    'run''s origin rather than repeating it. Capacity is live from the vehicle while DRAFT and '
    'frozen into snapshot_max_* at confirmation - see docs/domain/CAPACITY_MODEL.md.';
COMMENT ON COLUMN tms.trip.snapshot_max_weight_kg IS
    'NULL while DRAFT (capacity resolves live from the vehicle); frozen at confirmation so a '
    'later fleet edit cannot retro-invalidate a confirmed plan.';

CREATE TRIGGER tr_trip_set_updated_at
    BEFORE UPDATE ON tms.trip
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.trip ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- tms.trip_stop - the planning-instance stop list of one trip
-- ---------------------------------------------------------------------------
-- NOT tms.route_stop: a route stop belongs to a reusable master corridor, a trip stop belongs to
-- one dated planning instance and carries the service window that this day's assigned orders
-- actually asked for. The two never share rows and neither references the other - a trip is not
-- required to follow any master route in V1.
CREATE TABLE tms.trip_stop (
    id                    uuid        NOT NULL DEFAULT gen_random_uuid(),
    trip_id               uuid        NOT NULL,
    -- Denormalized from the parent trip (rule 7) so the destination reference can carry rule 6's
    -- composite-FK tenant guarantee, exactly like tms.route_stop (V8).
    company_id            uuid        NOT NULL,
    destination_id        uuid        NOT NULL,
    sequence              integer     NOT NULL,
    -- Snapshot of the service window this stop must respect, computed by TripStopPlanner as the
    -- envelope (earliest start, latest end) of the requested windows of the orders currently
    -- assigned to this trip for this destination; NULL when no assigned order declares one.
    -- Recomputed whenever the trip's assignments change - see CAPACITY_MODEL.md's sibling
    -- section in PLANNING_MANUAL_V1.md, "Stops follow assignments".
    service_window_start  time,
    service_window_end    time,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    created_by            uuid,
    updated_by            uuid,
    CONSTRAINT pk_trip_stop PRIMARY KEY (id),
    CONSTRAINT fk_trip_stop_trip FOREIGN KEY (trip_id)
        REFERENCES tms.trip (id) ON DELETE CASCADE,
    CONSTRAINT fk_trip_stop_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_stop_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),
    CONSTRAINT fk_trip_stop_destination FOREIGN KEY (destination_id)
        REFERENCES tms.destination (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_stop_destination_company FOREIGN KEY (destination_id, company_id)
        REFERENCES tms.destination (id, company_id),
    CONSTRAINT ck_trip_stop_sequence_positive CHECK (sequence >= 1),
    -- Both DEFERRABLE for rule 8's reason (a reorder passes through a transient duplicate
    -- position mid-transaction) and for V10's reason (TripStopPlanner may add a destination back
    -- in the same flush in which the old row is removed, and Hibernate flushes insertions before
    -- orphan-removal deletions). One destination appears at most once per trip in V1: a trip that
    -- must visit the same customer twice in one day is two trips.
    CONSTRAINT uq_trip_stop_trip_sequence UNIQUE (trip_id, sequence) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT uq_trip_stop_trip_destination UNIQUE (trip_id, destination_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_trip_stop_window_pair
        CHECK ((service_window_start IS NULL) = (service_window_end IS NULL)),
    CONSTRAINT ck_trip_stop_window_order
        CHECK (service_window_start IS NULL OR service_window_start < service_window_end),
    CONSTRAINT fk_trip_stop_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_stop_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_trip_stop_trip ON tms.trip_stop (trip_id);
CREATE INDEX ix_trip_stop_destination ON tms.trip_stop (destination_id);

COMMENT ON TABLE tms.trip_stop IS
    'One ordered destination stop of a tms.trip (migration V11) - a planning instance stop, not '
    'a master tms.route_stop. Kept in step with the trip''s active assignments by '
    'TripStopPlanner: a new destination is appended, a destination whose last order left is '
    'removed, and the planner may reorder the list explicitly.';
COMMENT ON CONSTRAINT uq_trip_stop_trip_sequence ON tms.trip_stop IS
    'Deferred to COMMIT so an explicit reorder can pass through a transient duplicate position '
    'mid-transaction - the same idiom as uq_route_stop_route_sequence (V8).';

CREATE TRIGGER tr_trip_stop_set_updated_at
    BEFORE UPDATE ON tms.trip_stop
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.trip_stop ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- tms.trip_order_assignment - the explicit order-to-trip assignment aggregate
-- ---------------------------------------------------------------------------
-- Deliberately NOT a trip_id column on tms.transport_order. Three things that column could not
-- do, and this table does (docs/domain/PLANNING_MANUAL_V1.md, "Why an assignment aggregate"):
--   1. carry the *allocated* quantities, so a future partial/split assignment is a second row
--      with smaller numbers rather than a schema change - the capacity service already sums this
--      table and never the order header, so splitting changes no capacity code;
--   2. keep history: a reassignment closes the prior row (status REMOVED, removed_at/by/reason)
--      and opens a new one, instead of overwriting the only record that a plan ever existed;
--   3. express the "one open whole-order assignment" invariant as a database constraint
--      (uq_trip_order_assignment_open_whole_order below) that two concurrent transactions cannot
--      both satisfy - without blocking the future split case, which the partial index excludes.
CREATE TABLE tms.trip_order_assignment (
    id                    uuid          NOT NULL DEFAULT gen_random_uuid(),
    -- Denormalized from the parent trip (rule 7): this table references tms.transport_order, a
    -- company-scoped table, so it needs its own company_id to carry rule 6's composite FK.
    company_id            uuid          NOT NULL,
    trip_id               uuid          NOT NULL,
    order_id              uuid          NOT NULL,
    -- true for every V1 assignment (the UI assigns whole orders only). The flag is what lets the
    -- open-assignment invariant below be a *partial* index: a future split assignment writes
    -- false and is simply not covered by it.
    whole_order           boolean       NOT NULL DEFAULT true,
    status                text          NOT NULL DEFAULT 'ACTIVE',
    -- The allocated load, snapshotted from the order's totals at assignment time. Capacity always
    -- sums these columns, never tms.transport_order's - see point 1 above and CAPACITY_MODEL.md.
    assigned_weight_kg    numeric(14,3) NOT NULL,
    assigned_volume_m3    numeric(14,4) NOT NULL,
    assigned_pallets      numeric(12,2) NOT NULL,
    assigned_at           timestamptz   NOT NULL DEFAULT now(),
    assigned_by           uuid,
    removed_at            timestamptz,
    removed_by            uuid,
    removal_reason        text,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now(),
    created_by            uuid,
    updated_by            uuid,
    CONSTRAINT pk_trip_order_assignment PRIMARY KEY (id),
    CONSTRAINT fk_trip_order_assignment_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_order_assignment_trip FOREIGN KEY (trip_id)
        REFERENCES tms.trip (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_order_assignment_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),
    CONSTRAINT fk_trip_order_assignment_order FOREIGN KEY (order_id)
        REFERENCES tms.transport_order (id) ON DELETE RESTRICT,
    -- The tenant guarantee that makes "an order of company A cannot be planned into a trip of
    -- company B" a database fact and not only a service check (rule 6): both references are
    -- pinned to this row's single company_id.
    CONSTRAINT fk_trip_order_assignment_order_company FOREIGN KEY (order_id, company_id)
        REFERENCES tms.transport_order (id, company_id),
    CONSTRAINT ck_trip_order_assignment_status CHECK (status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT ck_trip_order_assignment_removed_pair
        CHECK ((status = 'REMOVED') = (removed_at IS NOT NULL)),
    CONSTRAINT ck_trip_order_assignment_removal_reason_requires_removed
        CHECK (removal_reason IS NULL OR status = 'REMOVED'),
    CONSTRAINT ck_trip_order_assignment_removal_reason_not_blank
        CHECK (removal_reason IS NULL OR btrim(removal_reason) <> ''),
    -- Zero is legal (an order whose weight/volume/pallets are simply not known), negative is not.
    CONSTRAINT ck_trip_order_assignment_amounts_nonnegative CHECK (
        assigned_weight_kg >= 0 AND assigned_volume_m3 >= 0 AND assigned_pallets >= 0),
    CONSTRAINT fk_trip_order_assignment_assigned_by FOREIGN KEY (assigned_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_order_assignment_removed_by FOREIGN KEY (removed_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_order_assignment_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_order_assignment_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

-- The concurrency invariant, in the one place two racing transactions cannot both talk their way
-- past it: an order has at most one open whole-order assignment installation-wide. Two planners
-- assigning the same order to two different trips at the same instant both pass their service
-- level pre-check (each sees no active assignment in its own snapshot) and then one of the two
-- INSERTs fails here with 23505, which TripService translates into 409. See
-- docs/domain/PLANNING_MANUAL_V1.md, "Concurrency", and PlanningApiIntegrationTest.
-- Partial on (status, whole_order) so it constrains only the V1 shape: a future split allocation
-- (whole_order = false) and every closed historical row stay outside it.
CREATE UNIQUE INDEX uq_trip_order_assignment_open_whole_order
    ON tms.trip_order_assignment (order_id)
    WHERE status = 'ACTIVE' AND whole_order;

-- The hot path: "what is currently on this trip" - a partial index because capacity, stop
-- synchronisation and the trip board all read active rows only, and history never grows the
-- part of the index they scan.
CREATE INDEX ix_trip_order_assignment_trip_active
    ON tms.trip_order_assignment (trip_id)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_trip_order_assignment_company ON tms.trip_order_assignment (company_id);
-- Full (not partial) on purpose: the history of one order - "where has this been planned?" -
-- reads closed rows too.
CREATE INDEX ix_trip_order_assignment_order ON tms.trip_order_assignment (order_id);

COMMENT ON TABLE tms.trip_order_assignment IS
    'The explicit order-to-trip assignment aggregate (migration V11): allocated load, an '
    'ACTIVE/REMOVED lifecycle that preserves reassignment history, and the partial unique index '
    'that makes double-assignment of a whole order impossible under concurrency. Never replaced '
    'by a trip_id column on tms.transport_order - see docs/domain/PLANNING_MANUAL_V1.md.';
COMMENT ON COLUMN tms.trip_order_assignment.assigned_weight_kg IS
    'Allocated kilograms, snapshotted from the order header at assignment time. The capacity '
    'service sums this column, never tms.transport_order.total_weight_kg, so a future split '
    'assignment needs no change to capacity code.';
COMMENT ON INDEX tms.uq_trip_order_assignment_open_whole_order IS
    'One open whole-order assignment per order. Partial so future split allocations '
    '(whole_order = false) and closed history rows are unconstrained.';

CREATE TRIGGER tr_trip_order_assignment_set_updated_at
    BEFORE UPDATE ON tms.trip_order_assignment
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.trip_order_assignment ENABLE ROW LEVEL SECURITY;
