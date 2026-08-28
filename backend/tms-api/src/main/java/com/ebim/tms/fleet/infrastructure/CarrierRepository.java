package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.Carrier;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Company-scoped persistence for {@link Carrier}. Every finder is scoped by {@code companyId} - no exceptions. */
public interface CarrierRepository extends JpaRepository<Carrier, UUID>, JpaSpecificationExecutor<Carrier> {

    Optional<Carrier> findByIdAndCompanyId(UUID id, UUID companyId);

    /**
     * The batched sibling of {@link #findByIdAndCompanyId}, for resolving a page's worth of
     * references in one query. The company predicate is in the query rather than applied to the
     * result in Java: a row of another tenant must never be loaded at all, not merely discarded
     * after loading.
     */
    List<Carrier> findByIdInAndCompanyId(Collection<UUID> ids, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);

    boolean existsByCompanyIdAndTaxIdTypeAndTaxIdValue(UUID companyId, String taxIdType, String taxIdValue);

    boolean existsByCompanyIdAndTaxIdTypeAndTaxIdValueAndIdNot(
            UUID companyId, String taxIdType, String taxIdValue, UUID id);

    /** Which of a bulk import file's codes this company already holds - see {@code CarrierImportService}. */
    List<Carrier> findByCompanyIdAndCodeIn(UUID companyId, Collection<String> codes);

    /** Every active carrier of the company, by code - the tender ranking's candidate pool (V40). */
    List<Carrier> findByCompanyIdAndActiveTrueOrderByCodeAsc(UUID companyId);
}
