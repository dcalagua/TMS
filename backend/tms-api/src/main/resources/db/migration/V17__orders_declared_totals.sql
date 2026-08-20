-- TMS by EBIM - V17 orders: declared totals and the totals precedence rule.
--
-- V10 persisted total_weight_kg / total_volume_m3 / total_pallets as a snapshot recomputed from
-- the lines by TransportOrder.applyLines, and that is still what happens whenever an order has
-- lines. What V10 had no answer for is the case an inbound integration produces constantly: a
-- shipper sends "one order, 1,200 kg, 2 pallets" and no line detail at all. Under V10 such an
-- order could only be stored with totals of zero, which then fails the completeness check in
-- OrderService.markReadyForPlanning and can never be planned - the order is real, the capacity
-- figures are real, and the schema had nowhere to put them.
--
-- So this migration adds the *declared* figures - what the sender or the operator asserts - next
-- to the *effective* totals the rest of the system plans with, plus a column recording which of
-- the two produced the effective values. See docs/domain/ORDER_TOTALS_V1.md for the rule in
-- prose and OrderTotals for the single implementation of it.
--
--   lines present -> effective totals are CALCULATED from the lines. Declared values are still
--                    stored, and are cross-checked against the calculated ones within a
--                    tolerance; a contradiction is rejected rather than silently overwritten.
--   no lines      -> effective totals are DECLARED (the declared values, or zero where absent).
--
-- The browser never sends a total_* value under either branch; it may only ever send declared_*.
--
-- What this migration deliberately does NOT add is a database-level check that totals_source
-- agrees with whether the order has lines. See the section "Why the lines/source agreement is
-- not a database constraint" below - the short version is that it is a cross-table rule whose
-- only available implementation is a deferred constraint trigger, and that trigger would cost a
-- count(*) per order row at every commit while making every raw-SQL fixture and future data
-- migration carry knowledge of it. OrderTotals owns the rule; the columns below only record its
-- outcome.

-- ---------------------------------------------------------------------------
-- Declared (asserted) capacity figures
-- ---------------------------------------------------------------------------
-- Nullable, unlike their total_* siblings, and the distinction carries meaning: NULL is "the
-- sender said nothing about pallets", 0 is "the sender said zero pallets". The same
-- != null rather than > 0 discipline tms.transport_order_line.pallet_quantity already uses.
ALTER TABLE tms.transport_order
    ADD COLUMN declared_weight_kg numeric(14,3),
    ADD COLUMN declared_volume_m3 numeric(14,4),
    ADD COLUMN declared_pallets   numeric(12,2),
    -- DEFAULT 'DECLARED', not 'CALCULATED', and the choice is about who writes without going
    -- through OrderTotals. The application always sets this column explicitly, so the default
    -- only ever applies to a raw INSERT - a test fixture, a future data migration - and those
    -- overwhelmingly insert a header with totals and no lines, which is exactly DECLARED. The
    -- backfill below then corrects every pre-V17 row that does have lines.
    ADD COLUMN totals_source      text NOT NULL DEFAULT 'DECLARED';

ALTER TABLE tms.transport_order
    ADD CONSTRAINT ck_transport_order_declared_nonnegative CHECK (
        (declared_weight_kg IS NULL OR declared_weight_kg >= 0)
        AND (declared_volume_m3 IS NULL OR declared_volume_m3 >= 0)
        AND (declared_pallets IS NULL OR declared_pallets >= 0)
    ),
    ADD CONSTRAINT ck_transport_order_totals_source
        CHECK (totals_source IN ('CALCULATED', 'DECLARED'));

COMMENT ON COLUMN tms.transport_order.declared_weight_kg IS
    'What the sender or the operator asserts the order weighs, independent of the lines. NULL '
    'means "not stated", which is not the same as 0. Never the value planning reads - that is '
    'total_weight_kg. See docs/domain/ORDER_TOTALS_V1.md.';
COMMENT ON COLUMN tms.transport_order.totals_source IS
    'Which input produced total_weight_kg/total_volume_m3/total_pallets: CALCULATED (summed from '
    'transport_order_line) or DECLARED (copied from the declared_* columns because the order has '
    'no lines). Provenance, not an input: written only by TransportOrder.applyLines from what '
    'OrderTotals decided. See docs/domain/ORDER_TOTALS_V1.md.';

-- ---------------------------------------------------------------------------
-- Backfill: which strategy produced each existing row's totals
-- ---------------------------------------------------------------------------
-- Every pre-V17 row got its totals from TransportOrder.applyLines, which summed the lines. An
-- order that has lines is therefore CALCULATED; one that has none had zero totals and is, in the
-- new vocabulary, DECLARED with nothing declared. The column default already covers the second
-- case, so only the first needs writing.
UPDATE tms.transport_order o
   SET totals_source = 'CALCULATED'
 WHERE EXISTS (SELECT 1 FROM tms.transport_order_line l WHERE l.order_id = o.id);

