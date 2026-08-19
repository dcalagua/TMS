package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Destination;
import com.ebim.tms.masterdata.domain.DestinationType;
import com.ebim.tms.masterdata.domain.Zone;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** API-facing view of a {@link Destination}, kept separate from the JPA entity (review chain rule). */
public record DestinationView(
        UUID id,
        String code,
        String name,
        DestinationType type,
        String address,
        String addressReference,
        String district,
        String province,
        String department,
        String country,
        BigDecimal latitude,
        BigDecimal longitude,
        UUID zoneId,
        String zoneCode,
        String zoneName,
        int serviceTimeMinutes,
        String externalReference,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * @param zone the destination's zone, already resolved by the caller (batched for lists) -
     *     {@code null} when the destination has no zone. Passing it in rather than navigating a
     *     JPA association keeps {@link Destination} free of a relation that would go stale
     *     in-memory across {@code applyChanges} within the same transaction; see the entity's
     *     class comment.
     */
    public static DestinationView from(Destination destination, Zone zone) {
        return new DestinationView(destination.id(), destination.code(), destination.name(), destination.type(),
                destination.address(), destination.addressReference(), destination.district(),
                destination.province(), destination.department(), destination.country(), destination.latitude(),
                destination.longitude(), destination.zoneId(), zone == null ? null : zone.code(),
                zone == null ? null : zone.name(), destination.serviceTimeMinutes(), destination.externalReference(),
                destination.active(), destination.createdAt(), destination.updatedAt());
    }
}
