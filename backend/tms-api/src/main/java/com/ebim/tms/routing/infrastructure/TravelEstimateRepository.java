package com.ebim.tms.routing.infrastructure;

import com.ebim.tms.routing.domain.TravelEstimateRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The routing cache's persistence. Company-scoped in the query, never filtered after loading -
 * see {@code LocationRepository} for the rule every finder here follows.
 */
public interface TravelEstimateRepository extends JpaRepository<TravelEstimateRow, UUID> {

    /**
     * One leg, looked up on the database's own rounded grid.
     *
     * <p>Native because the grid columns are {@code GENERATED ALWAYS} and are deliberately not
     * mapped on the entity: reading them through JPA would mean mapping them, and mapping them
     * would invite writing them. Rounding here with the same {@code round(x, 4)} the generation
     * uses is what makes a lookup and an insert agree about what "the same point" means.
     */
    @Query(value = """
            SELECT * FROM tms.travel_estimate
            WHERE company_id = :companyId
              AND provider = :provider
              AND origin_key_lat = round(CAST(:originLat AS numeric), 4)
              AND origin_key_lon = round(CAST(:originLon AS numeric), 4)
              AND destination_key_lat = round(CAST(:destinationLat AS numeric), 4)
              AND destination_key_lon = round(CAST(:destinationLon AS numeric), 4)
            """, nativeQuery = true)
    Optional<TravelEstimateRow> findLeg(
            @Param("companyId") UUID companyId,
            @Param("provider") String provider,
            @Param("originLat") BigDecimal originLat,
            @Param("originLon") BigDecimal originLon,
            @Param("destinationLat") BigDecimal destinationLat,
            @Param("destinationLon") BigDecimal destinationLon);

    /**
     * Every cached leg of this company for one provider whose origin is among the given grid
     * points - the batched read a matrix needs.
     *
     * <p>Over-fetches on purpose: it filters by origin only and lets the caller pair up
     * destinations in memory. An N x M matrix has N distinct origins and at most N x M rows, so one
     * query bounded by the origins is cheaper than N queries or one query with N x M literal pairs -
     * and it keeps the SQL a fixed shape rather than one built by string concatenation per call.
     */
    @Query(value = """
            SELECT * FROM tms.travel_estimate
            WHERE company_id = :companyId
              AND provider = :provider
              AND (origin_key_lat, origin_key_lon) IN (
                  SELECT round(o.lat, 4), round(o.lon, 4)
                  FROM unnest(CAST(:originLats AS numeric[]), CAST(:originLons AS numeric[])) AS o(lat, lon))
            """, nativeQuery = true)
    List<TravelEstimateRow> findByOrigins(
            @Param("companyId") UUID companyId,
            @Param("provider") String provider,
            @Param("originLats") BigDecimal[] originLats,
            @Param("originLons") BigDecimal[] originLons);

    /**
     * Trims expired rows. The retention half of "this is a cache": without it the hottest table in
     * the schema grows forever, which is the trap V29's own DELETE grant exists to avoid.
     */
    @Modifying
    @Query("delete from TravelEstimateRow r where r.expiresAt < :before")
    int deleteExpired(@Param("before") OffsetDateTime before);

    List<TravelEstimateRow> findByCompanyIdAndIdIn(UUID companyId, Collection<UUID> ids);
}
