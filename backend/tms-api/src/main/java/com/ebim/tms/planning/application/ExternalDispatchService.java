package com.ebim.tms.planning.application;

import com.ebim.tms.planning.application.DispatchReconciler.Code;
import com.ebim.tms.planning.application.DispatchReconciler.Discrepancy;
import com.ebim.tms.planning.domain.DispatchVerificationStatus;
import com.ebim.tms.planning.domain.ExternalDispatch;
import com.ebim.tms.planning.domain.ExternalDispatchOrder;
import com.ebim.tms.planning.domain.ExternalDispatchOutcome;
import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.domain.TransportEventType;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.domain.WarehouseMilestone;
import com.ebim.tms.planning.infrastructure.ExternalDispatchOrderRepository;
import com.ebim.tms.planning.infrastructure.ExternalDispatchRepository;
import com.ebim.tms.planning.infrastructure.PlanningRunRepository;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.planning.infrastructure.WarehouseMilestoneRepository;
import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.DispatchConfirmationCommand;
import com.ebim.tms.shared.reference.DispatchConfirmationResult;
import com.ebim.tms.shared.reference.DriverLookupPort;
import com.ebim.tms.shared.reference.DriverReference;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OrderExternalKey;
import com.ebim.tms.shared.reference.OrderLineSnapshot;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.OrderReferencePort;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.reference.WarehouseExecutionPort;
import com.ebim.tms.shared.reference.WarehouseMilestoneCommand;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import com.ebim.tms.shared.settings.DispatchConfirmationMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * What a warehouse system physically did, received into TMS (ADR-013, WAREHOUSE_EXECUTION_V1 §4):
 * the planning module's implementation of {@link WarehouseExecutionPort}.
 *
 * <p><b>A valid document is always stored.</b> Whatever TMS then decides - apply, reconcile, leave
 * unapplied - the document and its reconciliation are recorded first-class, because a true
 * physical fact must not disappear because TMS had a logical objection to it (ADR-013 section 3).
 * Every disagreement is a business outcome answered 200/201; the only refusals are a malformed
 * request and a real idempotency conflict.
 *
 * <p><b>Exactly one dispatch.</b> The trip's row lock is taken before anything is decided - the
 * same lock {@code TripExecutionService.dispatch} takes - so a person and a document arriving at
 * the same instant in {@code HYBRID} serialise, and the second one finds the trip departed and only
 * reconciles.
 */
@Service
public class ExternalDispatchService implements WarehouseExecutionPort {

    /** The same future tolerance a person's recorded time gets (TripExecutionService). */
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(5);

