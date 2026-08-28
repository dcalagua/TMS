package com.ebim.tms.appointments.application;

import com.ebim.tms.appointments.domain.LocationResource;
import com.ebim.tms.appointments.domain.ResourceBlockedSlot;
import com.ebim.tms.appointments.domain.ResourceCalendarEntry;
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
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The doors themselves, their opening hours and their closures (migration V41).
 *
 * <p>Ordinary master data, kept apart from {@link AppointmentService} because the two have different
 * audiences and different risks: this is configured once by whoever runs the site, and that is used
 * every hour by whoever runs the yard.
 */
@Service
public class LocationResourceService {

    private final LocationResourceRepository resourceRepository;
    private final ResourceCalendarRepository calendarRepository;
    private final ResourceBlockedSlotRepository blockedSlotRepository;
    private final DestinationLookupPort destinationLookupPort;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    public LocationResourceService(LocationResourceRepository resourceRepository,
            ResourceCalendarRepository calendarRepository, ResourceBlockedSlotRepository blockedSlotRepository,
            DestinationLookupPort destinationLookupPort, AuditActorProvider auditActorProvider,
            AuditRecorder auditRecorder, Clock clock) {
        this.resourceRepository = resourceRepository;
        this.calendarRepository = calendarRepository;
        this.blockedSlotRepository = blockedSlotRepository;
        this.destinationLookupPort = destinationLookupPort;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<LocationResourceView> listForLocation(CompanyScope scope, UUID locationId) {
        return resourceRepository
                .findByCompanyIdAndLocationIdOrderByCodeAsc(scope.companyId(), locationId).stream()
                .map(resource -> LocationResourceView.from(resource,
                        calendarRepository.findByCompanyIdAndResourceIdOrderByDayOfWeekAsc(
                                scope.companyId(), resource.id())))
                .toList();
    }

    @Transactional
    public LocationResourceView create(CompanyScope scope, LocationResourceRequest request) {
        requireLocation(scope, request.locationId());
        String code = request.code().trim().toUpperCase(java.util.Locale.ROOT);
        if (resourceRepository.existsByLocationIdAndCode(request.locationId(), code)) {
            throw new ConflictException("This site already has a dock called " + code + ".");
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        LocationResource resource = new LocationResource(scope.companyId(), request.locationId(), code,
                request.name().trim(), request.resourceType(), request.defaultSlotMinutes(), actorId);
        LocationResource saved = resourceRepository.saveAndFlush(resource);
        auditRecorder.record(scope, AuditAggregateType.LOCATION_RESOURCE, saved.id(), AuditAction.CREATE,
                Map.of("code", saved.code(), "type", saved.resourceType().name()));
        return LocationResourceView.from(saved, List.of());
    }

    @Transactional
    public LocationResourceView update(CompanyScope scope, UUID id, LocationResourceRequest request) {
        LocationResource resource = require(scope, id);
        String code = request.code().trim().toUpperCase(java.util.Locale.ROOT);
        if (resourceRepository.existsByLocationIdAndCodeAndIdNot(resource.locationId(), code, id)) {
            throw new ConflictException("This site already has a dock called " + code + ".");
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        resource.applyChanges(code, request.name().trim(), request.resourceType(),
                request.defaultSlotMinutes(), actorId);
        LocationResource saved = resourceRepository.saveAndFlush(resource);
        auditRecorder.record(scope, AuditAggregateType.LOCATION_RESOURCE, saved.id(), AuditAction.UPDATE,
                Map.of("code", saved.code()));
        return LocationResourceView.from(saved, calendar(scope, saved.id()));
    }

    /**
     * Takes a door out of service.
     *
     * <p>Bookings already made against it are deliberately left alone: a truck that is on the road
     * for a slot booked yesterday must not silently lose it, and a site taking a door down deals
     * with the trucks it has already promised. Only <em>new</em> bookings are refused.
     */
    @Transactional
    public LocationResourceView deactivate(CompanyScope scope, UUID id) {
        LocationResource resource = require(scope, id);
        UUID actorId = auditActorProvider.requireAppUserId();
        resource.deactivate(actorId);
        LocationResource saved = resourceRepository.saveAndFlush(resource);
        auditRecorder.record(scope, AuditAggregateType.LOCATION_RESOURCE, saved.id(), AuditAction.DEACTIVATE,
                Map.of("code", saved.code()));
        return LocationResourceView.from(saved, calendar(scope, saved.id()));
    }

    @Transactional
    public LocationResourceView activate(CompanyScope scope, UUID id) {
        LocationResource resource = require(scope, id);
        UUID actorId = auditActorProvider.requireAppUserId();
        resource.activate(actorId);
        LocationResource saved = resourceRepository.saveAndFlush(resource);
        auditRecorder.record(scope, AuditAggregateType.LOCATION_RESOURCE, saved.id(), AuditAction.ACTIVATE,
                Map.of("code", saved.code()));
        return LocationResourceView.from(saved, calendar(scope, saved.id()));
    }

    /**
     * Replaces a door's whole week.
     *
     * <p>Whole-week and not per-day, for the reason {@code ResourceCalendarRequest} gives: opening
     * hours are read as a set, and a half-applied change leaves a door open on a day the site meant
     * to close.
     */
    @Transactional
    public LocationResourceView replaceCalendar(CompanyScope scope, UUID id, ResourceCalendarRequest request) {
        LocationResource resource = require(scope, id);
        Set<java.time.DayOfWeek> seen = new HashSet<>();
        for (ResourceCalendarRequest.DayHours day : request.days()) {
            if (!seen.add(day.day())) {
                throw new InvalidRequestException("A door has one set of opening hours per day; "
                        + day.day() + " is listed twice.");
            }
            if (!day.closesAt().isAfter(day.opensAt())) {
                throw new InvalidRequestException("On " + day.day() + ", closesAt must be after opensAt. "
                        + "A door open overnight is two entries, one on each day.");
            }
        }

        calendarRepository.deleteByResourceId(resource.id());
        calendarRepository.flush();
        request.days().forEach(day -> calendarRepository.save(
                new ResourceCalendarEntry(scope.companyId(), resource.id(), day.day(), day.opensAt(),
                        day.closesAt())));
        calendarRepository.flush();

        auditRecorder.record(scope, AuditAggregateType.LOCATION_RESOURCE, resource.id(), AuditAction.UPDATE,
                Map.of("code", resource.code(), "openDays", String.valueOf(request.days().size())));
        return LocationResourceView.from(resource, calendar(scope, resource.id()));
    }

    /** Closes a door for a specific interval - a holiday, a stocktake, a broken leveller. */
    @Transactional
    public void block(CompanyScope scope, UUID id, OffsetDateTime startsAt, OffsetDateTime endsAt,
            String reason) {
        LocationResource resource = require(scope, id);
        if (!endsAt.isAfter(startsAt)) {
            throw new InvalidRequestException("endsAt must be after startsAt.");
        }
        if (reason == null || reason.isBlank()) {
            throw new InvalidRequestException("A closure needs a reason: a door shut for no stated "
                    + "cause is one nobody can reopen with confidence.");
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        blockedSlotRepository.saveAndFlush(new ResourceBlockedSlot(scope.companyId(), resource.id(),
                startsAt, endsAt, reason.trim(), actorId));
        auditRecorder.record(scope, AuditAggregateType.LOCATION_RESOURCE, resource.id(), AuditAction.UPDATE,
                Map.of("code", resource.code(), "blockedFrom", startsAt.toString(),
                        "blockedTo", endsAt.toString(), "reason", reason.trim()));
    }

    private List<ResourceCalendarEntry> calendar(CompanyScope scope, UUID resourceId) {
        return calendarRepository.findByCompanyIdAndResourceIdOrderByDayOfWeekAsc(scope.companyId(), resourceId);
    }

    private LocationResource require(CompanyScope scope, UUID id) {
        return resourceRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Dock not found."));
    }

    private void requireLocation(CompanyScope scope, UUID locationId) {
        if (!destinationLookupPort.findAllInCompany(Set.of(locationId), scope.companyId())
                .containsKey(locationId)) {
            throw new InvalidRequestException("locationId does not name a location of this company.");
        }
    }
}
