package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link TripOrderAssignment}.
 *
 * <p>Two shapes deliberately coexist here: entity finders for the handful of rows one mutation
 * touches, and grouped <em>projections</em> for anything a screen renders. A planning board for a
 * 300-trip day reads {@link #loadByTripIds} once; it never materialises the assignments, and it
 * never touches an order line.
 */
public interface TripOrderAssignmentRepository extends JpaRepository<TripOrderAssignment, UUID> {

    List<TripOrderAssignment> findByTripIdAndStatusOrderByAssignedAtAsc(UUID tripId, AssignmentStatus status);

    Optional<TripOrderAssignment> findByTripIdAndOrderIdAndStatus(UUID tripId, UUID orderId, AssignmentStatus status);

    /**
     * The order's current open assignment, if any. The partial unique index
     * {@code uq_trip_order_assignment_open_whole_order} guarantees there is at most one while V1
     * only assigns whole orders, which is why this returns an {@link Optional} rather than a list.
     */
    Optional<TripOrderAssignment> findByOrderIdAndStatusAndWholeOrderTrue(UUID orderId, AssignmentStatus status);

    /**
     * Which of {@code orderIds} still have an open carrier other than {@code excludedTripId}: an
     * ACTIVE assignment on a trip that has not finished ({@code DRAFT} to {@code IN_TRANSIT}). A
     * split order closes out only with its last carrier (docs/domain/SPLIT_ORDER_EXECUTION.md, R2),
     * and this is how the carrier that is closing learns it is not the last. Company-scoped in the
     * query, like every other read here.
     */
    @Query("SELECT DISTINCT a.orderId FROM TripOrderAssignment a JOIN Trip t ON t.id = a.tripId "
            + "WHERE a.orderId IN :orderIds AND a.companyId = :companyId AND a.status = :status "
            + "AND a.tripId <> :excludedTripId AND t.status IN :openStatuses")
    List<UUID> findOrdersWithOtherOpenCarrier(@Param("orderIds") Collection<UUID> orderIds,
            @Param("companyId") UUID companyId, @Param("excludedTripId") UUID excludedTripId,
            @Param("status") AssignmentStatus status,
            @Param("openStatuses") Collection<com.ebim.tms.planning.domain.TripStatus> openStatuses);

    /**
     * Whether the order has an ACTIVE whole-order row on a trip that has already finished. That row
     * is history of a delivery attempt, not a plan: a failed or short order that was reopened
     * (ADR-009) is planned again from nothing, and this is how planning tells the two apart. The
     * finished row still holds V11's unique slot, so the re-attempt is stored as a share
     * ({@code whole_order = false}); the V37 ledger, not the index, is what serialises planners
     * racing for it.
     */
    @Query("SELECT COUNT(a) > 0 FROM TripOrderAssignment a JOIN Trip t ON t.id = a.tripId "
            + "WHERE a.orderId = :orderId AND a.status = com.ebim.tms.planning.domain.AssignmentStatus.ACTIVE "
            + "AND a.wholeOrder = true AND t.status = com.ebim.tms.planning.domain.TripStatus.COMPLETED")
    boolean existsWholeOnFinishedTrip(@Param("orderId") UUID orderId);

    /** The full history of one order, newest first - the audit answer to "where has this been planned?". */
    List<TripOrderAssignment> findByOrderIdOrderByAssignedAtDesc(UUID orderId);

    /**
     * The current load of one trip, computed in the database. Returns a row with zeros when the
     * trip carries nothing (a plain {@code SUM} over an empty set returns nulls, which
     * {@code COALESCE} turns into the honest "zero used", not "unknown").
     */
    @Query("SELECT COALESCE(SUM(a.assignedWeightKg), 0) AS weightKg, "
            + "COALESCE(SUM(a.assignedVolumeM3), 0) AS volumeM3, "
            + "COALESCE(SUM(a.assignedPallets), 0) AS pallets, "
            + "COUNT(a) AS orderCount "
            + "FROM TripOrderAssignment a WHERE a.tripId = :tripId AND a.status = :status")
    TripLoad loadByTripId(@Param("tripId") UUID tripId, @Param("status") AssignmentStatus status);

    /** The same aggregate for a whole board, in one grouped query instead of one query per trip. */
    @Query("SELECT a.tripId AS tripId, "
            + "COALESCE(SUM(a.assignedWeightKg), 0) AS weightKg, "
            + "COALESCE(SUM(a.assignedVolumeM3), 0) AS volumeM3, "
            + "COALESCE(SUM(a.assignedPallets), 0) AS pallets, "
            + "COUNT(a) AS orderCount "
            + "FROM TripOrderAssignment a WHERE a.tripId IN :tripIds AND a.status = :status GROUP BY a.tripId")
    List<TripLoadRow> loadByTripIds(
            @Param("tripIds") Collection<UUID> tripIds, @Param("status") AssignmentStatus status);

    /** Assigned-order counts for a page of planning runs, in one query. */
    @Query("SELECT t.planningRunId AS runId, COUNT(a) AS orderCount FROM TripOrderAssignment a "
            + "JOIN Trip t ON t.id = a.tripId "
            + "WHERE t.planningRunId IN :runIds AND a.status = :status GROUP BY t.planningRunId")
    List<RunOrderCount> countByPlanningRunIds(
            @Param("runIds") Collection<UUID> runIds, @Param("status") AssignmentStatus status);

    interface TripLoad {
        BigDecimal getWeightKg();

        BigDecimal getVolumeM3();

        BigDecimal getPallets();

        long getOrderCount();
    }

    interface TripLoadRow extends TripLoad {
        UUID getTripId();
    }

    interface RunOrderCount {
        UUID getRunId();

        long getOrderCount();
    }
}
