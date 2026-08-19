-- TMS by EBIM - V7 master data: destinations and frequencies.
--
-- Follows V6's shape exactly (docs/database/DATA_MODEL.md section 8): company_id NOT NULL
-- with an FK to tms.company and an index leading with it, explicit constraint names, RLS
-- enabled in this same migration, actor columns because a real actor exists (the
-- authenticated caller), and normalized codes enforced by CHECK rather than by convention
-- alone.
--
-- The permissions this module needs (masterdata.destination:*, masterdata.frequency:*)
-- already exist in V3 - nothing to add there.
--
-- Scope decision, matching how V6 deferred zone geometry rather than half-building it: a
-- destination-frequency association table is NOT added in this migration. No concrete
-- screen or use case needs it yet (Step 06's brief lists no association UI for either
-- Destinations or Frequencies), and the two masters are perfectly usable independently until
-- Orders/Planning (Steps 09-11) define what "which frequency serves this destination" must
-- actually look like. Adding the join table now, unused, would be exactly the speculative
-- framework complexity the repository instructions ask to avoid. `destination.zone_id` is
-- the one relationship this step does need (it is explicitly requested in the brief), so it
-- is included below with the same composite-FK tenant guarantee `tms.membership` uses for
-- company/organization.

-- ---------------------------------------------------------------------------
-- Resolve the PostGIS extension schema (MIGRATION_STRATEGY.md section 4), same as V6.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    ext_schema text;
BEGIN
    SELECT n.nspname INTO ext_schema
    FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
    WHERE e.extname = 'postgis';
    PERFORM set_config('search_path', 'tms, ' || quote_ident(ext_schema) || ', public', false);
END;
$$;

-- ---------------------------------------------------------------------------
-- tms.zone - add the composite-FK target destination.zone_id needs
-- ---------------------------------------------------------------------------
-- Redundant on its own (id is already the primary key), but it is the target of the
-- composite foreign key below, exactly like tms.company.uq_company_id_organization supports
-- tms.membership's composite FK (V2). This is what guarantees a destination can never point
-- at a zone belonging to a different company.
ALTER TABLE tms.zone ADD CONSTRAINT uq_zone_id_company UNIQUE (id, company_id);

