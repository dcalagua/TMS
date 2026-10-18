package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.FreightAccrual;
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

/**
 * Freight accruals (V65). Every finder names the company; the two cross-tenant questions the sweep
 * asks are native, named as such, and return ids rather than rows.
 */
public interface FreightAccrualRepository extends JpaRepository<FreightAccrual, UUID>,
        JpaSpecificationExecutor<FreightAccrual> {

    Optional<FreightAccrual> findByIdAndCompanyId(UUID id, UUID companyId);

    /** The serialisation point of every movement: approval, sweep and a person's reversal. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FreightAccrual a where a.id = :id and a.companyId = :companyId")
    Optional<FreightAccrual> findByIdAndCompanyIdForUpdate(@Param("id") UUID id, @Param("companyId") UUID companyId);

    /**
     * Serialises the birth of a trip's accrual with the approvals that consume it, until the end of
     * the caller's transaction. A row lock cannot do it: when the sweep is inserting the accrual and an
     * approval is looking for it, there is no committed row yet for either to lock, and under READ
     * COMMITTED each would miss the other - the accrual born after its invoice was approved, never
     * consumed. Keyed on the trip, so different trips never wait for each other.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:lockName, 0))", nativeQuery = true)
    Integer lockTripForAccrual(@Param("lockName") String lockName);

    /** The advisory lock key of one trip's accrual. */
    static String tripLockName(UUID companyId, UUID tripId) {
        return "tms.freight_accrual:" + companyId + ":" + tripId;
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FreightAccrual a where a.companyId = :companyId and a.tripId = :tripId"
            + " and a.currency = :currency")
    Optional<FreightAccrual> findForUpdate(@Param("companyId") UUID companyId, @Param("tripId") UUID tripId,
            @Param("currency") String currency);

    List<FreightAccrual> findByCompanyIdAndIdIn(UUID companyId, Collection<UUID> ids);

    List<FreightAccrual> findByCompanyIdAndTripIdIn(UUID companyId, Collection<UUID> tripIds);

    @Query("select distinct a.tripId from FreightAccrual a where a.companyId = :companyId and a.tripId in :tripIds")
    List<UUID> findAccruedTripIds(@Param("companyId") UUID companyId, @Param("tripIds") Collection<UUID> tripIds);

    /**
     * Claims a batch of open accruals for the sweep's adjustment pass. {@code SKIP LOCKED}: a second
     * node, or a person approving an invoice on one of them, is passed over rather than waited for,
     * and picked up by the next pass. Keyset on id so one pass walks every open accrual once.
     */
    @Query(value = """
            SELECT * FROM tms.freight_accrual
             WHERE company_id = :companyId
               AND status = 'OPEN'
               AND id > :afterId
             ORDER BY id
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<FreightAccrual> claimOpen(@Param("companyId") UUID companyId, @Param("afterId") UUID afterId,
            @Param("limit") int limit);

    /** Cross-tenant, for the scheduled sweep only: whom to visit for adjustments. */
    @Query(value = "SELECT DISTINCT company_id FROM tms.freight_accrual WHERE status = 'OPEN'", nativeQuery = true)
    List<UUID> findCompaniesWithOpenAccruals();
}
