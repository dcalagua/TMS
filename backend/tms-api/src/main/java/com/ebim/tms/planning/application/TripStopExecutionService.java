package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.StopExecutionStatus;
import com.ebim.tms.planning.domain.TransportEventType;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripException;
import com.ebim.tms.planning.domain.TripExceptionType;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.infrastructure.TripExceptionRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What happens at each stop of a trip that is on the road: arrived, service started, completed -
 * or skipped, or failed (migration V27).
 *
 * <p><b>Why it is not part of {@code TripExecutionService}.</b> That one moves a shipment through
 * five states a handful of times a day; this one is worked stop by stop, has its own transition
 * table, and produces an exception where the other produces an outbox event. They share a
 * permission and a row lock, not a set of rules.
 *
 * <p><b>The trip must be IN_TRANSIT.</b> Stops are worked while the vehicle is out and at no other
 * time: an arrival recorded against a shipment that has not been dispatched is not an early
 * arrival, it is a mistake, and letting it through would make {@code actualDepartureAt} stop
 * meaning what it says. Checked here with a sentence a dispatcher can read, and asserted again by
 * {@code Trip.recordStopOutcome}.
 *
 * <p><b>Idempotency and concurrency</b> follow {@code TripExecutionService} exactly: a stop already
 * in the outcome an action would produce is returned unchanged - a retry of something that
 * succeeded is not an error - and every action takes the trip's row lock first, so two dispatchers
 * cannot both record the same arrival.
 */
@Service
public class TripStopExecutionService {

    /** The tolerance {@code TripExecutionService} uses, and for the same reason: browser clocks. */
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(5);

    private final TripRepository tripRepository;
    private final TripExceptionRepository exceptionRepository;
    private final TransportEventRecorder transportEvents;
    private final TripAlertPublisher alerts;
    private final TripViewAssembler assembler;
    private final AuditActorProvider auditActorProvider;

    public TripStopExecutionService(TripRepository tripRepository, TripExceptionRepository exceptionRepository,
            TransportEventRecorder transportEvents, TripAlertPublisher alerts, TripViewAssembler assembler,
            AuditActorProvider auditActorProvider) {
        this.tripRepository = tripRepository;
        this.exceptionRepository = exceptionRepository;
        this.transportEvents = transportEvents;
        this.alerts = alerts;
        this.assembler = assembler;
        this.auditActorProvider = auditActorProvider;
    }

    /** The vehicle is at the destination. */
    @Transactional
    public TripDetailView arrive(CompanyScope scope, UUID tripId, UUID stopId, TripStopExecutionRequest request) {
        return transition(scope, tripId, stopId, StopExecutionStatus.ARRIVED, request.occurredAt(), request.notes(),
                null);
    }

    /**
     * Loading or unloading has started. Optional in the lifecycle - a company that does not record
     * service starts goes straight from arrival to completion - which is why nothing forces this
     * call and why {@code ARRIVED -> COMPLETED} is a legal move.
     */
    @Transactional
    public TripDetailView startService(CompanyScope scope, UUID tripId, UUID stopId,
            TripStopExecutionRequest request) {
        return transition(scope, tripId, stopId, StopExecutionStatus.IN_SERVICE, request.occurredAt(),
                request.notes(), null);
    }

    /**
     * Served and left. The stop's orders stay {@code PLANNED}: there is no delivered state for them
     * to move to, and inventing one from the planning module would put an order rule in the wrong
     * place - the same line migrations V25 and V27 both draw.
     */
    @Transactional
    public TripDetailView complete(CompanyScope scope, UUID tripId, UUID stopId, TripStopExecutionRequest request) {
        return transition(scope, tripId, stopId, StopExecutionStatus.COMPLETED, request.occurredAt(),
                request.notes(), null);
    }

    /** Never attempted, by decision. Carries no times at all - see {@code StopExecutionStatus}. */
    @Transactional
    public TripDetailView skip(CompanyScope scope, UUID tripId, UUID stopId, TripStopFailureRequest request) {
        return transition(scope, tripId, stopId, StopExecutionStatus.SKIPPED, request.occurredAt(), request.notes(),
                request.exceptionType());
    }

    /** Attempted and not served. */
    @Transactional
    public TripDetailView fail(CompanyScope scope, UUID tripId, UUID stopId, TripStopFailureRequest request) {
        return transition(scope, tripId, stopId, StopExecutionStatus.FAILED, request.occurredAt(), request.notes(),
                request.exceptionType());
    }

