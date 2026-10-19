package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.GlAccountMapping;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** GL account mappings (V65). */
public interface GlAccountMappingRepository extends JpaRepository<GlAccountMapping, UUID> {

    Optional<GlAccountMapping> findByIdAndCompanyId(UUID id, UUID companyId);

    List<GlAccountMapping> findByCompanyIdOrderByConceptAscScopeTypeAscCreatedAtAsc(UUID companyId);

    List<GlAccountMapping> findByCompanyIdAndActiveTrue(UUID companyId);
}
