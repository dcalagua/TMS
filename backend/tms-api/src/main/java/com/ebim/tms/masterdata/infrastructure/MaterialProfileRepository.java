package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.MaterialProfile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** {@code tms.material_profile} (V59). Every query names the company; RLS repeats it (ADR-005). */
public interface MaterialProfileRepository
        extends JpaRepository<MaterialProfile, UUID>, JpaSpecificationExecutor<MaterialProfile> {

    Optional<MaterialProfile> findByIdAndCompanyId(UUID id, UUID companyId);

    Optional<MaterialProfile> findByCompanyIdAndMaterialCode(UUID companyId, String materialCode);

    /** The profiles an order's lines name, in one query - see {@code MaterialProfileLookupAdapter}. */
    List<MaterialProfile> findByCompanyIdAndMaterialCodeIn(UUID companyId, Collection<String> materialCodes);
}
