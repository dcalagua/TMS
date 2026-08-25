package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.TripException;
import com.ebim.tms.planning.domain.TripExceptionStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The operational problems raised against a trip (migration V27).
 *
 * <p>Company-scoped in every signature, for the reason {@link TransportEventRepository} gives.
 * There is no delete: an exception that turned out to be a mistake is resolved with a note saying
 * so, which is a record of the mistake rather than an erasure of it - the same "deletes never
 * erase history" rule the whole schema follows.
 */
public interface TripExceptionRepository extends JpaRepository<TripException, UUID> {

    /** Newest first: the most recent problem is the one a dispatcher is dealing with. */
    List<TripException> findByCompanyIdAndTripIdOrderByReportedAtDesc(UUID companyId, UUID tripId);

    Optional<TripException> findByIdAndCompanyId(UUID id, UUID companyId);

    // --- control tower ---------------------------------------------------------------------
    //
    // The day's problems, reached from the day rather than from one trip. The subquery on trip is
    // what makes "today" mean anything here: an exception carries the instant it was reported, not
    // the operating day it belongs to, and a problem reported at 00:40 on a shipment that left
    // yesterday evening is yesterday's problem. Filtering on reported_at would put it in the wrong
    // day exactly when a night shift is reading the screen.

    /**
     * How many problems of the day's trips are still open - the "is anybody sitting on something"
     * number. Rides {@code ix_trip_exception_company_open} (migration V27), which is partial on
     * OPEN precisely so this stays a small index scan as resolved history accumulates.
     */
    @Query("SELECT COUNT(e) FROM TripException e WHERE e.companyId = :companyId AND e.status = :status "
            + "AND e.tripId IN (SELECT t.id FROM Trip t WHERE t.companyId = :companyId "
            + "AND t.planningDate = :date)")
    long countByStatusForDay(@Param("companyId") UUID companyId, @Param("status") TripExceptionStatus status,
            @Param("date") LocalDate date);

    /**
     * The same set as rows, newest first and capped by {@code pageable} - the exceptions panel.
     * Newest first because a problem reported two minutes ago is the one nobody has looked at yet;
     * the count above tells the screen how many did not fit.
     */
    @Query("SELECT e FROM TripException e WHERE e.companyId = :companyId AND e.status = :status "
            + "AND e.tripId IN (SELECT t.id FROM Trip t WHERE t.companyId = :companyId "
            + "AND t.planningDate = :date) ORDER BY e.reportedAt DESC")
    List<TripException> findByStatusForDay(@Param("companyId") UUID companyId,
            @Param("status") TripExceptionStatus status, @Param("date") LocalDate date, Pageable pageable);

    /**
     * Open-problem counts for a whole page of trips in one grouped query - the board column that
     * says which shipment to open first. Trips with nothing open are absent from the result, so
     * callers default them to zero.
     */
    @Query("SELECT e.tripId AS tripId, COUNT(e) AS exceptionCount FROM TripException e "
            + "WHERE e.companyId = :companyId AND e.status = :status AND e.tripId IN :tripIds "
            + "GROUP BY e.tripId")
    List<TripExceptionCount> countByStatusAndTripIds(@Param("companyId") UUID companyId,
            @Param("status") TripExceptionStatus status, @Param("tripIds") Collection<UUID> tripIds);

    interface TripExceptionCount {
        UUID getTripId();

        long getExceptionCount();
    }

    // --- KPI report ------------------------------------------------------------------------

    /**
     * The problems raised against each operating day of a range, grouped by whether they are still
     * open - one statement behind the report's exception rate and the daily series.
     *
     * <p>Dated by the trip's planning date for the reason the day queries above give, stated once
     * more because it is the whole reason this is a join and not a range on {@code reportedAt}: an
     * exception carries the instant it was reported, and a problem reported at 00:40 on a shipment
     * that left yesterday evening is yesterday's problem.
     *
     * <p>Grouped by status rather than by type: the report's question is "how much went wrong, and
     * how much of it is still open", and a breakdown by {@code TripExceptionType} is a table
     * nobody asked for on a screen that already has two.
     */
    @Query("""
            SELECT t.planningDate AS planningDate, e.status AS status, COUNT(e) AS exceptionCount
              FROM TripException e
              JOIN Trip t ON t.id = e.tripId
             WHERE e.companyId = :companyId
               AND t.companyId = :companyId
               AND t.planningDate BETWEEN :from AND :to
             GROUP BY t.planningDate, e.status
             ORDER BY t.planningDate
            """)
    List<ExceptionDailyCount> countDailyByStatusForRange(@Param("companyId") UUID companyId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    interface ExceptionDailyCount {
        LocalDate getPlanningDate();

        TripExceptionStatus getStatus();

        long getExceptionCount();
    }
}
