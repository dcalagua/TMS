package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.RouteStop;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link RouteStop}. {@code RouteStop} rows are only ever written through
 * {@code Route.replaceStops} (cascaded from {@link RouteRepository}); this repository exists so
 * {@link RouteService} can read stop counts for a whole page of routes in one query instead of
 * walking each route's lazily-loaded {@code stops} collection - the exact N+1 the step brief
 * asks list to avoid.
 */
public interface RouteStopRepository extends JpaRepository<RouteStop, UUID> {

    @Query("SELECT rs.route.id AS routeId, COUNT(rs) AS stopCount FROM RouteStop rs "
            + "WHERE rs.route.id IN :routeIds GROUP BY rs.route.id")
    List<RouteStopCount> countByRouteIds(@Param("routeIds") Collection<UUID> routeIds);

    interface RouteStopCount {
        UUID getRouteId();

        long getStopCount();
    }
}