    private final ExternalDispatchRepository dispatches;
    private final ExternalDispatchOrderRepository dispatchOrders;
    private final WarehouseMilestoneRepository milestones;
    private final TripRepository tripRepository;
    private final PlanningRunRepository planningRunRepository;
    private final TripOrderAssignmentRepository assignmentRepository;
    private final TripExecutionService tripExecution;
    private final TripTenderService tenders;
    private final DispatchReadiness readiness;
    private final TransportEventRecorder transportEvents;
    private final CompanySettingsPort companySettings;
    private final OrderPlanningPort orderPlanningPort;
    private final OrderReferencePort orderReferencePort;
    private final CarrierLookupPort carrierLookupPort;
    private final VehicleLookupPort vehicleLookupPort;
    private final DriverLookupPort driverLookupPort;
    private final OriginLookupPort originLookupPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public ExternalDispatchService(ExternalDispatchRepository dispatches, ExternalDispatchOrderRepository dispatchOrders,
            WarehouseMilestoneRepository milestones, TripRepository tripRepository,
            PlanningRunRepository planningRunRepository,
            TripOrderAssignmentRepository assignmentRepository, TripExecutionService tripExecution,
            TripTenderService tenders, DispatchReadiness readiness, TransportEventRecorder transportEvents,
            CompanySettingsPort companySettings, OrderPlanningPort orderPlanningPort,
            OrderReferencePort orderReferencePort, CarrierLookupPort carrierLookupPort,
            VehicleLookupPort vehicleLookupPort, DriverLookupPort driverLookupPort, OriginLookupPort originLookupPort,
            ObjectMapper objectMapper, Clock clock) {
        this.dispatches = dispatches;
        this.dispatchOrders = dispatchOrders;
        this.milestones = milestones;
        this.tripRepository = tripRepository;
        this.planningRunRepository = planningRunRepository;
        this.assignmentRepository = assignmentRepository;
        this.tripExecution = tripExecution;
        this.tenders = tenders;
        this.readiness = readiness;
        this.transportEvents = transportEvents;
        this.companySettings = companySettings;
        this.orderPlanningPort = orderPlanningPort;
        this.orderReferencePort = orderReferencePort;
        this.carrierLookupPort = carrierLookupPort;
        this.vehicleLookupPort = vehicleLookupPort;
        this.driverLookupPort = driverLookupPort;
        this.originLookupPort = originLookupPort;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // -----------------------------------------------------------------------------------------
    // DISPATCH_CONFIRMED
    // -----------------------------------------------------------------------------------------

    @Override
    @Transactional
    public DispatchConfirmationResult receiveDispatch(CompanyScope scope, DispatchConfirmationCommand command) {
        requireNotInTheFuture(command.actualDispatchAt());

        // The trip first, under its row lock: every decision below reads it, and a person
        // dispatching the same trip right now must either finish before this or wait for it.
        Optional<Trip> trip = lockedTrip(scope, command.transportReference());

        Optional<ExternalDispatch> current = dispatches
                .findByCompanyIdAndSourceSystemAndDispatchReferenceAndSupersededAtIsNull(
                        scope.companyId(), command.sourceSystem(), command.dispatchReference());
        // An older revision than the current one is STALE whatever it contains: it has been
        // corrected already, and answering 409 for a superseded document would dead-letter nothing.
        if (current.isPresent() && current.get().revision() > command.revision()) {
            return resultOf(current.get(), "STALE", false);
        }

        Optional<ExternalDispatch> sameRevision = dispatches.findByCompanyIdAndSourceSystemAndDispatchReferenceAndRevision(
                scope.companyId(), command.sourceSystem(), command.dispatchReference(), command.revision());
        if (sameRevision.isPresent()) {
            if (!sameRevision.get().payloadHash().equals(command.payloadHash())) {
                throw new ConflictException("Dispatch " + command.dispatchReference() + " revision "
                        + command.revision() + " was already received with different content. Send a correction as "
                        + "revision " + (command.revision() + 1) + ".");
            }
            return resultOf(sameRevision.get(), "UNCHANGED", false);
        }

        DispatchConfirmationMode mode = companySettings.settingsOf(scope.companyId()).dispatchConfirmationMode();
        List<Discrepancy> decisionNotes = new ArrayList<>();
        ExternalDispatchOutcome outcome = trip.isEmpty()
                ? ExternalDispatchOutcome.RECORDED_UNMATCHED
                : decideAndApply(scope, trip.get(), command, mode, decisionNotes);

        Map<OrderExternalKey, UUID> knownOrders = orderReferencePort.findIdsByExternalKeys(
                command.orders().stream().map(DispatchConfirmationCommand.Order::key).toList(), scope.companyId());
        DispatchReconciler.Plan plan = trip.map(value -> planOf(scope, value, current)).orElse(null);
        DispatchReconciler.Result reconciled = DispatchReconciler.reconcile(command, plan, knownOrders);

        List<Discrepancy> discrepancies = new ArrayList<>(reconciled.discrepancies());
        discrepancies.addAll(decisionNotes);

        ExternalDispatch stored = store(scope, command, trip.orElse(null), outcome, reconciled.verification(),
                discrepancies, reconciled.orders(), current.orElse(null));
        trip.ifPresent(value -> recordOnTimeline(scope, value, stored, discrepancies.size()));
        return resultOf(stored, outcome.name(), true);
    }

    /**
     * The table of ADR-013 section 3. Operational blockers become discrepancies; only a database
     * invariant that genuinely cannot hold leaves the document {@code UNAPPLIED}, and nothing is
     * ever falsified to make it fit.
     */
    private ExternalDispatchOutcome decideAndApply(CompanyScope scope, Trip trip, DispatchConfirmationCommand command,
            DispatchConfirmationMode mode, List<Discrepancy> notes) {
        return switch (trip.status()) {
            case DRAFT, CANCELLED -> ExternalDispatchOutcome.UNAPPLIED;
            case IN_TRANSIT, COMPLETED -> ExternalDispatchOutcome.RECONCILED;
            case CONFIRMED, READY_FOR_DISPATCH -> mode.externalMayDispatch()
                    ? apply(scope, trip, command, notes)
                    : ExternalDispatchOutcome.RECONCILED;
        };
    }

    private ExternalDispatchOutcome apply(CompanyScope scope, Trip trip, DispatchConfirmationCommand command,
            List<Discrepancy> notes) {
        OffsetDateTime at = command.actualDispatchAt();
        // V42: a shipment accepted by a carrier that does not own its vehicle may not depart. A CHECK
        // constraint says so, and a document cannot argue with it.
        if (trip.awaitsCarrierVehicle()) {
            notes.add(Discrepancy.of(Code.NOT_APPLIED, "The shipment was accepted by a carrier that does not own "
                    + "its vehicle; the database refuses its departure until one of that carrier's vehicles is "
                    + "assigned. A person must resolve it."));
            return ExternalDispatchOutcome.UNAPPLIED;
        }
        // V25: execution times only move forward. A dispatch stamped before the plan was committed
        // (or made ready) cannot be recorded as that plan's departure without inventing a time.
        OffsetDateTime earliest = trip.status() == TripStatus.CONFIRMED ? trip.confirmedAt() : trip.readyAt();
        if (earliest != null && at.isBefore(earliest)) {
            notes.add(Discrepancy.of(Code.NOT_APPLIED, "The warehouse dispatched at " + at + ", before the shipment "
                    + "was " + (trip.status() == TripStatus.CONFIRMED ? "confirmed" : "made ready") + " (" + earliest
                    + ") in TMS. A person must resolve it."));
            return ExternalDispatchOutcome.UNAPPLIED;
        }
        for (DispatchReadiness.Blocker blocker : readiness.evaluate(scope, trip, at)) {
            notes.add(new Discrepancy(Code.DISPATCH_BLOCKER, null, null, null, null, null,
                    blocker.code().name() + ": " + blocker.message()));
        }
        if (tenders.hasLiveOffer(scope, trip)) {
            notes.add(new Discrepancy(Code.DISPATCH_BLOCKER, null, null, null, null, null,
                    "OPEN_TENDER: a tender offer is still live on this shipment and was not withdrawn; a person "
                            + "should withdraw it."));
        }
        tripExecution.applyWarehouseDispatch(scope, trip, at, command.integrationClientId());
        return ExternalDispatchOutcome.APPLIED;
    }

    private DispatchReconciler.Plan planOf(CompanyScope scope, Trip trip, Optional<ExternalDispatch> current) {
        List<TripOrderAssignment> active =
                assignmentRepository.findByTripIdAndStatusOrderByAssignedAtAsc(trip.id(), AssignmentStatus.ACTIVE);
        Set<UUID> orderIds = active.stream().map(TripOrderAssignment::orderId).collect(Collectors.toSet());
        Map<UUID, PlannableOrder> orders = orderPlanningPort.findAllInCompany(orderIds, scope.companyId());
        Map<UUID, List<OrderLineSnapshot>> lines = orderReferencePort.linesOf(orderIds, scope.companyId());

        List<DispatchReconciler.PlannedOrder> planned = active.stream().map(assignment -> {
            PlannableOrder order = orders.get(assignment.orderId());
            return new DispatchReconciler.PlannedOrder(assignment.orderId(),
                    order == null ? null : order.externalSource(), order == null ? null : order.externalReference(),
                    assignment.wholeOrder(), assignment.assigned(), lines.getOrDefault(assignment.orderId(), List.of()));
        }).toList();

        Set<String> carrierCodes = new HashSet<>();
        if (trip.carrierId() != null) {
            Optional.ofNullable(carrierLookupPort.findAllInCompany(Set.of(trip.carrierId()), scope.companyId())
                    .get(trip.carrierId())).map(MasterReference::code).ifPresent(carrierCodes::add);
            Optional.ofNullable(carrierLookupPort.externalReferencesInCompany(Set.of(trip.carrierId()), scope.companyId())
                    .get(trip.carrierId())).ifPresent(carrierCodes::add);
        }
        String plate = trip.vehicleId() == null ? null
                : Optional.ofNullable(vehicleLookupPort.findAllInCompany(Set.of(trip.vehicleId()), scope.companyId())
                        .get(trip.vehicleId())).map(VehicleCapacityReference::licensePlate).orElse(null);
        DriverReference driver = trip.driverId() == null ? null
                : driverLookupPort.findAllInCompany(Set.of(trip.driverId()), scope.companyId()).get(trip.driverId());
        Set<String> warehouseCodes = new HashSet<>();
        UUID origin = originOf(scope, trip);
        if (origin != null) {
            Optional.ofNullable(originLookupPort.externalReferencesInCompany(Set.of(origin), scope.companyId()).get(origin))
                    .ifPresent(warehouseCodes::add);
            Optional.ofNullable(originLookupPort.findAllInCompany(Set.of(origin), scope.companyId()).get(origin))
                    .map(MasterReference::code).ifPresent(warehouseCodes::add);
        }
        // The load an earlier document of this trip named - another dispatch reference, still current.
        String previousLoad = dispatches.findCurrentByTripIds(scope.companyId(), Set.of(trip.id())).stream()
                .filter(document -> current.isEmpty() || !document.id().equals(current.get().id()))
                .map(ExternalDispatch::loadReference)
                .filter(load -> load != null && !load.isBlank())
                .findFirst()
                .orElse(current.map(ExternalDispatch::loadReference).orElse(null));

        return new DispatchReconciler.Plan(trip.status(), trip.actualDepartureAt(), trip.dispatchSource(), carrierCodes,
                plate, driver == null ? null : driver.fullName(), driver == null ? null : driver.documentNumber(),
                warehouseCodes, previousLoad, planned);
    }

    private UUID originOf(CompanyScope scope, Trip trip) {
        return planningRunRepository.findByIdAndCompanyId(trip.planningRunId(), scope.companyId())
                .map(PlanningRun::originId)
                .orElse(null);
    }

    private ExternalDispatch store(CompanyScope scope, DispatchConfirmationCommand command, Trip trip,
            ExternalDispatchOutcome outcome, DispatchVerificationStatus verification, List<Discrepancy> discrepancies,
            List<DispatchReconciler.OrderMatch> orderMatches, ExternalDispatch superseded) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        ExternalDispatch document = new ExternalDispatch(scope.companyId(), trip == null ? null : trip.id(),
                command.integrationClientId(), command.idempotencyKey(), command.correlationId(), command.sourceSystem(),
                command.dispatchReference(), command.revision(), command.transportReference(), command.loadReference(),
                command.warehouseCode(), command.actualDispatchAt(), command.carrierCode(), command.carrierName(),
                command.vehicleLicensePlate(), command.vehicleType(), command.driverName(),
                command.driverDocumentNumber(), command.sealNumber(), command.transportDocumentNumber(),
                command.totalHandlingUnits(), command.totalWeightKg(), command.totalVolumeM3(), outcome, verification,
                toJson(discrepancies), command.payloadHash(), command.rawBody(), now);
        try {
            if (superseded != null) {
                // Marked before the new row is inserted: uq_external_dispatch_current allows one
                // current revision per document, and Hibernate would otherwise insert first.
                superseded.supersede(now);
                dispatches.saveAndFlush(superseded);
            }
            ExternalDispatch saved = dispatches.saveAndFlush(document);
            if (superseded != null) {
                superseded.linkSupersededBy(saved.id());
                dispatches.saveAndFlush(superseded);
            }
            Map<OrderExternalKey, DispatchConfirmationCommand.Order> byKey = new LinkedHashMap<>();
            command.orders().forEach(order -> byKey.putIfAbsent(order.key(), order));
            for (DispatchReconciler.OrderMatch match : orderMatches) {
                DispatchConfirmationCommand.Order order = byKey.get(match.key());
                if (order == null) {
                    continue;
                }
                dispatchOrders.save(new ExternalDispatchOrder(saved.id(), scope.companyId(), match.orderId(),
                        order.externalSource(), order.externalReference(), order.warehouseOrderNumber(),
                        order.handlingUnits(), order.weightKg(), order.volumeM3(), order.status(), match.result()));
            }
            dispatchOrders.flush();
            return saved;
        } catch (DataIntegrityViolationException raced) {
            // The same document delivered twice at the same instant: the loser is told to retry, and
            // its retry is answered UNCHANGED from the winner's row.
            throw new ConflictException("Dispatch " + command.dispatchReference() + " revision " + command.revision()
                    + " is being received concurrently. Retry the delivery.");
        }
    }

