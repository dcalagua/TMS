-- ===========================================================================
-- V48 - What it costs US to run our own truck
-- ===========================================================================
--
-- Closes open debt D6, raised by JOB 11 when proposal pricing deliberately left own fleet
-- UNPRICED rather than priced at zero: "a plan mixing a carrier's price with an own-fleet estimate
-- compares two unlike numbers", and pricing it at zero "would make any plan that used it
-- unbeatable".
--
-- ---------------------------------------------------------------------------
-- 1. A PRICE and a COST are not the same number
-- ---------------------------------------------------------------------------
--
-- A carrier presents a PRICE: a commercial figure it has agreed to be paid, which already contains
-- their costs, their overhead and their margin, and which is binding.
--
-- Own fleet produces an INTERNAL COST ESTIMATE: a model of what running the truck consumes,
-- containing no margin, binding nobody, and only ever as good as the rates somebody typed into it.
--
-- Putting them in one column called "price" would let a screen say "own fleet is cheaper" about two
-- figures that are not economically comparable. So the nature travels with the number, all the way
-- to the UI - see com.ebim.tms.shared.reference.TransportCostNature.
--
-- ---------------------------------------------------------------------------
-- 2. Nothing here duplicates tms.trip_cost
-- ---------------------------------------------------------------------------
--
-- tms.trip_cost (V30) remains the single answer to "what did this shipment cost", estimated and
-- actual. This table holds the RATES an own-fleet estimate is computed from - the equivalent of a
-- rate card, for a truck we own. The estimate it produces flows through the existing
-- CostEstimate/CostLine shape, so no second breakdown model exists.

