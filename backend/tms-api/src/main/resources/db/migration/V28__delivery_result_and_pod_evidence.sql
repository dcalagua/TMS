-- TMS by EBIM - V28 delivery result and proof of delivery: what was actually handed over at each
-- stop, and the evidence that says so.
--
-- Design and the full decision record: docs/domain/PROOF_OF_DELIVERY_V1.md.
--
-- V27 closed with a list of what it deliberately left out, and the first two items were this one:
--
--   * "No proof of delivery, no signature, no photo, no per-order delivered/refused quantity. A
--     COMPLETED stop here means 'the vehicle served this destination', which is a dispatcher's
--     fact. Recording *what was handed over* is an order-level model with its own quantities and
--     its own disputes."
--
-- That was the right call then and it is the wrong call now, because the question a customer
-- service desk spends its day on is not answerable from a stop:
--
--   * "the customer says one of the three orders on that stop was refused - which one, and why?"
--   * "they are disputing the delivery; who signed for it, and what do we have on file?"
--
-- A stop cannot answer either. A stop serves a *destination*, and a destination can take three
-- orders and refuse the fourth - so the commercial outcome belongs one level down, at the order,
-- and it belongs *beside* the stop's operational status rather than inside it.
--
-- Two tables, and a deliberate refusal to build a third:
--
--   1. tms.order_delivery  - the commercial result of one order at one stop: delivered, partial,
--      rejected, failed, or never attempted, with who received it and when.
--   2. tms.delivery_evidence - metadata for the artefacts (a signature, a photo, a signed
--      delivery note) that back that result up. Metadata *only*: the bytes live in a private
--      object store behind shared.storage.EvidenceStoragePort, and this schema never sees them.
--
-- The third table this is not: no document management system. There is no folder, no version, no
-- retention policy, no sharing model and no full-text index, because none of those is a transport
-- rule. Evidence here exists to settle "did we deliver it" and nothing else.

