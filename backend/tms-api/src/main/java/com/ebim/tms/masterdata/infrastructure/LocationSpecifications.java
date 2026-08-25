package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Location;
import com.ebim.tms.masterdata.domain.LocationRole;
import com.ebim.tms.masterdata.domain.LocationType;
import jakarta.persistence.criteria.JoinType;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Composes the optional list filters for {@link LocationRepository}. */
public final class LocationSpecifications {

    private LocationSpecifications() {}

    public static Specification<Location> matching(UUID companyId, String search, LocationType type,
            LocationRole role, UUID zoneId, Boolean active) {
        Specification<Location> specification =
                (root, query, cb) -> cb.equal(root.get("companyId"), companyId);

        if (search != null && !search.isBlank()) {
            // One box over code, name and external reference: a planner looking for a store
            // types whichever of the three they happen to remember, not "the code field".
            String pattern = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
            // A NULL external reference yields NULL from LIKE, which is not TRUE, so a location
            // without one simply does not match on that branch - no COALESCE needed.
            specification = specification.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("code")), pattern),
                    cb.like(cb.lower(root.get("name")), pattern),
                    cb.like(cb.lower(root.get("externalReference")), pattern)));
        }
        if (type != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }
        if (role != null) {
            specification = specification.and((root, query, cb) -> {
                // distinct(): a location holding several roles would otherwise appear once per
                // joined row and corrupt both the page content and its total.
                if (query != null) {
                    query.distinct(true);
                }
                return cb.equal(root.join("roles", JoinType.INNER).get("role"), role);
            });
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
