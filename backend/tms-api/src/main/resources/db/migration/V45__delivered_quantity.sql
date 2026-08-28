-- ===========================================================================
-- V45 - How much was actually delivered
-- ===========================================================================
--
-- Closes open debt D3, which has been carried since V28 and was formally evaluated in Phase 1
-- JOB 10 (docs/domain/DELIVERED_QUANTITY_EVALUATION.md). That evaluation concluded three things,
-- and this migration is built on all three:
--
--   1. It is a MISSING CAPABILITY, not a defect. Nothing in the system ever claimed to know a
--      delivered quantity, so there is no wrong number to correct - only an absent one to supply.
--   2. It MUST NOT be inferred from ordered, allocated or planned amounts. A PARTIAL delivery is
--      by definition the case where the delivered amount differs from all three, so any of them
--      used as a substitute would be exactly wrong in exactly the case it is needed - and would
--      look like a measurement.
--   3. Closing it properly is a TABLE, not a column: per line, in the line's own unit, with a
--      refused counterpart and a ceiling.
--
-- ---------------------------------------------------------------------------
-- 1. Why quantities land in TWO places
-- ---------------------------------------------------------------------------
--
-- Because two different questions are being asked, and one shape cannot answer both.
--
-- **The summable question** - "how much of this shipment moved" - is asked by the allocation
-- ledger (V37), by cost allocation, and by the order lifecycle. It is answered in the three
-- measures a vehicle is constrained by: weight, volume, pallets. docs/domain/SHIP_UNITS_AND_
-- ALLOCATION_V1.md states why these are the only summable ones: an order's lines each carry their
-- own quantity and uom, and adding 40 boxes to 3 drums gives a number that means nothing.
--
-- **The operational question** - "which product did the customer refuse" - cannot be answered in
-- kilos at all. It needs the line, and the line's own unit.
--
-- So: the three measures go on tms.order_delivery, where the V37 ceiling can be enforced against
-- them; the per-product truth goes in tms.order_delivery_line. Neither is derived from the other,
-- and the second is optional - plenty of deliveries are settled on weight alone.
--
-- ---------------------------------------------------------------------------
-- 2. The measures, on the delivery row
-- ---------------------------------------------------------------------------
--
-- ATTEMPTED is stored rather than derived from the allocation, and that is deliberate. What a
-- driver actually put on the vehicle may be less than what a planner allocated - a pallet that
-- would not fit, a line pulled at the dock - and "we tried to deliver 8 of the 10 allocated" is a
-- different sentence from "we delivered 8 of 10". Deriving attempted would erase the difference.
ALTER TABLE tms.order_delivery ADD COLUMN attempted_weight_kg  numeric(14,3);
ALTER TABLE tms.order_delivery ADD COLUMN attempted_volume_m3  numeric(14,4);
ALTER TABLE tms.order_delivery ADD COLUMN attempted_pallets    numeric(12,2);

ALTER TABLE tms.order_delivery ADD COLUMN delivered_weight_kg  numeric(14,3);
ALTER TABLE tms.order_delivery ADD COLUMN delivered_volume_m3  numeric(14,4);
ALTER TABLE tms.order_delivery ADD COLUMN delivered_pallets    numeric(12,2);

ALTER TABLE tms.order_delivery ADD COLUMN refused_weight_kg    numeric(14,3);
ALTER TABLE tms.order_delivery ADD COLUMN refused_volume_m3    numeric(14,4);
ALTER TABLE tms.order_delivery ADD COLUMN refused_pallets      numeric(12,2);

-- Nullable, and null means NOT RECORDED - never zero.
--
-- This is what makes the migration backward compatible without a back-fill. Every delivery row
-- written before today has no quantities and must keep meaning exactly what it meant: an outcome,
-- and no claim about amounts. Back-filling zeros would have asserted "nothing was delivered" for
-- every historical delivery in the system, which is the single most damaging thing this migration
-- could do - and it would have looked like data.

-- All-or-nothing per measure. A row that states it delivered 800 kg without stating it attempted
-- any is not a partial record, it is an unanswerable one: 800 of what?
ALTER TABLE tms.order_delivery ADD CONSTRAINT ck_order_delivery_weight_block CHECK (
    num_nonnulls(attempted_weight_kg, delivered_weight_kg, refused_weight_kg) IN (0, 3));
ALTER TABLE tms.order_delivery ADD CONSTRAINT ck_order_delivery_volume_block CHECK (
    num_nonnulls(attempted_volume_m3, delivered_volume_m3, refused_volume_m3) IN (0, 3));