    /**
     * The shape every stop action shares: lock the trip, find the stop, answer a retry, check the
     * trip is out, check the move, check the time, apply, open an exception if the outcome needs
     * one, and log it.
     *
     * @param exceptionType non-null exactly for the outcomes that require a reason on file
     */
    private TripDetailView transition(CompanyScope scope, UUID tripId, UUID stopId, StopExecutionStatus target,
            OffsetDateTime requestedAt, String notes, TripExceptionType exceptionType) {
        Trip trip = tripRepository.findByIdAndCompanyIdForUpdate(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
        TripStop stop = trip.stops().stream()
                .filter(candidate -> stopId.equals(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Stop not found on this trip."));

        // Before every other check, exactly as TripExecutionService answers a repeated transition:
        // a dispatcher whose request timed out and who pressed the button again reached the state
        // they meant to reach, and telling them otherwise would be the lie, not the acceptance.
        if (stop.executionStatus() == target) {
            return assembler.toDetail(trip, scope.companyId());
        }
        requireInTransit(trip);
        requireLegalTransition(trip, stop, target);
        requireReason(target, exceptionType, notes);

        OffsetDateTime occurredAt = resolveOccurredAt(requestedAt);
        requireNotBefore(occurredAt, earliestAllowedFor(trip, stop, target));

        UUID actorId = auditActorProvider.requireAppUserId();
        trip.recordStopOutcome(stopId, target, occurredAt, notes, actorId);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("stopSequence", stop.sequence());
        detail.put("executionStatus", target.name());
        TripException opened = null;
        if (exceptionType != null) {
            // The reason is a typed row of its own, and the timeline entry names it so the log
            // reads on its own without a join: one user action produces one event, not two.
            opened = exceptionRepository.saveAndFlush(new TripException(scope.companyId(), trip.id(), stop.id(),
                    exceptionType, occurredAt, actorId, blankToNull(notes)));
            detail.put("exceptionType", exceptionType.name());
        }

        Trip saved = tripRepository.saveAndFlush(trip);
        transportEvents.record(scope, saved.id(), stop.id(), TransportEventType.forStopOutcome(target),
                occurredAt, notes, detail);
        if (opened != null) {
            // The same alert TripExceptionService raises when a dispatcher reports a problem by
            // hand (V32). Raised here too rather than only there, because a stop that was skipped
            // or failed is exactly the case nobody will go on to write up separately - and the two
            // paths must not produce two different-looking entries for one kind of fact.
            alerts.exceptionOpened(scope, saved, opened, stop);
        }
        return assembler.toDetail(saved, scope.companyId());
    }

    private static void requireInTransit(Trip trip) {
        if (trip.status() != TripStatus.IN_TRANSIT) {
            throw new ConflictException("Trip " + trip.tripNumber() + " is " + trip.status()
                    + " and its stops cannot be worked until it is on the road.");
        }
    }

    /**
     * The caller-facing half of the stop transition table. {@code TripStop.recordOutcome} asserts
     * the same rule again and throws {@link IllegalStateException} if it is ever reached without
     * this check - that one is a defect report, this one is the sentence a dispatcher reads.
     */
    private static void requireLegalTransition(Trip trip, TripStop stop, StopExecutionStatus target) {
        if (!stop.executionStatus().canTransitionTo(target)) {
            throw new ConflictException("Stop " + stop.sequence() + " of trip " + trip.tripNumber() + " is "
                    + stop.executionStatus() + " and cannot move to " + target + ".");
        }
    }

    /**
     * A delivery that did not happen has a typed reason on file, and {@code OTHER} has a sentence
     * with it. The first half is structural - the endpoint that reaches these outcomes takes a
     * mandatory type - and is re-checked here so a future caller cannot route around it.
     */
    private static void requireReason(StopExecutionStatus target, TripExceptionType exceptionType, String notes) {
        if (target.requiresException() && exceptionType == null) {
            throw new InvalidRequestException("exceptionType is required to record a stop as " + target + ".");
        }
        if (exceptionType != null && exceptionType.requiresNotes() && blankToNull(notes) == null) {
            throw new InvalidRequestException("notes are required when the reason is " + exceptionType + ".");
        }
    }

    /**
     * The earliest time this outcome could have happened, read <em>before</em> the stop is mutated.
     *
     * <p>Each step is bounded by the one before it and, at the top, by the trip's own departure: a
     * vehicle cannot arrive anywhere before it left. Null when there is nothing to compare
     * against, which is the honest answer for a stop whose earlier steps were never recorded.
     */
    private static OffsetDateTime earliestAllowedFor(Trip trip, TripStop stop, StopExecutionStatus target) {
        return switch (target) {
            case ARRIVED -> trip.actualDepartureAt();
            case IN_SERVICE -> firstNonNull(stop.actualArrivalAt(), trip.actualDepartureAt());
            case COMPLETED -> firstNonNull(stop.serviceStartedAt(), stop.actualArrivalAt(),
                    trip.actualDepartureAt());
            // Neither writes a timestamp, so neither has an ordering to respect: the time they
            // carry is the moment the decision was taken, which may well precede the arrival that
            // never happened.
            case SKIPPED, FAILED -> null;
            case PENDING -> null;
        };
    }

    private static OffsetDateTime firstNonNull(OffsetDateTime... candidates) {
        for (OffsetDateTime candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /** Defaults an omitted time to now and refuses one in the future - {@code TripExecutionService}'s rule. */
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

    private static void requireNotBefore(OffsetDateTime occurredAt, OffsetDateTime earliest) {
        if (earliest != null && occurredAt.isBefore(earliest)) {
            throw new InvalidRequestException("occurredAt cannot be before " + earliest + ".");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