-- ---------------------------------------------------------------------------
-- tms.destination - a delivery/service point (customer, store, branch, hub, CD, delivery point)
-- ---------------------------------------------------------------------------
CREATE TABLE tms.destination (
    id                    uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id            uuid          NOT NULL,
    code                  text          NOT NULL,
    name                  text          NOT NULL,
    destination_type      text          NOT NULL DEFAULT 'CUSTOMER',
    address               text,
    -- Free-text landmark/access note ("blue gate next to the pharmacy"), distinct from the
    -- structured address line - common in Peru addressing and harmless elsewhere.
    address_reference     text,
    -- Neutral locality fields: named for Peru's administrative divisions (distrito/provincia/
    -- departamento), which is where V1 operates, but they are plain optional text - nothing
    -- validates them against a Peru-specific catalogue, so a destination in another country
    -- can use the same three fields for its own local/regional/state hierarchy.
    district              text,
    province              text,
    department            text,
    country               text          NOT NULL DEFAULT 'PE',
    latitude              numeric(9,6),
    longitude             numeric(9,6),
    -- Same generated-column strategy as tms.origin.location (V6): plain numeric columns for
    -- ordinary JPA/API CRUD, geography derived by the database for future spatial queries.
    location              geography(Point, 4326) GENERATED ALWAYS AS (
                              ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography
                          ) STORED,
    zone_id               uuid,
    service_time_minutes  integer       NOT NULL DEFAULT 0,
    -- EWM boundary (architecture section 10): optional external code, never a foreign key.
    external_reference    text,
    active                boolean       NOT NULL DEFAULT true,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now(),
    created_by            uuid,
    updated_by            uuid,
    CONSTRAINT pk_destination PRIMARY KEY (id),
    CONSTRAINT fk_destination_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT uq_destination_company_code UNIQUE (company_id, code),
    CONSTRAINT ck_destination_code_normalized CHECK (code = upper(btrim(code))),
    CONSTRAINT ck_destination_code_shape CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_destination_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_destination_type CHECK (
        destination_type IN ('CUSTOMER', 'STORE', 'BRANCH', 'HUB', 'DISTRIBUTION_CENTER', 'DELIVERY_POINT')),
    CONSTRAINT ck_destination_address_not_blank CHECK (address IS NULL OR btrim(address) <> ''),
    CONSTRAINT ck_destination_address_reference_not_blank
        CHECK (address_reference IS NULL OR btrim(address_reference) <> ''),
    CONSTRAINT ck_destination_district_not_blank CHECK (district IS NULL OR btrim(district) <> ''),
    CONSTRAINT ck_destination_province_not_blank CHECK (province IS NULL OR btrim(province) <> ''),
    CONSTRAINT ck_destination_department_not_blank CHECK (department IS NULL OR btrim(department) <> ''),
    CONSTRAINT ck_destination_country_not_blank CHECK (btrim(country) <> ''),
    CONSTRAINT ck_destination_coordinates_pair CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CONSTRAINT ck_destination_latitude_range CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_destination_longitude_range CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_destination_service_time_nonnegative CHECK (service_time_minutes >= 0),
    CONSTRAINT ck_destination_external_reference_not_blank
        CHECK (external_reference IS NULL OR btrim(external_reference) <> ''),
    CONSTRAINT fk_destination_zone FOREIGN KEY (zone_id)
        REFERENCES tms.zone (id) ON DELETE RESTRICT,
    -- MATCH SIMPLE (the default): satisfied when zone_id is NULL, enforced otherwise - the
    -- same idiom tms.membership uses for company_id/organization_id (V2).
    CONSTRAINT fk_destination_zone_company FOREIGN KEY (zone_id, company_id)
        REFERENCES tms.zone (id, company_id),
    CONSTRAINT fk_destination_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_destination_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_destination_company ON tms.destination (company_id);
CREATE INDEX ix_destination_zone ON tms.destination (zone_id) WHERE zone_id IS NOT NULL;
CREATE INDEX ix_destination_location ON tms.destination USING GIST (location);

COMMENT ON TABLE tms.destination IS
    'Delivery/service point: customer, store, branch, hub, distribution center or delivery '
    'point. Coordinates are plain numeric columns for simple JPA/API CRUD; `location` is '
    'generated for spatial use, exactly like tms.origin (V6).';
COMMENT ON COLUMN tms.destination.zone_id IS
    'Optional grouping zone. The composite FK guarantees it belongs to the same company as '
    'the destination, the same guarantee tms.membership gives for company/organization.';
COMMENT ON COLUMN tms.destination.external_reference IS
    'Optional external system code (for example, a future EWM ship-to id). Never a foreign '
    'key: TMS and EWM share no internal tables (architecture section 10).';

