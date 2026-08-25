package com.ebim.tms.shared.reference;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A fact that has just been written to the outbox, handed to {@link EventFanoutPort} so that
 * whoever subscribed to it can be told.
 *
 * <p>Deliberately not shipment-shaped, even though every value that travels through it today comes
 * from {@code tms.shipment_outbox_event}. The webhook tables it feeds are generic on purpose
 * (migration V35), and a port that spelled {@code shipmentNumber} would have to be widened - along
 * with every row already stored under it - the first time a rate card or a master datum needed a
 * push. The three {@code resource*} fields are what a receiver uses to go and read the thing
 * itself.
 *
 * @param id the published fact's own id - the outbox row's id, and the value a receiver
 *     deduplicates on. Never regenerated: a redelivery of the same fact carries the same id
 * @param eventType the vocabulary term, e.g. {@code "SHIPMENT_CONFIRMED"}. A string and not an enum
 *     because the enum lives in {@code planning}, which {@code shared} may not depend on; the
 *     integration side validates it against its own copy and {@code WebhookEventTypeTest} keeps
 *     the two in step
 * @param resourceType what the event is about, lower case: {@code "shipment"} today
 * @param resourceId the business row's id, so a receiver can address it without parsing a reference
 * @param resourceReference the human-facing identifier - a shipment number - which is what appears
 *     on a document and in the support conversation about it
 * @param occurredAt when the business fact happened, not when this method ran. A backdated
 *     departure produces a backdated event, exactly as it does in the outbox
 */
public record PublishedEventNotification(
        UUID id,
        UUID companyId,
        String eventType,
        String resourceType,
        UUID resourceId,
        String resourceReference,
        OffsetDateTime occurredAt) {

    /** What {@link #resourceType} carries for every event family that exists today. */
    public static final String SHIPMENT = "shipment";
}
