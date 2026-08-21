package com.ebim.tms.integration.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code GET /integration/v1/shipments/events} - the change feed a partner polls
 * instead of re-listing every confirmed shipment on every call. See migration V20's
 * {@code tms.shipment_outbox_event} and {@code docs/integrations/OUTBOUND_SHIPMENT_V1.md}.
 *
 * @param eventType {@code "SHIPMENT_CONFIRMED"} today; {@code "SHIPMENT_CHANGED"} and
 *                  {@code "SHIPMENT_CANCELLED"} are reserved values a partner's deserializer
 *                  should not reject on sight, even though nothing emits them yet
 */
public record ShipmentEventV1(UUID id, String eventType, String shipmentNumber, OffsetDateTime occurredAt) {
}
