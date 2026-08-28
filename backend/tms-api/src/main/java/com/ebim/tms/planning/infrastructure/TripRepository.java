package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Company-scoped persistence for {@link Trip}. Every finder is scoped by {@code companyId} - no
 * exceptions, including the {@link org.springframework.data.jpa.repository.JpaSpecificationExecutor}
 * half, whose only caller composes its specifications through {@link TripSpecifications#matching},
 * which starts from the company predicate and can then only narrow.
 */
public interface TripRepository extends JpaRepository<Trip, UUID>, JpaSpecificationExecutor<Trip> {

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
     * The same external lookup for a whole run of shipment numbers at once - what
     * {@code TripTrackingLookupAdapter} answers a batch of reported positions with. A run of
     * positions is routinely one shipment repeated two hundred times, so the distinct set is
     * small and resolving it per report would be two hundred queries for a handful of answers.
     */
    List<Trip> findByShipmentNumberInAndCompanyId(Collection<String> shipmentNumbers, UUID companyId);

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
     *
     * <p>Two forms rather than one with a nullable exclusion, and deliberately so. A caller that
     * is <em>editing</em> a trip has to exclude that trip or it would collide with itself; a
     * caller that is <em>creating</em> one has no id to exclude yet. Passing a freshly generated
     * random UUID for the second case reads as "exclude this trip" while meaning "exclude
     * nothing", and a reader has to reconstruct the argument that no random UUID can collide
     * before they can believe the line. The question is different, so the method is.
     */
    boolean existsByCompanyIdAndVehicleIdAndPlanningDateAndStatusNot(
            UUID companyId, UUID vehicleId, LocalDate planningDate, TripStatus excludedStatus);

    /** The same question asked from an existing trip, which must not collide with itself. */
    boolean existsByCompanyIdAndVehicleIdAndPlanningDateAndStatusNotAndIdNot(
            UUID companyId, UUID vehicleId, LocalDate planningDate, TripStatus excludedStatus, UUID excludedTripId);

    /**
     * The same pre-check for the driver ({@code TripService.requireDriverNotDoubleBooked}), backed
     * by {@code uq_trip_driver_active_planning_date} (migration V26). Separate from the vehicle
     * one rather than parameterised over a column name: a derived query is what makes the index
     * it mirrors legible, and the two rules can diverge (a truck could be re-crewed; a person
     * cannot be in two cabs).
     */
    boolean existsByCompanyIdAndDriverIdAndPlanningDateAndStatusNotAndIdNot(
            UUID companyId, UUID driverId, LocalDate planningDate, TripStatus excludedStatus, UUID excludedTripId);

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

    // --- control tower (read-only aggregates over one operating day) ----------------------
    //
    // Every query below is scoped to one company and one planning date and answers a KPI with a
    // count instead of a page of rows: the control tower refreshes on a timer, and a screen that
    // fetched the day's trips to count them would re-read the whole board every minute to render
    // six numbers. They all ride ix_trip_company_planning_date_status (migration V25).
    //
    // None of them takes the screen's origin/carrier filter. That is a product decision, not an
    // omission - see ControlTowerService: the KPI strip is the whole day's picture and only the
    // table below it narrows, so a filtered-out shipment can never hide a problem from the
    // headline numbers.

    /**
     * The day's trips grouped by state, in one query - "what leaves today, and how far along is
     * it". States with no trip are simply absent, so callers default them to zero rather than the
     * query padding six rows it did not find.
     */
    @Query("SELECT t.status AS status, COUNT(t) AS tripCount FROM Trip t "
            + "WHERE t.companyId = :companyId AND t.planningDate = :date GROUP BY t.status")
    List<TripStatusCount> countByStatusForDay(@Param("companyId") UUID companyId, @Param("date") LocalDate date);

    /**
     * Trips that left after the plan said they would - the measured half of "which ones are late"
     * ({@code DepartureDelay}).
     *
     * <p>Strictly {@code actual > planned}, with no tolerance: V1 has no grace period, for the
     * reason {@code DepartureDelay}'s header gives. The {@code IS NOT NULL} on the planned side is
     * redundant against SQL's null comparison and kept because the rule being expressed is "both
     * instants are on file", which is what a reader needs to see.
     */
    @Query("SELECT COUNT(t) FROM Trip t WHERE t.companyId = :companyId AND t.planningDate = :date "
            + "AND t.plannedDepartureAt IS NOT NULL AND t.actualDepartureAt > t.plannedDepartureAt")
    long countDepartedLateForDay(@Param("companyId") UUID companyId, @Param("date") LocalDate date);

    /**
     * Shipments agreed with a carrier that does not own the vehicle on them (V42, JOB 12).
     *
     * <p>These <b>cannot depart</b> - {@code ck_trip_departed_carrier_matches_vehicle} sees to that
     * - and until now nothing said so before a dispatcher tried. Restricted to the states where it
     * still matters: a cancelled shipment is not going anywhere anyway, and one already in transit
     * resolved it or never had it.
     */
    @Query("SELECT t FROM Trip t WHERE t.companyId = :companyId AND t.planningDate = :date "
            + "AND t.acceptedCarrierId IS NOT NULL AND t.acceptedCarrierId <> t.carrierId "
            + "AND t.status IN :statuses ORDER BY t.tripNumber")
    List<Trip> findAwaitingCarrierVehicleForDay(@Param("companyId") UUID companyId,
            @Param("date") LocalDate date, @Param("statuses") Collection<TripStatus> statuses);

    /**
     * Today's shipments that have not left yet, with a vehicle or a driver on them.
     *
     * <p>The candidate set for the availability check (V42): whether any of them is booked into the
     * workshop or has a driver off sick is a question for the fleet module, through
     * {@code ResourceAvailabilityPort}, and is asked per shipment rather than in SQL because the
     * blocks live in another module's table.
     */
    @Query("SELECT t FROM Trip t WHERE t.companyId = :companyId AND t.planningDate = :date "
            + "AND t.status IN :statuses AND (t.vehicleId IS NOT NULL OR t.driverId IS NOT NULL) "
            + "ORDER BY t.tripNumber")
    List<Trip> findAwaitingDepartureWithResourcesForDay(@Param("companyId") UUID companyId,
            @Param("date") LocalDate date, @Param("statuses") Collection<TripStatus> statuses);

    /**
     * Trips that were due to leave and still have not - the unmeasured half of "which ones are
     * late", and the one a dispatcher can still do something about.
     *
     * <p>{@code statuses} is always {@code DepartureDelay.AWAITING_DEPARTURE} in production use;
     * it is a parameter so this count and the per-row verdict cannot drift apart, not so a caller
     * can choose a different rule.
     */
    @Query("SELECT COUNT(t) FROM Trip t WHERE t.companyId = :companyId AND t.planningDate = :date "
            + "AND t.actualDepartureAt IS NULL AND t.plannedDepartureAt < :now AND t.status IN :statuses")
    long countOverdueDepartureForDay(@Param("companyId") UUID companyId, @Param("date") LocalDate date,
            @Param("now") OffsetDateTime now, @Param("statuses") Collection<TripStatus> statuses);

    /**
     * The day's trips in the given states, capped by {@code pageable} - what the "most loaded
     * vehicles" panel ranks and what the exception and stop panels resolve their shipment numbers
     * from.
     *
     * <p>Capacity utilisation cannot be ranked in SQL: a draft trip's limit lives on the vehicle
     * master, which planning reaches only through {@code VehicleLookupPort}
     * ({@code docs/domain/CAPACITY_MODEL.md}). So the rows are loaded and ranked in Java, and the
     * cap is what keeps that honest - the set is bounded by the fleet in normal use (one trip per
     * vehicle per day, migration V16), and the cap is the backstop for a day that is not normal.
     */
    List<Trip> findByCompanyIdAndPlanningDateAndStatusIn(
            UUID companyId, LocalDate planningDate, Collection<TripStatus> statuses, Pageable pageable);

    /**
     * Resolves a set of trips inside the company, for a panel that starts from something else -
     * an exception, a stop - and needs the shipment it belongs to. Company-scoped like every other
     * finder here, even though the rows that produced the ids were already scoped: a repository
     * method that could be called with an unscoped id set is one refactor away from being.
     */
    List<Trip> findByIdInAndCompanyId(Collection<UUID> ids, UUID companyId);

    interface TripStatusCount {
        TripStatus getStatus();

        long getTripCount();
    }

    // --- KPI report (read-only aggregates over a range of operating days) -----------------
    //
    // The control tower's queries above answer "today"; these answer "the last quarter", and the
    // difference is not only the predicate. A day's numbers may be recomputed from a handful of
    // rows; a quarter's may not, so everything below returns either one row or one row per
    // operating day - never a row per trip. See docs/domain/KPIS_REPORTING_V1.md for the formula
    // each figure feeds.
    //
    // The range is on planning_date, so these ride ix_trip_company_planning_date_status (V25) too.

    /**
     * The range's trips grouped by operating day <em>and</em> state, with the two departure counts
     * that day's punctuality is computed from - one statement behind the whole shipments section of
     * the report and the whole daily series.
     *
     * <p>Grouped by both rather than by date alone so the report has one source for two questions
     * ("how many shipments ran on the 14th" and "how many were cancelled all quarter"): the caller
     * sums across states for the first and across dates for the second, and the two can therefore
     * not disagree. Days with no trip are absent, which is what lets the caller tell an operating
     * day with nothing on it from one outside the range.
     *
     * <p><b>Punctuality is measured, never assumed.</b> {@code departuresMeasured} counts only
     * trips that carry <em>both</em> instants, because a shipment nobody recorded a departure for
     * is not an on-time departure and must not be able to flatter the percentage by sitting in the
     * denominator. {@code departuresLate} is strictly {@code actual > planned}, with no grace
     * period, for the reason {@code DepartureDelay}'s header gives - and both are computed here
     * rather than in Java so a quarter's worth of trips never leaves the database.
     */
    @Query("""
            SELECT t.planningDate AS planningDate,
                   t.status AS status,
                   COUNT(t) AS tripCount,
                   SUM(CASE WHEN t.plannedDepartureAt IS NOT NULL AND t.actualDepartureAt IS NOT NULL
                            THEN 1 ELSE 0 END) AS departuresMeasured,
                   SUM(CASE WHEN t.plannedDepartureAt IS NOT NULL
                             AND t.actualDepartureAt > t.plannedDepartureAt
                            THEN 1 ELSE 0 END) AS departuresLate
              FROM Trip t
             WHERE t.companyId = :companyId
               AND t.planningDate BETWEEN :from AND :to
             GROUP BY t.planningDate, t.status
             ORDER BY t.planningDate
            """)
    List<TripDailyStatusCount> countDailyByStatusForRange(@Param("companyId") UUID companyId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    interface TripDailyStatusCount {
        LocalDate getPlanningDate();

        TripStatus getStatus();

        long getTripCount();

        long getDeparturesMeasured();

        long getDeparturesLate();
    }

    /**
     * How full the range's shipments actually ran, in one statement: the assigned load and the
     * frozen limit, summed over the same population, once per capacity dimension.
     *
     * <p><b>Why it is native.</b> The two sums come from two tables at two grains - one row per
     * trip for the limits, one row per assignment for the load - so summing them in a single JPQL
     * join would multiply each trip's limit by the number of orders on it. The CTE aggregates the
     * assignments to trip grain first and then joins, which is the shape that makes the answer
     * right; JPQL has no {@code WITH}. The alternative is six round trips, or loading a quarter of a
     * fleet's trips to divide in Java.
     *
     * <p><b>What is counted, and why it is not everything.</b> Only trips that carry a capacity
     * snapshot - confirmed or beyond - and only those not cancelled. A draft trip's limit lives on
     * the vehicle master, which {@code planning} may reach only through {@code VehicleLookupPort}
     * ({@code docs/domain/CAPACITY_MODEL.md}), so including drafts would mean either a cross-module
     * join or a denominator with holes in it; a cancelled trip did not run at all. Per dimension,
     * a trip whose snapshot for that dimension is null (a vehicle that declares no limit) is left
     * out of <em>both</em> sums, so its load can never appear over a capacity that was never
     * stated. That is the aggregate form of the rule the control tower applies per trip: "we do not
     * know how full this is" is not zero percent.
     *
     * <p>Returns one row always, with zeros and a zero trip count when the range holds nothing.
     */
    @Query(value = """
            WITH scoped AS (
                SELECT t.id,
                       t.snapshot_max_weight_kg,
                       t.snapshot_max_volume_m3,
                       t.snapshot_max_pallets
                  FROM tms.trip t
                 WHERE t.company_id = :companyId
                   AND t.planning_date BETWEEN :from AND :to
                   AND t.status <> 'CANCELLED'
                   AND t.capacity_snapshot_at IS NOT NULL
            ), trip_load AS (
                SELECT a.trip_id,
                       SUM(a.assigned_weight_kg) AS weight_kg,
                       SUM(a.assigned_volume_m3) AS volume_m3,
                       SUM(a.assigned_pallets)   AS pallets
                  FROM tms.trip_order_assignment a
                  JOIN scoped s ON s.id = a.trip_id
                 WHERE a.company_id = :companyId
                   AND a.status = 'ACTIVE'
                 GROUP BY a.trip_id
            )
            SELECT COUNT(*)                                                       AS "trips",
                   COALESCE(SUM(s.snapshot_max_weight_kg), 0)                     AS "weightCapacity",
                   COALESCE(SUM(CASE WHEN s.snapshot_max_weight_kg IS NOT NULL
                                     THEN COALESCE(l.weight_kg, 0) ELSE 0 END), 0) AS "weightUsed",
                   COALESCE(SUM(s.snapshot_max_volume_m3), 0)                     AS "volumeCapacity",
                   COALESCE(SUM(CASE WHEN s.snapshot_max_volume_m3 IS NOT NULL
                                     THEN COALESCE(l.volume_m3, 0) ELSE 0 END), 0) AS "volumeUsed",
                   -- Cast, unlike the two above it: snapshot_max_pallets is an integer column and
                   -- summing it yields bigint, while the load it is divided by is numeric. Making
                   -- both sides numeric here keeps the two projection getters the same type.
                   COALESCE(SUM(CAST(s.snapshot_max_pallets AS numeric)), 0)      AS "palletCapacity",
                   COALESCE(SUM(CASE WHEN s.snapshot_max_pallets IS NOT NULL
                                     THEN COALESCE(l.pallets, 0) ELSE 0 END), 0)  AS "palletsUsed"
              FROM scoped s
              LEFT JOIN trip_load l ON l.trip_id = s.id
            """, nativeQuery = true)
    TripUtilizationTotals utilizationForRange(@Param("companyId") UUID companyId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * The projection behind {@link #utilizationForRange}. The aliases in that query are quoted so
     * PostgreSQL preserves their case and each one matches a getter here exactly - an unquoted
     * alias is folded to lower case and would arrive as {@code weightcapacity}.
     */
    interface TripUtilizationTotals {
        long getTrips();

        BigDecimal getWeightCapacity();

        BigDecimal getWeightUsed();

        BigDecimal getVolumeCapacity();

        BigDecimal getVolumeUsed();

        BigDecimal getPalletCapacity();

        BigDecimal getPalletsUsed();
    }
}
