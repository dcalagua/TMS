package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Origin;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Company-scoped persistence for {@link Origin}.
 *
 * <p>Every finder either takes {@code companyId} explicitly or is combined with a
 * {@code Specification} that does (see {@code OriginSpecifications}), so a query can never
 * silently reach across tenants. {@link JpaSpecificationExecutor} composes the optional list
 * filters without a hand-written query per filter combination.
 */
public interface OriginRepository extends JpaRepository<Origin, UUID>, JpaSpecificationExecutor<Origin> {

    Optional<Origin> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);
}
