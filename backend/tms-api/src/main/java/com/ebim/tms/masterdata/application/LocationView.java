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
 * <p>There is no {@code originId}/{@code destinationId} any more. V14 exposed them because
 * {@code tms.transport_order} and friends still referenced the compatibility projections and a
 * client needed the projection's id to point at this place. V23 repointed all six foreign keys
 * at {@code tms.location}, so {@link #id()} is the only id a place has - which is the entire
 * claim of the model: one physical record, used from either end of a movement.
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
        /**
         * The radius of this place's geofence, in metres, or null when it has none (migration V43).
         * Read-only here: it is set through its own endpoint, because it is configured once when
         * the site is set up rather than edited alongside an address.
         */
        Integer geofenceRadiusM,
        String externalSystem,
        String externalReference,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * @param zone the location's zone, already resolved by the caller (batched for lists) -
     *     {@code null} when the location has no zone, so a list page costs one zone query rather
     *     than one per row
     */
    public static LocationView from(Location location, Zone zone) {
        return new LocationView(location.id(), location.code(), location.name(), location.type(),
                List.copyOf(location.roles()), location.address(), location.addressReference(),
                location.district(), location.province(), location.department(), location.country(),
                location.timeZone(), location.latitude(), location.longitude(), location.zoneId(),
                zone == null ? null : zone.code(), zone == null ? null : zone.name(),
                location.serviceTimeMinutes(), location.geofenceRadiusM(), location.externalSystem(),
                location.externalReference(),
                location.active(), location.createdAt(), location.updatedAt());
    }
}
