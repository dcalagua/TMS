-- ============================================================================
-- V63 - Rates by loading metre and effective-geometry volume (ADR-022 section 8, phase 45)
-- ============================================================================
-- A carrier may charge per loading metre: the length of deck the load takes, the way every
-- European and most Latin American groupage tariffs are written. TMS knows that length since V62:
-- the trip's ledger sums the floor area its stacks cover, and the trip's snapshot froze the
-- vehicle's load width at confirmation. Loading metres = floor area / (width x floor fill factor)
-- (shared.geometry.LoadGeometryCalculator.loadingMetres), computed at estimation time, never stored
-- as a figure of its own.
--
-- The per-m3 component keeps reading SUM(assigned_volume_m3), which since V62 is the planning
-- volume (effective where a line was measured). The breakdown now says which it was: the line's
-- quantity_source is EFFECTIVE_GEOMETRY when a measured line contributed, ORDER_DECLARED_TOTALS
-- otherwise.
--
-- Every existing card and estimate is unchanged: the new rate column is null (the card says nothing
-- about loading metres), so no line appears. No rows are seeded; no new table, permission or audit
-- action (a card edit is audited as RATE_CARD_UPDATED, like every other component).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. tms.rate_card - the rate
-- ---------------------------------------------------------------------------
ALTER TABLE tms.rate_card ADD COLUMN amount_per_loading_meter numeric(14,4);

ALTER TABLE tms.rate_card ADD CONSTRAINT ck_rate_card_loading_meter_nonnegative CHECK (
    amount_per_loading_meter IS NULL OR amount_per_loading_meter >= 0);

-- A card charging only per loading metre is a real card, so the rule learns the column. Replaced
-- rather than added to, as V39 did, so there is still one rule. The floor and the ceiling stay out
-- of it for V30's reason: a limit is a rule about other charges, not a charge.
ALTER TABLE tms.rate_card DROP CONSTRAINT ck_rate_card_has_a_component;
ALTER TABLE tms.rate_card ADD CONSTRAINT ck_rate_card_has_a_component CHECK (
    base_amount IS NOT NULL
    OR amount_per_km IS NOT NULL
    OR amount_per_kg IS NOT NULL
    OR amount_per_m3 IS NOT NULL
    OR amount_per_pallet IS NOT NULL
    OR amount_per_loading_meter IS NOT NULL
    OR amount_per_stop IS NOT NULL
    OR fuel_surcharge_percent IS NOT NULL
    OR amount_per_waiting_hour IS NOT NULL
    OR toll_amount IS NOT NULL
    OR accessorial_amount IS NOT NULL);

COMMENT ON COLUMN tms.rate_card.amount_per_loading_meter IS
    'Per loading metre of deck (V63, ADR-022 section 8): floor area of the stacks on the trip divided '
    'by the vehicle''s load width x floor fill factor. Null = this card says nothing about loading '
    'metres; a trip whose floor or width is unknown gets a NOT_CALCULABLE line, never a zero charge.';

-- ---------------------------------------------------------------------------
-- 2. tms.trip_cost_component - the breakdown line
-- ---------------------------------------------------------------------------
ALTER TABLE tms.trip_cost_component DROP CONSTRAINT ck_trip_cost_component_component;
ALTER TABLE tms.trip_cost_component ADD CONSTRAINT ck_trip_cost_component_component CHECK (
    component IN ('BASE', 'DISTANCE', 'WEIGHT', 'VOLUME', 'PALLETS', 'LOADING_METERS', 'STOP_OFF',
                  'FUEL_SURCHARGE', 'WAITING_TIME', 'TOLL', 'OTHER_ACCESSORIAL',
                  'MINIMUM_ADJUSTMENT', 'MAXIMUM_ADJUSTMENT'));

ALTER TABLE tms.trip_cost_component DROP CONSTRAINT ck_trip_cost_component_unit;
ALTER TABLE tms.trip_cost_component ADD CONSTRAINT ck_trip_cost_component_unit CHECK (
    unit IS NULL OR unit IN ('KM', 'KG', 'M3', 'PALLET', 'LOADING_METER', 'STOP', 'HOUR', 'PERCENT'));

ALTER TABLE tms.trip_cost_component DROP CONSTRAINT ck_trip_cost_component_quantity_source;
ALTER TABLE tms.trip_cost_component ADD CONSTRAINT ck_trip_cost_component_quantity_source CHECK (
    quantity_source IS NULL OR quantity_source IN (
        'ROUTE_REFERENCE', 'ORDER_DECLARED_TOTALS', 'MEASURED_ROUTE', 'TRIP_STOPS', 'LINEHAUL_SUBTOTAL',
        'RECORDED_WAITING', 'EFFECTIVE_GEOMETRY'));

-- Two reasons, because they ask two different people for two different things: the floor is
-- unknown when no line on the trip was measured (the material master), the width when the
-- vehicle type has no load dimensions (the fleet master).
ALTER TABLE tms.trip_cost_component DROP CONSTRAINT ck_trip_cost_component_reason;
ALTER TABLE tms.trip_cost_component ADD CONSTRAINT ck_trip_cost_component_reason CHECK (
    reason IS NULL OR reason IN (
        'DISTANCE_UNKNOWN', 'WEIGHT_UNKNOWN', 'VOLUME_UNKNOWN', 'PALLETS_UNKNOWN',
        'STOPS_UNKNOWN', 'WAITING_NOT_RECORDED', 'FLOOR_AREA_UNKNOWN', 'LOAD_WIDTH_UNKNOWN'));

-- The shape rule, restated for thirteen components. LOADING_METERS is measured, like PALLETS.
ALTER TABLE tms.trip_cost_component DROP CONSTRAINT ck_trip_cost_component_shape;
ALTER TABLE tms.trip_cost_component ADD CONSTRAINT ck_trip_cost_component_shape CHECK (
    (component IN ('BASE', 'TOLL', 'OTHER_ACCESSORIAL', 'MINIMUM_ADJUSTMENT', 'MAXIMUM_ADJUSTMENT')
        AND rate IS NULL AND quantity IS NULL AND unit IS NULL AND quantity_source IS NULL)
 OR (component IN ('DISTANCE', 'WEIGHT', 'VOLUME', 'PALLETS', 'LOADING_METERS', 'STOP_OFF',
                   'FUEL_SURCHARGE', 'WAITING_TIME')
        AND (status = 'NOT_CALCULABLE'
             OR (rate IS NOT NULL AND quantity IS NOT NULL AND unit IS NOT NULL
                 AND quantity_source IS NOT NULL))));
