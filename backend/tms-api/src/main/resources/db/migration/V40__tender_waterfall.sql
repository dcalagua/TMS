-- ===========================================================================
-- V40 - The tender waterfall: offer a shipment down a ranked list of carriers
-- ===========================================================================
--
-- V31 built tendering properly and stopped one step short of what an operation needs. A dispatcher
-- can offer a shipment to a carrier, the carrier can accept or reject, the history is immutable and
-- exactly one acceptance per trip is a database fact. What V31 cannot do is the thing that actually
-- fills a truck: when the first carrier says no, offer it to the second.
--
-- Today that is a person watching a screen. Every rejection at 19:40 waits until somebody notices,
-- and every deadline that lapses overnight is a shipment nobody offered to anyone until morning.
--
-- ---------------------------------------------------------------------------
-- 1. Two tables, and why the ranking is stored rather than recomputed
-- ---------------------------------------------------------------------------
--
-- The obvious design is to rank carriers each time the waterfall advances. It is wrong, for the
-- reason a rate snapshot exists at all: rate cards change, carriers are deactivated, and a
-- shipment offered to "the cheapest carrier" on Monday and re-ranked on Tuesday would walk a
-- different list than the one anybody approved. So the ranked candidates are written once, with
-- the price each was ranked on, and the waterfall walks that list.
--
-- It also makes the whole thing answerable after the fact: "why did this go to the third carrier"
-- is a question with a row behind it rather than a re-derivation that may no longer reproduce.
CREATE TABLE tms.tender_waterfall (
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid        NOT NULL,
    trip_id             uuid        NOT NULL,
    status              text        NOT NULL DEFAULT 'ACTIVE',
    -- How many carriers may be offered before the waterfall gives up and asks for a human. A
    -- ceiling and not a target: most shipments are accepted by the first or second.
    max_attempts        integer     NOT NULL,
    -- How long each carrier gets to answer. Stored on the waterfall rather than read from settings
    -- at each step, so lengthening the company default does not silently extend an offer that is
    -- already out.
    response_minutes    integer     NOT NULL,
    -- Why it ended, for the ones that did not end in an acceptance.
    outcome_note        text,
    started_at          timestamptz NOT NULL DEFAULT now(),
    started_by          uuid,
    completed_at        timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_tender_waterfall PRIMARY KEY (id),
    CONSTRAINT fk_tender_waterfall_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tender_waterfall_trip FOREIGN KEY (trip_id)
        REFERENCES tms.trip (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tender_waterfall_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),
    CONSTRAINT fk_tender_waterfall_started_by FOREIGN KEY (started_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_tender_waterfall_status CHECK (status IN (
        'ACTIVE', 'ACCEPTED', 'EXHAUSTED', 'CANCELLED')),
    CONSTRAINT ck_tender_waterfall_max_attempts CHECK (max_attempts BETWEEN 1 AND 20),
    CONSTRAINT ck_tender_waterfall_response_minutes CHECK (response_minutes BETWEEN 1 AND 10080),
    -- A finished waterfall has a moment it finished; a running one does not.
    CONSTRAINT ck_tender_waterfall_completed_pair CHECK (
        (status = 'ACTIVE') = (completed_at IS NULL))
);

-- At most one running waterfall per shipment, installation-wide. Two would race to open the next
-- attempt and V31's uq_trip_tender_live would then refuse one of them mid-transaction - a conflict
-- surfaced to whoever happened to be unlucky rather than prevented. Partial on ACTIVE so the
-- history of a shipment tendered twice (accepted, cancelled, re-tendered) stays outside it.
CREATE UNIQUE INDEX uq_tender_waterfall_active ON tms.tender_waterfall (trip_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_tender_waterfall_company ON tms.tender_waterfall (company_id);

COMMENT ON TABLE tms.tender_waterfall IS
    'A shipment being offered down a ranked list of carriers (V40). The ranking is stored rather '
    'than recomputed at each step: rate cards change and carriers are deactivated, and a shipment '
    'that walked a different list than the one approved would make "why did this go to the third '
    'carrier" unanswerable.';

-- ---------------------------------------------------------------------------
-- 2. The ranked candidates
-- ---------------------------------------------------------------------------
CREATE TABLE tms.tender_waterfall_candidate (
    id                  uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid          NOT NULL,
    waterfall_id        uuid          NOT NULL,
    -- 1 is offered first. Dense and unique within the waterfall.
    rank                integer       NOT NULL,
    carrier_id          uuid          NOT NULL,
    status              text          NOT NULL DEFAULT 'PENDING',
    -- What this carrier was ranked on, snapshotted at ranking time with the agreement it came
    -- from. Null when the carrier had no applicable rate card: it is still a candidate - a
    -- dispatcher may well want to offer to somebody they have no tariff for - but it ranks last,
    -- because "no price" is not "free".
    quoted_amount       numeric(14,2),
    quoted_currency     text,
    rate_card_id        uuid,
    -- The tender this candidate was actually offered through, once it was.
    tender_id           uuid,
    decided_at          timestamptz,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_tender_waterfall_candidate PRIMARY KEY (id),
    CONSTRAINT fk_twc_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_twc_waterfall FOREIGN KEY (waterfall_id)
        REFERENCES tms.tender_waterfall (id) ON DELETE CASCADE,
    CONSTRAINT fk_twc_carrier FOREIGN KEY (carrier_id)
        REFERENCES tms.carrier (id) ON DELETE RESTRICT,
    CONSTRAINT fk_twc_carrier_company FOREIGN KEY (carrier_id, company_id)
        REFERENCES tms.carrier (id, company_id),
    CONSTRAINT fk_twc_rate_card FOREIGN KEY (rate_card_id)
        REFERENCES tms.rate_card (id) ON DELETE SET NULL,
    CONSTRAINT fk_twc_tender FOREIGN KEY (tender_id)
        REFERENCES tms.trip_tender (id) ON DELETE SET NULL,
    CONSTRAINT ck_twc_rank_positive CHECK (rank >= 1),
    CONSTRAINT ck_twc_status CHECK (status IN (
        'PENDING', 'OFFERED', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'SKIPPED')),
    CONSTRAINT ck_twc_quote_pair CHECK ((quoted_amount IS NULL) = (quoted_currency IS NULL)),
    CONSTRAINT ck_twc_quote_nonnegative CHECK (quoted_amount IS NULL OR quoted_amount >= 0),
    CONSTRAINT ck_twc_currency CHECK (quoted_currency IS NULL OR quoted_currency ~ '^[A-Z]{3}$'),
    -- A candidate that was decided says when, and one still waiting does not. OFFERED is
    -- deliberately on the undecided side: the offer is out and nothing has come back.
    CONSTRAINT ck_twc_decided_pair CHECK (
        (status IN ('PENDING', 'OFFERED')) = (decided_at IS NULL)),
    -- Anything past PENDING went out through a tender, and that tender is named. Without this a
    -- rejected candidate could point at nothing and the trail would stop exactly where somebody
    -- reading it needs it to continue.
    CONSTRAINT ck_twc_decided_has_tender CHECK (
        status IN ('PENDING', 'SKIPPED') OR tender_id IS NOT NULL)
);

-- One candidate per rank, and one appearance per carrier: offering the same carrier twice in one
-- waterfall is a list somebody built wrong, and it would make max_attempts count the same refusal
-- twice.
CREATE UNIQUE INDEX uq_twc_rank ON tms.tender_waterfall_candidate (waterfall_id, rank);
CREATE UNIQUE INDEX uq_twc_carrier ON tms.tender_waterfall_candidate (waterfall_id, carrier_id);
CREATE INDEX ix_twc_company ON tms.tender_waterfall_candidate (company_id);
-- The scheduler's read: the candidate currently out, per waterfall.
CREATE INDEX ix_twc_offered ON tms.tender_waterfall_candidate (waterfall_id) WHERE status = 'OFFERED';

COMMENT ON COLUMN tms.tender_waterfall_candidate.quoted_amount IS
    'What this carrier was ranked on (V40), snapshotted with the agreement that produced it. Null '
    'when the carrier has no applicable rate card - such a carrier is still offerable but ranks '
    'last, because "no price" is not "free".';

-- ---------------------------------------------------------------------------
-- 3. Tenant isolation (ADR-005)
-- ---------------------------------------------------------------------------
ALTER TABLE tms.tender_waterfall ENABLE ROW LEVEL SECURITY;
ALTER TABLE tms.tender_waterfall_candidate ENABLE ROW LEVEL SECURITY;

-- No DELETE: a waterfall is the record of who was offered a shipment and in what order, which is
-- exactly what a carrier disputing a rate asks for. It is cancelled, not removed. The candidate
-- rows follow their waterfall by cascade if a trip is ever purged, which is a schema-owner
-- operation and not something tms_app may do.
GRANT SELECT, INSERT, UPDATE ON tms.tender_waterfall TO tms_app;
GRANT SELECT, INSERT, UPDATE ON tms.tender_waterfall_candidate TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.tender_waterfall
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

CREATE POLICY p_tenant_company_scope ON tms.tender_waterfall_candidate
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 4. The audit vocabulary
-- ---------------------------------------------------------------------------
--
-- Two actions, not six. Starting a waterfall and ending it are decisions a person makes; every step
-- in between already produces TENDER_SENT, TENDER_REJECTED or TENDER_EXPIRED against the shipment,
-- and minting a parallel WATERFALL_* row for each would duplicate the trail rather than extend it.
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
    'CREATE', 'UPDATE', 'ACTIVATE', 'DEACTIVATE', 'ASSIGN_ORDER', 'REMOVE_ORDER', 'MOVE_ORDER',
    'VEHICLE_CHANGE', 'DRIVER_CHANGE', 'CONFIRM', 'CANCEL', 'CREDENTIAL_CREATE',
    'CREDENTIAL_ROTATE', 'CREDENTIAL_REVOKE', 'AUTO_PLAN', 'IMPORT_EXECUTED', 'SHIPMENT_CONFIRMED',
    'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED', 'SHIPMENT_CANCELLED',
    'DELIVERY_RESULT_RECORDED', 'COST_ESTIMATED', 'COST_ACTUAL_RECORDED', 'COST_CLOSED',
    'COST_REOPENED',
    'TENDER_SENT', 'TENDER_ACCEPTED', 'TENDER_REJECTED', 'TENDER_EXPIRED', 'TENDER_CANCELLED',
    'ROLES_CHANGED',
    'ORDER_REOPENED',
    'WATERFALL_STARTED', 'WATERFALL_ENDED'));

-- ---------------------------------------------------------------------------
-- Deliberately NOT here
-- ---------------------------------------------------------------------------
--
--   * No carrier capacity or commitment model. Ranking uses price, lane applicability and whether
--     the carrier is active - all facts the product already holds. "How many trucks does this
--     carrier have free on Thursday" is a real question and needs a carrier-capacity feature, not
--     a column guessed at here.
--   * No spot-market or broadcast tendering. This offers to one carrier at a time, in order,
--     which is what a waterfall is. Offering to five at once is a different product decision with
--     different fairness and pricing consequences.
--   * No automatic acceptance. A carrier accepts; the system never accepts on their behalf, and it
--     never dispatches. V31's rule, unchanged.
