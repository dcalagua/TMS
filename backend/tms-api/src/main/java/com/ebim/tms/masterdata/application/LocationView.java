package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Location;
import com.ebim.tms.masterdata.domain.LocationRole;
import com.ebim.tms.masterdata.domain.LocationType;
import com.ebim.tms.masterdata.domain.Zone;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * API-facing view of a {@link Location}, kept separate from the JPA entity (review chain rule).
 *
 * <p>{@code originId} and {@code destinationId} are exposed deliberately. They are what a client
 * needs to point an order, a route or a planning run at this location while
 * {@code tms.transport_order} and friends still speak Origin/Destination
 * ({@code docs/architecture/ADR_LOCATION_MODEL.md}, debt D-6). They are equal to {@code id}
 * except for a destination that the V14 backfill merged into a same-code origin's location.
 */
public record LocationView(
        UUID id,
        String code,
        String name,
        LocationType type,
        List<LocationRole> roles,
        String address,
        String addressReference,
        String district,
        String province,
        String department,
        String country,
        String timeZone,
        BigDecimal latitude,
        BigDecimal longitude,
        UUID zoneId,
        String zoneCode,
        String zoneName,
        int serviceTimeMinutes,
        String externalSystem,
        String externalReference,
        UUID originId,
        UUID destinationId,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * @param zone the location's zone, already resolved by the caller (batched for lists) -
     *     {@code null} when the location has no zone, for the reason {@code DestinationView}
     *     documents
     * @param originId the id of the {@code tms.origin} projection, or {@code null} when the
     *     location does not hold the {@code ORIGIN} role
     * @param destinationId the id of the {@code tms.destination} projection, or {@code null}
     *     when the location does not hold the {@code SHIP_TO} role
     */
    public static LocationView from(Location location, Zone zone, UUID originId, UUID destinationId) {
        return new LocationView(location.id(), location.code(), location.name(), location.type(),
                List.copyOf(location.roles()), location.address(), location.addressReference(),
                location.district(), location.province(), location.department(), location.country(),
                location.timeZone(), location.latitude(), location.longitude(), location.zoneId(),
                zone == null ? null : zone.code(), zone == null ? null : zone.name(),
                location.serviceTimeMinutes(), location.externalSystem(), location.externalReference(),
                originId, destinationId, location.active(), location.createdAt(), location.updatedAt());
    }
}
