package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.Carrier;
import com.ebim.tms.fleet.domain.Vehicle;
import com.ebim.tms.fleet.domain.VehicleAvailabilityStatus;
import com.ebim.tms.fleet.domain.VehicleType;
import com.ebim.tms.fleet.infrastructure.CarrierRepository;
import com.ebim.tms.fleet.infrastructure.VehicleRepository;
import com.ebim.tms.fleet.infrastructure.VehicleTypeRepository;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fleet's implementation of {@link VehicleLookupPort}: the only way another business module -
 * {@code planning}, today - sees a vehicle and its effective capacity.
 *
 * <p>Lives in {@code application} rather than {@code infrastructure} (unlike
 * {@code masterdata.infrastructure.OriginLookupAdapter}) because it applies a fleet <em>rule</em>
 * and not just a repository translation: {@link EffectiveCapacityResolver} decides which of the
 * vehicle's override and its type's default actually applies, and that decision must exist in
 * exactly one place ({@code docs/architecture/OWNERSHIP_MATRIX.md}, "Capacity checks").
 *
 * <p>Both methods batch their lookups: a page of trips resolves its vehicles, types and carriers
 * in three queries, never three per row - the same N+1 discipline {@code RouteService.loadByIds}
 * established.
 */
@Service
public class VehicleLookupService implements VehicleLookupPort {

    private final VehicleRepository vehicleRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final CarrierRepository carrierRepository;
    private final EffectiveCapacityResolver effectiveCapacityResolver;

    public VehicleLookupService(VehicleRepository vehicleRepository, VehicleTypeRepository vehicleTypeRepository,
            CarrierRepository carrierRepository, EffectiveCapacityResolver effectiveCapacityResolver) {
        this.vehicleRepository = vehicleRepository;
        this.vehicleTypeRepository = vehicleTypeRepository;
        this.carrierRepository = carrierRepository;
        this.effectiveCapacityResolver = effectiveCapacityResolver;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VehicleCapacityReference> findAssignable(UUID vehicleId, UUID companyId) {
        return vehicleRepository.findByIdAndCompanyId(vehicleId, companyId)
                .filter(Vehicle::active)
                .filter(vehicle -> vehicle.availabilityStatus() == VehicleAvailabilityStatus.AVAILABLE)
                .map(vehicle -> toReference(vehicle, requireType(vehicle, companyId), carrierName(vehicle, companyId)));
    }

    /**
     * The whole assignable fleet, resolved in the same three batched queries a page of trips
     * costs. Sorted heaviest-first here rather than in the database because the sort key is the
     * <em>effective</em> capacity - the vehicle's override where it has one, its type's default
     * otherwise - and that resolution is a fleet rule, not a column.
     */
    @Override
    @Transactional(readOnly = true)
    public List<VehicleCapacityReference> findAssignableInCompany(UUID companyId) {
        List<Vehicle> vehicles = vehicleRepository.findByCompanyIdAndActiveTrueAndAvailabilityStatus(
                companyId, VehicleAvailabilityStatus.AVAILABLE);
        if (vehicles.isEmpty()) {
            return List.of();
        }
        return toReferences(vehicles, companyId).values().stream()
                .sorted(Comparator
                        .comparing(VehicleCapacityReference::maxWeightKg,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(VehicleCapacityReference::maxVolumeM3,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(VehicleCapacityReference::code))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, VehicleCapacityReference> findAllInCompany(Set<UUID> ids, UUID companyId) {
        Map<UUID, VehicleCapacityReference> byId = new HashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }

        List<Vehicle> vehicles = vehicleRepository.findByIdInAndCompanyId(ids, companyId);
        if (vehicles.isEmpty()) {
            return byId;
        }
        return toReferences(vehicles, companyId);
    }

    /** Resolves a batch of vehicles into references: three queries for the batch, never three per row. */
    private Map<UUID, VehicleCapacityReference> toReferences(List<Vehicle> vehicles, UUID companyId) {
        Map<UUID, VehicleCapacityReference> byId = new HashMap<>();
        Map<UUID, VehicleType> types = vehicleTypeRepository
                .findByIdInAndCompanyId(vehicles.stream().map(Vehicle::vehicleTypeId).collect(Collectors.toSet()),
                        companyId).stream()
                .collect(Collectors.toMap(VehicleType::id, type -> type));
        Set<UUID> carrierIds =
                vehicles.stream().map(Vehicle::carrierId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> carrierNames = carrierIds.isEmpty()
                ? Map.of()
                : carrierRepository.findByIdInAndCompanyId(carrierIds, companyId).stream()
                        .collect(Collectors.toMap(Carrier::id, Carrier::businessName));

        for (Vehicle vehicle : vehicles) {
            VehicleType type = types.get(vehicle.vehicleTypeId());
            if (type == null) {
                // Impossible while fk_vehicle_type holds; skipping rather than throwing keeps a
                // read-only display from failing on a row it only needed to render.
                continue;
            }
            byId.put(vehicle.id(), toReference(vehicle, type,
                    vehicle.carrierId() == null ? null : carrierNames.get(vehicle.carrierId())));
        }
        return byId;
    }

    private VehicleType requireType(Vehicle vehicle, UUID companyId) {
        return vehicleTypeRepository.findByIdAndCompanyId(vehicle.vehicleTypeId(), companyId)
                .orElseThrow(() -> new IllegalStateException(
                        "vehicle " + vehicle.id() + " references a vehicle type outside its own company"));
    }

    private String carrierName(Vehicle vehicle, UUID companyId) {
        if (vehicle.carrierId() == null) {
            return null;
        }
        return carrierRepository.findByIdAndCompanyId(vehicle.carrierId(), companyId)
                .map(Carrier::businessName)
                .orElse(null);
    }

    private VehicleCapacityReference toReference(Vehicle vehicle, VehicleType type, String carrierName) {
        EffectiveCapacity capacity = effectiveCapacityResolver.resolve(vehicle, type);
        return new VehicleCapacityReference(vehicle.id(), vehicle.code(), vehicle.licensePlate(), vehicle.carrierId(),
                carrierName, type.id(), type.code(), capacity.maxWeightKg(), capacity.maxVolumeM3(),
                capacity.maxPallets(), vehicle.active(), vehicle.availabilityStatus().name());
    }
}
