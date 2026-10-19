package com.ebim.tms.settlement.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The open accrual balance at a date, by carrier and currency, in age buckets counted from the
 * service date (V65, ADR-023). Currencies are never added together.
 */
public record AccrualAgingView(LocalDate asOf, List<Row> rows) {

    public AccrualAgingView {
        rows = List.copyOf(rows);
    }

    public record Row(
            UUID carrierId,
            String carrierName,
            String currency,
            long accruals,
            BigDecimal days0To30,
            BigDecimal days31To60,
            BigDecimal days61To90,
            BigDecimal over90,
            BigDecimal total) {
    }
}
