package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.ShipmentEventType;
import com.ebim.tms.planning.domain.ShipmentOutboxEvent;
import com.ebim.tms.planning.domain.TransportEventType;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.infrastructure.ShipmentOutboxEventRepository;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.EventFanoutPort;
import com.ebim.tms.shared.reference.PublishedEventNotification;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one place a trip's lifecycle transition becomes a <em>published fact</em>: one row in the
 * transactional outbox for whoever integrates with TMS, one audit event for whoever has to explain
 * later what happened, and - since migration V27 - one entry in the trip's own operational
 * timeline for the dispatcher watching the day unfold.
 *
 * <p>Since migration V31 it also publishes the five tender transitions, which are not transitions
 * of the trip at all - the shipment's status is unchanged when a carrier accepts. They come through
 * here anyway, and deliberately: the reason this class exists is that one fact must reach the
 * outbox, the audit trail and the timeline together, and a tender is the fact with the most
 * audiences of any in the product.
 *
 * <p>Exists so {@code PlanningRunService} (confirmation), {@code TripExecutionService} (ready,
 * dispatch, complete) and {@code TripTenderService} (the five tender transitions) cannot drift on
 * the things that must always happen together, in
 * the same transaction as the transition itself. That transactionality is the whole point of an
 * outbox and is inherited rather than declared: every method here participates in the caller's
 * transaction, so a rollback anywhere in it takes the event down too - see
 * {@code tms.shipment_outbox_event}'s migration V20 comment.
 *
 * <p>Nothing here calls an external system, and that is still true since migration V35 added
 * webhooks. {@link EventFanoutPort} records which endpoints are <em>owed</em> this event - two
 * indexed inserts, in this same transaction, so that a rolled-back confirmation takes the obligation
 * down with it. The HTTP call happens later, in a background dispatcher, outside any business
 * transaction. A partner who prefers to poll still polls
 * {@code GET /integration/v1/shipments/events?since=...}, which is unchanged.
 */
@Component
public class ShipmentEventPublisher {

    private final ShipmentOutboxEventRepository outboxRepository;
    private final AuditRecorder auditRecorder;
    private final TransportEventRecorder transportEvents;
    private final EventFanoutPort eventFanout;

    public ShipmentEventPublisher(ShipmentOutboxEventRepository outboxRepository, AuditRecorder auditRecorder,
            TransportEventRecorder transportEvents, EventFanoutPort eventFanout) {
        this.outboxRepository = outboxRepository;
        this.auditRecorder = auditRecorder;
        this.transportEvents = transportEvents;
        this.eventFanout = eventFanout;
    }

    /**
     * Publishes one transition of {@code trip}.
     *
     * @param occurredAt when the business fact happened - the operator's own actual time, not the
     *     instant this method ran, so a backdated departure produces a backdated event
     * @param metadata extra business detail for the audit trail only; the outbox row carries the
     *     shipment number and nothing else, because a partner reads the shipment itself for
     *     everything else
     */
    public void publish(CompanyScope scope, Trip trip, ShipmentEventType eventType, OffsetDateTime occurredAt,
            Map<String, Object> metadata) {
        publish(scope, trip, null, eventType, occurredAt, metadata);
    }

    /**
     * {@link #publish} for a fact that happened at one <em>stop</em> of the shipment - a delivery
     * result, since migration V28.
     *
     * <p>The stop id reaches the timeline entry and nothing else. The outbox row still carries the
     * shipment number and only that, exactly as V20 designed it: a partner re-reads the shipment,
     * whose order list reports each delivery result, rather than being handed a second copy of it
     * that can drift. The audit row is aggregate-scoped to the shipment for the same reason - it is
     * the shipment that was changed, and the stop travels in the metadata.
     *
     * @param tripStopId the stop the fact happened at, or null for a trip-level transition
     */
    public void publish(CompanyScope scope, Trip trip, UUID tripStopId, ShipmentEventType eventType,
            OffsetDateTime occurredAt, Map<String, Object> metadata) {
        ShipmentOutboxEvent published = outboxRepository.saveAndFlush(new ShipmentOutboxEvent(
                scope.companyId(), trip.id(), trip.shipmentNumber(), eventType, occurredAt));

        // The fourth audience, added in V35: whoever asked to be pushed this event rather than to
        // poll for it. The outbox row's own id travels with it and becomes the event id the
        // receiver deduplicates on, so a push and a poll describe the same fact with the same
        // identifier - which is what lets a partner move from one to the other without a gap or a
        // duplicate.
        eventFanout.fanOut(new PublishedEventNotification(
                published.id(),
                scope.companyId(),
                eventType.name(),
                PublishedEventNotification.SHIPMENT,
                trip.id(),
                trip.shipmentNumber(),
                occurredAt));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("shipmentNumber", trip.shipmentNumber());
        detail.put("status", trip.status().name());
        if (metadata != null) {
            metadata.forEach((key, value) -> {
                if (value != null) {
                    detail.put(key, value);
                }
            });
        }
        auditRecorder.record(scope, AuditAggregateType.SHIPMENT, trip.id(), auditActionFor(eventType), detail);

        // ...and the third audience, added in V27: the dispatcher reading this shipment's day in
        // the trip workspace. Written here rather than at each transition for the same reason the
        // other two are - three records of one fact that can drift is what a single publisher
        // exists to prevent.
        TransportEventType timelineType = transportEventFor(eventType);
        if (timelineType != null) {
            transportEvents.record(scope, trip.id(), tripStopId, timelineType, occurredAt, null, detail);
        }
    }

