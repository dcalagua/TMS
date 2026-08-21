-- TMS by EBIM - V27 stop execution, transport events and operational exceptions: the layer that
-- turns a trip that "left at 08:40 and came back at 17:10" into a day somebody can actually read.
--
-- Design and the full transition tables: docs/domain/TRIP_EXECUTION_V1.md.
--
-- V25 gave the *trip* an execution lifecycle and closed with a list of what it deliberately left
-- out, first item: "No per-stop arrival/departure/proof-of-delivery. tms.trip_stop stays a
-- planning row (a sequence and a service window)." That was the right call then - the trip-level
-- times answer "has it left?" and "is it back?" - and it is the wrong call now, because the two
-- questions a dispatcher actually spends the day on are per stop:
--
--   * "customer 4 says nothing arrived - did we get there?"
--   * "we are closing the day; which deliveries did not happen, and why?"
--
-- Neither is answerable from a trip that is merely IN_TRANSIT. This migration adds the three
-- things that make them answerable, and nothing else:
--
--   1. tms.trip_stop.execution_status and its actual times - what happened at each stop;
--   2. tms.transport_event - an append-only log of every execution fact, trip-level and
--      stop-level, in one ordered timeline per shipment;
--   3. tms.trip_exception - the small set of operational problems that need to be *tracked*
--      rather than merely logged, with an OPEN/RESOLVED state so a supervisor can see what is
--      still outstanding.
--
-- What this is NOT is event sourcing. tms.transport_event is a read/report trail beside the
-- transactional tables, exactly the relationship tms.audit_event (V22) has with them: the trip
-- and its stops remain the source of truth and are never rebuilt by replaying this log.

-- ---------------------------------------------------------------------------
-- 1. tms.trip_stop - the execution half of a stop
-- ---------------------------------------------------------------------------
-- Six states, mirrored by planning.domain.StopExecutionStatus:
--
--   PENDING -> ARRIVED -> IN_SERVICE -> COMPLETED
--      |          |  \________________-> FAILED
--      |          \-> COMPLETED
--      \-> SKIPPED
--      \-> FAILED
--
-- IN_SERVICE earns its place rather than being a state that exists to be named (the test V25
-- applied to DISPATCHED, and which DISPATCHED failed): service *duration* is already a modelled
-- planning input - tms.route_stop.service_time_override_minutes (V24) - so recording when service
-- actually started is what lets "we allow 20 minutes at this customer" be checked against what
-- the 20 minutes really are. Arriving and starting are also genuinely different moments at a
-- gated DC, which is where the planned figure is most often wrong.
--
-- ARRIVED -> COMPLETED is legal, so the extra state stays optional: a company that does not
-- record service starts must not be forced through a button that means nothing to them.
--
-- SKIPPED and FAILED are two different facts and not one "did not happen":
--   * SKIPPED - never attempted. The customer called at 06:00, the stop was dropped before the
--     truck got near it. Carries no actual times at all, and the CHECK below enforces that.
--   * FAILED  - attempted and not served: refused at the dock, address not found, nobody there.
--     May carry an arrival (refused at the dock) or none at all (never found the address), which
--     is why arrival is not required for it.
-- Both are terminal, and both require a typed reason - not by a CHECK, which cannot see another
-- table, but by TripStopExecutionService, which opens a tms.trip_exception in the same
-- transaction. "A delivery that did not happen has a reason on file" is the rule; see section 3.
ALTER TABLE tms.trip_stop ADD COLUMN execution_status    text NOT NULL DEFAULT 'PENDING';
ALTER TABLE tms.trip_stop ADD COLUMN actual_arrival_at   timestamptz;
ALTER TABLE tms.trip_stop ADD COLUMN service_started_at  timestamptz;
ALTER TABLE tms.trip_stop ADD COLUMN actual_departure_at timestamptz;
ALTER TABLE tms.trip_stop ADD COLUMN execution_notes     text;

