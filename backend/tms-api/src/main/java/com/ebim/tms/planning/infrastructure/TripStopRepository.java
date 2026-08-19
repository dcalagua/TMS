package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.TripStop;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
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
}
