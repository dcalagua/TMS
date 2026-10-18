package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.TolerancePolicy;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One tolerance policy (V64).
 *
 * @param carrierId   null for the company-wide default
 * @param carrierName null for the default, or when the carrier cannot be resolved
 */
public record TolerancePolicyView(
        UUID id,
        UUID carrierId,
        String carrierName,
        BigDecimal absoluteAmount,
        BigDecimal percentage,
        String currency,
        boolean active,
        OffsetDateTime updatedAt) {

    static TolerancePolicyView of(TolerancePolicy policy, String carrierName) {
        return new TolerancePolicyView(policy.id(), policy.carrierId(), carrierName, policy.absoluteAmount(),
                policy.percentage(), policy.currency(), policy.active(), policy.updatedAt());
    }
}
