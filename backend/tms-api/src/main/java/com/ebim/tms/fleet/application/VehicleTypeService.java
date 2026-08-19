package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.VehicleType;
import com.ebim.tms.fleet.infrastructure.VehicleTypeRepository;
import com.ebim.tms.fleet.infrastructure.VehicleTypeSpecifications;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
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
 * Vehicle type use cases. Takes a {@link CompanyScope}, never a company id - see
 * {@code com.ebim.tms.masterdata.application.OriginService} for the contract this follows.
 */
@Service
public class VehicleTypeService {

    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("code", "name", "maxWeightKg", "maxVolumeM3", "active", "createdAt", "updatedAt");

    private final VehicleTypeRepository vehicleTypeRepository;
    private final AuditActorProvider auditActorProvider;

    public VehicleTypeService(VehicleTypeRepository vehicleTypeRepository, AuditActorProvider auditActorProvider) {
        this.vehicleTypeRepository = vehicleTypeRepository;
        this.auditActorProvider = auditActorProvider;
    }

    @Transactional(readOnly = true)
    public PageResponse<VehicleTypeView> list(CompanyScope scope, VehicleTypeFilter filter, PageQuery pageQuery) {
        var specification = VehicleTypeSpecifications.matching(
                scope.companyId(), filter.code(), filter.name(), filter.bodyType(), filter.active());
        Page<VehicleType> page = vehicleTypeRepository.findAll(specification, toPageable(pageQuery));
        List<VehicleTypeView> content = page.getContent().stream().map(VehicleTypeView::from).toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public VehicleTypeView get(CompanyScope scope, UUID id) {
        return VehicleTypeView.from(find(scope, id));
    }

    @Transactional
    public VehicleTypeView create(CompanyScope scope, VehicleTypeRequest request) {
        String code = normalizeCode(request.code());
        validateTemperatureRange(request);
        if (vehicleTypeRepository.existsByCompanyIdAndCode(scope.companyId(), code)) {
            throw duplicateCode(code);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        VehicleType type = new VehicleType(scope.companyId(), code, request.name().trim(), request.maxWeightKg(),
                request.maxVolumeM3(), request.maxPallets(), request.lengthM(), request.widthM(), request.heightM(),
                request.bodyType(), request.temperatureControlled(), request.minTemperatureCelsius(),
                request.maxTemperatureCelsius(), request.axles(), actorId);
        return VehicleTypeView.from(saveOrConflict(type, code));
    }

    @Transactional
    public VehicleTypeView update(CompanyScope scope, UUID id, VehicleTypeRequest request) {
        VehicleType type = find(scope, id);
        String code = normalizeCode(request.code());
        validateTemperatureRange(request);
        if (vehicleTypeRepository.existsByCompanyIdAndCodeAndIdNot(scope.companyId(), code, id)) {
            throw duplicateCode(code);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        type.applyChanges(code, request.name().trim(), request.maxWeightKg(), request.maxVolumeM3(),
                request.maxPallets(), request.lengthM(), request.widthM(), request.heightM(), request.bodyType(),
                request.temperatureControlled(), request.minTemperatureCelsius(), request.maxTemperatureCelsius(),
                request.axles(), actorId);
        return VehicleTypeView.from(saveOrConflict(type, code));
    }

    @Transactional
    public VehicleTypeView activate(CompanyScope scope, UUID id) {
        VehicleType type = find(scope, id);
        type.activate(auditActorProvider.requireAppUserId());
        return VehicleTypeView.from(vehicleTypeRepository.save(type));
    }

    @Transactional
    public VehicleTypeView deactivate(CompanyScope scope, UUID id) {
        VehicleType type = find(scope, id);
        type.deactivate(auditActorProvider.requireAppUserId());
        return VehicleTypeView.from(vehicleTypeRepository.save(type));
    }

    VehicleType find(CompanyScope scope, UUID id) {
        return vehicleTypeRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle type not found."));
    }

    /** Mirrors {@code ck_vehicle_type_temperature_requires_controlled}/{@code _range} (V9) as a clean 400. */
    private static void validateTemperatureRange(VehicleTypeRequest request) {
        BigDecimal min = request.minTemperatureCelsius();
        BigDecimal max = request.maxTemperatureCelsius();
        if (!request.temperatureControlled() && (min != null || max != null)) {
            throw new InvalidRequestException(
                    "minTemperatureCelsius/maxTemperatureCelsius require temperatureControlled to be true.");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new InvalidRequestException("minTemperatureCelsius must not be greater than maxTemperatureCelsius.");
        }
    }

    private VehicleType saveOrConflict(VehicleType type, String code) {
        try {
            return vehicleTypeRepository.saveAndFlush(type);
        } catch (DataIntegrityViolationException raced) {
            throw duplicateCode(code);
        }
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("A vehicle type with code '" + code + "' already exists in this company.");
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
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
