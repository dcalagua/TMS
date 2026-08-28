-- ===========================================================================
-- V41 - Dock and appointment scheduling
-- ===========================================================================
--
-- A shipment knows where it is going and, since V24, roughly how long it will take to serve. What
-- it cannot say is *when the dock is free*. Every operation that runs more than a few trucks a day
-- solves that with a booking sheet somewhere outside the system, and the cost of that sheet being
-- outside is the whole reason this table exists: two trucks arrive at the same door at 09:00 and
-- one of them waits two hours, and the TMS that planned both of them had no way to know.
--
-- ---------------------------------------------------------------------------
-- 1. Why a resource is a door and not a location
-- ---------------------------------------------------------------------------
--
-- A location is a place. A dock is a queue. Modelling the appointment against the location would
-- make a warehouse with six doors look like a warehouse with one, and no amount of capacity
-- arithmetic on top recovers which truck goes to which door.
--
-- **Each resource takes one vehicle at a time.** A site with six doors has six resources. That is
-- not a simplification to be relaxed later - it is what makes the no-double-booking rule
-- expressible as a database constraint at all: PostgreSQL can refuse two overlapping ranges on one
-- key (EXCLUDE ... USING gist), and it cannot refuse "more than N overlapping". A capacity column
-- would move the invariant back into application code, which is exactly where a booking sheet
-- already fails.
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

CREATE TABLE tms.location_resource (
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid        NOT NULL,
    location_id         uuid        NOT NULL,
    code                text        NOT NULL,
    name                text        NOT NULL,
    resource_type       text        NOT NULL DEFAULT 'DOCK',
    -- How long a booking at this door lasts when nobody says otherwise. The location's own
    -- service_time_minutes (V14) is about serving an order; this is about occupying a door, and
    -- the two differ whenever a truck queues, couples or waits for paperwork.
    default_slot_minutes integer    NOT NULL DEFAULT 60,
    active              boolean     NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_by          uuid,

    CONSTRAINT pk_location_resource PRIMARY KEY (id),
    CONSTRAINT fk_location_resource_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_location_resource_location FOREIGN KEY (location_id)
        REFERENCES tms.location (id) ON DELETE RESTRICT,
    CONSTRAINT fk_location_resource_location_company FOREIGN KEY (location_id, company_id)
        REFERENCES tms.location (id, company_id),
    CONSTRAINT fk_location_resource_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_location_resource_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_location_resource_type CHECK (resource_type IN ('DOCK', 'DOOR', 'BAY', 'YARD')),
    CONSTRAINT ck_location_resource_code_not_blank CHECK (btrim(code) <> ''),
    CONSTRAINT ck_location_resource_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_location_resource_slot_minutes CHECK (default_slot_minutes BETWEEN 5 AND 1440),
    -- Unique per location, not per company: "DOCK-1" at two warehouses is two doors with the same
    -- local name, which is how sites actually label them.
    CONSTRAINT uq_location_resource_code UNIQUE (location_id, code),
    -- Needed by tms.appointment's composite FK: the resource and the appointment must belong to
    -- one company, as a database fact rather than a service check.
    CONSTRAINT uq_location_resource_id_company UNIQUE (id, company_id)
);

CREATE INDEX ix_location_resource_company ON tms.location_resource (company_id);
CREATE INDEX ix_location_resource_location ON tms.location_resource (location_id) WHERE active;

COMMENT ON TABLE tms.location_resource IS
    'A dock, door, bay or yard slot that takes ONE vehicle at a time (V41). Six doors are six rows. '
    'That is what makes no-double-booking expressible as an EXCLUDE constraint - PostgreSQL can '
    'refuse two overlapping ranges on one key and cannot refuse "more than N overlapping", so a '
    'capacity column would move the invariant back into application code.';

