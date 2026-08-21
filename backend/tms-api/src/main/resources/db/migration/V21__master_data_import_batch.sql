-- TMS by EBIM - V21 master-data import audit (Job 09: Import Center).
--
-- One shared audit table for the four master-data bulk imports (Locations, Carriers, Vehicle
-- Types, Vehicles) discriminated by entity_type, rather than four near-identical tables. This
-- deliberately does NOT touch tms.order_import_batch (V17): that table predates this migration,
-- V1-V20 are immutable, and Orders keeps its own table rather than being retrofitted onto this
-- one - same reasoning as everywhere else in this schema that an applied migration is never
-- edited to fit a later idea.
--
-- Same shape as tms.order_import_batch: what file was uploaded, by whom, and how many rows it
-- produced. Written only in the same transaction as the rows it created - a dry run writes
-- nothing here, and a rolled-back import leaves no batch row either.

CREATE TABLE tms.import_batch (
    id             uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id     uuid          NOT NULL,
    entity_type    text          NOT NULL,
    file_name      text          NOT NULL,
    file_format    text          NOT NULL,
    file_sha256    text          NOT NULL,
    row_count      integer       NOT NULL,
    created_count  integer       NOT NULL,
    skipped_count  integer       NOT NULL,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     uuid,
    CONSTRAINT pk_import_batch PRIMARY KEY (id),
    CONSTRAINT fk_import_batch_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT ck_import_batch_entity_type CHECK (
        entity_type IN ('LOCATION', 'CARRIER', 'VEHICLE_TYPE', 'VEHICLE')),
    CONSTRAINT ck_import_batch_file_name_not_blank CHECK (btrim(file_name) <> ''),
    CONSTRAINT ck_import_batch_format CHECK (file_format IN ('XLSX', 'CSV')),
    CONSTRAINT ck_import_batch_sha256 CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_import_batch_counts_nonnegative CHECK (
        row_count >= 0 AND created_count >= 0 AND skipped_count >= 0
    ),
    CONSTRAINT fk_import_batch_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_import_batch_company_entity_type_created
    ON tms.import_batch (company_id, entity_type, created_at DESC);

COMMENT ON TABLE tms.import_batch IS
    'One applied bulk master-data import: Location, Carrier, VehicleType or Vehicle, per '
    'entity_type. Written only by the corresponding *ImportService.apply, in the same '
    'transaction as the rows it created. A dry run never writes here.';

ALTER TABLE tms.import_batch ENABLE ROW LEVEL SECURITY;

-- The tenant policy every business table added after V13 carries (ADR-005).
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.import_batch TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.import_batch
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
