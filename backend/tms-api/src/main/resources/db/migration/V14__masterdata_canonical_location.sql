-- TMS by EBIM - V14 canonical Location master.
--
-- Reasoning, alternatives and the compatibility debt this leaves behind:
-- docs/architecture/ADR_LOCATION_MODEL.md.
--
-- In one paragraph: an origin, a store, a customer delivery point, a distribution centre, a
-- plant and a hub are all *physical places*, and V6/V7 modelled them as two independent tables
-- with asymmetric shapes, no link between a place that both ships and receives, and no
-- idempotency key for an inbound integration. This migration introduces tms.location as the
-- canonical master and turns tms.origin/tms.destination into projections of it.
--
-- What this migration deliberately does NOT do: touch a single existing foreign key. All five
-- referencing tables (route.origin_id, route_stop.destination_id, transport_order.origin_id/
-- destination_id, planning_run.origin_id, trip_stop.destination_id) are untouched, and V1-V13
-- are byte-identical to what they were. Unification is a later step, made cheap by the
-- location_id link added in section 3 and by the id equality the backfill in section 4 gives
-- every row that already existed.

-- ---------------------------------------------------------------------------
-- Resolve the PostGIS extension schema (MIGRATION_STRATEGY.md section 4)
-- ---------------------------------------------------------------------------
-- Same DO block V6 and V7 use: PostGIS may live in `public` or in `extensions` depending on how
-- the database was provisioned, so the real schema goes on the search path for this transaction
-- instead of being hardcoded.
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
-- 1. tms.location - the canonical physical place
-- ---------------------------------------------------------------------------
CREATE TABLE tms.location (
    id                   uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id           uuid          NOT NULL,
    code                 text          NOT NULL,
    name                 text          NOT NULL,
    -- The UNION of ck_origin_type (V6) and ck_destination_type (V7), so the backfill below is
    -- lossless in both directions and no legacy value has to be mapped onto a different one.
    location_type        text          NOT NULL DEFAULT 'OTHER',
    address              text,
    -- Free-text landmark/access note ("blue gate next to the pharmacy"), as on tms.destination.
    address_reference    text,
    -- Neutral locality fields, named for Peru's administrative divisions because that is where
    -- V1 operates, but validated against no country-specific catalogue - identical to V7.
    district             text,
    province             text,
    department           text,
    country              text          NOT NULL DEFAULT 'PE',
    -- Present on both roles now. tms.destination never had one, which meant a delivery window
    -- could not be interpreted in the receiving site's own time.
    time_zone            text          NOT NULL DEFAULT 'UTC',
    latitude             numeric(9,6),
    longitude            numeric(9,6),
    -- Same generated-column strategy as tms.origin.location and tms.destination.location:
    -- plain numeric columns for ordinary JPA/API CRUD, geography derived by the database for
    -- spatial use. Named geo_point rather than `location` because a column called `location`
    -- inside a table called `location` is legal and unreadable.
    geo_point            geography(Point, 4326) GENERATED ALWAYS AS (
                             ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography
                         ) STORED,
    zone_id              uuid,
    service_time_minutes integer       NOT NULL DEFAULT 0,
    -- Idempotency/external-reference pair, the same idiom tms.transport_order (V10) already
    -- uses: who sent this record and what they call it. Both optional - a manually created
    -- location has neither - but a reference without a source cannot be deduplicated, so the
    -- CHECK below requires the pair to be complete. This is the identity an inbound Locations
    -- integration will address, and neither legacy table could offer it: V6/V7 declared
    -- external_reference with no uniqueness constraint at all.
    external_system      text,
    external_reference   text,
    active               boolean       NOT NULL DEFAULT true,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),
    created_by           uuid,
    updated_by           uuid,
    CONSTRAINT pk_location PRIMARY KEY (id),
    CONSTRAINT fk_location_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- Target of the composite tenant FKs from tms.location_role's siblings and from
    -- origin.location_id / destination.location_id below - the same idiom V8 added for
    -- tms.origin and tms.destination themselves.
    CONSTRAINT uq_location_id_company UNIQUE (id, company_id),
    -- Codes are unique per company, not installation-wide (ADR-003).
    CONSTRAINT uq_location_company_code UNIQUE (company_id, code),
    CONSTRAINT ck_location_code_normalized CHECK (code = upper(btrim(code))),
    CONSTRAINT ck_location_code_shape CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_location_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_location_type CHECK (location_type IN (
        'WAREHOUSE', 'DISTRIBUTION_CENTER', 'PLANT', 'HUB', 'OTHER',
        'CUSTOMER', 'STORE', 'BRANCH', 'DELIVERY_POINT')),
    CONSTRAINT ck_location_address_not_blank CHECK (address IS NULL OR btrim(address) <> ''),
    CONSTRAINT ck_location_address_reference_not_blank
        CHECK (address_reference IS NULL OR btrim(address_reference) <> ''),
    CONSTRAINT ck_location_district_not_blank CHECK (district IS NULL OR btrim(district) <> ''),
    CONSTRAINT ck_location_province_not_blank CHECK (province IS NULL OR btrim(province) <> ''),
    CONSTRAINT ck_location_department_not_blank CHECK (department IS NULL OR btrim(department) <> ''),
    CONSTRAINT ck_location_country_not_blank CHECK (btrim(country) <> ''),
    CONSTRAINT ck_location_time_zone_not_blank CHECK (btrim(time_zone) <> ''),
    -- Both present or both absent: a single coordinate cannot describe a point.
    CONSTRAINT ck_location_coordinates_pair CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CONSTRAINT ck_location_latitude_range CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_location_longitude_range CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_location_service_time_nonnegative CHECK (service_time_minutes >= 0),
    CONSTRAINT ck_location_external_system_not_blank
        CHECK (external_system IS NULL OR btrim(external_system) <> ''),
    CONSTRAINT ck_location_external_reference_not_blank
        CHECK (external_reference IS NULL OR btrim(external_reference) <> ''),
    CONSTRAINT ck_location_external_pair_complete
        CHECK (external_reference IS NULL OR external_system IS NOT NULL),
    CONSTRAINT fk_location_zone FOREIGN KEY (zone_id)
        REFERENCES tms.zone (id) ON DELETE RESTRICT,
    -- MATCH SIMPLE (the default): satisfied when zone_id is NULL, enforced otherwise. This is
    -- what makes a cross-company zone reference impossible, exactly as on tms.destination (V7).
    CONSTRAINT fk_location_zone_company FOREIGN KEY (zone_id, company_id)
        REFERENCES tms.zone (id, company_id),
    CONSTRAINT fk_location_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_location_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

