-- TMS by EBIM - V25 trip execution lifecycle: the states and actual times that turn a *planned*
-- trip into an *operated* one, plus the events those transitions publish.
--
-- Design and the full transition table: docs/domain/TRIP_EXECUTION_V1.md.
--
-- Until V24 a trip had exactly three states - DRAFT while its run is open, CONFIRMED once the run
-- is confirmed, CANCELLED when a planner discards a draft (V11). That is a complete *planning*
-- lifecycle and an empty *execution* one: nothing recorded that the truck was loaded, that it
-- left, or that it came back, so "which shipments are still out there right now" was not a
-- question the database could answer.
--
-- This migration adds the three states between "the plan is binding" and "the day is over":
--
--   DRAFT -> CONFIRMED -> READY_FOR_DISPATCH -> IN_TRANSIT -> COMPLETED
--                 \____________/____________/
--                        \-> CANCELLED
--
-- Deliberately five states and not seven. DISPATCHED and IN_TRANSIT are one state here, because
-- nothing distinguishes them: the act of dispatching IS the departure, and a separate DISPATCHED
-- row would carry the same columns as an IN_TRANSIT one with no way to tell which is which. The
-- same reasoning keeps per-stop arrival/departure out of this migration - see "Deliberately NOT
-- here" at the bottom.
--
-- ---------------------------------------------------------------------------
-- 1. tms.trip - actual times and their actors
-- ---------------------------------------------------------------------------
-- Three business timestamps, one per transition that has one. Each is *operator-supplied* and
-- defaults to now() only in the service: a dispatcher who marks a trip departed at 08:40 while
-- standing at the gate at 09:05 must be able to record 08:40, or the actual times are a log of
-- when somebody reached a keyboard rather than of what the fleet did.
--
-- What is NOT stored next to them is the moment the button was pressed. tms.audit_event (V22)
-- already records that, with the actor and the request that carried it, for every one of these
-- transitions - a second copy here would be a second source of truth for the same fact, and the
-- two would drift the first time a row was corrected.
ALTER TABLE tms.trip ADD COLUMN ready_at             timestamptz;
ALTER TABLE tms.trip ADD COLUMN ready_by             uuid;
ALTER TABLE tms.trip ADD COLUMN actual_departure_at  timestamptz;
ALTER TABLE tms.trip ADD COLUMN dispatched_by        uuid;
ALTER TABLE tms.trip ADD COLUMN actual_completion_at timestamptz;
ALTER TABLE tms.trip ADD COLUMN completed_by         uuid;

COMMENT ON COLUMN tms.trip.ready_at IS
    'When the shipment was declared ready for dispatch - loaded, documented, waiting for the '
    'driver. Operator-supplied, not the moment the button was pressed (tms.audit_event has that).';
COMMENT ON COLUMN tms.trip.actual_departure_at IS
    'When the vehicle actually left the origin, as reported by whoever dispatched it. The '
    'counterpart of planned_departure_at, which is what the plan asked for and is never rewritten '
    'by this column - the gap between the two is the delay a report wants to measure.';
COMMENT ON COLUMN tms.trip.actual_completion_at IS
    'When the trip finished - the last stop served and the vehicle released. Named completion '
    'rather than arrival because V1 tracks the trip, not the return leg: there is no per-stop '
    'arrival model yet (see this migration''s closing note).';

ALTER TABLE tms.trip ADD CONSTRAINT fk_trip_ready_by FOREIGN KEY (ready_by)
    REFERENCES tms.app_user (id) ON DELETE RESTRICT;
ALTER TABLE tms.trip ADD CONSTRAINT fk_trip_dispatched_by FOREIGN KEY (dispatched_by)
    REFERENCES tms.app_user (id) ON DELETE RESTRICT;
ALTER TABLE tms.trip ADD CONSTRAINT fk_trip_completed_by FOREIGN KEY (completed_by)
    REFERENCES tms.app_user (id) ON DELETE RESTRICT;

