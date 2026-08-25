package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.Carrier;
import com.ebim.tms.fleet.domain.Vehicle;
import com.ebim.tms.fleet.domain.VehicleType;
import com.ebim.tms.fleet.infrastructure.CarrierRepository;
import com.ebim.tms.fleet.infrastructure.VehicleRepository;
import com.ebim.tms.fleet.infrastructure.VehicleSpecifications;
import com.ebim.tms.fleet.infrastructure.VehicleTypeRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vehicle use cases. Takes a {@link CompanyScope}, never a company id - see
 * {@code com.ebim.tms.masterdata.application.LocationService} for the contract this follows.
 *
 * <p>Every view is built with the vehicle's carrier/type resolved and its effective capacity
 * computed via {@link EffectiveCapacityResolver}; list resolves both relations for the whole
 * page with one batched lookup each ({@link #loadByIds}), never one query per row - the same
 * discipline {@code RouteService} (masterdata module) established, duplicated here rather than
 * shared because fleet must not depend on masterdata (module boundary, see
 * {@code ModuleBoundaryTest}).
 */
@Service
public class VehicleService {

    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("code", "licensePlate", "availabilityStatus", "active", "createdAt", "updatedAt");

    private final VehicleRepository vehicleRepository;
    private final CarrierRepository carrierRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final EffectiveCapacityResolver capacityResolver;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public VehicleService(VehicleRepository vehicleRepository, CarrierRepository carrierRepository,
            VehicleTypeRepository vehicleTypeRepository, EffectiveCapacityResolver capacityResolver,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder) {
        this.vehicleRepository = vehicleRepository;
        this.carrierRepository = carrierRepository;
        this.vehicleTypeRepository = vehicleTypeRepository;
        this.capacityResolver = capacityResolver;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public PageResponse<VehicleView> list(CompanyScope scope, VehicleFilter filter, PageQuery pageQuery) {
        var specification = VehicleSpecifications.matching(scope.companyId(), filter.code(), filter.licensePlate(),
                filter.carrierId(), filter.vehicleTypeId(), filter.availabilityStatus(), filter.active());
        Page<Vehicle> page = vehicleRepository.findAll(specification, toPageable(pageQuery));
        List<Vehicle> vehicles = page.getContent();

        Map<UUID, Carrier> carriersById = loadByIds(vehicles.stream().map(Vehicle::carrierId), scope,
                carrierRepository::findAllById, Carrier::id, Carrier::companyId);
        Map<UUID, VehicleType> typesById = loadByIds(vehicles.stream().map(Vehicle::vehicleTypeId), scope,
                vehicleTypeRepository::findAllById, VehicleType::id, VehicleType::companyId);

        List<VehicleView> content = vehicles.stream()
                .map(vehicle -> toView(vehicle, carriersById.get(vehicle.carrierId()),
                        typesById.get(vehicle.vehicleTypeId())))
                .toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public VehicleView get(CompanyScope scope, UUID id) {
        Vehicle vehicle = find(scope, id);
        return toView(vehicle, resolveCarrier(scope, vehicle.carrierId()), resolveType(scope, vehicle.vehicleTypeId()));
    }

    @Transactional
    public VehicleView create(CompanyScope scope, VehicleRequest request) {
        String code = normalizeCode(request.code());
        String licensePlate = normalizePlate(request.licensePlate());
        Carrier carrier = requireCarrierInScope(scope, request.carrierId());
        VehicleType type = requireVehicleTypeInScope(scope, request.vehicleTypeId());
        if (vehicleRepository.existsByCompanyIdAndCode(scope.companyId(), code)) {
            throw duplicateCode(code);
        }
        if (vehicleRepository.existsByCompanyIdAndLicensePlate(scope.companyId(), licensePlate)) {
            throw duplicatePlate(licensePlate);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        Vehicle vehicle = new Vehicle(scope.companyId(), code, licensePlate, request.carrierId(),
                request.vehicleTypeId(), request.maxWeightOverrideKg(), request.maxVolumeOverrideM3(),
                request.maxPalletsOverride(), request.availabilityStatus(), blankToNull(request.externalReference()),
                actorId);
        Vehicle saved = saveOrConflict(vehicle, code, licensePlate);
        auditRecorder.record(scope, AuditAggregateType.VEHICLE, saved.id(), AuditAction.CREATE,
                Map.of("code", saved.code(), "licensePlate", saved.licensePlate()));
        return toView(saved, carrier, type);
    }

    @Transactional
    public VehicleView update(CompanyScope scope, UUID id, VehicleRequest request) {
        Vehicle vehicle = find(scope, id);
        String code = normalizeCode(request.code());
        String licensePlate = normalizePlate(request.licensePlate());
        Carrier carrier = requireCarrierInScope(scope, request.carrierId());
        VehicleType type = requireVehicleTypeInScope(scope, request.vehicleTypeId());
        if (vehicleRepository.existsByCompanyIdAndCodeAndIdNot(scope.companyId(), code, id)) {
            throw duplicateCode(code);
        }
        if (vehicleRepository.existsByCompanyIdAndLicensePlateAndIdNot(scope.companyId(), licensePlate, id)) {
            throw duplicatePlate(licensePlate);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        vehicle.applyChanges(code, licensePlate, request.carrierId(), request.vehicleTypeId(),
                request.maxWeightOverrideKg(), request.maxVolumeOverrideM3(), request.maxPalletsOverride(),
                request.availabilityStatus(), blankToNull(request.externalReference()), actorId);
        Vehicle saved = saveOrConflict(vehicle, code, licensePlate);
        auditRecorder.record(scope, AuditAggregateType.VEHICLE, saved.id(), AuditAction.UPDATE,
                Map.of("code", saved.code(), "licensePlate", saved.licensePlate()));
        return toView(saved, carrier, type);
    }

    @Transactional
    public VehicleView activate(CompanyScope scope, UUID id) {
        Vehicle vehicle = find(scope, id);
        vehicle.activate(auditActorProvider.requireAppUserId());
        Vehicle saved = vehicleRepository.save(vehicle);
        auditRecorder.record(scope, AuditAggregateType.VEHICLE, saved.id(), AuditAction.ACTIVATE,
                Map.of("code", saved.code()));
        return toView(saved, resolveCarrier(scope, saved.carrierId()), resolveType(scope, saved.vehicleTypeId()));
    }

    @Transactional
    public VehicleView deactivate(CompanyScope scope, UUID id) {
        Vehicle vehicle = find(scope, id);
        vehicle.deactivate(auditActorProvider.requireAppUserId());
        Vehicle saved = vehicleRepository.save(vehicle);
        auditRecorder.record(scope, AuditAggregateType.VEHICLE, saved.id(), AuditAction.DEACTIVATE,
                Map.of("code", saved.code()));
        return toView(saved, resolveCarrier(scope, saved.carrierId()), resolveType(scope, saved.vehicleTypeId()));
    }

    private VehicleView toView(Vehicle vehicle, Carrier carrier, VehicleType type) {
        EffectiveCapacity capacity = capacityResolver.resolve(vehicle, type);
        return VehicleView.from(vehicle, carrier, type, capacity);
    }

    private Vehicle find(CompanyScope scope, UUID id) {
        return vehicleRepository.findByIdAndCompanyId(id, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found."));
    }

    /** {@code null} carrierId is allowed (a company may run its own owned fleet); a non-null one must resolve within this company. */
    private Carrier requireCarrierInScope(CompanyScope scope, UUID carrierId) {
        if (carrierId == null) {
            return null;
        }
        return carrierRepository.findByIdAndCompanyId(carrierId, scope.companyId())
                .orElseThrow(() -> new InvalidRequestException("carrierId does not reference a carrier in this company."));
    }

    private VehicleType requireVehicleTypeInScope(CompanyScope scope, UUID vehicleTypeId) {
        return vehicleTypeRepository.findByIdAndCompanyId(vehicleTypeId, scope.companyId())
                .orElseThrow(() -> new InvalidRequestException(
                        "vehicleTypeId does not reference a vehicle type in this company."));
    }

    private Carrier resolveCarrier(CompanyScope scope, UUID carrierId) {
        return carrierId == null ? null : carrierRepository.findByIdAndCompanyId(carrierId, scope.companyId()).orElse(null);
    }

    private VehicleType resolveType(CompanyScope scope, UUID vehicleTypeId) {
        return vehicleTypeId == null
                ? null
                : vehicleTypeRepository.findByIdAndCompanyId(vehicleTypeId, scope.companyId()).orElse(null);
    }

    private interface BatchFinder<T> {
        Iterable<T> findAllById(Iterable<UUID> ids);
    }

    /** One batched lookup per page instead of one query per row - see {@code RouteService.loadByIds} (masterdata module). */
    private <T> Map<UUID, T> loadByIds(java.util.stream.Stream<UUID> ids, CompanyScope scope, BatchFinder<T> finder,
            Function<T, UUID> idOf, Function<T, UUID> companyIdOf) {
        Set<UUID> distinctIds = ids.filter(Objects::nonNull).collect(Collectors.toSet());
        // A plain HashMap, not Map.of(): a vehicle with no carrier looks up a null key below,
        // and Map.of()'s immutable map throws on get(null) instead of returning null.
        Map<UUID, T> byId = new HashMap<>();
        if (distinctIds.isEmpty()) {
            return byId;
        }
        for (T item : finder.findAllById(distinctIds)) {
            if (companyIdOf.apply(item).equals(scope.companyId())) {
                byId.put(idOf.apply(item), item);
            }
        }
        return byId;
    }

    /**
     * Two unique constraints can raise a race here (code, license plate) - unlike the
     * single-unique-column pattern {@code ZoneService}/{@code FrequencyService} follow, so the
     * conflict is disambiguated by re-checking which value is now actually duplicated, rather
     * than assuming it was the code.
     */
    private Vehicle saveOrConflict(Vehicle vehicle, String code, String licensePlate) {
        try {
            return vehicleRepository.saveAndFlush(vehicle);
        } catch (DataIntegrityViolationException raced) {
            UUID id = vehicle.id();
            boolean codeConflict = id == null
                    ? vehicleRepository.existsByCompanyIdAndCode(vehicle.companyId(), code)
                    : vehicleRepository.existsByCompanyIdAndCodeAndIdNot(vehicle.companyId(), code, id);
            if (codeConflict) {
                throw duplicateCode(code);
            }
            throw duplicatePlate(licensePlate);
        }
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("A vehicle with code '" + code + "' already exists in this company.");
    }

    private static ConflictException duplicatePlate(String licensePlate) {
        return new ConflictException(
                "A vehicle with license plate '" + licensePlate + "' already exists in this company.");
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizePlate(String licensePlate) {
        return licensePlate.trim().toUpperCase(Locale.ROOT);
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
