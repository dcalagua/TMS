package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.planning.domain.EvidenceType;
import com.ebim.tms.planning.domain.OrderDelivery;
import com.ebim.tms.planning.domain.PortalAction;
import com.ebim.tms.planning.domain.PortalActionReceipt;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.infrastructure.OrderDeliveryRepository;
import com.ebim.tms.planning.infrastructure.PortalActionReceiptRepository;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.IdempotencyConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stop execution and proof of delivery from the carrier portal (V70, ADR-018 addendum).
 *
 * <p><b>Not a second implementation.</b> Every action delegates to the service the office uses -
 * {@link TripStopExecutionService} for the stop's transition table, {@link TripDeliveryService}
 * for the delivery result and its quantity ceilings, {@link DeliveryEvidenceService} for the
 * photos and the signature, {@link TripExceptionService} for incidents - inside one transaction.
 * The same rules refuse the same requests, the same events are published (the warehouse webhook
 * {@code DELIVERY_RESULT_RECORDED} included), and the recorders stamp the source
 * {@code CARRIER_PORTAL} because the scope is carrier-scoped. What this class adds is only what the
 * portal needs on top:
 *
 * <ul>
 *   <li><b>the carrier boundary</b>: the trip must be one of the caller's carrier's dispatched trips
 *       ({@link PortalTripService#visibleTrip}, one 404 for "not yours" and "does not exist"), and the
 *       V70 policies narrow every statement again in the database;</li>
 *   <li><b>the window</b>: a carrier acts on a trip that is on the road ({@code IN_TRANSIT}) and at no
 *       other time. Once the office closes the trip out, corrections are the office's;</li>
 *   <li><b>idempotency</b> (ADR-018 section 5): every action carries an {@code Idempotency-Key}, kept in
 *       {@code tms.portal_action_receipt} with the action and its target. A replay - the offline queue
 *       resending something the server did receive - is answered with the current trip and records
 *       nothing; the same key on another action or target is a 409;</li>
 *   <li><b>a delivery in one request</b>: the result, the refused amounts of a partial delivery and its
 *       evidence travel together, so a phone with no signal queues one entry rather than a chain whose
 *       second half needs an id the first has not returned yet.</li>
 * </ul>
 *
 * <p>The trip's row lock is taken before the receipt is looked up, so the same request sent twice at
 * once serialises here and the second one answers from the first.
 */
@Service
public class PortalExecutionService {

    static final int MAX_KEY = 128;
    static final int MAX_PHOTOS = 5;

    private final PortalTripService portal;
    private final TripRepository trips;
    private final PortalActionReceiptRepository receipts;
    private final TripStopExecutionService stopExecution;
    private final TripDeliveryService deliveries;
    private final DeliveryEvidenceService evidence;
    private final TripExceptionService exceptions;
    private final TripOrderAssignmentRepository assignments;
    private final OrderDeliveryRepository orderDeliveries;
    private final AuditActorProvider actors;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public PortalExecutionService(PortalTripService portal, TripRepository trips,
            PortalActionReceiptRepository receipts, TripStopExecutionService stopExecution,
            TripDeliveryService deliveries, DeliveryEvidenceService evidence, TripExceptionService exceptions,
            TripOrderAssignmentRepository assignments, OrderDeliveryRepository orderDeliveries,
            AuditActorProvider actors, Clock clock) {
        this.portal = portal;
        this.trips = trips;
        this.receipts = receipts;
        this.stopExecution = stopExecution;
        this.deliveries = deliveries;
        this.evidence = evidence;
        this.exceptions = exceptions;
        this.assignments = assignments;
        this.orderDeliveries = orderDeliveries;
        this.actors = actors;
        this.clock = clock;
    }

    /** The vehicle is at the stop. */
    @Transactional
    public PortalTripDetailView arrive(CompanyScope scope, UUID tripId, UUID stopId, String idempotencyKey,
            TripStopExecutionRequest request) {
        return execute(scope, tripId, stopId, null, PortalAction.STOP_ARRIVE, idempotencyKey,
                () -> stopExecution.arrive(scope, tripId, stopId, request));
    }

    /** Unloading has started. Optional, as in the office: arrival may go straight to completion. */
    @Transactional
    public PortalTripDetailView startService(CompanyScope scope, UUID tripId, UUID stopId, String idempotencyKey,
            TripStopExecutionRequest request) {
        return execute(scope, tripId, stopId, null, PortalAction.STOP_START_SERVICE, idempotencyKey,
                () -> stopExecution.startService(scope, tripId, stopId, request));
    }

    /** Served and left. */
    @Transactional
    public PortalTripDetailView complete(CompanyScope scope, UUID tripId, UUID stopId, String idempotencyKey,
            TripStopExecutionRequest request) {
        return execute(scope, tripId, stopId, null, PortalAction.STOP_COMPLETE, idempotencyKey,
                () -> stopExecution.complete(scope, tripId, stopId, request));
    }

    /** Attempted and not served, with a typed reason - which opens the exception, as in the office. */
    @Transactional
    public PortalTripDetailView fail(CompanyScope scope, UUID tripId, UUID stopId, String idempotencyKey,
            TripStopFailureRequest request) {
        return execute(scope, tripId, stopId, null, PortalAction.STOP_FAIL, idempotencyKey,
                () -> stopExecution.fail(scope, tripId, stopId, request));
    }

    /** A problem on the trip or one of its stops. */
    @Transactional
    public PortalTripDetailView reportIncident(CompanyScope scope, UUID tripId, String idempotencyKey,
            TripExceptionRequest request) {
        return execute(scope, tripId, request.tripStopId(), null, PortalAction.INCIDENT_REPORT, idempotencyKey,
                () -> exceptions.report(scope, tripId, request));
    }

    /**
     * What happened to one order at one stop, with its evidence.
     *
     * <p>Every file is checked before anything is written, so one bad photo stores nothing. The
     * result goes through {@link TripDeliveryService#record} and each file through
     * {@link DeliveryEvidenceService#attach}, both joining this transaction.
     */
    @Transactional
    public PortalTripDetailView recordDelivery(CompanyScope scope, UUID tripId, UUID stopId, UUID orderId,
            String idempotencyKey, PortalDeliveryCommand command) {
        return execute(scope, tripId, stopId, orderId, PortalAction.DELIVERY_RECORD, idempotencyKey, () -> {
            requireEvidenceAcceptable(command);
            DeliveryResultRequest request = toRequest(scope, tripId, orderId, command);
            deliveries.record(scope, tripId, stopId, orderId, request);
            if (command.hasEvidence()) {
                OrderDelivery delivery = orderDeliveries
                        .findByCompanyIdAndTripStopIdAndOrderId(scope.companyId(), stopId, orderId)
                        .orElseThrow(() -> new IllegalStateException("the delivery just recorded is not there"));
                for (EvidenceUpload photo : command.photos()) {
                    evidence.attach(scope, tripId, delivery.id(), EvidenceType.PHOTO, null, photo);
                }
                if (command.signature() != null) {
                    evidence.attach(scope, tripId, delivery.id(), EvidenceType.SIGNATURE, null, command.signature());
                }
            }
        });
    }

    /**
     * The shape every portal action shares: refuse a non-carrier, read the key, check the trip is
     * this carrier's, lock it, answer a replay, check it is on the road, act, keep the receipt.
     */
    private PortalTripDetailView execute(CompanyScope scope, UUID tripId, UUID stopId, UUID orderId,
            PortalAction action, String rawKey, Runnable work) {
        PortalTripService.requireCarrier(scope);
        String key = requireKey(rawKey);
        UUID actorId = actors.requireAppUserId();
        portal.visibleTrip(scope, tripId);
        Trip trip = trips.findByIdAndCompanyIdForUpdate(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));

        Optional<PortalActionReceipt> previous =
                receipts.findByCompanyIdAndAppUserIdAndIdempotencyKey(scope.companyId(), actorId, key);
        if (previous.isPresent()) {
            if (!previous.get().answersFor(action, tripId, stopId, orderId)) {
                throw new IdempotencyConflictException(
                        "This Idempotency-Key was already used for another action. Send this one with a new key.");
            }
            return portal.detail(scope, tripId);
        }

        if (trip.status() != TripStatus.IN_TRANSIT) {
            throw new ConflictException("Trip " + trip.shipmentNumber() + " is " + trip.status()
                    + ": the portal records stops and deliveries only while it is on the road. Ask the office"
                    + " to correct it.");
        }

        work.run();

        try {
            receipts.saveAndFlush(new PortalActionReceipt(scope.companyId(), tripId, stopId, orderId, action,
                    actorId, key));
        } catch (DataIntegrityViolationException raced) {
            // uq_portal_action_receipt_key, lost to the same key arriving twice at once. The row lock
            // above makes this unreachable for one trip; a key reused across two trips at once ends
            // here, and the rollback takes this action with it.
            throw new IdempotencyConflictException("This action is already being recorded. Retry it.");
        }
        return portal.detail(scope, tripId);
    }

    private void requireEvidenceAcceptable(PortalDeliveryCommand command) {
        if (!command.hasEvidence()) {
            return;
        }
        if (!evidence.isEnabled()) {
            // 400 and not 503: no retry will help, and the offline queue must show it rather than
            // resend it forever. The result itself can be recorded without the files.
            throw new InvalidRequestException("This system does not store photos or signatures. Record the result"
                    + " without them.");
        }
        if (command.photos().size() > MAX_PHOTOS) {
            throw new InvalidRequestException("At most " + MAX_PHOTOS + " photos per delivery.");
        }
        command.photos().forEach(photo -> evidence.requireAcceptable(EvidenceType.PHOTO, photo));
        if (command.signature() != null) {
            evidence.requireAcceptable(EvidenceType.SIGNATURE, command.signature());
        }
    }

    /**
     * The office's request, built from what a carrier types at the door.
     *
     * <p>The time defaults to now for every result that happened at a time, and is dropped for
     * {@code NOT_ATTEMPTED}, which has none. A partial delivery carries what the customer refused,
     * and the rest is derived from this trip's allocation for the order - the attempt is what was
     * loaded, the delivery what was not refused - so the driver types one figure, not nine, and
     * {@link TripDeliveryService}'s ceilings still apply to the result.
     */
    private DeliveryResultRequest toRequest(CompanyScope scope, UUID tripId, UUID orderId,
            PortalDeliveryCommand command) {
        DeliveryResult result = command.result();
        if (result == null) {
            throw new InvalidRequestException("result is required.");
        }
        OffsetDateTime deliveredAt = result.forbidsDeliveredAt() ? null
                : command.deliveredAt() != null ? command.deliveredAt() : OffsetDateTime.now(clock);

        DeliveryResultRequest.DeliveryQuantitiesRequest quantities = null;
        if (command.hasRefusedAmounts()) {
            if (result != DeliveryResult.PARTIAL) {
                throw new InvalidRequestException("Refused quantities are recorded only for a partial delivery.");
            }
            OrderAmounts allocated = assignments
                    .findByTripIdAndOrderIdAndStatus(tripId, orderId, AssignmentStatus.ACTIVE)
                    .filter(assignment -> assignment.companyId().equals(scope.companyId()))
                    .map(TripOrderAssignment::assigned)
                    .orElseThrow(() -> new ResourceNotFoundException("That order is not on this trip."));
            quantities = PortalDeliveryQuantities.fromRefused(allocated, command.refused());
        } else if (result == DeliveryResult.PARTIAL) {
            throw new InvalidRequestException("Say how much the customer refused to record a partial delivery.");
        }
        return new DeliveryResultRequest(result, deliveredAt, command.receiverName(), command.receiverDocument(),
                command.notes(), quantities);
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new InvalidRequestException("The Idempotency-Key header is required.");
        }
        String trimmed = key.trim();
        if (trimmed.length() > MAX_KEY) {
            throw new InvalidRequestException("The Idempotency-Key header must be at most " + MAX_KEY + " characters.");
        }
        return trimmed;
    }
}
