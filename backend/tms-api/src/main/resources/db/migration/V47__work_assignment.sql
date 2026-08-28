-- ===========================================================================
-- V47 - A driver and a vehicle, running several shipments in a day
-- ===========================================================================
--
-- Closes open debt D5, opened by JOB 09 when V42 delivered the availability layer and deliberately
-- stopped short of a scheduler: "a table nothing writes to would be scaffolding". This is what that
-- layer was for.
--
-- ---------------------------------------------------------------------------
-- 1. What this is, and what it is NOT
-- ---------------------------------------------------------------------------
--
-- A work assignment ORGANISES SHIPMENTS THAT ALREADY EXIST. It is not a second trip, it carries no
-- load, it visits no stops and it has no lifecycle of its own beyond being open or closed. A trip
-- keeps its own status, its own dispatch guards and its own execution - and this is the one thing
-- that must not slip, because:
--
--   **A WORK ASSIGNMENT MUST NEVER BECOME AN ALTERNATIVE ROUTE PAST A DISPATCH GUARD.**
--
-- Putting a shipment into somebody's day does not make it dispatchable. TripExecutionService is
-- still the only authority on whether a vehicle may leave, and everything it refuses today it goes
-- on refusing - including the accepted-carrier invariant of V42 (debt D2), which this migration
-- deliberately does not touch and cannot satisfy.
--
-- ---------------------------------------------------------------------------
-- 2. The assignment
-- ---------------------------------------------------------------------------
CREATE TABLE tms.work_assignment (
    id                uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id        uuid        NOT NULL,
    -- The day this pairing works. Deliberately a date and not a range: the driver shift model (V42)
    -- is a weekly rule keyed on a day of the week, and an assignment spanning two dates could not
    -- be validated against it without inventing overnight semantics V42 refused on purpose.
    operational_date  date        NOT NULL,
    vehicle_id        uuid        NOT NULL,
    -- Nullable: a vehicle can be scheduled before the driver is named, which is how a real yard
    -- plans - the truck is committed, the person is confirmed the night before.
    driver_id         uuid,
    status            text        NOT NULL DEFAULT 'PLANNED',
    notes             text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_by        uuid,
    version           bigint      NOT NULL DEFAULT 0,

    CONSTRAINT pk_work_assignment PRIMARY KEY (id),
    CONSTRAINT uq_work_assignment_id_company UNIQUE (id, company_id),
    CONSTRAINT fk_work_assignment_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_assignment_vehicle FOREIGN KEY (vehicle_id)
        REFERENCES tms.vehicle (id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_assignment_vehicle_company FOREIGN KEY (vehicle_id, company_id)
        REFERENCES tms.vehicle (id, company_id),
    CONSTRAINT fk_work_assignment_driver FOREIGN KEY (driver_id)
        REFERENCES tms.driver (id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_assignment_driver_company FOREIGN KEY (driver_id, company_id)
        REFERENCES tms.driver (id, company_id),
    CONSTRAINT ck_work_assignment_status CHECK (status IN ('PLANNED', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_work_assignment_notes_not_blank CHECK (notes IS NULL OR btrim(notes) <> '')
);

-- THE concurrency guarantee, and the reason it is an index rather than a service check.
--
-- One live assignment per vehicle per day, and one per driver per day. Two dispatchers building
-- tomorrow's plan at the same second both pass any check a service can make - a check and a write
-- are not one operation - and the second one loses here instead of producing a truck that is in two
-- people's plans.
--
-- Per DAY rather than per interval: a vehicle's day is one sequence of work, and two assignments on
-- one date would make "what is this truck doing tomorrow" a question with two answers and an
-- ordering rule to choose between them. That is the same argument uq_trip_vehicle_active_planning_
-- date (V16) makes one level down.
CREATE UNIQUE INDEX uq_work_assignment_vehicle_day
    ON tms.work_assignment (company_id, vehicle_id, operational_date)
    WHERE status <> 'CANCELLED';

CREATE UNIQUE INDEX uq_work_assignment_driver_day
    ON tms.work_assignment (company_id, driver_id, operational_date)
    WHERE status <> 'CANCELLED' AND driver_id IS NOT NULL;

CREATE INDEX ix_work_assignment_company_date ON tms.work_assignment (company_id, operational_date);

COMMENT ON TABLE tms.work_assignment IS
    'One driver-and-vehicle pairing''s work for one day (V47). ORGANISES shipments that already '
    'exist - it is not a second trip and it grants no authority: a shipment in somebody''s day is '
    'still refused at the gate by every guard that refuses it now.';

-- ---------------------------------------------------------------------------
-- 3. The shipments in it, in order
-- ---------------------------------------------------------------------------
CREATE TABLE tms.work_assignment_trip (
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid        NOT NULL,
    work_assignment_id  uuid        NOT NULL,
    trip_id             uuid        NOT NULL,
    -- 1-based and contiguous, the convention tms.trip_stop and tms.route_stop already use.
    sequence            integer     NOT NULL,
    -- What the sequence implies, frozen when it was validated. Derived from the trip's planned
    -- departure and its last stop's ETA (V43), so a shipment whose route changed later shows a
    -- stale window rather than silently re-deciding whether the day was feasible.
    planned_start       timestamptz,
    planned_end         timestamptz,
    -- Driving time from the previous shipment's last stop to this one's origin, in minutes. NULL
    -- for the first shipment (nothing to reposition from) and NULL when routing could not measure
    -- it - which is NOT zero, and is what makes ROUTING_UNKNOWN a real refusal.
    reposition_minutes  integer,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_work_assignment_trip PRIMARY KEY (id),
    CONSTRAINT uq_work_assignment_trip_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_work_assignment_trip_sequence UNIQUE (work_assignment_id, sequence)
        DEFERRABLE INITIALLY DEFERRED,
    -- A shipment belongs to at most one day's work. Without this, reordering could leave a trip in
    -- two assignments and two drivers would both believe they were running it.
    CONSTRAINT uq_work_assignment_trip_once UNIQUE (trip_id),

    CONSTRAINT fk_work_assignment_trip_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_assignment_trip_assignment FOREIGN KEY (work_assignment_id)
        REFERENCES tms.work_assignment (id) ON DELETE CASCADE,
    CONSTRAINT fk_work_assignment_trip_assignment_company FOREIGN KEY (work_assignment_id, company_id)
        REFERENCES tms.work_assignment (id, company_id),
    CONSTRAINT fk_work_assignment_trip_trip FOREIGN KEY (trip_id)
        REFERENCES tms.trip (id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_assignment_trip_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),

    CONSTRAINT ck_work_assignment_trip_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_work_assignment_trip_window CHECK (
        planned_start IS NULL OR planned_end IS NULL OR planned_end >= planned_start),
    CONSTRAINT ck_work_assignment_trip_reposition CHECK (
        reposition_minutes IS NULL OR reposition_minutes >= 0)
);

CREATE INDEX ix_work_assignment_trip_assignment ON tms.work_assignment_trip (work_assignment_id);

COMMENT ON COLUMN tms.work_assignment_trip.reposition_minutes IS
    'Driving time from the previous shipment''s last stop to this one''s origin (V47). NULL for the '
    'first shipment, and NULL when routing could not measure the leg - which is NOT zero. A day '
    'built on an unmeasured reposition is a day nobody has checked, so the validator refuses it '
    'with ROUTING_UNKNOWN rather than assuming the truck teleports.';

-- ---------------------------------------------------------------------------
-- 4. Tenant isolation (ADR-005)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.work_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.work_assignment_trip ENABLE ROW LEVEL SECURITY;

-- Both are working plans, rewritten all day: a shipment is added, dropped, reordered.
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.work_assignment TO tms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.work_assignment_trip TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.work_assignment
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.work_assignment_trip
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 4b. Permissions
-- ---------------------------------------------------------------------------
--
-- Under fleet rather than planning: the thing being scheduled is a resource, and the people who
-- build a driver's day are the people who maintain the drivers.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('fleet.work_assignment', 'read',   'View how a driver and vehicle''s day is sequenced'),
    ('fleet.work_assignment', 'manage', 'Build and rearrange a driver and vehicle''s day');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN', 'PLANNER')
  AND p.code IN ('fleet.work_assignment:read', 'fleet.work_assignment:manage');

-- A viewer reads the day. The yard and the gate need to know which truck is doing what, and
-- neither of them plans it.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code = 'VIEWER'
  AND p.code = 'fleet.work_assignment:read';

-- ---------------------------------------------------------------------------
-- 5. Deliberately NOT here
-- ---------------------------------------------------------------------------
--
--   * No overnight assignment. V42 stored driver shifts as minutes since local midnight and
--     refused overnight shifts on purpose ("a shift running 22:00-06:00 is two rows on two days").
--     operational_date being a DATE keeps that promise: a day's work is validated against a day's
--     shift. Adding a range here would quietly grant overnight support the shift model cannot
--     express, and the validator would then be checking against a rule that does not exist.
--   * No change to tms.trip. An assignment references shipments; it does not own them, does not
--     move them and does not write accepted_carrier_id. A trip whose accepted carrier does not own
--     its vehicle (V42, debt D2) can sit in an assignment and STILL cannot depart - being scheduled
--     is not being permitted.
--   * No hours-of-service model. PlanningShift is a configurable ceiling and this product holds no
--     jurisdiction's driving rules, exactly as V42 said.
--   * No automatic assignment. A planner builds the day; nothing here schedules itself, which would
--     need the system actor debt D4 has been holding open since JOB 07.
