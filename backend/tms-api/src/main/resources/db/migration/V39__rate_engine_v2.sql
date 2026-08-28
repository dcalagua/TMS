-- ===========================================================================
-- V39 - Rate engine V2: the charges a real freight agreement actually contains
-- ===========================================================================
--
-- V30 gave a rate card six components: a base, four per-unit rates and a minimum. That is enough
-- to price a simple haulage agreement and it is not enough to price most of them. A real contract
-- also says what happens when the truck makes five drops instead of one, what happens when diesel
-- moves, what happens when the driver waits three hours at a gate, and what the ceiling is.
--
-- Every one of those is money somebody argues about, and a TMS that cannot express them makes its
-- users keep the real agreement in a spreadsheet beside it - which is the failure mode this whole
-- module exists to prevent.
--
-- Six components are added:
--
--     STOP_OFF            per additional stop after the first
--     FUEL_SURCHARGE      a percentage of the linehaul
--     WAITING_TIME        per hour of detention
--     TOLL                a flat pass-through
--     OTHER_ACCESSORIAL   a flat named extra
--     MAXIMUM_ADJUSTMENT  the ceiling, mirroring V30's minimum
--
-- ---------------------------------------------------------------------------
-- 1. Why the order of application is part of the schema's meaning
-- ---------------------------------------------------------------------------
--
-- These do not commute. The calculator applies them in exactly this order and
-- docs/domain/RATE_ENGINE_V2.md states it as a contract:
--
--     BASE + DISTANCE + WEIGHT + VOLUME + PALLETS + STOP_OFF     -> the linehaul
--     + FUEL_SURCHARGE (a percentage OF THE LINEHAUL ONLY)
--     + WAITING_TIME + TOLL + OTHER_ACCESSORIAL                  -> the accessorials
--     then MINIMUM_ADJUSTMENT or MAXIMUM_ADJUSTMENT on the total
--
-- Fuel on the linehaul and not on the accessorials is the industry norm and it is a real decision
-- rather than an implementation detail: a fuel percentage applied to a toll is a fuel surcharge on
-- a road authority's fee, which no carrier bills and no shipper would accept. Applying the minimum
-- before the accessorials would be a different agreement again.
--
-- ---------------------------------------------------------------------------
-- 2. The new rate columns
-- ---------------------------------------------------------------------------
ALTER TABLE tms.rate_card
    -- Per stop after the first: a one-drop trip pays none. See the calculator for why the first
    -- stop is free - it is already inside the base and charging it twice is double-billing.
    ADD COLUMN amount_per_stop         numeric(14,4),
    -- A percentage, not an amount: fuel moves and the agreement says "X% of linehaul", so storing
    -- a computed amount would freeze today's diesel price into the contract.
    ADD COLUMN fuel_surcharge_percent  numeric(7,4),
    ADD COLUMN amount_per_waiting_hour numeric(14,4),
    ADD COLUMN toll_amount             numeric(14,2),
    ADD COLUMN accessorial_amount      numeric(14,2),
    -- The label the accessorial appears under on a breakdown. Without it "OTHER_ACCESSORIAL 45.00"
    -- is a line nobody can approve.
    ADD COLUMN accessorial_label       text,
    ADD COLUMN maximum_amount          numeric(14,2);

ALTER TABLE tms.rate_card
    ADD CONSTRAINT ck_rate_card_v2_amounts_nonnegative CHECK (
        (amount_per_stop IS NULL OR amount_per_stop >= 0)
        AND (fuel_surcharge_percent IS NULL OR fuel_surcharge_percent >= 0)
        AND (amount_per_waiting_hour IS NULL OR amount_per_waiting_hour >= 0)
        AND (toll_amount IS NULL OR toll_amount >= 0)
        AND (accessorial_amount IS NULL OR accessorial_amount >= 0)
        AND (maximum_amount IS NULL OR maximum_amount >= 0)),
    -- A surcharge of 900% is a typo, not a contract. The ceiling is deliberately generous: fuel
    -- surcharges above 50% have existed, and refusing a real one to catch a typo would be worse.
    ADD CONSTRAINT ck_rate_card_fuel_surcharge_sane CHECK (
        fuel_surcharge_percent IS NULL OR fuel_surcharge_percent <= 100),
    -- A ceiling below the floor prices nothing, and which one wins would be arbitrary.
    ADD CONSTRAINT ck_rate_card_maximum_above_minimum CHECK (
        maximum_amount IS NULL OR minimum_amount IS NULL OR maximum_amount >= minimum_amount),
    -- The label and the amount travel together: a labelled nothing and an unlabelled charge are
    -- both rows nobody can act on.
    ADD CONSTRAINT ck_rate_card_accessorial_pair CHECK (
        (accessorial_amount IS NULL) = (accessorial_label IS NULL)),
    ADD CONSTRAINT ck_rate_card_accessorial_label_not_blank CHECK (
        accessorial_label IS NULL OR btrim(accessorial_label) <> '');

