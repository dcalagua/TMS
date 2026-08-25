-- TMS by EBIM - V24: two per-instance overrides the masters could not express.
--
-- Domain contracts updated by this step: docs/domain/FREQUENCIES.md section 3 and
-- docs/domain/ROUTES.md section 2. Both had a written "this is not representable yet" note; this
-- migration is what removes them.
--
-- 1. tms.frequency_exception.cutoff_time_override
--
--    A frequency's cutoff lives on the weekly rule, so it is a property of "every Wednesday",
--    not of one date. The real calendar an operator has to enter is:
--
--        normal cadence   cutoff 15:00
--        24/12 OPEN       cutoff 11:00
--        25/12 CLOSED
--
--    The 24/12 row was only expressible by editing the Wednesday rule, which would move the
--    cutoff of every Wednesday of the year. Nullable, so an exception that does not mention a
--    cutoff keeps falling back to the weekly rule - the precedence lives in one place,
--    FrequencyCalendar.effectiveCutoff, and nowhere else.
--
-- 2. tms.route_stop.service_time_override_minutes
--
--    Service time lives on tms.location, which stays the source of truth: it is where an
--    operator expects to find "how long a stop at this store takes", and it is right for the
--    ordinary case. What it cannot say is that one corridor is different - the same store served
--    off-hours on the night route needs 40 minutes rather than its usual 15. Nullable, so a stop
--    that does not override anything reads the location's value, exactly as before.
--
-- Neither column is a new engine. Both are one nullable value plus one stated resolution rule,
-- and both resolution rules are pure functions proved without a database (FrequencyCalendarTest,
-- RouteStopServiceTimeTest) - see docs/database/MIGRATION_STRATEGY.md on what a schema step is
-- allowed to assume on a host where Testcontainers cannot run.
--
-- No GRANTs here: V13 grants SELECT/INSERT/UPDATE/DELETE at table level to tms_app, and a table
-- grant covers columns added later. No RLS change either - both tables already have their
-- policy, and a new column is not a new row.

-- ---------------------------------------------------------------------------
-- tms.frequency_exception - a per-date cutoff override
-- ---------------------------------------------------------------------------
ALTER TABLE tms.frequency_exception
    ADD COLUMN cutoff_time_override time;

-- A CLOSED date has no cutoff to state: nothing is dispatched, so "the last moment to order"
-- is not a question that has an answer. Storing one would be a value no reader could act on,
-- and it would survive a later toggle back to OPEN as a stale time nobody chose.
ALTER TABLE tms.frequency_exception
    ADD CONSTRAINT ck_frequency_exception_cutoff_requires_service
        CHECK (cutoff_time_override IS NULL OR service_override);

COMMENT ON COLUMN tms.frequency_exception.cutoff_time_override IS
    'Optional per-date replacement for the weekly rule''s cutoff_time (for example 24/12 open '
    'but closing at 11:00). NULL means "no opinion": the weekly rule for that day of week '
    'decides, which is the ordinary case. Only meaningful when service_override is true - see '
    'ck_frequency_exception_cutoff_requires_service.';

COMMENT ON CONSTRAINT ck_frequency_exception_cutoff_requires_service ON tms.frequency_exception IS
    'A blackout date has no cutoff: no service happens, so there is no last moment to order.';

-- ---------------------------------------------------------------------------
-- tms.route_stop - a per-stop service time override
-- ---------------------------------------------------------------------------
ALTER TABLE tms.route_stop
    ADD COLUMN service_time_override_minutes integer;

-- Zero is a legitimate value, not a mistake: a drop-and-go stop where the driver never leaves
-- the cab really does cost no service time, and NULL already means "use the location's".
ALTER TABLE tms.route_stop
    ADD CONSTRAINT ck_route_stop_service_time_override_nonnegative
        CHECK (service_time_override_minutes IS NULL OR service_time_override_minutes >= 0);

COMMENT ON COLUMN tms.route_stop.service_time_override_minutes IS
    'Optional per-stop replacement for location.service_time_minutes on this route only. NULL '
    'means "use the location''s", which is the ordinary case; tms.location remains the source '
    'of truth for service time and this column never writes back to it. Resolution rule: '
    'effective = route_stop.service_time_override_minutes ?? location.service_time_minutes.';
