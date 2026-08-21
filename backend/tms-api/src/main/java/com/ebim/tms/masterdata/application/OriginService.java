package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Origin;
import com.ebim.tms.masterdata.infrastructure.OriginRepository;
import com.ebim.tms.masterdata.infrastructure.OriginSpecifications;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
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
 * Origin use cases. Takes a {@link CompanyScope}, never a company id (see
 * {@code CompanyContextService}) - a caller with no active membership in a company cannot
 * reach these methods with that company's id at all, because nothing can construct the scope
 * for them.
 *
 * <p>Since migration V14 an origin is a <em>compatibility projection</em> of a canonical
 * {@code tms.location}. This endpoint is unchanged for callers, and every write additionally
 * pushes the change up to the linked location through {@link LocationCompatibilityProjector},
 * in the same transaction. Without that the two models would drift the moment anyone used the
 * older API. See {@code docs/architecture/ADR_LOCATION_MODEL.md} section 5, which is also where
 * the one deliberate behaviour change lives: renaming an origin to a code another location
 * already holds is now a 409.
 */
@Service
public class OriginService {

    /** {@link PageQuery} sort allow-list: JPA entity attribute names, not column names. */
    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("code", "name", "type", "active", "createdAt", "updatedAt");

    private final OriginRepository originRepository;
    private final LocationCompatibilityProjector projector;
    private final AuditActorProvider auditActorProvider;

    public OriginService(OriginRepository originRepository, LocationCompatibilityProjector projector,
            AuditActorProvider auditActorProvider) {
        this.originRepository = originRepository;
        this.projector = projector;
        this.auditActorProvider = auditActorProvider;
    }

    @Transactional(readOnly = true)
    public PageResponse<OriginView> list(CompanyScope scope, OriginFilter filter, PageQuery pageQuery) {
        var specification = OriginSpecifications.matching(
                scope.companyId(), filter.code(), filter.name(), filter.search(), filter.type(), filter.active());
        Page<Origin> page = originRepository.findAll(specification, toPageable(pageQuery));
        List<OriginView> content = page.getContent().stream().map(OriginView::from).toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public OriginView get(CompanyScope scope, UUID id) {
        return OriginView.from(find(scope, id));
    }

    @Transactional
    public OriginView create(CompanyScope scope, OriginRequest request) {
        String code = normalizeCode(request.code());
        validateCoordinatePair(request.latitude(), request.longitude());
        validateTimeZone(request.timeZone());
        if (originRepository.existsByCompanyIdAndCode(scope.companyId(), code)) {
            throw duplicateCode(code);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        Origin origin = new Origin(scope.companyId(), code, request.name().trim(), request.type(),
                blankToNull(request.address()), request.latitude(), request.longitude(),
                request.timeZone().trim(), blankToNull(request.externalReference()), actorId);
        return OriginView.from(syncUp(saveOrConflict(origin, code), actorId));
    }

    @Transactional
    public OriginView update(CompanyScope scope, UUID id, OriginRequest request) {
        Origin origin = find(scope, id);
        String code = normalizeCode(request.code());
        validateCoordinatePair(request.latitude(), request.longitude());
        validateTimeZone(request.timeZone());
        if (originRepository.existsByCompanyIdAndCodeAndIdNot(scope.companyId(), code, id)) {
            throw duplicateCode(code);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        origin.applyChanges(code, request.name().trim(), request.type(), blankToNull(request.address()),
                request.latitude(), request.longitude(), request.timeZone().trim(),
                blankToNull(request.externalReference()), actorId);
        return OriginView.from(syncUp(saveOrConflict(origin, code), actorId));
    }

    @Transactional
    public OriginView activate(CompanyScope scope, UUID id) {
        Origin origin = find(scope, id);
        UUID actorId = auditActorProvider.requireAppUserId();
        origin.activate(actorId);
        return OriginView.from(syncUp(originRepository.saveAndFlush(origin), actorId));
    }

    @Transactional
    public OriginView deactivate(CompanyScope scope, UUID id) {
        Origin origin = find(scope, id);
        UUID actorId = auditActorProvider.requireAppUserId();
        origin.deactivate(actorId);
        return OriginView.from(syncUp(originRepository.saveAndFlush(origin), actorId));
    }

    private Origin find(CompanyScope scope, UUID id) {
        return originRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Origin not found."));
    }

    /**
     * Pushes a just-saved origin up to its canonical location, creating one if this origin has
     * none yet. Returns the same instance so call sites read as one expression.
     */
    private Origin syncUp(Origin origin, UUID actorId) {
        projector.syncFromOrigin(origin, actorId);
        return origin;
    }

    /** Saves and translates a race with a concurrent duplicate write into the same conflict a pre-check would give. */
    private Origin saveOrConflict(Origin origin, String code) {
        try {
            return originRepository.saveAndFlush(origin);
        } catch (DataIntegrityViolationException raced) {
            throw duplicateCode(code);
        }
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("An origin with code '" + code + "' already exists in this company.");
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

    private static void validateTimeZone(String timeZone) {
        try {
            ZoneId.of(timeZone.trim());
        } catch (DateTimeException invalid) {
            throw new InvalidRequestException("timeZone must be a valid IANA time zone identifier, e.g. 'America/Lima'.");
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
