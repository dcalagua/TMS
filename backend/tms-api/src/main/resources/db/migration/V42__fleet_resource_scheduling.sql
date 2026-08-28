-- ===========================================================================
-- V42 - Fleet resource scheduling, and the accepted-tender invariant
-- ===========================================================================
--
-- Two things, and the first is a correctness debt rather than a feature.
--
-- ---------------------------------------------------------------------------
-- 1. THE DEBT: an accepted tender could contradict the vehicle on the trip
-- ---------------------------------------------------------------------------
--
-- tms.trip.carrier_id is set by Trip.assignVehicle and is, by construction, the owner of the
-- assigned vehicle. V40's waterfall offers a shipment to carriers that do *not* own its vehicle -
-- which is what subcontracting is - and JOB 07 deliberately refused to change carrier_id on
-- acceptance, because doing so would have produced a shipment whose carrier and whose vehicle's
-- owner disagreed. That left the acceptance recorded on the tender and nowhere on the trip.
--
-- Three resolutions were possible:
--
--   A. clear the vehicle and let the shipment wait for one of the accepting carrier's.
--      Impossible: ck_trip_confirmed_is_complete (V25) requires a vehicle on every confirmed trip,
--      and only confirmed trips are tenderable.
--   B. pick a compatible vehicle of the accepting carrier automatically. Refused: choosing among
--      another company's fleet needs rules nobody has stated, and inventing a vehicle assignment is
--      exactly the fabrication this project keeps refusing.
--   C. record who accepted, keep carrier_id meaning what it has always meant, and make the
--      mismatch REPRESENTABLE and BLOCKING.
--
-- C is what this migration does. accepted_carrier_id says who agreed to run the shipment;
-- carrier_id goes on being the owner of the vehicle attached to it. The two may disagree - that is
-- a real operational state, "the carrier is agreed and the truck is not sorted out yet" - and a
-- shipment in it CANNOT DEPART. A planner assigns one of the accepting carrier's vehicles, which
-- sets carrier_id through the ordinary path, and the two agree again.
ALTER TABLE tms.trip ADD COLUMN accepted_carrier_id uuid;