-- ---------------------------------------------------------------------------
-- 2. The status domain, widened
-- ---------------------------------------------------------------------------
ALTER TABLE tms.trip DROP CONSTRAINT ck_trip_status;
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_status CHECK (status IN (
    'DRAFT', 'CONFIRMED', 'READY_FOR_DISPATCH', 'IN_TRANSIT', 'COMPLETED', 'CANCELLED'));

COMMENT ON COLUMN tms.trip.status IS
    'The trip''s position in the lifecycle. DRAFT is the only state in which vehicle, assignments '
    'and stops may change; CONFIRMED, READY_FOR_DISPATCH, IN_TRANSIT and COMPLETED are the '
    '"committed" states, in which the plan is binding and only execution facts are still '
    'writable; CANCELLED is terminal and reachable from every state before departure. The legal '
    'transitions live in planning.domain.TripStatus - this CHECK constrains the *values*, not the '
    'moves between them, exactly as V11''s did.';

-- uq_trip_vehicle_active_planning_date (V16) needs no change: it is partial on
-- "status <> 'CANCELLED'", so the three new states count as active for double booking - which is
-- the right answer. A truck that is out on the road is even less available than one that is
-- merely planned.

-- ---------------------------------------------------------------------------
-- 3. The coherence constraints, restated for six states
-- ---------------------------------------------------------------------------
-- V11's versions all said "CONFIRMED" where they meant "the plan is binding", because CONFIRMED
-- was the only such state. Each is dropped and restated over the four committed states.

-- (a) A committed trip is a complete one. Same columns V11 required, four states instead of one.
ALTER TABLE tms.trip DROP CONSTRAINT ck_trip_confirmed_is_complete;
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_confirmed_is_complete CHECK (
    status NOT IN ('CONFIRMED', 'READY_FOR_DISPATCH', 'IN_TRANSIT', 'COMPLETED') OR (
        vehicle_id IS NOT NULL
        AND planned_departure_at IS NOT NULL
        AND snapshot_max_weight_kg IS NOT NULL
        AND snapshot_max_volume_m3 IS NOT NULL
        AND snapshot_max_pallets IS NOT NULL
        AND capacity_snapshot_at IS NOT NULL));

-- (b) The converse, and the renamed one. V11 asked "only a CONFIRMED trip carries a snapshot",
-- which is now false in the harmless direction: an IN_TRANSIT trip carries the snapshot it was
-- confirmed with, and so does a trip cancelled after confirmation. What still has to be true is
-- the question the capacity model actually asks - "is this trip reading live capacity or frozen
-- capacity?" - and that is answered by "a DRAFT trip has no snapshot". Renamed to say so.
ALTER TABLE tms.trip DROP CONSTRAINT ck_trip_snapshot_requires_confirmed;
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_draft_has_no_snapshot CHECK (
    status <> 'DRAFT' OR (
        snapshot_max_weight_kg IS NULL
        AND snapshot_max_volume_m3 IS NULL
        AND snapshot_max_pallets IS NULL
        AND capacity_snapshot_at IS NULL));

-- (c) confirmed_at. V11 made it a biconditional with status = 'CONFIRMED'; both halves now have
-- to be weakened, in opposite directions, so it splits into two implications:
--   * every committed state has a confirmed_at - the trip passed through confirmation to get
--     there, and losing that timestamp on dispatch would erase when the plan became binding;
--   * a CANCELLED trip may or may not have one, because cancellation is now reachable from
--     CONFIRMED and READY_FOR_DISPATCH as well as from DRAFT. What stays impossible is a DRAFT
--     trip that claims to have been confirmed.
ALTER TABLE tms.trip DROP CONSTRAINT ck_trip_confirmed_pair;
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_committed_requires_confirmed_at CHECK (
    status NOT IN ('CONFIRMED', 'READY_FOR_DISPATCH', 'IN_TRANSIT', 'COMPLETED')
    OR confirmed_at IS NOT NULL);
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_confirmed_at_requires_left_draft CHECK (
    confirmed_at IS NULL OR status <> 'DRAFT');

