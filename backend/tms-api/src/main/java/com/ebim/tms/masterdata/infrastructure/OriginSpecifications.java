package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Origin;
import com.ebim.tms.masterdata.domain.OriginType;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composes the optional list filters for {@link OriginRepository#findAll(Specification,
 * org.springframework.data.domain.Pageable)}. {@code companyId} is folded in here rather than
 * left to the caller to remember, so a filtered query cannot be built without a company scope.
 */
public final class OriginSpecifications {

    private OriginSpecifications() {}

    public static Specification<Origin> matching(UUID companyId, String code, String name, String search,
            OriginType type, Boolean active) {
        Specification<Origin> specification =
                (root, query, cb) -> cb.equal(root.get("companyId"), companyId);

        if (code != null && !code.isBlank()) {
            String pattern = "%" + code.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("code")), pattern));
        }
        if (name != null && !name.isBlank()) {
            String pattern = "%" + name.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern));
        }
        if (search != null && !search.isBlank()) {
            // OR across code and name - the autocomplete term, see OriginFilter. Lower-cased on both
            // sides because `name` is not normalised the way `code` is.
            String pattern = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("code")), pattern),
                    cb.like(cb.lower(root.get("name")), pattern)));
        }
        if (type != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }
        if (active != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        return specification;
    }
}
