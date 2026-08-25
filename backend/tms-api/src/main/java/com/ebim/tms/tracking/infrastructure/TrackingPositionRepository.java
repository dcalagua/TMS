package com.ebim.tms.tracking.infrastructure;

import com.ebim.tms.tracking.domain.TrackingPosition;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Company-scoped persistence for {@link TrackingPosition}. Every finder is scoped by
 * {@code companyId}, without exception - the rule {@code TripRepository} states.
 *
 * <p>There is no {@code findAll}, no unbounded finder and no {@code Pageable} overload. This is the
 * largest table in the schema and every read of it here answers one of exactly two questions -
 * "where is this shipment now" and "where has it been recently" - both bounded, both served by
 * {@code ix_tracking_position_trip_recent}. A page-through API over a position feed would be a
 * feature nobody asked for and an easy way to pull a million rows through the application.
 */
public interface TrackingPositionRepository extends JpaRepository<TrackingPosition, UUID> {

    /**
     * The newest position of one shipment - the first row of the index, whatever reported it.
     * Deliberately not filtered by provider: a shipment tracked by two feeds has one current
     * position, and it is whichever measured most recently.
     */
    Optional<TrackingPosition> findFirstByCompanyIdAndTripIdOrderByOccurredAtDesc(UUID companyId, UUID tripId);

    /**
     * The recent trail, newest first and bounded by the caller. Newest first rather than oldest so
     * that the limit keeps the points that matter; the caller reverses for drawing.
     */
    List<TrackingPosition> findByCompanyIdAndTripIdOrderByOccurredAtDesc(UUID companyId, UUID tripId, Limit limit);

    /**
     * The newest {@code occurred_at} per feed for a set of shipments, in one query - what the
     * sampling and staleness rules are decided against.
     *
     * <p>A projection and not the entities: the intake path needs one timestamp per (trip,
     * provider) pair and loading whole rows to read one column of each is the kind of waste that
     * only shows up under the load this table is designed for.
     */
    @Query("SELECT p.tripId AS tripId, p.provider AS provider, MAX(p.occurredAt) AS latest "
            + "FROM TrackingPosition p "
            + "WHERE p.companyId = :companyId AND p.tripId IN :tripIds AND p.provider = :provider "
            + "GROUP BY p.tripId, p.provider")
    List<FeedWatermark> findWatermarks(@Param("companyId") UUID companyId,
            @Param("tripIds") Collection<UUID> tripIds, @Param("provider") String provider);

    /** How far one feed has got with one shipment: the newest position time it has reported. */
    interface FeedWatermark {

        UUID getTripId();

        String getProvider();

        OffsetDateTime getLatest();
    }
}
