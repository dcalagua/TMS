package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.Carrier;
import java.time.OffsetDateTime;
import java.util.UUID;

/** API-facing view of a {@link Carrier}, kept separate from the JPA entity (review chain rule). */
public record CarrierView(
        UUID id,
        String code,
        String businessName,
        String taxIdType,
        String taxIdValue,
        String contactName,
        String phone,
        String email,
        String externalReference,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static CarrierView from(Carrier carrier) {
        return new CarrierView(carrier.id(), carrier.code(), carrier.businessName(), carrier.taxIdType(),
                carrier.taxIdValue(), carrier.contactName(), carrier.phone(), carrier.email(),
                carrier.externalReference(), carrier.active(), carrier.createdAt(), carrier.updatedAt());
    }
}
