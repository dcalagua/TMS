package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Frequency;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Composes the optional list filters for {@link FrequencyRepository}. See {@code OriginSpecifications}. */
public final class FrequencySpecifications {

    private FrequencySpecifications() {}

    public static Specification<Frequency> matching(UUID companyId, String code, String name, Boolean active) {
        Specification<Frequency> specification = (root, query, cb) -> cb.equal(root.get("companyId"), companyId);

        if (code != null && !code.isBlank()) {
            String pattern = "%" + code.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("code")), pattern));
        }
        if (name != null && !name.isBlank()) {
            String pattern = "%" + name.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern));
        }
        if (active != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        return specification;
    }
}
