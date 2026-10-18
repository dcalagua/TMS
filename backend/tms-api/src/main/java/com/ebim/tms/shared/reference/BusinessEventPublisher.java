package com.ebim.tms.shared.reference;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Publishes a business fact that is not about a trip (migration V61, ADR-025 section 4): one row in
 * {@code tms.business_outbox_event} and the webhook deliveries owed for it.
 *
 * <p>Implemented by {@code integration}, called by the module that owns the fact ({@code orders}
 * today). The trip-shaped sibling is {@code planning.application.ShipmentEventPublisher}, which keeps
 * its own outbox because {@code tms.shipment_outbox_event.trip_id} is NOT NULL.
 *
 * <p>Runs <em>inside</em> the caller's transaction, under the {@link EventFanoutPort} rules: no
 * network call, no swallowed database failure. A rolled-back cancellation takes its event with it.
 */
public interface BusinessEventPublisher {

    /** The resource types the outbox accepts ({@code ck_business_outbox_event_resource_type}). */
    String ORDER = "ORDER";

    /** A transport order was cancelled in TMS (WAREHOUSE_EXECUTION_V1 7.4). */
    String ORDER_CANCELLED = "ORDER_CANCELLED";

    /**
     * One business fact.
     *
     * @param resourceType upper case, {@link #ORDER}; the webhook carries it lower case
     * @param resourceReference the human-facing identifier (an order number)
     * @param eventType the vocabulary term, e.g. {@link #ORDER_CANCELLED}
     * @param occurredAt when the business fact happened
     */
    record BusinessEvent(UUID companyId, String resourceType, UUID resourceId, String resourceReference,
            String eventType, OffsetDateTime occurredAt) {
    }

    void publish(BusinessEvent event);
}
