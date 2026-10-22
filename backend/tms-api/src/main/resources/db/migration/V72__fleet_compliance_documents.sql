-- ============================================================================
-- V72 - Fleet compliance documents with expiry (backlog item 7)
-- ============================================================================
-- Until now the only document TMS knew about was the driver's licence (V26,
-- tms.driver.license_expires_on). A truck without a valid registration, technical inspection or
-- mandatory insurance cannot legally leave in Ecuador or Peru, and a shipper audits its carriers on
-- exactly those papers. Three tables, all owned by `fleet`:
--
--   tms.fleet_document             one paper of one vehicle or one driver: number, issuer, dates
--   tms.fleet_document_policy      per company and document type: mandatory? block or warn when expired?
--   tms.fleet_compliance_settings  per company: how many days ahead an expiry is "about to expire"
--
-- The STATUS of a document (valid / expiring / expired / unrecorded) is derived at read time and never
-- stored: a status column would be wrong every morning at midnight (the rule DriverLicenseStatus set).
--
-- The rule V26 wrote for the licence holds here too: NOT KNOWING IS NOT EXPIRED. A document with no
-- expiry date, or a mandatory one that was never recorded, warns and never blocks. Only an expiry date
-- that has passed can block, and only when the company's policy for that type says BLOCK.
--
-- No policy row means the type's built-in default (FleetDocumentType in Java): nothing mandatory, so a
-- company that never opens the screen sees no new warning, and the four legal papers of a vehicle block
-- when they are on file and expired. No business rows are seeded. Three permissions are, and the audit
-- vocabulary grows by two aggregate types.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. The documents
-- ---------------------------------------------------------------------------
-- Two typed subject columns and a CHECK that the one matching subject_type is set - the shape V42 chose
-- for tms.resource_unavailability, because a polymorphic subject_id cannot carry a foreign key.
CREATE TABLE tms.fleet_document (
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id       uuid        NOT NULL,
    subject_type     text        NOT NULL,
    vehicle_id       uuid,
    driver_id        uuid,
    document_type    text        NOT NULL,
    document_number  text,
    issuer           text,
    issued_on        date,
    -- The last day the document is valid, INCLUSIVE (the licence's rule). Null = not recorded, which
    -- is never read as expired.
    expires_on       date,
    notes            text,
    active           boolean     NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_by       uuid,
    version          bigint      NOT NULL DEFAULT 0,

    CONSTRAINT pk_fleet_document PRIMARY KEY (id),
    CONSTRAINT fk_fleet_document_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_fleet_document_vehicle_company FOREIGN KEY (vehicle_id, company_id)
        REFERENCES tms.vehicle (id, company_id),
    CONSTRAINT fk_fleet_document_driver_company FOREIGN KEY (driver_id, company_id)
        REFERENCES tms.driver (id, company_id),
    CONSTRAINT fk_fleet_document_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_fleet_document_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,

    CONSTRAINT ck_fleet_document_subject_type CHECK (subject_type IN ('VEHICLE', 'DRIVER')),
    CONSTRAINT ck_fleet_document_one_subject CHECK (
        (subject_type = 'VEHICLE' AND vehicle_id IS NOT NULL AND driver_id IS NULL)
        OR (subject_type = 'DRIVER' AND driver_id IS NOT NULL AND vehicle_id IS NULL)),
    CONSTRAINT ck_fleet_document_document_type CHECK (document_type IN (
        'REGISTRATION', 'TECHNICAL_INSPECTION', 'MANDATORY_INSURANCE', 'OPERATING_PERMIT',
        'CARGO_INSURANCE', 'VEHICLE_OTHER',
        'MEDICAL_CERTIFICATE', 'DEFENSIVE_DRIVING_COURSE', 'DRIVER_OTHER')),
    -- A vehicle's paper is never a driver's, and the other way round.
    CONSTRAINT ck_fleet_document_type_matches_subject CHECK (
        (subject_type = 'VEHICLE' AND document_type IN ('REGISTRATION', 'TECHNICAL_INSPECTION',
            'MANDATORY_INSURANCE', 'OPERATING_PERMIT', 'CARGO_INSURANCE', 'VEHICLE_OTHER'))
        OR (subject_type = 'DRIVER' AND document_type IN ('MEDICAL_CERTIFICATE',
            'DEFENSIVE_DRIVING_COURSE', 'DRIVER_OTHER'))),
    CONSTRAINT ck_fleet_document_dates CHECK (
        issued_on IS NULL OR expires_on IS NULL OR expires_on >= issued_on),
    CONSTRAINT ck_fleet_document_number CHECK (
        document_number IS NULL OR (btrim(document_number) <> '' AND length(document_number) <= 60)),
    CONSTRAINT ck_fleet_document_issuer CHECK (
        issuer IS NULL OR (btrim(issuer) <> '' AND length(issuer) <= 200)),
    CONSTRAINT ck_fleet_document_notes CHECK (
        notes IS NULL OR (btrim(notes) <> '' AND length(notes) <= 500))
);

CREATE INDEX ix_fleet_document_company_expiry
    ON tms.fleet_document (company_id, expires_on) WHERE active;
CREATE INDEX ix_fleet_document_vehicle ON tms.fleet_document (vehicle_id) WHERE vehicle_id IS NOT NULL;
CREATE INDEX ix_fleet_document_driver ON tms.fleet_document (driver_id) WHERE driver_id IS NOT NULL;

COMMENT ON TABLE tms.fleet_document IS
    'A compliance paper of one vehicle or one driver (V72): registration, technical inspection, '
    'mandatory insurance, operating permit, cargo policy, medical certificate, defensive driving. '
    'Its status is derived at read time from expires_on; null expiry is unrecorded, never expired.';
COMMENT ON COLUMN tms.fleet_document.expires_on IS
    'Last valid day, inclusive. Null = not recorded: warns when the type is mandatory, never blocks.';

-- ---------------------------------------------------------------------------
-- 2. The policy per company and document type
-- ---------------------------------------------------------------------------
-- Sparse: a type without a row follows its built-in default (FleetDocumentType.defaultMandatory /
-- defaultEnforcement). Mandatory = a missing or undated document warns. Enforcement = what an EXPIRED
-- document does to a planning or a work day: BLOCK refuses it, WARN only says so.
CREATE TABLE tms.fleet_document_policy (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id     uuid        NOT NULL,
    document_type  text        NOT NULL,
    mandatory      boolean     NOT NULL,
    enforcement    text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid,
    version        bigint      NOT NULL DEFAULT 0,

    CONSTRAINT pk_fleet_document_policy PRIMARY KEY (id),
    CONSTRAINT uq_fleet_document_policy_type UNIQUE (company_id, document_type),
    CONSTRAINT fk_fleet_document_policy_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_fleet_document_policy_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_fleet_document_policy_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_fleet_document_policy_document_type CHECK (document_type IN (
        'REGISTRATION', 'TECHNICAL_INSPECTION', 'MANDATORY_INSURANCE', 'OPERATING_PERMIT',
        'CARGO_INSURANCE', 'VEHICLE_OTHER',
        'MEDICAL_CERTIFICATE', 'DEFENSIVE_DRIVING_COURSE', 'DRIVER_OTHER')),
    CONSTRAINT ck_fleet_document_policy_enforcement CHECK (enforcement IN ('BLOCK', 'WARN'))
);

COMMENT ON TABLE tms.fleet_document_policy IS
    'Per company and document type (V72): whether the document is mandatory and whether an expired one '
    'blocks (BLOCK) or only warns (WARN). No row = the type''s built-in default.';

-- ---------------------------------------------------------------------------
-- 3. The alert window per company
-- ---------------------------------------------------------------------------
-- One row per company, created the first time the window is saved; no row = 30 days, the licence's
-- warning window (DriverLicenseStatus.EXPIRY_WARNING_DAYS).
CREATE TABLE tms.fleet_compliance_settings (
    company_id         uuid        NOT NULL,
    expiry_alert_days  integer     NOT NULL DEFAULT 30,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    updated_by         uuid,
    version            bigint      NOT NULL DEFAULT 0,

    CONSTRAINT pk_fleet_compliance_settings PRIMARY KEY (company_id),
    CONSTRAINT fk_fleet_compliance_settings_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_fleet_compliance_settings_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_fleet_compliance_settings_alert_days CHECK (expiry_alert_days BETWEEN 1 AND 365)
);

COMMENT ON TABLE tms.fleet_compliance_settings IS
    'How many days ahead a fleet document counts as about to expire, per company (V72). No row = 30.';

-- ---------------------------------------------------------------------------
-- 4. Tenant isolation (ADR-005) and least privilege
-- ---------------------------------------------------------------------------
ALTER TABLE tms.fleet_document ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.fleet_document_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.fleet_compliance_settings ENABLE ROW LEVEL SECURITY;

-- V13 gives the four verbs by default: the REVOKEs are what restrict. A document is deactivated, never
-- deleted - it is the record of what a truck carried on the day it was audited.
REVOKE DELETE ON tms.fleet_document FROM tms_app;
GRANT SELECT, INSERT, UPDATE ON tms.fleet_document TO tms_app;
REVOKE DELETE ON tms.fleet_document_policy FROM tms_app;
GRANT SELECT, INSERT, UPDATE ON tms.fleet_document_policy TO tms_app;
REVOKE DELETE ON tms.fleet_compliance_settings FROM tms_app;
GRANT SELECT, INSERT, UPDATE ON tms.fleet_compliance_settings TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.fleet_document
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.fleet_document_policy
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
CREATE POLICY p_tenant_company_scope ON tms.fleet_compliance_settings
    FOR ALL TO tms_app USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- A carrier session (ADR-018) never reads the shipper's compliance file, not even its own trucks':
-- the portal reports documents of a trip, it does not audit the fleet. RESTRICTIVE, ANDed with the
-- tenant policy; an office transaction (NULL tms.current_carrier_id()) is left alone.
DO $$
DECLARE
    internal text;
BEGIN
    FOREACH internal IN ARRAY ARRAY['fleet_document', 'fleet_document_policy', 'fleet_compliance_settings']
    LOOP
        EXECUTE format(
            'CREATE POLICY p_carrier_scope ON tms.%I AS RESTRICTIVE FOR ALL TO tms_app
                 USING (tms.current_carrier_id() IS NULL)
                 WITH CHECK (tms.current_carrier_id() IS NULL)', internal);
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------------
-- 5. Permissions (V46 shape)
-- ---------------------------------------------------------------------------
-- Their own resource rather than a widening of fleet.vehicle:* or fleet.driver:*: the clerk who keeps
-- the papers is often not the one who edits a truck's capacity, and the policy - what blocks a trip -
-- is an administrator's decision.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('fleet.document',        'read',   'View fleet compliance documents and their expiry status'),
    ('fleet.document',        'manage', 'Record, update and deactivate fleet compliance documents'),
    ('fleet.document_policy', 'manage', 'Decide which fleet documents are mandatory and which block when expired');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN', 'PLANNER', 'VIEWER')
  AND p.code = 'fleet.document:read';

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN')
  AND p.code IN ('fleet.document:manage', 'fleet.document_policy:manage');

-- ---------------------------------------------------------------------------
-- 6. Audit vocabulary (the whole list, restated)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_aggregate_type;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_aggregate_type CHECK (aggregate_type IN (
    'APPOINTMENT',
    'APP_USER',
    'CARRIER',
    'CARRIER_INVOICE',
    'COMPANY',
    'DRIVER',
    'FREIGHT_ACCRUAL',
    'FREIGHT_CLAIM',
    'GL_ACCOUNT_MAPPING',
    'INTEGRATION_CLIENT',
    'LOCATION',
    'LOCATION_RESOURCE',
    'MASTER_DATA_IMPORT_BATCH',
    'MATERIAL_PROFILE',
    'MEMBERSHIP',
    'ORDER_IMPORT_BATCH',
    'OWN_FLEET_COST_PROFILE',
    'PLANNING_RUN',
    'RATE_CARD',
    'SHIPMENT',
    'TOLERANCE_POLICY',
    'TRANSPORT_ORDER',
    'TRIP',
    'TRIP_COST',
    'VEHICLE',
    'WEBHOOK_SUBSCRIPTION',
    'FLEET_DOCUMENT',
    'FLEET_DOCUMENT_POLICY'
));