CREATE TRIGGER tr_destination_set_updated_at
    BEFORE UPDATE ON tms.destination
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.destination ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- tms.frequency - a company-scoped, named delivery schedule (header)
-- ---------------------------------------------------------------------------
CREATE TABLE tms.frequency (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id  uuid        NOT NULL,
    code        text        NOT NULL,
    name        text        NOT NULL,
    description text,
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_by  uuid,
    CONSTRAINT pk_frequency PRIMARY KEY (id),
    CONSTRAINT fk_frequency_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT uq_frequency_company_code UNIQUE (company_id, code),
    CONSTRAINT ck_frequency_code_normalized CHECK (code = upper(btrim(code))),
    CONSTRAINT ck_frequency_code_shape CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_frequency_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_frequency_description_not_blank CHECK (description IS NULL OR btrim(description) <> ''),
    CONSTRAINT fk_frequency_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_frequency_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_frequency_company ON tms.frequency (company_id);

COMMENT ON TABLE tms.frequency IS
    'A named delivery schedule. Deliberately just a header: the weekly cadence lives in '
    'tms.frequency_weekly_rule and date overrides in tms.frequency_exception, so this model '
    'is not five or seven hardcoded boolean columns and can grow fortnightly/cutoff/lead-time '
    'behaviour without another migration touching this table.';

CREATE TRIGGER tr_frequency_set_updated_at
    BEFORE UPDATE ON tms.frequency
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.frequency ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- tms.frequency_weekly_rule - the Monday-Sunday cadence, one row per configured day
-- ---------------------------------------------------------------------------
-- No company_id: this table is a pure child of tms.frequency, exactly like
-- tms.membership_role is a pure child of tms.membership (V2) - "the link has no meaning
-- without its" parent, so it cascades from it instead of carrying its own tenant column.
CREATE TABLE tms.frequency_weekly_rule (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    frequency_id   uuid        NOT NULL,
    -- ISO-8601: 1 = Monday ... 7 = Sunday.
    day_of_week    smallint    NOT NULL,
    -- Row presence models "this day is configured"; `enabled` additionally lets a day be
    -- temporarily suspended (for example a seasonal pause) while keeping its cutoff/lead-time
    -- configuration, instead of losing that configuration on every toggle-off/toggle-on.
    enabled        boolean     NOT NULL DEFAULT true,
    cutoff_time    time,
    lead_time_days integer,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid,
    CONSTRAINT pk_frequency_weekly_rule PRIMARY KEY (id),
    CONSTRAINT fk_frequency_weekly_rule_frequency FOREIGN KEY (frequency_id)
        REFERENCES tms.frequency (id) ON DELETE CASCADE,
    CONSTRAINT uq_frequency_weekly_rule_day UNIQUE (frequency_id, day_of_week),
    CONSTRAINT ck_frequency_weekly_rule_day_range CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_frequency_weekly_rule_lead_time_nonnegative
        CHECK (lead_time_days IS NULL OR lead_time_days >= 0),
    CONSTRAINT fk_frequency_weekly_rule_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_frequency_weekly_rule_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_frequency_weekly_rule_frequency ON tms.frequency_weekly_rule (frequency_id);

COMMENT ON TABLE tms.frequency_weekly_rule IS
    'One row per configured day of a frequency''s weekly cadence (1=Monday..7=Sunday). '
    'Cascades from tms.frequency: the row has no meaning without its parent, same as '
    'tms.membership_role (V2).';

CREATE TRIGGER tr_frequency_weekly_rule_set_updated_at
    BEFORE UPDATE ON tms.frequency_weekly_rule
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.frequency_weekly_rule ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- tms.frequency_exception - a date-specific override of the weekly cadence
-- ---------------------------------------------------------------------------
CREATE TABLE tms.frequency_exception (
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    frequency_id     uuid        NOT NULL,
    exception_date   date        NOT NULL,
    -- true = service happens on this date even though the weekly rule would not run it
    -- (an extra pickup); false = no service on this date even though the weekly rule would
    -- (a holiday/blackout).
    service_override boolean     NOT NULL,
    note             text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_by       uuid,
    CONSTRAINT pk_frequency_exception PRIMARY KEY (id),
    CONSTRAINT fk_frequency_exception_frequency FOREIGN KEY (frequency_id)
        REFERENCES tms.frequency (id) ON DELETE CASCADE,
    CONSTRAINT uq_frequency_exception_date UNIQUE (frequency_id, exception_date),
    CONSTRAINT ck_frequency_exception_note_not_blank CHECK (note IS NULL OR btrim(note) <> ''),
    CONSTRAINT fk_frequency_exception_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_frequency_exception_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_frequency_exception_frequency ON tms.frequency_exception (frequency_id);

COMMENT ON TABLE tms.frequency_exception IS
    'A single-date override of tms.frequency''s weekly cadence: an extra service date or a '
    'blackout. Cascades from tms.frequency for the same reason as tms.frequency_weekly_rule.';

CREATE TRIGGER tr_frequency_exception_set_updated_at
    BEFORE UPDATE ON tms.frequency_exception
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.frequency_exception ENABLE ROW LEVEL SECURITY;
