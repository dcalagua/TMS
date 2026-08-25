package com.ebim.tms.integration.domain;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a webhook subscription may ask to be told about (migration V35). Mirrors
 * {@code ck_webhook_subscription_event_type}.
 *
 * <h2>Why this list is written twice</h2>
 *
 * <p>These are, value for value, {@code planning.domain.ShipmentEventType} - and they are declared
 * again here rather than imported because {@code ModuleBoundaryTest} forbids {@code integration}
 * from naming {@code planning}, and because the two enums answer different questions: that one is
 * what TMS may <em>publish</em>, this one is what a customer may <em>subscribe to</em>. A future
 * event family that TMS publishes for its own internal reasons and does not offer over the wire
 * would appear in one and not the other.
 *
 * <p>The duplication is not left to good intentions: {@code WebhookEventTypeTest} asserts on every
 * build that the two lists still coincide, so a value added to the outbox and forgotten here fails
 * a test rather than silently delivering nothing to everyone who subscribed.
 */
public enum WebhookEventType {

    /** The plan became a committed shipment. The event most subscriptions exist for. */
    SHIPMENT_CONFIRMED,
    SHIPMENT_READY,
    SHIPMENT_DISPATCHED,
    SHIPMENT_COMPLETED,
    SHIPMENT_CANCELLED,

    /**
     * Reserved, and with no source today: a committed shipment is locked against edits to what it
     * carries, so TMS cannot yet produce a change to publish. Offered in the picker anyway, so a
     * subscription written today keeps working on the day one exists.
     */
    SHIPMENT_CHANGED,

    /** What was handed over at a stop was recorded - the event an ERP raises a credit note from. */
    DELIVERY_RESULT_RECORDED,

    /** The five tender transitions. Their audience is the carrier's system, not the shipper's. */
    TENDER_SENT,
    TENDER_ACCEPTED,
    TENDER_REJECTED,
    TENDER_EXPIRED,
    TENDER_CANCELLED;

    /**
     * The event type behind a published fact's {@code eventType} string, or empty when TMS
     * published something no subscription can name.
     *
     * <p>Empty is a real case rather than a defect to throw on: the fan-out asks this question
     * about every outbox row, and an event family added to {@code planning} before this enum
     * catches up must delay nobody's confirmation. It delivers nothing and the parity test is what
     * complains.
     */
    public static Optional<WebhookEventType> byName(String name) {
        return Arrays.stream(values()).filter(value -> value.name().equals(name)).findFirst();
    }

    /** The vocabulary as an ordered set, for an error message that lists the valid choices. */
    public static Set<String> names() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
