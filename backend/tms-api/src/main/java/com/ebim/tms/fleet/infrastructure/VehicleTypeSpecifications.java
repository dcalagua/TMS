package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.VehicleBodyType;
import com.ebim.tms.fleet.domain.VehicleType;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Composes the optional list filters for {@link VehicleTypeRepository}. */
public final class VehicleTypeSpecifications {

    private VehicleTypeSpecifications() {}

    public static Specification<VehicleType> matching(
            UUID companyId, String code, String name, VehicleBodyType bodyType, Boolean active) {
        Specification<VehicleType> specification = (root, query, cb) -> cb.equal(root.get("companyId"), companyId);

        if (code != null && !code.isBlank()) {
            String pattern = "%" + code.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("code")), pattern));
        }
        if (name != null && !name.isBlank()) {
            String pattern = "%" + name.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern));
        }
        if (bodyType != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("bodyType"), bodyType));
        }
        if (active != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        return specification;
    }
}