-- ---------------------------------------------------------------------------
-- Why the lines/source agreement is not a database constraint
-- ---------------------------------------------------------------------------
-- "CALCULATED requires at least one line" is a statement about two tables, and a CHECK
-- constraint may only see one row of one table. The only thing PostgreSQL offers is a constraint
-- trigger deferred to COMMIT - deferred because TransportOrder.applyLines replaces the line set
-- and updates the header in one flush whose intermediate states are legitimately inconsistent,
-- the same reason uq_transport_order_line_order_line_number is DEFERRABLE.
--
-- That trigger was written, and then removed, for two reasons:
--
--   1. Cost. It runs one count(*) over transport_order_line per order row at every commit. The
--      bulk import (OrderImportService) commits up to 1,000 orders in a single transaction, so
--      the check would add 1,000 queries to the one operation in the system that most needs to
--      stay fast - to verify a column that no query filters on and no foreign key depends on.
--   2. Blast radius. Constraint triggers fire for the schema owner too, so every integration
--      fixture, every seed and every future data migration that inserts an order with raw SQL
--      would have to know this rule and set the column accordingly, or fail at COMMIT with an
--      error pointing at a trigger rather than at the insert. That is the same trade-off
--      ADR_LOCATION_MODEL section 3 made when it left tms.origin.location_id nullable.
--
-- What is enforced in the database is what the database can check cheaply and locally: the
-- value domain (ck_transport_order_totals_source) and non-negativity
-- (ck_transport_order_declared_nonnegative). The agreement between the lines and the source is
-- an application invariant with exactly one writer - TransportOrder.applyLines, which calls
-- OrderTotals.resolve and stores what it returns - and OrderTotalsTest proves that writer.

-- ---------------------------------------------------------------------------
-- Import batch audit
-- ---------------------------------------------------------------------------
-- Every applied bulk import records what was uploaded, by whom, and what it produced. The step
-- brief's "auditable" and "no partial silent success" both need this: the report the API returns
-- lives only in the caller's browser, so without a persisted row there would be no way to answer
-- "who loaded these 300 orders and from which file" a week later.
--
-- A dry run writes nothing here - it writes nothing anywhere, which is the point of a dry run.
CREATE TABLE tms.order_import_batch (
    id                 uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id         uuid          NOT NULL,
    -- The external_source every order in the file was created under: the first half of the
    -- (source, reference) idempotency pair uq_transport_order_external indexes.
    external_source    text          NOT NULL,
    file_name          text          NOT NULL,
    -- 'XLSX' or 'CSV' - what the parser actually read, not what the extension claimed.
    file_format        text          NOT NULL,
    -- SHA-256 of the uploaded bytes. Not a uniqueness constraint: re-uploading the same file is
    -- a legitimate, and thanks to the external-reference idempotency a harmless, operation - the
    -- second run simply skips everything. It is recorded so that "was this exact file already
    -- loaded?" is answerable without keeping the file itself.
    file_sha256        text          NOT NULL,
    row_count          integer       NOT NULL,
    created_count      integer       NOT NULL,
    skipped_count      integer       NOT NULL,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    created_by         uuid,
    CONSTRAINT pk_order_import_batch PRIMARY KEY (id),
    CONSTRAINT fk_order_import_batch_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT ck_order_import_batch_source_not_blank CHECK (btrim(external_source) <> ''),
    CONSTRAINT ck_order_import_batch_file_name_not_blank CHECK (btrim(file_name) <> ''),
    CONSTRAINT ck_order_import_batch_format CHECK (file_format IN ('XLSX', 'CSV')),
    CONSTRAINT ck_order_import_batch_sha256 CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_order_import_batch_counts_nonnegative CHECK (
        row_count >= 0 AND created_count >= 0 AND skipped_count >= 0
    ),
    CONSTRAINT fk_order_import_batch_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_order_import_batch_company ON tms.order_import_batch (company_id);
CREATE INDEX ix_order_import_batch_company_created
    ON tms.order_import_batch (company_id, created_at DESC);

COMMENT ON TABLE tms.order_import_batch IS
    'One applied bulk order import (V17). Written only by OrderImportService.apply, in the same '
    'transaction as the orders it created, so a rolled-back import leaves no batch row either. '
    'A dry run never writes here.';

ALTER TABLE tms.order_import_batch ENABLE ROW LEVEL SECURITY;

-- The tenant policy every business table added after V13 carries (ADR-005): the non-owner
-- tms_app role sees only rows of the transaction's current tenant, so a query that forgets its
-- company predicate stops being a cross-tenant leak. Copied in shape from V14's location policy.
GRANT SELECT, INSERT, UPDATE, DELETE ON tms.order_import_batch TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.order_import_batch
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());
