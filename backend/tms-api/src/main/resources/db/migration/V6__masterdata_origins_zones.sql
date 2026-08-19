-- TMS by EBIM - V6 master data: origins and zones.
--
-- First company-scoped business masters (Step 05). Both tables follow the rules
-- docs/database/MIGRATION_STRATEGY.md section 9 sets for every migration after V1-V5:
-- company_id NOT NULL with an FK to tms.company and an index leading with it, explicit
-- constraint names, RLS enabled in this same migration, actor columns because a real actor
-- exists (the authenticated caller), and normalized codes enforced by CHECK rather than by
-- convention alone.
--
-- The permissions this module needs (masterdata.origin:*, masterdata.zone:*) already exist
-- in V3 - nothing to add there.

-- ---------------------------------------------------------------------------
-- Resolve the PostGIS extension schema (MIGRATION_STRATEGY.md section 4)
-- ---------------------------------------------------------------------------
-- V1 may have installed PostGIS in `public`, or a Supabase project may already carry it in
-- `extensions`. Putting the real schema on the search path for this transaction means the
-- geography/ST_* references below resolve correctly either way, with nothing hardcoded.
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
-- tms.zone - a named operational area, not a geofence (V1 keeps this deliberately simple)
-- ---------------------------------------------------------------------------
-- No geometry: the step brief is explicit that polygons are not forced into V1 without a
-- concrete use case. Nothing here blocks adding one later - a future migration would add a
-- nullable geometry column to this same table.
CREATE TABLE tms.zone (
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
    CONSTRAINT pk_zone PRIMARY KEY (id),
    CONSTRAINT fk_zone_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- Codes are unique per company, not installation-wide: two companies may both run a
    -- zone called "NORTH".
    CONSTRAINT uq_zone_company_code UNIQUE (company_id, code),
    CONSTRAINT ck_zone_code_normalized CHECK (code = upper(btrim(code))),
    CONSTRAINT ck_zone_code_shape CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_zone_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_zone_description_not_blank CHECK (description IS NULL OR btrim(description) <> ''),
    CONSTRAINT fk_zone_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_zone_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

-- Every business list is "list mine": company_id leads the index (ADR-003).
CREATE INDEX ix_zone_company ON tms.zone (company_id);

COMMENT ON TABLE tms.zone IS
    'Named operational area used to group origins, destinations and routes. No geometry in '
    'V1 - geofencing can be added later as a nullable column without changing this shape.';

CREATE TRIGGER tr_zone_set_updated_at
    BEFORE UPDATE ON tms.zone
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.zone ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- tms.origin - a loading/dispatch point (warehouse, distribution center, plant, hub)
-- ---------------------------------------------------------------------------
CREATE TABLE tms.origin (
    id                 uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id         uuid          NOT NULL,
    code               text          NOT NULL,
    name               text          NOT NULL,
    origin_type        text          NOT NULL DEFAULT 'OTHER',
    address            text,
    latitude           numeric(9,6),
    longitude          numeric(9,6),
    -- Generated from latitude/longitude so JPA/API CRUD stays plain numeric columns
    -- (architecture section 8) while spatial queries of a later step can index and use this
    -- column directly. ST_MakePoint/ST_SetSRID propagate NULL, so no CASE is needed: a point
    -- with no coordinates yields a NULL location, not an error.
    location           geography(Point, 4326) GENERATED ALWAYS AS (
                            ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography
                       ) STORED,
    time_zone          text          NOT NULL DEFAULT 'UTC',
    -- EWM boundary (architecture section 10, ADR nothing-shared): an optional external code,
    -- never a foreign key to another product's table.
    external_reference text,
    active             boolean       NOT NULL DEFAULT true,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    created_by         uuid,
    updated_by         uuid,
    CONSTRAINT pk_origin PRIMARY KEY (id),
    CONSTRAINT fk_origin_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT uq_origin_company_code UNIQUE (company_id, code),
    CONSTRAINT ck_origin_code_normalized CHECK (code = upper(btrim(code))),
    CONSTRAINT ck_origin_code_shape CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_origin_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_origin_type CHECK (
        origin_type IN ('WAREHOUSE', 'DISTRIBUTION_CENTER', 'PLANT', 'HUB', 'OTHER')),
    CONSTRAINT ck_origin_address_not_blank CHECK (address IS NULL OR btrim(address) <> ''),
    -- Both present or both absent: a single coordinate cannot describe a point.
    CONSTRAINT ck_origin_coordinates_pair CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CONSTRAINT ck_origin_latitude_range CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_origin_longitude_range CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_origin_time_zone_not_blank CHECK (btrim(time_zone) <> ''),
    CONSTRAINT ck_origin_external_reference_not_blank
        CHECK (external_reference IS NULL OR btrim(external_reference) <> ''),
    CONSTRAINT fk_origin_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_origin_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_origin_company ON tms.origin (company_id);

-- Supports future spatial queries (nearest origin, within-radius) without forcing them into
-- this step. A GiST index on a column nothing queries yet costs only write-time maintenance,
-- which is worth paying once rather than adding as a second migration later.
CREATE INDEX ix_origin_location ON tms.origin USING GIST (location);

COMMENT ON TABLE tms.origin IS
    'Loading/dispatch point: warehouse, distribution center, plant or hub. Coordinates are '
    'plain numeric columns for simple JPA/API CRUD; `location` is generated for spatial use.';
COMMENT ON COLUMN tms.origin.external_reference IS
    'Optional external system code (for example, a future EWM warehouse id). Never a foreign '
    'key: TMS and EWM share no internal tables (architecture section 10).';
COMMENT ON COLUMN tms.origin.time_zone IS
    'IANA time zone used to interpret this origin''s operating hours. Validated in Java; the '
    'database only guarantees it is not blank, matching tms.company.time_zone.';

CREATE TRIGGER tr_origin_set_updated_at
    BEFORE UPDATE ON tms.origin
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.origin ENABLE ROW LEVEL SECURITY;
