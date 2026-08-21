-- TMS by EBIM - V29 tracking positions: where the vehicle was, as reported by something that is
-- not a person typing.
--
-- Design and the full decision record: docs/domain/TRACKING_V1.md and
-- docs/architecture/ADR-007-tracking-provider-port.md.
--
-- CLAUDE.md defers GPS/telematics "until a concrete requirement and an ADR justify them". The
-- concrete requirement is not "draw a truck on a map" - it is the question a customer service desk
-- cannot answer today and asks several times a day:
--
--   * "the customer is on the phone asking where the delivery is, and the driver is not picking up."
--
-- V27 answers that only for stops somebody has already reported. Between two stops - which is most
-- of a delivery day - the timeline is silent, and V27 said so explicitly when it left latitude and
-- longitude on tms.transport_event unused: "the columns exist so that the day a driver app or a
-- telematics integration does report a position, recording it is an application change and not a
-- migration".
--
-- This migration is the other half of that sentence, and it deliberately does NOT use those two
-- columns for a position feed. They stay what V27 made them: the optional position of a *reported
-- fact* ("I arrived, and here is where I was standing"). A feed is a different kind of data:
--
--   * volume differs by three orders of magnitude. A trip produces ~12 transport events in a day
--     and, at one point a minute, ~500 positions. Mixing them makes the timeline query - the one a
--     dispatcher runs constantly - scan a table that is 98% pings;
--   * an event has an actor and a position has none. tms.transport_event's actor xor CHECK is
--     load-bearing ("a log the client can sign somebody else's name to is not a log") and a feed
--     would have to be given a fake machine label to satisfy it;
--   * an event is kept forever and a position is not. Retention is the whole reason this table has
--     a DELETE grant and tms.transport_event does not.
--
-- What this is NOT: not a telematics integration. There is no vendor here, no credential for one,
-- and no adapter that speaks anybody's protocol - see the ADR. This is the storage and the intake
-- contract, so that the day a customer arrives with a provider, TMS has somewhere to put what it
-- sends and one interface to implement.

