package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.ShipmentEventType;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.reference.DriverLicenseStatus;
import com.ebim.tms.shared.reference.DriverLookupPort;
import com.ebim.tms.shared.reference.DriverReference;
import com.ebim.tms.shared.reference.ResourceAvailabilityPort;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The execution half of the trip lifecycle: a confirmed plan becomes a shipment that is ready,
 * then gone, then finished. Cancellation is deliberately <em>not</em> here - it is one action a
 * planner and a dispatcher share, reachable from a draft trip and from a committed one, and it
 * lives with the code that releases the orders ({@code TripService.cancel}).
 *
 * <p><b>The transition table is not restated here.</b> Every action asks
 * {@link TripStatus#canTransitionTo} and refuses with a message naming both states. That keeps
 * "which move is legal" a single fact in {@code planning.domain}, testable without Spring, rather
 * than three {@code if} statements that could disagree with each other and with migration V25's
 * CHECK constraints.
 *
 * <p><b>Idempotency.</b> A trip already in the state an action would produce is returned
 * unchanged: no second event, no second audit row, no error. This is checked <em>before</em> the
 * version is, on purpose - a dispatcher whose request timed out and who pressed the button again
 * is holding a stale version by definition, and answering "someone else changed this, reload" to
 * a retry of an operation that already succeeded is a worse lie than accepting it. It costs
 * nothing in safety: the intent was reached, and reaching it twice is not a different outcome.
 * Every other conflict - a genuinely competing edit, an illegal move - still fails.
 *
 * <p><b>Concurrency.</b> Every action takes the trip's row lock first, the same serialization
 * point {@code TripService} uses, so two dispatchers cannot both read {@code READY_FOR_DISPATCH}
 * and both write a departure.
 */
@Service
public class TripExecutionService {

    /**
     * How far into the future an operator-supplied time may sit before it is refused. Not zero:
     * the browser sends the clock of the machine it runs on, and a workstation a few seconds ahead
     * of the server must not make "the truck is leaving now" unrecordable. Not generous either - a
     * departure an hour from now is a typo, not a clock.
     */
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(5);

    private final TripRepository tripRepository;
    private final VehicleLookupPort vehicleLookupPort;
    private final DriverLookupPort driverLookupPort;
    private final ResourceAvailabilityPort resourceAvailabilityPort;
    private final ShipmentEventPublisher events;
    private final TripTenderService tenders;
    private final TripAlertPublisher alerts;
    private final TripViewAssembler assembler;
    private final OrderExecutionPropagator orderExecution;
    private final AuditActorProvider auditActorProvider;

    public TripExecutionService(TripRepository tripRepository, VehicleLookupPort vehicleLookupPort,
            DriverLookupPort driverLookupPort, ResourceAvailabilityPort resourceAvailabilityPort,
            ShipmentEventPublisher events, TripTenderService tenders,
            TripAlertPublisher alerts, TripViewAssembler assembler, OrderExecutionPropagator orderExecution,
            AuditActorProvider auditActorProvider) {
        this.tripRepository = tripRepository;
        this.resourceAvailabilityPort = resourceAvailabilityPort;
        this.vehicleLookupPort = vehicleLookupPort;
        this.driverLookupPort = driverLookupPort;
        this.events = events;
        this.tenders = tenders;
        this.alerts = alerts;
        this.assembler = assembler;
        this.orderExecution = orderExecution;
        this.auditActorProvider = auditActorProvider;
    }

    /**
     * {@code CONFIRMED → READY_FOR_DISPATCH}: the shipment is loaded, its paperwork is done and it
     * is waiting for a driver.
     *
     * <p>The vehicle is re-checked here and not only at confirmation. Confirmation may have been
     * yesterday, and a truck that has since gone into maintenance must not be declared ready to
     * leave - the same reasoning {@code PlanningRunService.confirm} gives for revalidating
     * everything instead of trusting the board.
     */
    @Transactional
    public TripDetailView markReadyForDispatch(CompanyScope scope, UUID tripId, TripExecutionRequest request) {
        return transition(scope, tripId, TripStatus.READY_FOR_DISPATCH, request, ShipmentEventType.SHIPMENT_READY,
                (trip, occurredAt) -> {
                    requireOperableVehicle(scope, trip);
                    requireOperableDriver(scope, trip);
                    requireNotBefore(occurredAt, trip.confirmedAt(), "before the trip was confirmed");
                    trip.markReadyForDispatch(occurredAt, auditActorProvider.requireAppUserId());
                });
    }

    /**
     * {@code READY_FOR_DISPATCH → IN_TRANSIT}: the vehicle has left. Records the real departure
     * beside the planned one rather than over it.
     *
     * <p>Any carrier tender still open on the shipment is withdrawn here (migration V31). Departure
     * is where an offer stops meaning anything - the truck went, so there is nothing left to accept
     * - and an offer left live would sit in the carrier's queue forever and occupy the trip's one
     * live tender slot. Not a refusal: a shipment leaving before its carrier answered is an ordinary
     * bad day, not an illegal state, and refusing to dispatch over it would stop a truck that is
     * already loaded.
     */
    @Transactional
    public TripDetailView dispatch(CompanyScope scope, UUID tripId, TripExecutionRequest request) {
        TripDetailView dispatched = transition(scope, tripId, TripStatus.IN_TRANSIT, request,
                ShipmentEventType.SHIPMENT_DISPATCHED,
                (trip, occurredAt) -> {
                    requireOperableVehicle(scope, trip);
                    requireOperableDriver(scope, trip);
                    requireCarrierOwnsTheVehicle(trip);
                    requireResourcesAvailable(scope, trip, occurredAt);
                    requireNotBefore(occurredAt, trip.readyAt(), "before the trip was made ready");
                    trip.dispatch(occurredAt, auditActorProvider.requireAppUserId());
                });
        tripRepository.findByIdAndCompanyId(tripId, scope.companyId()).ifPresent(trip -> {
            tenders.withdrawOpen(scope, trip, "Shipment " + trip.shipmentNumber()
                    + " departed before the carrier answered.");
            orderExecution.dispatched(scope, trip);
        });
        return dispatched;
    }

    /**
     * {@code IN_TRANSIT → COMPLETED}: the trip is over and the vehicle is released.
     *
     * <p>Every stop must have been resolved first (migration V27): completed, skipped or failed. A
     * trip closed over three stops nobody ever touched is a day that <em>looks</em> finished, and
     * the whole reason per-stop execution exists is to stop that being recordable. There is no
     * override flag - a stop that genuinely should not be counted is skipped, which takes a typed
     * reason and is the honest way to say so.
     *
     * <p><b>Closes out the orders it carried</b> (migration V36): each one moves to
     * {@code DELIVERED}, {@code PARTIALLY_DELIVERED} or {@code DELIVERY_FAILED} according to what
     * {@code tms.order_delivery} says about it at this moment. Planning still does not decide what
     * a fact means for an order - it reports the fulfilment through {@code OrderPlanningPort} and
     * the orders module maps it, the same direction {@code allocate} runs in. An order with
     * nothing recorded closes as failed, which is both the honest reading and the reopenable one.
     * See {@link OrderExecutionPropagator}.
     */
    @Transactional
    public TripDetailView complete(CompanyScope scope, UUID tripId, TripExecutionRequest request) {
        TripDetailView completed = transition(scope, tripId, TripStatus.COMPLETED, request,
                ShipmentEventType.SHIPMENT_COMPLETED,
                (trip, occurredAt) -> {
                    requireEveryStopResolved(trip);
                    requireNotBefore(occurredAt, trip.actualDepartureAt(), "before the vehicle departed");
                    trip.complete(occurredAt, auditActorProvider.requireAppUserId());
                });
        tripRepository.findByIdAndCompanyId(tripId, scope.companyId())
                .ifPresent(trip -> orderExecution.closedOut(scope, trip));
        return completed;
    }

    /**
     * The caller-facing half of the "a finished trip has no outstanding stop" rule, naming the
     * stops so a dispatcher knows what to go and do. {@code Trip.complete} asserts the same rule
     * again as a last line of defense.
     */
    private static void requireEveryStopResolved(Trip trip) {
        List<TripStop> outstanding = trip.unresolvedStops();
        if (outstanding.isEmpty()) {
            return;
        }
        String sequences = outstanding.stream()
                .map(stop -> String.valueOf(stop.sequence()))
                .collect(Collectors.joining(", "));
        throw new ConflictException("Trip " + trip.tripNumber() + " cannot be completed: stop"
                + (outstanding.size() == 1 ? " " : "s ") + sequences
                + (outstanding.size() == 1 ? " has" : " have") + " not been resolved yet.");
    }

    /**
     * The shape every execution action shares: lock, answer a retry, check the version, check the
     * move, apply it, publish it.
     *
     * @param apply the action's own rules and its call to the entity - run after the transition
     *     has been found legal and before anything is published
     */
    private TripDetailView transition(CompanyScope scope, UUID tripId, TripStatus target,
            TripExecutionRequest request, ShipmentEventType eventType, BiConsumer<Trip, OffsetDateTime> apply) {
        Trip trip = tripRepository.findByIdAndCompanyIdForUpdate(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));

        // Before the version check - see the class comment on idempotency.
        if (trip.status() == target) {
            return assembler.toDetail(trip, scope.companyId());
        }
        requireCurrentVersion(trip, request.version());
        requireLegalTransition(trip, target);

        OffsetDateTime occurredAt = resolveOccurredAt(request.occurredAt());
        apply.accept(trip, occurredAt);

        Trip saved = save(trip);
        events.publish(scope, saved, eventType, occurredAt,
                Map.of("tripNumber", saved.tripNumber(), "planningDate", saved.planningDate().toString()));
        announce(scope, saved, eventType, occurredAt);
        return assembler.toDetail(saved, scope.companyId());
    }

    /**
     * The fourth audience, and the only one that is not told about every transition: the alert bell
     * (migration V32).
     *
     * <p>Two of the three transitions produce one. {@code READY_FOR_DISPATCH} produces none on
     * purpose - a shipment declared ready is the plan going right, and an alert per shipment per
     * ordinary step is how a bell gets ignored. Whether a departure is late enough to be worth
     * saying is {@code TripAlertPublisher}'s question, not this one's, so that the bell and the
     * control tower judge lateness with the same rule.
     *
     * <p>Raised here rather than inside {@link ShipmentEventPublisher} with the other three
     * audiences, deliberately: that class publishes one fact to everybody who must hear it, and an
     * alert is not that. Two of the seven alert types are keyed on a tender and on a delivery, rows
     * the publisher does not hold, so putting some there and some here would be worse than putting
     * each at the call site that has the typed fact.
     */
    private void announce(CompanyScope scope, Trip trip, ShipmentEventType eventType, OffsetDateTime occurredAt) {
        switch (eventType) {
            case SHIPMENT_DISPATCHED -> alerts.departed(scope, trip, occurredAt);
            case SHIPMENT_COMPLETED -> alerts.completed(scope, trip, occurredAt);
            default -> {
                // Every other transition this service can publish is the plan going right.
            }
        }
    }

    /**
     * The caller-facing half of the transition table. {@link Trip} asserts the same rule again and
     * throws {@link IllegalStateException} if it is ever reached without this check - that one is a
     * defect report, this one is the sentence a dispatcher reads.
     */
    private static void requireLegalTransition(Trip trip, TripStatus target) {
        if (!trip.status().canTransitionTo(target)) {
            throw new ConflictException("Trip " + trip.tripNumber() + " is " + trip.status()
                    + " and cannot move to " + target + ".");
        }
    }

    /**
     * The vehicle must still be one this company may send out. Not applied to
     * {@link #complete}: a trip that is already on the road has to be closeable even if the truck
     * broke down and was marked out of service while it was gone - refusing to complete it would
     * leave a shipment permanently {@code IN_TRANSIT}, which is worse than the fleet edit it is
     * reacting to.
     */
    /**
     * A shipment accepted by one carrier may not depart on another carrier's vehicle (V42, debt D2).
     *
     * <p>The sentence a dispatcher reads. {@code Trip.dispatch} refuses again in the aggregate and
     * {@code ck_trip_departed_carrier_matches_vehicle} refuses in the database - the three-layer
     * shape every invariant in this codebase has. Only the first of the three says what to do
     * about it.
     */
    private void requireCarrierOwnsTheVehicle(Trip trip) {
        if (trip.awaitsCarrierVehicle()) {
            throw new ConflictException("Trip " + trip.tripNumber() + " was accepted by a carrier that does not"
                    + " own the vehicle assigned to it. Assign one of that carrier's vehicles before dispatching.");
        }
    }

    /**
     * Neither the vehicle nor the driver may be blocked at the moment of departure (V42).
     *
     * <p>Checked at the gate rather than only when the shipment was planned, for the reason the
     * licence check is: a truck booked into the workshop this morning was assignable last night,
     * and the record of why it did not run is worth more than the shipment that pretended it did.
     */
    private void requireResourcesAvailable(CompanyScope scope, Trip trip, OffsetDateTime at) {
        resourceAvailabilityPort.findBlock(scope.companyId(), trip.vehicleId(), trip.driverId(), at)
                .ifPresent(block -> {
                    throw new ConflictException("Trip " + trip.tripNumber() + " cannot depart: its "
                            + block.resource() + " is unavailable (" + block.reason() + ") until "
                            + block.endsAt() + ".");
                });
    }

    private void requireOperableVehicle(CompanyScope scope, Trip trip) {
        UUID vehicleId = trip.vehicleId();
        if (vehicleId == null) {
            // Unreachable through the API: ck_trip_confirmed_is_complete (V25) makes a committed
            // trip without a vehicle impossible. Checked anyway so the failure, if a raw data fix
            // ever produced one, is this sentence and not a NullPointerException.
            throw new ConflictException("Trip " + trip.tripNumber() + " has no vehicle assigned.");
        }
        vehicleLookupPort.findAssignable(vehicleId, scope.companyId())
                .orElseThrow(() -> new ConflictException("Trip " + trip.tripNumber()
                        + " is assigned a vehicle that is no longer active and available."));
    }

    /**
     * If a driver is named, they must still be someone this company may send out - active, with a
     * licence that has not run out. Re-checked here and not only when they were assigned, for the
     * same reason the vehicle is: the assignment may have been days ago, and a licence that lapsed
     * in between must stop the truck at the gate rather than on the road.
     *
     * <p>Judged against <em>today</em> in the company's own zone, not against the trip's planning
     * date. A shipment being dispatched a day late is dispatched with today's licences, and the
     * planning date is what the assignment rule uses precisely because at that point the day in
     * question is still in the future ({@code TripService.requireValidLicenceOn}).
     *
     * <p>A trip with no driver passes: naming one is not required by any state (migration V26), so
     * this must not become a back-door mandate. Not applied to {@link #complete}, exactly as the
     * vehicle check is not - a trip already on the road has to be closeable whatever has since
     * happened to the fleet or the personnel file.
     */
    private void requireOperableDriver(CompanyScope scope, Trip trip) {
        UUID driverId = trip.driverId();
        if (driverId == null) {
            return;
        }
        DriverReference driver = driverLookupPort.findAssignable(driverId, scope.companyId())
                .orElseThrow(() -> new ConflictException("Trip " + trip.tripNumber()
                        + " is assigned a driver who is no longer active."));
        if (driver.licenseStatusOn(scope.today()) == DriverLicenseStatus.EXPIRED) {
            throw new ConflictException("Driver " + driver.code() + " has a licence that expired on "
                    + driver.licenseExpiresOn() + " and cannot run trip " + trip.tripNumber() + ".");
        }
    }

    /**
     * Defaults an omitted time to now and refuses one in the future. A shipment cannot have
     * departed at a time that has not happened yet, and accepting one would put a report's
     * "average delay" permanently out of reach of correction.
     */
    private static OffsetDateTime resolveOccurredAt(OffsetDateTime requested) {
        OffsetDateTime now = OffsetDateTime.now();
        if (requested == null) {
            return now;
        }
        if (requested.isAfter(now.plus(CLOCK_SKEW_TOLERANCE))) {
            throw new InvalidRequestException("occurredAt cannot be in the future.");
        }
        return requested;
    }

    /**
     * Time only moves forward through the lifecycle. {@code earlier} is null on any trip whose
     * previous step recorded no time, in which case there is nothing to compare against and
     * nothing to refuse.
     */
    private static void requireNotBefore(OffsetDateTime occurredAt, OffsetDateTime earlier, String what) {
        if (earlier != null && occurredAt.isBefore(earlier)) {
            throw new InvalidRequestException("occurredAt cannot be " + what + " (" + earlier + ").");
        }
    }

    private static void requireCurrentVersion(Trip trip, Long requested) {
        if (requested == null) {
            throw new InvalidRequestException("version is required to modify a trip.");
        }
        if (requested != trip.version()) {
            throw new ConflictException(
                    "This trip was changed by someone else since it was loaded. Reload and try again.");
        }
    }

    private Trip save(Trip trip) {
        try {
            return tripRepository.saveAndFlush(trip);
        } catch (ObjectOptimisticLockingFailureException raced) {
            throw new ConflictException(
                    "This trip was changed by someone else since it was loaded. Reload and try again.");
        }
    }
}
