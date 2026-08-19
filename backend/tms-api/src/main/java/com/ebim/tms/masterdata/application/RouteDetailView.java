package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Destination;
import com.ebim.tms.masterdata.domain.Frequency;
import com.ebim.tms.masterdata.domain.Origin;
import com.ebim.tms.masterdata.domain.Route;
import com.ebim.tms.masterdata.domain.RouteStop;
import com.ebim.tms.masterdata.domain.Zone;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single-route view of a {@link Route}, including its ordered stops with the destination
 * each one resolves to. Returned by get/create/update/activate/deactivate - never by list, which
 * uses {@link RouteView} instead (see that record's class comment for why).
 *
 * @param stops already sorted by sequence by {@link Route#stops()}; every destination on the
 *     page was resolved by the caller in one batched lookup, the same discipline
 *     {@code DestinationService.loadZones} uses for zones.
 */
public record RouteDetailView(
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
        List<RouteStopView> stops,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static RouteDetailView from(
            Route route, Origin origin, Zone zone, Frequency frequency, Map<UUID, Destination> destinationsById) {
        List<RouteStopView> stops = route.stops().stream()
                .map(stop -> RouteStopView.from(stop, destinationsById.get(stop.destinationId())))
                .toList();
        return new RouteDetailView(route.id(), route.code(), route.name(), route.originId(),
                origin == null ? null : origin.code(), origin == null ? null : origin.name(), route.zoneId(),
                zone == null ? null : zone.code(), zone == null ? null : zone.name(), route.frequencyId(),
                frequency == null ? null : frequency.code(), frequency == null ? null : frequency.name(),
                route.referenceDistanceKm(), route.referenceDurationMinutes(), stops, route.active(),
                route.createdAt(), route.updatedAt());
    }

    public record RouteStopView(UUID destinationId, String destinationCode, String destinationName, int sequence) {
        static RouteStopView from(RouteStop stop, Destination destination) {
            return new RouteStopView(stop.destinationId(), destination == null ? null : destination.code(),
                    destination == null ? null : destination.name(), stop.sequence());
        }
    }
}
