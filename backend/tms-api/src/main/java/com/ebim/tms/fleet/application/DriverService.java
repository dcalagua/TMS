package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.Carrier;
import com.ebim.tms.fleet.domain.Driver;
import com.ebim.tms.fleet.infrastructure.CarrierRepository;
import com.ebim.tms.fleet.infrastructure.DriverRepository;
import com.ebim.tms.fleet.infrastructure.DriverSpecifications;
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
import java.time.LocalDate;
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
 * Driver use cases. Takes a {@link CompanyScope}, never a company id - see
 * {@code com.ebim.tms.masterdata.application.LocationService} for the contract this follows.
 *
 * <p>Normalization is this service's job and the database's CHECKs are the backstop: code,
 * document type/number, licence number and licence category are stored trimmed and upper case, so
 * the uniqueness constraints actually catch {@code "dni"} and {@code "DNI"} as one value rather
 * than admitting a second row for the same person. Names are trimmed and <em>not</em> upper-cased:
 * they are printed on a manifest a human reads.
 *
 * <p>Carrier lookups for a whole page cost one batched query, never one per row - the same
 * discipline {@code VehicleService} follows and for the same reason.
 */
@Service
public class DriverService {

    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("code", "firstName", "lastName", "licenseExpiresOn", "active", "createdAt", "updatedAt");

