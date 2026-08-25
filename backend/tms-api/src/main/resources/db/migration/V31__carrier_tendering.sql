-- TMS by EBIM - V31 carrier tendering: offering a planned shipment to the carrier that is meant to
-- run it, and recording what they answered.
--
-- Design and the full rule set: docs/domain/CARRIER_TENDERING_V1.md.
--
-- Until now a confirmed shipment named a carrier and nothing said whether that carrier had agreed
-- to run it. The agreement happened - on the phone, over WhatsApp, in a mail thread - and TMS held
-- no trace of it, so "did ACME accept SH-00000142, and when" was a question answered by scrolling
-- somebody's inbox. That is exactly the kind of fact that matters most on the day it is disputed.
--
-- One table, tms.trip_tender: one row per *attempt* to place a shipment with a carrier, with the
-- answer beside the offer. Plus one column on tms.integration_client, which is what lets an
-- integrated carrier answer for themselves instead of a shipper's clerk answering on their behalf.
--
-- What this is NOT is a marketplace. There is no bidding, no counter-offer, no ranked carrier list,
-- no automatic waterfall down a preference order, and no spot rate. Every one of those is a
-- commercial workflow a customer has to define before it can be built, and the closing
-- "Deliberately NOT here" section says what each would need first.

-- ---------------------------------------------------------------------------
-- 1. tms.trip_tender
-- ---------------------------------------------------------------------------
-- Five states, mirrored by planning.domain.TenderStatus:
--
--   DRAFT -> SENT -> ACCEPTED
--     |        \---> REJECTED
--     |        \---> EXPIRED
--     \--------\---> CANCELLED
--
-- DRAFT earns its place, which is the test V25 applied to DISPATCHED and DISPATCHED failed: it is
-- the only state in which the offer's *terms* may be edited. Once a tender is SENT its amount, its
-- deadline and its instructions are frozen, because at that point a carrier has been shown them and
-- an offer that can be rewritten under the party considering it is not an offer. A planner who
-- mistyped 1.200,00 as 12.000,00 fixes it while it is a draft; afterwards they cancel and send
-- another, which is a second attempt and is recorded as one.
--
-- EXPIRED is reachable only from SENT: a draft has no deadline running against it.
--
-- CANCELLED is the shipper withdrawing, from either live state, and is also what TMS writes when
-- the shipment itself stops being offerable - it was cancelled, or it left without an answer. See
-- TripTenderService.withdrawOpen.
--
-- Terminal: ACCEPTED, REJECTED, EXPIRED, CANCELLED. A rejection is never edited into an acceptance;
-- the carrier changing their mind is a new attempt, and the history keeps both.
CREATE TABLE tms.trip_tender (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id         uuid        NOT NULL,
    trip_id            uuid        NOT NULL,
    -- Who the shipment was offered to. Snapshotted at creation and never updated, so the history
    -- of attempts survives whatever happens to the trip afterwards.
    --
    -- It is always the trip's *own* carrier, and TripTenderService refuses anything else. That is
    -- not a limitation of this table, it is the shape of the model above it: a trip's carrier comes
    -- from the vehicle planned on it (V11), and the vehicle may only be swapped while the trip is a
    -- DRAFT - so from the moment a shipment is offerable, who would run it is already decided and
    -- there is nothing for a second carrier on the tender to mean. Offering the same load to three
    -- carriers at once is a marketplace; see the closing section.
    carrier_id         uuid        NOT NULL,
    -- 1, 2, 3... per trip. A number and not merely an ordering by created_at, because "this is the
    -- third time we have tried to place this shipment" is the fact an operations manager reads off
    -- the screen, and deriving it from a row count would make it change if a row were ever removed.
    attempt            integer     NOT NULL,
    status             text        NOT NULL DEFAULT 'DRAFT',

    -- What is being offered, and both columns move together (ck_trip_tender_offer_pair). Optional
    -- as a pair: a company that tenders under a standing rate card has no per-shipment price to
    -- state, and forcing one would make them invent a number. When it is present it is what TMS
    -- offered to pay for this shipment - deliberately not read from tms.trip_cost and deliberately
    -- not written back to it. The estimate is what the tariff says the shipment should cost; this
    -- is what somebody offered on the day, and letting either overwrite the other would destroy the
    -- comparison that makes both worth having.
    offered_amount     numeric(14,2),
    currency           text,
    -- Instructions travelling with the offer ("load 06:00, gate B, tail lift required"). Free text
    -- because it is addressed to a person at the carrier, not to a rule in this system.
    notes              text,
    -- The deadline, optional. Optional because a company that tenders a day ahead by phone has no
    -- deadline to state and a NOT NULL here would make them invent one; and because expiry has real
    -- cost - see section 1b.
    expires_at         timestamptz,

    -- Drafted. created_by is NOT NULL, unlike most tables in this schema: every tender is created
    -- by a person in the UI, and there is no unattended source of one. The day a rule tenders
    -- automatically, relaxing this is a one-line migration; having allowed nulls nobody produces is
    -- the change that cannot be undone (the argument V27 made for trip_exception.reported_by).
    created_by         uuid        NOT NULL,
    -- Sent. This is the moment the outbox row is written and the carrier can see the offer.
    sent_at            timestamptz,
    sent_by            uuid,

    -- Answered. responded_at is set for ACCEPTED and REJECTED and for nothing else: an expiry and a
    -- withdrawal are not answers, and they have their own timestamps below so that "did they ever
    -- reply" stays a single, honest question.
    responded_at       timestamptz,
    -- OPERATOR when a person recorded the answer in the TMS UI - the phone call, the mail - and
    -- INTEGRATION when the carrier's own system answered over the M2M API. The same two-source
    -- vocabulary tms.transport_event.source uses, and for the same reason: an acceptance typed in
    -- by the shipper's own clerk and one signed by the carrier's system are different evidence, and
    -- a dispute turns on which of the two it was.
    response_source    text,
    -- Exactly one of these is set when response_source says so (ck_trip_tender_response_actor).
    responded_by       uuid,
    responded_by_client uuid,
    -- The carrier's own words. Required on a rejection (ck_trip_tender_rejection_has_reason): "they
    -- said no" with no reason is the one answer that helps nobody - it is what the planner needs in
    -- order to decide what to do next.
    response_notes     text,

    -- Lapsed. Set when the deadline passed with no answer. Kept beside expires_at rather than
    -- inferred from it, because the two are genuinely different facts: expires_at is when the offer
    -- was due, expired_at is when TMS resolved that it had lapsed - which, without a scheduler, is
    -- later. See section 1b.
    expired_at         timestamptz,

    -- Withdrawn. cancel_reason is required (ck_trip_tender_cancel_has_reason) for the same reason
    -- TripService.cancel requires one on a confirmed trip: withdrawing something a carrier was told
    -- about needs an explanation, and this is the only place it is recorded.
    --
    -- cancelled_by is required beside cancelled_at (ck_trip_tender_cancelled_actor_pair) even
    -- though a withdrawal can be triggered by the shipment's own lifecycle - cancelling or
    -- dispatching it. Those paths are a person's action today and TripTenderService.withdrawOpen
    -- takes the acting app_user rather than tolerating none, the same call V27 made for
    -- trip_exception.reported_by: the day an unattended rule withdraws an offer, relaxing this is a
    -- one-line migration, and having allowed nulls nobody produces is what cannot be undone.
    cancelled_at       timestamptz,
    cancelled_by       uuid,
    cancel_reason      text,

    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    updated_by         uuid,
    CONSTRAINT pk_trip_tender PRIMARY KEY (id),
    CONSTRAINT fk_trip_tender_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_tender_trip FOREIGN KEY (trip_id)
        REFERENCES tms.trip (id) ON DELETE RESTRICT,
    -- Rule 6, the tenant guarantee, once per referenced master: the trip and the carrier on a
    -- tender both belong to the tender's own company.
    CONSTRAINT fk_trip_tender_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),
    CONSTRAINT fk_trip_tender_carrier FOREIGN KEY (carrier_id)
        REFERENCES tms.carrier (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_tender_carrier_company FOREIGN KEY (carrier_id, company_id)
        REFERENCES tms.carrier (id, company_id),
    CONSTRAINT fk_trip_tender_created_by FOREIGN KEY (created_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_tender_sent_by FOREIGN KEY (sent_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_tender_responded_by FOREIGN KEY (responded_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    -- The credential that answered, when the carrier's own system did. Same composite tenant
    -- guarantee: a credential of another company cannot have answered this company's tender.
    CONSTRAINT fk_trip_tender_responded_by_client FOREIGN KEY (responded_by_client)
        REFERENCES tms.integration_client (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_tender_responded_by_client_company FOREIGN KEY (responded_by_client, company_id)
        REFERENCES tms.integration_client (id, company_id),
    CONSTRAINT fk_trip_tender_cancelled_by FOREIGN KEY (cancelled_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trip_tender_updated_by FOREIGN KEY (updated_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,

    CONSTRAINT uq_trip_tender_trip_attempt UNIQUE (trip_id, attempt),
    CONSTRAINT ck_trip_tender_attempt_positive CHECK (attempt >= 1),
    CONSTRAINT ck_trip_tender_status CHECK (status IN (
        'DRAFT', 'SENT', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED')),

    -- An amount with no currency is not a price, and a currency with no amount is not an offer.
    CONSTRAINT ck_trip_tender_offer_pair CHECK ((offered_amount IS NULL) = (currency IS NULL)),
    CONSTRAINT ck_trip_tender_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    -- Zero is a legal offer (a backhaul run at no charge); negative is not.
    CONSTRAINT ck_trip_tender_amount_nonnegative CHECK (offered_amount IS NULL OR offered_amount >= 0),

    -- The declarative half of the transition table. The moves themselves live in
    -- planning.domain.TenderStatus - the same division ck_trip_status (V25) uses: this column's
    -- CHECK constrains the values and their timestamps, never the paths between them.
    --
    -- Everything past DRAFT was sent, except a draft that was discarded before it ever went out.
    CONSTRAINT ck_trip_tender_sent_pair CHECK ((sent_at IS NULL) = (sent_by IS NULL)),
    CONSTRAINT ck_trip_tender_live_states_were_sent CHECK (
        status IN ('DRAFT', 'CANCELLED') OR sent_at IS NOT NULL),
    CONSTRAINT ck_trip_tender_draft_was_not_sent CHECK (status <> 'DRAFT' OR sent_at IS NULL),

    -- A biconditional: an answer is exactly ACCEPTED or REJECTED, and nothing else writes one.
    CONSTRAINT ck_trip_tender_responded_pair CHECK (
        (status IN ('ACCEPTED', 'REJECTED')) = (responded_at IS NOT NULL)),
    CONSTRAINT ck_trip_tender_response_source CHECK (
        response_source IS NULL OR response_source IN ('OPERATOR', 'INTEGRATION')),
    -- The source and the actor say the same thing, so neither can drift from the other: a person
    -- answered and is named, or a credential answered and is named, or nobody answered at all.
    CONSTRAINT ck_trip_tender_response_actor CHECK (
        CASE response_source
            WHEN 'OPERATOR'    THEN responded_by IS NOT NULL AND responded_by_client IS NULL
            WHEN 'INTEGRATION' THEN responded_by IS NULL     AND responded_by_client IS NOT NULL
            ELSE responded_by IS NULL AND responded_by_client IS NULL
        END),
    CONSTRAINT ck_trip_tender_response_source_pair CHECK (
        (responded_at IS NULL) = (response_source IS NULL)),
    CONSTRAINT ck_trip_tender_rejection_has_reason CHECK (
        status <> 'REJECTED' OR btrim(coalesce(response_notes, '')) <> ''),
    CONSTRAINT ck_trip_tender_response_after_sent CHECK (
        responded_at IS NULL OR sent_at IS NULL OR responded_at >= sent_at),

    CONSTRAINT ck_trip_tender_expired_pair CHECK ((status = 'EXPIRED') = (expired_at IS NOT NULL)),
    -- Nothing lapses without a deadline to lapse against.
    CONSTRAINT ck_trip_tender_expired_had_deadline CHECK (
        expired_at IS NULL OR expires_at IS NOT NULL),
    CONSTRAINT ck_trip_tender_deadline_after_sent CHECK (
        expires_at IS NULL OR sent_at IS NULL OR expires_at > sent_at),

    CONSTRAINT ck_trip_tender_cancelled_pair CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    CONSTRAINT ck_trip_tender_cancelled_actor_pair CHECK ((cancelled_at IS NULL) = (cancelled_by IS NULL)),
    CONSTRAINT ck_trip_tender_cancel_has_reason CHECK (
        status <> 'CANCELLED' OR btrim(coalesce(cancel_reason, '')) <> ''),

    CONSTRAINT ck_trip_tender_notes_not_blank CHECK (notes IS NULL OR btrim(notes) <> ''),
    CONSTRAINT ck_trip_tender_notes_length CHECK (length(notes) <= 1000),
    CONSTRAINT ck_trip_tender_response_notes_not_blank CHECK (
        response_notes IS NULL OR btrim(response_notes) <> ''),
    CONSTRAINT ck_trip_tender_response_notes_length CHECK (length(response_notes) <= 1000),
    CONSTRAINT ck_trip_tender_cancel_reason_not_blank CHECK (
        cancel_reason IS NULL OR btrim(cancel_reason) <> ''),
    CONSTRAINT ck_trip_tender_cancel_reason_length CHECK (length(cancel_reason) <= 500)
);

-- ---------------------------------------------------------------------------
-- 1a. The two rules that make a tender history safe
-- ---------------------------------------------------------------------------
-- At most one *live* attempt per trip. Without it, two planners each pressing "tender" would put
-- the same shipment in front of the same carrier twice, and a carrier answering one of the two
-- would leave the other hanging with no way to tell which was the real offer. TripTenderService
-- refuses it first with a sentence a planner can read; this index is the concurrency backstop, the
-- same relationship uq_trip_vehicle_active_planning_date (V16) has with
-- TripService.requireVehicleNotDoubleBooked.
CREATE UNIQUE INDEX uq_trip_tender_live
    ON tms.trip_tender (trip_id)
    WHERE status IN ('DRAFT', 'SENT');

-- At most one acceptance per trip, ever. This is the invariant the whole feature exists to
-- guarantee: exactly one carrier has agreed to run this shipment, and no sequence of retries,
-- re-tenders or concurrent responses can produce a second. A partial unique index and not a service
-- check alone, because it is the one rule whose violation would be unrecoverable - two carriers
-- both believing they have the load.
CREATE UNIQUE INDEX uq_trip_tender_accepted
    ON tms.trip_tender (trip_id)
    WHERE status = 'ACCEPTED';

-- The trip's own history, newest attempt first - the order the trip workspace renders it in.
CREATE INDEX ix_trip_tender_trip ON tms.trip_tender (trip_id, attempt DESC);

-- "What have we offered that nobody has answered", ordered by how soon it lapses. The dispatcher's
-- standing question, and the access path the expiry resolution below reads. Partial, because a
-- tender that has been answered is never its answer.
CREATE INDEX ix_trip_tender_company_outstanding
    ON tms.trip_tender (company_id, expires_at)
    WHERE status = 'SENT';

-- The carrier's own inbox: "which shipments am I being offered". Read by the M2M endpoint in
-- section 3, which is the only place it is asked from the carrier's side.
CREATE INDEX ix_trip_tender_company_carrier
    ON tms.trip_tender (company_id, carrier_id, status);

COMMENT ON TABLE tms.trip_tender IS
    'One attempt to place a shipment with a carrier: what was offered, when it was sent, and what '
    'they answered. A trip may have many attempts and at most one live and one accepted, both '
    'enforced by partial unique indexes. Rejections are never deleted - the history is the point.';
COMMENT ON COLUMN tms.trip_tender.attempt IS
    'The nth attempt to place this trip, from 1. Stored rather than derived so it does not change '
    'if a row is ever removed, and so a screen can say "attempt 3" without counting.';
COMMENT ON COLUMN tms.trip_tender.offered_amount IS
    'What TMS offered to pay for this shipment, or NULL when the offer names no price. Never read '
    'from, and never written back to, tms.trip_cost: the estimate is what the tariff says and this '
    'is what somebody offered, and the difference between them is worth keeping.';
COMMENT ON COLUMN tms.trip_tender.response_source IS
    'OPERATOR when a person recorded the answer in the TMS UI - the phone call, the mail - and '
    'INTEGRATION when the carrier''s own credential answered over the M2M API. Different evidence, '
    'and a dispute turns on which it was.';
COMMENT ON COLUMN tms.trip_tender.expired_at IS
    'When TMS resolved that the offer had lapsed, which is not expires_at: with no scheduler, a '
    'lapse is materialised by the next action that touches the tender. Everything that reads a '
    'tender reports it as expired from expires_at onwards regardless - see V31''s header.';

CREATE TRIGGER tr_trip_tender_set_updated_at
    BEFORE UPDATE ON tms.trip_tender
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.trip_tender ENABLE ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE, DELETE ON tms.trip_tender TO tms_app;

-- The tenant policy every business table added after V13 carries (ADR-005).
CREATE POLICY p_tenant_company_scope ON tms.trip_tender
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 1b. Expiry, and why there is no job
-- ---------------------------------------------------------------------------
-- This installation runs no scheduler - there is not one @Scheduled method in the backend, and
-- introducing one for this feature would mean introducing the whole question of how it behaves
-- across two application instances. So expiry is resolved in the two places that cannot be wrong:
--
--   * on every read - TenderStatus/TripTender report a SENT tender past its deadline as EXPIRED, so
--     no screen ever shows a live offer that has lapsed and no response is ever accepted after the
--     deadline. This is the half that matters for correctness, and it needs no write at all;
--   * on the next write that touches the trip's tenders - TripTenderService materialises the lapse
--     into this table, audits it and publishes it. This is the half that matters for reporting, and
--     it is what frees uq_trip_tender_live so the next attempt can be created.
--
-- The consequence, stated plainly rather than hidden: a tender that lapses and is never touched
-- again keeps status = 'SENT' in this table while every API and every screen calls it EXPIRED. A
-- report reading this column directly must apply the same rule (status = 'SENT' AND expires_at <
-- now() means expired), which is why ix_trip_tender_company_outstanding indexes expires_at.
--
-- The day a reliable scheduler exists, a sweep that materialises lapses on a timer is a new class
-- and changes nothing here - the states, the columns and the reads are already correct.

-- ---------------------------------------------------------------------------
-- 2. tms.integration_client.carrier_id - the carrier that answers for itself
-- ---------------------------------------------------------------------------
-- The whole point of tendering over an API is that the *carrier* answers, not a clerk at the
-- shipper answering on their behalf. That needs a credential that is one carrier and no other, and
-- V18's integration_client is a credential bound to a company and nothing narrower.
--
-- So: optional, and meaningful only together with the integration.tender:respond scope in section
-- 3. A credential with no carrier is every credential that exists today - an ERP, a WMS, a
-- telematics feed - and none of them gains anything from this column. A credential *with* one is a
-- carrier's key: it can see the tenders addressed to that carrier and answer them, and it can do
-- nothing else unless it also holds another scope.
--
-- Nullable rather than a second table, because this is one attribute of a credential and not a
-- relationship with a life of its own. A carrier with two systems gets two credentials, exactly as
-- a partner writing into two companies does.
ALTER TABLE tms.integration_client ADD COLUMN carrier_id uuid;

ALTER TABLE tms.integration_client ADD CONSTRAINT fk_integration_client_carrier
    FOREIGN KEY (carrier_id) REFERENCES tms.carrier (id) ON DELETE RESTRICT;
-- Rule 6 again: a credential's carrier belongs to the credential's own company. MATCH SIMPLE (the
-- default) is what makes this satisfied-when-NULL, the same idiom as fk_vehicle_carrier_company.
ALTER TABLE tms.integration_client ADD CONSTRAINT fk_integration_client_carrier_company
    FOREIGN KEY (carrier_id, company_id) REFERENCES tms.carrier (id, company_id);

CREATE INDEX ix_integration_client_carrier
    ON tms.integration_client (carrier_id)
    WHERE carrier_id IS NOT NULL;

COMMENT ON COLUMN tms.integration_client.carrier_id IS
    'The carrier this credential answers for, or NULL for an ordinary partner credential. Set only '
    'for a carrier''s own key: it is what makes integration.tender:respond mean "answer MY tenders" '
    'rather than "answer anybody''s". An endpoint that reads it must refuse a credential that has '
    'none rather than falling back to the company.';

-- ---------------------------------------------------------------------------
-- 3. integration.tender:respond
-- ---------------------------------------------------------------------------
-- One scope and not a read/write pair, unlike every permission in tms.permission. A carrier reading
-- the tenders addressed to them and answering those same tenders is one capability from one party's
-- point of view - there is no role that should see its own offers and be unable to answer them, and
-- no role that should answer offers it cannot read. Splitting it would produce two scopes that are
-- always granted together, which is the failure mode IntegrationScope's own comment warns about
-- ("a narrow, purpose-built key that should hold one or two capabilities and nothing else").
--
-- Deliberately not integration.shipment:read. That scope exposes every confirmed shipment of the
-- company; this one exposes the tenders of one carrier and the shipment header behind each. A
-- carrier must not learn what the shipper's other carriers are running, and a credential holding
-- this scope learns nothing about a shipment it was never offered.
ALTER TABLE tms.integration_client_scope DROP CONSTRAINT ck_integration_client_scope_value;
ALTER TABLE tms.integration_client_scope ADD CONSTRAINT ck_integration_client_scope_value CHECK (
    scope IN ('integration.location:write', 'integration.order:write', 'integration.shipment:read',
              'integration.tracking:write', 'integration.tender:respond'));

-- ---------------------------------------------------------------------------
-- 4. planning.tender:*
-- ---------------------------------------------------------------------------
-- Its own resource rather than part of planning.trip:manage, for the reason V25 gave when it split
-- planning.trip:execute out: placing a shipment with a carrier is a commercial act with a price on
-- it, building the plan is not, and an installation may well want the two in different hands.
INSERT INTO tms.permission (resource, action, description) VALUES
    ('planning.tender', 'read',   'View the tenders offered on a shipment and their answers'),
    ('planning.tender', 'manage', 'Offer a shipment to its carrier, withdraw it and record the answer');

INSERT INTO tms.role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM tms.role r
CROSS JOIN tms.permission p
WHERE r.code IN ('ORGANIZATION_ADMIN', 'COMPANY_ADMIN', 'PLANNER')
  AND p.code IN ('planning.tender:read', 'planning.tender:manage');

-- VIEWER gets neither, which is the same call V30 made for rates and for the same reason: a tender
-- carries an offered price, and what one company offers another is commercially sensitive by
-- default. An installation that wants its read-only accounts to watch tender status grants the read
-- permission on purpose; one that never thought about it does not publish its freight rates to
-- every viewer account.

-- ---------------------------------------------------------------------------
-- 5. The event a partner is told about, the timeline entry, and the audit action
-- ---------------------------------------------------------------------------
-- All five transitions are published, and that is a departure from V27's rule that only trip-level
-- state changes reach the outbox. It is justified by who the audience is: TENDER_SENT is how an
-- integrated carrier learns there is an offer waiting at all, and TENDER_CANCELLED is how they
-- learn it has been withdrawn before they answer it. Without those two the M2M endpoint in section
-- 3 would be a thing a carrier had to poll blind.
--
-- TENDER_ACCEPTED, TENDER_REJECTED and TENDER_EXPIRED are published for the other side of the same
-- integration: the shipper's ERP, which has to know whether the load is placed before it prints a
-- manifest. They are also the carrier's own echo, confirming TMS recorded what they sent.
--
-- The row still carries the shipment number and nothing else, exactly as V20 designed it. Which
-- carrier, how much and why travel in the tender itself, which the reader fetches.
ALTER TABLE tms.shipment_outbox_event DROP CONSTRAINT ck_shipment_outbox_event_type;
ALTER TABLE tms.shipment_outbox_event ADD CONSTRAINT ck_shipment_outbox_event_type CHECK (
    event_type IN ('SHIPMENT_CONFIRMED', 'SHIPMENT_CHANGED', 'SHIPMENT_CANCELLED',
                   'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED',
                   'DELIVERY_RESULT_RECORDED',
                   'TENDER_SENT', 'TENDER_ACCEPTED', 'TENDER_REJECTED', 'TENDER_EXPIRED',
                   'TENDER_CANCELLED'));

COMMENT ON COLUMN tms.shipment_outbox_event.event_type IS
    'One row per publishable change. SHIPMENT_CONFIRMED (V19/V20) is written by '
    'PlanningRunService.confirm; SHIPMENT_READY, SHIPMENT_DISPATCHED, SHIPMENT_COMPLETED and '
    'SHIPMENT_CANCELLED by TripExecutionService (V25); DELIVERY_RESULT_RECORDED by '
    'TripDeliveryService (V28); the five TENDER_* by TripTenderService (V31) - the first family '
    'whose audience is the carrier rather than the shipper''s own back office. All are written in '
    'the same transaction as the fact they describe. SHIPMENT_CHANGED still has no source.';

-- The same five in the operational timeline. A tender is trip-scoped and never names a stop, so it
-- joins the TRIP_* branch of ck_transport_event_stop_scope: an offer is made for the whole
-- shipment, and there is no one stop it could be about.
--
-- Worth the entries, unlike V27's per-stop transitions were not: "10:12 offered to ACME - 10:40
-- ACME accepted" is precisely what a dispatcher opening a shipment at 11:00 needs in order to know
-- whether the truck is coming, and it is the one part of a shipment's day that involves a party
-- outside this company.
ALTER TABLE tms.transport_event DROP CONSTRAINT ck_transport_event_type;
ALTER TABLE tms.transport_event ADD CONSTRAINT ck_transport_event_type CHECK (event_type IN (
    'TRIP_CONFIRMED', 'TRIP_READY', 'TRIP_DISPATCHED', 'TRIP_COMPLETED', 'TRIP_CANCELLED',
    'ARRIVED_AT_STOP', 'SERVICE_STARTED', 'STOP_COMPLETED', 'STOP_SKIPPED', 'STOP_FAILED',
    'DELIVERY_RECORDED',
    'TENDER_SENT', 'TENDER_ACCEPTED', 'TENDER_REJECTED', 'TENDER_EXPIRED', 'TENDER_CANCELLED',
    'EXCEPTION_REPORTED', 'EXCEPTION_RESOLVED'));

ALTER TABLE tms.transport_event DROP CONSTRAINT ck_transport_event_stop_scope;
ALTER TABLE tms.transport_event ADD CONSTRAINT ck_transport_event_stop_scope CHECK (
    CASE
        WHEN event_type IN ('ARRIVED_AT_STOP', 'SERVICE_STARTED', 'STOP_COMPLETED',
                            'STOP_SKIPPED', 'STOP_FAILED', 'DELIVERY_RECORDED')
            THEN trip_stop_id IS NOT NULL
        WHEN event_type IN ('TRIP_CONFIRMED', 'TRIP_READY', 'TRIP_DISPATCHED', 'TRIP_COMPLETED',
                            'TRIP_CANCELLED',
                            'TENDER_SENT', 'TENDER_ACCEPTED', 'TENDER_REJECTED', 'TENDER_EXPIRED',
                            'TENDER_CANCELLED') THEN trip_stop_id IS NULL
        ELSE true
    END);

-- And the audit trail. Five actions rather than one TENDER_UPDATED, because each is a question
-- somebody asks by itself: "when did we offer this and to whom", "who accepted it, and were they
-- the carrier or were they us", "who said no and why", "did it lapse", "who pulled it back".
--
-- No new aggregate type: these are audited against SHIPMENT, exactly as V28's
-- DELIVERY_RESULT_RECORDED is, with the tender's id and attempt number in the metadata. The thing
-- that changed commercially is the shipment; the tender is how it changed.
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
    'CREATE', 'UPDATE', 'ACTIVATE', 'DEACTIVATE', 'ASSIGN_ORDER', 'REMOVE_ORDER', 'MOVE_ORDER',
    'VEHICLE_CHANGE', 'DRIVER_CHANGE', 'CONFIRM', 'CANCEL', 'CREDENTIAL_CREATE',
    'CREDENTIAL_ROTATE', 'CREDENTIAL_REVOKE', 'AUTO_PLAN', 'IMPORT_EXECUTED', 'SHIPMENT_CONFIRMED',
    'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED', 'SHIPMENT_CANCELLED',
    'DELIVERY_RESULT_RECORDED', 'COST_ESTIMATED', 'COST_ACTUAL_RECORDED', 'COST_CLOSED',
    'COST_REOPENED',
    'TENDER_SENT', 'TENDER_ACCEPTED', 'TENDER_REJECTED', 'TENDER_EXPIRED', 'TENDER_CANCELLED'));

-- Creating a draft tender is deliberately NOT audited. It publishes nothing, tells nobody, and can
-- be edited freely until it is sent - TENDER_SENT is the first moment anything left this company,
-- and auditing the keystrokes before it would fill the compliance trail with drafts.

-- ---------------------------------------------------------------------------
-- Deliberately NOT here
-- ---------------------------------------------------------------------------
--   * No multi-carrier tender and no bidding. Offering one load to three carriers at once needs a
--     rule for what happens when two accept (first wins? cheapest wins? the planner chooses?), and
--     that rule is a commercial policy per company, not a schema decision. uq_trip_tender_live is
--     the honest V1 answer: one live offer at a time, and the second attempt starts when the first
--     ends.
--   * No automatic waterfall down a carrier preference list. It is the natural next feature and it
--     needs two things that do not exist: an ordered carrier preference per lane, and a scheduler
--     to advance the waterfall when an offer lapses. Both are named in section 1b and in
--     docs/domain/CARRIER_TENDERING_V1.md.
--   * No spot rate, no counter-offer, no negotiation thread. A carrier answers yes or no. A carrier
--     who wants a different price says so in response_notes and the planner sends a new tender,
--     which is a second attempt and is recorded as one. A counter-offer field would be the first
--     half of a negotiation model whose second half is a rule for accepting one, and nobody has
--     asked for it.
--   * No tender on a DRAFT trip, enforced by TripTenderService and not by a CHECK - a CHECK here
--     cannot see tms.trip.status. Offering a plan that is still being rearranged would show a
--     carrier a shipment whose stops, load and vehicle can all still change, and the outbound
--     shipment API already refuses to expose a draft for exactly that reason.
--   * No reassignment to a different carrier after a rejection. A trip's carrier comes from its
--     vehicle and the vehicle may only be swapped while the trip is a DRAFT (V11/V25), so placing a
--     rejected shipment elsewhere means cancelling and replanning it today. Widening the vehicle
--     window is a change to the capacity snapshot and to what a confirmed shipment means, which is
--     a bigger decision than tendering should make on its way past.
--   * No acceptance requirement before dispatch. Nothing in the lifecycle asks whether a tender was
--     accepted, because an installation that never tenders must still be able to send a truck. The
--     trip workspace shows the tender state beside the dispatch button and lets a dispatcher
--     decide, which is where that judgement belongs in V1.
--   * No link between tms.trip_tender.offered_amount and tms.trip_cost. See the column comment.
--   * No carrier-facing web portal. The two ways a carrier answers are the M2M endpoint (their
--     system) and a person recording what they said (the phone). A portal needs external identity,
--     invitation, password reset and session management for users who are not in tms.app_user, and
--     that is a product, not an endpoint.
