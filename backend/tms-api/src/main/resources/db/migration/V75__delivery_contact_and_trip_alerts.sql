-- ============================================================================
-- V75 - Who receives at a destination, and two more trip alerts
-- ============================================================================
-- Three changes the rejection walkthrough asked for (docs/demo/PLAPASA_RECORRIDO_RECHAZOS_LOCAL.md).
-- Two of them need the schema; the rest of that change set is application code over columns that
-- already exist:
--
--   * the refused products of a partial delivery go to tms.order_delivery_line, which V45 created
--     for exactly that ("WHICH product the customer refused") and nothing wrote until now;
--   * the carrier portal's «Llegué» sends the phone's position to tms.transport_event.latitude/
--     longitude, which V27 created as "the optional position of a reported fact" and ADR-007 kept
--     for that. A position informs the person reading the timeline and moves no lifecycle.
--
-- 1. tms.location: contact_name, contact_phone, delivery_instructions - what the driver reads on
--    the portal at the stop. Office-maintained; the integration API does not carry them.
-- 2. tms.notification: TRIP_DISPATCHED (the trip went on the road) and TRIP_READY_TO_CLOSE (every
--    stop is resolved and the office has not closed the trip yet; resolved when it does).
--
-- Nothing is backfilled and no tenant data is touched.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. The delivery contact of a location
-- ---------------------------------------------------------------------------
-- Nullable, blank refused, bounded - the V14 conventions for free text on this table. The phone is
-- text, not a number: "+593 4 2690-100 ext. 12" is a phone, and leading zeros are significant.
ALTER TABLE tms.location
    ADD COLUMN contact_name          text,
    ADD COLUMN contact_phone         text,
    ADD COLUMN delivery_instructions text;

ALTER TABLE tms.location
    ADD CONSTRAINT ck_location_contact_name_not_blank
        CHECK (contact_name IS NULL OR btrim(contact_name) <> ''),
    ADD CONSTRAINT ck_location_contact_name_length
        CHECK (contact_name IS NULL OR length(contact_name) <= 120),
    ADD CONSTRAINT ck_location_contact_phone_not_blank
        CHECK (contact_phone IS NULL OR btrim(contact_phone) <> ''),
    ADD CONSTRAINT ck_location_contact_phone_length
        CHECK (contact_phone IS NULL OR length(contact_phone) <= 40),
    ADD CONSTRAINT ck_location_delivery_instructions_not_blank
        CHECK (delivery_instructions IS NULL OR btrim(delivery_instructions) <> ''),
    ADD CONSTRAINT ck_location_delivery_instructions_length
        CHECK (delivery_instructions IS NULL OR length(delivery_instructions) <= 500);

COMMENT ON COLUMN tms.location.contact_name IS
    'Who receives goods at this place (V75), shown to the carrier on the portal stop. Office-maintained: '
    'the integration API neither sends nor clears it.';
COMMENT ON COLUMN tms.location.contact_phone IS
    'How the driver reaches the receiver (V75). Free text: extensions and country prefixes are kept as typed.';
COMMENT ON COLUMN tms.location.delivery_instructions IS
    'What the driver must know on arrival (V75): which gate, unloading hours, what to ask for. '
    'Shown on the portal stop; it informs and enforces nothing.';

-- ---------------------------------------------------------------------------
-- 2. Two more trip alerts
-- ---------------------------------------------------------------------------
-- The V32 list grows by two informational types. TRIP_DISPATCHED is raised whichever door
-- dispatched the trip (office or warehouse confirmation, ADR-013); TRIP_READY_TO_CLOSE when the last
-- outstanding stop is resolved, and resolved when the trip is completed. Both are keyed on the trip
-- (TYPE:trip_id), so a retried dispatch rings once.
ALTER TABLE tms.notification DROP CONSTRAINT ck_notification_type;
ALTER TABLE tms.notification ADD CONSTRAINT ck_notification_type CHECK (type IN (
    'TRIP_DELAYED', 'EXCEPTION_OPENED', 'TENDER_REJECTED', 'TENDER_EXPIRED',
    'DRIVER_LICENSE_EXPIRING', 'TRIP_COMPLETED', 'DELIVERY_FAILED',
    'TRIP_DISPATCHED', 'TRIP_READY_TO_CLOSE'));
