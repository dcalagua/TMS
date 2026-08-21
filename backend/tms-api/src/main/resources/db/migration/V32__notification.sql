-- TMS by EBIM - V32 operational alerts (job 10 overnight-v4).
--
-- One row per operational fact somebody has to look at: a shipment that left late, a problem
-- opened on a trip, a carrier that said no or never answered, a delivery that did not happen, a
-- driver whose licence runs out soon, a shipment that finished.
--
-- Written by com.ebim.tms.notification.application.NotificationRecorder, the only implementation
-- of com.ebim.tms.shared.notification.NotificationPublisher - the "explicit API"
-- ModuleBoundaryTest requires for one business module to reach another, the same shape
-- com.ebim.tms.shared.audit.AuditRecorder established in V22. planning and fleet raise alerts
-- through the port; neither may import this module's table or entity.
--
-- ---------------------------------------------------------------------------
-- 1. What this is NOT
-- ---------------------------------------------------------------------------
--   * Not the audit trail. tms.audit_event answers "who changed what, and when" for a reader who
--     already knows which shipment they are asking about. This answers "what should I look at
--     right now", it is read by nobody after the day ends, and it is mutable - an alert is
--     acknowledged, an audit record never is.
--   * Not the outbox. tms.shipment_outbox_event is addressed to a partner system that polls; this
--     is addressed to a person looking at a screen. The same business fact legitimately produces
--     one of each, which is why ShipmentEventPublisher writes them together.
--   * Not the trip timeline. tms.transport_event is one shipment's day, read on that shipment's
--     workspace. This is the whole company's day, read from the bell in the top bar.
--   * Not a message store. There is no thread, no reply, no assignment, no recipient list and no
--     mention. Section 3 says why the recipient is a company and not a person.
--
-- ---------------------------------------------------------------------------
-- 2. No rendered text, ever
-- ---------------------------------------------------------------------------
-- There is no title column and no message column. `type` selects the sentence in the frontend's
-- `notifications` namespace and `message_args` carries the placeholders it needs - the shipment
-- number, the minutes late, the licence expiry date.
--
-- A stored sentence is stored in one language and one wording. A tenant that switches from
-- Spanish to English would read its own history in the language it left behind, and a reworded
-- alert would leave every row written before the change saying the old thing. Storing the
-- arguments instead makes the alert translatable at read time, which is what "i18n-friendly"
-- has to mean if it is to mean anything. It also removes the question of whether HTML belongs in
-- the column: nothing here is markup, so nothing here can be injected into a panel.

