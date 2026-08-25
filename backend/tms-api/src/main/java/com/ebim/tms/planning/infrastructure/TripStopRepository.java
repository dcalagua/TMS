package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.StopExecutionStatus;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.domain.TripStop;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read-only access to {@link TripStop} rows that must be counted without loading them.
 *
 * <p>{@code TripStop} is owned by the {@link com.ebim.tms.planning.domain.Trip} aggregate and is
 * written only through {@code Trip.syncStops}/{@code Trip.reorderStops} - this repository exists
 * solely so the planning board can render "how many stops" for a page of trips in one grouped
 * query instead of triggering the lazy collection once per trip. It is the same shape as
 * {@code RouteStopRepository.countByRouteIds} and {@code TransportOrderLineRepository.countByOrderIds},
 * and deliberately exposes no mutating method: a stop is never created or deleted except by its
 * trip.
 */
public interface TripStopRepository extends JpaRepository<TripStop, UUID> {

    /**
     * Stop counts for a whole board in one query. Trips with no stop yet are simply absent from
     * the result, so callers default them to zero.
     */
    @Query("SELECT s.trip.id AS tripId, COUNT(s) AS stopCount FROM TripStop s "
            + "WHERE s.trip.id IN :tripIds GROUP BY s.trip.id")
    List<TripStopCount> countByTripIds(@Param("tripIds") Collection<UUID> tripIds);

    interface TripStopCount {
        UUID getTripId();

        long getStopCount();
    }

    // --- control tower ---------------------------------------------------------------------

    /**
     * Every stop of a page of trips, in one query, ordered so a caller can group them as they
     * arrive - what turns "3 of 7 stops done" and "next stop, due 14:00" into a board column
     * instead of one lazy collection per row.
     *
     * <p>{@code JOIN FETCH s.trip} rather than a plain join: {@link TripStop#tripId()} reads
     * through the association, and a to-one fetch is the one kind that pages correctly in SQL.
     */
    @Query("SELECT s FROM TripStop s JOIN FETCH s.trip t WHERE t.id IN :tripIds "
            + "ORDER BY t.id ASC, s.sequence ASC")
    List<TripStop> findByTripIds(@Param("tripIds") Collection<UUID> tripIds);

    /**
     * How many stops of the day's running trips nobody has resolved yet - the denominator behind
     * "what is still outstanding out there".
     *
     * <p>Restricted to trips that are {@code tripStatus} ({@code IN_TRANSIT} in every caller):
     * a confirmed trip's stops are all PENDING and none of them is outstanding in any operational
     * sense, because the vehicle has not left. Backed by
     * {@code ix_trip_stop_company_unresolved} (migration V27).
     */
    @Query("SELECT COUNT(s) FROM TripStop s WHERE s.companyId = :companyId "
            + "AND s.executionStatus IN :outstanding AND s.trip.id IN "
            + "(SELECT t.id FROM Trip t WHERE t.companyId = :companyId AND t.planningDate = :date "
            + "AND t.status = :tripStatus)")
    long countOutstandingForDay(@Param("companyId") UUID companyId, @Param("date") LocalDate date,
            @Param("tripStatus") TripStatus tripStatus,
            @Param("outstanding") Collection<StopExecutionStatus> outstanding);

    /**
     * The same set, narrowed to the stops whose service window has already closed - "which
     * deliveries are running late", counted rather than fetched.
     *
     * <p>{@code cutoff} is a {@link LocalTime} and not an instant, which is what makes this a
     * plain indexed comparison instead of a per-row time-zone conversion in SQL. It works because
     * every stop counted here belongs to the <em>same</em> planning date, so ordering by local
     * window time is ordering by absolute time: the caller resolves "what local time is it on that
     * date" once - now for today, end-of-day for a date already past, and no query at all for a
     * date still ahead - and passes the answer down. See {@code ControlTowerService.windowCutoff}.
     */
    @Query("SELECT COUNT(s) FROM TripStop s WHERE s.companyId = :companyId "
            + "AND s.executionStatus IN :outstanding AND s.serviceWindowEnd <= :cutoff AND s.trip.id IN "
            + "(SELECT t.id FROM Trip t WHERE t.companyId = :companyId AND t.planningDate = :date "
            + "AND t.status = :tripStatus)")
    long countOutstandingPastWindowForDay(@Param("companyId") UUID companyId, @Param("date") LocalDate date,
            @Param("tripStatus") TripStatus tripStatus,
            @Param("outstanding") Collection<StopExecutionStatus> outstanding,
            @Param("cutoff") LocalTime cutoff);

