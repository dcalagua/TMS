-- TMS by EBIM - V23: Location becomes the single physical master.
--
-- Reasoning and alternatives: docs/architecture/ADR_LOCATION_MODEL.md (updated by this step)
-- and docs/domain/LOCATIONS.md.
--
-- V14 introduced tms.location as the canonical place and left three pieces of compatibility
-- debt behind, deliberately and on the record (D-1, D-2, D-6): three tables still described a
-- place, two write paths were kept in step by application code, and every consumer - route,
-- route_stop, transport_order, planning_run, trip_stop - still pointed at tms.origin /
-- tms.destination. This migration retires all three.
--
-- What it does, in order:
--
--   1. adopts any legacy row that never got a canonical location (only reachable through
--      hand-seeded SQL; a no-op on a database that has only ever been written through the API);
--   2. reduces tms.location_role to the two roles that are actually operational uses -
--      ORIGIN and DESTINATION - and drops the five that were duplicating location_type;
--   3. repoints the six foreign keys at tms.location, tenant composite FK included;
--   4. freezes tms.origin and tms.destination: the application role loses every write
--      privilege on them, so "not a source of truth" stops being a convention and becomes a
--      grant.
--
-- What it deliberately does NOT do: drop tms.origin or tms.destination. The rule this work
-- follows is not to remove legacy until the data migration can be shown green, and no
-- Testcontainers test can execute on the development host (BASELINE E-1). The tables stay,
-- readable and unwritable, as the recovery path for a V14 merge that turns out to be wrong
-- (D-5). Dropping them is a separate migration, after this one has run against a real database.
--
-- Column names: route.origin_id and friends keep their names while changing what they
-- reference. Renaming them to origin_location_id would rename the JSON fields of the inbound
-- integration contract v1 (tms/integration/api) that external systems already call, which is a
-- breaking change bought for a synonym. The COMMENT ON COLUMN statements below are what make
-- the new target unambiguous to anyone reading the schema.

-- ---------------------------------------------------------------------------
-- Resolve the PostGIS extension schema (MIGRATION_STRATEGY.md section 4)
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
-- 1. Adopt legacy rows that have no canonical location
-- ---------------------------------------------------------------------------
-- V14 backfilled and linked every row that existed, and every write path since - the Locations
-- API through LocationCompatibilityProjector, the Origins/Destinations APIs through their own
-- upward sync - sets location_id. So an unlinked legacy row can only come from direct SQL, and
-- on a real database sections 1a-1d change nothing. They exist because section 3 repoints
-- through location_id, and a repoint that silently skipped a row would leave a dangling
-- foreign key instead of failing.

-- 1a. An unlinked legacy row whose code already names a location in its own company adopts it,
--     unless a sibling of the same side already claims that location (uq_origin_location /
--     uq_destination_location are one-to-one). At most one origin per (company, code) exists,
--     so this cannot pick a winner arbitrarily.
UPDATE tms.origin o
SET location_id = l.id
FROM tms.location l
WHERE o.location_id IS NULL
  AND l.company_id = o.company_id
  AND l.code = o.code
  AND NOT EXISTS (SELECT 1 FROM tms.origin claimed WHERE claimed.location_id = l.id);

-- 1b/1c. Still unlinked -> create the canonical row. The legacy id becomes the location id, the
-- same identity V14's backfill established, so the repoint in section 3 stays a no-op for these
-- rows too. The code is the legacy code when free in tms.location, otherwise the code truncated
-- and suffixed with the first 8 hex digits of the row's own id: deterministic (the id does not
-- change between replays), inside ck_location_code_shape, and inside the 32-character limit.
--
-- external_reference is deliberately left NULL on the adopted row rather than carried over:
-- uq_location_external is unique per company and V6/V7 never constrained it, so carrying it
-- could fail the migration on data the legacy tables considered valid. The legacy row keeps its
-- own value, and an operator reconciles it in the Locations screen - the same treatment V14
-- gave the references that lost its deduplication race (D-4).
INSERT INTO tms.location (
    id, company_id, code, name, location_type, address, time_zone,
    latitude, longitude, active, created_at, updated_at, created_by, updated_by)
SELECT
    o.id,
    o.company_id,
    CASE
        WHEN EXISTS (SELECT 1 FROM tms.location l
                      WHERE l.company_id = o.company_id AND l.code = o.code)
            THEN left(o.code, 23) || '-' || upper(left(replace(o.id::text, '-', ''), 8))
        ELSE o.code
    END,
    o.name, o.origin_type, o.address, o.time_zone,
    o.latitude, o.longitude, o.active, o.created_at, o.updated_at, o.created_by, o.updated_by
