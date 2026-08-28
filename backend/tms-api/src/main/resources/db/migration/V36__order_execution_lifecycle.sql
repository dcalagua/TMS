-- ===========================================================================
-- V36 - The order lifecycle learns what happened on the road
-- ===========================================================================
--
-- Until now tms.transport_order carried four states: NOT_READY, READY_FOR_PLANNING, PLANNED and
-- CANCELLED. That was the step 09 brief's deliberate minimum, written when nothing downstream of
-- planning existed and recorded as such in docs/domain/ORDER_LIFECYCLE_V1.md.
--
-- Everything downstream now exists. V25 gave trips an execution lifecycle, V27 gave stops one, and
-- V28 gave every order at every stop a commercial outcome. The order itself never learned any of
-- it, and that left a real operational hole rather than a cosmetic one:
--
--   * an order on a vehicle that had already departed still read PLANNED, indistinguishable from
--     one sitting in a draft trip for next Tuesday;
--   * an order whose delivery was refused stayed PLANNED forever. It was not plannable, so it
--     never came back into the pool for a second attempt; it was not cancellable, because
--     OrderService.cancel refuses a planned order; and it was not deliverable, because its trip
--     was closed. A customer waiting for a redelivery was invisible to the system that owed it;
--   * an order delivered in full stayed PLANNED too, so "what is still outstanding" could only be
--     answered by joining to the delivery rows.
--
-- Four states are added: IN_EXECUTION, DELIVERED, PARTIALLY_DELIVERED, DELIVERY_FAILED.
--
-- ---------------------------------------------------------------------------
-- Why this is not a duplicate of tms.order_delivery
-- ---------------------------------------------------------------------------
--
-- shared.reference.OrderFulfillmentStatus already derives "what happened at the dock" from the
-- delivery rows on read, and its own comment argues - correctly - that a stored copy of that fact
-- would be a second version of the truth kept in step by whoever remembered to.
--
-- That argument still holds and this column does not violate it, because these states are not the
-- delivery fact. They are its lifecycle consequence: what may be done with the order next. Three
-- of the four cannot be derived from delivery rows at all - an order on a departed vehicle has no
-- delivery row yet, an order with nothing recorded is not the same as one nobody planned, and
-- "this is back in the pool for a second attempt" is a decision a person makes, not a fact a row
-- states.
--
-- Drift is prevented structurally rather than by discipline. The status is recomputed from the
-- delivery rows inside the same transaction as every change to them:
--
--     TripExecutionService.dispatch    -> markInExecution for every order on the trip
--     TripExecutionService.complete    -> closeOut from the delivery rows as they stand
--     TripDeliveryService.record       -> closeOut again, for corrections keyed after completion
--
-- The last one is what makes the recording window - deliberately left open after completion,
-- because the signed notes come back at 18:40 - safe: a note keyed late moves the order too.
--
-- See docs/domain/ORDER_LIFECYCLE_V2.md and docs/architecture/ADR-009-order-execution-lifecycle.md.
--
-- ---------------------------------------------------------------------------
-- 1. The status vocabulary
-- ---------------------------------------------------------------------------
--
-- The transition table itself lives in orders.domain.OrderStatus, where it is unit-testable
-- without a database, and is asserted again by TransportOrder as a last line of defense. This
-- CHECK is the backstop under both - it constrains which values may exist, not which moves are
-- legal, exactly as ck_trip_status does for V25.
ALTER TABLE tms.transport_order DROP CONSTRAINT ck_transport_order_status;
ALTER TABLE tms.transport_order ADD CONSTRAINT ck_transport_order_status CHECK (status IN (
    'NOT_READY', 'READY_FOR_PLANNING', 'PLANNED',
    'IN_EXECUTION', 'DELIVERED', 'PARTIALLY_DELIVERED', 'DELIVERY_FAILED',
    'CANCELLED'));