    /**
     * The outstanding stops of the day's running trips, most urgent first, capped by
     * {@code pageable} - the "which stops are late" panel.
     *
     * <p>Ordered by the planned window end, which within one planning date is the same order as
     * the absolute instant it resolves to, so the most overdue stop is first and the panel's cap
     * throws away the least urgent rows rather than arbitrary ones. A stop with no window sorts
     * last (PostgreSQL orders nulls last on ASC), which is where it belongs: no window means it
     * cannot be late.
     */
    @Query("SELECT s FROM TripStop s JOIN FETCH s.trip t WHERE s.companyId = :companyId "
            + "AND s.executionStatus IN :outstanding AND t.companyId = :companyId "
            + "AND t.planningDate = :date AND t.status = :tripStatus "
            + "ORDER BY s.serviceWindowEnd ASC, s.sequence ASC")
    List<TripStop> findOutstandingForDay(@Param("companyId") UUID companyId, @Param("date") LocalDate date,
            @Param("tripStatus") TripStatus tripStatus,
            @Param("outstanding") Collection<StopExecutionStatus> outstanding, Pageable pageable);

    // --- KPI report ------------------------------------------------------------------------

    /**
     * How the range's stops ended, and how many of them were served inside the window they were
     * promised - one statement behind the report's service section.
     *
     * <p><b>Why it is native, and why the time zone is a parameter.</b> A stop stores a window as a
     * bare {@link java.time.LocalTime} with no date; the date is its trip's, and the zone is the
     * company's ({@code CompanyScope.zoneId}). Judging lateness therefore means resolving
     * {@code planning_date + service_window_end} in that zone and comparing it against a
     * {@code timestamptz} - which PostgreSQL does exactly, per row, with {@code AT TIME ZONE},
     * including across a daylight-saving change. The control tower avoids this by asking about one
     * date at a time and passing down a single local cutoff ({@code countOutstandingPastWindowForDay});
     * that trick does not survive a range, because two dates in a range can sit on either side of a
     * clock change and no single cutoff is true for both.
     *
     * <p>Doing it in Java instead would mean reading every stop of a quarter - hundreds of
     * thousands of rows - to produce two numbers.
     *
     * <p><b>Measured, never assumed</b>, exactly as departures are: {@code windowsMeasured} counts
     * only stops that carry both an arrival and a promised window, so a stop nobody recorded and a
     * stop with no window sit outside the percentage rather than flattering it. Cancelled trips are
     * excluded because they were never served.
     */
    @Query(value = """
            SELECT COUNT(*)                                                            AS "stops",
                   COUNT(*) FILTER (WHERE s.execution_status = 'COMPLETED')            AS "stopsCompleted",
                   COUNT(*) FILTER (WHERE s.execution_status = 'SKIPPED')              AS "stopsSkipped",
                   COUNT(*) FILTER (WHERE s.execution_status = 'FAILED')               AS "stopsFailed",
                   COUNT(*) FILTER (WHERE s.actual_arrival_at IS NOT NULL
                                      AND s.service_window_end IS NOT NULL)            AS "windowsMeasured",
                   COUNT(*) FILTER (WHERE s.actual_arrival_at IS NOT NULL
                                      AND s.service_window_end IS NOT NULL
                                      AND s.actual_arrival_at >
                                          ((t.planning_date + s.service_window_end)
                                              AT TIME ZONE CAST(:zone AS text)))       AS "windowsMissed"
              FROM tms.trip_stop s
              JOIN tms.trip t ON t.id = s.trip_id
             WHERE s.company_id = :companyId
               AND t.company_id = :companyId
               AND t.planning_date BETWEEN :from AND :to
               AND t.status <> 'CANCELLED'
            """, nativeQuery = true)
    StopServiceTotals serviceTotalsForRange(@Param("companyId") UUID companyId,
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("zone") String zone);

    /** The projection behind {@link #serviceTotalsForRange}; its aliases are quoted to keep their case. */
    interface StopServiceTotals {
        long getStops();

        long getStopsCompleted();

        long getStopsSkipped();

        long getStopsFailed();

        long getWindowsMeasured();

        long getWindowsMissed();
    }
}
