package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Destination;
import com.ebim.tms.masterdata.domain.Zone;
import com.ebim.tms.masterdata.infrastructure.DestinationRepository;
import com.ebim.tms.masterdata.infrastructure.DestinationSpecifications;
import com.ebim.tms.masterdata.infrastructure.ZoneRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Destination use cases. Takes a {@link CompanyScope}, never a company id - see
 * {@link com.ebim.tms.masterdata.application.OriginService} for the contract this follows, and
 * for why every write here also pushes the change up to the canonical {@code tms.location} this
 * destination projects (migration V14, {@code docs/architecture/ADR_LOCATION_MODEL.md}).
 */
@Service
public class DestinationService {

    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("code", "name", "type", "active", "createdAt", "updatedAt");

    private final DestinationRepository destinationRepository;
    private final ZoneRepository zoneRepository;
    private final LocationCompatibilityProjector projector;
    private final AuditActorProvider auditActorProvider;

    public DestinationService(
            DestinationRepository destinationRepository, ZoneRepository zoneRepository,
            LocationCompatibilityProjector projector, AuditActorProvider auditActorProvider) {
        this.destinationRepository = destinationRepository;
        this.zoneRepository = zoneRepository;
        this.projector = projector;
        this.auditActorProvider = auditActorProvider;
    }

    @Transactional(readOnly = true)
    public PageResponse<DestinationView> list(CompanyScope scope, DestinationFilter filter, PageQuery pageQuery) {
        var specification = DestinationSpecifications.matching(
                scope.companyId(), filter.code(), filter.name(), filter.search(), filter.type(), filter.zoneId(),
                filter.active());
        Page<Destination> page = destinationRepository.findAll(specification, toPageable(pageQuery));

        Map<UUID, Zone> zonesById = loadZones(scope, page.getContent());
        List<DestinationView> content = page.getContent().stream()
                .map(destination -> DestinationView.from(destination, zonesById.get(destination.zoneId())))
                .toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public DestinationView get(CompanyScope scope, UUID id) {
        Destination destination = find(scope, id);
        return DestinationView.from(destination, resolveZone(scope, destination.zoneId()));
    }

    @Transactional
    public DestinationView create(CompanyScope scope, DestinationRequest request) {
        String code = normalizeCode(request.code());
        validateCoordinatePair(request.latitude(), request.longitude());
        Zone zone = requireZoneInScope(scope, request.zoneId());
        if (destinationRepository.existsByCompanyIdAndCode(scope.companyId(), code)) {
            throw duplicateCode(code);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        Destination destination = new Destination(scope.companyId(), code, request.name().trim(), request.type(),
                blankToNull(request.address()), blankToNull(request.addressReference()), blankToNull(request.district()),
                blankToNull(request.province()), blankToNull(request.department()), request.country().trim(),
                request.latitude(), request.longitude(), request.zoneId(), request.serviceTimeMinutes(),
                blankToNull(request.externalReference()), actorId);
        return DestinationView.from(syncUp(saveOrConflict(destination, code), actorId), zone);
    }

    @Transactional
    public DestinationView update(CompanyScope scope, UUID id, DestinationRequest request) {
        Destination destination = find(scope, id);
        String code = normalizeCode(request.code());
        validateCoordinatePair(request.latitude(), request.longitude());
        Zone zone = requireZoneInScope(scope, request.zoneId());
        if (destinationRepository.existsByCompanyIdAndCodeAndIdNot(scope.companyId(), code, id)) {
            throw duplicateCode(code);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        destination.applyChanges(code, request.name().trim(), request.type(), blankToNull(request.address()),
                blankToNull(request.addressReference()), blankToNull(request.district()),
                blankToNull(request.province()), blankToNull(request.department()), request.country().trim(),
                request.latitude(), request.longitude(), request.zoneId(), request.serviceTimeMinutes(),
                blankToNull(request.externalReference()), actorId);
        return DestinationView.from(syncUp(saveOrConflict(destination, code), actorId), zone);
    }

    @Transactional
    public DestinationView activate(CompanyScope scope, UUID id) {
        Destination destination = find(scope, id);
        UUID actorId = auditActorProvider.requireAppUserId();
        destination.activate(actorId);
        return DestinationView.from(syncUp(destinationRepository.saveAndFlush(destination), actorId),
                resolveZone(scope, destination.zoneId()));
    }

    @Transactional
    public DestinationView deactivate(CompanyScope scope, UUID id) {
        Destination destination = find(scope, id);
        UUID actorId = auditActorProvider.requireAppUserId();
        destination.deactivate(actorId);
        return DestinationView.from(syncUp(destinationRepository.saveAndFlush(destination), actorId),
                resolveZone(scope, destination.zoneId()));
    }

    private Destination find(CompanyScope scope, UUID id) {
        return destinationRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Destination not found."));
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
    private Map<UUID, Zone> loadZones(CompanyScope scope, List<Destination> destinations) {
        Set<UUID> zoneIds = destinations.stream().map(Destination::zoneId).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        // A plain HashMap, not Map.of(): destinations without a zone look up a null key below,
        // and Map.of()'s immutable map throws on get(null) instead of returning null.
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

    /**
     * Pushes a just-saved destination up to its canonical location, creating one if this
     * destination has none yet. Returns the same instance so call sites read as one expression.
     */
    private Destination syncUp(Destination destination, UUID actorId) {
        projector.syncFromDestination(destination, actorId);
        return destination;
    }

    /** Saves and translates a race with a concurrent duplicate write into the same conflict a pre-check would give. */
    private Destination saveOrConflict(Destination destination, String code) {
        try {
            return destinationRepository.saveAndFlush(destination);
        } catch (DataIntegrityViolationException raced) {
            throw duplicateCode(code);
        }
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("A destination with code '" + code + "' already exists in this company.");
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static void validateCoordinatePair(java.math.BigDecimal latitude, java.math.BigDecimal longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new InvalidRequestException("latitude and longitude must both be provided or both left blank.");
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
