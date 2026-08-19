package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Frequency;
import com.ebim.tms.masterdata.domain.Origin;
import com.ebim.tms.masterdata.domain.Route;
import com.ebim.tms.masterdata.domain.Zone;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The list-row view of a {@link Route}: origin/zone/frequency names and a stop <em>count</em>,
 * never the full stop/destination list. Deliberately a different shape from
 * {@link RouteDetailView} - the step brief is explicit that a list of routes must not load every
 * destination for each row (N+1), so the count comes from one batched {@code GROUP BY} query
 * ({@code RouteStopRepository.countByRouteIds}) instead of walking each route's stop collection.
 */
public record RouteView(
        UUID id,
        String code,
        String name,
        UUID originId,
        String originCode,
        String originName,
        UUID zoneId,
        String zoneCode,
        String zoneName,
        UUID frequencyId,
        String frequencyCode,
        String frequencyName,
        BigDecimal referenceDistanceKm,
        Integer referenceDurationMinutes,
        int stopCount,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static RouteView from(Route route, Origin origin, Zone zone, Frequency frequency, long stopCount) {
        return new RouteView(route.id(), route.code(), route.name(), route.originId(),
                origin == null ? null : origin.code(), origin == null ? null : origin.name(), route.zoneId(),
                zone == null ? null : zone.code(), zone == null ? null : zone.name(), route.frequencyId(),
                frequency == null ? null : frequency.code(), frequency == null ? null : frequency.name(),
                route.referenceDistanceKm(), route.referenceDurationMinutes(), (int) stopCount, route.active(),
                route.createdAt(), route.updatedAt());
    }
}