COMMENT ON COLUMN tms.trip_stop.execution_status IS
    'What happened at this stop. Every existing row starts PENDING, which is true of them: a '
    'stop planned before this migration has no execution record, and PENDING is exactly that '
    'statement. The legal moves live in planning.domain.StopExecutionStatus - this column''s '
    'CHECK constrains the values, not the moves, the same division V25''s ck_trip_status uses.';
COMMENT ON COLUMN tms.trip_stop.actual_departure_at IS
    'When the vehicle left this stop - service finished, paperwork signed, wheels rolling. Named '
    'departure and not "completed_at" because it is the counterpart of actual_arrival_at: the '
    'two together are the dwell time, which is the number this column exists to make computable.';
COMMENT ON COLUMN tms.trip_stop.execution_notes IS
    'Free text from whoever worked the stop ("left with security at reception"). Never the '
    'reason a stop was skipped or failed - that is typed, and lives in tms.trip_exception.';

-- No arrived_by / completed_by columns here, and that is a deliberate departure from V25, which
-- paired every trip-level actual time with the app_user who reported it. The reason the two
-- differ: when V25 was written there was no per-event log to hold the actor, so the columns were
-- the only place to put them. tms.transport_event (section 2) is now that place - it is
-- append-only, company-scoped, actor-stamped and written in the same transaction as every
-- transition below. Five more actor columns and five more (time, actor) pair CHECKs would be a
-- second copy of a fact the log already holds, and V25's own argument against storing the
-- button-press moment beside the business time applies to the button-presser too.

ALTER TABLE tms.trip_stop ADD CONSTRAINT ck_trip_stop_execution_status CHECK (execution_status IN (
    'PENDING', 'ARRIVED', 'IN_SERVICE', 'COMPLETED', 'SKIPPED', 'FAILED'));

-- A state that claims a thing happened carries the timestamp of that thing - the same
-- declarative half of the transition table V25 wrote for the trip.
ALTER TABLE tms.trip_stop ADD CONSTRAINT ck_trip_stop_arrival_required CHECK (
    execution_status NOT IN ('ARRIVED', 'IN_SERVICE', 'COMPLETED') OR actual_arrival_at IS NOT NULL);
ALTER TABLE tms.trip_stop ADD CONSTRAINT ck_trip_stop_service_start_required CHECK (
    execution_status <> 'IN_SERVICE' OR service_started_at IS NOT NULL);
ALTER TABLE tms.trip_stop ADD CONSTRAINT ck_trip_stop_departure_required CHECK (
    execution_status <> 'COMPLETED' OR actual_departure_at IS NOT NULL);

-- ...and the converse for the two states that assert nothing happened. PENDING is the stronger
-- of the two: a stop nobody has touched has no times, which is what makes "PENDING" readable as
-- "not started" rather than as "started and not recorded".
ALTER TABLE tms.trip_stop ADD CONSTRAINT ck_trip_stop_pending_has_no_times CHECK (
    execution_status <> 'PENDING' OR (
        actual_arrival_at IS NULL AND service_started_at IS NULL AND actual_departure_at IS NULL));
ALTER TABLE tms.trip_stop ADD CONSTRAINT ck_trip_stop_skipped_has_no_times CHECK (
    execution_status <> 'SKIPPED' OR (
        actual_arrival_at IS NULL AND service_started_at IS NULL AND actual_departure_at IS NULL));

-- Time only moves forward through the stop, null-tolerant on both sides of every pair so a stop
-- completed without a recorded service start is still a legal row.
ALTER TABLE tms.trip_stop ADD CONSTRAINT ck_trip_stop_execution_times_ordered CHECK (
    (service_started_at IS NULL OR actual_arrival_at IS NULL OR service_started_at >= actual_arrival_at)
    AND (actual_departure_at IS NULL OR service_started_at IS NULL
         OR actual_departure_at >= service_started_at)
    AND (actual_departure_at IS NULL OR actual_arrival_at IS NULL
         OR actual_departure_at >= actual_arrival_at));

