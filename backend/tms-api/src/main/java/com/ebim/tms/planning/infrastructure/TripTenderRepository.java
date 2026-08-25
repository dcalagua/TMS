package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.TenderStatus;
import com.ebim.tms.planning.domain.TripTender;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The tender attempts made on a trip (migration V31).
 *
 * <p>Company-scoped in every signature, for the reason {@link TransportEventRepository} gives.
 * There is no delete and no method that could produce one: a withdrawn offer is
 * {@code CANCELLED} with a reason and a rejected one stays rejected, because the history of who was
 * asked and what they said is the whole product.
 */
public interface TripTenderRepository extends JpaRepository<TripTender, UUID> {

    /** One trip's attempts, newest first - the order the workspace renders them in. */
    List<TripTender> findByCompanyIdAndTripIdOrderByAttemptDesc(UUID companyId, UUID tripId);

    Optional<TripTender> findByIdAndCompanyId(UUID id, UUID companyId);

    /**
     * The trip's live attempt, if it has one - at most one exists ({@code uq_trip_tender_live}).
     *
     * <p>"Live" here is the stored sense: a sent tender past its deadline is still returned, because
     * this is what {@code TripTenderService} calls in order to <em>resolve</em> that lapse. A caller
     * that wants "can the carrier still answer" asks {@code TripTender.awaitsResponseAt}.
     */
    @Query("""
            select t from TripTender t
            where t.companyId = :companyId and t.tripId = :tripId
              and t.status in (com.ebim.tms.planning.domain.TenderStatus.DRAFT,
                               com.ebim.tms.planning.domain.TenderStatus.SENT)
            """)
    Optional<TripTender> findLive(@Param("companyId") UUID companyId, @Param("tripId") UUID tripId);

    /**
     * The accepted attempt, if there is one - at most one exists, ever
     * ({@code uq_trip_tender_accepted}).
     */
    Optional<TripTender> findByCompanyIdAndTripIdAndStatus(UUID companyId, UUID tripId, TenderStatus status);

    /**
     * What one carrier has been offered and not yet answered - the carrier's own inbox, read by the
     * M2M endpoint.
     *
     * <p>Returns tenders that have lapsed as well, deliberately: the caller filters on
     * {@code awaitsResponseAt} so that "expired" is decided by one rule in one place rather than by
     * a {@code now()} inside a query that a test could not control.
     */
    List<TripTender> findByCompanyIdAndCarrierIdAndStatusOrderBySentAtAsc(
            UUID companyId, UUID carrierId, TenderStatus status);

    /** The highest attempt number used on this trip, or 0 - what the next attempt counts from. */
    @Query("select coalesce(max(t.attempt), 0) from TripTender t where t.tripId = :tripId")
    int maxAttempt(@Param("tripId") UUID tripId);

    // --- KPI report ------------------------------------------------------------------------

    /**
     * Every tender attempt made on the range's shipments, grouped by state - the acceptance and
     * rejection figures, in one statement.
     *
     * <p>Counts <em>attempts</em> and not shipments, which is the only honest denominator: a
     * shipment refused by two carriers and taken by a third is three attempts and one shipment, and
     * an acceptance rate over shipments would report it as 100%.
     *
     * <p>Dated by the trip's planning date rather than by {@code sentAt}, so a tender placed on
     * Friday for Monday's shipment is counted against Monday - the day the rest of the report is
     * about.
     *
     * <p><b>The stored state, not the effective one.</b> A {@code SENT} offer whose deadline has
     * passed and which nothing has resolved yet is counted as {@code SENT}, because that is what
     * the row says; expiry is applied when the tender is next touched, for the reason migration
     * V31 section 1b gives. The consequence is bounded and stated rather than papered over with a
     * {@code now()} inside the query: yesterday's unanswered offers can sit in {@code SENT} for a
     * while, so {@code EXPIRED} is a floor on how many lapsed and the acceptance rate - taken over
     * answered attempts only - is unaffected either way.
     */
    @Query("""
            SELECT te.status AS status, COUNT(te) AS tenderCount
              FROM TripTender te
              JOIN Trip t ON t.id = te.tripId
             WHERE te.companyId = :companyId
               AND t.companyId = :companyId
               AND t.planningDate BETWEEN :from AND :to
             GROUP BY te.status
            """)
    List<TenderStatusCount> countByStatusForRange(@Param("companyId") UUID companyId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    interface TenderStatusCount {
        TenderStatus getStatus();

        long getTenderCount();
    }
}