    private final DriverRepository driverRepository;
    private final CarrierRepository carrierRepository;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public DriverService(DriverRepository driverRepository, CarrierRepository carrierRepository,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder) {
        this.driverRepository = driverRepository;
        this.carrierRepository = carrierRepository;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public PageResponse<DriverView> list(CompanyScope scope, DriverFilter filter, PageQuery pageQuery) {
        LocalDate today = scope.today();
        var specification = DriverSpecifications.matching(scope.companyId(), filter.code(), filter.name(),
                filter.carrierId(), filter.licenseStatus(), today, filter.active());
        Page<Driver> page = driverRepository.findAll(specification, toPageable(pageQuery));
        List<Driver> drivers = page.getContent();

        Map<UUID, Carrier> carriersById = carriersOf(drivers, scope);
        List<DriverView> content = drivers.stream()
                .map(driver -> DriverView.from(driver, carriersById.get(driver.carrierId()), today))
                .toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public DriverView get(CompanyScope scope, UUID id) {
        Driver driver = find(scope, id);
        return toView(scope, driver, resolveCarrier(scope, driver.carrierId()));
    }

    @Transactional
    public DriverView create(CompanyScope scope, DriverRequest request) {
        Normalized values = Normalized.of(request);
        Carrier carrier = requireCarrierInScope(scope, request.carrierId());
        requireUnique(scope, values, null);

        UUID actorId = auditActorProvider.requireAppUserId();
        Driver driver = new Driver(scope.companyId(), values.code(), values.firstName(), values.lastName(),
                values.documentType(), values.documentNumber(), values.phone(), values.licenseNumber(),
                values.licenseCategory(), request.licenseExpiresOn(), request.carrierId(), actorId);
        Driver saved = saveOrConflict(scope, driver, values);
        auditRecorder.record(scope, AuditAggregateType.DRIVER, saved.id(), AuditAction.CREATE,
                Map.of("code", saved.code(), "documentNumber", saved.documentNumber()));
        return toView(scope, saved, carrier);
    }

    @Transactional
    public DriverView update(CompanyScope scope, UUID id, DriverRequest request) {
        Driver driver = find(scope, id);
        Normalized values = Normalized.of(request);
        Carrier carrier = requireCarrierInScope(scope, request.carrierId());
        requireUnique(scope, values, id);

        UUID actorId = auditActorProvider.requireAppUserId();
        driver.applyChanges(values.code(), values.firstName(), values.lastName(), values.documentType(),
                values.documentNumber(), values.phone(), values.licenseNumber(), values.licenseCategory(),
                request.licenseExpiresOn(), request.carrierId(), actorId);
        Driver saved = saveOrConflict(scope, driver, values);
        auditRecorder.record(scope, AuditAggregateType.DRIVER, saved.id(), AuditAction.UPDATE,
                Map.of("code", saved.code(), "documentNumber", saved.documentNumber()));
        return toView(scope, saved, carrier);
    }

    @Transactional
    public DriverView activate(CompanyScope scope, UUID id) {
        Driver driver = find(scope, id);
        driver.activate(auditActorProvider.requireAppUserId());
        Driver saved = driverRepository.save(driver);
        auditRecorder.record(scope, AuditAggregateType.DRIVER, saved.id(), AuditAction.ACTIVATE,
                Map.of("code", saved.code()));
        return toView(scope, saved, resolveCarrier(scope, saved.carrierId()));
    }

    /**
     * Retires a driver. Deliberately does <em>not</em> touch the trips they are already on: a
     * shipment that is out on the road keeps the driver it left with, because that is what
     * happened, and clearing the column would erase the only record of who was in the cab. What
     * deactivation does stop is being picked again - {@code DriverLookupPort.findAssignable}
     * filters on {@code active}, and {@code TripExecutionService} re-checks it before a dispatch.
     */
    @Transactional
    public DriverView deactivate(CompanyScope scope, UUID id) {
        Driver driver = find(scope, id);
        driver.deactivate(auditActorProvider.requireAppUserId());
        Driver saved = driverRepository.save(driver);
        auditRecorder.record(scope, AuditAggregateType.DRIVER, saved.id(), AuditAction.DEACTIVATE,
                Map.of("code", saved.code()));
        return toView(scope, saved, resolveCarrier(scope, saved.carrierId()));
    }

    private DriverView toView(CompanyScope scope, Driver driver, Carrier carrier) {
        return DriverView.from(driver, carrier, scope.today());
    }

    private Driver find(CompanyScope scope, UUID id) {
        return driverRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found."));
    }

    /** {@code null} carrierId is allowed (a company may employ its own drivers); a non-null one must resolve within this company. */
    private Carrier requireCarrierInScope(CompanyScope scope, UUID carrierId) {
        if (carrierId == null) {
            return null;
        }
        return carrierRepository.findByIdAndCompanyId(carrierId, scope.companyId())
                .orElseThrow(() -> new InvalidRequestException("carrierId does not reference a carrier in this company."));
    }

    private Carrier resolveCarrier(CompanyScope scope, UUID carrierId) {
        return carrierId == null
                ? null
                : carrierRepository.findByIdAndCompanyId(carrierId, scope.companyId()).orElse(null);
    }

    /** One batched lookup per page - see {@code VehicleService.loadByIds} for the same rule. */
    private Map<UUID, Carrier> carriersOf(List<Driver> drivers, CompanyScope scope) {
        Set<UUID> carrierIds = drivers.stream().map(Driver::carrierId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // A plain HashMap, not Map.of(): a driver with no carrier looks up a null key below, and
        // an immutable map throws on get(null) instead of returning null.
        Map<UUID, Carrier> byId = new HashMap<>();
        if (carrierIds.isEmpty()) {
            return byId;
        }
        for (Carrier carrier : carrierRepository.findByIdInAndCompanyId(carrierIds, scope.companyId())) {
            byId.put(carrier.id(), carrier);
        }
        return byId;
    }

    /**
     * The three uniqueness rules, checked ahead of the write so the caller gets a sentence naming
     * the value that collided rather than a constraint name. {@code excludedId} is the row being
     * edited, or null on create.
     */
    private void requireUnique(CompanyScope scope, Normalized values, UUID excludedId) {
        if (duplicateCode(scope, values.code(), excludedId)) {
            throw duplicateCodeError(values.code());
        }
        if (duplicateDocument(scope, values, excludedId)) {
            throw duplicateDocumentError(values);
        }
        if (duplicateLicense(scope, values.licenseNumber(), excludedId)) {
            throw duplicateLicenseError(values.licenseNumber());
        }
    }

    private boolean duplicateCode(CompanyScope scope, String code, UUID excludedId) {
        return excludedId == null
                ? driverRepository.existsByCompanyIdAndCode(scope.companyId(), code)
                : driverRepository.existsByCompanyIdAndCodeAndIdNot(scope.companyId(), code, excludedId);
    }

    private boolean duplicateDocument(CompanyScope scope, Normalized values, UUID excludedId) {
        return excludedId == null
                ? driverRepository.existsByCompanyIdAndDocumentTypeAndDocumentNumber(
                        scope.companyId(), values.documentType(), values.documentNumber())
                : driverRepository.existsByCompanyIdAndDocumentTypeAndDocumentNumberAndIdNot(
                        scope.companyId(), values.documentType(), values.documentNumber(), excludedId);
    }

    private boolean duplicateLicense(CompanyScope scope, String licenseNumber, UUID excludedId) {
        return excludedId == null
                ? driverRepository.existsByCompanyIdAndLicenseNumber(scope.companyId(), licenseNumber)
                : driverRepository.existsByCompanyIdAndLicenseNumberAndIdNot(
                        scope.companyId(), licenseNumber, excludedId);
    }

    /**
     * Three unique constraints can race here, so a violation is disambiguated by re-asking which
     * value is now actually taken instead of assuming it was the code - the same shape
     * {@code VehicleService.saveOrConflict} uses for its two.
     */
    private Driver saveOrConflict(CompanyScope scope, Driver driver, Normalized values) {
        try {
            return driverRepository.saveAndFlush(driver);
        } catch (DataIntegrityViolationException raced) {
            UUID id = driver.id();
            if (duplicateCode(scope, values.code(), id)) {
                throw duplicateCodeError(values.code());
            }
            if (duplicateDocument(scope, values, id)) {
                throw duplicateDocumentError(values);
            }
            throw duplicateLicenseError(values.licenseNumber());
        }
    }

    private static ConflictException duplicateCodeError(String code) {
        return new ConflictException("A driver with code '" + code + "' already exists in this company.");
    }

    private static ConflictException duplicateDocumentError(Normalized values) {
        return new ConflictException("A driver with document " + values.documentType() + " '"
                + values.documentNumber() + "' already exists in this company.");
    }

    private static ConflictException duplicateLicenseError(String licenseNumber) {
        return new ConflictException(
                "A driver with licence number '" + licenseNumber + "' already exists in this company.");
    }

    /**
     * The request's text fields after normalization, so create, update and the conflict re-check
     * all read the same values rather than each trimming and upper-casing their own copy.
     */
    private record Normalized(String code, String firstName, String lastName, String documentType,
            String documentNumber, String phone, String licenseNumber, String licenseCategory) {

        static Normalized of(DriverRequest request) {
            return new Normalized(upper(request.code()), request.firstName().trim(), request.lastName().trim(),
                    upper(request.documentType()), upper(request.documentNumber()), blankToNull(request.phone()),
                    upper(request.licenseNumber()), upper(blankToNull(request.licenseCategory())));
        }

        private static String upper(String value) {
            return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
        }

        private static String blankToNull(String value) {
            return (value == null || value.isBlank()) ? null : value.trim();
        }
    }

    /** Surname first by default: a driver list is read the way a manifest is. */
    private static Pageable toPageable(PageQuery pageQuery) {
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(SORTABLE_PROPERTIES);
        Sort sort = terms.isEmpty()
                ? Sort.by(Sort.Order.asc("lastName"), Sort.Order.asc("firstName"))
                : Sort.by(terms.stream()
                        .map(term -> new Sort.Order(
                                term.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, term.property()))
                        .toList());
        return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), sort);
    }
}
