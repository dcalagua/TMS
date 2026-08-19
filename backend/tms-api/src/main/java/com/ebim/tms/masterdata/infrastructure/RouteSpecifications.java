package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Route;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Composes the optional list filters for {@link RouteRepository}. See {@code OriginSpecifications}. */
public final class RouteSpecifications {

    private RouteSpecifications() {}

    public static Specification<Route> matching(
            UUID companyId, String code, String name, UUID originId, UUID zoneId, Boolean active) {
        Specification<Route> specification = (root, query, cb) -> cb.equal(root.get("companyId"), companyId);

        if (code != null && !code.isBlank()) {
            String pattern = "%" + code.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("code")), pattern));
        }
        if (name != null && !name.isBlank()) {
            String pattern = "%" + name.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern));
        }
        if (originId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("originId"), originId));
        }
        if (zoneId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("zoneId"), zoneId));
        }
        if (active != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        return specification;
    }
}