ALTER TABLE tms.trip
    ADD CONSTRAINT fk_trip_accepted_carrier FOREIGN KEY (accepted_carrier_id)
        REFERENCES tms.carrier (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_trip_accepted_carrier_company FOREIGN KEY (accepted_carrier_id, company_id)
        REFERENCES tms.carrier (id, company_id);

-- THE invariant. A shipment may not have departed while the carrier who agreed to run it is not the
-- owner of the vehicle running it. Stated over the two terminal-ish states rather than over
-- dispatch itself, because a CHECK sees a row and not a transition - and a row that has departed is
-- exactly the row this must never allow.
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_departed_carrier_matches_vehicle CHECK (
    status NOT IN ('IN_TRANSIT', 'COMPLETED')
    OR accepted_carrier_id IS NULL
    OR accepted_carrier_id = carrier_id);

COMMENT ON COLUMN tms.trip.accepted_carrier_id IS
    'The carrier that ACCEPTED a tender for this shipment (V42), which is not necessarily the owner '
    'of the vehicle on it - carrier_id is that, and goes on being that. When the two differ the '
    'shipment is agreed but not resourced: it may be planned and edited, and it may NOT depart '
    '(ck_trip_departed_carrier_matches_vehicle). Assigning one of the accepting carrier''s vehicles '
    'is what makes them agree.';

CREATE INDEX ix_trip_accepted_carrier ON tms.trip (accepted_carrier_id)
    WHERE accepted_carrier_id IS NOT NULL;

-- Existing rows: none has ever been tendered to a different carrier, because until now that was not
-- expressible. NULL is the correct value for every one of them and means "nobody has accepted a
-- tender that says anything different from the vehicle" - not "unknown".

-- ---------------------------------------------------------------------------
-- 2. When a driver or a vehicle is not available
-- ---------------------------------------------------------------------------
--
-- One table for both, with two typed columns and a CHECK that exactly one is set - the same shape
-- V30 chose for rate-card scope targets ("split into two typed columns rather than one polymorphic
-- scope_id"), and for the same reason: a polymorphic resource_id cannot carry a foreign key, and a
-- block pointing at a deleted driver is a gap in the one record that says why a truck did not run.
CREATE TABLE tms.resource_unavailability (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id      uuid        NOT NULL,
    driver_id       uuid,
    vehicle_id      uuid,
    reason          text        NOT NULL,
    -- Absolute instants. A holiday is a specific interval somebody decided on; a shift is a weekly
    -- rule and lives in tms.driver_shift below. Confusing the two is how "off next Tuesday" becomes
    -- "off on Tuesdays".
    starts_at       timestamptz NOT NULL,
    ends_at         timestamptz NOT NULL,
    notes           text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,

    CONSTRAINT pk_resource_unavailability PRIMARY KEY (id),
    CONSTRAINT fk_resource_unavailability_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_resource_unavailability_driver FOREIGN KEY (driver_id)
        REFERENCES tms.driver (id) ON DELETE CASCADE,
    CONSTRAINT fk_resource_unavailability_driver_company FOREIGN KEY (driver_id, company_id)
        REFERENCES tms.driver (id, company_id),
    CONSTRAINT fk_resource_unavailability_vehicle FOREIGN KEY (vehicle_id)
        REFERENCES tms.vehicle (id) ON DELETE CASCADE,
    CONSTRAINT fk_resource_unavailability_vehicle_company FOREIGN KEY (vehicle_id, company_id)
        REFERENCES tms.vehicle (id, company_id),
    CONSTRAINT fk_resource_unavailability_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    -- Exactly one resource. Neither would be a block on nothing; both would be a block whose
    -- meaning depends on which column a reader looked at first.
    CONSTRAINT ck_resource_unavailability_one_resource CHECK (
        (driver_id IS NOT NULL) <> (vehicle_id IS NOT NULL)),
    CONSTRAINT ck_resource_unavailability_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_resource_unavailability_reason CHECK (reason IN (
        -- Vehicles
        'MAINTENANCE', 'REPAIR', 'INSPECTION',
        -- Drivers
        'ABSENCE', 'HOLIDAY', 'TRAINING', 'MEDICAL',
        -- Either
        'OTHER')),
    CONSTRAINT ck_resource_unavailability_notes_not_blank CHECK (notes IS NULL OR btrim(notes) <> '')
);

-- One block per resource per instant. Two overlapping "in maintenance" rows on one truck are two
-- statements of one fact, and the second is what makes a report double-count downtime. The same
-- EXCLUDE technique V41 used for docks, applied per resource column so a driver's blocks and a
-- vehicle's never collide with each other.
ALTER TABLE tms.resource_unavailability
    ADD CONSTRAINT ex_driver_unavailability_no_overlap
        EXCLUDE USING gist (driver_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
        WHERE (driver_id IS NOT NULL),
    ADD CONSTRAINT ex_vehicle_unavailability_no_overlap
        EXCLUDE USING gist (vehicle_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
        WHERE (vehicle_id IS NOT NULL);

CREATE INDEX ix_resource_unavailability_company ON tms.resource_unavailability (company_id);
CREATE INDEX ix_resource_unavailability_driver
    ON tms.resource_unavailability USING gist (driver_id, tstzrange(starts_at, ends_at))
    WHERE driver_id IS NOT NULL;
CREATE INDEX ix_resource_unavailability_vehicle
    ON tms.resource_unavailability USING gist (vehicle_id, tstzrange(starts_at, ends_at))
    WHERE vehicle_id IS NOT NULL;

COMMENT ON TABLE tms.resource_unavailability IS
    'When a driver or a vehicle cannot work (V42): maintenance, absence, a holiday. Absolute '
    'intervals, unlike tms.driver_shift''s weekly rule - "off next Tuesday" and "off on Tuesdays" '
    'are different sentences with the same words.';

-- ---------------------------------------------------------------------------
-- 3. When a driver normally works
-- ---------------------------------------------------------------------------
--
-- MINUTES SINCE LOCAL MIDNIGHT, not a `time` column - the lesson V41 paid for. This application
-- sets hibernate.jdbc.time_zone to UTC, which normalises temporal values on write and silently
-- moved every dock's opening hours by its own offset until a CHECK caught it. A shift is a quantity
-- of minutes into the day at the depot, and an integer cannot be zone-shifted by a configuration.
CREATE TABLE tms.driver_shift (
    id                 uuid    NOT NULL DEFAULT gen_random_uuid(),
    company_id         uuid    NOT NULL,
    driver_id          uuid    NOT NULL,
    -- ISO-8601: 1 = Monday .. 7 = Sunday, matching java.time.DayOfWeek.getValue().
    day_of_week        integer NOT NULL,
    starts_at_minutes  integer NOT NULL,
    ends_at_minutes    integer NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_driver_shift PRIMARY KEY (id),
    CONSTRAINT fk_driver_shift_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_driver_shift_driver FOREIGN KEY (driver_id)
        REFERENCES tms.driver (id) ON DELETE CASCADE,
    CONSTRAINT fk_driver_shift_driver_company FOREIGN KEY (driver_id, company_id)
        REFERENCES tms.driver (id, company_id),
    CONSTRAINT ck_driver_shift_day CHECK (day_of_week BETWEEN 1 AND 7),
    -- No overnight shifts in V1, for V41's reason: a shift running 22:00-06:00 is two rows on two
    -- days, and allowing ends < starts puts a wrap-around branch in every containment check.
    CONSTRAINT ck_driver_shift_window CHECK (ends_at_minutes > starts_at_minutes),
    CONSTRAINT ck_driver_shift_bounds CHECK (
        starts_at_minutes BETWEEN 0 AND 1439 AND ends_at_minutes BETWEEN 1 AND 1440),
    CONSTRAINT uq_driver_shift_day UNIQUE (driver_id, day_of_week)
);

CREATE INDEX ix_driver_shift_company ON tms.driver_shift (company_id);

COMMENT ON COLUMN tms.driver_shift.starts_at_minutes IS
    'Minutes since LOCAL midnight (V42): 360 is 06:00. Deliberately an integer and not a `time`, '
    'for the reason V41 records - hibernate.jdbc.time_zone normalises temporal values on write and '
    'moved every dock''s hours by its own offset before a CHECK caught it.';

-- ---------------------------------------------------------------------------
-- 4. Tenant isolation (ADR-005)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.resource_unavailability ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.driver_shift ENABLE ROW LEVEL SECURITY;

-- Both are editable master data: a maintenance window is rescheduled and a shift is rewritten.
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.resource_unavailability TO tms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.driver_shift TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.resource_unavailability
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

CREATE POLICY p_tenant_company_scope ON tms.driver_shift
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 5. Audit
-- ---------------------------------------------------------------------------
--
-- One action. Blocking a resource is a decision somebody makes and a shipment may later be
-- explained by; putting a truck back is the same decision reversed and reads on the same row.
-- Recorded against VEHICLE or DRIVER, whichever the block names - not a new aggregate type,
-- because what changed is the availability of an existing master.
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
    'CREATE', 'UPDATE', 'ACTIVATE', 'DEACTIVATE', 'ASSIGN_ORDER', 'REMOVE_ORDER', 'MOVE_ORDER',
    'VEHICLE_CHANGE', 'DRIVER_CHANGE', 'CONFIRM', 'CANCEL', 'CREDENTIAL_CREATE',
    'CREDENTIAL_ROTATE', 'CREDENTIAL_REVOKE', 'AUTO_PLAN', 'IMPORT_EXECUTED', 'SHIPMENT_CONFIRMED',
    'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED', 'SHIPMENT_CANCELLED',
    'DELIVERY_RESULT_RECORDED', 'COST_ESTIMATED', 'COST_ACTUAL_RECORDED', 'COST_CLOSED',
    'COST_REOPENED',
    'TENDER_SENT', 'TENDER_ACCEPTED', 'TENDER_REJECTED', 'TENDER_EXPIRED', 'TENDER_CANCELLED',
    'ROLES_CHANGED',
    'ORDER_REOPENED',
    'WATERFALL_STARTED', 'WATERFALL_ENDED',
    'APPOINTMENT_BOOKED', 'APPOINTMENT_RESCHEDULED', 'APPOINTMENT_CANCELLED', 'APPOINTMENT_NO_SHOW',
    'RESOURCE_BLOCKED', 'RESOURCE_RELEASED'));

-- ---------------------------------------------------------------------------
-- Deliberately NOT here
-- ---------------------------------------------------------------------------
--
--   * No work_assignment table. Sequencing several trips onto one driver-and-vehicle pair, with
--     travel time between them, is a real feature and a large one - it needs a scheduling model,
--     a rebalancing story and its own screen. What V42 delivers is the layer underneath it:
--     availability every planner and every engine can already read. The JOB 09 result names it as
--     the next step rather than shipping a table nothing writes to.
--   * No hours-of-service model. PlanningShift is a configurable ceiling and this product holds no
--     jurisdiction's driving rules; pretending otherwise would be worse than a number an operation
--     sets to what it actually does.
--   * No automatic vehicle selection on tender acceptance. See section 1, resolution B.
