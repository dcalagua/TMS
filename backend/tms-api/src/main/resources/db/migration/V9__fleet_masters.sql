-- TMS by EBIM - V9 fleet masters: carriers, vehicle types and vehicles.
--
-- Follows V6/V7/V8's shape unchanged (docs/database/DATA_MODEL.md section 10): company_id
-- NOT NULL with an FK to tms.company and an index leading with it, actor columns, RLS enabled
-- in this same migration, normalized-code CHECKs, and the composite-FK tenant guarantee (rule
-- 6) for tms.vehicle's references into tms.carrier and tms.vehicle_type.
--
-- The permissions this module needs (fleet.carrier:*, fleet.vehicle_type:*, fleet.vehicle:*)
-- already exist in V3 - nothing to add there.

-- ---------------------------------------------------------------------------
-- tms.carrier - a company-scoped third-party or owned-fleet transport provider
-- ---------------------------------------------------------------------------
CREATE TABLE tms.carrier (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id     uuid        NOT NULL,
    code           text        NOT NULL,
    business_name  text        NOT NULL,
    -- Flexible legal/tax identification: unlike origin_type/destination_type (a small fixed
    -- catalogue), tax_id_type is deliberately free text rather than a CHECK...IN list. Legal
    -- identifier types vary by country (RUC in Peru, RFC in Mexico, CNPJ in Brazil, EIN in the
    -- US, ...) and TMS must not hardcode a per-country catalogue to stay usable beyond Peru -
    -- the same reasoning destination.district/province/department (V7) already uses for
    -- locality fields. Java may suggest common values; it does not restrict them.
    tax_id_type    text        NOT NULL,
    tax_id_value   text        NOT NULL,
    contact_name   text,
    phone          text,
    email          text,
    active         boolean     NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid,
    CONSTRAINT pk_carrier PRIMARY KEY (id),
    CONSTRAINT fk_carrier_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- Target for vehicle's composite FK below - same idiom as uq_zone_id_company (V7).
    CONSTRAINT uq_carrier_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_carrier_company_code UNIQUE (company_id, code),
    -- A given legal identifier is registered once per company; the same real-world carrier
    -- used by two different tenant companies is two separate rows, one per company (ADR-003).
    CONSTRAINT uq_carrier_company_tax_id UNIQUE (company_id, tax_id_type, tax_id_value),
    CONSTRAINT ck_carrier_code_normalized CHECK (code = upper(btrim(code))),
    CONSTRAINT ck_carrier_code_shape CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_carrier_business_name_not_blank CHECK (btrim(business_name) <> ''),
    -- Normalization is enforced, not assumed (docs/database/DATA_MODEL.md section 3.8): both
    -- halves of the flexible tax-id pair are stored trimmed and upper case so
    -- uq_carrier_company_tax_id actually catches "ruc"/"RUC" as the same type.
    CONSTRAINT ck_carrier_tax_id_type_not_blank CHECK (btrim(tax_id_type) <> ''),
    CONSTRAINT ck_carrier_tax_id_type_normalized CHECK (tax_id_type = upper(btrim(tax_id_type))),
    CONSTRAINT ck_carrier_tax_id_value_not_blank CHECK (btrim(tax_id_value) <> ''),
    CONSTRAINT ck_carrier_tax_id_value_normalized CHECK (tax_id_value = upper(btrim(tax_id_value))),
    CONSTRAINT ck_carrier_contact_name_not_blank CHECK (contact_name IS NULL OR btrim(contact_name) <> ''),
    CONSTRAINT ck_carrier_phone_not_blank CHECK (phone IS NULL OR btrim(phone) <> ''),
    CONSTRAINT ck_carrier_email_normalized CHECK (email IS NULL OR email = lower(btrim(email))),
    CONSTRAINT ck_carrier_email_shape
        CHECK (email IS NULL OR email ~ '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$'),
    CONSTRAINT fk_carrier_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_carrier_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_carrier_company ON tms.carrier (company_id);

COMMENT ON TABLE tms.carrier IS
    'A transport provider (third-party or the company''s own fleet operator) used by vehicles. '
    'tax_id_type/tax_id_value are a flexible free-text pair rather than a fixed catalogue, '
    'since legal identifier types vary by country.';

CREATE TRIGGER tr_carrier_set_updated_at
    BEFORE UPDATE ON tms.carrier
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.carrier ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- tms.vehicle_type - default capacities and physical characteristics for a class of vehicle
-- ---------------------------------------------------------------------------
CREATE TABLE tms.vehicle_type (
    id                       uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id               uuid        NOT NULL,
    code                     text        NOT NULL,
    name                     text        NOT NULL,
    -- Units are explicit in the column name and never mixed: kilograms, cubic meters, meters,
    -- Celsius - the step brief's "do not mix kilograms/tons or m3/cm3 implicitly."
    max_weight_kg            numeric(10,2) NOT NULL,
    max_volume_m3            numeric(10,3) NOT NULL,
    max_pallets              integer       NOT NULL DEFAULT 0,
    length_m                 numeric(6,2),
    width_m                  numeric(6,2),
    height_m                 numeric(6,2),
    body_type                text,
    temperature_controlled   boolean       NOT NULL DEFAULT false,
    min_temperature_celsius  numeric(5,2),
    max_temperature_celsius  numeric(5,2),
    axles                    integer,
    active                   boolean       NOT NULL DEFAULT true,
    created_at               timestamptz   NOT NULL DEFAULT now(),
    updated_at               timestamptz   NOT NULL DEFAULT now(),
    created_by               uuid,
    updated_by               uuid,
    CONSTRAINT pk_vehicle_type PRIMARY KEY (id),
    CONSTRAINT fk_vehicle_type_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- Target for vehicle's composite FK below.
    CONSTRAINT uq_vehicle_type_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_vehicle_type_company_code UNIQUE (company_id, code),
    CONSTRAINT ck_vehicle_type_code_normalized CHECK (code = upper(btrim(code))),
    CONSTRAINT ck_vehicle_type_code_shape CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_vehicle_type_name_not_blank CHECK (btrim(name) <> ''),
    -- A vehicle type with zero or negative weight/volume capacity is meaningless; max_pallets
    -- may legitimately be zero (a tanker or bulk-liquid type carries no pallets).
    CONSTRAINT ck_vehicle_type_max_weight_positive CHECK (max_weight_kg > 0),
    CONSTRAINT ck_vehicle_type_max_volume_positive CHECK (max_volume_m3 > 0),
    CONSTRAINT ck_vehicle_type_max_pallets_nonnegative CHECK (max_pallets >= 0),
    CONSTRAINT ck_vehicle_type_length_positive CHECK (length_m IS NULL OR length_m > 0),
    CONSTRAINT ck_vehicle_type_width_positive CHECK (width_m IS NULL OR width_m > 0),
    CONSTRAINT ck_vehicle_type_height_positive CHECK (height_m IS NULL OR height_m > 0),
    CONSTRAINT ck_vehicle_type_body_type CHECK (body_type IS NULL OR body_type IN (
        'DRY_VAN', 'REFRIGERATED', 'FLATBED', 'TANKER', 'CONTAINER', 'CURTAIN_SIDER', 'OTHER')),
    CONSTRAINT ck_vehicle_type_axles_positive CHECK (axles IS NULL OR axles >= 1),
    -- A temperature range only makes sense for a temperature-controlled type; keeps the model
    -- coherent without a separate "temperature profile" table (brief: "only when the model
    -- remains simple").
    CONSTRAINT ck_vehicle_type_temperature_requires_controlled CHECK (
        temperature_controlled OR (min_temperature_celsius IS NULL AND max_temperature_celsius IS NULL)),
    CONSTRAINT ck_vehicle_type_temperature_range CHECK (
        min_temperature_celsius IS NULL OR max_temperature_celsius IS NULL
        OR min_temperature_celsius <= max_temperature_celsius),
    CONSTRAINT fk_vehicle_type_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_vehicle_type_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_vehicle_type_company ON tms.vehicle_type (company_id);

COMMENT ON TABLE tms.vehicle_type IS
    'A class of vehicle and its default capacities/physical characteristics. tms.vehicle may '
    'override max_weight_kg/max_volume_m3/max_pallets individually; see '
    'EffectiveCapacityResolver for the vehicle-overrides-first resolution rule.';
COMMENT ON COLUMN tms.vehicle_type.max_weight_kg IS 'Kilograms, never tons - explicit unit in the column name.';
COMMENT ON COLUMN tms.vehicle_type.max_volume_m3 IS 'Cubic meters, never cm3 - explicit unit in the column name.';

CREATE TRIGGER tr_vehicle_type_set_updated_at
    BEFORE UPDATE ON tms.vehicle_type
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.vehicle_type ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- tms.vehicle - a physical vehicle, optionally carrier-operated, always vehicle-typed
-- ---------------------------------------------------------------------------
CREATE TABLE tms.vehicle (
    id                        uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id                uuid          NOT NULL,
    code                      text          NOT NULL,
    license_plate             text          NOT NULL,
    -- Optional: a company may run its own owned fleet with no third-party carrier.
    carrier_id                uuid,
    -- Mandatory: capacity resolution (EffectiveCapacityResolver) always needs a vehicle-type
    -- baseline to fall back to.
    vehicle_type_id           uuid          NOT NULL,
    max_weight_override_kg    numeric(10,2),
    max_volume_override_m3    numeric(10,3),
    max_pallets_override      integer,
    -- Current operational status, not a scheduling calendar (step brief: "availability baseline
    -- that does not pretend to be a full scheduling calendar"). Trip/planning-level scheduling
    -- is a later, separate concern.
    availability_status       text          NOT NULL DEFAULT 'AVAILABLE',
    active                    boolean       NOT NULL DEFAULT true,
    created_at                timestamptz   NOT NULL DEFAULT now(),
    updated_at                timestamptz   NOT NULL DEFAULT now(),
    created_by                uuid,
    updated_by                uuid,
    CONSTRAINT pk_vehicle PRIMARY KEY (id),
    CONSTRAINT fk_vehicle_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT uq_vehicle_company_code UNIQUE (company_id, code),
    -- License plate uniqueness is scoped to the company, matching every other master's code
    -- uniqueness (ADR-003), not enforced globally. A global unique constraint would also open a
    -- covert cross-tenant channel: company A could learn whether company B has registered a
    -- specific plate merely by triggering a conflict - the same reason cross-company lookups
    -- elsewhere return 404, never 409/403 (see RouteApiIntegrationTest.crossCompanyAccessIsBlocked).
    CONSTRAINT uq_vehicle_company_license_plate UNIQUE (company_id, license_plate),
    CONSTRAINT ck_vehicle_code_normalized CHECK (code = upper(btrim(code))),
    CONSTRAINT ck_vehicle_code_shape CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_vehicle_license_plate_normalized CHECK (license_plate = upper(btrim(license_plate))),
    CONSTRAINT ck_vehicle_license_plate_shape CHECK (license_plate ~ '^[A-Z0-9-]{4,12}$'),
    CONSTRAINT ck_vehicle_max_weight_override_positive
        CHECK (max_weight_override_kg IS NULL OR max_weight_override_kg > 0),
    CONSTRAINT ck_vehicle_max_volume_override_positive
        CHECK (max_volume_override_m3 IS NULL OR max_volume_override_m3 > 0),
    CONSTRAINT ck_vehicle_max_pallets_override_nonnegative
        CHECK (max_pallets_override IS NULL OR max_pallets_override >= 0),
    CONSTRAINT ck_vehicle_availability_status CHECK (
        availability_status IN ('AVAILABLE', 'IN_MAINTENANCE', 'OUT_OF_SERVICE')),
    CONSTRAINT fk_vehicle_carrier FOREIGN KEY (carrier_id)
        REFERENCES tms.carrier (id) ON DELETE RESTRICT,
    -- MATCH SIMPLE (default): satisfied when carrier_id IS NULL, enforced otherwise - same
    -- idiom as destination.zone_id (V7). A vehicle's carrier must belong to the vehicle's own
    -- company even though the two FK columns are separate.
    CONSTRAINT fk_vehicle_carrier_company FOREIGN KEY (carrier_id, company_id)
        REFERENCES tms.carrier (id, company_id),
    CONSTRAINT fk_vehicle_type FOREIGN KEY (vehicle_type_id)
        REFERENCES tms.vehicle_type (id) ON DELETE RESTRICT,
    -- vehicle_type_id is NOT NULL, so this composite FK is always checked - the vehicle's type
    -- must belong to the vehicle's own company.
    CONSTRAINT fk_vehicle_type_company FOREIGN KEY (vehicle_type_id, company_id)
        REFERENCES tms.vehicle_type (id, company_id),
    CONSTRAINT fk_vehicle_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_vehicle_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_vehicle_company ON tms.vehicle (company_id);
CREATE INDEX ix_vehicle_carrier ON tms.vehicle (carrier_id) WHERE carrier_id IS NOT NULL;
CREATE INDEX ix_vehicle_type ON tms.vehicle (vehicle_type_id);

COMMENT ON TABLE tms.vehicle IS
    'A physical vehicle: company-scoped, optionally operated by a carrier, always assigned a '
    'vehicle type. max_*_override columns take precedence over the vehicle type''s defaults '
    'when present - see EffectiveCapacityResolver. availability_status is a current-state flag, '
    'not a scheduling calendar (GPS/telematics and live tracking are deferred by decision).';
COMMENT ON COLUMN tms.vehicle.license_plate IS
    'Normalized upper case, unique per company (not installation-wide) - see the constraint '
    'comment on uq_vehicle_company_license_plate for why a global uniqueness scope was rejected.';

CREATE TRIGGER tr_vehicle_set_updated_at
    BEFORE UPDATE ON tms.vehicle
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.vehicle ENABLE ROW LEVEL SECURITY;