FROM tms.origin o
WHERE o.location_id IS NULL;

UPDATE tms.origin SET location_id = id WHERE location_id IS NULL;

UPDATE tms.destination d
SET location_id = l.id
FROM tms.location l
WHERE d.location_id IS NULL
  AND l.company_id = d.company_id
  AND l.code = d.code
  AND NOT EXISTS (SELECT 1 FROM tms.destination claimed WHERE claimed.location_id = l.id);

INSERT INTO tms.location (
    id, company_id, code, name, location_type, address, address_reference,
    district, province, department, country, zone_id, service_time_minutes,
    latitude, longitude, active, created_at, updated_at, created_by, updated_by)
SELECT
    d.id,
    d.company_id,
    CASE
        WHEN EXISTS (SELECT 1 FROM tms.location l
                      WHERE l.company_id = d.company_id AND l.code = d.code)
            THEN left(d.code, 23) || '-' || upper(left(replace(d.id::text, '-', ''), 8))
        ELSE d.code
    END,
    d.name, d.destination_type, d.address, d.address_reference,
    d.district, d.province, d.department, d.country, d.zone_id, d.service_time_minutes,
    d.latitude, d.longitude, d.active, d.created_at, d.updated_at, d.created_by, d.updated_by
FROM tms.destination d
WHERE d.location_id IS NULL;

UPDATE tms.destination SET location_id = id WHERE location_id IS NULL;

-- ---------------------------------------------------------------------------
-- 2. The role vocabulary becomes what a role actually is
-- ---------------------------------------------------------------------------
-- V14 implemented seven roles: ORIGIN, SHIP_TO, STORE, DC, PLANT, HUB, OTHER. Its own ADR
-- section 2 recorded that only the first two carry behaviour and that the rest are
-- classification - which is the job tms.location.location_type already does, with a richer
-- vocabulary and exactly one value per place. Keeping both meant the UI offered "Tipo: Tienda"
-- and "Roles: Tienda" side by side, which is how an operator learns to distrust a master.
--
-- After this migration a role answers one question only: how may this place be used in a
-- movement. It is the shape the domain always meant:
--
--     Ubicacion (que es)  --> location_type
--     Uso operacional     --> location_role  in (ORIGIN, DESTINATION)
--
-- SHIP_TO becomes DESTINATION. The two words meant the same thing; DESTINATION is the one the
-- rest of this product uses - transport_order.destination_id, route_stop, trip_stop - and a
-- master-data vocabulary that needs a translation table to reach the tables that use it is a
-- vocabulary with a bug in it.
ALTER TABLE tms.location_role DROP CONSTRAINT ck_location_role_role;

UPDATE tms.location_role SET role = 'DESTINATION' WHERE role = 'SHIP_TO';

-- The classification roles are removed, not remapped: every one of them names a value that
-- location_type carries already, so there is nothing to preserve. A location whose only role
-- was a classification one is left with none - which is the honest outcome, because such a
-- location never had a projection and could never be selected as either end of a movement.
DELETE FROM tms.location_role WHERE role NOT IN ('ORIGIN', 'DESTINATION');

-- Restore, from the projections that exist and are usable, any behavioural role a location is
-- missing. Its real work is the rows adopted in section 1, which arrived with no roles at all;
-- for everything else it is a no-op, because V14 created these rows and the projector keeps
-- them in step. Restricted to active projections on purpose: an inactive projection is how the
-- projector records "the operator removed this role", and resurrecting it would undo that.
INSERT INTO tms.location_role (location_id, role)
SELECT DISTINCT o.location_id, 'ORIGIN'
FROM tms.origin o
WHERE o.location_id IS NOT NULL AND o.active
ON CONFLICT (location_id, role) DO NOTHING;

INSERT INTO tms.location_role (location_id, role)
SELECT DISTINCT d.location_id, 'DESTINATION'
FROM tms.destination d
WHERE d.location_id IS NOT NULL AND d.active
ON CONFLICT (location_id, role) DO NOTHING;

ALTER TABLE tms.location_role
    ADD CONSTRAINT ck_location_role_role CHECK (role IN ('ORIGIN', 'DESTINATION'));

