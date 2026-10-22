package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.ClaimStatus;
import com.ebim.tms.settlement.domain.FreightClaim;
import com.ebim.tms.settlement.domain.RecoveryMethod;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Company-scoped persistence for {@link FreightClaim} (V71). Every finder names the company. */
public interface FreightClaimRepository extends JpaRepository<FreightClaim, UUID>,
        JpaSpecificationExecutor<FreightClaim> {

    Optional<FreightClaim> findByIdAndCompanyId(UUID id, UUID companyId);

    /** Every transition locks the row: two people deciding the same claim decide it once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from FreightClaim c where c.id = :id and c.companyId = :companyId")
    Optional<FreightClaim> findByIdAndCompanyIdForUpdate(@Param("id") UUID id, @Param("companyId") UUID companyId);

    /** The claims of one trip, newest first - the trip workspace and the "already claimed?" hint. */
    List<FreightClaim> findByCompanyIdAndTripIdOrderByOpenedAtDesc(UUID companyId, UUID tripId);

    /** The claims of several ids at once, for the invoice bridge. Absent ids are not this company's. */
    List<FreightClaim> findByCompanyIdAndIdIn(UUID companyId, Collection<UUID> ids);

    /**
     * Accepted claims of one carrier in one currency that are recovered on the invoice and not yet
     * filed - the candidates for a penalty line.
     */
    List<FreightClaim> findByCompanyIdAndCarrierIdAndCurrencyAndRecoveryMethodAndStatusInOrderByDecidedAtAsc(
            UUID companyId, UUID carrierId, String currency, RecoveryMethod recoveryMethod,
            Collection<ClaimStatus> statuses);

    /** The claims opened from these exceptions - the incident centre's "already claimed" column. */
    List<FreightClaim> findByCompanyIdAndTripExceptionIdIn(UUID companyId, Collection<UUID> tripExceptionIds);

    /** Installation-wide, like the shipment number (V19); formatted RC-000001 by the service. */
    @Query(value = "SELECT nextval('tms.freight_claim_number_seq')", nativeQuery = true)
    long nextClaimNumberValue();
}
