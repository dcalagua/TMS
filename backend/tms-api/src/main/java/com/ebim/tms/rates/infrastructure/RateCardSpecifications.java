package com.ebim.tms.rates.infrastructure;

import com.ebim.tms.rates.domain.RateCard;
import com.ebim.tms.rates.domain.RateCardScope;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Composes the optional list filters for {@link RateCardRepository}. */
public final class RateCardSpecifications {

    private RateCardSpecifications() {}

    /**
     * @param onDate when set, keeps only the cards in force that day - the "show me what applies
     *     today" filter the Rates screen opens with. Both bounds inclusive, and an open-ended card
     *     ({@code validTo} null) always passes the upper one.
     */
    public static Specification<RateCard> matching(UUID companyId, String code, String name, UUID carrierId,
            RateCardScope scope, UUID vehicleTypeId, String currency, LocalDate onDate, Boolean active) {
        Specification<RateCard> specification = (root, query, cb) -> cb.equal(root.get("companyId"), companyId);

        if (code != null && !code.isBlank()) {
            String pattern = "%" + code.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("code")), pattern));
        }
        if (name != null && !name.isBlank()) {
            String pattern = "%" + name.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern));
        }
        if (carrierId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("carrierId"), carrierId));
        }
        if (scope != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("scope"), scope));
        }
        if (vehicleTypeId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("vehicleTypeId"), vehicleTypeId));
        }
        if (currency != null && !currency.isBlank()) {
            String value = currency.trim().toUpperCase(Locale.ROOT);
            specification = specification.and((root, query, cb) -> cb.equal(root.get("currency"), value));
        }
        if (onDate != null) {
            specification = specification.and((root, query, cb) -> cb.and(
                    cb.lessThanOrEqualTo(root.get("validFrom"), onDate),
                    cb.or(cb.isNull(root.get("validTo")), cb.greaterThanOrEqualTo(root.get("validTo"), onDate))));
        }
        if (active != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        return specification;
    }
}
