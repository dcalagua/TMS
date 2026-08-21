-- TMS by EBIM - V30 rates and costing: what a carrier charges for a shipment, and what one
-- shipment actually cost.
--
-- Design and the full rule set: docs/domain/RATES_COSTING_V1.md.
--
-- Until now a trip could be planned, dispatched, delivered and closed without anyone being able
-- to say what it was worth. Everything operational was recorded and nothing commercial was, so
-- "which corridor is losing money" and "did this carrier invoice what we agreed" were questions
-- that had to be answered in somebody's spreadsheet.
--
-- Three tables, in the order they depend on each other:
--   1. tms.rate_card    - a company/carrier agreement, valid between two dates, made of the few
--                         components a terrestrial shipment can actually be charged by.
--   2. tms.trip_cost    - one row per trip: the estimate (snapshotted from the card that won) and
--                         the actual, side by side and never overwriting each other.
--   3. tms.trip_cost_component - the estimate's line items, including the ones that could NOT be
--                         calculated and why, which is the whole reason this is a table.
--
-- This is deliberately not a rate engine. There are no accessorials, no fuel index, no break
-- tables, no lane matrix, no tariff versioning graph. Every component here is one a company can
-- state in a sentence and TMS can prove from data it already holds; the closing "Deliberately NOT
-- here" section lists what was left out and what would have to exist first.