-- Every business list is "list mine": company_id leads the index (ADR-003).
CREATE INDEX ix_location_company ON tms.location (company_id);
CREATE INDEX ix_location_zone ON tms.location (zone_id) WHERE zone_id IS NOT NULL;
CREATE INDEX ix_location_geo_point ON tms.location USING GIST (geo_point);

-- Idempotency guard: one (system, reference) pair per company. Partial, because most locations
-- have neither, and ck_location_external_pair_complete already guarantees external_system is
-- never NULL when external_reference is not - so this index cannot be defeated by two
-- differently-NULL systems. Same reasoning as uq_transport_order_external (V10).
CREATE UNIQUE INDEX uq_location_external
    ON tms.location (company_id, external_system, external_reference)
    WHERE external_reference IS NOT NULL;

COMMENT ON TABLE tms.location IS
    'Canonical physical place: origin, store, customer delivery point, distribution centre, '
    'plant or hub. The role it plays is in tms.location_role, not in this table. '
    'tms.origin and tms.destination are compatibility projections of it (ADR_LOCATION_MODEL).';
COMMENT ON COLUMN tms.location.location_type IS
    'What the place is. The union of the V6 origin and V7 destination type vocabularies, so a '
    'legacy row backfills without its type being reinterpreted.';
COMMENT ON COLUMN tms.location.external_reference IS
    'What the sending system calls this location, unique per (company, external_system). Never '
    'a foreign key: TMS and EWM share no internal tables (architecture section 10).';
