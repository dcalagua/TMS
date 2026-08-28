package com.ebim.tms.rates.infrastructure;

import com.ebim.tms.rates.domain.TripCost;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Company-scoped persistence for {@link TripCost}. Every finder is scoped by {@code companyId} - no exceptions. */
public interface TripCostRepository extends JpaRepository<TripCost, UUID> {

    /**
     * The one cost row of a trip, if it has one ({@code uq_trip_cost_trip}).
     *
     * <p>The only finder this module needs so far, and deliberately the only one here: a batched
     * {@code findByTripIdIn} sibling belongs with the first screen that shows a whole board's
     * costs, not written ahead of it against a guess about what that screen will want.
     */
    Optional<TripCost> findByTripIdAndCompanyId(UUID tripId, UUID companyId);

    /**
     * The batched sibling this file predicted, written now that freight settlement (V46) is the
     * screen that needs it: an invoice with twenty lines resolves its shipments in one query rather
     * than twenty.
     */
    List<TripCost> findByCompanyIdAndTripIdIn(UUID companyId, Collection<UUID> tripIds);

    /**
     * What a range of operating days cost, grouped by currency - the whole of
     * {@code TripCostAnalyticsPort}, in one statement that returns at most a handful of rows.
     *
     * <p>Rides {@code ix_trip_cost_company_planning_date} (migration V33), which is the reason the
     * range predicate is on this table's own column and not on a join into {@code tms.trip}.
     *
     * <p>Six sums rather than three because the comparable set is its own population: the first
     * four describe every row that has an estimate or an actual, and the last two describe only the
     * rows that have <em>both</em> and are therefore the only ones a variance may be computed over.
     * {@code TripCostTotals.variance} explains why subtracting the other two sums would produce a
     * confident, wrong number.
     *
     * <p>{@code COALESCE} on every sum, for the reason
     * {@code TripOrderAssignmentRepository.loadByTripId} gives: a {@code SUM} over an empty set is
     * null, and the honest reading of "no shipment in this currency carried an estimate" is zero
     * money and not unknown money - the count beside it is what says the set was empty.
     */
    @Query("""
            SELECT c.currency AS currency,
                   COUNT(c.estimatedAmount) AS tripsEstimated,
                   COALESCE(SUM(c.estimatedAmount), 0) AS estimatedAmount,
                   COUNT(c.actualAmount) AS tripsWithActual,
                   COALESCE(SUM(c.actualAmount), 0) AS actualAmount,
                   SUM(CASE WHEN c.estimatedAmount IS NOT NULL AND c.actualAmount IS NOT NULL
                            THEN 1 ELSE 0 END) AS tripsComparable,
                   COALESCE(SUM(CASE WHEN c.actualAmount IS NOT NULL
                                     THEN c.estimatedAmount ELSE 0 END), 0) AS comparableEstimated,
                   COALESCE(SUM(CASE WHEN c.estimatedAmount IS NOT NULL
                                     THEN c.actualAmount ELSE 0 END), 0) AS comparableActual
              FROM TripCost c
             WHERE c.companyId = :companyId
               AND c.planningDate BETWEEN :from AND :to
             GROUP BY c.currency
             ORDER BY c.currency
            """)
    List<TripCostTotalsRow> totalsByCurrency(@Param("companyId") UUID companyId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** The projection behind {@link #totalsByCurrency}; translated to a port type by the adapter. */
    interface TripCostTotalsRow {
        String getCurrency();

        long getTripsEstimated();

        BigDecimal getEstimatedAmount();

        long getTripsWithActual();

        BigDecimal getActualAmount();

        long getTripsComparable();

        BigDecimal getComparableEstimated();

        BigDecimal getComparableActual();
    }
}
