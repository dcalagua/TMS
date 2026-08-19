package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Destination;
import com.ebim.tms.masterdata.domain.DestinationType;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Composes the optional list filters for {@link DestinationRepository}. See {@code OriginSpecifications}. */
public final class DestinationSpecifications {

    private DestinationSpecifications() {}

    public static Specification<Destination> matching(UUID companyId, String code, String name,
            DestinationType type, UUID zoneId, Boolean active) {
        Specification<Destination> specification =
                (root, query, cb) -> cb.equal(root.get("companyId"), companyId);

        if (code != null && !code.isBlank()) {
            String pattern = "%" + code.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("code")), pattern));
        }
        if (name != null && !name.isBlank()) {
            String pattern = "%" + name.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern));
        }
        if (type != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("type"), type));
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