-- ---------------------------------------------------------------------------
-- 2. When the door is open
-- ---------------------------------------------------------------------------
--
-- Local times, per weekday, against the LOCATION's own time zone (V14) - never against the
-- server's. A dock in Arequipa opens at 07:00 in Arequipa, and storing that as an instant would
-- make it move twice a year in any country that shifts its clocks and would make "07:00" unreadable
-- to the person who typed it.
CREATE TABLE tms.resource_calendar (
    id            uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id    uuid        NOT NULL,
    resource_id   uuid        NOT NULL,
    -- ISO-8601: 1 = Monday .. 7 = Sunday, matching java.time.DayOfWeek.getValue().
    day_of_week   integer     NOT NULL,
    -- MINUTES SINCE LOCAL MIDNIGHT, not a `time`. The application sets hibernate.jdbc.time_zone to
    -- UTC, which normalises every temporal value on write - and a `time` column went through that
    -- normalisation, turning "the door opens at 07:00 here" into 12:00 and silently moving every
    -- site's hours by its own offset. An integer cannot be zone-shifted by any configuration,
    -- which is the whole point: this is a quantity of minutes into the site's day, not an instant.
    opens_at_minutes   integer NOT NULL,
    closes_at_minutes  integer NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_resource_calendar PRIMARY KEY (id),
    CONSTRAINT fk_resource_calendar_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_resource_calendar_resource FOREIGN KEY (resource_id)
        REFERENCES tms.location_resource (id) ON DELETE CASCADE,
    CONSTRAINT ck_resource_calendar_day CHECK (day_of_week BETWEEN 1 AND 7),
    -- No overnight windows in V1. A door open 22:00-06:00 is two rows on two days, which is what a
    -- reader means anyway; allowing closes_at < opens_at would make every "is this inside the
    -- window" check carry a wrap-around branch nobody would remember to test.
    CONSTRAINT ck_resource_calendar_window CHECK (closes_at_minutes > opens_at_minutes),
    CONSTRAINT ck_resource_calendar_bounds CHECK (
        opens_at_minutes BETWEEN 0 AND 1439 AND closes_at_minutes BETWEEN 1 AND 1440),
    CONSTRAINT uq_resource_calendar_day UNIQUE (resource_id, day_of_week)
);

CREATE INDEX ix_resource_calendar_company ON tms.resource_calendar (company_id);

COMMENT ON COLUMN tms.resource_calendar.opens_at_minutes IS
    'Minutes since LOCAL midnight at the site (V41): 420 is 07:00 there. Deliberately an integer '
    'and not a `time` - hibernate.jdbc.time_zone normalises temporal values to UTC on write, which '
    'moved every door''s hours by the site''s own offset. A dock in Arequipa opens at 07:00 in '
    'Arequipa; an integer cannot be shifted by a configuration change.';

-- ---------------------------------------------------------------------------
-- 3. When it is closed anyway
-- ---------------------------------------------------------------------------
--
-- A holiday, a stocktake, a broken leveller. Absolute instants, unlike the calendar: a closure is a
-- specific interval somebody decided on, not a weekly rule.
CREATE TABLE tms.resource_blocked_slot (
    id            uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id    uuid        NOT NULL,
    resource_id   uuid        NOT NULL,
    starts_at     timestamptz NOT NULL,
    ends_at       timestamptz NOT NULL,
    reason        text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,

    CONSTRAINT pk_resource_blocked_slot PRIMARY KEY (id),
    CONSTRAINT fk_resource_blocked_slot_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_resource_blocked_slot_resource FOREIGN KEY (resource_id)
        REFERENCES tms.location_resource (id) ON DELETE CASCADE,
    CONSTRAINT fk_resource_blocked_slot_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_resource_blocked_slot_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_resource_blocked_slot_reason_not_blank CHECK (btrim(reason) <> '')
);

CREATE INDEX ix_resource_blocked_slot_resource
    ON tms.resource_blocked_slot USING gist (resource_id, tstzrange(starts_at, ends_at));
CREATE INDEX ix_resource_blocked_slot_company ON tms.resource_blocked_slot (company_id);