CREATE TABLE tms.notification (
    id             uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id     uuid          NOT NULL,
    type           text          NOT NULL,
    severity       text          NOT NULL,
    -- What the alert is about and where the panel navigates when it is clicked. Not a foreign
    -- key: the two entity types live in two modules' tables, and a polymorphic FK is not
    -- expressible. The consequence is stated rather than hidden - an alert can outlive the row it
    -- points at, and NotificationView carries entity_label so the panel still reads.
    entity_type    text          NOT NULL,
    entity_id      uuid          NOT NULL,
    -- The shipment number or the driver code, snapshotted. Without it the bell would join two
    -- other modules' tables to render six rows, which is exactly the coupling the port avoids.
    entity_label   text,
    -- Compact JSON, placeholders only - see section 2.
    message_args   text,
    -- What makes "this fact" one fact. Composed by the raiser (type + the id of whatever the
    -- alert is keyed on) and unique per company, so a retried request, a re-read of the same
    -- lapsed tender or two planners assigning the same driver produce one alert and not three.
    dedupe_key     text          NOT NULL,
    -- When the business fact happened - the operator's own time, backdated where they backdated
    -- it. The feed is ordered by this and not by created_at, for the reason V22 gives about
    -- occurred_at: a departure recorded at 18:40 for 08:05 belongs at 08:05.
    occurred_at    timestamptz   NOT NULL,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    read_at        timestamptz,
    read_by        uuid,
    -- Set when the condition that raised the alert closes on its own: a trip exception being
    -- resolved, or a shortfall delivery corrected to a full one. There is no resolved_by beside it
    -- on purpose: "resolved" is a fact about the condition, not an act by a person, and the person
    -- who closed it is already recorded on tms.trip_exception.resolved_by or on
    -- tms.order_delivery.actor_app_user_id. A second copy here could only disagree.
    resolved_at    timestamptz,
    CONSTRAINT pk_notification PRIMARY KEY (id),
    CONSTRAINT fk_notification_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_read_by FOREIGN KEY (read_by)
        REFERENCES tms.app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_notification_type CHECK (type IN (
        'TRIP_DELAYED', 'EXCEPTION_OPENED', 'TENDER_REJECTED', 'TENDER_EXPIRED',
        'DRIVER_LICENSE_EXPIRING', 'TRIP_COMPLETED', 'DELIVERY_FAILED')),
    CONSTRAINT ck_notification_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_notification_entity_type CHECK (entity_type IN ('TRIP', 'DRIVER')),
    -- Read is an act, so it has an actor; both columns or neither. Mirrors what
    -- NotificationService can produce: the endpoint behind it is restricted to an interactive
    -- user, so a row that fails this means the service has a bug, not that the data is unusual.
    CONSTRAINT ck_notification_read_pair CHECK ((read_at IS NULL) = (read_by IS NULL)),
    CONSTRAINT ck_notification_message_args_length CHECK (length(message_args) <= 2000),
    CONSTRAINT ck_notification_dedupe_key_length CHECK (length(dedupe_key) BETWEEN 1 AND 200)
);

-- The idempotency contract, and the reason NotificationRecorder inserts with ON CONFLICT DO
-- NOTHING rather than reading first. A check-then-insert would race between two application
-- instances, and the loser of that race would take a unique violation *inside the business
-- transaction that raised the alert* - which would fail a driver assignment over a duplicate
-- bell entry. ON CONFLICT resolves it in the statement, so the worst case is an alert that was
-- already there.
CREATE UNIQUE INDEX uq_notification_company_dedupe
    ON tms.notification (company_id, dedupe_key);

-- The feed: newest first, whole company.
CREATE INDEX ix_notification_company_occurred
    ON tms.notification (company_id, occurred_at DESC);

-- The badge. Partial, because the count the top bar re-reads is always the unread one and the
-- read rows are the ones that accumulate.
CREATE INDEX ix_notification_company_unread
    ON tms.notification (company_id, occurred_at DESC)
    WHERE read_at IS NULL;

COMMENT ON TABLE tms.notification IS
    'One operational alert a person has to look at, addressed to a company rather than to a user '
    '- see V32 section 3. Carries no rendered text: `type` selects the sentence and '
    '`message_args` carries its placeholders, so history stays readable in whichever language '
    'the tenant reads it in.';

COMMENT ON COLUMN tms.notification.dedupe_key IS
    'type + the id the alert is keyed on. Unique per company, enforced by '
    'uq_notification_company_dedupe and relied on by NotificationRecorder''s ON CONFLICT DO '
    'NOTHING insert and by its resolve path.';

COMMENT ON COLUMN tms.notification.message_args IS
    'Compact JSON, placeholders only (a shipment number, a count of minutes, a date) - never a '
    'rendered sentence, never markup and never a business detail the entity itself already holds.';

