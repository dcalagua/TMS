-- TMS by EBIM - V26 drivers: the fleet master for the person who drives, and the trip column
-- that says which one is driving this shipment.
--
-- Design and the full rule set: docs/domain/DRIVERS_V1.md.
--
-- Until V25 a trip named a vehicle and a carrier and stopped there, which is enough to plan a
-- load and not enough to run one: nobody could say who was at the wheel, so "call the driver of
-- SH-00000142" was not a question this database could answer, and a licence that expired last
-- month could not stop a dispatch it should have stopped.
--
-- Two independent pieces, in the order they depend on each other:
--   1. tms.driver - a company-scoped person master, optionally attached to a carrier, following
--      V9's fleet shape unchanged (code/company uniqueness, actor columns, RLS, normalized-code
--      CHECKs, the composite-FK tenant guarantee of rule 6).
--   2. tms.trip.driver_id - nullable, tenant-guaranteed by a composite FK, plus the one-active-
--      trip-per-driver-per-day index that is the exact sibling of V16's vehicle rule.

-- ---------------------------------------------------------------------------
-- 1. tms.driver
-- ---------------------------------------------------------------------------
CREATE TABLE tms.driver (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id         uuid        NOT NULL,
    code               text        NOT NULL,
    -- Two name columns and not one "full_name": a dispatch list sorts and searches by surname,
    -- and splitting a single field back apart is guesswork the moment a driver has two of each
    -- (common across this product's target markets). The concatenation a screen shows is
    -- DriverView.fullName's job - derived, never stored, so the two can never disagree.
    first_name         text        NOT NULL,
    last_name          text        NOT NULL,
    -- The same flexible free-text pair as tms.carrier.tax_id_type/tax_id_value (V9), for the
    -- same reason: identity-document types vary by country (DNI in Peru, CURP in Mexico, CPF in
    -- Brazil, ...) and TMS must not hardcode a per-country catalogue. Mandatory here, unlike a
    -- carrier's contact fields: a driver is a person a shipment is legally handed to, and
    -- "which document did they show" is the whole point of recording them.
    document_type      text        NOT NULL,
    document_number    text        NOT NULL,
    phone              text,
    license_number     text        NOT NULL,
    -- Free text for the same reason document_type is: licence classes are national catalogues
    -- (A-IIb here, C+E there). Optional, because a company that does not track classes should
    -- not be forced to invent one.
    license_category   text,
    -- The last day the licence is valid, inclusive. NULLABLE on purpose, and that nullability is
    -- a rule rather than a shrug: a company migrating a spreadsheet of drivers rarely has the
    -- expiry for all of them, and refusing to store the driver until it does would push the data
    -- back into the spreadsheet this master exists to replace. What follows from NULL is stated
    -- once, in TripService/TripExecutionService: an unknown expiry never blocks an assignment,
    -- because "we do not know" is not the same claim as "it has expired".
    license_expires_on date,
    -- Optional, exactly like tms.vehicle.carrier_id and for the same reason: a company may
    -- employ its own drivers, in which case there is no third-party carrier to point at.
    carrier_id         uuid,
    active             boolean     NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    created_by         uuid,
    updated_by         uuid,
    CONSTRAINT pk_driver PRIMARY KEY (id),
    CONSTRAINT fk_driver_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- Target for tms.trip's composite FK below - same idiom as uq_carrier_id_company (V9).
    CONSTRAINT uq_driver_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_driver_company_code UNIQUE (company_id, code),
    -- One person is registered once per company. Scoped to the company, never installation-wide:
    -- the same real driver working for two tenant companies is two rows (ADR-003), and a global
    -- constraint would leak whether another tenant has registered a given document number - the
    -- covert channel uq_vehicle_company_license_plate's comment (V9) rejects at length.
    CONSTRAINT uq_driver_company_document UNIQUE (company_id, document_type, document_number),
    CONSTRAINT uq_driver_company_license_number UNIQUE (company_id, license_number),
    CONSTRAINT ck_driver_code_normalized CHECK (code = upper(btrim(code))),
    CONSTRAINT ck_driver_code_shape CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_driver_first_name_not_blank CHECK (btrim(first_name) <> ''),
    CONSTRAINT ck_driver_last_name_not_blank CHECK (btrim(last_name) <> ''),
    -- Normalization enforced, not assumed (docs/database/DATA_MODEL.md section 3.8): both halves
    -- of the document pair and the licence number are stored trimmed and upper case, so the
    -- uniqueness constraints above actually catch "dni"/"DNI" and "q-1234"/"Q-1234" as the same
    -- value instead of admitting a second row for the same person.
    CONSTRAINT ck_driver_document_type_not_blank CHECK (btrim(document_type) <> ''),
    CONSTRAINT ck_driver_document_type_normalized CHECK (document_type = upper(btrim(document_type))),
    CONSTRAINT ck_driver_document_number_not_blank CHECK (btrim(document_number) <> ''),
    CONSTRAINT ck_driver_document_number_normalized CHECK (document_number = upper(btrim(document_number))),
    CONSTRAINT ck_driver_phone_not_blank CHECK (phone IS NULL OR btrim(phone) <> ''),
    CONSTRAINT ck_driver_license_number_not_blank CHECK (btrim(license_number) <> ''),
    CONSTRAINT ck_driver_license_number_normalized CHECK (license_number = upper(btrim(license_number))),
    CONSTRAINT ck_driver_license_category_not_blank
        CHECK (license_category IS NULL OR btrim(license_category) <> ''),
    CONSTRAINT ck_driver_license_category_normalized
        CHECK (license_category IS NULL OR license_category = upper(btrim(license_category))),
    CONSTRAINT fk_driver_carrier FOREIGN KEY (carrier_id)
        REFERENCES tms.carrier (id) ON DELETE RESTRICT,
    -- MATCH SIMPLE (default): satisfied when carrier_id IS NULL, enforced otherwise - the same
    -- idiom as fk_vehicle_carrier_company (V9). A driver's carrier must belong to the driver's
    -- own company even though the two FK columns are separate.
    CONSTRAINT fk_driver_carrier_company FOREIGN KEY (carrier_id, company_id)
        REFERENCES tms.carrier (id, company_id),
    CONSTRAINT fk_driver_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_driver_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_driver_company ON tms.driver (company_id);
CREATE INDEX ix_driver_carrier ON tms.driver (carrier_id) WHERE carrier_id IS NOT NULL;

-- The "whose licence is about to run out" question, which the Drivers screen asks on every load
-- and a planner asks before every assignment. Partial on the two conditions that make the row
-- interesting at all: an inactive driver is not going to be assigned, and one with no recorded
-- expiry can never match a date range.
CREATE INDEX ix_driver_company_license_expiry
    ON tms.driver (company_id, license_expires_on)
    WHERE active AND license_expires_on IS NOT NULL;

COMMENT ON TABLE tms.driver IS
    'A person who drives: company-scoped, optionally employed through a carrier, always carrying '
    'an identity document and a licence number. Deactivated, never deleted - a trip that was run '
    'by this driver must keep resolving their name after they leave the fleet.';
COMMENT ON COLUMN tms.driver.license_expires_on IS
    'The last day the licence is valid, inclusive. NULL means "not recorded", which never blocks '
    'an assignment - see TripService.updateDriver. An expired licence does.';
COMMENT ON COLUMN tms.driver.carrier_id IS
    'The carrier this driver works for, or NULL for a company''s own staff. When both a trip''s '
    'carrier and its driver''s carrier are known they must agree - TripService enforces that; '
    'the database cannot, because the trip''s carrier is copied from its vehicle and a composite '
    'FK cannot express "equal when both are non-null".';

CREATE TRIGGER tr_driver_set_updated_at
    BEFORE UPDATE ON tms.driver
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.driver ENABLE ROW LEVEL SECURITY;

-- The tenant policy every business table added after V13 carries (ADR-005).
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.driver TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.driver
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 2. tms.trip.driver_id
-- ---------------------------------------------------------------------------
-- Nullable, and deliberately not made mandatory for a confirmed or dispatched trip. Companies
-- differ on when the driver is known: some assign one with the vehicle days ahead, others hand
-- the manifest to whoever is at the gate. Adding driver_id to ck_trip_confirmed_is_complete
-- (V25) would encode the first practice as the only legal one and make TMS unusable for the
-- second, so the rule V1 ships is the weaker true one - *if* a driver is named, they must be
-- one this company may send out.
ALTER TABLE tms.trip ADD COLUMN driver_id uuid;

COMMENT ON COLUMN tms.trip.driver_id IS
    'The driver planned to run this shipment, or NULL when none has been named yet. Not required '
    'by any state: see this migration''s comment. Never rewritten by a later master edit - the '
    'row keeps pointing at the driver, and the driver row keeps their name, which is why no '
    'name/document snapshot is stored here (see "Deliberately NOT here" below).';

ALTER TABLE tms.trip ADD CONSTRAINT fk_trip_driver FOREIGN KEY (driver_id)
    REFERENCES tms.driver (id) ON DELETE RESTRICT;

-- Rule 6, the tenant guarantee: a trip's driver must belong to the trip's own company. MATCH
-- SIMPLE, so a NULL driver_id satisfies it - identical in shape to fk_vehicle_carrier_company.
ALTER TABLE tms.trip ADD CONSTRAINT fk_trip_driver_company FOREIGN KEY (driver_id, company_id)
    REFERENCES tms.driver (id, company_id);

-- At most one non-cancelled trip per driver per company per planning date - the exact sibling of
-- uq_trip_vehicle_active_planning_date (V16), partial on the same two conditions and for the
-- same reasons. It is if anything the harder physical constraint of the two: a truck could in
-- principle be re-crewed and sent out twice in a day, a person cannot be in two cabs at once.
CREATE UNIQUE INDEX uq_trip_driver_active_planning_date
    ON tms.trip (company_id, driver_id, planning_date)
    WHERE status <> 'CANCELLED' AND driver_id IS NOT NULL;

COMMENT ON INDEX tms.uq_trip_driver_active_planning_date IS
    'One active trip per driver per planning date. The concurrency backstop for '
    'TripService.updateDriver''s own pre-check, the same relationship '
    'uq_trip_vehicle_active_planning_date (V16) has with its service-level check.';

-- The Trips screen filters by driver ("where is Ana today"), which is a different access path
-- from the unique index above: that one is partial on non-cancelled, and a dispatcher looking up
-- a driver's day wants the cancelled ones too.
CREATE INDEX ix_trip_company_driver_planning_date
    ON tms.trip (company_id, driver_id, planning_date DESC)
    WHERE driver_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. Audit vocabulary
-- ---------------------------------------------------------------------------
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_aggregate_type;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_aggregate_type CHECK (aggregate_type IN (
    'LOCATION', 'CARRIER', 'VEHICLE', 'DRIVER', 'TRANSPORT_ORDER', 'TRIP', 'PLANNING_RUN',
    'INTEGRATION_CLIENT', 'MASTER_DATA_IMPORT_BATCH', 'ORDER_IMPORT_BATCH', 'SHIPMENT'));

-- DRIVER_CHANGE is to driver_id what VEHICLE_CHANGE (V22) is to vehicle_id: a distinct action
-- rather than a plain UPDATE, because "who was driving this shipment and when did that change"
-- is a question an operations manager asks by itself, and a generic UPDATE row would bury it
-- among edits to the plan.
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
    'CREATE', 'UPDATE', 'ACTIVATE', 'DEACTIVATE', 'ASSIGN_ORDER', 'REMOVE_ORDER', 'MOVE_ORDER',
    'VEHICLE_CHANGE', 'DRIVER_CHANGE', 'CONFIRM', 'CANCEL', 'CREDENTIAL_CREATE',
    'CREDENTIAL_ROTATE', 'CREDENTIAL_REVOKE', 'AUTO_PLAN', 'IMPORT_EXECUTED', 'SHIPMENT_CONFIRMED',
    'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED', 'SHIPMENT_CANCELLED'));