ALTER TABLE tms.order_delivery ADD CONSTRAINT ck_order_delivery_pallet_block CHECK (
    num_nonnulls(attempted_pallets, delivered_pallets, refused_pallets) IN (0, 3));

ALTER TABLE tms.order_delivery ADD CONSTRAINT ck_order_delivery_quantities_nonnegative CHECK (
    COALESCE(attempted_weight_kg, 0) >= 0 AND COALESCE(delivered_weight_kg, 0) >= 0
        AND COALESCE(refused_weight_kg, 0) >= 0
    AND COALESCE(attempted_volume_m3, 0) >= 0 AND COALESCE(delivered_volume_m3, 0) >= 0
        AND COALESCE(refused_volume_m3, 0) >= 0
    AND COALESCE(attempted_pallets, 0) >= 0 AND COALESCE(delivered_pallets, 0) >= 0
        AND COALESCE(refused_pallets, 0) >= 0);

-- THE invariant of this migration.
--
-- Nothing can be delivered and refused beyond what was attempted. Stated per measure rather than
-- over a total, because the three are not interchangeable and a shortfall in pallets is not
-- cancelled out by a surplus in kilos.
--
-- Note this is <=, not =. Goods can be attempted and neither delivered nor refused - left on the
-- vehicle because the dock closed, carried back to the depot. That difference is the amount still
-- outstanding, and it is a real operational state rather than an accounting error to forbid.
ALTER TABLE tms.order_delivery ADD CONSTRAINT ck_order_delivery_not_over_delivered CHECK (
    (attempted_weight_kg IS NULL OR delivered_weight_kg + refused_weight_kg <= attempted_weight_kg)
    AND (attempted_volume_m3 IS NULL OR delivered_volume_m3 + refused_volume_m3 <= attempted_volume_m3)
    AND (attempted_pallets IS NULL OR delivered_pallets + refused_pallets <= attempted_pallets));

-- A result that asserts nothing changed hands cannot carry a delivered amount. NOT_ATTEMPTED means
-- the goods never came off the vehicle; DELIVERY of zero with that result would be a contradiction
-- the lifecycle would then have to interpret.
ALTER TABLE tms.order_delivery ADD CONSTRAINT ck_order_delivery_not_attempted_delivers_nothing CHECK (
    result <> 'NOT_ATTEMPTED'
    OR (COALESCE(delivered_weight_kg, 0) = 0 AND COALESCE(delivered_volume_m3, 0) = 0
        AND COALESCE(delivered_pallets, 0) = 0));

COMMENT ON COLUMN tms.order_delivery.attempted_weight_kg IS
    'What was taken to the customer, in kilograms (V45). NULL means NOT RECORDED - never zero, and '
    'never inferred from the order or the allocation. Stored rather than derived because what a '
    'driver loaded may be less than what a planner allocated, and "we tried to deliver 8 of the 10 '
    'allocated" is a different sentence from "we delivered 8 of 10".';

COMMENT ON COLUMN tms.order_delivery.delivered_weight_kg IS
    'What the customer actually took, in kilograms (V45). With refused_weight_kg it may be less '
    'than attempted: goods carried back to the depot are neither delivered nor refused, and that '
    'difference is what is still outstanding.';

-- ---------------------------------------------------------------------------
-- 3. Per line, in the line's own unit
-- ---------------------------------------------------------------------------
--
-- The composite key the line result needs to be pinned to its order (DATA_MODEL.md rule 6).
-- transport_order_line has never carried company_id - it is scoped through its order - so this is
-- what lets a delivery line prove it belongs to the order the delivery is about.
ALTER TABLE tms.transport_order_line
    ADD CONSTRAINT uq_transport_order_line_id_order UNIQUE (id, order_id);

