package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.FreightClaimLine;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The lines of a freight claim (V71), always read and rewritten through their claim. */
public interface FreightClaimLineRepository extends JpaRepository<FreightClaimLine, UUID> {

    List<FreightClaimLine> findByCompanyIdAndFreightClaimIdOrderByLineNumberAsc(UUID companyId, UUID freightClaimId);

    List<FreightClaimLine> findByCompanyIdAndFreightClaimIdIn(UUID companyId, Collection<UUID> freightClaimIds);

    /** The first half of a rewrite; the caller flushes before inserting the new lines. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from FreightClaimLine l where l.companyId = :companyId and l.freightClaimId = :claimId")
    int deleteAllOfClaim(@Param("companyId") UUID companyId, @Param("claimId") UUID claimId);
}