CREATE TABLE tms.own_fleet_cost_profile (
    id                     uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id             uuid          NOT NULL,
    -- Exactly one of the two. A profile is either about one specific truck or about every truck of
    -- a type, and the vehicle-specific one outranks the type (see the resolver). Two typed columns
    -- rather than one polymorphic target, the shape V30 chose for rate-card scope and V42 for
    -- resource blocks: each keeps a real foreign key and the composite tenant guarantee.
    vehicle_id             uuid,
    vehicle_type_id        uuid,
    -- ISO-4217. Never converted: a company running two currencies has two profiles, and a plan
    -- that mixes them is reported incomparable rather than silently added up.
    currency               char(3)       NOT NULL,
    effective_from         date          NOT NULL,
    -- NULL is "still in force". A rate that ends is superseded by the next profile rather than
    -- edited, so a shipment costed last month keeps resolving the rates that were true then.
    effective_to           date,
    active                 boolean       NOT NULL DEFAULT true,

    -- The components. EVERY ONE IS NULLABLE, and null means THIS PROFILE DOES NOT CHARGE FOR IT -
    -- not that it charges zero. A company that does not model depreciation leaves it null; one that
    -- has decided depreciation is nil types 0. The two are different statements and the estimate
    -- treats them differently: a null component contributes nothing and demands no input, a zero
    -- one is a configured rate that still needs its quantity.
    fixed_trip_amount      numeric(14,2),
    fuel_per_km            numeric(14,4),
    driver_per_hour        numeric(14,4),
    vehicle_per_hour       numeric(14,4),
    maintenance_per_km     numeric(14,4),
    depreciation_per_km    numeric(14,4),
    -- A flat expected toll for this scope, per trip. DELIBERATELY NOT DERIVED FROM DISTANCE: tolls
    -- are a function of which roads a route uses, not how long it is, and multiplying kilometres by
    -- an average would produce a number with the shape of a measurement and the content of a guess.
    -- A company that cannot state one leaves it null.
    toll_amount            numeric(14,2),

    notes                  text,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    created_by             uuid,
    updated_by             uuid,

    CONSTRAINT pk_own_fleet_cost_profile PRIMARY KEY (id),
    CONSTRAINT uq_own_fleet_cost_profile_id_company UNIQUE (id, company_id),

    CONSTRAINT fk_own_fleet_cost_profile_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_own_fleet_cost_profile_vehicle FOREIGN KEY (vehicle_id)
        REFERENCES tms.vehicle (id) ON DELETE CASCADE,
    CONSTRAINT fk_own_fleet_cost_profile_vehicle_company FOREIGN KEY (vehicle_id, company_id)
        REFERENCES tms.vehicle (id, company_id),
    CONSTRAINT fk_own_fleet_cost_profile_type FOREIGN KEY (vehicle_type_id)
        REFERENCES tms.vehicle_type (id) ON DELETE CASCADE,
    CONSTRAINT fk_own_fleet_cost_profile_type_company FOREIGN KEY (vehicle_type_id, company_id)
        REFERENCES tms.vehicle_type (id, company_id),

    CONSTRAINT ck_own_fleet_cost_profile_one_target CHECK (
        (vehicle_id IS NOT NULL) <> (vehicle_type_id IS NOT NULL)),
    CONSTRAINT ck_own_fleet_cost_profile_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_own_fleet_cost_profile_window CHECK (
        effective_to IS NULL OR effective_to > effective_from),
    -- Zero is legal in every component and negative is not. Zero is a real statement ("we do not
    -- charge depreciation on this truck"); a negative rate is a subsidy model this product does not
    -- have, and would produce an estimate below zero with no way to explain it. The same rule V30
    -- states for rate cards.
    CONSTRAINT ck_own_fleet_cost_profile_amounts_nonnegative CHECK (
        COALESCE(fixed_trip_amount, 0) >= 0 AND COALESCE(fuel_per_km, 0) >= 0
        AND COALESCE(driver_per_hour, 0) >= 0 AND COALESCE(vehicle_per_hour, 0) >= 0
        AND COALESCE(maintenance_per_km, 0) >= 0 AND COALESCE(depreciation_per_km, 0) >= 0
        AND COALESCE(toll_amount, 0) >= 0),
    -- A profile that charges for nothing is not a profile. Deliberately NOT requiring a specific
    -- component: a company whose only modelled cost is a flat trip charge has said something real.
    CONSTRAINT ck_own_fleet_cost_profile_has_a_component CHECK (
        fixed_trip_amount IS NOT NULL OR fuel_per_km IS NOT NULL OR driver_per_hour IS NOT NULL
        OR vehicle_per_hour IS NOT NULL OR maintenance_per_km IS NOT NULL
        OR depreciation_per_km IS NOT NULL OR toll_amount IS NOT NULL),
    CONSTRAINT ck_own_fleet_cost_profile_notes_not_blank CHECK (notes IS NULL OR btrim(notes) <> '')
);

-- No two active profiles may cover one target on one day.
--
-- Expressed in the database because a service check cannot close the race, and because the
-- alternative - resolving "which of these two applies" with a tie-break rule - makes the answer
-- depend on an ordering nobody chose. This is the same EXCLUDE technique V41 used for dock
-- bookings and V42 for resource blocks, over a daterange instead of a timestamp range.
--
-- daterange(effective_from, effective_to) is half-open, so a profile ending on the 1st and one
-- starting on the 1st do not overlap - which is how a rate change is entered.
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

ALTER TABLE tms.own_fleet_cost_profile
    ADD CONSTRAINT ex_own_fleet_profile_vehicle_no_overlap
        EXCLUDE USING gist (
            vehicle_id WITH =,
            daterange(effective_from, effective_to) WITH &&
        ) WHERE (vehicle_id IS NOT NULL AND active),
    ADD CONSTRAINT ex_own_fleet_profile_type_no_overlap
        EXCLUDE USING gist (
            vehicle_type_id WITH =,
            daterange(effective_from, effective_to) WITH &&
        ) WHERE (vehicle_type_id IS NOT NULL AND active);

CREATE INDEX ix_own_fleet_cost_profile_company ON tms.own_fleet_cost_profile (company_id);

COMMENT ON TABLE tms.own_fleet_cost_profile IS
    'The rates an own-fleet cost estimate is computed from (V48) - a rate card for a truck we own. '
    'Produces an INTERNAL COST, never a price: there is no margin in it, it binds nobody, and it is '
    'only ever as good as the rates somebody typed in. tms.trip_cost remains the single answer to '
    'what a shipment cost.';

COMMENT ON COLUMN tms.own_fleet_cost_profile.toll_amount IS
    'A flat expected toll per trip for this scope (V48). NOT derived from distance, deliberately: '
    'tolls depend on which roads a route uses rather than how long it is, and kilometres times an '
    'average would look like a measurement and be a guess. Null when a company cannot state one.';

COMMENT ON COLUMN tms.own_fleet_cost_profile.depreciation_per_km IS
    'NULL means this profile does not charge for depreciation. ZERO means it charges nothing for '
    'it. The two are different statements: a null component demands no input and contributes '
    'nothing, a zero one is a configured rate that still needs its distance before a total is '
    'comparable.';

-- ---------------------------------------------------------------------------
-- 3. Tenant isolation (ADR-005)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.own_fleet_cost_profile ENABLE ROW LEVEL SECURITY;

-- Deactivated rather than deleted where possible, but DELETE is granted: a profile keyed by mistake
-- and never used to cost anything is noise, not history. What must not be edited is a stored
-- estimate, and V30 already governs that.
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.own_fleet_cost_profile TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.own_fleet_cost_profile
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 4. Permissions
-- ---------------------------------------------------------------------------
--
-- Its own resource rather than folded into rates: a tariff is a commercial agreement and an
-- internal cost model is a finance artefact, and an installation will want them in different hands.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('costing.own_fleet', 'read',  'View what running our own vehicles is modelled to cost'),
    ('costing.own_fleet', 'write', 'Configure own-fleet cost rates');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code IN ('costing.own_fleet:read', 'costing.own_fleet:write');

-- A planner reads it - comparing a carrier's price with an own-fleet estimate is the decision they
-- make - and does not set the rates, which is a finance decision about the business.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code = 'PLANNER'
  AND p.code = 'costing.own_fleet:read';

-- ---------------------------------------------------------------------------
-- 5. Deliberately NOT here
-- ---------------------------------------------------------------------------
--
--   * No actual own-fleet cost. TMS has no fuel consumption readings, no payroll, no workshop
--     invoices and no toll records, so an "actual" here would be the estimate wearing a different
--     label. tms.trip_cost.actual_* stays what it is: a figure a person recorded.
--   * No cost allocation across orders. Debt D10, still open: knowing what a trip cost does not
--     decide how that cost is shared, and choosing delivered quantity, weight, volume or pallets as
--     a default would be inventing the business rule nobody has approved.
--   * No company-wide fallback profile. A rate that applies to every truck a company owns,
--     regardless of type, is a number that means nothing - a van and an articulated truck do not
--     share a fuel rate. Two levels (vehicle, then type) and then honestly no cost.
--   * No FX. Two currencies do not add up.

-- ---------------------------------------------------------------------------
-- 6. Audit vocabulary
-- ---------------------------------------------------------------------------
--
-- Rates are money, and who changed them and when is a question finance will ask. The four actions
-- it uses (CREATE, UPDATE, ACTIVATE, DEACTIVATE) already exist; only the aggregate is new.
--
-- The list below is GENERATED FROM tms.shared.audit.AuditAggregateType rather than typed, because
-- V46 was typed from memory and silently omitted three values - AuditVocabularyMigrationTest caught
-- it, and the fix was to stop writing these by hand.
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
    'MEMBERSHIP',
    'ORDER_IMPORT_BATCH',
    'OWN_FLEET_COST_PROFILE',
    'PLANNING_RUN',
    'RATE_CARD',
    'SHIPMENT',
    'TRANSPORT_ORDER',
    'TRIP',
    'TRIP_COST',
    'VEHICLE',
    'WEBHOOK_SUBSCRIPTION'
));
