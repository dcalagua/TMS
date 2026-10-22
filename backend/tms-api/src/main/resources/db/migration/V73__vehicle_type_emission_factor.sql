-- ============================================================================
-- V73 - Carbon footprint: an emission factor per vehicle type (backlog item 9)
-- ============================================================================
-- A trip's emissions are its distance (the same figure the trip workspace already shows, with its
-- routing provenance - V38, ADR-010) times the kilograms of CO2-equivalent its vehicle type emits per
-- kilometre. They are computed when read and never stored, and split between the trip's orders on the
-- company's cost-allocation basis (V68, ADR-026), so "the carbon to serve a customer" is read with the
-- very machinery that answers "the cost to serve" it.
--
-- NO FACTOR, NO FIGURE. The factor is nullable and a type without one produces no emission at all -
-- "sin datos", never zero. A zero would be the most flattering number in a sustainability report and
-- the least true one. TMS ships no default factor: what a truck emits per km depends on its engine, its
-- load and the fuel standard of the country, and the shipper (or its auditor) is the one who knows it.
--
-- The fuel type is descriptive: it says what the factor was measured for, so a report can be read by
-- someone who did not configure it. No business rows are seeded.
-- ============================================================================

ALTER TABLE tms.vehicle_type
    ADD COLUMN fuel_type text,
    ADD COLUMN emission_factor_kg_co2e_per_km numeric(8,4);

ALTER TABLE tms.vehicle_type ADD CONSTRAINT ck_vehicle_type_fuel_type
    CHECK (fuel_type IS NULL OR fuel_type IN ('DIESEL', 'GASOLINE', 'LPG', 'CNG', 'ELECTRIC', 'HYBRID', 'OTHER'));

-- Strictly positive and bounded: even an electric van has an upstream factor above zero, and nothing
-- that drives on a road emits fifty kilograms per kilometre - a value above that is a unit mistake
-- (grams typed as kilograms), refused here rather than reported as a footprint.
ALTER TABLE tms.vehicle_type ADD CONSTRAINT ck_vehicle_type_emission_factor
    CHECK (emission_factor_kg_co2e_per_km IS NULL
        OR (emission_factor_kg_co2e_per_km > 0 AND emission_factor_kg_co2e_per_km <= 50));

COMMENT ON COLUMN tms.vehicle_type.fuel_type IS
    'What the vehicle type runs on (V73). Descriptive: it says what the emission factor was measured for.';
COMMENT ON COLUMN tms.vehicle_type.emission_factor_kg_co2e_per_km IS
    'kg CO2e emitted per km driven (V73). Null = no factor: the trip''s emissions are absent ("sin '
    'datos"), never zero.';

-- Reading a trip's footprint and the footprint per customer. Same roles as the cost-to-serve read
-- (V68): a planner choosing a truck is the person who can change the figure.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('settlement.emission', 'read', 'View the carbon footprint of trips, and per customer, destination and route');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN', 'PLANNER', 'VIEWER')
  AND p.code = 'settlement.emission:read';