-- ---------------------------------------------------------------------------
-- 1. tms.order_delivery - the commercial outcome, one row per order per stop
-- ---------------------------------------------------------------------------
-- Why (trip_stop_id, order_id) and not (trip_id, order_id): an order is delivered *somewhere*, and
-- the stop is where. Keying on the stop is what makes "show me this stop's delivery outcomes" a
-- single-column lookup, and what makes it structurally impossible to record a delivery against a
-- trip without saying at which of its destinations it happened. trip_id is carried alongside
-- rather than joined for, because every read of this table so far is "one trip's deliveries", and
-- because it is what lets the composite tenant FK point at tms.trip directly.
--
-- Why this is not a status on tms.trip_stop: five outcomes on the stop would say the whole stop
-- was rejected, which is exactly the case this exists to distinguish. StopExecutionStatus stays
-- what it is - what the *vehicle* did there - and this is what the *goods* did. A stop can be
-- COMPLETED with one of its orders REJECTED, and both statements are true.
--
-- Why this is not a status on tms.transport_order either: the orders module owns that lifecycle
-- (V27's "the orders module owns that lifecycle and will extend it in its own migration"), and
-- nothing here weakens it - an order whose delivery is recorded here is still PLANNED as far as
-- tms.transport_order is concerned. Two owners for one column is how a status ends up meaning
-- different things in two screens.
CREATE TABLE tms.order_delivery (
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id          uuid        NOT NULL,
    trip_id             uuid        NOT NULL,
    trip_stop_id        uuid        NOT NULL,
    order_id            uuid        NOT NULL,
    result              text        NOT NULL,
    -- When the handover happened, operator-supplied - the same business-time-not-keyboard-time
    -- rule V25 established for the trip and V27 for its stops. Which results require it and which
    -- forbid it is enforced below: NOT_ATTEMPTED has no moment to record, and DELIVERED cannot be
    -- claimed without one.
    delivered_at        timestamptz,
    -- Who took the goods. Both optional and both personal data: a name and, where a company asks
    -- for it, an identity document. They are stored because a disputed delivery is settled by
    -- naming the person who signed for it, and they are optional because plenty of deliveries are
    -- left at a dock with a stamp and no name. Never required by any result - see the receiver
    -- CHECK for the one rule they do carry.
    receiver_name       text,
    receiver_document   text,
    notes               text,
    -- OPERATOR / SYSTEM / INTEGRATION, the same three tms.transport_event carries and for the same
    -- reason: a delivery result typed by a dispatcher and one posted by a partner's driver app are
    -- different facts, and the difference has to survive in the row.
    source              text        NOT NULL,
    -- ...and when it was typed, against delivered_at's when-it-happened. A delivery recorded at
    -- 18:40 for a handover at 09:15 is an end-of-day paperwork run, which is the ordinary case and
    -- must be visible as such.
    recorded_at         timestamptz NOT NULL DEFAULT now(),
    -- The same three-column actor tms.audit_event (V22) and tms.transport_event (V27) carry: a
    -- machine gets no invented app_user row, and the email is a snapshot so a later address change
    -- does not rewrite who signed off a delivery two years ago.
    actor_app_user_id   uuid,
    actor_email         text,
    actor_machine_label text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_by          uuid,
    CONSTRAINT pk_order_delivery PRIMARY KEY (id),
    -- The target of tms.delivery_evidence's composite tenant FK (DATA_MODEL.md rule 6).
    CONSTRAINT uq_order_delivery_id_company UNIQUE (id, company_id),
    -- One outcome per order per stop. A correction overwrites this row rather than appending a
    -- second one, and loses nothing by doing so: every recording also appends a DELIVERY_RECORDED
    -- entry to tms.transport_event, which is append-only, so the history of what was claimed and
    -- when lives there. Two rows here would instead make "was this order delivered" a question
    -- with two answers and an ordering rule to pick between them.
    CONSTRAINT uq_order_delivery_stop_order UNIQUE (trip_stop_id, order_id),
    CONSTRAINT fk_order_delivery_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- RESTRICT everywhere, exactly as V27's log tables: a delivery record that a delete could
    -- silently take with it is not a record. Trips and orders are cancelled, never deleted.
    CONSTRAINT fk_order_delivery_trip FOREIGN KEY (trip_id)
        REFERENCES tms.trip (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_delivery_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),
    CONSTRAINT fk_order_delivery_trip_stop FOREIGN KEY (trip_stop_id)
        REFERENCES tms.trip_stop (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_delivery_trip_stop_company FOREIGN KEY (trip_stop_id, company_id)
        REFERENCES tms.trip_stop (id, company_id),
    CONSTRAINT fk_order_delivery_order FOREIGN KEY (order_id)
        REFERENCES tms.transport_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_delivery_order_company FOREIGN KEY (order_id, company_id)
        REFERENCES tms.transport_order (id, company_id),
    CONSTRAINT fk_order_delivery_actor FOREIGN KEY (actor_app_user_id)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    -- Five results, mirrored by planning.domain.DeliveryResult. Deliberately no IN_PROGRESS and no
    -- PENDING: the absence of a row *is* "not recorded yet", and a state meaning the same thing
    -- would give the same fact two representations - the trap V25 avoided with DISPATCHED.
    --
    --   DELIVERED     - handed over in full.
    --   PARTIAL       - some of it was taken and some was not. The quantities are NOT modelled
    --                   here (see "Deliberately NOT here"); the note says what was short.
    --   REJECTED      - the customer refused it. Somebody was there and said no.
    --   FAILED        - the attempt failed for a reason that is not a refusal: nobody at the
    --                   address, dock closed, goods damaged in transit and pulled back.
    --   NOT_ATTEMPTED - never taken off the vehicle. The stop was skipped, or the order was left
    --                   on board for tomorrow. Carries no delivered_at at all.
    CONSTRAINT ck_order_delivery_result CHECK (result IN (
        'DELIVERED', 'PARTIAL', 'REJECTED', 'FAILED', 'NOT_ATTEMPTED')),
    CONSTRAINT ck_order_delivery_source CHECK (source IN ('OPERATOR', 'SYSTEM', 'INTEGRATION')),
    CONSTRAINT ck_order_delivery_actor_xor CHECK (
        (actor_app_user_id IS NOT NULL) <> (actor_machine_label IS NOT NULL)),
    CONSTRAINT ck_order_delivery_actor_email_scope CHECK (
        actor_email IS NULL OR actor_app_user_id IS NOT NULL),
    CONSTRAINT ck_order_delivery_operator_is_person CHECK (
        source <> 'OPERATOR' OR actor_app_user_id IS NOT NULL),
    -- A result that claims goods changed hands carries the moment they did; one that says nothing
    -- was attempted carries none. REJECTED and FAILED are in between on purpose: the vehicle was
    -- at the door at some time, and recording it is useful, but a driver who never got out has no
    -- handover moment to report.
    CONSTRAINT ck_order_delivery_delivered_at_required CHECK (
        result NOT IN ('DELIVERED', 'PARTIAL') OR delivered_at IS NOT NULL),
    CONSTRAINT ck_order_delivery_not_attempted_has_no_time CHECK (
        result <> 'NOT_ATTEMPTED' OR delivered_at IS NULL),
    -- A receiver is somebody who was present: they took the goods, took part of them, or refused
    -- them to the driver's face. FAILED and NOT_ATTEMPTED mean nobody did any of those, and a name
    -- on such a row could only be the driver's own - which is not what this column means.
    CONSTRAINT ck_order_delivery_receiver_scope CHECK (
        result IN ('DELIVERED', 'PARTIAL', 'REJECTED')
        OR (receiver_name IS NULL AND receiver_document IS NULL)),
    -- Anything that did not go to plan carries a sentence. DELIVERED needs none - the result says
    -- everything - and NOT_ATTEMPTED is already explained by the stop that was skipped or failed,
    -- which V27 requires a typed tms.trip_exception for. The other three are the ones a customer
    -- will ring about, and "PARTIAL" on its own is not an answer to that call.
    CONSTRAINT ck_order_delivery_shortfall_requires_notes CHECK (
        result NOT IN ('PARTIAL', 'REJECTED', 'FAILED') OR btrim(coalesce(notes, '')) <> ''),
    CONSTRAINT ck_order_delivery_notes_not_blank CHECK (notes IS NULL OR btrim(notes) <> ''),
    CONSTRAINT ck_order_delivery_notes_length CHECK (length(notes) <= 1000),
    CONSTRAINT ck_order_delivery_receiver_name_not_blank
        CHECK (receiver_name IS NULL OR btrim(receiver_name) <> ''),
    CONSTRAINT ck_order_delivery_receiver_name_length CHECK (length(receiver_name) <= 120),
    CONSTRAINT ck_order_delivery_receiver_document_not_blank
        CHECK (receiver_document IS NULL OR btrim(receiver_document) <> ''),
    CONSTRAINT ck_order_delivery_receiver_document_length CHECK (length(receiver_document) <= 60),
    CONSTRAINT ck_order_delivery_actor_email_not_blank
        CHECK (actor_email IS NULL OR btrim(actor_email) <> ''),
    CONSTRAINT ck_order_delivery_actor_machine_label_not_blank
        CHECK (actor_machine_label IS NULL OR btrim(actor_machine_label) <> '')
);

COMMENT ON TABLE tms.order_delivery IS
    'The commercial outcome of one order at one trip stop: delivered, partial, rejected, failed or '
    'never attempted, with who received it and when. Beside tms.trip_stop.execution_status, never '
    'inside it - a stop can be COMPLETED with one of its orders REJECTED, and both are true. Does '
    'not move tms.transport_order.status: the orders module owns that lifecycle.';
COMMENT ON COLUMN tms.order_delivery.result IS
    'One of DELIVERED, PARTIAL, REJECTED, FAILED, NOT_ATTEMPTED. There is no "pending" value: an '
    'order with no row here has simply not been recorded yet, which is the same statement.';
COMMENT ON COLUMN tms.order_delivery.receiver_name IS
    'Personal data, kept because a disputed delivery is settled by naming who signed for it. Never '
    'required, and never present on a result where nobody was there to receive anything.';
COMMENT ON COLUMN tms.order_delivery.notes IS
    'What was short, why it was refused, why the attempt failed. Required for exactly those three '
    'results and never for the others.';

-- "This trip's deliveries", which is how the workspace reads them: one query per trip detail.
CREATE INDEX ix_order_delivery_company_trip ON tms.order_delivery (company_id, trip_id);

-- "Has this order ever been delivered, and on which shipment" - the customer-service lookup, and
-- the access path the outbound shipment payload uses to report a result per order.
CREATE INDEX ix_order_delivery_order ON tms.order_delivery (order_id);

-- "What did not arrive today" - the cross-trip read that the whole table exists to make cheap.
-- Partial on the four non-clean results: by the end of a normal day almost every row is DELIVERED,
-- which is exactly what makes the partial index small.
CREATE INDEX ix_order_delivery_company_shortfall
    ON tms.order_delivery (company_id, recorded_at DESC)
    WHERE result <> 'DELIVERED';

CREATE TRIGGER tr_order_delivery_set_updated_at
    BEFORE UPDATE ON tms.order_delivery
    FOR EACH ROW EXECUTE FUNCTION tms.set_updated_at();

ALTER TABLE tms.order_delivery ENABLE ROW LEVEL SECURITY;

-- Three verbs, not four. A delivery result is recorded, corrected while the shipment is still
-- being worked, and never deleted - "we decided this delivery never happened" is itself a result
-- (NOT_ATTEMPTED), not the absence of one. TripDeliveryService refuses a correction once the trip
-- is closed; the missing DELETE grant is what makes the other half unreachable at any time.
GRANT SELECT, INSERT, UPDATE ON tms.order_delivery TO tms_app;
REVOKE DELETE ON tms.order_delivery FROM tms_app;

CREATE POLICY p_tenant_company_scope ON tms.order_delivery
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 2. tms.delivery_evidence - the artefacts, by reference only
-- ---------------------------------------------------------------------------
-- Metadata for one stored object per row. What this table does NOT have is the point of it:
--
--   * no bytea, no base64 text column, no "content" of any kind. A signature bitmap or a photo of
--     a pallet is between 50 KB and several MB; putting those in the row would put them in every
--     backup, every replica, every dump a developer takes, and in the WAL of a database that is
--     sized for transport rows. The bytes go to a private object store and this table keeps the
--     key that finds them.
--   * no URL. Not a signed one, and emphatically not a public one: a permanent public link to a
--     customer's signed delivery note is a data leak with a stable address. Bytes are served by
--     TripDeliveryEvidenceController, which re-checks the company scope and the permission on
--     every single read - the same rule the rest of the API follows, applied to files.
--
-- storage_key is opaque and server-generated (company/year/month/uuid). It is never built from
-- anything the caller sent, which is what makes path traversal not a class of bug here rather
-- than a filter that has to be right.
CREATE TABLE tms.delivery_evidence (
    id                uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id        uuid        NOT NULL,
    order_delivery_id uuid        NOT NULL,
    evidence_type     text        NOT NULL,
    -- The object store's own identifier for the bytes. Unique per company so a key can never be
    -- claimed twice, and long enough for a path scheme that has room to change.
    storage_key       text        NOT NULL,
    content_type      text        NOT NULL,
    size_bytes        bigint      NOT NULL,
    -- SHA-256 of the stored bytes, lower-case hex. Not a security control - the store is trusted -
    -- but the answer to "is the file we are showing in a dispute the one that was uploaded", which
    -- is the question that gets asked once and cannot be answered afterwards if nobody computed it.
    checksum_sha256   char(64)    NOT NULL,
    -- What the operator's device called it, kept for display only. Never used to build the storage
    -- key, never echoed into a file system path.
    original_filename text,
    -- When the photo was taken or the signature captured, if that is known to differ from the
    -- upload. Optional: a scan made at the depot in the evening has no meaningful capture time
    -- other than the upload itself.
    captured_at       timestamptz,
    uploaded_at       timestamptz NOT NULL DEFAULT now(),
    -- NOT NULL and a person, unlike order_delivery's actor: V1 has no unattended uploader. An
    -- integration that posts evidence will make this nullable in its own migration, which is a
    -- one-line change; allowing nulls nobody produces is the change that cannot be undone.
    uploaded_by       uuid        NOT NULL,
    CONSTRAINT pk_delivery_evidence PRIMARY KEY (id),
    CONSTRAINT uq_delivery_evidence_company_key UNIQUE (company_id, storage_key),
    CONSTRAINT fk_delivery_evidence_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_evidence_delivery FOREIGN KEY (order_delivery_id)
        REFERENCES tms.order_delivery (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_evidence_delivery_company FOREIGN KEY (order_delivery_id, company_id)
        REFERENCES tms.order_delivery (id, company_id),
    CONSTRAINT fk_delivery_evidence_uploaded_by FOREIGN KEY (uploaded_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    -- Three types, mirrored by planning.domain.EvidenceType. SIGNATURE is a captured signature
    -- image and NOT a digital signature in the legal sense - TMS makes no such claim, and the
    -- column name would be the first place that claim would be made by accident.
    CONSTRAINT ck_delivery_evidence_type CHECK (evidence_type IN ('SIGNATURE', 'PHOTO', 'DOCUMENT')),
    -- A hard ceiling well above the application's own configurable limit
    -- (tms.storage.evidence.max-file-size). This one exists so a misconfigured deployment cannot
    -- record a 2 GB object; the application refuses far earlier and with a readable message.
    CONSTRAINT ck_delivery_evidence_size CHECK (size_bytes > 0 AND size_bytes <= 26214400),
    CONSTRAINT ck_delivery_evidence_checksum_format CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_delivery_evidence_storage_key_not_blank CHECK (btrim(storage_key) <> ''),
    CONSTRAINT ck_delivery_evidence_storage_key_length CHECK (length(storage_key) <= 512),
    CONSTRAINT ck_delivery_evidence_content_type_not_blank CHECK (btrim(content_type) <> ''),
    CONSTRAINT ck_delivery_evidence_content_type_length CHECK (length(content_type) <= 120),
    CONSTRAINT ck_delivery_evidence_filename_not_blank
        CHECK (original_filename IS NULL OR btrim(original_filename) <> ''),
    CONSTRAINT ck_delivery_evidence_filename_length CHECK (length(original_filename) <= 255)
);

COMMENT ON TABLE tms.delivery_evidence IS
    'Metadata for one stored proof-of-delivery artefact - signature, photo or document. The bytes '
    'live in a private object store behind shared.storage.EvidenceStoragePort and are served only '
    'by an authenticated, company-scoped endpoint. This table holds no content and no URL, by '
    'decision: a permanent public link to a signed delivery note is a leak with a stable address.';
COMMENT ON COLUMN tms.delivery_evidence.storage_key IS
    'Opaque, server-generated object key. Never derived from a caller-supplied file name, which is '
    'what makes path traversal impossible here rather than filtered.';
COMMENT ON COLUMN tms.delivery_evidence.evidence_type IS
    'SIGNATURE is a captured signature image, not a digital signature in the legal sense - TMS '
    'makes no such claim.';

CREATE INDEX ix_delivery_evidence_delivery ON tms.delivery_evidence (order_delivery_id);

ALTER TABLE tms.delivery_evidence ENABLE ROW LEVEL SECURITY;

-- Append-only as a database fact, exactly as tms.audit_event (V22) and tms.transport_event (V27)
-- are: evidence is the thing a dispute is settled with, and evidence that can be edited or removed
-- by the party holding it is not evidence. Replacing a wrong photo means uploading the right one
-- beside it; the delivery record's note says which is which.
GRANT SELECT, INSERT ON tms.delivery_evidence TO tms_app;
REVOKE UPDATE, DELETE ON tms.delivery_evidence FROM tms_app;

CREATE POLICY p_tenant_company_scope_select ON tms.delivery_evidence
    FOR SELECT TO tms_app
    USING (company_id = tms.current_company_id());

CREATE POLICY p_tenant_company_scope_insert ON tms.delivery_evidence
    FOR INSERT TO tms_app
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 3. The timeline entry a recorded delivery produces
-- ---------------------------------------------------------------------------
-- V27 predicted this widening in its own comment about the position columns: "the day a driver app
-- or a telematics integration does report a position, recording it is an application change and
-- not a migration". The event *type* list is the half that is not open, deliberately - a CHECK is
-- what keeps the vocabulary honest - so a new kind of fact costs exactly this: one ALTER, in the
-- migration that introduces the fact.
--
-- DELIVERY_RECORDED is stop-scoped: it always names the stop the delivery happened at, so it lands
-- in the same clause as ARRIVED_AT_STOP and STOP_COMPLETED rather than in the "either" branch.
-- Which order it was about travels in metadata, where the order number belongs - the timeline is
-- read as a sequence of stops, not as a ledger of orders.
ALTER TABLE tms.transport_event DROP CONSTRAINT ck_transport_event_type;
ALTER TABLE tms.transport_event ADD CONSTRAINT ck_transport_event_type CHECK (event_type IN (
    'TRIP_CONFIRMED', 'TRIP_READY', 'TRIP_DISPATCHED', 'TRIP_COMPLETED', 'TRIP_CANCELLED',
    'ARRIVED_AT_STOP', 'SERVICE_STARTED', 'STOP_COMPLETED', 'STOP_SKIPPED', 'STOP_FAILED',
    'DELIVERY_RECORDED',
    'EXCEPTION_REPORTED', 'EXCEPTION_RESOLVED'));

ALTER TABLE tms.transport_event DROP CONSTRAINT ck_transport_event_stop_scope;
ALTER TABLE tms.transport_event ADD CONSTRAINT ck_transport_event_stop_scope CHECK (
    CASE
        WHEN event_type IN ('ARRIVED_AT_STOP', 'SERVICE_STARTED', 'STOP_COMPLETED',
                            'STOP_SKIPPED', 'STOP_FAILED', 'DELIVERY_RECORDED')
            THEN trip_stop_id IS NOT NULL
        WHEN event_type IN ('TRIP_CONFIRMED', 'TRIP_READY', 'TRIP_DISPATCHED', 'TRIP_COMPLETED',
                            'TRIP_CANCELLED') THEN trip_stop_id IS NULL
        ELSE true
    END);

-- ---------------------------------------------------------------------------
-- 4. The event a partner is told about
-- ---------------------------------------------------------------------------
-- The first outbox event that is not a trip-state change, and the reason the table was built with
-- an event_type rather than a status column. A partner that has been told a shipment is IN_TRANSIT
-- learns nothing more until it completes, and "order 4711 was refused at 11:20" is precisely the
-- fact an ERP has to act on before then - it is what triggers a credit note, a re-delivery or a
-- customer call.
--
-- The row still carries nothing but the shipment number, exactly as V20 designed it: the partner
-- re-reads GET /integration/v1/shipments/{shipmentNumber}, whose order list now reports each
-- order's delivery result (see docs/integrations/OUTBOUND_SHIPMENT_V1.md). Putting the order and
-- the result *in* the event would make the outbox a second copy of the shipment, drifting from the
-- first, which is the failure mode the "shipment number and nothing else" rule prevents.
--
-- Deliberately not named DELIVERY_FAILED or split per result: a partner subscribes to "a delivery
-- outcome was recorded" and reads what it was. Five event types where one plus a re-read does the
-- job would freeze today's five results into the wire contract.
ALTER TABLE tms.shipment_outbox_event DROP CONSTRAINT ck_shipment_outbox_event_type;
ALTER TABLE tms.shipment_outbox_event ADD CONSTRAINT ck_shipment_outbox_event_type CHECK (
    event_type IN ('SHIPMENT_CONFIRMED', 'SHIPMENT_CHANGED', 'SHIPMENT_CANCELLED',
                   'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED',
                   'DELIVERY_RESULT_RECORDED'));

COMMENT ON COLUMN tms.shipment_outbox_event.event_type IS
    'One row per publishable change. SHIPMENT_CONFIRMED (V19/V20) is written by '
    'PlanningRunService.confirm; SHIPMENT_READY, SHIPMENT_DISPATCHED, SHIPMENT_COMPLETED and '
    'SHIPMENT_CANCELLED by TripExecutionService (V25); DELIVERY_RESULT_RECORDED by '
    'TripDeliveryService (V28) - the first one that is not a trip-state change, and the reason '
    'this column is an event type and not a status. All are written in the same transaction as '
    'the fact they describe. SHIPMENT_CHANGED still has no source.';

-- ---------------------------------------------------------------------------
-- 5. The audit action
-- ---------------------------------------------------------------------------
-- V27 argued against an audit row per *stop* transition, and that argument is unchanged: the
-- operational timeline already holds those, in an append-only, actor-stamped table. A delivery
-- result is the exception it named - "somebody confirmed this shipment" is a compliance fact as
-- well as an operational one, and so is "somebody recorded that the customer refused these goods".
-- It is the record a dispute, an insurance claim or a credit note is argued from, and the audit
-- trail is where a compliance reader looks for it, under one correlation id with everything else
-- that request did.
ALTER TABLE tms.audit_event DROP CONSTRAINT ck_audit_event_action;
ALTER TABLE tms.audit_event ADD CONSTRAINT ck_audit_event_action CHECK (action IN (
    'CREATE', 'UPDATE', 'ACTIVATE', 'DEACTIVATE', 'ASSIGN_ORDER', 'REMOVE_ORDER', 'MOVE_ORDER',
    'VEHICLE_CHANGE', 'DRIVER_CHANGE', 'CONFIRM', 'CANCEL', 'CREDENTIAL_CREATE',
    'CREDENTIAL_ROTATE', 'CREDENTIAL_REVOKE', 'AUTO_PLAN', 'IMPORT_EXECUTED', 'SHIPMENT_CONFIRMED',
    'SHIPMENT_READY', 'SHIPMENT_DISPATCHED', 'SHIPMENT_COMPLETED', 'SHIPMENT_CANCELLED',
    'DELIVERY_RESULT_RECORDED'));

-- ---------------------------------------------------------------------------
-- Deliberately NOT here
-- ---------------------------------------------------------------------------
--   * No delivered/refused quantities, and no line-level result. The V1 result is about the
--     order: delivered, part of it, refused, failed, not attempted. Quantities need a unit model
--     that agrees with tms.transport_order_line's, a rule for what a partial delivery does to the
--     order's declared totals, and a decision about who is right when the two disagree - three
--     product decisions nobody has made. PARTIAL requires a note precisely so that the shortfall
--     is recorded in the meantime, in the only form that cannot be wrong.
--   * No change to tms.transport_order.status. Repeating V25's and V27's line, and for the same
--     reason: the orders module owns that lifecycle. A DELIVERED order here is still PLANNED
--     there, and the day orders grow a delivered state, this table is what will feed it.
--   * No new permission. planning.trip:execute (V25) is "operate a trip through its day", and
--     recording what was handed over at a stop is that job at its finest grain - the same argument
--     V27 made for stop execution. Reading a delivery and its evidence is planning.trip:read plus
--     orders.order:read, which is exactly what reading a trip's order list already costs.
--   * No evidence deletion and no retention job. Both are real requirements the day a customer
--     asks for erasure, and both need a policy decision (how long is a signed delivery note kept?)
--     that belongs in a data-protection document, not in a migration. The REVOKE above is the
--     honest interim answer: nothing can remove evidence today, so nothing can remove it by
--     accident either.
--   * No storage backend in the schema. Which store holds the bytes is deployment configuration
--     (tms.storage.evidence.*), not a column: a company that moves from a local volume to Supabase
--     Storage keeps every key it has, because the key is opaque and the port is what resolves it.