    private void recordOnTimeline(CompanyScope scope, Trip trip, ExternalDispatch document, int discrepancyCount) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("dispatchReference", document.dispatchReference());
        metadata.put("revision", document.revision());
        metadata.put("sourceSystem", document.sourceSystem());
        if (document.loadReference() != null) {
            metadata.put("loadReference", document.loadReference());
        }
        metadata.put("outcome", document.outcome().name());
        metadata.put("verificationStatus", document.verificationStatus().name());
        metadata.put("discrepancies", discrepancyCount);
        transportEvents.record(scope, trip.id(), null, TransportEventType.WAREHOUSE_DISPATCH_CONFIRMED,
                document.actualDispatchAt(), null, metadata);
    }

    // -----------------------------------------------------------------------------------------
    // WAREHOUSE_MILESTONE
    // -----------------------------------------------------------------------------------------

    @Override
    @Transactional
    public String receiveMilestone(CompanyScope scope, WarehouseMilestoneCommand command) {
        if (milestones.existsByCompanyIdAndSourceSystemAndEventId(scope.companyId(), command.sourceSystem(),
                command.eventId())) {
            return "DUPLICATE";
        }
        Optional<Trip> trip = tripRepository.findByShipmentNumberAndCompanyId(command.transportReference(),
                scope.companyId());
        try {
            milestones.saveAndFlush(new WarehouseMilestone(scope.companyId(), command.integrationClientId(),
                    command.sourceSystem(), command.eventId(), command.type(), command.transportReference(),
                    trip.map(Trip::id).orElse(null), command.loadReference(), command.warehouseCode(),
                    command.occurredAt(), OffsetDateTime.now(clock)));
        } catch (DataIntegrityViolationException raced) {
            return "DUPLICATE";
        }
        if (trip.isEmpty()) {
            return "UNKNOWN_SHIPMENT";
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventId", command.eventId());
        metadata.put("sourceSystem", command.sourceSystem());
        if (command.loadReference() != null) {
            metadata.put("loadReference", command.loadReference());
        }
        if (command.warehouseCode() != null) {
            metadata.put("warehouseCode", command.warehouseCode());
        }
        transportEvents.record(scope, trip.get().id(), null, timelineTypeOf(command.type()), command.occurredAt(),
                null, metadata);
        return "RECORDED";
    }

    /** The public milestone types, and the timeline types they are stored as (ADR-013 section 13). */
    static TransportEventType timelineTypeOf(String publicType) {
        return switch (publicType) {
            case "LOADING_STARTED" -> TransportEventType.WAREHOUSE_LOADING_STARTED;
            case "LOAD_READY" -> TransportEventType.WAREHOUSE_LOAD_READY;
            case "LOAD_CANCELLED" -> TransportEventType.WAREHOUSE_LOAD_CANCELLED;
            default -> throw new InvalidRequestException("Unknown milestone type " + publicType + ".");
        };
    }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    private Optional<Trip> lockedTrip(CompanyScope scope, String shipmentNumber) {
        return tripRepository.findByShipmentNumberAndCompanyIdForUpdate(shipmentNumber, scope.companyId());
    }

    private void requireNotInTheFuture(OffsetDateTime actualDispatchAt) {
        if (actualDispatchAt.isAfter(OffsetDateTime.now(clock).plus(CLOCK_SKEW_TOLERANCE))) {
            throw new InvalidRequestException("actualDispatchAt cannot be in the future.");
        }
    }

    private DispatchConfirmationResult resultOf(ExternalDispatch document, String outcome, boolean firstReceipt) {
        return new DispatchConfirmationResult(document.id(), document.dispatchReference(), document.revision(), outcome,
                document.transportReference(), document.verificationStatus().name(), fromJson(document.discrepancies()),
                firstReceipt);
    }

    private String toJson(List<Discrepancy> discrepancies) {
        List<DispatchConfirmationResult.Discrepancy> published = discrepancies.stream()
                .map(discrepancy -> new DispatchConfirmationResult.Discrepancy(discrepancy.code().name(),
                        discrepancy.severity().name(), discrepancy.orderReference(), discrepancy.lineNumber(),
                        discrepancy.planned(), discrepancy.dispatched(), discrepancy.uom(), discrepancy.detail()))
                .toList();
        try {
            return objectMapper.writeValueAsString(published);
        } catch (JacksonException impossible) {
            throw new IllegalStateException("discrepancies could not be serialised", impossible);
        }
    }

    List<DispatchConfirmationResult.Discrepancy> fromJson(String json) {
        try {
            return List.of(objectMapper.readValue(json, DispatchConfirmationResult.Discrepancy[].class));
        } catch (JacksonException unreadable) {
            throw new IllegalStateException("stored discrepancies could not be read", unreadable);
        }
    }
}