ALTER TABLE tms.trip_stop ADD CONSTRAINT ck_trip_stop_execution_notes_not_blank
    CHECK (execution_notes IS NULL OR btrim(execution_notes) <> '');
ALTER TABLE tms.trip_stop ADD CONSTRAINT ck_trip_stop_execution_notes_length
    CHECK (length(execution_notes) <= 500);

-- The target of the composite tenant FKs the two new tables below carry (DATA_MODEL.md rule 6).
-- tms.trip has had uq_trip_id_company since V11; trip_stop never needed one because nothing
-- pointed at a stop until now.
ALTER TABLE tms.trip_stop ADD CONSTRAINT uq_trip_stop_id_company UNIQUE (id, company_id);

-- "Which stops on today's board are still outstanding" - the cross-trip question a supervisor
-- asks, and the access path a per-company execution list reads. Partial on the three unresolved
-- states: a finished stop is never the answer to it, and by the end of a day almost every row is
-- finished, which is what makes the partial index small enough to be worth having.
--
-- Not the path TripExecutionService.complete uses: that one asks the same question of a single
-- trip whose stops are already loaded in the aggregate, and answers it in memory rather than
-- with a query it does not need.
CREATE INDEX ix_trip_stop_company_unresolved
    ON tms.trip_stop (company_id, trip_id)
    WHERE execution_status IN ('PENDING', 'ARRIVED', 'IN_SERVICE');

