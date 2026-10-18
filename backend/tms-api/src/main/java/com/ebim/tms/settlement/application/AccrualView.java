package com.ebim.tms.settlement.application;

import java.util.List;

/** One accrual with its movements (V65). The movements sum to {@code summary.balance}. */
public record AccrualView(AccrualSummaryView summary, List<AccrualEntryView> entries) {

    public AccrualView {
        entries = List.copyOf(entries);
    }
}
