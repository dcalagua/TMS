-- TMS by EBIM - V10 orders: the first operational (non-master) module.
--
-- Follows V6/V7/V8/V9's shape unchanged (docs/database/DATA_MODEL.md section 11): company_id
-- NOT NULL with an FK and a leading index, actor columns, RLS enabled in this same migration,
-- and the composite-FK tenant guarantee (rule 6) for every reference into another company-scoped
-- table (origin, destination).
--
-- The permissions this module needs (orders.order:read/manage) already exist in V3 and are
-- already granted to every role that needs them - nothing to add there.
--
-- Table names avoid the reserved word ORDER: tms.transport_order / tms.transport_order_line,
-- per the step brief's own suggestion.

-- ---------------------------------------------------------------------------
-- Order number sequence.
-- ---------------------------------------------------------------------------
-- transport_order.order_number is the "stable internal number" the brief asks for: a
-- system-generated, immutable identifier, distinct from every master's user-supplied `code`.
-- OrderService.generateOrderNumber() reads this sequence once per create and formats
-- 'TO-' || the zero-padded value; the sequence itself is the only thing the database owns, so
-- a raw SQL insert (a future data migration, a test fixture) can still obtain a collision-free
-- number without depending on application code.
CREATE SEQUENCE tms.transport_order_number_seq AS bigint INCREMENT BY 1 START WITH 1 NO CYCLE;