COMMENT ON COLUMN tms.rate_card.fuel_surcharge_percent IS
    'A percentage of the linehaul (V39), never of the accessorials - a fuel surcharge on a toll is '
    'not something any carrier bills. Stored as a percentage rather than an amount because the '
    'agreement is written that way and diesel moves.';

COMMENT ON COLUMN tms.rate_card.amount_per_stop IS
    'Charged per stop AFTER THE FIRST (V39). The first drop is already inside the base; charging '
    'it again is double-billing, and a one-drop trip must pay no stop-off at all.';

COMMENT ON COLUMN tms.rate_card.maximum_amount IS
    'The ceiling, mirroring minimum_amount (V30). Applied last, after every other component, and '
    'never below the minimum - see ck_rate_card_maximum_above_minimum.';

-- V30's "a card must charge something" rule has to learn the new components, or a card carrying
-- only a toll would be refused as empty. Replaced rather than added to, so there is one rule.
--
-- minimum_amount is deliberately still NOT in the list, and neither is maximum_amount. V30 chose
-- that and it was right: a floor is a rule about other charges, not a charge. A card that says
-- only "never less than 200" states no price at all - it states a constraint on a price that does
-- not exist - and pricing a shipment from it would produce 200 out of nothing.
ALTER TABLE tms.rate_card DROP CONSTRAINT ck_rate_card_has_a_component;
ALTER TABLE tms.rate_card ADD CONSTRAINT ck_rate_card_has_a_component CHECK (
    base_amount IS NOT NULL
    OR amount_per_km IS NOT NULL
    OR amount_per_kg IS NOT NULL
    OR amount_per_m3 IS NOT NULL
    OR amount_per_pallet IS NOT NULL
    OR amount_per_stop IS NOT NULL
    OR fuel_surcharge_percent IS NOT NULL
    OR amount_per_waiting_hour IS NOT NULL
    OR toll_amount IS NOT NULL
    OR accessorial_amount IS NOT NULL);

-- ---------------------------------------------------------------------------
-- 3. Lane pricing
-- ---------------------------------------------------------------------------
--
-- The single most common thing a freight agreement is priced on, and V30 could not say it: a rate
-- for Lima -> Arequipa, whatever route or vehicle runs it. ORIGIN alone is "everything out of this
-- depot" and ROUTE is "this specific master corridor"; neither is a lane.
--
-- One column serves it. A LANE card names both an origin and a destination; the existing
-- origin_id carries the first.
ALTER TABLE tms.rate_card ADD COLUMN destination_id uuid;

