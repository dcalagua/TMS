package com.ebim.tms.orders.infrastructure;

import com.ebim.tms.orders.domain.TrackingLink;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Company-scoped persistence for {@link TrackingLink} (migration V74, ADR-029). Every finder names the
 * company: a link id and an order id both come from a request.
 *
 * <p>The one cross-tenant question - "which company does this token hash belong to?" - is deliberately
 * <b>not</b> here. It lives in {@link TrackingTokenDirectory}, alone, so the single statement the
 * anonymous path runs on the owner connection is a class of its own and can be read in one sitting.
 */
public interface TrackingLinkRepository extends JpaRepository<TrackingLink, UUID> {

    Optional<TrackingLink> findByIdAndCompanyId(UUID id, UUID companyId);

    Optional<TrackingLink> findByIdAndOrderIdAndCompanyId(UUID id, UUID orderId, UUID companyId);

    List<TrackingLink> findByOrderIdAndCompanyIdOrderByCreatedAtDesc(UUID orderId, UUID companyId);

    /** Links of one order neither revoked nor past their hard cap - the per-order limit (ADR-029 T12). */
    @Query("""
            select count(l) from TrackingLink l
             where l.companyId = :companyId and l.orderId = :orderId
               and l.revokedAt is null and l.expiresAt > :now
            """)
    long countLive(@Param("orderId") UUID orderId, @Param("companyId") UUID companyId,
            @Param("now") OffsetDateTime now);

    /**
     * One more view, as one statement: no read-modify-write, no version bump, so concurrent views never
     * conflict with each other or with an office revocation. Touches only the two telemetry columns
     * {@code tms_app} may update for this purpose (V74 grants).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE tms.tracking_link
               SET view_count = view_count + 1, last_viewed_at = :at
             WHERE id = :id AND company_id = :companyId AND revoked_at IS NULL
            """, nativeQuery = true)
    int recordView(@Param("id") UUID id, @Param("companyId") UUID companyId, @Param("at") OffsetDateTime at);
}
