package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.Carrier;
import com.ebim.tms.fleet.infrastructure.CarrierRepository;
import com.ebim.tms.fleet.infrastructure.CarrierSpecifications;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carrier use cases. Takes a {@link CompanyScope}, never a company id - see
 * {@code com.ebim.tms.masterdata.application.OriginService} for the contract this follows.
 */
@Service
public class CarrierService {

    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("code", "businessName", "active", "createdAt", "updatedAt");
    private static final Pattern EMAIL_SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+[.][^@\\s]+$");

    private final CarrierRepository carrierRepository;
    private final AuditActorProvider auditActorProvider;

    public CarrierService(CarrierRepository carrierRepository, AuditActorProvider auditActorProvider) {
        this.carrierRepository = carrierRepository;
        this.auditActorProvider = auditActorProvider;
    }

    @Transactional(readOnly = true)
    public PageResponse<CarrierView> list(CompanyScope scope, CarrierFilter filter, PageQuery pageQuery) {
        var specification = CarrierSpecifications.matching(
                scope.companyId(), filter.code(), filter.businessName(), filter.taxIdValue(), filter.active());
        Page<Carrier> page = carrierRepository.findAll(specification, toPageable(pageQuery));
        List<CarrierView> content = page.getContent().stream().map(CarrierView::from).toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public CarrierView get(CompanyScope scope, UUID id) {
        return CarrierView.from(find(scope, id));
    }

    @Transactional
    public CarrierView create(CompanyScope scope, CarrierRequest request) {
        String code = normalize(request.code());
        String taxIdType = normalize(request.taxIdType());
        String taxIdValue = normalize(request.taxIdValue());
        String email = normalizeEmail(request.email());
        validateEmail(email);
        if (carrierRepository.existsByCompanyIdAndCode(scope.companyId(), code)) {
            throw duplicateCode(code);
        }
        if (carrierRepository.existsByCompanyIdAndTaxIdTypeAndTaxIdValue(scope.companyId(), taxIdType, taxIdValue)) {
            throw duplicateTaxId(taxIdType, taxIdValue);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        Carrier carrier = new Carrier(scope.companyId(), code, request.businessName().trim(), taxIdType, taxIdValue,
                blankToNull(request.contactName()), blankToNull(request.phone()), email, actorId);
        return CarrierView.from(saveOrConflict(carrier, code));
    }

    @Transactional
    public CarrierView update(CompanyScope scope, UUID id, CarrierRequest request) {
        Carrier carrier = find(scope, id);
        String code = normalize(request.code());
        String taxIdType = normalize(request.taxIdType());
        String taxIdValue = normalize(request.taxIdValue());
        String email = normalizeEmail(request.email());
        validateEmail(email);
        if (carrierRepository.existsByCompanyIdAndCodeAndIdNot(scope.companyId(), code, id)) {
            throw duplicateCode(code);
        }
        if (carrierRepository.existsByCompanyIdAndTaxIdTypeAndTaxIdValueAndIdNot(
                scope.companyId(), taxIdType, taxIdValue, id)) {
            throw duplicateTaxId(taxIdType, taxIdValue);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        carrier.applyChanges(code, request.businessName().trim(), taxIdType, taxIdValue,
                blankToNull(request.contactName()), blankToNull(request.phone()), email, actorId);
        return CarrierView.from(saveOrConflict(carrier, code));
    }

    @Transactional
    public CarrierView activate(CompanyScope scope, UUID id) {
        Carrier carrier = find(scope, id);
        carrier.activate(auditActorProvider.requireAppUserId());
        return CarrierView.from(carrierRepository.save(carrier));
    }

    @Transactional
    public CarrierView deactivate(CompanyScope scope, UUID id) {
        Carrier carrier = find(scope, id);
        carrier.deactivate(auditActorProvider.requireAppUserId());
        return CarrierView.from(carrierRepository.save(carrier));
    }

    Carrier find(CompanyScope scope, UUID id) {
        return carrierRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Carrier not found."));
    }

    private Carrier saveOrConflict(Carrier carrier, String code) {
        try {
            return carrierRepository.saveAndFlush(carrier);
        } catch (DataIntegrityViolationException raced) {
            throw duplicateCode(code);
        }
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("A carrier with code '" + code + "' already exists in this company.");
    }

    private static ConflictException duplicateTaxId(String taxIdType, String taxIdValue) {
        return new ConflictException(
                "A carrier with tax identification '" + taxIdType + " " + taxIdValue
                        + "' already exists in this company.");
    }

    private static void validateEmail(String email) {
        if (email != null && !EMAIL_SHAPE.matcher(email).matches()) {
            throw new InvalidRequestException("email is not a valid address.");
        }
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String normalizeEmail(String value) {
        return (value == null || value.isBlank()) ? null : value.trim().toLowerCase(Locale.ROOT);
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
