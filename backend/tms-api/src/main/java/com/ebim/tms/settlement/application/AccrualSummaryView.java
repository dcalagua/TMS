package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.FreightAccrual;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** An accrual in a list (V65). {@code balance} is negative when invoiced above what was accrued. */
public record AccrualSummaryView(
        UUID id,
        UUID tripId,
        String shipmentNumber,
        UUID carrierId,
        String carrierName,
        String currency,
        String status,
        String basis,
        LocalDate serviceDate,
        BigDecimal accruedAmount,
        BigDecimal consumedAmount,
        BigDecimal reversedAmount,
        BigDecimal balance,
        OffsetDateTime createdAt) {

    public static AccrualSummaryView of(FreightAccrual accrual, String shipmentNumber, String carrierName) {
        return new AccrualSummaryView(accrual.id(), accrual.tripId(), shipmentNumber, accrual.carrierId(),
                carrierName, accrual.currency(), accrual.status().name(), accrual.basis().name(),
                accrual.serviceDate(), accrual.accruedAmount(), accrual.consumedAmount(),
                accrual.reversedAmount(), accrual.balance(), accrual.createdAt());
    }
}
