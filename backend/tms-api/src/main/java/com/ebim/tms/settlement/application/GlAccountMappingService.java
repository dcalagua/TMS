package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.GlAccountMapping;
import com.ebim.tms.settlement.domain.GlScopeType;
import com.ebim.tms.settlement.infrastructure.GlAccountMappingRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.RouteTemplateLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code tms.gl_account_mapping} (V65, ADR-023). One active mapping per concept and scope
 * ({@code uq_gl_account_mapping_active_scope}); a retired one is kept, because a past period's
 * export named it. Read with {@code settlement.accrual:read}, written with {@code settlement.accrual:manage}.
 */
@Service
public class GlAccountMappingService {

    private final GlAccountMappingRepository repository;
    private final OriginLookupPort originLookupPort;
    private final RouteTemplateLookupPort routeLookupPort;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public GlAccountMappingService(GlAccountMappingRepository repository, OriginLookupPort originLookupPort,
            RouteTemplateLookupPort routeLookupPort, AuditActorProvider auditActorProvider,
            AuditRecorder auditRecorder) {
        this.repository = repository;
        this.originLookupPort = originLookupPort;
        this.routeLookupPort = routeLookupPort;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public List<GlAccountMappingView> list(CompanyScope scope, boolean includeInactive) {
        List<GlAccountMapping> mappings = repository.findByCompanyIdOrderByConceptAscScopeTypeAscCreatedAtAsc(
                scope.companyId()).stream().filter(mapping -> includeInactive || mapping.active()).toList();
        Map<UUID, String> names = scopeNames(scope, mappings);
        return mappings.stream().map(mapping -> GlAccountMappingView.of(mapping, names.get(mapping.scopeId()))).toList();
    }

    @Transactional
    public GlAccountMappingView create(CompanyScope scope, GlAccountMappingRequest request) {
        UUID actorId = auditActorProvider.requireAppUserId();
        GlScopeType scopeType = request.scopeType() == null ? GlScopeType.COMPANY : request.scopeType();
        if ((scopeType == GlScopeType.COMPANY) != (request.scopeId() == null)) {
            throw new InvalidRequestException("A COMPANY mapping names no scopeId; ORIGIN and ROUTE name one.");
        }
        requireScopeInCompany(scope, scopeType, request.scopeId());
        if (request.withholdingKind() != null && request.concept() != com.ebim.tms.settlement.domain.GlConcept.WITHHOLDING_PAYABLE) {
            throw new InvalidRequestException("Only a WITHHOLDING_PAYABLE mapping names a withholding kind.");
        }
        GlAccountMapping mapping = new GlAccountMapping(scope.companyId(), request.concept(), request.withholdingKind(),
                scopeType,
                request.scopeId(), request.accountCode().trim(), blankToNull(request.costCenterCode()), actorId);
        try {
            mapping = repository.saveAndFlush(mapping);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_gl_account_mapping_active_scope")) {
                throw new ConflictException("There is already an active " + request.concept()
                        + (request.withholdingKind() == null ? "" : " (" + request.withholdingKind() + ")")
                        + " mapping for this scope. Change it, or retire it first.");
            }
            throw e;
        }
        audit(scope, mapping, "created", null);
        return view(scope, mapping);
    }

    @Transactional
    public GlAccountMappingView update(CompanyScope scope, UUID id, GlAccountMappingRequest request) {
        UUID actorId = auditActorProvider.requireAppUserId();
        GlAccountMapping mapping = load(scope, id);
        if (!mapping.active()) {
            throw new ConflictException("A retired mapping is not changed; create a new one.");
        }
        if (request.concept() != mapping.concept()
                || request.withholdingKind() != mapping.withholdingKind()
                || (request.scopeType() != null && request.scopeType() != mapping.scopeType())
                || !Objects.equals(request.scopeId(), mapping.scopeId())) {
            throw new InvalidRequestException("The concept and scope of a mapping do not change; create a new one.");
        }
        String before = mapping.accountCode() + "/" + Objects.toString(mapping.costCenterCode(), "");
        mapping.change(request.accountCode().trim(), blankToNull(request.costCenterCode()), actorId);
        mapping = repository.saveAndFlush(mapping);
        audit(scope, mapping, "changed", before);
        return view(scope, mapping);
    }

    @Transactional
    public GlAccountMappingView deactivate(CompanyScope scope, UUID id) {
        UUID actorId = auditActorProvider.requireAppUserId();
        GlAccountMapping mapping = load(scope, id);
        if (mapping.active()) {
            mapping.deactivate(actorId);
            mapping = repository.saveAndFlush(mapping);
            audit(scope, mapping, "retired", null);
        }
        return view(scope, mapping);
    }

    private void requireScopeInCompany(CompanyScope scope, GlScopeType scopeType, UUID scopeId) {
        boolean exists = switch (scopeType) {
            case COMPANY -> true;
            case ORIGIN -> !originLookupPort.findAllInCompany(Set.of(scopeId), scope.companyId()).isEmpty();
            case ROUTE -> !routeLookupPort.findAllInCompany(Set.of(scopeId), scope.companyId()).isEmpty();
        };
        if (!exists) {
            throw new InvalidRequestException("That " + scopeType.name().toLowerCase(java.util.Locale.ROOT)
                    + " does not belong to this company.");
        }
    }

    private GlAccountMapping load(CompanyScope scope, UUID id) {
        return repository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("GL account mapping not found."));
    }

    private void audit(CompanyScope scope, GlAccountMapping mapping, String change, String before) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("change", change);
        metadata.put("concept", mapping.concept().name());
        if (mapping.withholdingKind() != null) {
            metadata.put("withholdingKind", mapping.withholdingKind().name());
        }
        metadata.put("scopeType", mapping.scopeType().name());
        metadata.put("accountCode", mapping.accountCode());
        if (mapping.costCenterCode() != null) {
            metadata.put("costCenterCode", mapping.costCenterCode());
        }
        if (before != null) {
            metadata.put("before", before);
        }
        auditRecorder.record(scope, AuditAggregateType.GL_ACCOUNT_MAPPING, mapping.id(), AuditAction.GL_MAPPING_CHANGED,
                metadata);
    }

    private GlAccountMappingView view(CompanyScope scope, GlAccountMapping mapping) {
        return GlAccountMappingView.of(mapping, scopeNames(scope, List.of(mapping)).get(mapping.scopeId()));
    }

    private Map<UUID, String> scopeNames(CompanyScope scope, List<GlAccountMapping> mappings) {
        Set<UUID> origins = idsOf(mappings, GlScopeType.ORIGIN);
        Set<UUID> routes = idsOf(mappings, GlScopeType.ROUTE);
        Map<UUID, String> names = new HashMap<>();
        if (!origins.isEmpty()) {
            originLookupPort.findAllInCompany(origins, scope.companyId()).values()
                    .forEach(origin -> names.put(origin.id(), label(origin)));
        }
        if (!routes.isEmpty()) {
            routeLookupPort.findAllInCompany(routes, scope.companyId()).values()
                    .forEach((RouteTemplate route) -> names.put(route.id(), route.code() + " - " + route.name()));
        }
        return names;
    }

    private static Set<UUID> idsOf(List<GlAccountMapping> mappings, GlScopeType type) {
        return mappings.stream().filter(mapping -> mapping.scopeType() == type).map(GlAccountMapping::scopeId)
                .collect(Collectors.toSet());
    }

    private static String label(MasterReference reference) {
        return reference.code() + " - " + reference.name();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