-- ---------------------------------------------------------------------------
-- 2. tms.transport_event - the timeline
-- ---------------------------------------------------------------------------
-- One append-only row per execution fact, trip-level and stop-level in the same table so that
-- "show me this shipment's day" is one ordered read and not a merge of four.
--
-- Why not reuse tms.audit_event (V22), which is also append-only and also records an actor: the
-- two answer different questions for different readers. audit_event is a *security and
-- compliance* trail - who touched which aggregate, under which correlation id - and its
-- vocabulary is CREATE/UPDATE/CANCEL. This is an *operational* trail a dispatcher reads inside
-- the trip workspace, in the vocabulary of a delivery day (arrived, service started, exception
-- reported), carrying a position and free-text notes that have no business in a compliance log.
-- Folding either into the other would make one of the two audiences read the other's noise.
--
-- Why not reuse tms.shipment_outbox_event (V20): that is an *outbound integration* queue with a
-- delivery cursor, deliberately carrying nothing but the shipment number because a partner reads
-- the shipment for the rest. Its rows exist to be consumed once; these exist to be read forever.
CREATE TABLE tms.transport_event (
    id                uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id        uuid          NOT NULL,
    trip_id           uuid          NOT NULL,
    -- NULL for a trip-level event (dispatched, completed), set for one that happened at a stop.
    trip_stop_id      uuid,
    event_type        text          NOT NULL,
    -- When the fact happened, operator-supplied - the same business-time-not-keyboard-time rule
    -- V25 established for the trip's actual times.
    event_time        timestamptz   NOT NULL,
    -- ...and when the row was written, which is the one thing the business time cannot tell you.
    -- Kept here and not left to audit_event (as V25 left the trip's) because this table *is* the
    -- log: an entry backdated to 08:40 and typed at 17:00 is exactly the kind of correction a
    -- supervisor needs to be able to see, and the pair of columns is what shows it.
    recorded_at       timestamptz   NOT NULL DEFAULT now(),
    source            text          NOT NULL,
    -- The same three-column actor tms.audit_event (V22) carries, and for the same two reasons: a
    -- machine gets no invented app_user row, and the email is denormalized so a timeline can name
    -- who acted without this module reaching into the identity module - there is no cross-module
    -- user lookup port, deliberately. It is a snapshot: an operator who later changes their
    -- address keeps the address they acted under, which is what a log should say.
    actor_app_user_id uuid,
    actor_email       text,
    actor_machine_label text,
    -- Optional and never required. A dispatcher typing at a desk has no position to report, and
    -- V1 has no telematics feed to supply one (CLAUDE.md defers GPS by decision) - the columns
    -- exist so that the day a driver app or a telematics integration does report a position,
    -- recording it is an application change and not a migration, which is precisely the bet V20
    -- made and won with its event_type CHECK.
    latitude          numeric(9,6),
    longitude         numeric(9,6),
    notes             text,
    -- Controlled extensibility only: compact JSON of business fields (a shipment number, an
    -- exception type), never a payload, never a secret, never a copy of the row the event points
    -- at. Same shape and same 4000-character ceiling as tms.audit_event.metadata.
    metadata          text,
    CONSTRAINT pk_transport_event PRIMARY KEY (id),
    CONSTRAINT fk_transport_event_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- RESTRICT rather than CASCADE, unlike fk_trip_stop_trip (V11): a trip is never deleted, and
    -- an execution log that a delete could silently take with it is not a log. The stop-level FK
    -- is RESTRICT for the same reason and can never fire in practice - stops are only added and
    -- removed while a trip is DRAFT, and a stop only has events once its trip is IN_TRANSIT.
    CONSTRAINT fk_transport_event_trip FOREIGN KEY (trip_id)
        REFERENCES tms.trip (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transport_event_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),
    CONSTRAINT fk_transport_event_trip_stop FOREIGN KEY (trip_stop_id)
        REFERENCES tms.trip_stop (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transport_event_trip_stop_company FOREIGN KEY (trip_stop_id, company_id)
        REFERENCES tms.trip_stop (id, company_id),
    CONSTRAINT fk_transport_event_actor FOREIGN KEY (actor_app_user_id)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_transport_event_type CHECK (event_type IN (
        'TRIP_CONFIRMED', 'TRIP_READY', 'TRIP_DISPATCHED', 'TRIP_COMPLETED', 'TRIP_CANCELLED',
        'ARRIVED_AT_STOP', 'SERVICE_STARTED', 'STOP_COMPLETED', 'STOP_SKIPPED', 'STOP_FAILED',
        'EXCEPTION_REPORTED', 'EXCEPTION_RESOLVED')),
    -- OPERATOR: a person in the TMS UI. SYSTEM: a TMS rule that recorded a fact nobody typed.
    -- INTEGRATION: an authenticated partner over the M2M API. Deliberately no DRIVER_APP and no
    -- TELEMATICS - neither exists, and a source value with no source is a promise the schema
    -- cannot keep (V20's SHIPMENT_CHANGED spent three migrations being exactly that).
    CONSTRAINT ck_transport_event_source CHECK (source IN ('OPERATOR', 'SYSTEM', 'INTEGRATION')),
    -- Exactly one of "a person" or "a machine", the same xor tms.audit_event carries and for the
    -- same reason: a row that fails it means the recorder has a bug, not that the data is odd.
    CONSTRAINT ck_transport_event_actor_xor CHECK (
        (actor_app_user_id IS NOT NULL) <> (actor_machine_label IS NOT NULL)),
    -- An email belongs to a person and only to a person, exactly as in AuditActor's own contract.
    CONSTRAINT ck_transport_event_actor_email_scope CHECK (
        actor_email IS NULL OR actor_app_user_id IS NOT NULL),
    CONSTRAINT ck_transport_event_operator_is_person CHECK (
        source <> 'OPERATOR' OR actor_app_user_id IS NOT NULL),
    -- A stop event names its stop and a trip event does not. Without this, "the trip arrived"
    -- would be a storable sentence, and the timeline would have entries nothing could place.
    CONSTRAINT ck_transport_event_stop_scope CHECK (
        CASE
            WHEN event_type IN ('ARRIVED_AT_STOP', 'SERVICE_STARTED', 'STOP_COMPLETED',
                                'STOP_SKIPPED', 'STOP_FAILED') THEN trip_stop_id IS NOT NULL
            WHEN event_type IN ('TRIP_CONFIRMED', 'TRIP_READY', 'TRIP_DISPATCHED', 'TRIP_COMPLETED',
                                'TRIP_CANCELLED') THEN trip_stop_id IS NULL
            -- EXCEPTION_REPORTED / EXCEPTION_RESOLVED are legitimately either: a breakdown
            -- happens to a trip, a refused delivery happens at a stop.
            ELSE true
        END),
    CONSTRAINT ck_transport_event_coordinates_pair CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CONSTRAINT ck_transport_event_latitude_range CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_transport_event_longitude_range CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_transport_event_notes_not_blank CHECK (notes IS NULL OR btrim(notes) <> ''),
    CONSTRAINT ck_transport_event_notes_length CHECK (length(notes) <= 1000),
    CONSTRAINT ck_transport_event_metadata_length CHECK (length(metadata) <= 4000),
    CONSTRAINT ck_transport_event_actor_email_not_blank
        CHECK (actor_email IS NULL OR btrim(actor_email) <> ''),
    CONSTRAINT ck_transport_event_actor_machine_label_not_blank
        CHECK (actor_machine_label IS NULL OR btrim(actor_machine_label) <> '')
);

-- The timeline query: one shipment's day in order. event_time and not recorded_at, because the
-- timeline a dispatcher reads is the one the fleet lived, not the one the keyboard saw.
CREATE INDEX ix_transport_event_company_trip_time
    ON tms.transport_event (company_id, trip_id, event_time);

-- "What happened across the fleet this afternoon" - the cross-trip read a supervisor does, and
-- the access path a future integration cursor would use.
CREATE INDEX ix_transport_event_company_time
    ON tms.transport_event (company_id, event_time DESC);

COMMENT ON TABLE tms.transport_event IS
    'Append-only operational log: every execution fact of a trip and its stops, in one ordered '
    'timeline per shipment. Written in the same transaction as the transition it describes and '
    'never updated or deleted (see the grants below). Not event sourcing - the trip and its '
    'stops stay the source of truth and are never rebuilt from this table.';
COMMENT ON COLUMN tms.transport_event.event_time IS
    'When the fact happened, as reported by whoever recorded it. Compare with recorded_at to see '
    'how long after the fact it was entered.';
COMMENT ON COLUMN tms.transport_event.metadata IS
    'Compact JSON, business fields only - never a secret, never the row-level detail already on '
    'the trip or stop this event points at.';

ALTER TABLE tms.transport_event ENABLE ROW LEVEL SECURITY;

-- Append-only as a database fact and not as a convention, exactly as tms.audit_event (V22) is:
-- the four-verb default grant of V13 is narrowed by the REVOKE, and the policies are split into
-- the two operations that remain possible so the page says so without the grants being read
-- first.
GRANT SELECT, INSERT ON tms.transport_event TO tms_app;
REVOKE UPDATE, DELETE ON tms.transport_event FROM tms_app;

CREATE POLICY p_tenant_company_scope_select ON tms.transport_event
    FOR SELECT TO tms_app
    USING (company_id = tms.current_company_id());

CREATE POLICY p_tenant_company_scope_insert ON tms.transport_event
    FOR INSERT TO tms_app
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 3. tms.trip_exception - the problems that need tracking, not just logging
-- ---------------------------------------------------------------------------
-- The one mutable table of the three, and the only justification for its existence is that
-- OPEN/RESOLVED is a real question with a real answer: "what went wrong today that nobody has
-- dealt with yet". An append-only log cannot answer it - a REPORTED entry and a RESOLVED entry
-- are two rows, and asking whether the second exists for every first is a self-join a supervisor
-- screen would run on every refresh.
--
-- That is the whole workflow. Two states, no assignment, no severity, no escalation ladder, no
-- SLA clock: each of those is a product decision nobody has made, and a workflow engine invented
-- ahead of the first customer who needs one is the failure mode this schema has avoided seven
-- times already (see V26's "Deliberately NOT here").
--
-- Operational problems only. A technical error - a failed migration, a 500, a rejected payload -
-- belongs to logs, tms.integration_request and the error handler; putting the two vocabularies in
-- one table would produce a screen where a dispatcher reads stack traces.
CREATE TABLE tms.trip_exception (
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id       uuid        NOT NULL,
    trip_id          uuid        NOT NULL,
    -- Set when the problem is a stop's ("customer closed"), NULL when it is the trip's ("the
    -- truck broke down"). Which types require one is enforced below.
    trip_stop_id     uuid,
    exception_type   text        NOT NULL,
    status           text        NOT NULL DEFAULT 'OPEN',
    reported_at      timestamptz NOT NULL,
    -- NOT NULL, unlike tms.transport_event.actor_app_user_id: V1 has no unattended source of
    -- exceptions. Every one of them is reported by a person through the UI, and the day an
    -- integration reports one, making this nullable is a one-line migration - the reverse, having
    -- allowed nulls nobody produces, is the change that cannot be undone.
    reported_by      uuid        NOT NULL,
    notes            text,
    resolved_at      timestamptz,
    resolved_by      uuid,
    resolution_notes text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_trip_exception PRIMARY KEY (id),
    CONSTRAINT fk_trip_exception_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_exception_trip FOREIGN KEY (trip_id)
        REFERENCES tms.trip (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_exception_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),
    CONSTRAINT fk_trip_exception_trip_stop FOREIGN KEY (trip_stop_id)
        REFERENCES tms.trip_stop (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_exception_trip_stop_company FOREIGN KEY (trip_stop_id, company_id)
        REFERENCES tms.trip_stop (id, company_id),
    CONSTRAINT fk_trip_exception_reported_by FOREIGN KEY (reported_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_exception_resolved_by FOREIGN KEY (resolved_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    -- Small and extensible, in that order. Seven values that cover what a delivery day actually
    -- throws at a fleet, with OTHER as the honest escape hatch rather than as a dumping ground -
    -- it requires notes (below), so choosing it costs a sentence and choosing a typed value does
    -- not, which is the only pressure that keeps a catalogue like this from collapsing into OTHER.
    CONSTRAINT ck_trip_exception_type CHECK (exception_type IN (
        'TRAFFIC_DELAY', 'VEHICLE_BREAKDOWN', 'CUSTOMER_CLOSED', 'DELIVERY_REJECTED',
        'ADDRESS_NOT_FOUND', 'DELIVERY_FAILED', 'OTHER')),
    CONSTRAINT ck_trip_exception_status CHECK (status IN ('OPEN', 'RESOLVED')),
    -- The four types that are statements about a delivery, not about a journey, must name the
    -- stop they are about. TRAFFIC_DELAY and VEHICLE_BREAKDOWN may name one (stuck on the way to
    -- stop 3) or not; OTHER is unconstrained by definition.
    CONSTRAINT ck_trip_exception_stop_scope CHECK (
        exception_type NOT IN ('CUSTOMER_CLOSED', 'DELIVERY_REJECTED', 'ADDRESS_NOT_FOUND',
                               'DELIVERY_FAILED')
        OR trip_stop_id IS NOT NULL),
    -- A biconditional, unlike V25's confirmed_at: there is no third state to weaken it for.
    CONSTRAINT ck_trip_exception_resolved_pair CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL)),
    CONSTRAINT ck_trip_exception_resolved_actor_pair CHECK ((resolved_at IS NULL) = (resolved_by IS NULL)),
    CONSTRAINT ck_trip_exception_resolved_after_reported CHECK (
        resolved_at IS NULL OR resolved_at >= reported_at),
    CONSTRAINT ck_trip_exception_resolution_notes_requires_resolved CHECK (
        resolution_notes IS NULL OR resolved_at IS NOT NULL),
    -- OTHER means nothing on its own; a typed value already says what happened.
    CONSTRAINT ck_trip_exception_other_requires_notes CHECK (
        exception_type <> 'OTHER' OR btrim(coalesce(notes, '')) <> ''),
    CONSTRAINT ck_trip_exception_notes_not_blank CHECK (notes IS NULL OR btrim(notes) <> ''),
    CONSTRAINT ck_trip_exception_notes_length CHECK (length(notes) <= 1000),
    CONSTRAINT ck_trip_exception_resolution_notes_not_blank
        CHECK (resolution_notes IS NULL OR btrim(resolution_notes) <> ''),
    CONSTRAINT ck_trip_exception_resolution_notes_length CHECK (length(resolution_notes) <= 1000)
);

-- "What is still open across the fleet" - the supervisor's standing question, and the reason
-- this table is not the event log. Partial, because a resolved exception is never its answer.
CREATE INDEX ix_trip_exception_company_open
    ON tms.trip_exception (company_id, reported_at DESC)
    WHERE status = 'OPEN';

CREATE INDEX ix_trip_exception_trip
    ON tms.trip_exception (trip_id, reported_at DESC);

CREATE INDEX ix_trip_exception_trip_stop
    ON tms.trip_exception (trip_stop_id)
    WHERE trip_stop_id IS NOT NULL;

COMMENT ON TABLE tms.trip_exception IS
    'An operational problem on a trip or at one of its stops, OPEN until somebody deals with it. '
    'Never a technical error: those belong to logs and tms.integration_request. Every skipped or '
    'failed stop has one, opened by TripStopExecutionService in the same transaction as the stop '
    'transition - which is what makes "why did this delivery not happen" answerable.';
COMMENT ON COLUMN tms.trip_exception.status IS
    'OPEN or RESOLVED, and deliberately nothing else. Resolving records who and when; there is no '
    'assignment, severity or escalation model, because no rule in TMS reads one.';

CREATE TRIGGER tr_trip_exception_set_updated_at
    BEFORE UPDATE ON tms.trip_exception
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.trip_exception ENABLE ROW LEVEL SECURITY;

-- The tenant policy every business table added after V13 carries (ADR-005). All four verbs,
-- unlike the two tables above: an exception is resolved, which is an UPDATE.
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.trip_exception TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.trip_exception
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- Deliberately NOT here
-- ---------------------------------------------------------------------------
--   * No planned_arrival_at / planned_departure_at per stop. There is nothing to put in them:
--     TMS has no ETA engine, and route optimisation is deferred by decision (CLAUDE.md). What a
--     stop plans is its service *window* (service_window_start/end, V11) and, through its master
--     route, its service *duration* (V24) - both real, both already stored. Two columns holding
--     an arrival time nobody computes would read as a plan the actual times could be judged
--     against, and there would be no such plan.
--   * No proof of delivery, no signature, no photo, no per-order delivered/refused quantity. A
--     COMPLETED stop here means "the vehicle served this destination", which is a dispatcher's
--     fact. Recording *what was handed over* is an order-level model with its own quantities and
--     its own disputes, and it needs Supabase Storage for the artefacts - deferred by decision.
--     tms.transport_order therefore still stops at PLANNED, exactly as V25 left it.
--   * No order-level delivery status, for the same reason V25 gave and this migration does not
--     weaken: completing every stop of a trip leaves its orders PLANNED. The orders module owns
--     that lifecycle and will extend it in its own migration.
--   * No audit_event row per stop transition, and no widening of ck_audit_event_action for one.
--     tms.transport_event is itself append-only, actor-stamped and company-scoped: writing both
--     would be two records of one fact, in two vocabularies, that can drift. The trip-level
--     transitions keep their audit rows because they already had them (V25) and because
--     "somebody confirmed this shipment" is a compliance fact as well as an operational one.
--   * No backfill of events for trips that already ran. The log starts now and says so by being
--     empty for them; inventing an ARRIVED_AT_STOP nobody reported would be worse than a gap.
--   * No new permission. planning.trip:execute (V25) is exactly this authority - "move trips
--     through their day" - and stop execution is that same job at a finer grain. A company that
--     wants to separate the two is asking for a driver app, not for a permission.
