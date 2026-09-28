package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.ExternalDispatch;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link ExternalDispatch}. Every finder names the company (ADR-003, ADR-005). */
public interface ExternalDispatchRepository extends JpaRepository<ExternalDispatch, UUID> {

    /** The document's business identity (ADR-013 section 6). */
    Optional<ExternalDispatch> findByCompanyIdAndSourceSystemAndDispatchReferenceAndRevision(
            UUID companyId, String sourceSystem, String dispatchReference, int revision);

    /** The current (not superseded) revision of a document, if any. */
    Optional<ExternalDispatch> findByCompanyIdAndSourceSystemAndDispatchReferenceAndSupersededAtIsNull(
            UUID companyId, String sourceSystem, String dispatchReference);

    /** Every document received for one trip, newest first, superseded revisions included. */
    List<ExternalDispatch> findByCompanyIdAndTripIdOrderByReceivedAtDesc(UUID companyId, UUID tripId);

    /** The current documents of a set of trips, for a board: one query, not one per trip. */
    @Query("SELECT d FROM ExternalDispatch d WHERE d.companyId = :companyId AND d.tripId IN :tripIds "
            + "AND d.supersededAt IS NULL ORDER BY d.receivedAt DESC")
    List<ExternalDispatch> findCurrentByTripIds(@Param("companyId") UUID companyId,
            @Param("tripIds") Collection<UUID> tripIds);

    /** Current documents that matched no trip, newest first - the Control Tower's unmatched panel. */
    @Query("SELECT d FROM ExternalDispatch d WHERE d.companyId = :companyId AND d.tripId IS NULL "
            + "AND d.supersededAt IS NULL AND d.receivedAt >= :since ORDER BY d.receivedAt DESC")
    List<ExternalDispatch> findCurrentUnmatchedSince(@Param("companyId") UUID companyId,
            @Param("since") java.time.OffsetDateTime since);

    Optional<ExternalDispatch> findByIdAndCompanyId(UUID id, UUID companyId);
}