-- ---------------------------------------------------------------------------
-- tms.transport_order - the order header
-- ---------------------------------------------------------------------------
CREATE TABLE tms.transport_order (
    id                       uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id               uuid          NOT NULL,
    order_number             text          NOT NULL,
    -- Idempotency/external-reference pair (see the CHECK/index below): who sent this order and
    -- what they call it. Both optional - a manually created order has neither.
    external_source          text,
    external_reference       text,
    origin_id                uuid          NOT NULL,
    destination_id           uuid          NOT NULL,
    -- Free-text customer fields, not a foreign key into a CRM table that does not exist
    -- (the step brief explicitly asks not to duplicate one).
    customer_name            text,
    customer_reference       text,
    service_date             date          NOT NULL,
    priority                 text          NOT NULL DEFAULT 'NORMAL',
    requested_window_start   time,
    requested_window_end     time,
    status                   text          NOT NULL DEFAULT 'NOT_READY',
    cancel_reason            text,
    -- Transactional snapshot, not live-recomputed on read: see the migration-level comment on
    -- ck_transport_order_totals_nonnegative below and docs/domain/ORDER_LIFECYCLE_V1.md section
    -- "Totals strategy" for why persisting these is safe. OrderService is the only writer of
    -- transport_order_line, and TransportOrder.applyLines recomputes all three together, in the
    -- same transaction as every line change - there is no code path that changes lines without
    -- also updating the header snapshot in the same flush.
    total_weight_kg          numeric(14,3) NOT NULL DEFAULT 0,
    total_volume_m3          numeric(14,4) NOT NULL DEFAULT 0,
    total_pallets            numeric(12,2) NOT NULL DEFAULT 0,
    -- Optimistic locking: the first module that needs it (step brief, "optimistic
    -- locking/versioning where concurrent edits matter"). OrderService checks the client's
    -- last-seen version explicitly (catches a stale form resubmission, not only a true
    -- in-flight race) and JPA's own @Version check over this column is the backstop for the
    -- narrow case of two transactions racing to flush at the same instant.
    version                  bigint        NOT NULL DEFAULT 0,
    created_at               timestamptz   NOT NULL DEFAULT now(),
    updated_at               timestamptz   NOT NULL DEFAULT now(),
    created_by               uuid,
    updated_by               uuid,
    CONSTRAINT pk_transport_order PRIMARY KEY (id),
    CONSTRAINT fk_transport_order_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- Target for a future Planning trip-assignment table's composite FK - see the handoff note
    -- at the end of this file and DATA_MODEL.md section 9's rule 6 idiom.
    CONSTRAINT uq_transport_order_id_company UNIQUE (id, company_id),
    -- order_number is generated from one global sequence, never chosen or guessed by a user -
    -- unlike a master's `code` or a vehicle's license plate (DATA_MODEL.md rule 9), there is no
    -- real-world value a caller could "attempt to register" to probe another company's data, so
    -- a global (not company-scoped) uniqueness constraint carries none of rule 9's cross-tenant
    -- leak risk. See docs/database/DATA_MODEL.md section 12.1.
    CONSTRAINT uq_transport_order_number UNIQUE (order_number),
    CONSTRAINT ck_transport_order_number_not_blank CHECK (btrim(order_number) <> ''),
    CONSTRAINT ck_transport_order_external_source_not_blank
        CHECK (external_source IS NULL OR btrim(external_source) <> ''),
    CONSTRAINT ck_transport_order_external_reference_not_blank
        CHECK (external_reference IS NULL OR btrim(external_reference) <> ''),
    -- The idempotency pair must be fully specified or fully absent: a reference with no source
    -- cannot be safely deduplicated (see the partial unique index below and DATA_MODEL.md
    -- section 12.2).
    CONSTRAINT ck_transport_order_external_pair_complete
        CHECK (external_reference IS NULL OR external_source IS NOT NULL),
    CONSTRAINT fk_transport_order_origin FOREIGN KEY (origin_id)
        REFERENCES tms.origin (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transport_order_origin_company FOREIGN KEY (origin_id, company_id)
        REFERENCES tms.origin (id, company_id),
    CONSTRAINT fk_transport_order_destination FOREIGN KEY (destination_id)
        REFERENCES tms.destination (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transport_order_destination_company FOREIGN KEY (destination_id, company_id)
        REFERENCES tms.destination (id, company_id),
    CONSTRAINT ck_transport_order_customer_name_not_blank
        CHECK (customer_name IS NULL OR btrim(customer_name) <> ''),
    CONSTRAINT ck_transport_order_customer_reference_not_blank
        CHECK (customer_reference IS NULL OR btrim(customer_reference) <> ''),
    CONSTRAINT ck_transport_order_priority
        CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    -- Both present or both absent, and a genuine window when both are given - see
    -- docs/domain/ORDER_LIFECYCLE_V1.md for why this is enforced here and mirrored in
    -- OrderService.validateTimeWindow (defense in depth, same split as
    -- OriginService.validateCoordinatePair).
    CONSTRAINT ck_transport_order_window_pair
        CHECK ((requested_window_start IS NULL) = (requested_window_end IS NULL)),
    CONSTRAINT ck_transport_order_window_order
        CHECK (requested_window_start IS NULL OR requested_window_start < requested_window_end),
    CONSTRAINT ck_transport_order_status
        CHECK (status IN ('NOT_READY', 'READY_FOR_PLANNING', 'PLANNED', 'CANCELLED')),
    CONSTRAINT ck_transport_order_cancel_reason_not_blank
        CHECK (cancel_reason IS NULL OR btrim(cancel_reason) <> ''),
    CONSTRAINT ck_transport_order_cancel_reason_requires_cancelled
        CHECK (cancel_reason IS NULL OR status = 'CANCELLED'),
    CONSTRAINT ck_transport_order_totals_nonnegative CHECK (
        total_weight_kg >= 0 AND total_volume_m3 >= 0 AND total_pallets >= 0
    ),
    CONSTRAINT fk_transport_order_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transport_order_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_transport_order_company ON tms.transport_order (company_id);
CREATE INDEX ix_transport_order_company_status ON tms.transport_order (company_id, status);
CREATE INDEX ix_transport_order_company_service_date ON tms.transport_order (company_id, service_date);
CREATE INDEX ix_transport_order_origin ON tms.transport_order (origin_id);
CREATE INDEX ix_transport_order_destination ON tms.transport_order (destination_id);
-- Idempotency guard: one (source, reference) pair per company. Partial (only when a reference
-- is present) because most orders have neither, and ck_transport_order_external_pair_complete
-- already guarantees external_source is never NULL whenever external_reference is not, so this
-- index cannot be defeated by two differently-NULL sources - see DATA_MODEL.md section 12.2.
CREATE UNIQUE INDEX uq_transport_order_external
    ON tms.transport_order (company_id, external_source, external_reference)
    WHERE external_reference IS NOT NULL;

COMMENT ON TABLE tms.transport_order IS
    'An operational transport order: company-scoped origin/destination, required service date, '
    'priority, an optional requested time window, a minimal V1 status lifecycle (see '
    'docs/domain/ORDER_LIFECYCLE_V1.md) and a transactional weight/volume/pallet snapshot kept '
    'current by OrderService/TransportOrder.applyLines, never computed by the frontend.';
COMMENT ON COLUMN tms.transport_order.order_number IS
    'System-generated, immutable, globally unique (see uq_transport_order_number). Never '
    'user-supplied, unlike a master''s code.';
COMMENT ON COLUMN tms.transport_order.total_weight_kg IS
    'Transactional snapshot: sum of transport_order_line.line_weight_kg. Recomputed by '
    'TransportOrder.applyLines every time lines change, in the same transaction - never entered '
    'or trusted from the frontend.';

CREATE TRIGGER tr_transport_order_set_updated_at
    BEFORE UPDATE ON tms.transport_order
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.transport_order ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- tms.transport_order_line - order lines, replaced as a whole set on every update
-- ---------------------------------------------------------------------------
-- A pure child of transport_order with no company_id of its own (DATA_MODEL.md section 11 rule
-- 1's documented exception): material/uom are free-text snapshot fields, not references into
-- another company-scoped table, so rule 6/7's composite-FK guarantee does not apply here - the
-- same shape as tms.frequency_weekly_rule (V7).
--
-- Unlike tms.route_stop (updated in place, keyed by destination_id) or
-- tms.frequency_weekly_rule (keyed by day_of_week), an order line has no reliable natural key
-- across an edit: two lines can legitimately share the same material_code with different
-- quantities. TransportOrder.applyLines therefore deletes and re-creates the whole set on every
-- update (orphanRemoval) rather than diffing by a key that does not exist.
--
-- That delete-and-recreate still needs the same DEFERRABLE idiom route_stop's in-place reorder
-- needed, for a different reason: Hibernate's flush order runs collection insertions before
-- collection (orphan-removal) deletions, so replacing a 2-line order with a new 2-line list
-- inserts line_number 1/2 for the new rows *before* deleting the old line_number 1/2 rows in the
-- same flush - a transient duplicate, exactly like route_stop's in-place swap, just produced by
-- a different code path. See uq_transport_order_line_order_line_number below.
CREATE TABLE tms.transport_order_line (
    id                    uuid          NOT NULL DEFAULT gen_random_uuid(),
    order_id              uuid          NOT NULL,
    -- 1-based position, contiguous, server-assigned from array order - RouteStop's convention,
    -- but without RouteStop's DEFERRABLE reorder support: see the class comment above.
    line_number           integer       NOT NULL,
    material_code         text          NOT NULL,
    material_description  text          NOT NULL,
    quantity              numeric(12,3) NOT NULL,
    uom                   text          NOT NULL,
    unit_weight_kg        numeric(10,3),
    unit_volume_m3        numeric(10,4),
    -- Computed snapshot (quantity * unit_weight_kg / unit_volume_m3), NULL exactly when the
    -- corresponding unit_* value is unknown. Never client-supplied - see TransportOrderLine.
    line_weight_kg        numeric(14,3),
    line_volume_m3        numeric(14,4),
    -- Pallet contribution "when known" (step brief): a direct planner/integration input, not
    -- derived from quantity, because how many units make a pallet is not modelled in V1.
    pallet_quantity       numeric(10,2),
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now(),
    created_by            uuid,
    updated_by            uuid,
    CONSTRAINT pk_transport_order_line PRIMARY KEY (id),
    CONSTRAINT fk_transport_order_line_order FOREIGN KEY (order_id)
        REFERENCES tms.transport_order (id) ON DELETE CASCADE,
    -- DEFERRABLE INITIALLY DEFERRED: TransportOrder.applyLines deletes and re-creates the whole
    -- line set on every update; Hibernate flushes the new INSERTs before the orphan-removal
    -- DELETEs in the same flush, which transiently duplicates (order_id, line_number) for any
    -- line number that appears in both the old and new set. Deferring the check to COMMIT is
    -- the same idiom uq_route_stop_route_sequence (V8) uses for its in-place reorder - see the
    -- class comment above and OrderApiIntegrationTest.updateRecomputesTotals.
    CONSTRAINT uq_transport_order_line_order_line_number
        UNIQUE (order_id, line_number) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_transport_order_line_number_positive CHECK (line_number >= 1),
    CONSTRAINT ck_transport_order_line_material_code_not_blank
        CHECK (btrim(material_code) <> ''),
    CONSTRAINT ck_transport_order_line_material_description_not_blank
        CHECK (btrim(material_description) <> ''),
    CONSTRAINT ck_transport_order_line_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_transport_order_line_uom_normalized
        CHECK (uom = upper(btrim(uom)) AND btrim(uom) <> ''),
    CONSTRAINT ck_transport_order_line_unit_weight_positive
        CHECK (unit_weight_kg IS NULL OR unit_weight_kg > 0),
    CONSTRAINT ck_transport_order_line_unit_volume_positive
        CHECK (unit_volume_m3 IS NULL OR unit_volume_m3 > 0),
    CONSTRAINT ck_transport_order_line_weight_nonnegative
        CHECK (line_weight_kg IS NULL OR line_weight_kg >= 0),
    CONSTRAINT ck_transport_order_line_volume_nonnegative
        CHECK (line_volume_m3 IS NULL OR line_volume_m3 >= 0),
    CONSTRAINT ck_transport_order_line_pallet_quantity_nonnegative
        CHECK (pallet_quantity IS NULL OR pallet_quantity >= 0),
    CONSTRAINT fk_transport_order_line_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transport_order_line_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_transport_order_line_order ON tms.transport_order_line (order_id);

COMMENT ON TABLE tms.transport_order_line IS
    'One line of a tms.transport_order (migration V10). Cascades from its order on delete. '
    'Deleted and re-created as a whole set on every header update (TransportOrder.applyLines) - '
    'no natural key survives an edit, unlike tms.route_stop or tms.frequency_weekly_rule.';
COMMENT ON COLUMN tms.transport_order_line.line_weight_kg IS
    'quantity * unit_weight_kg, computed by TransportOrderLine.applyInput. NULL when '
    'unit_weight_kg is unknown - never client-supplied.';

CREATE TRIGGER tr_transport_order_line_set_updated_at
    BEFORE UPDATE ON tms.transport_order_line
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.transport_order_line ENABLE ROW LEVEL SECURITY;