-- ---------------------------------------------------------------------------
-- 1. tms.tracking_position - one reported position of one shipment
-- ---------------------------------------------------------------------------
-- Named for what it is rather than for its parent (tms.transport_event's own naming rule), even
-- though trip_id is NOT NULL in V1. That is not a hedge about a future "position with no trip": it
-- is that a position is a fact about a vehicle at a time, and TMS only accepts the ones a partner
-- can attach to a shipment - which is the only scope in which TMS can authorize, tenant and read
-- them. A vehicle idling at a depot is somebody else's data.
CREATE TABLE tms.tracking_position (
    id                          uuid          NOT NULL DEFAULT gen_random_uuid(),
    company_id                  uuid          NOT NULL,
    trip_id                     uuid          NOT NULL,
    -- When the device was at this point, as the feed reports it. The same business-time rule V25
    -- established for actual times and V27 for events: a ping buffered in a tunnel for ten minutes
    -- belongs to the minute it was measured, not to the minute it arrived.
    occurred_at                 timestamptz   NOT NULL,
    -- ...and when TMS stored it. The pair is what makes feed latency measurable, which is the
    -- first thing anybody asks when a map looks wrong.
    received_at                 timestamptz   NOT NULL DEFAULT now(),
    -- NOT NULL, unlike tms.transport_event's pair: an event without a position is an ordinary
    -- event, and a position without coordinates is not a position.
    latitude                    numeric(9,6)  NOT NULL,
    longitude                   numeric(9,6)  NOT NULL,
    -- Optional because plenty of feeds report neither. Never computed by TMS from consecutive
    -- points: a speed TMS derived would look identical to a speed the vehicle measured, and the
    -- two are not the same claim - one of them can be evidence in a dispute.
    speed_kph                   numeric(6,2),
    heading_degrees             numeric(5,2),
    -- Which feed said so. Free text of a constrained shape and NOT a value list, deliberately: the
    -- vendors are unknown, and a CHECK enumerating them would make onboarding a customer's
    -- existing telematics contract a migration. It is a label on the data and never an authority -
    -- the company always comes from the credential (section 3 of docs/domain/TRACKING_V1.md).
    provider                    text          NOT NULL,
    -- The provider's own id for the vehicle ("TRK-0431", an IMEI, a box serial). Stored so that a
    -- position can be traced back to the thing that sent it while TMS's own tms.vehicle and the
    -- partner's fleet register are still being reconciled - which, on every real onboarding, is
    -- for the first few weeks. Never used to resolve the trip: the shipment number does that.
    external_vehicle_reference  text,
    -- The provider's id for this ping, kept so "we sent it and you do not have it" is answerable.
    -- A *reference*, never the payload: TMS stores no raw telematics document, because such a
    -- document routinely carries the driver's identity, their phone's identifiers and their
    -- movements off shift - personal data TMS has no purpose for and therefore must not hold.
    correlation_reference       text,
    CONSTRAINT pk_tracking_position PRIMARY KEY (id),
    CONSTRAINT fk_tracking_position_company FOREIGN KEY (company_id)
        REFERENCES tms.company (id) ON DELETE RESTRICT,
    -- CASCADE and not RESTRICT, which is the opposite of tms.transport_event's choice and for the
    -- reason that table gives: it is a log and this is a measurement. A trip is never deleted in
    -- V1, so neither clause fires; if one ever does, losing the pings of a deleted shipment is
    -- correct and blocking the delete over them would not be.
    CONSTRAINT fk_tracking_position_trip FOREIGN KEY (trip_id)
        REFERENCES tms.trip (id) ON DELETE CASCADE,
    CONSTRAINT fk_tracking_position_trip_company FOREIGN KEY (trip_id, company_id)
        REFERENCES tms.trip (id, company_id),
    CONSTRAINT ck_tracking_position_latitude_range CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_tracking_position_longitude_range CHECK (longitude BETWEEN -180 AND 180),
    -- 400 km/h is not a speed limit - it is the line past which the number is a unit error (mph
    -- read as km/h, m/s read as km/h) rather than a fast truck. Rejecting it at intake is what
    -- stops one misconfigured feed from putting a vehicle in the next country on a map.
    CONSTRAINT ck_tracking_position_speed_range CHECK (
        speed_kph IS NULL OR (speed_kph >= 0 AND speed_kph <= 400)),
    -- [0, 360) and not [0, 360]: 360 and 0 are the same bearing, and allowing both would make
    -- "due north" two different stored values.
    CONSTRAINT ck_tracking_position_heading_range CHECK (
        heading_degrees IS NULL OR (heading_degrees >= 0 AND heading_degrees < 360)),
    CONSTRAINT ck_tracking_position_provider_shape CHECK (provider ~ '^[a-z0-9][a-z0-9._-]{1,63}$'),
    CONSTRAINT ck_tracking_position_external_vehicle_reference_not_blank CHECK (
        external_vehicle_reference IS NULL OR btrim(external_vehicle_reference) <> ''),
    CONSTRAINT ck_tracking_position_external_vehicle_reference_length CHECK (
        length(external_vehicle_reference) <= 128),
    CONSTRAINT ck_tracking_position_correlation_reference_not_blank CHECK (
        correlation_reference IS NULL OR btrim(correlation_reference) <> ''),
    CONSTRAINT ck_tracking_position_correlation_reference_length CHECK (
        length(correlation_reference) <= 128)
);

-- Redelivery is a no-op, as a database fact.
--
-- This is the *business identity* of a ping - one feed reporting one shipment at one instant - and
-- it is the primary idempotency mechanism here, exactly as (externalSource, externalReference) is
-- for an order. The Idempotency-Key header covers the other case (the sender never learned the
-- outcome) and neither replaces the other; see IntegrationRequestExecutor's class comment.
--
-- A partner whose at-least-once queue replays an hour of pings therefore needs no cursor, no
-- de-duplication of its own and no coordination with us: it resends, and nothing happens twice.
CREATE UNIQUE INDEX uq_tracking_position_feed_instant
    ON tms.tracking_position (company_id, trip_id, provider, occurred_at);

-- "Where is this shipment now" - the read the trip workspace does, answered by the first row of
-- this index. DESC because the newest position is the one anybody asks for, and the same index
-- serves the bounded track behind it without a second one.
CREATE INDEX ix_tracking_position_trip_recent
    ON tms.tracking_position (company_id, trip_id, occurred_at DESC);

-- The retention sweep's access path: "everything older than X", company by company. Without it,
-- purging is a sequential scan of the largest table in the schema.
CREATE INDEX ix_tracking_position_occurred_at
    ON tms.tracking_position (occurred_at);

COMMENT ON TABLE tms.tracking_position IS
    'Reported positions of shipments in transit, from a provider-agnostic feed (ADR-007). Not a '
    'log and not a source of truth for anything: no rule in TMS reads it, no status is derived '
    'from it, and losing it costs a map and no business fact. Thinned at intake to one point per '
    'configured interval per (trip, provider) and purged after a configured age - see '
    'docs/domain/TRACKING_V1.md, "Volume and retention".';
COMMENT ON COLUMN tms.tracking_position.provider IS
    'Which feed reported this, as a lowercase slug. A label, never an authority: the tenant comes '
    'from the authenticated credential and never from this column or from a request body.';
COMMENT ON COLUMN tms.tracking_position.correlation_reference IS
    'The provider''s own id for this ping, so a "we sent it" dispute is answerable. Never the raw '
    'payload: a telematics document carries the driver''s identity and their movements off shift, '
    'which TMS has no purpose for.';

ALTER TABLE tms.tracking_position ENABLE ROW LEVEL SECURITY;

-- SELECT, INSERT and DELETE, and the DELETE is the interesting one.
--
-- UPDATE is revoked for the same reason tms.transport_event revokes it: a measurement is not
-- corrected, it is superseded by the next one. DELETE is *granted*, which tms.transport_event does
-- not do, because this table has a retention policy and that policy has to be executable by the
-- application role that runs it. A row here is disposable by design - that is what distinguishes a
-- feed from a log, and pretending otherwise would leave the largest table in the schema growing
-- forever with no supported way to trim it.
GRANT SELECT, INSERT, DELETE ON tms.tracking_position TO tms_app;
REVOKE UPDATE ON tms.tracking_position FROM tms_app;

-- One FOR ALL policy rather than V27's split pair: three verbs are in play here and splitting them
-- would produce three policies saying the same sentence. The narrowing that matters is in the
-- grant above, which is where a reader looks for it.
CREATE POLICY p_tenant_company_scope ON tms.tracking_position
    FOR ALL TO tms_app
    USING (company_id = tms.current_company_id())
    WITH CHECK (company_id = tms.current_company_id());

-- ---------------------------------------------------------------------------
-- 2. The integration scope that may write positions
-- ---------------------------------------------------------------------------
-- A separate scope and not integration.order:write, because a telematics credential and an ERP
-- credential are different keys held by different parties. The tracking box vendor must not be
-- able to create orders, and the ERP has no business reporting positions - IntegrationScope's own
-- rule ("a narrow, purpose-built key that should hold one or two capabilities and nothing else").
--
-- Write-only in effect: holding it grants no read anywhere. A telematics provider pushes where its
-- boxes are and learns nothing about the shipment it is pushing against, not even whether the
-- shipment number it named exists - see IntegrationTrackingService's per-item reasoning.
ALTER TABLE tms.integration_client_scope DROP CONSTRAINT ck_integration_client_scope_value;
ALTER TABLE tms.integration_client_scope ADD CONSTRAINT ck_integration_client_scope_value CHECK (
    scope IN ('integration.location:write', 'integration.order:write', 'integration.shipment:read',
              'integration.tracking:write'));

-- ---------------------------------------------------------------------------
-- Deliberately NOT here
-- ---------------------------------------------------------------------------
--   * No new permission for reading positions. monitoring.transport:read has existed since V3,
--     has been granted to the seeded roles since V5, and means exactly this - "see where the
--     transport is". It was the one permission in the catalogue with no endpoint behind it; V29
--     gives it one instead of inventing tracking.position:read beside it.
--   * No geometry column, no PostGIS index, and numeric(9,6) rather than geography(Point). Every
--     question V1 asks of this table is "the newest row for this trip", which is an ordinary
--     b-tree lookup. A spatial index earns its cost when something asks "which vehicles are within
--     2 km of here", and nothing does - geofencing is not a feature TMS has. The columns match
--     tms.transport_event's and tms.location's existing shape, so adding geography later is a
--     generated column and not a rewrite.
--   * No ETA, no delay calculation, no "off route" detection. Each needs a routing engine and
--     route optimisation is deferred by decision (CLAUDE.md). A column holding an ETA nobody
--     computes would read as a promise the product does not keep, which is precisely why V27
--     refused planned_arrival_at per stop.
--   * No geofence table and no automatic arrival detection. It is buildable on this data and it is
--     a product decision nobody has made: a stop that marks itself ARRIVED from a GPS fence
--     changes who is accountable for the timeline, and that is a conversation with a customer, not
--     a schema change. tms.trip_stop's execution times stay operator-reported.
--   * No vehicle-to-device registry (tms.vehicle_tracking_device or similar). external_vehicle_
--     reference is carried on the position rather than resolved through a master, because V1 never
--     resolves it - the shipment number identifies the trip. A registry is what the *second*
--     integration needs, the one that pulls by vehicle instead of receiving by shipment, and
--     TrackingProviderPort is where that lands.
--   * No driver identity anywhere in this table. The trip already names its driver (V26); copying
--     that onto half a million position rows would turn a fleet feed into a per-employee movement
--     record with a different legal weight and no additional operational value.
--   * No partition and no time-series extension. At the design target - 300 vehicles, one point a
--     minute, 10 hours a day - this table takes ~180k rows a day and the retention default keeps
--     ~5.4M of them, which a b-tree on (company, trip, occurred_at) serves without help.
--     Partitioning by month is the next step and it is a migration, not a redesign; see
--     docs/domain/TRACKING_V1.md.
