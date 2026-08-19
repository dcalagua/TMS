package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Zone;
import com.ebim.tms.masterdata.infrastructure.ZoneRepository;
import com.ebim.tms.masterdata.infrastructure.ZoneSpecifications;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
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

/** Zone use cases. See {@link OriginService} for the {@link CompanyScope} contract this follows. */
@Service
public class ZoneService {

    private static final Set<String> SORTABLE_PROPERTIES = Set.of("code", "name", "active", "createdAt", "updatedAt");

    private final ZoneRepository zoneRepository;
    private final AuditActorProvider auditActorProvider;

    public ZoneService(ZoneRepository zoneRepository, AuditActorProvider auditActorProvider) {
        this.zoneRepository = zoneRepository;
        this.auditActorProvider = auditActorProvider;
    }

    @Transactional(readOnly = true)
    public PageResponse<ZoneView> list(CompanyScope scope, ZoneFilter filter, PageQuery pageQuery) {
        var specification =
                ZoneSpecifications.matching(scope.companyId(), filter.code(), filter.name(), filter.active());
        Page<Zone> page = zoneRepository.findAll(specification, toPageable(pageQuery));
        List<ZoneView> content = page.getContent().stream().map(ZoneView::from).toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ZoneView get(CompanyScope scope, UUID id) {
        return ZoneView.from(find(scope, id));
    }

    @Transactional
    public ZoneView create(CompanyScope scope, ZoneRequest request) {
        String code = normalizeCode(request.code());
        if (zoneRepository.existsByCompanyIdAndCode(scope.companyId(), code)) {
            throw duplicateCode(code);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        Zone zone = new Zone(scope.companyId(), code, request.name().trim(),
                blankToNull(request.description()), actorId);
        return ZoneView.from(saveOrConflict(zone, code));
    }

    @Transactional
    public ZoneView update(CompanyScope scope, UUID id, ZoneRequest request) {
        Zone zone = find(scope, id);
        String code = normalizeCode(request.code());
        if (zoneRepository.existsByCompanyIdAndCodeAndIdNot(scope.companyId(), code, id)) {
            throw duplicateCode(code);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        zone.applyChanges(code, request.name().trim(), blankToNull(request.description()), actorId);
        return ZoneView.from(saveOrConflict(zone, code));
    }

    @Transactional
    public ZoneView activate(CompanyScope scope, UUID id) {
        Zone zone = find(scope, id);
        zone.activate(auditActorProvider.requireAppUserId());
        return ZoneView.from(zoneRepository.save(zone));
    }

    @Transactional
    public ZoneView deactivate(CompanyScope scope, UUID id) {
        Zone zone = find(scope, id);
        zone.deactivate(auditActorProvider.requireAppUserId());
        return ZoneView.from(zoneRepository.save(zone));
    }

    private Zone find(CompanyScope scope, UUID id) {
        return zoneRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Zone not found."));
    }

    private Zone saveOrConflict(Zone zone, String code) {
        try {
            return zoneRepository.saveAndFlush(zone);
        } catch (DataIntegrityViolationException raced) {
            throw duplicateCode(code);
        }
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("A zone with code '" + code + "' already exists in this company.");
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
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
