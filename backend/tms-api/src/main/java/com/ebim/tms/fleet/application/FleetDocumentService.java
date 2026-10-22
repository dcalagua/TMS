package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.Driver;
import com.ebim.tms.fleet.domain.FleetComplianceEvaluator.Rule;
import com.ebim.tms.fleet.domain.FleetDocument;
import com.ebim.tms.fleet.domain.Vehicle;
import com.ebim.tms.fleet.infrastructure.DriverRepository;
import com.ebim.tms.fleet.infrastructure.FleetDocumentRepository;
import com.ebim.tms.fleet.infrastructure.FleetDocumentSpecifications;
import com.ebim.tms.fleet.infrastructure.VehicleRepository;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.FleetDocumentSubject;
import com.ebim.tms.shared.reference.FleetDocumentType;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recording and reading fleet compliance documents (migration V72). A document is deactivated,
 * never deleted: it is the record of what a truck carried on the day it was audited.
 */
@Service
public class FleetDocumentService {

    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("expiresOn", "issuedOn", "documentType", "subjectType", "createdAt", "updatedAt");

    private final FleetDocumentRepository documentRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final FleetComplianceService complianceService;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public FleetDocumentService(FleetDocumentRepository documentRepository, VehicleRepository vehicleRepository,
            DriverRepository driverRepository, FleetComplianceService complianceService,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder) {
        this.documentRepository = documentRepository;
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.complianceService = complianceService;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    /**
     * The documents screen: filtered, paginated, soonest expiry first by default - the paper that
     * runs out next is the one somebody has to chase. Undated documents sort last.
     */
    @Transactional(readOnly = true)
    public PageResponse<FleetDocumentView> list(CompanyScope scope, FleetDocumentFilter filter, PageQuery pageQuery) {
        UUID companyId = scope.companyId();
        LocalDate today = scope.today();
        int alertDays = complianceService.alertDaysOf(companyId);
        Page<FleetDocument> page = documentRepository.findAll(
                FleetDocumentSpecifications.matching(companyId, filter.subjectType(), filter.vehicleId(),
                        filter.driverId(), filter.documentType(), filter.status(), today, alertDays, filter.active()),
                toPageable(pageQuery));
        return new PageResponse<>(views(companyId, page.getContent(), today, alertDays), pageQuery.pageNumber(),
                pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public FleetDocumentView get(CompanyScope scope, UUID id) {
        return single(scope, find(scope, id));
    }

    @Transactional
    public FleetDocumentView create(CompanyScope scope, FleetDocumentRequest request) {
        requireTypeMatchesSubject(request.subjectType(), request.documentType());
        requireDates(request);
        requireSubject(scope, request.subjectType(), request.subjectId());
        UUID actorId = auditActorProvider.requireAppUserId();
        FleetDocument document = documentRepository.saveAndFlush(new FleetDocument(scope.companyId(),
                request.subjectType(), request.subjectId(), request.documentType(), trim(request.documentNumber()),
                trim(request.issuer()), request.issuedOn(), request.expiresOn(), trim(request.notes()), actorId));
        audit(scope, document, AuditAction.CREATE);
        return single(scope, document);
    }

    @Transactional
    public FleetDocumentView update(CompanyScope scope, UUID id, FleetDocumentRequest request) {
        FleetDocument document = find(scope, id);
        if (request.subjectType() != document.subjectType() || !request.subjectId().equals(document.subjectId())) {
            throw new InvalidRequestException("A document stays with the vehicle or driver it was recorded for."
                    + " Record a new document for the other one instead.");
        }
        requireTypeMatchesSubject(request.subjectType(), request.documentType());
        requireDates(request);
        document.applyChanges(request.documentType(), trim(request.documentNumber()), trim(request.issuer()),
                request.issuedOn(), request.expiresOn(), trim(request.notes()), auditActorProvider.requireAppUserId());
        FleetDocument saved = documentRepository.saveAndFlush(document);
        audit(scope, saved, AuditAction.UPDATE);
        return single(scope, saved);
    }

    @Transactional
    public FleetDocumentView activate(CompanyScope scope, UUID id) {
        FleetDocument document = find(scope, id);
        document.activate(auditActorProvider.requireAppUserId());
        FleetDocument saved = documentRepository.saveAndFlush(document);
        audit(scope, saved, AuditAction.ACTIVATE);
        return single(scope, saved);
    }

    @Transactional
    public FleetDocumentView deactivate(CompanyScope scope, UUID id) {
        FleetDocument document = find(scope, id);
        document.deactivate(auditActorProvider.requireAppUserId());
        FleetDocument saved = documentRepository.saveAndFlush(document);
        audit(scope, saved, AuditAction.DEACTIVATE);
        return single(scope, saved);
    }

    // ------------------------------------------------------------------ internals

    private FleetDocument find(CompanyScope scope, UUID id) {
        return documentRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Fleet document not found."));
    }

    private FleetDocumentView single(CompanyScope scope, FleetDocument document) {
        int alertDays = complianceService.alertDaysOf(scope.companyId());
        return views(scope.companyId(), List.of(document), scope.today(), alertDays).get(0);
    }

    /** The subjects of a page in two batched lookups, never one per row. */
    private List<FleetDocumentView> views(UUID companyId, List<FleetDocument> documents, LocalDate today,
            int alertDays) {
        if (documents.isEmpty()) {
            return List.of();
        }
        Map<FleetDocumentType, Rule> rules = complianceService.rulesOf(companyId);
        Set<UUID> vehicleIds = documents.stream().map(FleetDocument::vehicleId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> driverIds = documents.stream().map(FleetDocument::driverId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Vehicle> vehicles = vehicleIds.isEmpty() ? Map.of()
                : vehicleRepository.findByIdInAndCompanyId(vehicleIds, companyId).stream()
                        .collect(Collectors.toMap(Vehicle::id, Function.identity()));
        Map<UUID, Driver> drivers = driverIds.isEmpty() ? Map.of()
                : driverRepository.findByIdInAndCompanyId(driverIds, companyId).stream()
                        .collect(Collectors.toMap(Driver::id, Function.identity()));
        return documents.stream().map(document -> {
            String code;
            String name;
            if (document.subjectType() == FleetDocumentSubject.VEHICLE) {
                Vehicle vehicle = vehicles.get(document.vehicleId());
                code = vehicle == null ? null : vehicle.licensePlate();
                name = vehicle == null ? null : vehicle.code();
            } else {
                Driver driver = drivers.get(document.driverId());
                code = driver == null ? null : driver.code();
                name = driver == null ? null : driver.fullName();
            }
            return FleetComplianceService.view(document, code, name, today, alertDays, rules);
        }).toList();
    }

    private void requireSubject(CompanyScope scope, FleetDocumentSubject subject, UUID subjectId) {
        boolean exists = subject == FleetDocumentSubject.VEHICLE
                ? vehicleRepository.findByIdAndCompanyId(subjectId, scope.companyId()).isPresent()
                : driverRepository.findByIdAndCompanyId(subjectId, scope.companyId()).isPresent();
        if (!exists) {
            throw new InvalidRequestException("subjectId does not reference a "
                    + (subject == FleetDocumentSubject.VEHICLE ? "vehicle" : "driver") + " of this company.");
        }
    }

    private static void requireTypeMatchesSubject(FleetDocumentSubject subject, FleetDocumentType type) {
        if (type.subject() != subject) {
            throw new InvalidRequestException("A " + type.label() + " is a document of a "
                    + type.subject().name().toLowerCase(java.util.Locale.ROOT) + ", not of a "
                    + subject.name().toLowerCase(java.util.Locale.ROOT) + ".");
        }
    }

    /** Mirrors {@code ck_fleet_document_dates} as a clean 400. */
    private static void requireDates(FleetDocumentRequest request) {
        if (request.issuedOn() != null && request.expiresOn() != null
                && request.expiresOn().isBefore(request.issuedOn())) {
            throw new InvalidRequestException("expiresOn must not be before issuedOn.");
        }
    }

    private void audit(CompanyScope scope, FleetDocument document, AuditAction action) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subjectType", document.subjectType().name());
        details.put("subjectId", document.subjectId().toString());
        details.put("documentType", document.documentType().name());
        if (document.expiresOn() != null) {
            details.put("expiresOn", document.expiresOn().toString());
        }
        auditRecorder.record(scope, AuditAggregateType.FLEET_DOCUMENT, document.id(), action, details);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Pageable toPageable(PageQuery pageQuery) {
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(SORTABLE_PROPERTIES);
        Sort sort = terms.isEmpty()
                // PostgreSQL puts nulls last on an ascending sort: an undated paper sorts after every dated one.
                ? Sort.by(Sort.Order.asc("expiresOn"), Sort.Order.asc("documentType"), Sort.Order.asc("id"))
                : Sort.by(terms.stream()
                        .map(term -> new Sort.Order(
                                term.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, term.property()))
                        .toList());
        return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), sort);
    }
}
