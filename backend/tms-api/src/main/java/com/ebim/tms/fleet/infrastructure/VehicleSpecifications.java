package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.Vehicle;
import com.ebim.tms.fleet.domain.VehicleAvailabilityStatus;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Composes the optional list filters for {@link VehicleRepository}. */
public final class VehicleSpecifications {

    private VehicleSpecifications() {}

    public static Specification<Vehicle> matching(UUID companyId, String code, String licensePlate, UUID carrierId,
            UUID vehicleTypeId, VehicleAvailabilityStatus availabilityStatus, Boolean active) {
        Specification<Vehicle> specification = (root, query, cb) -> cb.equal(root.get("companyId"), companyId);

        if (code != null && !code.isBlank()) {
            String pattern = "%" + code.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("code")), pattern));
        }
        if (licensePlate != null && !licensePlate.isBlank()) {
            String pattern = "%" + licensePlate.trim().toLowerCase(Locale.ROOT) + "%";
            specification =
                    specification.and((root, query, cb) -> cb.like(cb.lower(root.get("licensePlate")), pattern));
        }
        if (carrierId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("carrierId"), carrierId));
        }
        if (vehicleTypeId != null) {
            specification =
                    specification.and((root, query, cb) -> cb.equal(root.get("vehicleTypeId"), vehicleTypeId));
        }
        if (availabilityStatus != null) {
            specification =
                    specification.and((root, query, cb) -> cb.equal(root.get("availabilityStatus"), availabilityStatus));
        }
        if (active != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        return specification;
    }
}