-- ---------------------------------------------------------------------------
-- 4. fleet.driver:read / fleet.driver:manage
-- ---------------------------------------------------------------------------
-- A resource of its own, not a widening of fleet.vehicle:*: a driver master holds personal data
-- (name, identity document, phone) that a vehicle master does not, and an installation that
-- wants the fleet clerk to maintain trucks without opening the personnel file must be able to
-- say so. Same granularity argument Permission's class comment makes.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('fleet.driver', 'read',   'View drivers and their licence status'),
    ('fleet.driver', 'manage', 'Create, update and deactivate drivers');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code IN ('fleet.driver:read', 'fleet.driver:manage');

-- PLANNER and VIEWER read the fleet catalogue and administer none of it, exactly as they already
-- relate to carriers, vehicle types and vehicles in V3. A planner needs to *pick* a driver, which
-- is a read of this master plus planning.trip:manage on the trip - not a write here.
INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('PLANNER', 'VIEWER')
  AND p.code = 'fleet.driver:read';

-- ---------------------------------------------------------------------------
-- Deliberately NOT here
-- ---------------------------------------------------------------------------
--   * No driver name/document/licence snapshot on tms.trip. The historical question - "who drove
--     SH-00000142" - is already answered exactly by driver_id, because a driver is deactivated
--     and never deleted (ON DELETE RESTRICT above makes deletion impossible while any trip
--     points at them) and their row keeps their name. A snapshot would only preserve the name as
--     it read on the day against a later *correction* of a misspelling - which is a change every
--     other master in this schema deliberately lets flow through to history, and three copied
--     columns that can silently drift is a high price for the opposite choice. Capacity is
--     snapshotted (V11) because it is a *validation input*: the plan was proved to fit those
--     numbers. A driver's surname proves nothing.
--   * No driver-hours, rest periods or shift calendar. Availability here is `active` plus the
--     one-trip-per-day index, the same "current-state flag, not a scheduling calendar" line
--     tms.vehicle.availability_status draws (V9). A real hours-of-service model needs legal
--     rules per country and per-stop execution times TMS does not record yet (V25's own closing
--     note) - inventing columns for it now would be the shape of the feature with none of it.
--   * No licence-document storage or scanned attachments. Supabase Storage is deferred by
--     decision (CLAUDE.md); license_number plus an expiry date is the part planning can act on.
--   * No second driver / co-driver. One driver per trip is what the double-booking index and the
--     assignment rules are written against; a crew model is a join table, not a nullable column,
--     and nothing in V1 asks for one.