COMMENT ON COLUMN tms.location.geo_point IS
    'Derived from latitude/longitude by the database. GiST-indexed for proximity and '
    'containment queries; the numeric columns stay the API and JPA contract.';

CREATE TRIGGER tr_location_set_updated_at
    BEFORE UPDATE ON tms.location
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.location ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 2. tms.location_role - what the place may do
-- ---------------------------------------------------------------------------
-- No company_id: a pure child of tms.location, exactly like tms.frequency_weekly_rule is a
-- pure child of tms.frequency (V7). The tenant column would be denormalised state that could
-- drift from the parent, and the RLS policy in section 6 scopes it through the parent instead.
--
-- Of the seven roles, ORIGIN and SHIP_TO carry behaviour - they are what decide whether the
-- location has a tms.origin and/or a tms.destination projection. The rest are classification
-- (ADR_LOCATION_MODEL section 2).
CREATE TABLE tms.location_role (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    location_id uuid        NOT NULL,
    role        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_location_role PRIMARY KEY (id),
    -- CASCADE, not RESTRICT: a role assignment has no meaning without its location, the same
    -- reasoning tms.membership_role (V2) and tms.frequency_weekly_rule (V7) follow.
    CONSTRAINT fk_location_role_location FOREIGN KEY (location_id)
        REFERENCES tms.location (id) ON DELETE CASCADE,
    CONSTRAINT uq_location_role_location_role UNIQUE (location_id, role),
    CONSTRAINT ck_location_role_role CHECK (
        role IN ('ORIGIN', 'SHIP_TO', 'STORE', 'DC', 'PLANT', 'HUB', 'OTHER'))
);

CREATE INDEX ix_location_role_location ON tms.location_role (location_id);

COMMENT ON TABLE tms.location_role IS
    'The roles one location plays. ORIGIN and SHIP_TO are behavioural - they decide whether the '
    'location has a tms.origin and/or tms.destination compatibility projection. STORE, DC, '
    'PLANT, HUB and OTHER are classification only.';

ALTER TABLE tms.location_role ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 3. The link columns on the legacy tables
-- ---------------------------------------------------------------------------
-- Nullable, not NOT NULL, and deliberately so: the integration and constraint suites seed
-- tms.origin and tms.destination with direct SQL, and a mandatory link would turn every one of
-- those fixtures into ceremony that tests nothing. "Every origin has a location" is enforced by
-- LocationCompatibilityProjector instead, and recorded as debt D-2 in the ADR.
ALTER TABLE tms.origin ADD COLUMN location_id uuid;
ALTER TABLE tms.destination ADD COLUMN location_id uuid;

ALTER TABLE tms.origin
    ADD CONSTRAINT fk_origin_location FOREIGN KEY (location_id)
        REFERENCES tms.location (id) ON DELETE RESTRICT,
    -- The composite tenant guarantee: an origin can never point at a location of another
    -- company. MATCH SIMPLE, so a NULL location_id is simply not checked.
    ADD CONSTRAINT fk_origin_location_company FOREIGN KEY (location_id, company_id)
        REFERENCES tms.location (id, company_id);