-- ---------------------------------------------------------------------------
-- 3. Addressed to a company, not to a person
-- ---------------------------------------------------------------------------
-- read_at/read_by say "somebody here has seen this", not "this user has seen this". There is no
-- row per recipient and no read receipt per user.
--
-- That is a decision and it has a cost: two dispatchers share one badge, and the second one does
-- not see the alert the first has already acknowledged. It is the right cost for what this is.
-- An operational alert is work to be done once - one person calls the customer whose delivery
-- failed - and a per-user inbox would show the same job to five people and give each of them a
-- private way to dismiss it. Acknowledging is how a team says "I have got this", which is the
-- question a dispatch desk actually asks.
--
-- What it would take to change: a tms.notification_read (notification_id, app_user_id, read_at)
-- table and a per-user count in the summary query. Nothing here has to move for that - which is
-- why read_at lives on this row instead of a fan-out of one row per eligible user, a shape that
-- could not be walked back.
--
-- ---------------------------------------------------------------------------
-- 4. Who may see which alert
-- ---------------------------------------------------------------------------
-- No permission of its own, deliberately. Minting notification.alert:read would give every
-- installation one more grant to make before the bell worked at all, and it would answer the
-- wrong question: the risk here is not "may this account open the panel", it is "may this
-- account be told that driver JD-004's licence expires on the 12th".
--
-- So the disclosure is decided per alert type, in com.ebim.tms.shared.notification.
-- NotificationType, against a permission that already exists and already means that thing:
--
--   TRIP_DELAYED, TRIP_COMPLETED, EXCEPTION_OPENED, DELIVERY_FAILED -> monitoring.transport:read
--   TENDER_REJECTED, TENDER_EXPIRED                                 -> planning.tender:read
--   DRIVER_LICENSE_EXPIRING                                         -> fleet.driver:read
--
-- NotificationService filters the feed and the unread count by the types the caller's scope
-- holds, so an account with none of the three gets an empty panel rather than a 403 on a control
-- that is always on screen. Same reasoning ControlTowerController gives for reusing
-- monitoring.transport:read instead of minting monitoring.control_tower:read.
--
-- ---------------------------------------------------------------------------
-- 5. Grants and RLS
-- ---------------------------------------------------------------------------
ALTER TABLE tms.notification ENABLE ROW LEVEL SECURITY;

-- No DELETE, for the reason tms.order_delivery has none: an alert history that the application
-- can silently erase stops being evidence of what the operation was told. Purging old rows is a
-- maintenance task for the schema owner, not an action reachable from a request. Retention is
-- deferred rather than forgotten - see section 6.
GRANT SELECT, INSERT, UPDATE ON tms.notification TO tms_app;

CREATE POLICY p_tenant_company_scope ON tms.notification
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 6. Deliberately NOT here
-- ---------------------------------------------------------------------------
--   * No email, no SMS, no WhatsApp, no webhook. In-app only. External delivery needs a per-user
--     channel preference, a bounce/retry policy and a suppression list, and none of those are
--     decisions a schema should make on its way past. When one arrives it is an adapter behind
--     NotificationPublisher's own read side, not a column here.
--   * No alert that needs a timer. Nothing in this installation runs on a scheduler - V31 section
--     1b says so about tender expiry and the same is true here - so every type above is raised by
--     a business transaction that was going to happen anyway. The two alerts that would need a
--     sweep are named and left out: a shipment that has *not yet* departed and is already past its
--     planned time (DepartureTimeliness.OVERDUE, which the control tower answers live on every
--     read), and a licence expiring on a driver nobody is currently planning with. Writing either
--     without a scheduler would mean either a fake source or an alert that appears only when
--     somebody happens to open a screen, and an alert that fires when you are already looking is
--     not an alert.
--   * No severity override per company. Severity is a property of the type
--     (NotificationType.severity), not a column somebody tunes. A per-tenant severity policy is a
--     configuration product, and no customer has asked for one.
--   * No retention job. The table grows by roughly one row per shipment per notable event, which
--     at the stated scale (10,000 orders/day) is thousands of rows a day and years away from
--     being a problem. A DELETE grant added early would be the thing that made it a problem, by
--     letting a well-meaning cleanup remove evidence.
--   * No snooze, no assignment, no escalation. tms.trip_exception already declined all three for
--     the same reason (V27): no rule in TMS reads any of them, so each would be a field somebody
--     fills in and nothing acts on.
