package com.ebim.tms.integration.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The body of every webhook POST (migration V35) - the wire contract in
 * {@code docs/integrations/WEBHOOKS_V1.md}.
 *
 * <h2>Why the envelope carries so little</h2>
 *
 * <p>It says what happened, to which thing, and when. It does not embed the shipment, and that is
 * the same decision {@code tms.shipment_outbox_event} made in V20 for the polling feed: a copy of a
 * business object travelling separately from the object is a copy that is wrong the moment anything
 * changes, and a receiver that acts on a three-hour-old retry would act on three-hour-old
 * quantities. The receiver reads {@code resource.reference}, calls
 * {@code GET /integration/v1/shipments}, and acts on what TMS believes right now.
 *
 * <p>It also means the body carries no personal data. A webhook target is an address an
 * administrator typed; keeping customer names and addresses out of what gets POSTed there is worth
 * more than saving the receiver one call.
 *
 * <h2>Stability</h2>
 *
 * <p>{@code apiVersion} is in the body rather than only in the URL because a receiver stores these
 * and reads them back later - out of a queue, out of a dead-letter table - and at that point the URL
 * is gone. New fields may be added within {@code v1}; a receiver must ignore what it does not know,
 * which is stated in the documentation and is the only compatibility rule this contract asks for.
 *
 * @param id the published fact's id. Stable across every attempt and every redelivery, so it is
 *     what a receiver deduplicates on - the same value as the {@code X-TMS-Event-Id} header
 * @param resource what the event is about, and how to go and read it
 */
public record WebhookEventV1(
        String apiVersion,
        UUID id,
        String type,
        OffsetDateTime occurredAt,
        UUID companyId,
        Resource resource) {

    /** The only value in circulation, and the one this record's field names describe. */
    public static final String API_VERSION = "v1";

    /**
     * @param type {@code "shipment"} for every event family that exists today
     * @param reference the human-facing identifier - a shipment number - which is what appears on
     *     the paperwork and in the conversation about it
     */
    public record Resource(String type, UUID id, String reference) {
    }
}