ALTER TABLE tms.destination
    ADD CONSTRAINT fk_destination_location FOREIGN KEY (location_id)
        REFERENCES tms.location (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_destination_location_company FOREIGN KEY (location_id, company_id)
        REFERENCES tms.location (id, company_id);

-- One origin and one destination per location: the projection is one-to-one, and a partial
-- unique index says so rather than leaving it to application discipline.
CREATE UNIQUE INDEX uq_origin_location ON tms.origin (location_id) WHERE location_id IS NOT NULL;
CREATE UNIQUE INDEX uq_destination_location
    ON tms.destination (location_id) WHERE location_id IS NOT NULL;

COMMENT ON COLUMN tms.origin.location_id IS
    'The canonical tms.location this origin projects, and the authoritative mapping for the '
    'later unification (ADR_LOCATION_MODEL section 3). The V14 backfill additionally makes it '
    'equal to tms.origin.id for every row that existed before V14.';
COMMENT ON COLUMN tms.destination.location_id IS
    'The canonical tms.location this destination projects. For pre-V14 rows it equals '
    'tms.destination.id, unless the backfill merged the destination with a same-code origin, in '
    'which case it is that origin id.';

-- ---------------------------------------------------------------------------
-- 4. Backfill: one canonical location per (company_id, code)
-- ---------------------------------------------------------------------------
-- A FULL OUTER JOIN on (company_id, code). uq_origin_company_code and
-- uq_destination_company_code guarantee at most one row per side per key, so the join produces
-- exactly one output row per group and needs no aggregation.
--
--   origin only        -> location with role ORIGIN
--   destination only   -> location with role SHIP_TO
--   both               -> ONE location with BOTH roles  <- the duplicate-DC case this fixes
--
-- Field precedence is documented per column below and in ADR_LOCATION_MODEL section 4. No
-- legacy row loses an attribute: origin and destination keep everything they had, and only
-- gain location_id in section 5.
WITH candidate AS (
    SELECT
        COALESCE(o.id, d.id)                                        AS id,
        COALESCE(o.company_id, d.company_id)                        AS company_id,
        COALESCE(o.code, d.code)                                    AS code,
        -- The dispatch classification wins when a place does both: an operator who registered
        -- the same code on both sides described the site's function on the origin side.
        COALESCE(o.name, d.name)                                    AS name,
        COALESCE(o.origin_type, d.destination_type)                 AS location_type,
        -- The destination shape is the richer address, so it wins where both exist.
        COALESCE(d.address, o.address)                              AS address,
        d.address_reference                                         AS address_reference,
        d.district                                                  AS district,
        d.province                                                  AS province,
        d.department                                                AS department,
        COALESCE(d.country, 'PE')                                   AS country,
        -- Only the origin carries one; a destination-only place inherits its company's.
        COALESCE(o.time_zone, c.time_zone, 'UTC')                   AS time_zone,
        -- ck_origin_coordinates_pair / ck_destination_coordinates_pair make both-or-neither
        -- true on each side, so coalescing the two columns independently cannot produce a
        -- latitude from one place and a longitude from another.
        COALESCE(o.latitude, d.latitude)                            AS latitude,
        COALESCE(o.longitude, d.longitude)                          AS longitude,
        d.zone_id                                                   AS zone_id,
        COALESCE(d.service_time_minutes, 0)                         AS service_time_minutes,
        COALESCE(o.external_reference, d.external_reference)         AS external_reference,
        -- Active if EITHER side is: a merge must never make a row disappear from a list it
        -- was already in.
        (COALESCE(o.active, false) OR COALESCE(d.active, false))    AS active,
        -- LEAST and GREATEST ignore NULLs, so a one-sided group simply uses its own timestamp.
        LEAST(o.created_at, d.created_at)                           AS created_at,
        GREATEST(o.updated_at, d.updated_at)                        AS updated_at,
        COALESCE(o.created_by, d.created_by)                        AS created_by,
        COALESCE(o.updated_by, d.updated_by)                        AS updated_by
    FROM tms.origin o
    FULL OUTER JOIN tms.destination d
        ON d.company_id = o.company_id AND d.code = o.code
    LEFT JOIN tms.company c ON c.id = COALESCE(o.company_id, d.company_id)
),
deduplicated AS (
    -- Neither V6 nor V7 constrained external_reference, so the same value may appear on several
    -- rows of one company. uq_location_external does constrain it, so the first occurrence -
    -- deterministically ordered by code then id - keeps the canonical identity and the rest are
    -- left NULL. The legacy rows keep their own values either way (ADR_LOCATION_MODEL D-4).
    SELECT
        candidate.*,
        CASE
            WHEN candidate.external_reference IS NULL THEN NULL
            WHEN row_number() OVER (
                     PARTITION BY candidate.company_id, candidate.external_reference
                     ORDER BY candidate.code, candidate.id) = 1
                THEN candidate.external_reference
            ELSE NULL
        END AS canonical_external_reference
    FROM candidate
)
INSERT INTO tms.location (
    id, company_id, code, name, location_type, address, address_reference,
    district, province, department, country, time_zone, latitude, longitude,
    zone_id, service_time_minutes, external_system, external_reference, active,
    created_at, updated_at, created_by, updated_by)
SELECT
    id, company_id, code, name, location_type, address, address_reference,
    district, province, department, country, time_zone, latitude, longitude,
    zone_id, service_time_minutes,
    -- ck_location_external_pair_complete requires a system whenever a reference is present.
    -- V6/V7 had no such column, so the honest value for pre-V14 data is the name of the step
    -- that produced it, not an invented integration name.
    CASE WHEN canonical_external_reference IS NULL THEN NULL ELSE 'LEGACY' END,
    canonical_external_reference,
    active, created_at, updated_at, created_by, updated_by
FROM deduplicated;

-- Roles: ORIGIN for every origin, SHIP_TO for every destination, attached to the location the
-- backfill above produced for that (company_id, code) group.
INSERT INTO tms.location_role (location_id, role)
SELECT o.id, 'ORIGIN'
FROM tms.origin o;

INSERT INTO tms.location_role (location_id, role)
SELECT COALESCE(o.id, d.id), 'SHIP_TO'
FROM tms.destination d
LEFT JOIN tms.origin o ON o.company_id = d.company_id AND o.code = d.code;

-- ---------------------------------------------------------------------------
-- 5. Link the legacy rows to their canonical location
-- ---------------------------------------------------------------------------
-- An origin's location always carries the origin's own id (section 4 chose it that way), so
-- this is an identity assignment and the composite FK is satisfied trivially.
UPDATE tms.origin SET location_id = id;

-- A destination points at the same-code origin's location when one exists, otherwise at its
-- own. Correlated rather than joined so that a destination with no same-code origin is still
-- updated instead of being skipped by an inner join.
UPDATE tms.destination d
SET location_id = COALESCE(
    (SELECT o.id FROM tms.origin o WHERE o.company_id = d.company_id AND o.code = d.code),
    d.id);

-- ---------------------------------------------------------------------------
-- 6. Tenant Row Level Security (ADR-005)
-- ---------------------------------------------------------------------------
-- V13's ALTER DEFAULT PRIVILEGES already grants DML on tables Flyway creates later, so these
-- grants are belt and braces - but an explicit grant is cheaper to read than a default
-- privilege three migrations away, and a missing grant surfaces as an empty list rather than
-- as an error, which is the worst failure mode there is.
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.location TO tms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.location_role TO tms_app;

-- Company-scoped, FOR ALL, with both USING and WITH CHECK: a USING-only policy would let a
-- caller insert a row into another company and then simply not see it - a silent write leak.
CREATE POLICY p_tenant_company_scope ON tms.location
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- Scoped through the parent, like tms.frequency_weekly_rule: a role row is reachable exactly
-- when its location is, and the location's own policy is what decides that.
CREATE POLICY p_tenant_company_scope ON tms.location_role
    FOR ALL TO tms_app
    USING (EXISTS (SELECT 1 FROM tms.location l
                    WHERE l.id = location_role.location_id
                      AND l.company_id = tms.current_company_id()))
    WITH CHECK (EXISTS (SELECT 1 FROM tms.location l
                         WHERE l.id = location_role.location_id
                           AND l.company_id = tms.current_company_id()));

-- ---------------------------------------------------------------------------
-- 7. Authorization catalogue
-- ---------------------------------------------------------------------------
-- V3 seeded the permissions the modules of the time needed and V5 completed the catalogue for
-- planning and monitoring. V3/V5 are applied and immutable (ADR-002), so the Locations module's
-- permissions arrive here, with explicit grants: V3's CROSS JOIN only covered the permissions
-- that existed when it ran.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('masterdata.location', 'read',
     'View locations: stores, warehouses, plants, hubs and delivery points'),
    ('masterdata.location', 'manage',
     'Create, update and deactivate locations');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code IN ('masterdata.location:read', 'masterdata.location:manage');

-- PLANNER and VIEWER read the operational catalogue; neither administers master data, exactly
-- as they already relate to origins and destinations in V3.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('PLANNER', 'VIEWER')
  AND p.code = 'masterdata.location:read';