-- ---------------------------------------------------------------------------
-- 1. tms.rate_card
-- ---------------------------------------------------------------------------
CREATE TABLE tms.rate_card (
    id                uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id        uuid        NOT NULL,
    code              text        NOT NULL,
    name              text        NOT NULL,
    -- Mandatory, unlike every other scope column below. A rate is what somebody charges, and in
    -- this product that somebody is always a carrier - including a company's own fleet operator,
    -- which V9 already models as a carrier row rather than as an absence. A card with no carrier
    -- would be a cost model of the company's own trucks (drivers, fuel, depreciation), which is a
    -- different feature with different inputs and is not this one.
    carrier_id        uuid        NOT NULL,
    -- How narrowly this card applies, and the only three answers a *trip* can be matched against
    -- unambiguously:
    --   CARRIER - anything this carrier runs. The fallback every company can state on day one.
    --   ORIGIN  - anything leaving this depot, whatever it then serves.
    --   ROUTE   - shipments built from this master corridor.
    -- Zone is absent on purpose and it is the one omission worth naming here: a zone belongs to a
    -- destination, a trip serves many destinations, and "which zone is this trip in" therefore has
    -- no answer that is true rather than convenient. Charging by zone needs a per-stop cost model
    -- (a cost row per trip_stop, allocated back to orders), which is a bigger feature than a
    -- fourth value in this CHECK, and inventing a rule like "the zone of the first stop" would
    -- produce invoices nobody could defend.
    scope             text        NOT NULL,
    -- The scope's target, split into two typed columns rather than one polymorphic scope_id, so
    -- each one keeps a real foreign key and the composite tenant guarantee of rule 6. Exactly one
    -- is non-null, and which one is decided by `scope` - see ck_rate_card_scope_target.
    origin_id         uuid,
    route_id          uuid,
    -- Optional refinement, orthogonal to `scope`: the same corridor costs one price in a 3.5t van
    -- and another in an articulated truck. NULL means "any vehicle type", and a card that names a
    -- type outranks the card that does not at the same scope (RateCardSelector).
    vehicle_type_id   uuid,
    -- ISO 4217, stored as the three upper-case letters and nothing else. No FX and no conversion
    -- anywhere in V1: an estimate and the actual it is compared against are the same currency by
    -- construction (tms.trip_cost holds one currency for both), and a company operating in two
    -- currencies keeps two sets of cards.
    currency          text        NOT NULL,
    -- Validity as two dates, both inclusive, valid_to NULL meaning open-ended. Dates and not
    -- timestamps because a tariff is agreed per day, never per minute, and the day it is judged
    -- against is the trip's planning date.
    valid_from        date        NOT NULL,
    valid_to          date,
    -- The components. All nullable, all independent, and at least one required
    -- (ck_rate_card_has_a_component): a card is "base 120.00 plus 0.85 per km", or "12.50 per
    -- pallet", or any other combination of these five, and a card that charges nothing is not a
    -- rate.
    --
    -- Two scales on purpose: an amount somebody pays is money (2 decimals), a unit rate is a
    -- coefficient and rounding it to cents would make 0.085/kg unstateable.
    base_amount       numeric(14,2),
    amount_per_km     numeric(14,4),
    amount_per_kg     numeric(14,4),
    amount_per_m3     numeric(14,4),
    amount_per_pallet numeric(14,4),
    -- A floor, applied after everything else: the minimum a carrier charges for turning a wheel.
    -- The one "surcharge-shaped" thing V1 ships, because it is universal in road freight, needs no
    -- new input, and is one subtraction - not because the model wants a surcharge framework.
    minimum_amount    numeric(14,2),
    active            boolean     NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_by        uuid,
    CONSTRAINT pk_rate_card PRIMARY KEY (id),
    CONSTRAINT fk_rate_card_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- Target for tms.trip_cost's composite FK below - the same idiom as uq_carrier_id_company (V9).
    CONSTRAINT uq_rate_card_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_rate_card_company_code UNIQUE (company_id, code),
    CONSTRAINT ck_rate_card_code_normalized CHECK (code = upper(btrim(code))),
    CONSTRAINT ck_rate_card_code_shape CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_rate_card_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_rate_card_scope CHECK (scope IN ('CARRIER', 'ORIGIN', 'ROUTE')),
    CONSTRAINT ck_rate_card_scope_target CHECK (
        (scope = 'CARRIER' AND origin_id IS NULL     AND route_id IS NULL)
     OR (scope = 'ORIGIN'  AND origin_id IS NOT NULL AND route_id IS NULL)
     OR (scope = 'ROUTE'   AND origin_id IS NULL     AND route_id IS NOT NULL)),
    CONSTRAINT ck_rate_card_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_rate_card_validity_ordered CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_rate_card_has_a_component CHECK (
        base_amount IS NOT NULL OR amount_per_km IS NOT NULL OR amount_per_kg IS NOT NULL
     OR amount_per_m3 IS NOT NULL OR amount_per_pallet IS NOT NULL),
    -- Zero is legal in every component and negative is not. Zero is a real agreement ("no charge
    -- per pallet on this corridor"); a negative rate is a discount model this product does not
    -- have, and letting one in would produce an estimate below zero with no way to explain it.
    CONSTRAINT ck_rate_card_amounts_nonnegative CHECK (
        (base_amount       IS NULL OR base_amount       >= 0)
    AND (amount_per_km     IS NULL OR amount_per_km     >= 0)
    AND (amount_per_kg     IS NULL OR amount_per_kg     >= 0)
    AND (amount_per_m3     IS NULL OR amount_per_m3     >= 0)
    AND (amount_per_pallet IS NULL OR amount_per_pallet >= 0)
    AND (minimum_amount    IS NULL OR minimum_amount    >= 0)),
    CONSTRAINT fk_rate_card_carrier FOREIGN KEY (carrier_id)
        REFERENCES tms.carrier (id) ON DELETE RESTRICT,
    -- Rule 6, the tenant guarantee, once per referenced master: a card's carrier, origin, route
    -- and vehicle type all belong to the card's own company. MATCH SIMPLE (the default) is what
    -- makes the three optional ones satisfied-when-NULL, the same idiom as fk_vehicle_carrier_company
    -- (V9).
    CONSTRAINT fk_rate_card_carrier_company FOREIGN KEY (carrier_id, company_id)
        REFERENCES tms.carrier (id, company_id),
    CONSTRAINT fk_rate_card_origin FOREIGN KEY (origin_id)
        REFERENCES tms.location (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rate_card_origin_company FOREIGN KEY (origin_id, company_id)
        REFERENCES tms.location (id, company_id),
    CONSTRAINT fk_rate_card_route FOREIGN KEY (route_id)
        REFERENCES tms.route (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rate_card_route_company FOREIGN KEY (route_id, company_id)
        REFERENCES tms.route (id, company_id),
    CONSTRAINT fk_rate_card_vehicle_type FOREIGN KEY (vehicle_type_id)
        REFERENCES tms.vehicle_type (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rate_card_vehicle_type_company FOREIGN KEY (vehicle_type_id, company_id)
        REFERENCES tms.vehicle_type (id, company_id),
    CONSTRAINT fk_rate_card_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rate_card_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

-- The selection access path: RateCardSelector asks for "every active card of this company and
-- carrier whose validity covers this date", then ranks what comes back in Java. Carrier first
-- because it is always known and always equality-tested.
CREATE INDEX ix_rate_card_company_carrier_validity
    ON tms.rate_card (company_id, carrier_id, valid_from DESC)
    WHERE active;

CREATE INDEX ix_rate_card_origin ON tms.rate_card (origin_id) WHERE origin_id IS NOT NULL;
CREATE INDEX ix_rate_card_route ON tms.rate_card (route_id) WHERE route_id IS NOT NULL;
CREATE INDEX ix_rate_card_vehicle_type ON tms.rate_card (vehicle_type_id) WHERE vehicle_type_id IS NOT NULL;

-- Two active cards with the same scope, the same vehicle type and the same start date are the
-- same agreement entered twice, and the index refuses it. It is a *backstop*, not the rule:
-- RateCardService.requireNoOverlap rejects any overlapping validity with a message naming the
-- card it collides with, which is what an operator actually needs. This index only catches the
-- narrow race two concurrent inserts could win against that check.
--
-- The nil UUID standing in for a NULL scope target and a NULL vehicle type is not decoration:
-- NULLs are distinct in a unique index, so without it the two rows this constraint exists to
-- refuse - both CARRIER-scoped, both for any vehicle type - would both be admitted.
--
-- A full non-overlap constraint (EXCLUDE USING gist with a daterange) is the right tool for the
-- general case and is deliberately not used: it needs the btree_gist extension, which this
-- installation's baseline (V1) does not create, and adding a database extension to enforce a rule
-- the service already enforces is a bigger change than the rule deserves. What makes that safe is
-- that an overlap slipping through cannot make costing ambiguous: RateCardSelector's ranking is
-- total (specificity, then latest valid_from, then code), so it still returns exactly one card.
CREATE UNIQUE INDEX uq_rate_card_active_agreement
    ON tms.rate_card (
        company_id,
        carrier_id,
        scope,
        coalesce(origin_id, route_id, '00000000-0000-0000-0000-000000000000'::uuid),
        coalesce(vehicle_type_id, '00000000-0000-0000-0000-000000000000'::uuid),
        valid_from)
    WHERE active;

COMMENT ON TABLE tms.rate_card IS
    'What one carrier charges this company for a shipment, between two dates: a base amount and '
    'up to four unit components, optionally narrowed to one origin or route and one vehicle type. '
    'Deactivated, never deleted - an estimate snapshotted from this card must keep resolving it.';
COMMENT ON COLUMN tms.rate_card.scope IS
    'CARRIER (anything this carrier runs), ORIGIN (anything leaving this depot) or ROUTE (this '
    'master corridor). Zone is absent by design - see this migration''s header.';
COMMENT ON COLUMN tms.rate_card.vehicle_type_id IS
    'NULL means "any vehicle type". A card naming a type beats a card that does not at the same '
    'scope, which is how a company states "0.85/km, but 1.20/km if it goes on the articulated".';
COMMENT ON COLUMN tms.rate_card.minimum_amount IS
    'The floor a shipment is charged whatever the components add up to. Applied last, and recorded '
    'as its own MINIMUM_ADJUSTMENT line in tms.trip_cost_component so an operator can see that it '
    'was the minimum and not the components that set the price.';

CREATE TRIGGER tr_rate_card_set_updated_at
    BEFORE UPDATE ON tms.rate_card
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.rate_card ENABLE ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE, DELETE ON tms.rate_card TO tms_app;

-- The tenant policy every business table added after V13 carries (ADR-005).
CREATE POLICY p_tenant_company_scope ON tms.rate_card
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 2. tms.trip_cost
-- ---------------------------------------------------------------------------
-- One row per trip, holding two independent facts that must never overwrite each other:
--
--   the ESTIMATE - what the agreed tariff says this shipment should cost, frozen at the moment it
--   was calculated, together with which card produced it;
--   the ACTUAL   - what it turned out to cost, typed in later against an invoice.
--
-- Separate columns and not a status field flipping one into the other, because the number an
-- operations manager needs is the *difference*, and a model that let the actual overwrite the
-- estimate would destroy the only figure worth reporting on.
CREATE TABLE tms.trip_cost (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id         uuid        NOT NULL,
    trip_id            uuid        NOT NULL,
    -- One currency for both halves. An actual recorded in another currency is not a currency
    -- conversion problem TMS solves in V1, it is a data-entry mistake, and TripCostService refuses
    -- it by name rather than storing two currencies that no code would ever reconcile.
    currency           text        NOT NULL,

    -- The estimate. All four move together (ck_trip_cost_estimate_complete): a row either carries
    -- a full estimate or none at all.
    estimated_amount   numeric(14,2),
    estimated_at       timestamptz,
    estimated_by       uuid,
    -- Which card won, kept as a live reference AND as a snapshot of what it said at the time.
    -- The reference is what a screen links to; the snapshot is what makes the estimate history
    -- rather than a query: editing CARD-2026-NORTE tomorrow, or deactivating it, must not silently
    -- restate what a shipment was estimated at last week. This is the same argument
    -- tms.trip.snapshot_max_* makes for capacity (V11) - a figure a decision was made against is
    -- frozen at the moment of the decision.
    rate_card_id       uuid,
    rate_card_code     text,
    rate_card_name     text,
    rate_card_scope    text,

    -- The actual. Likewise all-or-nothing (ck_trip_cost_actual_complete).
    actual_amount      numeric(14,2),
    -- The carrier's invoice/settlement number, free text: TMS is not an accounts-payable system
    -- and must not pretend to validate somebody else's document numbering.
    actual_reference   text,
    actual_notes       text,
    actual_recorded_at timestamptz,
    actual_recorded_by uuid,

    -- Closing is what makes a cost stop being editable. Not a lifecycle of its own: one moment,
    -- set by a person who has reconciled the invoice, after which every write is refused
    -- (TripCostService) - including a re-estimate, because re-running a tariff over a settled
    -- shipment would restate a figure somebody has already paid against.
    --
    -- Reversible, deliberately: TripCostService.reopen clears these two columns and is audited as
    -- COST_REOPENED. Without it a mis-clicked Close would freeze a wrong number permanently and
    -- the only remedy would be hand-editing this table, which is precisely the repair this
    -- product must never require. Reopening restores nothing and changes no figure; it only makes
    -- the row writable again, so the correction that follows is itself recorded.
    closed_at          timestamptz,
    closed_by          uuid,

    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    created_by         uuid,
    updated_by         uuid,
    CONSTRAINT pk_trip_cost PRIMARY KEY (id),
    CONSTRAINT fk_trip_cost_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- One cost row per trip, full stop. The estimate is re-calculable in place and the actual is
    -- edited in place; a second row would mean a shipment with two prices and no rule for which
    -- one is true.
    CONSTRAINT uq_trip_cost_trip UNIQUE (trip_id),
    -- Target for tms.trip_cost_component's composite FK below.
    CONSTRAINT uq_trip_cost_id_company UNIQUE (id, company_id),
    CONSTRAINT fk_trip_cost_trip FOREIGN KEY (trip_id)
        REFERENCES tms.trip (id) ON DELETE CASCADE,
    -- Rule 6: the trip this cost belongs to is a trip of this company.
    CONSTRAINT fk_trip_cost_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),
    -- ON DELETE RESTRICT and not SET NULL: a card that has priced a shipment cannot be deleted,
    -- which is the same protection deactivation gives at the application level, enforced where
    -- neither a script nor a support session can talk its way past it.
    CONSTRAINT fk_trip_cost_rate_card FOREIGN KEY (rate_card_id)
        REFERENCES tms.rate_card (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_cost_rate_card_company FOREIGN KEY (rate_card_id, company_id)
        REFERENCES tms.rate_card (id, company_id),
    CONSTRAINT fk_trip_cost_estimated_by FOREIGN KEY (estimated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_cost_actual_recorded_by FOREIGN KEY (actual_recorded_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_cost_closed_by FOREIGN KEY (closed_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_cost_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_cost_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_trip_cost_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_trip_cost_amounts_nonnegative CHECK (
        (estimated_amount IS NULL OR estimated_amount >= 0)
    AND (actual_amount    IS NULL OR actual_amount    >= 0)),
    -- "An estimate" means all of: an amount, when it was calculated, who ran it, and which card
    -- it came from. A row carrying an amount with no card behind it would be a number nobody can
    -- explain, which is exactly what this feature exists to stop being possible.
    CONSTRAINT ck_trip_cost_estimate_complete CHECK (
        (estimated_amount IS NULL AND estimated_at IS NULL AND rate_card_id IS NULL
            AND rate_card_code IS NULL AND rate_card_scope IS NULL)
     OR (estimated_amount IS NOT NULL AND estimated_at IS NOT NULL AND rate_card_id IS NOT NULL
            AND rate_card_code IS NOT NULL AND rate_card_scope IS NOT NULL)),
    CONSTRAINT ck_trip_cost_actual_complete CHECK (
        (actual_amount IS NULL AND actual_recorded_at IS NULL)
     OR (actual_amount IS NOT NULL AND actual_recorded_at IS NOT NULL)),
    -- No empty rows. A trip with neither an estimate nor an actual simply has no cost row.
    CONSTRAINT ck_trip_cost_not_empty CHECK (estimated_amount IS NOT NULL OR actual_amount IS NOT NULL),
    -- Closing settles an actual figure. Closing a row that only carries an estimate would freeze
    -- the guess and lock out the fact.
    CONSTRAINT ck_trip_cost_closed_has_actual CHECK (closed_at IS NULL OR actual_amount IS NOT NULL),
    CONSTRAINT ck_trip_cost_closed_complete CHECK (
        (closed_at IS NULL AND closed_by IS NULL) OR (closed_at IS NOT NULL AND closed_by IS NOT NULL))
);

CREATE INDEX ix_trip_cost_company_rate_card
    ON tms.trip_cost (company_id, rate_card_id)
    WHERE rate_card_id IS NOT NULL;

-- "What is still not settled" - the question a cost review screen opens with, and the only one
-- that needs an index of its own here (the per-trip lookup rides uq_trip_cost_trip).
CREATE INDEX ix_trip_cost_company_open
    ON tms.trip_cost (company_id, updated_at DESC)
    WHERE closed_at IS NULL;

COMMENT ON TABLE tms.trip_cost IS
    'The estimate and the actual for one trip, side by side. Neither ever overwrites the other: '
    'the difference between them is the figure this table exists to make reportable.';
COMMENT ON COLUMN tms.trip_cost.rate_card_code IS
    'The card''s code as it read when the estimate was calculated. Snapshotted beside the live '
    'rate_card_id for the same reason tms.trip.snapshot_max_weight_kg is (V11): a figure a '
    'decision was made against must not be rewritten by a later edit to the master.';
COMMENT ON COLUMN tms.trip_cost.closed_at IS
    'When the cost was settled and frozen. After this, every write is refused - including a '
    're-estimate, which would restate a figure somebody has already paid against. Reversible '
    'through TripCostService.reopen, which is audited as COST_REOPENED.';

CREATE TRIGGER tr_trip_cost_set_updated_at
    BEFORE UPDATE ON tms.trip_cost
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.trip_cost ENABLE ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE, DELETE ON tms.trip_cost TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.trip_cost
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 3. tms.trip_cost_component
-- ---------------------------------------------------------------------------
-- The estimate's line items. A table and not five more columns on tms.trip_cost, for one reason
-- that is worth the join: a component can be *not calculable*, and that has to be recordable.
--
-- A card that charges 0.85 per km, applied to a shipment whose distance nobody knows, must not
-- quietly contribute zero - that would publish an estimate that looks complete and is short by the
-- entire line haul. So the line is written with status NOT_CALCULABLE and a reason, the estimate
-- says so, and the screen shows it. That is the "no inventar la distancia" rule made structural
-- instead of aspirational.
CREATE TABLE tms.trip_cost_component (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id      uuid        NOT NULL,
    trip_cost_id    uuid        NOT NULL,
    component       text        NOT NULL,
    status          text        NOT NULL,
    -- What the card said (rate) and what the shipment supplied (quantity). Both NULL for BASE and
    -- MINIMUM_ADJUSTMENT, which are amounts rather than products, and both NULL for a component
    -- that could not be calculated at all.
    rate            numeric(14,4),
    quantity        numeric(14,3),
    unit            text,
    -- Where the quantity came from, recorded rather than assumed. An operator disputing an
    -- estimate asks "where did 39.5 km come from" and the answer is on the line, not in a
    -- developer's head.
    quantity_source text,
    -- rate x quantity, rounded to the currency's 2 decimals, or the flat amount for BASE. Always
    -- 0.00 for a NOT_CALCULABLE line: it contributes nothing, and NULL would make every sum in
    -- every report have to remember that.
    amount          numeric(14,2) NOT NULL,
    -- Why a line could not be calculated. NULL exactly when status is APPLIED.
    reason          text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_trip_cost_component PRIMARY KEY (id),
    CONSTRAINT fk_trip_cost_component_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- CASCADE: these lines have no meaning apart from the estimate they explain, and a
    -- re-estimate deletes and rewrites them as one unit.
    CONSTRAINT fk_trip_cost_component_cost FOREIGN KEY (trip_cost_id)
        REFERENCES tms.trip_cost (id) ON DELETE CASCADE,
    CONSTRAINT fk_trip_cost_component_cost_company FOREIGN KEY (trip_cost_id, company_id)
        REFERENCES tms.trip_cost (id, company_id),
    -- One line per component per estimate. A second BASE line would be a bug that reads as a
    -- surcharge.
    CONSTRAINT uq_trip_cost_component_cost_component UNIQUE (trip_cost_id, component),
    CONSTRAINT ck_trip_cost_component_component CHECK (component IN (
        'BASE', 'DISTANCE', 'WEIGHT', 'VOLUME', 'PALLETS', 'MINIMUM_ADJUSTMENT')),
    CONSTRAINT ck_trip_cost_component_status CHECK (status IN ('APPLIED', 'NOT_CALCULABLE')),
    CONSTRAINT ck_trip_cost_component_unit CHECK (unit IS NULL OR unit IN ('KM', 'KG', 'M3', 'PALLET')),
    CONSTRAINT ck_trip_cost_component_quantity_source CHECK (
        quantity_source IS NULL OR quantity_source IN ('ROUTE_REFERENCE', 'ORDER_DECLARED_TOTALS')),
    CONSTRAINT ck_trip_cost_component_reason CHECK (
        reason IS NULL OR reason IN (
            'DISTANCE_UNKNOWN', 'WEIGHT_UNKNOWN', 'VOLUME_UNKNOWN', 'PALLETS_UNKNOWN')),
    CONSTRAINT ck_trip_cost_component_amount_nonnegative CHECK (amount >= 0),
    -- The two halves of the status, stated as one rule so neither can drift: an applied line
    -- carries no reason, a non-calculable line carries one and contributes nothing.
    CONSTRAINT ck_trip_cost_component_status_consistent CHECK (
        (status = 'APPLIED' AND reason IS NULL)
     OR (status = 'NOT_CALCULABLE' AND reason IS NOT NULL AND amount = 0
            AND rate IS NULL AND quantity IS NULL)),
    -- A unit component is a product: it shows what it multiplied. BASE and MINIMUM_ADJUSTMENT are
    -- flat and show neither, which is what stops a screen rendering "1 x 120.00" as though the
    -- base rate were a unit rate.
    CONSTRAINT ck_trip_cost_component_shape CHECK (
        (component IN ('BASE', 'MINIMUM_ADJUSTMENT')
            AND rate IS NULL AND quantity IS NULL AND unit IS NULL AND quantity_source IS NULL)
     OR (component IN ('DISTANCE', 'WEIGHT', 'VOLUME', 'PALLETS')
            AND (status = 'NOT_CALCULABLE'
                 OR (rate IS NOT NULL AND quantity IS NOT NULL AND unit IS NOT NULL
                     AND quantity_source IS NOT NULL))))
);

CREATE INDEX ix_trip_cost_component_cost ON tms.trip_cost_component (trip_cost_id);

COMMENT ON TABLE tms.trip_cost_component IS
    'How one estimate was arrived at, line by line - including the lines that could NOT be '
    'calculated and why, which is the reason this is a table and not five columns.';
COMMENT ON COLUMN tms.trip_cost_component.status IS
    'APPLIED, or NOT_CALCULABLE when the card charges for something the shipment cannot supply - '
    'a per-km rate with no known distance. A non-calculable line contributes 0.00 and is shown, '
    'never silently dropped.';
COMMENT ON COLUMN tms.trip_cost_component.quantity_source IS
    'ROUTE_REFERENCE (the master route''s planner-entered reference distance - the only distance '
    'TMS has, and never a road distance it measured) or ORDER_DECLARED_TOTALS (the sum of the '
    'declared totals of the orders on the trip).';

-- No updated_at trigger and no updated_at column: a component line is written once and deleted
-- once. A re-estimate replaces the whole set rather than editing lines, so "when was this line
-- last changed" has no answer other than its estimate's.

ALTER TABLE tms.trip_cost_component ENABLE ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE, DELETE ON tms.trip_cost_component TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.trip_cost_component
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 4. Audit vocabulary
-- ---------------------------------------------------------------------------
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_aggregate_type;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_aggregate_type CHECK (aggregate_type IN (
    'LOCATION', 'CARRIER', 'VEHICLE', 'DRIVER', 'TRANSPORT_ORDER', 'TRIP', 'PLANNING_RUN',
    'INTEGRATION_CLIENT', 'MASTER_DATA_IMPORT_BATCH', 'ORDER_IMPORT_BATCH', 'SHIPMENT',
    'RATE_CARD', 'TRIP_COST'));

-- Three actions rather than UPDATE, because each answers a question somebody asks by itself:
-- "when was this priced and against which card", "who typed in what the carrier invoiced", and
-- "who signed this off". A generic UPDATE row would bury all three.
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
    'CREATE', 'UPDATE', 'ACTIVATE', 'DEACTIVATE', 'ASSIGN_ORDER', 'REMOVE_ORDER', 'MOVE_ORDER',
    'VEHICLE_CHANGE', 'DRIVER_CHANGE', 'CONFIRM', 'CANCEL', 'CREDENTIAL_CREATE',
    'CREDENTIAL_ROTATE', 'CREDENTIAL_REVOKE', 'AUTO_PLAN', 'IMPORT_EXECUTED', 'SHIPMENT_CONFIRMED',
    'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED', 'SHIPMENT_CANCELLED',
    'DELIVERY_RESULT_RECORDED', 'COST_ESTIMATED', 'COST_ACTUAL_RECORDED', 'COST_CLOSED',
    'COST_REOPENED'));

-- ---------------------------------------------------------------------------
-- 5. rates.rate_card:* and rates.trip_cost:*
-- ---------------------------------------------------------------------------
-- Four permissions and not two, because reading a tariff and reading what one shipment cost are
-- different disclosures: a dispatcher may legitimately need the second without ever being shown
-- the commercial agreement behind it.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('rates.rate_card', 'read',   'View rate cards and their validity'),
    ('rates.rate_card', 'manage', 'Create, update and deactivate rate cards'),
    ('rates.trip_cost', 'read',   'View the estimated and actual cost of a trip'),
    ('rates.trip_cost', 'manage', 'Estimate a trip cost, record the actual and close it');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code IN ('rates.rate_card:read', 'rates.rate_card:manage',
                 'rates.trip_cost:read', 'rates.trip_cost:manage');

-- A PLANNER prices the shipments they build and reconciles them afterwards, and reads the cards to
-- understand why a number came out the way it did - but does not negotiate the agreement, which is
-- the same read/administer split V3 gives them over the fleet catalogue.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code = 'PLANNER'
  AND p.code IN ('rates.rate_card:read', 'rates.trip_cost:read', 'rates.trip_cost:manage');

-- VIEWER gets neither, and that is the one grant decision here worth defending. Every other
-- catalogue in this schema is readable by a viewer because operational data is what the role
-- exists to watch. A tariff is not operational data: it is what one company negotiated with
-- another, and the default for commercially sensitive figures is that somebody has to be given
-- them on purpose. An installation that disagrees grants the permission; one that never thought
-- about it does not leak its rates to every read-only account.

-- ---------------------------------------------------------------------------
-- Deliberately NOT here
-- ---------------------------------------------------------------------------
--   * No accessorials/surcharge catalogue (waiting time, second driver, night delivery, tolls,
--     fuel index). Each needs an input TMS does not record yet - measured wait per stop, a
--     published index feed, a toll table - and a surcharge whose driver nobody measures is a
--     column that gets filled in by hand, which is a spreadsheet with extra steps. The one floor
--     that needs no new input, minimum_amount, is here.
--   * No break/scale tables (0-500 kg at one rate, 501-1000 at another). A real one is a child
--     table plus a bracket-selection rule plus a UI to maintain it; V1's answer is a second card
--     narrowed by vehicle type, which covers the case companies actually state first.
--   * No cost per order. Allocating a trip's cost back to the orders on it needs an allocation
--     basis (by weight? by pallet? by drop?) that is a commercial decision per company, and
--     picking one here would bake somebody's accounting policy into the schema. tms.trip_cost is
--     per trip because that is the unit the carrier charges for.
--   * No customer-facing price. This is what TMS pays, not what it bills. A sell-side rate is a
--     different agreement with a different counterparty and, when it arrives, a different table -
--     not a `direction` column on this one.
--   * No FX. See tms.trip_cost.currency.
--   * No road distance. TMS has one distance and it is tms.route.reference_distance_km, typed in
--     by a planner (V8). Everything here that uses it says so on the line
--     (quantity_source = 'ROUTE_REFERENCE'), and a shipment with no route gets a NOT_CALCULABLE
--     distance line rather than a number this product would be inventing.
