package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Frequency;
import com.ebim.tms.masterdata.domain.Location;
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
 * <p>The JSON field names still say {@code originId} / {@code destinationId}. They name the two
 * ends of a movement, which is what a route has; since V23 both resolve to a canonical
 * {@code tms.location}, and renaming the fields would break every client for a synonym.
 *
 * @param stops already sorted by sequence by {@link Route#stops()}; every stop's location was
 *     resolved by the caller in one batched lookup, never one query per stop.
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
            Route route, Location origin, Zone zone, Frequency frequency, Map<UUID, Location> destinationsById) {
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

    /**
     * Service time is returned three ways on purpose, because an editor has to show all three:
     * what this stop overrides ({@code serviceTimeOverrideMinutes}, null when it overrides
     * nothing), what it would inherit ({@code destinationServiceTimeMinutes}, so the field can
     * show the location's value as its placeholder rather than an empty box), and what actually
     * applies ({@code effectiveServiceTimeMinutes}). The client never recomputes the third from
     * the first two - {@code RouteStop.effectiveServiceTimeMinutes} owns that rule.
     *
     * @param destinationServiceTimeMinutes {@code null} only when the destination could not be
     *     resolved at all; the column itself is NOT NULL on {@code tms.location}.
     */
    public record RouteStopView(UUID destinationId, String destinationCode, String destinationName, int sequence,
            Integer serviceTimeOverrideMinutes, Integer destinationServiceTimeMinutes,
            Integer effectiveServiceTimeMinutes) {

        static RouteStopView from(RouteStop stop, Location destination) {
            return new RouteStopView(stop.destinationId(), destination == null ? null : destination.code(),
                    destination == null ? null : destination.name(), stop.sequence(),
                    stop.serviceTimeOverrideMinutes(),
                    destination == null ? null : destination.serviceTimeMinutes(),
                    stop.effectiveServiceTimeMinutes(destination));
        }
    }
}