-- ck_trip_cancelled_pair (V11) is untouched and still exact: cancelled_at is set if and only if
-- the trip is CANCELLED, whatever it was cancelled from.

-- ---------------------------------------------------------------------------
-- 4. What each execution state guarantees
-- ---------------------------------------------------------------------------
-- The declarative half of the transition table: a state that claims a thing happened must carry
-- the timestamp of that thing. TripExecutionService checks the same rules first, with messages a
-- dispatcher can read; these are what make an incoherent row impossible even from raw SQL - the
-- relationship every other CHECK in this schema has with its service.
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_ready_requires_timestamp CHECK (
    status NOT IN ('READY_FOR_DISPATCH', 'IN_TRANSIT', 'COMPLETED') OR ready_at IS NOT NULL);
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_in_transit_requires_departure CHECK (
    status NOT IN ('IN_TRANSIT', 'COMPLETED') OR actual_departure_at IS NOT NULL);
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_completed_requires_completion CHECK (
    status <> 'COMPLETED' OR actual_completion_at IS NOT NULL);

-- An execution fact never exists without the actor who reported it, and never without the trip
-- having been confirmed first - a DRAFT trip cannot have departed.
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_ready_actor_pair
    CHECK ((ready_at IS NULL) = (ready_by IS NULL));
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_dispatched_actor_pair
    CHECK ((actual_departure_at IS NULL) = (dispatched_by IS NULL));
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_completed_actor_pair
    CHECK ((actual_completion_at IS NULL) = (completed_by IS NULL));
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_execution_requires_confirmed CHECK (
    confirmed_at IS NOT NULL
    OR (ready_at IS NULL AND actual_departure_at IS NULL AND actual_completion_at IS NULL));

-- Time only moves forward through the lifecycle. Null-tolerant on both sides of every pair, so a
-- trip that stops halfway (cancelled after being made ready) is still a legal row.
ALTER TABLE tms.trip ADD CONSTRAINT ck_trip_execution_times_ordered CHECK (
    (ready_at IS NULL OR confirmed_at IS NULL OR ready_at >= confirmed_at)
    AND (actual_departure_at IS NULL OR ready_at IS NULL OR actual_departure_at >= ready_at)
    AND (actual_completion_at IS NULL OR actual_departure_at IS NULL
         OR actual_completion_at >= actual_departure_at));

-- ---------------------------------------------------------------------------
-- 5. The execution board's index
-- ---------------------------------------------------------------------------
-- The Trips screen's default question is "this company's trips around this date, newest first",
-- narrowed by status. Cancelled trips are in the index too - a dispatcher filtering for them is a
-- normal query, unlike the partial index in V16 whose whole purpose was to exclude them.
CREATE INDEX ix_trip_company_planning_date_status
    ON tms.trip (company_id, planning_date DESC, status);

-- ---------------------------------------------------------------------------
-- 6. The events the new transitions publish
-- ---------------------------------------------------------------------------
-- V20 accepted three event types and wrote exactly one, documenting that SHIPMENT_CANCELLED and
-- SHIPMENT_CHANGED had no source "so the day either business rule changes, emitting the event is
-- an application change, not a migration". That day is this one for SHIPMENT_CANCELLED: a
-- confirmed shipment can now be cancelled, and a partner that was told it was confirmed has to be
-- told it is not happening. The two new values are the ones V20 could not have anticipated,
-- because the states they describe did not exist.
ALTER TABLE tms.shipment_outbox_event DROP CONSTRAINT ck_shipment_outbox_event_type;
ALTER TABLE tms.shipment_outbox_event ADD CONSTRAINT ck_shipment_outbox_event_type CHECK (
    event_type IN ('SHIPMENT_CONFIRMED', 'SHIPMENT_CHANGED', 'SHIPMENT_CANCELLED',
                   'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED'));