    /** {@link #publish} with no extra audit detail. */
    public void publish(CompanyScope scope, Trip trip, ShipmentEventType eventType, OffsetDateTime occurredAt) {
        publish(scope, trip, eventType, occurredAt, Map.of());
    }

    /**
     * The event type a partner sees and the action the audit trail records are the same fact told
     * to two audiences, so they are paired here rather than at each of the five call sites - which
     * is what would let one of them publish {@code SHIPMENT_DISPATCHED} while auditing
     * {@code UPDATE}.
     */
    /**
     * The timeline entry a published transition produces, or {@code null} when it produces none.
     *
     * <p>{@link ShipmentEventType#SHIPMENT_CHANGED} is the null case and stays one until something
     * can produce it: V20 reserved the value, V25 confirmed it still has no source, and inventing
     * a {@code TRIP_CHANGED} for it would put an entry in the timeline that no transition writes.
     */
    private static TransportEventType transportEventFor(ShipmentEventType eventType) {
        return switch (eventType) {
            case SHIPMENT_CONFIRMED -> TransportEventType.TRIP_CONFIRMED;
            case SHIPMENT_READY -> TransportEventType.TRIP_READY;
            case SHIPMENT_DISPATCHED -> TransportEventType.TRIP_DISPATCHED;
            case SHIPMENT_COMPLETED -> TransportEventType.TRIP_COMPLETED;
            case SHIPMENT_CANCELLED -> TransportEventType.TRIP_CANCELLED;
            case DELIVERY_RESULT_RECORDED -> TransportEventType.DELIVERY_RECORDED;
            case TENDER_SENT -> TransportEventType.TENDER_SENT;
            case TENDER_ACCEPTED -> TransportEventType.TENDER_ACCEPTED;
            case TENDER_REJECTED -> TransportEventType.TENDER_REJECTED;
            case TENDER_EXPIRED -> TransportEventType.TENDER_EXPIRED;
            case TENDER_CANCELLED -> TransportEventType.TENDER_CANCELLED;
            case SHIPMENT_CHANGED -> null;
        };
    }

    private static AuditAction auditActionFor(ShipmentEventType eventType) {
        return switch (eventType) {
            case SHIPMENT_CONFIRMED -> AuditAction.SHIPMENT_CONFIRMED;
            case SHIPMENT_READY -> AuditAction.SHIPMENT_READY;
            case SHIPMENT_DISPATCHED -> AuditAction.SHIPMENT_DISPATCHED;
            case SHIPMENT_COMPLETED -> AuditAction.SHIPMENT_COMPLETED;
            case SHIPMENT_CANCELLED -> AuditAction.SHIPMENT_CANCELLED;
            case DELIVERY_RESULT_RECORDED -> AuditAction.DELIVERY_RESULT_RECORDED;
            case TENDER_SENT -> AuditAction.TENDER_SENT;
            case TENDER_ACCEPTED -> AuditAction.TENDER_ACCEPTED;
            case TENDER_REJECTED -> AuditAction.TENDER_REJECTED;
            case TENDER_EXPIRED -> AuditAction.TENDER_EXPIRED;
            case TENDER_CANCELLED -> AuditAction.TENDER_CANCELLED;
            case SHIPMENT_CHANGED -> AuditAction.UPDATE;
        };
    }
}
