package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.OrderDelivery;
import com.ebim.tms.planning.domain.ShipmentEventType;
import com.ebim.tms.planning.domain.StopExecutionStatus;
import com.ebim.tms.planning.domain.TransportEventSource;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.infrastructure.OrderDeliveryRepository;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActor;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What was actually handed over at a stop (migration V28): one order's commercial outcome, recorded
 * by whoever worked the delivery and corrected by whoever finds it wrong.
 *
 * <p><b>Why it is not part of {@code TripStopExecutionService}.</b> That one walks a stop through a
 * transition table - arrived, in service, completed - and each move is a step in a sequence. This
 * records a result that has no sequence: any outcome may replace any other while the shipment is
 * open, because a correction is not a transition. They share a permission, a row lock and a trip;
 * they share no rules.
 *
 * <p><b>The window.</b> A delivery can be recorded from the moment the vehicle leaves until the
 * shipment is closed out - {@link TripStatus#IN_TRANSIT} and {@link TripStatus#COMPLETED} - and at
 * no other time:
 *
 * <ul>
 *   <li>before departure there is nothing to record, and a "delivery" against a shipment still on
 *       the dock is a mistake rather than an early one;</li>
 *   <li><em>after</em> completion is deliberately allowed, unlike a stop transition, because that
 *       is when the paperwork comes back. A dispatcher closing the day at 18:00 and keying twelve
 *       signed notes at 18:40 is the ordinary case, and forcing them to leave the shipment open to
 *       do it would corrupt the completion time to protect the delivery record;</li>
 *   <li>a cancelled shipment delivered nothing, so it is refused outright.</li>
 * </ul>
 *
 * <p><b>Concurrency</b> follows the rest of stop execution exactly: every recording takes the
 * trip's row lock first, so two dispatchers recording the same delivery serialise rather than race,
 * and the {@code (stop, order)} uniqueness invariant is what the second one lands on.
 */
@Service
public class TripDeliveryService {

    /** The tolerance the rest of execution uses, and for the same reason: browser clocks. */
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(5);

    private final TripRepository tripRepository;
    private final OrderDeliveryRepository deliveryRepository;
    private final TripOrderAssignmentRepository assignmentRepository;
    private final OrderPlanningPort orderPlanningPort;
    private final ShipmentEventPublisher eventPublisher;
    private final TripAlertPublisher alerts;
    private final TripViewAssembler assembler;
    private final OrderExecutionPropagator orderExecution;
    private final AuditActorProvider auditActorProvider;

    public TripDeliveryService(TripRepository tripRepository, OrderDeliveryRepository deliveryRepository,
            TripOrderAssignmentRepository assignmentRepository, OrderPlanningPort orderPlanningPort,
            ShipmentEventPublisher eventPublisher, TripAlertPublisher alerts, TripViewAssembler assembler,
            OrderExecutionPropagator orderExecution, AuditActorProvider auditActorProvider) {
        this.tripRepository = tripRepository;
        this.deliveryRepository = deliveryRepository;
        this.assignmentRepository = assignmentRepository;
        this.orderPlanningPort = orderPlanningPort;
        this.eventPublisher = eventPublisher;
        this.alerts = alerts;
        this.assembler = assembler;
        this.orderExecution = orderExecution;
        this.auditActorProvider = auditActorProvider;
    }

    /**
     * Records - or replaces - what happened to one order at one stop.
     *
     * <p>Idempotent in the sense that matters for a retried request: sending the same body twice
     * leaves the same row, because the second call overwrites the first with identical values. It
     * is deliberately <em>not</em> short-circuited the way a repeated stop transition is: two
     * identical stop arrivals are one event, while two identical delivery recordings are two
     * statements by (possibly) two people, and the timeline should say so.
     */
    @Transactional
    public TripDetailView record(CompanyScope scope, UUID tripId, UUID stopId, UUID orderId,
            DeliveryResultRequest request) {
        Trip trip = tripRepository.findByIdAndCompanyIdForUpdate(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
        TripStop stop = trip.stops().stream()
                .filter(candidate -> stopId.equals(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Stop not found on this trip."));

        requireRecordable(trip);
        requireStopReached(trip, stop);
        PlannableOrder order = requireOrderOnStop(scope, trip, stop, orderId);

        DeliveryResult result = request.result();
        String notes = blankToNull(request.notes());
        String receiverName = blankToNull(request.receiverName());
        String receiverDocument = blankToNull(request.receiverDocument());
        requireConsistent(result, request.deliveredAt(), receiverName, receiverDocument, notes);
        OffsetDateTime deliveredAt = resolveDeliveredAt(request.deliveredAt(), trip, stop);

        AuditActor actor = auditActorProvider.current().orElseThrow(() -> new IllegalStateException(
                "no authenticated actor: a delivery must be recorded inside an authenticated request"));
        TransportEventSource source =
                actor.isMachine() ? TransportEventSource.INTEGRATION : TransportEventSource.OPERATOR;

        OrderDelivery delivery = deliveryRepository
                .findByCompanyIdAndTripStopIdAndOrderId(scope.companyId(), stop.id(), orderId)
                .orElse(null);
        if (delivery == null) {
            delivery = new OrderDelivery(scope.companyId(), trip.id(), stop.id(), orderId, result, deliveredAt,
                    receiverName, receiverDocument, notes, source, actor.appUserId(), actor.email(),
                    actor.machineActorLabel());
        } else {
            delivery.record(result, deliveredAt, receiverName, receiverDocument, notes, source, actor.appUserId(),
                    actor.email(), actor.machineActorLabel());
        }
        // The returned instance is kept: the alert below is keyed on the delivery's id, and on the
        // insert path that id does not exist until this flush.
        delivery = deliveryRepository.saveAndFlush(delivery);

        // One user action, three records - the outbox row a partner polls, the audit row a
        // compliance reader searches, and the timeline entry a dispatcher reads - written together
        // in this transaction so they cannot drift. The stop travels to the timeline; the outbox
        // row carries the shipment number and nothing else, exactly as V20 designed it.
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("stopSequence", stop.sequence());
        detail.put("orderNumber", order.orderNumber());
        detail.put("result", result.name());
        // A result that allows no time still happened at a time; "now" stands in for it, once, so
        // the event and the alert below cannot land a few milliseconds apart on two clock reads.
        OffsetDateTime recordedAt = deliveredAt == null ? OffsetDateTime.now() : deliveredAt;
        eventPublisher.publish(scope, trip, stop.id(), ShipmentEventType.DELIVERY_RESULT_RECORDED,
                recordedAt, detail);
        // And the bell (V32), in both directions: a shortfall raises the alert, a correction back
        // to DELIVERED resolves it. See TripAlertPublisher.deliveryRecorded - a delivery record is
        // edited in place, so an alert that only ever appeared would outlive the problem.
        alerts.deliveryRecorded(scope, trip, stop, delivery, order.orderNumber(), recordedAt);

        // And, on a shipment that has already been closed out, the order's own lifecycle (V36).
        // The window above stays open after completion because the signed notes come back later;
        // without this, an order closed out as failed at 18:00 would still read failed after the
        // note proving delivery was keyed at 18:40. Inside this transaction, so the delivery row
        // and the status it implies move together. No-op while the trip is still running - the
        // close-out at completion is what reads the rows then.
        orderExecution.deliveryRecorded(scope, trip, orderId);

        return assembler.toDetail(trip, scope.companyId());
    }

    /**
     * The one delivery a caller addressed, for the evidence endpoints - loaded through the trip and
     * the stop rather than by id alone, so a delivery id from another shipment cannot be attached to
     * this one's stop.
     */
    @Transactional(readOnly = true)
    public OrderDelivery requireDelivery(CompanyScope scope, UUID tripId, UUID deliveryId) {
        OrderDelivery delivery = deliveryRepository.findByIdAndCompanyId(deliveryId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Delivery not found."));
        if (!delivery.tripId().equals(tripId)) {
            // Not a 403: inside this company the row exists, but it is not on the trip the URL
            // names, and saying "not found" for that path is the honest answer.
            throw new ResourceNotFoundException("Delivery not found on this trip.");
        }
        return delivery;
    }

    /**
     * The shipment has left and has not been cancelled. {@link TripStatus#COMPLETED} is allowed on
     * purpose - see the class comment on the recording window.
     */
    private static void requireRecordable(Trip trip) {
        if (trip.status() != TripStatus.IN_TRANSIT && trip.status() != TripStatus.COMPLETED) {
            throw new ConflictException("Trip " + trip.shipmentNumber() + " is " + trip.status()
                    + " and nothing can have been delivered on it yet.");
        }
    }

    /**
     * The vehicle has been at the stop, one way or another. {@link StopExecutionStatus#PENDING} is
     * the only outcome refused: recording what was handed over at a destination nobody has reached
     * is not an early record, it is a wrong one. Every other outcome is legal, including
     * {@code SKIPPED} and {@code FAILED} - which is exactly where {@code NOT_ATTEMPTED} rows come
     * from.
     */
    private static void requireStopReached(Trip trip, TripStop stop) {
        if (stop.executionStatus() == StopExecutionStatus.PENDING) {
            throw new ConflictException("Stop " + stop.sequence() + " of trip " + trip.shipmentNumber()
                    + " has not been reached yet, so nothing can have been delivered there.");
        }
    }

    /**
     * The order is on this trip and this stop is where it goes.
     *
     * <p>The second half is the one that matters: without it a dispatcher could record order 4711 as
     * delivered at a stop it was never going to, and the delivery record would contradict the plan
     * it belongs to. The order's destination is read from the orders module through
     * {@link OrderPlanningPort}, which is the only way {@code planning} is allowed to ask.
     */
    private PlannableOrder requireOrderOnStop(CompanyScope scope, Trip trip, TripStop stop, UUID orderId) {
        assignmentRepository.findByTripIdAndOrderIdAndStatus(trip.id(), orderId, AssignmentStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "That order is not currently assigned to trip " + trip.shipmentNumber() + "."));
        PlannableOrder order = orderPlanningPort.findAllInCompany(Set.of(orderId), scope.companyId()).get(orderId);
        if (order == null) {
            throw new ResourceNotFoundException("Order not found.");
        }
        if (!stop.destinationId().equals(order.destinationId())) {
            throw new ConflictException("Order " + order.orderNumber() + " is not delivered at stop "
                    + stop.sequence() + " of trip " + trip.shipmentNumber() + ".");
        }
        return order;
    }

    /**
     * The three field rules of {@link DeliveryResult}, refused here with a sentence a dispatcher can
     * read. {@code OrderDelivery} asserts the same rules again as a last line of defense, and
     * migration V28's CHECK constraints are the backstop under both - three layers, because this is
     * the record a dispute is argued from.
     */
    private static void requireConsistent(DeliveryResult result, OffsetDateTime deliveredAt, String receiverName,
            String receiverDocument, String notes) {
        if (result.requiresDeliveredAt() && deliveredAt == null) {
            throw new InvalidRequestException(
                    "deliveredAt is required to record an order as " + result + ".");
        }
        if (result.forbidsDeliveredAt() && deliveredAt != null) {
            throw new InvalidRequestException(
                    "deliveredAt cannot be set when nothing was attempted.");
        }
        if (!result.allowsReceiver() && (receiverName != null || receiverDocument != null)) {
            throw new InvalidRequestException(
                    "A receiver cannot be named when the result is " + result + ": nobody received anything.");
        }
        if (result.requiresNotes() && notes == null) {
            throw new InvalidRequestException("notes are required to explain a " + result + " delivery.");
        }
    }

    /**
     * The delivery time, checked against the day it claims to be part of.
     *
     * <p>Not in the future beyond the clock-skew tolerance, and not before the vehicle left: goods
     * cannot be handed over before the shipment departed, and a stop's own arrival bounds it more
     * tightly still when one was recorded. Null passes straight through - the results that allow no
     * time, and the ones that merely do not require one, are equally entitled to have none.
     */
    private static OffsetDateTime resolveDeliveredAt(OffsetDateTime requested, Trip trip, TripStop stop) {
        if (requested == null) {
            return null;
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (requested.isAfter(now.plus(CLOCK_SKEW_TOLERANCE))) {
            throw new InvalidRequestException("deliveredAt cannot be in the future.");
        }
        OffsetDateTime earliest = stop.actualArrivalAt() != null ? stop.actualArrivalAt() : trip.actualDepartureAt();
        if (earliest != null && requested.isBefore(earliest)) {
            throw new InvalidRequestException("deliveredAt cannot be before " + earliest + ".");
        }
        return requested;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
