-- ============================================================================
-- V64 - Settlement hardening: charge codes, match basis, tolerance audit (ADR-024 part 1, phase 50)
-- ============================================================================
-- V46 compared, per shipment, the SUM of an invoice's lines with the shipment's estimate, and called
-- a second line of the same shipment a duplicate. A carrier that bills freight and a toll on two
-- lines was therefore reported as billing the shipment twice. From here a line says which charge it
-- is (charge_code), the matcher compares per component against the cost breakdown, and only the
-- same (shipment, charge) twice is a duplicate.
--
-- freight_match.expected_basis says what the invoice was compared against: the closed actual cost
-- (ACTUAL), the estimate because the cost is still open (ESTIMATED), or both across its shipments
-- (MIXED). Null on matches computed before V64 and on UNMATCHABLE ones.
--
-- No rows are seeded. The permission settlement.tolerance:manage was seeded by V46 and now guards
-- the tolerance endpoints; no new permission. Every V46 line stays valid: a null charge_code reads
-- as FREIGHT (all-in).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. tms.carrier_invoice_line.charge_code
-- ---------------------------------------------------------------------------
-- The rate components a carrier bills (com.ebim.tms.rates.domain.RateComponent minus the two
-- adjustments, which are compared under BASE), FREIGHT for an all-in line, PENALTY for a deduction.
ALTER TABLE tms.carrier_invoice_line ADD COLUMN charge_code text;

ALTER TABLE tms.carrier_invoice_line ADD CONSTRAINT ck_carrier_invoice_line_charge_code CHECK (
    charge_code IS NULL OR charge_code IN (
        'FREIGHT', 'BASE', 'DISTANCE', 'WEIGHT', 'VOLUME', 'PALLETS', 'LOADING_METERS', 'STOP_OFF',
        'FUEL_SURCHARGE', 'WAITING_TIME', 'TOLL', 'OTHER_ACCESSORIAL', 'PENALTY'));

COMMENT ON COLUMN tms.carrier_invoice_line.charge_code IS
    'Which charge this line bills (V64, ADR-024): a rate component name, FREIGHT (all-in) or PENALTY. '
    'NULL reads as FREIGHT - every line recorded before V64. Two lines of one shipment with different '
    'codes are summed, not duplicates; the same shipment and code twice is a duplicate.';

-- The cross-invoice duplicate question is now (trip, charge): asked on every match.
CREATE INDEX IF NOT EXISTS ix_carrier_invoice_line_trip_charge
    ON tms.carrier_invoice_line (company_id, trip_id, charge_code) WHERE trip_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. tms.freight_match.expected_basis
-- ---------------------------------------------------------------------------
ALTER TABLE tms.freight_match ADD COLUMN expected_basis text;

ALTER TABLE tms.freight_match ADD CONSTRAINT ck_freight_match_expected_basis CHECK (
    expected_basis IS NULL OR expected_basis IN ('ACTUAL', 'ESTIMATED', 'MIXED'));

-- A basis names what the expected figure is; with no expected figure there is no basis.
ALTER TABLE tms.freight_match ADD CONSTRAINT ck_freight_match_basis_needs_expected CHECK (
    expected_basis IS NULL OR expected_amount IS NOT NULL);

COMMENT ON COLUMN tms.freight_match.expected_basis IS
    'What expected_amount is (V64, ADR-024): ACTUAL = the closed actual cost of every matched '
    'shipment, ESTIMATED = the estimate (cost still open), MIXED = some of each. NULL before V64 and '
    'when nothing was comparable.';

-- ---------------------------------------------------------------------------
-- 3. Audit vocabulary (the whole lists, restated)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
    'CREATE', 'UPDATE', 'ACTIVATE', 'DEACTIVATE', 'ASSIGN_ORDER', 'REMOVE_ORDER', 'MOVE_ORDER',
    'VEHICLE_CHANGE', 'DRIVER_CHANGE', 'CONFIRM', 'CANCEL', 'CREDENTIAL_CREATE',
    'CREDENTIAL_ROTATE', 'CREDENTIAL_REVOKE', 'AUTO_PLAN', 'IMPORT_EXECUTED', 'SHIPMENT_CONFIRMED',
    'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED', 'SHIPMENT_CANCELLED',
    'DELIVERY_RESULT_RECORDED', 'COST_ESTIMATED', 'COST_ACTUAL_RECORDED', 'COST_CLOSED',
    'COST_REOPENED',
    'TENDER_SENT', 'TENDER_ACCEPTED', 'TENDER_REJECTED', 'TENDER_EXPIRED', 'TENDER_CANCELLED',
    'ROLES_CHANGED', 'ORDER_REOPENED',
    'WATERFALL_STARTED', 'WATERFALL_ENDED',
    'APPOINTMENT_BOOKED', 'APPOINTMENT_RESCHEDULED', 'APPOINTMENT_CANCELLED', 'APPOINTMENT_NO_SHOW',
    'RESOURCE_BLOCKED', 'RESOURCE_RELEASED',
    'INVOICE_RECEIVED', 'INVOICE_MATCHED', 'INVOICE_APPROVED', 'INVOICE_REJECTED',
    'INVOICE_EXPORTED',
    'DISPATCH_OVERRIDDEN',
    'ORDER_RELEASED', 'ORDER_HOLD_PLACED', 'ORDER_HOLD_RELEASED',
    -- V59 (ADR-022).
    'MATERIAL_PROFILE_CREATED', 'MATERIAL_PROFILE_UPDATED',
    -- V64 (ADR-024 part 1).
    'TOLERANCE_POLICY_CHANGED'));

-- Generated from com.ebim.tms.shared.audit.AuditAggregateType (V48's rule), plus TOLERANCE_POLICY.
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_aggregate_type;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_aggregate_type CHECK (aggregate_type IN (
    'APPOINTMENT',
    'APP_USER',
    'CARRIER',
    'CARRIER_INVOICE',
    'COMPANY',
    'DRIVER',
    'INTEGRATION_CLIENT',
    'LOCATION',
    'LOCATION_RESOURCE',
    'MASTER_DATA_IMPORT_BATCH',
    'MATERIAL_PROFILE',
    'MEMBERSHIP',
    'ORDER_IMPORT_BATCH',
    'OWN_FLEET_COST_PROFILE',
    'PLANNING_RUN',
    'RATE_CARD',
    'SHIPMENT',
    'TOLERANCE_POLICY',
    'TRANSPORT_ORDER',
    'TRIP',
    'TRIP_COST',
    'VEHICLE',
    'WEBHOOK_SUBSCRIPTION'
));
