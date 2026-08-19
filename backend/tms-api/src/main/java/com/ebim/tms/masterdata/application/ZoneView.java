package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Zone;
import java.time.OffsetDateTime;
import java.util.UUID;

/** API-facing view of a {@link Zone}, kept separate from the JPA entity (review chain rule). */
public record ZoneView(
        UUID id,
        String code,
        String name,
        String description,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ZoneView from(Zone zone) {
        return new ZoneView(zone.id(), zone.code(), zone.name(), zone.description(), zone.active(),
                zone.createdAt(), zone.updatedAt());
    }
}