COMMENT ON TABLE tms.location_role IS
    'The operational uses one location may be put to: ORIGIN (it may ship) and DESTINATION (it '
    'may receive). What the place IS - store, distribution centre, plant, hub - is '
    'tms.location.location_type, never a role. Extending this vocabulary (PICKUP, CROSS_DOCK, '
    'RETURN_POINT) means widening ck_location_role_role in a new migration, and is deferred '
    'until a functional requirement asks for it.';

COMMENT ON COLUMN tms.location_role.role IS
    'ORIGIN or DESTINATION. A location may hold both: the same store is the destination of a '
    'delivery and the origin of the return.';

-- ---------------------------------------------------------------------------
-- 3. Repoint every consumer at tms.location
-- ---------------------------------------------------------------------------
-- Each block is the same three steps: carry the value across through location_id, drop the two
-- foreign keys that pointed at the legacy table, and recreate them - simple plus composite -
-- against tms.location. The composite (id, company_id) FK is the one that makes a cross-company
-- reference impossible at the database level, and tms.location has carried
-- uq_location_id_company since V14 precisely so it could be its target.
--
-- The mapping is injective: uq_origin_location and uq_destination_location allow at most one
-- legacy row per location, so no two distinct references can collapse onto one id. That is what
-- guarantees uq_route_stop_route_destination, uq_trip_stop_trip_destination and
-- uq_planning_run_company_origin_date cannot newly collide.
--
-- For every row that existed before V14 the UPDATE writes the value that is already there
-- (V14 gave those locations the legacy id), so these statements are measurable no-ops on
-- historical data and only move rows created afterwards.

-- 3.1 tms.route.origin_id
UPDATE tms.route r
SET origin_id = o.location_id
FROM tms.origin o
WHERE o.id = r.origin_id AND o.location_id IS DISTINCT FROM r.origin_id;

