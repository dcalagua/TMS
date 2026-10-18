package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.MaterialProfile;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class MaterialProfileSpecifications {

    private MaterialProfileSpecifications() {}

    /** The company's profiles whose code or description contains {@code search}, case-insensitively. */
    public static Specification<MaterialProfile> matching(UUID companyId, String search) {
        Specification<MaterialProfile> specification =
                (root, query, cb) -> cb.equal(root.get("companyId"), companyId);
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("materialCode")), pattern),
                    cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern)));
        }
        return specification;
    }
}