-- ---------------------------------------------------------------------------
-- 4. The appointment, and the constraint the whole feature exists for
-- ---------------------------------------------------------------------------
CREATE TABLE tms.appointment (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id      uuid        NOT NULL,
    resource_id     uuid        NOT NULL,
    location_id     uuid        NOT NULL,
    -- Both optional, and that is the point: a customer may book a slot before a shipment exists,
    -- and a shipment's stop may never need a booked door. Neither direction is mandatory.
    trip_id         uuid,
    trip_stop_id    uuid,
    purpose         text        NOT NULL,
    status          text        NOT NULL DEFAULT 'REQUESTED',
    -- Absolute instants. The location's time zone is how they are DISPLAYED and how the calendar's
    -- local opening hours are interpreted; it is not how they are stored, because an appointment is
    -- a moment two parties agreed on and a moment does not have a time zone.
    window_start    timestamptz NOT NULL,
    window_end      timestamptz NOT NULL,
    reference       text,
    notes           text,
    arrived_at      timestamptz,
    completed_at    timestamptz,
    cancelled_at    timestamptz,
    cancel_reason   text,
    -- Where it was before somebody moved it, so "this slot was changed" is answerable from the row.
    rescheduled_from_start timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,

    CONSTRAINT pk_appointment PRIMARY KEY (id),
    CONSTRAINT fk_appointment_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_resource FOREIGN KEY (resource_id)
        REFERENCES tms.location_resource (id) ON DELETE RESTRICT,
    -- The tenant guarantee: a company's appointment cannot name another company's door.
    CONSTRAINT fk_appointment_resource_company FOREIGN KEY (resource_id, company_id)
        REFERENCES tms.location_resource (id, company_id),
    CONSTRAINT fk_appointment_location FOREIGN KEY (location_id)
        REFERENCES tms.location (id) ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_location_company FOREIGN KEY (location_id, company_id)
        REFERENCES tms.location (id, company_id),
    CONSTRAINT fk_appointment_trip FOREIGN KEY (trip_id)
        REFERENCES tms.trip (id) ON DELETE SET NULL,
    CONSTRAINT fk_appointment_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),
    CONSTRAINT fk_appointment_trip_stop FOREIGN KEY (trip_stop_id)
        REFERENCES tms.trip_stop (id) ON DELETE SET NULL,
    CONSTRAINT fk_appointment_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,

    CONSTRAINT ck_appointment_purpose CHECK (purpose IN ('PICKUP', 'DELIVERY')),
    CONSTRAINT ck_appointment_status CHECK (status IN (
        'REQUESTED', 'CONFIRMED', 'RESCHEDULED', 'ARRIVED', 'COMPLETED', 'CANCELLED', 'NO_SHOW')),
    CONSTRAINT ck_appointment_window CHECK (window_end > window_start),
    -- A stop belongs to a trip: naming one without the other would leave the appointment pointing
    -- at half a shipment.
    CONSTRAINT ck_appointment_stop_needs_trip CHECK (trip_stop_id IS NULL OR trip_id IS NOT NULL),
    CONSTRAINT ck_appointment_arrived_pair CHECK (
        arrived_at IS NULL OR status IN ('ARRIVED', 'COMPLETED')),
    CONSTRAINT ck_appointment_completed_pair CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT ck_appointment_cancelled_pair CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    CONSTRAINT ck_appointment_cancel_reason CHECK (cancel_reason IS NULL OR status = 'CANCELLED'),
    -- A vehicle that arrived cannot then be a no-show: somebody was there.
    CONSTRAINT ck_appointment_no_show_never_arrived CHECK (status <> 'NO_SHOW' OR arrived_at IS NULL),
    CONSTRAINT ck_appointment_reference_not_blank CHECK (reference IS NULL OR btrim(reference) <> '')
);

-- ---------------------------------------------------------------------------
-- THE constraint: one vehicle per door at a time
-- ---------------------------------------------------------------------------
--
-- Two dispatchers booking the same door for 09:00-10:00 both pass any service-level "is it free?"
-- check, because each sees a free door in its own snapshot. This is the one place they cannot both
-- get past. It is an EXCLUDE rather than a unique index because the thing being excluded is an
-- *overlap*, which no unique key can express - and it needs btree_gist for the `=` half, because
-- gist alone cannot compare uuids for equality.
--
-- CANCELLED and NO_SHOW are outside it, and only those two: nobody used the door. Everything else
-- did or will, including COMPLETED - two trucks recorded as having used one door at the same time
-- is a history that cannot be true.
ALTER TABLE tms.appointment ADD CONSTRAINT ex_appointment_no_double_booking
    EXCLUDE USING gist (
        resource_id WITH =,
        tstzrange(window_start, window_end) WITH &&
    ) WHERE (status NOT IN ('CANCELLED', 'NO_SHOW'));

CREATE INDEX ix_appointment_company ON tms.appointment (company_id);
CREATE INDEX ix_appointment_resource_window ON tms.appointment (resource_id, window_start);
CREATE INDEX ix_appointment_trip ON tms.appointment (trip_id) WHERE trip_id IS NOT NULL;
-- The day view: every appointment at a site, in order.
CREATE INDEX ix_appointment_location_window ON tms.appointment (location_id, window_start);

COMMENT ON CONSTRAINT ex_appointment_no_double_booking ON tms.appointment IS
    'One vehicle per door at a time (V41). An EXCLUDE and not a unique index because what is being '
    'refused is an overlap, which no unique key can express. CANCELLED and NO_SHOW are outside it '
    'and only those two - nobody used the door; everything else did or will.';

