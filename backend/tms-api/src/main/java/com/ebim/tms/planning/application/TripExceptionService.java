package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TransportEvent;
import com.ebim.tms.planning.domain.TransportEventType;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripException;
import com.ebim.tms.planning.domain.TripExceptionType;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.infrastructure.TransportEventRepository;
import com.ebim.tms.planning.infrastructure.TripExceptionRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operational problems on a trip: reporting one, resolving it, and reading the timeline they land
 * in (migration V27).
 *
 * <p>Reporting is a first-class action and not a by-product of failing a delivery. A traffic jam or
 * a breakdown is a problem that has not (yet) changed any stop's outcome, and a dispatcher forced
 * to fail a stop in order to record one would be putting a lie in the delivery history. The
 * reverse path exists too and lives in {@code TripStopExecutionService}: a skipped or failed stop
 * opens its exception automatically, because a delivery that did not happen must have a reason on
 * file.
 *
 * <p><b>Reachable from any state after confirmation, including COMPLETED and CANCELLED.</b> The
 * problems this table records are often written up after the day ends - that is when somebody has
 * time - and a log that refused the write at 18:00 would simply not have the record. What it will
 * not accept is a problem on a trip that never became real: a DRAFT trip has no operations to have
 * had problems with.
 */
@Service
public class TripExceptionService {

    /** The tolerance the rest of the execution module uses, and for the same reason: browser clocks. */
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(5);

    private final TripRepository tripRepository;
    private final TripExceptionRepository exceptionRepository;
    private final TransportEventRepository transportEventRepository;
    private final TransportEventRecorder transportEvents;
    private final TripAlertPublisher alerts;
    private final TripViewAssembler assembler;
    private final AuditActorProvider auditActorProvider;

    public TripExceptionService(TripRepository tripRepository, TripExceptionRepository exceptionRepository,
            TransportEventRepository transportEventRepository, TransportEventRecorder transportEvents,
            TripAlertPublisher alerts, TripViewAssembler assembler, AuditActorProvider auditActorProvider) {
        this.tripRepository = tripRepository;
        this.exceptionRepository = exceptionRepository;
        this.transportEventRepository = transportEventRepository;
        this.transportEvents = transportEvents;
        this.alerts = alerts;
        this.assembler = assembler;
        this.auditActorProvider = auditActorProvider;
    }

    /** One trip's timeline, oldest first - the order a day is read in. */
    @Transactional(readOnly = true)
    public List<TransportEventView> events(CompanyScope scope, UUID tripId) {
        Trip trip = find(scope, tripId);
        List<TransportEvent> events = transportEventRepository
                .findByCompanyIdAndTripIdOrderByEventTimeAscRecordedAtAsc(scope.companyId(), tripId);
        return assembler.toEventViews(trip, events, scope.companyId());
    }

    /**
     * Reports a problem against a trip, or against one of its stops.
     *
     * <p>Takes the trip's row lock like every other write in this module, even though nothing here
     * changes the trip: the lock is what serialises the exception against a concurrent stop
     * transition that would open one of its own, so the two cannot interleave into a stop that is
     * FAILED with no reason or a reason with no stop.
     */
    @Transactional
    public TripDetailView report(CompanyScope scope, UUID tripId, TripExceptionRequest request) {
        Trip trip = tripRepository.findByIdAndCompanyIdForUpdate(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
        requireOperated(trip);

        TripExceptionType type = request.exceptionType();
        TripStop stop = resolveStop(trip, request.tripStopId());
        if (type.requiresStop() && stop == null) {
            throw new InvalidRequestException("tripStopId is required when the problem is " + type + ".");
        }
        String notes = blankToNull(request.notes());
        if (type.requiresNotes() && notes == null) {
            throw new InvalidRequestException("notes are required when the problem is " + type + ".");
        }

        OffsetDateTime occurredAt = resolveOccurredAt(request.occurredAt());
        UUID actorId = auditActorProvider.requireAppUserId();
        TripException reported = exceptionRepository.saveAndFlush(new TripException(scope.companyId(), trip.id(),
                stop == null ? null : stop.id(), type, occurredAt, actorId, notes));

        transportEvents.record(scope, trip.id(), stop == null ? null : stop.id(),
                TransportEventType.EXCEPTION_REPORTED, occurredAt, notes,
                detailOf(reported, stop));
        // ...and the bell (V32). Keyed on the exception, so a day with three problems is three
        // alerts - see TripAlertPublisher.
        alerts.exceptionOpened(scope, trip, reported, stop);
        return assembler.toDetail(trip, scope.companyId());
    }

    /**
     * Closes a problem out.
     *
     * <p>A retry is answered with the resolved trip rather than with an error, the same rule every
     * transition in this module follows: the intent was reached, and reaching it twice is not a
     * different outcome.
     */
    @Transactional
    public TripDetailView resolve(CompanyScope scope, UUID tripId, UUID exceptionId,
            TripExceptionResolutionRequest request) {
        Trip trip = tripRepository.findByIdAndCompanyIdForUpdate(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
        TripException exception = exceptionRepository.findByIdAndCompanyId(exceptionId, scope.companyId())
                // Checked against the trip in the path too, not only against the company: an
                // exception id from another of this company's trips must not be resolvable through
                // this one's URL, or the audit trail would name the wrong shipment.
                .filter(candidate -> candidate.tripId().equals(trip.id()))
                .orElseThrow(() -> new ResourceNotFoundException("Exception not found on this trip."));

        if (!exception.isOpen()) {
            return assembler.toDetail(trip, scope.companyId());
        }

        OffsetDateTime occurredAt = resolveOccurredAt(request.occurredAt());
        if (occurredAt.isBefore(exception.reportedAt())) {
            throw new InvalidRequestException(
                    "occurredAt cannot be before the problem was reported (" + exception.reportedAt() + ").");
        }

        exception.resolve(occurredAt, auditActorProvider.requireAppUserId(), request.notes().trim());
        exceptionRepository.saveAndFlush(exception);

        TripStop stop = resolveStop(trip, exception.tripStopId());
        transportEvents.record(scope, trip.id(), exception.tripStopId(), TransportEventType.EXCEPTION_RESOLVED,
                occurredAt, request.notes(), detailOf(exception, stop));
        // The alert stays on the board and stops being outstanding. The early return above means a
        // retry never gets here, which is what keeps the first resolution time the recorded one.
        alerts.exceptionResolved(scope, exception, occurredAt);
        return assembler.toDetail(trip, scope.companyId());
    }

    /**
     * A problem belongs to a trip that has become real. DRAFT is the only state refused: it has no
     * operations to have had problems with, and every state after confirmation - cancelled and
     * completed included - can legitimately be written up after the fact.
     */
    private static void requireOperated(Trip trip) {
        if (trip.isDraft()) {
            throw new InvalidRequestException(
                    "Trip " + trip.tripNumber() + " is still a draft and has no operations to report on.");
        }
    }

    private static TripStop resolveStop(Trip trip, UUID stopId) {
        if (stopId == null) {
            return null;
        }
        return trip.stops().stream()
                .filter(candidate -> stopId.equals(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Stop not found on this trip."));
    }

    private static Map<String, Object> detailOf(TripException exception, TripStop stop) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("exceptionId", exception.id().toString());
        detail.put("exceptionType", exception.exceptionType().name());
        if (stop != null) {
            detail.put("stopSequence", stop.sequence());
        }
        return detail;
    }

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

    private Trip find(CompanyScope scope, UUID tripId) {
        return tripRepository.findByIdAndCompanyId(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
