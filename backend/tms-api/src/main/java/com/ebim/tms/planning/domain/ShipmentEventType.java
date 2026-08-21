package com.ebim.tms.planning.domain;

/**
 * What {@link ShipmentOutboxEvent} records. Mirrors {@code ck_shipment_outbox_event_type}
 * (migrations V20 and V25).
 *
 * <p>One value per {@link TripStatus} transition that a partner can act on:
 * {@link #SHIPMENT_CONFIRMED} from {@code PlanningRunService.confirm}, and
 * {@link #SHIPMENT_READY}, {@link #SHIPMENT_DISPATCHED}, {@link #SHIPMENT_COMPLETED} and
 * {@link #SHIPMENT_CANCELLED} from {@code TripExecutionService} - each written in the same
 * transaction as the transition it describes.
 *
 * <p>{@link #DELIVERY_RESULT_RECORDED} (migration V28) is the first value that is <em>not</em> a
 * {@link TripStatus} transition, and the reason the outbox was built around an event type rather
 * than a status column: a partner told a shipment is {@code IN_TRANSIT} learns nothing more until
 * it completes, and "an order on it was refused" is exactly the fact an ERP has to act on before
 * then. It is deliberately one value rather than one per {@code DeliveryResult} - a partner
 * subscribes to "a delivery outcome was recorded" and re-reads the shipment for what it was, which
 * is what keeps today's five results out of the wire contract.
 *
 * <p>The five {@code TENDER_*} values (migration V31) are the first family whose audience is the
 * <em>carrier</em> rather than the shipper's own back office, and the first that a partner is
 * expected to act on by writing back. {@link #TENDER_SENT} is how an integrated carrier learns
 * there is an offer waiting at all and {@link #TENDER_CANCELLED} is how they learn it was withdrawn
 * before they answered; without those two, {@code /integration/v1/tenders} would be an endpoint a
 * carrier had to poll blind. The other three close the loop for the shipper's ERP, which has to
 * know whether the load is placed before it prints a manifest.
 *
 * <p>{@link #SHIPMENT_CHANGED} is still reserved and still has no source: the committed states
 * remain locked against edits to what a shipment carries, so TMS cannot yet produce a change to
 * publish. V20's reasoning for keeping the schema open to it is unchanged.
 */
public enum ShipmentEventType {
    SHIPMENT_CONFIRMED,
    SHIPMENT_READY,
    SHIPMENT_DISPATCHED,
    SHIPMENT_COMPLETED,
    SHIPMENT_CANCELLED,
    SHIPMENT_CHANGED,
    DELIVERY_RESULT_RECORDED,
    /** The shipment was offered to its carrier (migration V31) - see {@code TenderStatus}. */
    TENDER_SENT,
    TENDER_ACCEPTED,
    TENDER_REJECTED,
    /** The offer's deadline passed with no answer, and TMS resolved the lapse. */
    TENDER_EXPIRED,
    /** The offer was withdrawn - by the shipper, or because the shipment stopped being offerable. */
    TENDER_CANCELLED
}