COMMENT ON COLUMN tms.transport_order.status IS
    'The order lifecycle, planning and execution (V10, V36): NOT_READY, READY_FOR_PLANNING, '
    'PLANNED, IN_EXECUTION, DELIVERED, PARTIALLY_DELIVERED, DELIVERY_FAILED, CANCELLED. Which '
    'moves between them are legal is orders.domain.OrderStatus; this constraint only bounds the '
    'vocabulary. The three delivery outcomes are derived from tms.order_delivery at trip close-out '
    'and recomputed on every correction afterwards, so they cannot drift from it.';

-- No data migration, and none is needed. Every existing row is in one of the four original states,
-- all four of which survive with their meaning unchanged. An order that was PLANNED on a trip that
-- has already been completed stays PLANNED: back-filling it would mean inventing a delivery
-- outcome for a shipment nobody recorded one against, and guessing DELIVERED over historical rows
-- is precisely the kind of comfortable lie this schema refuses elsewhere. Those orders close out
-- correctly the moment anything is recorded against them, and until then they read exactly as
-- truthfully as they did yesterday.

-- ---------------------------------------------------------------------------
-- 2. cancel_reason follows the states that may still be cancelled
-- ---------------------------------------------------------------------------
--
-- Unchanged in substance: a reason may only exist on a cancelled order. Restated here only
-- because it is worth being explicit that the new states did not widen it.

-- ---------------------------------------------------------------------------
-- 3. The plannable-pool index
-- ---------------------------------------------------------------------------
--
-- ix_transport_order_company_status (V10) is a full index on (company_id, status) and stays. The
-- hot query underneath planning is narrower than that and now matters more: with orders reopened
-- for a second attempt, READY_FOR_PLANNING is a small live set inside a table that keeps every
-- order the company ever took. A partial index keeps the plannable scan proportional to the work
-- outstanding rather than to the history.
CREATE INDEX ix_transport_order_plannable
    ON tms.transport_order (company_id, service_date)
    WHERE status = 'READY_FOR_PLANNING';

COMMENT ON INDEX tms.ix_transport_order_plannable IS
    'The plannable pool (V36): the orders a planner may assign right now, by service date. Partial '
    'so that it stays the size of the outstanding work rather than of the order history - which is '
    'what makes reopening a failed order for a second attempt cheap to plan against.';

-- ---------------------------------------------------------------------------
-- 4. The audit vocabulary
-- ---------------------------------------------------------------------------
--
-- ORDER_REOPENED joins the list. Its own action rather than an UPDATE, for the reason
-- DRIVER_CHANGE and COST_REOPENED are theirs: reopening is the moment a failed delivery becomes
-- work somebody still owes a customer, and "who decided to try again, and why" is a question asked
-- on its own - usually by the customer.
--
-- Dispatch and close-out mint no new action. Both already produce SHIPMENT_DISPATCHED and
-- SHIPMENT_COMPLETED against the shipment, and the orders' moves are that one fact's consequence,
-- not forty separate decisions. Recording forty ORDER_* rows per completed trip would bury the
-- trail it exists to make readable - the same reasoning V27 gives for not auditing stop
-- transitions.
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
    'ORDER_REOPENED'));

-- ---------------------------------------------------------------------------
-- Deliberately NOT here
-- ---------------------------------------------------------------------------
--
--   * No attempt counter on the order. "How many times have we tried" is answerable from the
--     delivery rows, which carry one per stop per attempt, and a counter beside them would be the
--     second copy this migration's header spends its length avoiding.
--   * No partial-quantity columns. PARTIALLY_DELIVERED says that something is still owed; how
--     much is a ship-unit question and ship units do not exist yet. Adding "delivered_quantity"
--     here would put the allocation ledger in the wrong table and have to be undone.
--   * No automatic reopening. An order that failed goes back into the pool because a person
--     decided it should - a redelivery costs a truck, and a system that silently re-queued every
--     refusal would plan work nobody agreed to pay for.
