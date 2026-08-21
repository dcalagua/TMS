package com.ebim.tms.shared.reference;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the shipment outbox ({@code tms.shipment_outbox_event}, migration V20) - a fact a
 * partner may have missed, cheap enough to poll for on every call.
 *
 * @param eventType {@code "SHIPMENT_CONFIRMED"} or {@code "SHIPMENT_CANCELLED"} today.
 *                  {@code "SHIPMENT_CHANGED"} is a reserved value the schema already accepts: V1
 *                  has no business rule that mutates a confirmed trip
 *                  ({@code planning.domain.TripStatus}'s class comment - "a confirmed trip is
 *                  locked"), so nothing emits it yet. See
 *                  {@code docs/integrations/OUTBOUND_SHIPMENT_V1.md}, "Change feed".
 */
public record PublishedShipmentEvent(UUID id, String eventType, String shipmentNumber, OffsetDateTime occurredAt) {
}
