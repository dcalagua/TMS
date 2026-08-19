-- TMS by EBIM - V8 master data: routes.
--
-- Follows V6/V7's shape unchanged (docs/database/DATA_MODEL.md section 9): company_id NOT NULL
-- with an FK to tms.company and an index leading with it, actor columns, RLS enabled in this
-- same migration, normalized-code CHECKs.
--
-- The permissions this module needs (masterdata.route:*) already exist in V3 - nothing to add
-- there.
--
-- A master tms.route is a reusable planned corridor: a company-scoped origin, an ordered list
-- of destination stops, and optional reference distance/duration/frequency/zone. It is
-- deliberately NOT the same thing as a future calculated Trip route (deferred - OR-Tools/route
-- optimization is out of scope per the repository's "deferred by decision" list): this table
-- has no geometry, no live position, no optimizer output - just a named, reusable sequence a
-- planner can point a Trip at later.

-- ---------------------------------------------------------------------------
-- Composite-FK tenant guarantees this migration needs on tables V6/V7 already created.
-- ---------------------------------------------------------------------------
-- tms.zone already carries uq_zone_id_company (V7). tms.origin and tms.destination do not yet
-- have one because nothing referenced them cross-table before this step - route.origin_id and
-- route_stop.destination_id are the first such references, so both get the same idiom
-- tms.membership (V2) and destination.zone_id (V7) use.
ALTER TABLE tms.origin ADD CONSTRAINT uq_origin_id_company UNIQUE (id, company_id);
ALTER TABLE tms.destination ADD CONSTRAINT uq_destination_id_company UNIQUE (id, company_id);
ALTER TABLE tms.frequency ADD CONSTRAINT uq_frequency_id_company UNIQUE (id, company_id);

-- ---------------------------------------------------------------------------
-- tms.route - a reusable planned corridor/service sequence
-- ---------------------------------------------------------------------------
CREATE TABLE tms.route (
    id                       uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id               uuid          NOT NULL,
    code                     text          NOT NULL,
    name                     text          NOT NULL,
    origin_id                uuid          NOT NULL,
    zone_id                  uuid,
    frequency_id             uuid,
    -- Optional planning reference values, not a live GPS/telematics measurement (deferred by
    -- decision) - just what a planner expects this corridor to cost in distance/time.
    reference_distance_km    numeric(8,2),
    reference_duration_minutes integer,
    active                   boolean       NOT NULL DEFAULT true,
    created_at               timestamptz   NOT NULL DEFAULT now(),
    updated_at               timestamptz   NOT NULL DEFAULT now(),
    created_by               uuid,
    updated_by               uuid,
    CONSTRAINT pk_route PRIMARY KEY (id),
    CONSTRAINT fk_route_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- Target for route_stop's composite FK below - same idiom as uq_zone_id_company (V7).
    CONSTRAINT uq_route_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_route_company_code UNIQUE (company_id, code),
    CONSTRAINT ck_route_code_normalized CHECK (code = upper(btrim(code))),
    CONSTRAINT ck_route_code_shape CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_route_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_route_reference_distance_nonnegative
        CHECK (reference_distance_km IS NULL OR reference_distance_km >= 0),
    CONSTRAINT ck_route_reference_duration_nonnegative
        CHECK (reference_duration_minutes IS NULL OR reference_duration_minutes >= 0),
    CONSTRAINT fk_route_origin FOREIGN KEY (origin_id)
        REFERENCES tms.origin (id) ON DELETE RESTRICT,
    -- MATCH SIMPLE (default): origin_id is NOT NULL so this is always checked - the route's
    -- origin must belong to the route's own company, not just be a real origin somewhere.
    CONSTRAINT fk_route_origin_company FOREIGN KEY (origin_id, company_id)
        REFERENCES tms.origin (id, company_id),
    CONSTRAINT fk_route_zone FOREIGN KEY (zone_id)
        REFERENCES tms.zone (id) ON DELETE RESTRICT,
    CONSTRAINT fk_route_zone_company FOREIGN KEY (zone_id, company_id)
        REFERENCES tms.zone (id, company_id),
    CONSTRAINT fk_route_frequency FOREIGN KEY (frequency_id)
        REFERENCES tms.frequency (id) ON DELETE RESTRICT,
    CONSTRAINT fk_route_frequency_company FOREIGN KEY (frequency_id, company_id)
        REFERENCES tms.frequency (id, company_id),
    CONSTRAINT fk_route_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_route_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_route_company ON tms.route (company_id);
CREATE INDEX ix_route_origin ON tms.route (origin_id);
CREATE INDEX ix_route_zone ON tms.route (zone_id) WHERE zone_id IS NOT NULL;
CREATE INDEX ix_route_frequency ON tms.route (frequency_id) WHERE frequency_id IS NOT NULL;

COMMENT ON TABLE tms.route IS
    'A reusable planned corridor/service sequence: company-scoped origin plus an ordered list '
    'of destination stops in tms.route_stop. Distinct from a future calculated Trip route - no '
    'geometry, no live position, no optimizer output (OR-Tools is deferred by decision).';
COMMENT ON COLUMN tms.route.reference_distance_km IS
    'Planner-entered reference value, not a measured/optimized distance. Optional, nonnegative.';
COMMENT ON COLUMN tms.route.reference_duration_minutes IS
    'Planner-entered reference value, not a measured/optimized duration. Optional, nonnegative.';

CREATE TRIGGER tr_route_set_updated_at
    BEFORE UPDATE ON tms.route
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.route ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- tms.route_stop - one ordered destination stop of a route
-- ---------------------------------------------------------------------------
-- Unlike tms.frequency_weekly_rule (a pure child with no company_id of its own), route_stop
-- carries its own company_id: it has a cross-table reference to tms.destination (another
-- company-scoped table), which DATA_MODEL.md section 9 rule 6 requires a composite-FK tenant
-- guarantee for. That guarantee needs company_id on the referencing row, exactly like
-- destination.zone_id (V7) needed it on tms.destination.
CREATE TABLE tms.route_stop (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    route_id       uuid        NOT NULL,
    company_id     uuid        NOT NULL,
    destination_id uuid        NOT NULL,
    -- Server-assigned from submitted array order (RouteService), starting at 1, always
    -- contiguous - never a client-supplied gap-prone number. See RouteService.replaceStops.
    sequence       integer     NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid,
    CONSTRAINT pk_route_stop PRIMARY KEY (id),
    CONSTRAINT fk_route_stop_route FOREIGN KEY (route_id)
        REFERENCES tms.route (id) ON DELETE CASCADE,
    CONSTRAINT fk_route_stop_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- The stop's company must match its own route's company - a database guarantee, not just
    -- an application-level assumption that Route and RouteStop are always saved together.
    CONSTRAINT fk_route_stop_route_company FOREIGN KEY (route_id, company_id)
        REFERENCES tms.route (id, company_id),
    CONSTRAINT fk_route_stop_destination FOREIGN KEY (destination_id)
        REFERENCES tms.destination (id) ON DELETE RESTRICT,
    CONSTRAINT fk_route_stop_destination_company FOREIGN KEY (destination_id, company_id)
        REFERENCES tms.destination (id, company_id),
    CONSTRAINT ck_route_stop_sequence_positive CHECK (sequence >= 1),
    -- A destination may appear at most once per route (documented decision: a master route is
    -- a corridor of distinct stops in V1; a legitimate "visit twice" itinerary is Trip-level
    -- planning, not a reusable master). Revisit if a concrete round-trip use case appears.
    CONSTRAINT uq_route_stop_route_destination UNIQUE (route_id, destination_id),
    -- DEFERRABLE INITIALLY DEFERRED: RouteService.replaceStops reorders stops in place (an
    -- update-only diff, matching Frequency.replaceWeeklyRules - see the handoff in
    -- docs/overnight/06_DESTINATIONS_FREQUENCIES.md section 8.3), which for a swap (stop A and
    -- B trade positions) necessarily passes through a transient duplicate (route_id, sequence)
    -- pair mid-transaction. Deferring the check to COMMIT is the standard PostgreSQL idiom for
    -- a reorderable unique-position list; the final, committed state is always unique because
    -- RouteService assigns sequence from array order (1..N, no gaps, no client-supplied
    -- duplicates) before touching any entity.
    CONSTRAINT uq_route_stop_route_sequence UNIQUE (route_id, sequence) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_route_stop_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_route_stop_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_route_stop_route ON tms.route_stop (route_id);
CREATE INDEX ix_route_stop_destination ON tms.route_stop (destination_id);

COMMENT ON TABLE tms.route_stop IS
    'One ordered destination stop of a tms.route (migration V8). Cascades from route on '
    'delete. company_id is denormalized from the parent route so the destination reference can '
    'carry the same composite-FK tenant guarantee as destination.zone_id (V7) - see rule 6 in '
    'docs/database/DATA_MODEL.md section 9.';
COMMENT ON CONSTRAINT uq_route_stop_route_sequence ON tms.route_stop IS
    'Deferred to COMMIT so RouteService.replaceStops can reorder stops in place (update-only '
    'diff) without a transient duplicate position failing mid-transaction.';

CREATE TRIGGER tr_route_stop_set_updated_at
    BEFORE UPDATE ON tms.route_stop
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.route_stop ENABLE ROW LEVEL SECURITY;
