package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.Carrier;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Composes the optional list filters for {@link CarrierRepository}. */
public final class CarrierSpecifications {

    private CarrierSpecifications() {}

    public static Specification<Carrier> matching(
            UUID companyId, String code, String businessName, String taxIdValue, Boolean active) {
        Specification<Carrier> specification = (root, query, cb) -> cb.equal(root.get("companyId"), companyId);

        if (code != null && !code.isBlank()) {
            String pattern = "%" + code.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("code")), pattern));
        }
        if (businessName != null && !businessName.isBlank()) {
            String pattern = "%" + businessName.trim().toLowerCase(Locale.ROOT) + "%";
            specification =
                    specification.and((root, query, cb) -> cb.like(cb.lower(root.get("businessName")), pattern));
        }
        if (taxIdValue != null && !taxIdValue.isBlank()) {
            String pattern = "%" + taxIdValue.trim().toLowerCase(Locale.ROOT) + "%";
            specification =
                    specification.and((root, query, cb) -> cb.like(cb.lower(root.get("taxIdValue")), pattern));
        }
        if (active != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        return specification;
    }
}
