package com.ebim.tms.rates.application;

import com.ebim.tms.rates.domain.RateCard;
import com.ebim.tms.rates.domain.RateCardScope;
import com.ebim.tms.shared.reference.MasterReference;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API-facing view of a {@link RateCard}, kept separate from the JPA entity (review chain rule).
 *
 * <p>The four referenced masters are resolved to code and name here rather than left as bare ids,
 * batched by {@code RateCardService} - a list of 50 cards costs four lookups, never 200.
 *
 * @param scopeTargetCode the code of whatever the scope names (the origin, the route), or null for
 *     a {@code CARRIER}-scoped card. Flattened into one pair rather than exposed as two optional
 *     objects, because every screen renders it as one column: "what does this card apply to".
 */
public record RateCardView(
        UUID id,
        String code,
        String name,
        UUID carrierId,
        String carrierCode,
        String carrierName,
        RateCardScope scope,
        UUID scopeTargetId,
        String scopeTargetCode,
        String scopeTargetName,
        UUID vehicleTypeId,
        String vehicleTypeCode,
        String vehicleTypeName,
        String currency,
        LocalDate validFrom,
        LocalDate validTo,
        BigDecimal baseAmount,
        BigDecimal amountPerKm,
        BigDecimal amountPerKg,
        BigDecimal amountPerM3,
        BigDecimal amountPerPallet,
        BigDecimal minimumAmount,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** Any of the three references may be null - a master that was deactivated still renders its id. */
    public static RateCardView from(RateCard card, MasterReference carrier, MasterReference scopeTarget,
            MasterReference vehicleType) {
        UUID scopeTargetId = card.scope() == RateCardScope.ROUTE ? card.routeId() : card.originId();
        return new RateCardView(
                card.id(),
                card.code(),
                card.name(),
                card.carrierId(),
                carrier == null ? null : carrier.code(),
                carrier == null ? null : carrier.name(),
                card.scope(),
                scopeTargetId,
                scopeTarget == null ? null : scopeTarget.code(),
                scopeTarget == null ? null : scopeTarget.name(),
                card.vehicleTypeId(),
                vehicleType == null ? null : vehicleType.code(),
                vehicleType == null ? null : vehicleType.name(),
                card.currency(),
                card.validFrom(),
                card.validTo(),
                card.baseAmount(),
                card.amountPerKm(),
                card.amountPerKg(),
                card.amountPerM3(),
                card.amountPerPallet(),
                card.minimumAmount(),
                card.active(),
                card.createdAt(),
                card.updatedAt());
    }
}
