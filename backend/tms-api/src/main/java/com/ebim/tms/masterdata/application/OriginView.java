package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Origin;
import com.ebim.tms.masterdata.domain.OriginType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** API-facing view of an {@link Origin}, kept separate from the JPA entity (review chain rule). */
public record OriginView(
        UUID id,
        String code,
        String name,
        OriginType type,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        String timeZone,
        String externalReference,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static OriginView from(Origin origin) {
        return new OriginView(origin.id(), origin.code(), origin.name(), origin.type(), origin.address(),
                origin.latitude(), origin.longitude(), origin.timeZone(), origin.externalReference(),
                origin.active(), origin.createdAt(), origin.updatedAt());
    }
}
