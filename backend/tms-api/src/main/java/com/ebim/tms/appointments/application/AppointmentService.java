package com.ebim.tms.appointments.application;

import com.ebim.tms.appointments.domain.Appointment;
import com.ebim.tms.appointments.domain.AppointmentStatus;
import com.ebim.tms.appointments.domain.LocationResource;
import com.ebim.tms.appointments.domain.ResourceBlockedSlot;
import com.ebim.tms.appointments.domain.ResourceCalendarEntry;
import com.ebim.tms.appointments.infrastructure.AppointmentRepository;
import com.ebim.tms.appointments.infrastructure.LocationResourceRepository;
import com.ebim.tms.appointments.infrastructure.ResourceBlockedSlotRepository;
import com.ebim.tms.appointments.infrastructure.ResourceCalendarRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.AppointmentTripPort;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.LocationTimeZonePort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.security.CompanyScope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking a vehicle into a door (migration V41).
 *
 * <h2>Where the no-double-booking rule actually lives</h2>
 *
 * <p>In the database, and only there. {@link #requireFree} exists to produce a <em>readable</em>
 * refusal naming the booking that is in the way, and it is not the guarantee: two dispatchers
 * booking 09:00 on the same door in the same instant both see a free door in their own snapshot and
 * both pass it. {@code ex_appointment_no_double_booking} is the one place they cannot both get
 * past, and the {@code DataIntegrityViolationException} branch below is where the loser is told so
 * in the language of the dock board rather than as a 500.
 *
 * <p>That is the same two-layer shape {@code TripAssignmentService} uses for
 * {@code uq_trip_order_assignment_open_whole_order}, and for the same reason: a check that only
 * exists in application code is the booking sheet this feature replaces.
 *
 * <h2>Time zones</h2>
 *
 * <p>Windows are absolute instants. The <b>location's</b> zone is used for exactly two things:
 * reading the door's local opening hours, and telling a user what time their booking is. Neither
 * uses the server's zone, because a dock in Arequipa opens at 07:00 in Arequipa whatever the server
 * thinks.
 */
@Service
public class AppointmentService {

    private static final String BOOKING_METRIC = "tms.appointments.bookings";

    private final AppointmentRepository appointmentRepository;
    private final LocationResourceRepository resourceRepository;
    private final ResourceCalendarRepository calendarRepository;
    private final ResourceBlockedSlotRepository blockedSlotRepository;
    private final DestinationLookupPort destinationLookupPort;
    private final AppointmentTripPort tripPort;
    private final LocationTimeZonePort locationTimeZonePort;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public AppointmentService(AppointmentRepository appointmentRepository,
            LocationResourceRepository resourceRepository, ResourceCalendarRepository calendarRepository,
            ResourceBlockedSlotRepository blockedSlotRepository, DestinationLookupPort destinationLookupPort,
            AppointmentTripPort tripPort, LocationTimeZonePort locationTimeZonePort,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder,
            MeterRegistry meterRegistry, Clock clock) {
        this.appointmentRepository = appointmentRepository;
        this.resourceRepository = resourceRepository;
        this.calendarRepository = calendarRepository;
        this.blockedSlotRepository = blockedSlotRepository;
        this.destinationLookupPort = destinationLookupPort;
        this.tripPort = tripPort;
        this.locationTimeZonePort = locationTimeZonePort;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    // --- booking ----------------------------------------------------------------------

    @Transactional
    public AppointmentView book(CompanyScope scope, AppointmentRequest request) {
        LocationResource resource = requireActiveResource(scope, request.resourceId());
        OffsetDateTime start = request.windowStart();
        OffsetDateTime end = request.windowEnd() != null
                ? request.windowEnd()
                : start.plusMinutes(resource.defaultSlotMinutes());
        requireWindowSane(start, end);
        requireStopBelongsToTrip(scope, request.tripId(), request.tripStopId());
        requireWithinOpeningHours(scope, resource, start, end);
        requireNotBlocked(scope, resource, start, end);
        requireFree(scope, resource, start, end, null);

        UUID actorId = auditActorProvider.requireAppUserId();
        Appointment appointment = new Appointment(scope.companyId(), resource.id(), resource.locationId(),
                request.tripId(), request.tripStopId(), request.purpose(), start, end,
                blankToNull(request.reference()), blankToNull(request.notes()), actorId);

        Appointment saved = saveWithOverlapBackstop(appointment, resource);
        auditRecorder.record(scope, AuditAggregateType.APPOINTMENT, saved.id(), AuditAction.APPOINTMENT_BOOKED,
                detail(resource, saved));
        count("booked");
        return view(scope, saved, resource);
    }

    /**
     * Moves a booking's window, keeping the same commitment.
     *
     * <p>The appointment itself is excluded from the conflict check: a booking always overlaps
     * itself, and without the exclusion moving a slot by ten minutes would be refused by the
     * booking it <em>is</em>.
     */
    @Transactional
    public AppointmentView reschedule(CompanyScope scope, UUID appointmentId, OffsetDateTime newStart,
            OffsetDateTime newEnd) {
        Appointment appointment = require(scope, appointmentId);
        LocationResource resource = requireResource(scope, appointment.resourceId());
        OffsetDateTime end = newEnd != null ? newEnd : newStart.plusMinutes(resource.defaultSlotMinutes());
        requireWindowSane(newStart, end);
        if (!appointment.status().isReschedulable()) {
            throw new ConflictException("An appointment that is " + appointment.status()
                    + " can no longer be moved.");
        }
        requireWithinOpeningHours(scope, resource, newStart, end);
        requireNotBlocked(scope, resource, newStart, end);
        requireFree(scope, resource, newStart, end, appointment.id());

        UUID actorId = auditActorProvider.requireAppUserId();
        OffsetDateTime previousStart = appointment.windowStart();
        appointment.reschedule(newStart, end, actorId);
        Appointment saved = saveWithOverlapBackstop(appointment, resource);

        Map<String, Object> detail = detail(resource, saved);
        detail.put("movedFrom", previousStart.toString());
        auditRecorder.record(scope, AuditAggregateType.APPOINTMENT, saved.id(),
                AuditAction.APPOINTMENT_RESCHEDULED, detail);
        count("rescheduled");
        return view(scope, saved, resource);
    }

    // --- the rest of the lifecycle -----------------------------------------------------

    @Transactional
    public AppointmentView confirm(CompanyScope scope, UUID appointmentId) {
        return transition(scope, appointmentId, AppointmentStatus.CONFIRMED, (appointment, actorId, now) ->
                appointment.confirm(actorId), null, "confirmed");
    }

    @Transactional
    public AppointmentView arrive(CompanyScope scope, UUID appointmentId, OffsetDateTime at) {
        OffsetDateTime moment = at != null ? at : OffsetDateTime.now(clock);
        return transition(scope, appointmentId, AppointmentStatus.ARRIVED, (appointment, actorId, now) ->
                appointment.arrive(moment, actorId), null, "arrived");
    }

    @Transactional
    public AppointmentView complete(CompanyScope scope, UUID appointmentId, OffsetDateTime at) {
        OffsetDateTime moment = at != null ? at : OffsetDateTime.now(clock);
        return transition(scope, appointmentId, AppointmentStatus.COMPLETED, (appointment, actorId, now) ->
                appointment.complete(moment, actorId), null, "completed");
    }

    @Transactional
    public AppointmentView cancel(CompanyScope scope, UUID appointmentId, String reason) {
        return transition(scope, appointmentId, AppointmentStatus.CANCELLED,
                (appointment, actorId, now) -> appointment.cancel(now, blankToNull(reason), actorId),
                AuditAction.APPOINTMENT_CANCELLED, "cancelled");
    }

    /**
     * Nobody came.
     *
     * <p>The slot is released and the record stays: a no-show is what a demurrage or missed-slot
     * conversation is argued from, and deleting it would destroy the site's only evidence.
     */
    @Transactional
    public AppointmentView markNoShow(CompanyScope scope, UUID appointmentId) {
        return transition(scope, appointmentId, AppointmentStatus.NO_SHOW,
                (appointment, actorId, now) -> appointment.markNoShow(actorId),
                AuditAction.APPOINTMENT_NO_SHOW, "no-show");
    }

    /** One transition, with its refusal, its audit row and its metric. */
    private AppointmentView transition(CompanyScope scope, UUID appointmentId, AppointmentStatus target,
            TransitionAction action, AuditAction auditAction, String outcome) {
        Appointment appointment = require(scope, appointmentId);
        // Before anything else: reaching the state you asked for is not an error. Same rule
        // TripExecutionService applies to a retried dispatch.
        if (appointment.status() == target) {
            return view(scope, appointment, requireResource(scope, appointment.resourceId()));
        }
        if (!appointment.status().canTransitionTo(target)) {
            throw new ConflictException("An appointment that is " + appointment.status()
                    + " cannot become " + target + ".");
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        action.apply(appointment, actorId, OffsetDateTime.now(clock));
        Appointment saved = appointmentRepository.saveAndFlush(appointment);
        LocationResource resource = requireResource(scope, saved.resourceId());
        if (auditAction != null) {
            auditRecorder.record(scope, AuditAggregateType.APPOINTMENT, saved.id(), auditAction,
                    detail(resource, saved));
        }
        count(outcome);
        return view(scope, saved, resource);
    }

    @FunctionalInterface
    private interface TransitionAction {
        void apply(Appointment appointment, UUID actorId, OffsetDateTime now);
    }

    // --- reading ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AppointmentView get(CompanyScope scope, UUID appointmentId) {
        Appointment appointment = require(scope, appointmentId);
        return view(scope, appointment, requireResource(scope, appointment.resourceId()));
    }

    /** Every booking at a site between two instants, across every door - the dock board. */
    @Transactional(readOnly = true)
    public List<AppointmentView> forLocation(CompanyScope scope, UUID locationId, OffsetDateTime from,
            OffsetDateTime to) {
        List<Appointment> appointments =
                appointmentRepository.findForLocationBetween(scope.companyId(), locationId, from, to);
        return withResources(scope, appointments);
    }

    /** A shipment's bookings, in visiting order. */
    @Transactional(readOnly = true)
    public List<AppointmentView> forTrip(CompanyScope scope, UUID tripId) {
        return withResources(scope,
                appointmentRepository.findByCompanyIdAndTripIdOrderByWindowStartAsc(scope.companyId(), tripId));
    }

    private List<AppointmentView> withResources(CompanyScope scope, List<Appointment> appointments) {
        if (appointments.isEmpty()) {
            return List.of();
        }
        // One lookup for the whole page rather than one per row: a dock board is fifty bookings
        // across six doors, and the doors repeat.
        Map<UUID, LocationResource> resources = new LinkedHashMap<>();
        resourceRepository.findByCompanyIdAndIdIn(scope.companyId(),
                        appointments.stream().map(Appointment::resourceId).collect(java.util.stream.Collectors.toSet()))
                .forEach(resource -> resources.put(resource.id(), resource));
        Map<UUID, MasterReference> locations = destinationLookupPort.findAllInCompany(
                appointments.stream().map(Appointment::locationId).collect(java.util.stream.Collectors.toSet()),
                scope.companyId());

        return appointments.stream()
                .map(appointment -> AppointmentView.from(appointment, resources.get(appointment.resourceId()),
                        locations.get(appointment.locationId())))
                .toList();
    }

    // --- the rules --------------------------------------------------------------------

    /**
     * Refuses a window that already has a booking, naming the one in the way.
     *
     * <p><b>Not the guarantee.</b> See the class comment: this produces a sentence a dispatcher can
     * act on, and {@code ex_appointment_no_double_booking} is what actually makes two simultaneous
     * bookings impossible.
     */
    private void requireFree(CompanyScope scope, LocationResource resource, OffsetDateTime start,
            OffsetDateTime end, UUID excludeId) {
        List<Appointment> conflicts = appointmentRepository.findConflicting(
                scope.companyId(), resource.id(), start, end, excludeId);
        if (!conflicts.isEmpty()) {
            Appointment clash = conflicts.get(0);
            throw new ConflictException("Dock " + resource.code() + " is already booked from "
                    + clash.windowStart() + " to " + clash.windowEnd() + ".");
        }
    }

    /**
     * Refuses a window outside the door's opening hours.
     *
     * <p>Read in the <b>location's</b> zone, never the server's, and against the weekday the window
     * falls on there. A booking that crosses local midnight is refused rather than silently checked
     * against one of the two days: V41 allows no overnight window, so a run that spans two days is
     * two bookings, which is also what the site would write on its own board.
     *
     * <p>A door with no calendar at all is open: a company that has not configured opening hours has
     * not said the door is shut, and refusing every booking until somebody fills in a form would
     * make the feature unusable on day one.
     */
    private void requireWithinOpeningHours(CompanyScope scope, LocationResource resource,
            OffsetDateTime start, OffsetDateTime end) {
        List<ResourceCalendarEntry> calendar =
                calendarRepository.findByCompanyIdAndResourceIdOrderByDayOfWeekAsc(scope.companyId(), resource.id());
        if (calendar.isEmpty()) {
            return;
        }

        ZoneId zone = zoneOf(scope, resource);
        ZonedDateTime localStart = start.atZoneSameInstant(zone);
        ZonedDateTime localEnd = end.atZoneSameInstant(zone);
        LocalDate day = localStart.toLocalDate();
        if (!localEnd.toLocalDate().equals(day)) {
            throw new InvalidRequestException("An appointment cannot cross midnight at the site. "
                    + "Book two windows, one on each day.");
        }

        Optional<ResourceCalendarEntry> entry = calendar.stream()
                .filter(candidate -> candidate.day() == day.getDayOfWeek())
                .findFirst();
        if (entry.isEmpty()) {
            throw new ConflictException("Dock " + resource.code() + " is closed on "
                    + day.getDayOfWeek() + ".");
        }
        LocalTime from = localStart.toLocalTime();
        LocalTime to = localEnd.toLocalTime();
        if (!entry.get().covers(from, to)) {
            throw new ConflictException("Dock " + resource.code() + " is open from "
                    + entry.get().opensAt() + " to " + entry.get().closesAt() + " on "
                    + day.getDayOfWeek() + ", local time.");
        }
    }

    private void requireNotBlocked(CompanyScope scope, LocationResource resource, OffsetDateTime start,
            OffsetDateTime end) {
        List<ResourceBlockedSlot> blocked =
                blockedSlotRepository.findOverlapping(scope.companyId(), resource.id(), start, end);
        if (!blocked.isEmpty()) {
            throw new ConflictException("Dock " + resource.code() + " is closed then: "
                    + blocked.get(0).reason() + ".");
        }
    }

    /**
     * The stop, if named, must belong to the trip, if named - and both to this company.
     *
     * <p>Checked through a port rather than by reading planning's tables: appointments is its own
     * module, and a booking that could name another company's shipment would be a tenancy hole the
     * composite foreign keys already close at the database level. This is the readable half.
     */
    private void requireStopBelongsToTrip(CompanyScope scope, UUID tripId, UUID tripStopId) {
        if (tripStopId != null && tripId == null) {
            throw new InvalidRequestException("tripStopId needs its tripId: a stop without its trip "
                    + "points at half a shipment.");
        }
        if (tripId == null) {
            return;
        }
        if (!tripPort.tripExists(tripId, scope.companyId())) {
            throw new InvalidRequestException("tripId does not name a shipment of this company.");
        }
        if (tripStopId != null && !tripPort.stopBelongsToTrip(tripStopId, tripId, scope.companyId())) {
            throw new InvalidRequestException("tripStopId does not name a stop of that shipment.");
        }
    }

    private static void requireWindowSane(OffsetDateTime start, OffsetDateTime end) {
        if (!end.isAfter(start)) {
            throw new InvalidRequestException("windowEnd must be after windowStart.");
        }
        if (java.time.Duration.between(start, end).toHours() > 24) {
            throw new InvalidRequestException("An appointment cannot hold a dock for more than 24 hours.");
        }
    }

    // --- plumbing ---------------------------------------------------------------------

    /**
     * Saves, translating the exclusion constraint into the sentence a dispatcher needs.
     *
     * <p>This is the branch that catches the two-dispatchers race. Without it the loser gets a 500
     * about a constraint name, which is the moment a user stops believing the dock board.
     */
    private Appointment saveWithOverlapBackstop(Appointment appointment, LocationResource resource) {
        try {
            return appointmentRepository.saveAndFlush(appointment);
        } catch (DataIntegrityViolationException raced) {
            count("raced");
            throw new ConflictException("Dock " + resource.code() + " was booked for that time by "
                    + "somebody else a moment ago. Reload the dock board and pick another slot.");
        }
    }

    /**
     * The site's time zone, falling back to the company's.
     *
     * <p>{@code MasterReference} does not carry a zone, so a location whose zone cannot be resolved
     * uses the company's - which is the same default {@code tms.location}'s own generated column
     * uses (V23). Never the server's.
     */
    private ZoneId zoneOf(CompanyScope scope, LocationResource resource) {
        return locationTimeZonePort.findTimeZone(resource.locationId(), scope.companyId())
                .map(ZoneId::of)
                .orElseGet(() -> ZoneId.of(scope.timeZone()));
    }

    private Appointment require(CompanyScope scope, UUID appointmentId) {
        return appointmentRepository.findByIdAndCompanyId(appointmentId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found."));
    }

    private LocationResource requireResource(CompanyScope scope, UUID resourceId) {
        return resourceRepository.findByIdAndCompanyId(resourceId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Dock not found."));
    }

    private LocationResource requireActiveResource(CompanyScope scope, UUID resourceId) {
        LocationResource resource = requireResource(scope, resourceId);
        if (!resource.active()) {
            throw new ConflictException("Dock " + resource.code() + " is out of service.");
        }
        return resource;
    }

    private AppointmentView view(CompanyScope scope, Appointment appointment, LocationResource resource) {
        MasterReference location = destinationLookupPort
                .findAllInCompany(Set.of(appointment.locationId()), scope.companyId())
                .get(appointment.locationId());
        return AppointmentView.from(appointment, resource, location);
    }

    private static Map<String, Object> detail(LocationResource resource, Appointment appointment) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("dock", resource.code());
        detail.put("windowStart", appointment.windowStart().toString());
        detail.put("windowEnd", appointment.windowEnd().toString());
        detail.put("purpose", appointment.purpose().name());
        if (appointment.tripId() != null) {
            detail.put("tripId", appointment.tripId().toString());
        }
        return detail;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void count(String outcome) {
        Counter.builder(BOOKING_METRIC).tag("outcome", outcome).register(meterRegistry).increment();
    }
}
