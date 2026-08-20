package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Origin;
import java.util.Collection;
import java.util.List;
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

    /**
     * The batched sibling of {@link #findByIdAndCompanyId}, for resolving a page's worth of
     * references in one query. The company predicate is in the query rather than applied to the
     * result in Java: a row of another tenant must never be loaded at all, not merely discarded
     * after loading.
     */
    List<Origin> findByIdInAndCompanyId(Collection<UUID> ids, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);

    /**
     * The compatibility projection of one canonical {@code tms.location} (migration V14). The
     * company predicate is redundant here - {@code uq_origin_location} makes the link
     * one-to-one and {@code fk_origin_location_company} makes it same-company - but it is present
     * for the same reason every other finder carries one: a query in this package is read as
     * proof of tenant scoping, and an exception to that rule has to be re-proved every time it
     * is read.
     */
    Optional<Origin> findByLocationIdAndCompanyId(UUID locationId, UUID companyId);

    /** The batched sibling of {@link #findByLocationIdAndCompanyId}, one query per page. */
    List<Origin> findByLocationIdInAndCompanyId(Collection<UUID> locationIds, UUID companyId);
}