COMMENT ON COLUMN tms.shipment_outbox_event.event_type IS
    'One row per publishable trip-state change. SHIPMENT_CONFIRMED (V19/V20) is written by '
    'PlanningRunService.confirm; SHIPMENT_READY, SHIPMENT_DISPATCHED, SHIPMENT_COMPLETED and '
    'SHIPMENT_CANCELLED by TripExecutionService, each in the same transaction as the transition '
    'it describes. SHIPMENT_CHANGED still has no source: the committed states remain locked '
    'against edits to what a shipment carries, so TMS cannot yet produce a change to publish.';

-- ---------------------------------------------------------------------------
-- 7. The audit actions the new transitions record
-- ---------------------------------------------------------------------------
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
    'CREATE', 'UPDATE', 'ACTIVATE', 'DEACTIVATE', 'ASSIGN_ORDER', 'REMOVE_ORDER', 'MOVE_ORDER',
    'VEHICLE_CHANGE', 'CONFIRM', 'CANCEL', 'CREDENTIAL_CREATE', 'CREDENTIAL_ROTATE',
    'CREDENTIAL_REVOKE', 'AUTO_PLAN', 'IMPORT_EXECUTED', 'SHIPMENT_CONFIRMED',
    'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED', 'SHIPMENT_CANCELLED'));

-- AUTO_PLAN is in the list above and was NOT in V22's, and that is a defect being fixed here,
-- not housekeeping. AutoPlanningService.apply records AuditAction.AUTO_PLAN
-- (AutoPlanningService.java, "auditRecorder.record(... AuditAction.AUTO_PLAN ...)") inside the
-- caller's own transaction, so against a real PostgreSQL every POST
-- /planning/runs/{id}/auto-plan violated this CHECK and rolled the whole apply back. It went
-- unnoticed because the only tests that reach a real database are the Testcontainers ones, which
-- do not run in this environment (CLAUDE.md, "Local environment notes"). AuditAction has carried
-- the constant since automatic planning V1; the CHECK it claims to mirror had simply not been
-- widened with it.

-- ---------------------------------------------------------------------------
-- 8. planning.trip:execute
-- ---------------------------------------------------------------------------
-- Execution is a different job from planning, and V1 says so with a permission rather than with a
-- comment. A dispatcher who moves trips through their day must not thereby be able to reopen the
-- plan and reassign orders (planning.trip:manage), and the reverse is just as true - which is why
-- this is a new permission and not a widening of the existing one. Both are granted together to
-- the seeded roles because TMS ships no dispatcher role yet; a customer that wants the split
-- creates a custom role, which is exactly what the (role, permission) model is for.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('planning.trip', 'execute', 'Move trips through dispatch and completion');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE p.code = 'planning.trip:execute'
  AND r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN', 'PLANNER');

-- VIEWER is deliberately not in that list: V3 granted it every `read` permission and nothing
-- else, and `execute` is not a read.

-- ---------------------------------------------------------------------------
-- Deliberately NOT here
-- ---------------------------------------------------------------------------
--   * No per-stop arrival/departure/proof-of-delivery. tms.trip_stop stays a *planning* row (a
--     sequence and a service window). Recording execution per stop needs a delivery-outcome model
--     - delivered, partial, refused, with quantities and a reason - and inventing three
--     timestamp columns now would be the shape of that feature without any of its rules. The
--     trip-level actual times answer the questions V1 actually asks ("has it left?", "is it
--     back?", "how late?").
--   * No order-level delivery status. tms.transport_order stops at PLANNED (V10, and
--     orders.domain.OrderStatus says so explicitly). Completing a trip therefore leaves its
--     orders PLANNED rather than inventing a DELIVERED they have no lifecycle for; that belongs
--     to the orders module, with its own transitions and its own migration.
--   * No GPS, no telematics, no live position. CLAUDE.md defers those by decision. IN_TRANSIT
--     here means "a person said it left", which is a fact TMS owns, not a position it guesses.
