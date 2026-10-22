package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.TenderWaterfall;
import com.ebim.tms.planning.domain.WaterfallStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Company-scoped persistence for the tender waterfall (migration V40). */
public interface TenderWaterfallRepository extends JpaRepository<TenderWaterfall, UUID> {

    Optional<TenderWaterfall> findByIdAndCompanyId(UUID id, UUID companyId);

    Optional<TenderWaterfall> findByCompanyIdAndTripIdAndStatus(UUID companyId, UUID tripId,
            WaterfallStatus status);

    List<TenderWaterfall> findByCompanyIdAndTripIdOrderByStartedAtDesc(UUID companyId, UUID tripId);

    // Removed in JOB 15: findByIdForUpdate(UUID) took a waterfall by its own id with no company
    // predicate and had no callers - an unscoped own-id finder waiting for somebody to reach for
    // it, which is exactly the shape a cross-tenant read takes. TenantScopedRepositoryTest now
    // refuses to let one back in. If a locking read is ever needed, it takes the company too.

    // --- the automatic advance (ITEM 10, TenderWaterfallSweep) ------------------------------
    //
    // "Due" is one definition, written twice below because one statement crosses tenants and the
    // other does not: a running waterfall whose offer is out and past its deadline (stored SENT, or
    // already materialised EXPIRED while the candidate still reads OFFERED), or one with no offer
    // out at all - the state a rejection from a carrier's own system leaves it in, waiting at its
    // rank for somebody to send the next offer.

    /** Cross-tenant, for the scheduled sweep only: which companies have a waterfall due. */
    @Query(value = """
            SELECT DISTINCT w.company_id
              FROM tms.tender_waterfall w
             WHERE w.status = 'ACTIVE'
               AND (EXISTS (SELECT 1 FROM tms.tender_waterfall_candidate c
                              JOIN tms.trip_tender te ON te.id = c.tender_id AND te.company_id = c.company_id
                             WHERE c.waterfall_id = w.id AND c.status = 'OFFERED'
                               AND te.status IN ('SENT', 'EXPIRED') AND te.expires_at <= :now)
                    OR NOT EXISTS (SELECT 1 FROM tms.tender_waterfall_candidate c
                                    WHERE c.waterfall_id = w.id AND c.status = 'OFFERED'))
            """, nativeQuery = true)
    List<UUID> findCompaniesWithDueWaterfalls(@Param("now") OffsetDateTime now);

    /** The shipments of one company whose waterfall is due, oldest waterfall first, at most {@code limit}. */
    @Query(value = """
            SELECT w.trip_id
              FROM tms.tender_waterfall w
             WHERE w.company_id = :companyId
               AND w.status = 'ACTIVE'
               AND (EXISTS (SELECT 1 FROM tms.tender_waterfall_candidate c
                              JOIN tms.trip_tender te ON te.id = c.tender_id AND te.company_id = c.company_id
                             WHERE c.waterfall_id = w.id AND c.status = 'OFFERED'
                               AND te.status IN ('SENT', 'EXPIRED') AND te.expires_at <= :now)
                    OR NOT EXISTS (SELECT 1 FROM tms.tender_waterfall_candidate c
                                    WHERE c.waterfall_id = w.id AND c.status = 'OFFERED'))
             ORDER BY w.started_at, w.id
             LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findDueTripIds(@Param("companyId") UUID companyId, @Param("now") OffsetDateTime now,
            @Param("limit") int limit);

    /**
     * Takes the shipment's row lock - the one every tender and waterfall mutation serialises on - or
     * returns nothing at once when another transaction holds it. {@code SKIP LOCKED} is what lets two
     * nodes sweep side by side: the second passes over the shipment the first is advancing instead of
     * waiting for it and then finding nothing left to do.
     */
    @Query(value = """
            SELECT t.id FROM tms.trip t
             WHERE t.id = :tripId AND t.company_id = :companyId
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockTripIfFree(@Param("tripId") UUID tripId, @Param("companyId") UUID companyId);
}
