package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Company-scoped persistence for {@link Trip}. Every finder is scoped by {@code companyId} - no exceptions. */
public interface TripRepository extends JpaRepository<Trip, UUID> {

    Optional<Trip> findByIdAndCompanyId(UUID id, UUID companyId);

    /**
     * The serialization point of the whole module: every mutation that changes what a trip
     * carries takes this lock first, so two planners assigning different orders to the same trip
     * cannot both read "there is room" and then both write. {@code SELECT ... FOR UPDATE} on one
     * row - not a table lock, not a giant trigger, and short enough to hold for the length of one
     * assignment.
     *
     * <p>Callers that lock two trips (a move) must lock them in a deterministic order - see
     * {@code TripService.moveOrder}, which sorts by id - or two opposite moves would deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Trip t WHERE t.id = :id AND t.companyId = :companyId")
    Optional<Trip> findByIdAndCompanyIdForUpdate(@Param("id") UUID id, @Param("companyId") UUID companyId);

    /**
     * The same row lock, plus a version increment applied at once
     * ({@code SELECT ... FOR UPDATE} followed by {@code UPDATE trip SET version = version + 1}).
     *
     * <p>Used by the operations that change what a trip <em>carries</em> rather than what its own
     * row says - assigning, removing, moving an order, reordering stops. Those never dirty a trip
     * column, so without this the trip's version would stay put while its load changed underneath
     * every planner holding the board, and a stale vehicle change would be accepted as current.
     *
     * <p>Deliberately not used by {@code updateVehicle}/{@code cancel}: those compare the caller's
     * version against the persisted one first, and a lock that increments before that comparison
     * would make every such request conflict with itself.
     */
    @Lock(LockModeType.PESSIMISTIC_FORCE_INCREMENT)
    @Query("SELECT t FROM Trip t WHERE t.id = :id AND t.companyId = :companyId")
    Optional<Trip> findByIdAndCompanyIdForAssignment(@Param("id") UUID id, @Param("companyId") UUID companyId);

    List<Trip> findByPlanningRunIdOrderByTripNumberAsc(UUID planningRunId);

    /**
     * The next value of {@code tms.shipment_number_seq} - {@code TripService} formats it into
     * {@code shipment_number} (migration V19). A plain {@code nextval()} call, not an entity
     * read, so it never participates in optimistic locking or the persistence context - the same
     * shape as {@code PlanningRunRepository.nextPlanNumberValue}.
     */
    @Query(value = "SELECT nextval('tms.shipment_number_seq')", nativeQuery = true)
    long nextShipmentNumberValue();

    /**
     * The next trip number inside a run. Read while the run row is locked by the caller
     * ({@code PlanningRunService}/{@code TripService} hold the run's optimistic version and the
     * uniqueness constraint {@code uq_trip_run_number} is the backstop), so two concurrent
     * creates cannot both take the same number.
     */
    @Query("SELECT COALESCE(MAX(t.tripNumber), 0) FROM Trip t WHERE t.planningRunId = :planningRunId")
    int maxTripNumber(@Param("planningRunId") UUID planningRunId);

    long countByPlanningRunIdAndStatusNot(UUID planningRunId, TripStatus status);

    /**
     * Resolves a trip by its external identity rather than its internal id - the lookup key of
     * the outbound Shipment integration (job 08), where a partner never learns {@code trip.id}.
     * See {@code docs/domain/SHIPMENT_V2.md} on why {@code shipmentNumber} exists.
     */
    Optional<Trip> findByShipmentNumberAndCompanyId(String shipmentNumber, UUID companyId);

    /**
     * The publishable set for {@code ShipmentPublicationAdapter}: this company's trips in one of
     * {@code statuses}, touched at or after {@code updatedSince} (or all of them, when null),
     * oldest-touched first with {@code id} as the tie-breaker for a deterministic page boundary -
     * the same shape {@code ShipmentOutboxEventRepository.findPublishable} uses for the same
     * reason. {@code DRAFT} is never one of {@code statuses} in production use; the caller
     * decides that, not this query, so a test can still ask for it explicitly.
     */
    @Query("SELECT t FROM Trip t WHERE t.companyId = :companyId AND t.status IN :statuses "
            + "AND (:updatedSince IS NULL OR t.updatedAt >= :updatedSince) "
            + "ORDER BY t.updatedAt ASC, t.id ASC")
    Page<Trip> findPublishable(@Param("companyId") UUID companyId, @Param("statuses") Collection<TripStatus> statuses,
            @Param("updatedSince") OffsetDateTime updatedSince, Pageable pageable);

    /**
     * The double-booking pre-check ({@code TripService.requireVehicleNotDoubleBooked}): is this
     * vehicle already on another non-cancelled trip the same planning date? The database's own
     * copy of the same rule is {@code uq_trip_vehicle_active_planning_date} (migration V16) - this
     * is the caller-facing check that runs first, the index is the concurrency backstop.
     */
    boolean existsByCompanyIdAndVehicleIdAndPlanningDateAndStatusNotAndIdNot(
            UUID companyId, UUID vehicleId, LocalDate planningDate, TripStatus excludedStatus, UUID excludedTripId);

    /**
     * Trip counts for a whole page of planning runs in one grouped query - never one count per
     * run row (the N+1 discipline {@code RouteService.loadByIds} established).
     */
    @Query("SELECT t.planningRunId AS runId, COUNT(t) AS tripCount FROM Trip t "
            + "WHERE t.planningRunId IN :runIds AND t.status <> :excludedStatus GROUP BY t.planningRunId")
    List<TripCount> countByPlanningRunIds(
            @Param("runIds") Collection<UUID> runIds, @Param("excludedStatus") TripStatus excludedStatus);

    interface TripCount {
        UUID getRunId();

        long getTripCount();
    }
}