CREATE TABLE tms.order_delivery_line (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid          NOT NULL,
    order_delivery_id   uuid          NOT NULL,
    order_id            uuid          NOT NULL,
    order_line_id       uuid          NOT NULL,
    -- The unit, snapshotted from the line at the moment of the delivery. A line's uom is master
    -- data and can be corrected; what somebody signed for cannot. The same argument
    -- tms.trip_cost.rate_card_* makes for the winning card.
    uom                 text          NOT NULL,
    quantity_attempted  numeric(12,3) NOT NULL,
    quantity_delivered  numeric(12,3) NOT NULL,
    quantity_refused    numeric(12,3) NOT NULL,
    -- Why the shortfall, when there is one. Free text and optional: the typed reason lives on the
    -- delivery's own result, and a per-line code catalogue is a thing to add when somebody needs to
    -- count them, not before.
    notes               text,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_by          uuid,

    CONSTRAINT pk_order_delivery_line PRIMARY KEY (id),
    CONSTRAINT uq_order_delivery_line_id_company UNIQUE (id, company_id),
    -- One result per line per delivery. A correction overwrites, exactly as the delivery row
    -- itself does (V28) and for the same reason: two rows would make "how much of line 3 arrived"
    -- a question with two answers and an ordering rule to choose between them.
    CONSTRAINT uq_order_delivery_line_delivery_line UNIQUE (order_delivery_id, order_line_id),

    CONSTRAINT fk_order_delivery_line_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_delivery_line_delivery FOREIGN KEY (order_delivery_id)
        REFERENCES tms.order_delivery (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_delivery_line_delivery_company FOREIGN KEY (order_delivery_id, company_id)
        REFERENCES tms.order_delivery (id, company_id),
    CONSTRAINT fk_order_delivery_line_order FOREIGN KEY (order_id)
        REFERENCES tms.transport_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_delivery_line_order_company FOREIGN KEY (order_id, company_id)
        REFERENCES tms.transport_order (id, company_id),
    -- The line must belong to the order this delivery is about. Without this a delivery could
    -- record a quantity against another order's line - same company, wrong goods.
    CONSTRAINT fk_order_delivery_line_line_in_order FOREIGN KEY (order_line_id, order_id)
        REFERENCES tms.transport_order_line (id, order_id) ON DELETE RESTRICT,

    CONSTRAINT ck_order_delivery_line_nonnegative CHECK (
        quantity_attempted >= 0 AND quantity_delivered >= 0 AND quantity_refused >= 0),
    -- The same invariant as the measures above, at line grain.
    CONSTRAINT ck_order_delivery_line_not_over_delivered CHECK (
        quantity_delivered + quantity_refused <= quantity_attempted),
    CONSTRAINT ck_order_delivery_line_uom_not_blank CHECK (btrim(uom) <> ''),
    CONSTRAINT ck_order_delivery_line_notes_not_blank CHECK (notes IS NULL OR btrim(notes) <> '')
);

CREATE INDEX ix_order_delivery_line_delivery ON tms.order_delivery_line (order_delivery_id);
CREATE INDEX ix_order_delivery_line_order ON tms.order_delivery_line (order_id);
CREATE INDEX ix_order_delivery_line_company ON tms.order_delivery_line (company_id);

COMMENT ON TABLE tms.order_delivery_line IS
    'What arrived, per order line, in the line''s own unit (V45). Optional: plenty of deliveries '
    'are settled on weight alone, and the three measures on tms.order_delivery are what the '
    'allocation ceiling and cost allocation are expressed in. This table answers the question kilos '
    'cannot - WHICH product the customer refused.';

-- ---------------------------------------------------------------------------
-- 4. Tenant isolation (ADR-005)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.order_delivery_line ENABLE ROW LEVEL SECURITY;

-- SELECT, INSERT and UPDATE but no DELETE, matching tms.order_delivery: what was signed for is a
-- commercial fact somebody may be invoiced or credited against. A line entered by mistake is
-- corrected to zero, not erased. The CASCADE from order_delivery above is the owner's path, not
-- tms_app's.
GRANT SELECT, INSERT, UPDATE ON tms.order_delivery_line TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.order_delivery_line
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 5. Deliberately NOT here
-- ---------------------------------------------------------------------------
--
--   * No DAMAGED / MISSING / RETURNED columns. The brief allows them only if they can be modelled
--     without inventing semantics, and they cannot yet: "damaged" is a claim that needs evidence
--     and a liability owner, "returned" is a movement that needs a return trip, and "missing" is a
--     dispute rather than a quantity. All three are refusals as far as the customer's account is
--     concerned, which is what refused_* records. Adding three columns nothing can populate
--     defensibly would be scaffolding.
--   * No back-fill. See section 2 - zeros would have asserted a falsehood about every historical
--     delivery in the system.
--   * No new evidence store. ADR-006's EvidenceStoragePort already binds proof to a delivery row,
--     and a line result inherits that binding through its delivery.
--   * No cross-attempt total column. "How much has this order received in all" is a SUM over the
--     attempts, and storing it would be a second answer that can drift from the first - the same
--     reason V37's ledger stores allocations and not a remaining balance.