ALTER TABLE tms.rate_card
    ADD CONSTRAINT fk_rate_card_destination FOREIGN KEY (destination_id)
        REFERENCES tms.location (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_rate_card_destination_company FOREIGN KEY (destination_id, company_id)
        REFERENCES tms.location (id, company_id);

ALTER TABLE tms.rate_card DROP CONSTRAINT ck_rate_card_scope;
ALTER TABLE tms.rate_card ADD CONSTRAINT ck_rate_card_scope
    CHECK (scope IN ('CARRIER', 'ORIGIN', 'LANE', 'ROUTE'));

-- V30 split the scope target into typed columns rather than one polymorphic id, and this extends
-- that: each scope names exactly the columns it needs and nulls the rest, so a card cannot claim
-- to be a lane rate while carrying only an origin.
ALTER TABLE tms.rate_card DROP CONSTRAINT ck_rate_card_scope_target;
ALTER TABLE tms.rate_card ADD CONSTRAINT ck_rate_card_scope_target CHECK (
    (scope = 'CARRIER' AND origin_id IS NULL AND destination_id IS NULL AND route_id IS NULL)
 OR (scope = 'ORIGIN'  AND origin_id IS NOT NULL AND destination_id IS NULL AND route_id IS NULL)
 OR (scope = 'LANE'    AND origin_id IS NOT NULL AND destination_id IS NOT NULL AND route_id IS NULL)
 OR (scope = 'ROUTE'   AND route_id IS NOT NULL AND origin_id IS NULL AND destination_id IS NULL));

CREATE INDEX ix_rate_card_destination ON tms.rate_card (destination_id) WHERE destination_id IS NOT NULL;

COMMENT ON COLUMN tms.rate_card.destination_id IS
    'The lane''s far end (V39). Set only for scope = LANE, where origin_id carries the near end. '
    'A lane is what most freight agreements are actually priced on, and neither ORIGIN (everything '
    'out of a depot) nor ROUTE (one master corridor) can express it.';

-- ---------------------------------------------------------------------------
-- 4. The breakdown learns the new lines
-- ---------------------------------------------------------------------------
ALTER TABLE tms.trip_cost_component DROP CONSTRAINT ck_trip_cost_component_component;
ALTER TABLE tms.trip_cost_component ADD CONSTRAINT ck_trip_cost_component_component CHECK (
    component IN ('BASE', 'DISTANCE', 'WEIGHT', 'VOLUME', 'PALLETS', 'STOP_OFF', 'FUEL_SURCHARGE',
                  'WAITING_TIME', 'TOLL', 'OTHER_ACCESSORIAL',
                  'MINIMUM_ADJUSTMENT', 'MAXIMUM_ADJUSTMENT'));

ALTER TABLE tms.trip_cost_component DROP CONSTRAINT ck_trip_cost_component_unit;
ALTER TABLE tms.trip_cost_component ADD CONSTRAINT ck_trip_cost_component_unit CHECK (
    unit IS NULL OR unit IN ('KM', 'KG', 'M3', 'PALLET', 'STOP', 'HOUR', 'PERCENT'));

ALTER TABLE tms.trip_cost_component DROP CONSTRAINT ck_trip_cost_component_quantity_source;
ALTER TABLE tms.trip_cost_component ADD CONSTRAINT ck_trip_cost_component_quantity_source CHECK (
    quantity_source IS NULL OR quantity_source IN (
        'ROUTE_REFERENCE', 'ORDER_DECLARED_TOTALS', 'MEASURED_ROUTE', 'TRIP_STOPS', 'LINEHAUL_SUBTOTAL',
        'RECORDED_WAITING'));

ALTER TABLE tms.trip_cost_component DROP CONSTRAINT ck_trip_cost_component_reason;
ALTER TABLE tms.trip_cost_component ADD CONSTRAINT ck_trip_cost_component_reason CHECK (
    reason IS NULL OR reason IN (
        'DISTANCE_UNKNOWN', 'WEIGHT_UNKNOWN', 'VOLUME_UNKNOWN', 'PALLETS_UNKNOWN',
        'STOPS_UNKNOWN', 'WAITING_NOT_RECORDED'));

-- The one line on a breakdown that may be negative, and it has to be.
--
-- V30's rule was "amount >= 0", which was right when the only adjustment was a floor. A ceiling
-- adjusts the total *down*, and a ceiling rendered as a positive number would read as one more
-- charge on the very breakdown a controller is checking. So MAXIMUM_ADJUSTMENT is carved out by
-- name rather than the rule being dropped: every other component stays non-negative, because for
-- every other component a negative amount is a bug.
ALTER TABLE tms.trip_cost_component DROP CONSTRAINT ck_trip_cost_component_amount_nonnegative;
ALTER TABLE tms.trip_cost_component ADD CONSTRAINT ck_trip_cost_component_amount_sign CHECK (
    (component = 'MAXIMUM_ADJUSTMENT' AND amount <= 0)
 OR (component <> 'MAXIMUM_ADJUSTMENT' AND amount >= 0));

-- The shape rule, restated for twelve components instead of six. Flat lines show no product;
-- measured lines show what they multiplied. Unchanged in spirit and in wording.
ALTER TABLE tms.trip_cost_component DROP CONSTRAINT ck_trip_cost_component_shape;
ALTER TABLE tms.trip_cost_component ADD CONSTRAINT ck_trip_cost_component_shape CHECK (
    (component IN ('BASE', 'TOLL', 'OTHER_ACCESSORIAL', 'MINIMUM_ADJUSTMENT', 'MAXIMUM_ADJUSTMENT')
        AND rate IS NULL AND quantity IS NULL AND unit IS NULL AND quantity_source IS NULL)
 OR (component IN ('DISTANCE', 'WEIGHT', 'VOLUME', 'PALLETS', 'STOP_OFF', 'FUEL_SURCHARGE',
                   'WAITING_TIME')
        AND (status = 'NOT_CALCULABLE'
             OR (rate IS NOT NULL AND quantity IS NOT NULL AND unit IS NOT NULL
                 AND quantity_source IS NOT NULL))));

-- ---------------------------------------------------------------------------
-- 5. No back-fill, and nothing existing changes
-- ---------------------------------------------------------------------------
--
-- Every new rate column is nullable and every existing card keeps exactly the components it had,
-- so every price this system has ever quoted still computes to the same number. A card that says
-- nothing about fuel is not a card with a zero fuel surcharge: the component simply does not
-- appear on its breakdown, which is the difference between "no surcharge applies" and "the
-- surcharge is nothing" - and only the first is true.
--
-- ---------------------------------------------------------------------------
-- Deliberately NOT here
-- ---------------------------------------------------------------------------
--
--   * No ZONE scope. Pricing by destination zone needs the zone of a destination resolved at
--     rating time, which is a masterdata lookup this module does not have and should not grow
--     casually. LANE covers the case most agreements actually state.
--   * No break-weight or tiered rate tables. A rate that changes at 5,000 kg is a second table,
--     not a column, and nothing has asked for one.
--   * No currency conversion. V30's rule stands: a card states its currency and the product does
--     not invent an FX rate.
--   * No customer-facing sell rates. Everything here is what a carrier charges. Quoting a customer
--     is a different agreement with different scopes, and merging the two is how a TMS ends up
--     unable to say what its own margin is.
