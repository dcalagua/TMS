package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Location;
import com.ebim.tms.masterdata.domain.Zone;
import com.ebim.tms.masterdata.infrastructure.LocationRepository;
import com.ebim.tms.masterdata.infrastructure.LocationSpecifications;
import com.ebim.tms.masterdata.infrastructure.ZoneRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Canonical Location use cases: the one write path for a physical place. Takes a
 * {@link CompanyScope}, never a company id, so the tenant is whatever
 * {@code CompanyScopeFilter} resolved from the caller's membership and never something the
 * request body can name.
 *
 * <p>Until V23 every write here also had to project the location into {@code tms.origin} and
 * {@code tms.destination} and keep the three tables in step. Those foreign keys now point at
 * {@code tms.location} directly, so there is nothing left to synchronise: saving the location
 * <em>is</em> the write. Deactivating one no longer needs to deactivate anything else either -
 * a place that is out of service is out of service from both ends of a movement, which is the
 * behaviour an operator expected all along.
 *
 * <p>See {@code docs/domain/LOCATIONS.md} for the domain contract this implements.
 */
@Service
public class LocationService {

    /** {@link PageQuery} sort allow-list: JPA entity attribute names, not column names. */
    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("code", "name", "type", "active", "createdAt", "updatedAt");

    private final LocationRepository locationRepository;
    private final ZoneRepository zoneRepository;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public LocationService(LocationRepository locationRepository, ZoneRepository zoneRepository,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder) {
        this.locationRepository = locationRepository;
        this.zoneRepository = zoneRepository;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public PageResponse<LocationView> list(CompanyScope scope, LocationFilter filter, PageQuery pageQuery) {
        var specification = LocationSpecifications.matching(scope.companyId(), filter.search(), filter.type(),
                filter.role(), filter.zoneId(), filter.active());
        Page<Location> page = locationRepository.findAll(specification, toPageable(pageQuery));

        Map<UUID, Zone> zonesById = loadZones(scope, page.getContent());
        List<LocationView> content = page.getContent().stream()
                .map(location -> LocationView.from(location, zonesById.get(location.zoneId())))
                .toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public LocationView get(CompanyScope scope, UUID id) {
        Location location = find(scope, id);
        return LocationView.from(location, resolveZone(scope, location.zoneId()));
    }

    @Transactional
    public LocationView create(CompanyScope scope, LocationRequest request) {
        String code = normalizeCode(request.code());
        validateCoordinatePair(request.latitude(), request.longitude());
        validateTimeZone(request.timeZone());
        ExternalIdentity identity = validateExternalIdentity(request);
        Zone zone = requireZoneInScope(scope, request.zoneId());
        if (locationRepository.existsByCompanyIdAndCode(scope.companyId(), code)) {
            throw duplicateCode(code);
        }
        requireExternalIdentityIsFree(scope, identity, null);

        UUID actorId = auditActorProvider.writerAppUserId();
        Location location = new Location(scope.companyId(), code, request.name().trim(), request.type(),
                blankToNull(request.address()), blankToNull(request.addressReference()),
                blankToNull(request.district()), blankToNull(request.province()), blankToNull(request.department()),
                request.country().trim(), request.timeZone().trim(), request.latitude(), request.longitude(),
                request.zoneId(), request.serviceTimeMinutes(), identity.system(), identity.reference(), actorId);
        location.replaceRoles(request.roles());

        Location saved = saveOrConflict(location, code);
        auditRecorder.record(scope, AuditAggregateType.LOCATION, saved.id(), AuditAction.CREATE,
                Map.of("code", saved.code(), "type", saved.type().name()));
        return LocationView.from(saved, zone);
    }

    @Transactional
    public LocationView update(CompanyScope scope, UUID id, LocationRequest request) {
        Location location = find(scope, id);
        String code = normalizeCode(request.code());
        validateCoordinatePair(request.latitude(), request.longitude());
        validateTimeZone(request.timeZone());
        ExternalIdentity identity = validateExternalIdentity(request);
        Zone zone = requireZoneInScope(scope, request.zoneId());
        if (locationRepository.existsByCompanyIdAndCodeAndIdNot(scope.companyId(), code, id)) {
            throw duplicateCode(code);
        }
        requireExternalIdentityIsFree(scope, identity, id);

        UUID actorId = auditActorProvider.writerAppUserId();
        location.applyChanges(code, request.name().trim(), request.type(), blankToNull(request.address()),
                blankToNull(request.addressReference()), blankToNull(request.district()),
                blankToNull(request.province()), blankToNull(request.department()), request.country().trim(),
                request.timeZone().trim(), request.latitude(), request.longitude(), request.zoneId(),
                request.serviceTimeMinutes(), identity.system(), identity.reference(), actorId);
        location.replaceRoles(request.roles());

        Location saved = saveOrConflict(location, code);
        auditRecorder.record(scope, AuditAggregateType.LOCATION, saved.id(), AuditAction.UPDATE,
                Map.of("code", saved.code()));
        return LocationView.from(saved, zone);
    }

    @Transactional
    public LocationView activate(CompanyScope scope, UUID id) {
        return setActive(scope, id, true);
    }

    @Transactional
    public LocationView deactivate(CompanyScope scope, UUID id) {
        return setActive(scope, id, false);
    }

    /**
     * One flag, both ends: a store that is out of service stops being offered as an order
     * destination and as the origin of its own returns, because there is one row and one
     * {@code active} on it. Existing orders and trips that already reference it keep rendering -
     * see {@code OriginLookupPort.findAllInCompany}.
     */
    private LocationView setActive(CompanyScope scope, UUID id, boolean active) {
        Location location = find(scope, id);
        UUID actorId = auditActorProvider.writerAppUserId();
        if (active) {
            location.activate(actorId);
        } else {
            location.deactivate(actorId);
        }
        Location saved = locationRepository.saveAndFlush(location);
        auditRecorder.record(scope, AuditAggregateType.LOCATION, saved.id(),
                active ? AuditAction.ACTIVATE : AuditAction.DEACTIVATE, Map.of("code", saved.code()));
        return LocationView.from(saved, resolveZone(scope, saved.zoneId()));
    }

    private Location find(CompanyScope scope, UUID id) {
        return locationRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Location not found."));
    }

    /** {@code null} zoneId is allowed (the field is optional); a non-null one must resolve within this company. */
    private Zone requireZoneInScope(CompanyScope scope, UUID zoneId) {
        if (zoneId == null) {
            return null;
        }
        return zoneRepository.findByIdAndCompanyId(zoneId, scope.companyId())
                .orElseThrow(() -> new InvalidRequestException("zoneId does not reference a zone in this company."));
    }

    private Zone resolveZone(CompanyScope scope, UUID zoneId) {
        return zoneId == null ? null : zoneRepository.findByIdAndCompanyId(zoneId, scope.companyId()).orElse(null);
    }

    /** One batched lookup per page instead of one query per row. */
    private Map<UUID, Zone> loadZones(CompanyScope scope, List<Location> locations) {
        Set<UUID> zoneIds = locations.stream().map(Location::zoneId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // A plain HashMap, not Map.of(): locations without a zone look up a null key below, and
        // Map.of()'s immutable map throws on get(null) instead of returning null.
        Map<UUID, Zone> byId = new HashMap<>();
        if (zoneIds.isEmpty()) {
            return byId;
        }
        for (Zone zone : zoneRepository.findAllById(zoneIds)) {
            if (zone.companyId().equals(scope.companyId())) {
                byId.put(zone.id(), zone);
            }
        }
        return byId;
    }

    /** The external identity pair, kept together so the pair-complete rule cannot be half-applied. */
    private record ExternalIdentity(String system, String reference) {}

    /**
     * Both halves or neither, the same rule {@code ck_location_external_pair_complete} enforces in
     * the database and for the same reason: a reference with no system cannot be deduplicated, so
     * accepting one would be accepting an idempotency key that does not identify anything.
     */
    private static ExternalIdentity validateExternalIdentity(LocationRequest request) {
        String system = blankToNull(request.externalSystem());
        String reference = blankToNull(request.externalReference());
        if ((system == null) != (reference == null)) {
            throw new InvalidRequestException(
                    "externalSystem and externalReference must both be provided or both left blank.");
        }
        return new ExternalIdentity(system, reference);
    }

    private void requireExternalIdentityIsFree(CompanyScope scope, ExternalIdentity identity, UUID selfId) {
        if (identity.reference() == null) {
            return;
        }
        boolean taken = selfId == null
                ? locationRepository.existsByCompanyIdAndExternalSystemAndExternalReference(
                        scope.companyId(), identity.system(), identity.reference())
                : locationRepository.existsByCompanyIdAndExternalSystemAndExternalReferenceAndIdNot(
                        scope.companyId(), identity.system(), identity.reference(), selfId);
        if (taken) {
            throw new ConflictException("A location with external reference '" + identity.reference()
                    + "' from system '" + identity.system() + "' already exists in this company.");
        }
    }

    /** Saves and translates a race with a concurrent duplicate write into the same conflict a pre-check would give. */
    private Location saveOrConflict(Location location, String code) {
        try {
            return locationRepository.saveAndFlush(location);
        } catch (DataIntegrityViolationException raced) {
            throw duplicateCode(code);
        }
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("A location with code '" + code + "' already exists in this company.");
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static void validateCoordinatePair(BigDecimal latitude, BigDecimal longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new InvalidRequestException("latitude and longitude must both be provided or both left blank.");
        }
    }

    private static void validateTimeZone(String timeZone) {
        try {
            ZoneId.of(timeZone.trim());
        } catch (DateTimeException invalid) {
            throw new InvalidRequestException(
                    "timeZone must be a valid IANA time zone identifier, e.g. 'America/Lima'.");
        }
    }

    private static Pageable toPageable(PageQuery pageQuery) {
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(SORTABLE_PROPERTIES);
        Sort sort = terms.isEmpty()
                ? Sort.by(Sort.Direction.ASC, "code")
                : Sort.by(terms.stream()
                        .map(term -> new Sort.Order(
                                term.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, term.property()))
                        .toList());
        return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), sort);
    }
}
