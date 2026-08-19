package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Frequency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Company-scoped persistence for {@link Frequency}. See {@code OriginRepository} for the
 * isolation rule every finder here follows.
 */
public interface FrequencyRepository extends JpaRepository<Frequency, UUID>, JpaSpecificationExecutor<Frequency> {

    Optional<Frequency> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);
}
