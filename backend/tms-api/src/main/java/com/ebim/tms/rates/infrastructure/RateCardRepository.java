package com.ebim.tms.rates.infrastructure;

import com.ebim.tms.rates.domain.RateCard;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Company-scoped persistence for {@link RateCard}. Every finder is scoped by {@code companyId} - no exceptions. */
public interface RateCardRepository extends JpaRepository<RateCard, UUID>, JpaSpecificationExecutor<RateCard> {

    Optional<RateCard> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);

    /**
     * Every active card this carrier has with this company - the candidate set
     * {@code RateCardSelector} ranks.
     *
     * <p>Filtered no further in SQL on purpose. Validity, scope and vehicle type are all decided
     * by {@code RateCardSelector} in one place so that "which agreement priced this shipment" has
     * a single, testable answer; splitting half the rule into a WHERE clause is how the SQL and
     * the ranking start to disagree. The candidate set is small by construction - one carrier's
     * live agreements, which is single digits in every installation this product is designed for -
     * so there is nothing to win by pushing it down.
     */
    List<RateCard> findByCompanyIdAndCarrierIdAndActiveTrue(UUID companyId, UUID carrierId);
}