COMMENT ON COLUMN tms.appointment.window_start IS
    'An absolute instant. The location''s time zone is how it is displayed and how the calendar''s '
    'local opening hours are read - not how it is stored, because a moment two parties agreed on '
    'does not have a time zone.';

-- ---------------------------------------------------------------------------
-- 5. Tenant isolation (ADR-005)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.location_resource ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.resource_calendar ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.resource_blocked_slot ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.appointment ENABLE ROW LEVEL SECURITY;

-- Master data is edited and removed; an appointment is cancelled, never deleted - who booked which
-- door and what happened is exactly what a carrier disputing a detention charge asks for.
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.location_resource TO tms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.resource_calendar TO tms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.resource_blocked_slot TO tms_app;
GRANT SELECT, INSERT, UPDATE ON tms.appointment TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.location_resource
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

CREATE POLICY p_tenant_company_scope ON tms.resource_calendar
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

CREATE POLICY p_tenant_company_scope ON tms.resource_blocked_slot
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

CREATE POLICY p_tenant_company_scope ON tms.appointment
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 6. The audit vocabulary
-- ---------------------------------------------------------------------------
--
-- One action per thing a person decides about a booking. Booking, moving, cancelling and marking a
-- no-show are each a commercial fact somebody may later be charged for; arriving and completing are
-- recorded on the row and produce no separate action, exactly as V27 decided for stop transitions.
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
    'APPOINTMENT_BOOKED', 'APPOINTMENT_RESCHEDULED', 'APPOINTMENT_CANCELLED', 'APPOINTMENT_NO_SHOW'));

-- An appointment is its own aggregate, not a note on a trip: it exists before a trip does and
-- outlives one that is cancelled, and a carrier arguing about a missed slot asks about the booking
-- rather than about the shipment that happened to be behind it.
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_aggregate_type;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_aggregate_type CHECK (aggregate_type IN (
    'LOCATION', 'CARRIER', 'VEHICLE', 'DRIVER', 'TRANSPORT_ORDER', 'TRIP', 'PLANNING_RUN',
    'INTEGRATION_CLIENT', 'MASTER_DATA_IMPORT_BATCH', 'ORDER_IMPORT_BATCH', 'SHIPMENT',
    'RATE_CARD', 'TRIP_COST',
    'COMPANY', 'APP_USER', 'MEMBERSHIP',
    'WEBHOOK_SUBSCRIPTION',
    'LOCATION_RESOURCE', 'APPOINTMENT'));

-- ---------------------------------------------------------------------------
-- 7. Permissions
-- ---------------------------------------------------------------------------
--
-- Two, split read from manage as everywhere else. VIEWER gets the read: unlike a tender, a dock
-- booking carries no price and a site's own people need to see the day's slots to do their job -
-- the yard, the gate and the warehouse all read this board and none of them plans shipments.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('appointments.appointment', 'read',   'View dock bookings and the day''s slots'),
    ('appointments.appointment', 'manage', 'Book, move, cancel and close out dock appointments'),
    ('appointments.resource',    'manage', 'Configure docks, their opening hours and their closures');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN', 'PLANNER')
  AND p.code IN ('appointments.appointment:read', 'appointments.appointment:manage');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code = 'VIEWER'
  AND p.code = 'appointments.appointment:read';

-- Configuring docks is an administrator's job, not a planner's: adding a door changes what the
-- whole site can promise, and a planner adding one to fit today's truck is how a yard ends up with
-- eleven doors and eight of them imaginary.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code = 'appointments.resource:manage';

-- ---------------------------------------------------------------------------
-- Deliberately NOT here
-- ---------------------------------------------------------------------------
--
--   * No WMS or EWM table, column or foreign key. A warehouse's own dock schedule is a different
--     system's record and this one integrates with it through a port when somebody asks - never by
--     sharing a table. The boundary is stated in docs/domain/APPOINTMENTS_V1.md.
--   * No capacity column. See the location_resource header: "at most N overlapping" is not
--     expressible as an EXCLUDE, and an invariant that lives in application code is the booking
--     sheet this feature replaces.
--   * No recurring appointments. A standing 09:00 Tuesday slot is a template feature, and nothing
--     has asked for one.
--   * No automatic booking from planning. Which door a shipment uses is a site's decision, often
--     the customer's; a planner books it. Planning V2 proposing appointments needs a dock-capacity
--     model in the engine and its own brief.