ALTER TABLE tms.route
    DROP CONSTRAINT fk_route_origin,
    DROP CONSTRAINT fk_route_origin_company,
    ADD CONSTRAINT fk_route_origin FOREIGN KEY (origin_id)
        REFERENCES tms.location (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_route_origin_company FOREIGN KEY (origin_id, company_id)
        REFERENCES tms.location (id, company_id);

COMMENT ON COLUMN tms.route.origin_id IS
    'The tms.location this route departs from. The location must hold the ORIGIN role, which '
    'the application enforces (RouteService); the database enforces existence and tenant.';

-- 3.2 tms.route_stop.destination_id
UPDATE tms.route_stop s
SET destination_id = d.location_id
FROM tms.destination d
WHERE d.id = s.destination_id AND d.location_id IS DISTINCT FROM s.destination_id;

ALTER TABLE tms.route_stop
    DROP CONSTRAINT fk_route_stop_destination,
    DROP CONSTRAINT fk_route_stop_destination_company,
    ADD CONSTRAINT fk_route_stop_destination FOREIGN KEY (destination_id)
        REFERENCES tms.location (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_route_stop_destination_company FOREIGN KEY (destination_id, company_id)
        REFERENCES tms.location (id, company_id);

COMMENT ON COLUMN tms.route_stop.destination_id IS
    'The tms.location served by this stop. The location must hold the DESTINATION role.';

-- 3.3 tms.transport_order.origin_id and .destination_id
--
-- Note what becomes possible here and did not exist before: a single location that holds both
-- roles can now be one end of an order while another is the other end, and the two columns may
-- even hold the same id if a movement starts and ends at one place. No constraint forbids it,
-- and none should - that is the model this whole migration is for.
UPDATE tms.transport_order t
SET origin_id = o.location_id
FROM tms.origin o
WHERE o.id = t.origin_id AND o.location_id IS DISTINCT FROM t.origin_id;

UPDATE tms.transport_order t
SET destination_id = d.location_id
FROM tms.destination d
WHERE d.id = t.destination_id AND d.location_id IS DISTINCT FROM t.destination_id;

ALTER TABLE tms.transport_order
    DROP CONSTRAINT fk_transport_order_origin,
    DROP CONSTRAINT fk_transport_order_origin_company,
    DROP CONSTRAINT fk_transport_order_destination,
    DROP CONSTRAINT fk_transport_order_destination_company,
    ADD CONSTRAINT fk_transport_order_origin FOREIGN KEY (origin_id)
        REFERENCES tms.location (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_transport_order_origin_company FOREIGN KEY (origin_id, company_id)
        REFERENCES tms.location (id, company_id),
    ADD CONSTRAINT fk_transport_order_destination FOREIGN KEY (destination_id)
        REFERENCES tms.location (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_transport_order_destination_company FOREIGN KEY (destination_id, company_id)
        REFERENCES tms.location (id, company_id);

COMMENT ON COLUMN tms.transport_order.origin_id IS
    'The tms.location the order ships from; must hold the ORIGIN role (OrderService).';
COMMENT ON COLUMN tms.transport_order.destination_id IS
    'The tms.location the order ships to; must hold the DESTINATION role (OrderService). May '
    'equal origin_id only if a movement genuinely starts and ends at the same place.';

-- 3.4 tms.planning_run.origin_id
UPDATE tms.planning_run p
SET origin_id = o.location_id
FROM tms.origin o
WHERE o.id = p.origin_id AND o.location_id IS DISTINCT FROM p.origin_id;

ALTER TABLE tms.planning_run
    DROP CONSTRAINT fk_planning_run_origin,
    DROP CONSTRAINT fk_planning_run_origin_company,
    ADD CONSTRAINT fk_planning_run_origin FOREIGN KEY (origin_id)
        REFERENCES tms.location (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_planning_run_origin_company FOREIGN KEY (origin_id, company_id)
        REFERENCES tms.location (id, company_id);

COMMENT ON COLUMN tms.planning_run.origin_id IS
    'The tms.location this planning session dispatches from; must hold the ORIGIN role.';

-- 3.5 tms.trip_stop.destination_id
UPDATE tms.trip_stop s
SET destination_id = d.location_id
FROM tms.destination d
WHERE d.id = s.destination_id AND d.location_id IS DISTINCT FROM s.destination_id;

ALTER TABLE tms.trip_stop
    DROP CONSTRAINT fk_trip_stop_destination,
    DROP CONSTRAINT fk_trip_stop_destination_company,
    ADD CONSTRAINT fk_trip_stop_destination FOREIGN KEY (destination_id)
        REFERENCES tms.location (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_trip_stop_destination_company FOREIGN KEY (destination_id, company_id)
        REFERENCES tms.location (id, company_id);

COMMENT ON COLUMN tms.trip_stop.destination_id IS
    'The tms.location this stop serves - the canonical place, not a per-role duplicate of it.';

-- ---------------------------------------------------------------------------
-- 4. Freeze the legacy tables
-- ---------------------------------------------------------------------------
-- Nothing in the application reads or writes tms.origin / tms.destination after this step:
-- the entities, repositories, services, controllers and the compatibility projector are gone,
-- and the Origins/Destinations screens are role-filtered views of tms.location.
--
-- Removing the write privileges is what turns that from a claim about the current source tree
-- into a property of the database. SELECT is kept: these rows are the recovery path for a V14
-- merge that united two places that were genuinely different (D-5), and an operator resolving
-- that needs to be able to read them.
REVOKE INSERT, UPDATE, DELETE ON tms.origin FROM tms_app;
REVOKE INSERT, UPDATE, DELETE ON tms.destination FROM tms_app;

COMMENT ON TABLE tms.origin IS
    'DEPRECATED since V23 - frozen historical projection, read-only for tms_app and referenced '
    'by nothing. tms.location holding the ORIGIN role is the master. Kept, not dropped, as the '
    'recovery path for a V14 code merge that turns out to have united two different places; '
    'dropping it is a separate migration once V23 has been verified against a real database.';
COMMENT ON TABLE tms.destination IS
    'DEPRECATED since V23 - see tms.origin. tms.location holding the DESTINATION role is the '
    'master.';

-- ---------------------------------------------------------------------------
-- 5. Authorization catalogue
-- ---------------------------------------------------------------------------
-- masterdata.origin:* and masterdata.destination:* (V3) now name endpoints that no longer
-- exist. The rows stay - tms.role_permission references them, and deleting seeded catalogue
-- data to save two rows trades a real risk for nothing - but their description says what they
-- are, so an administrator reading the permission matrix is not told about a screen that is
-- really a filter over another one.
UPDATE tms.permission
SET description = description || ' (DEPRECATED since V23: Origins is a role-filtered view of '
                              || 'Locations and is governed by masterdata.location)'
WHERE resource IN ('masterdata.origin', 'masterdata.destination')
  AND description NOT LIKE '%DEPRECATED since V23%';
