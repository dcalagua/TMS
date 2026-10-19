package com.ebim.tms.settlement.domain;

import java.math.BigDecimal;

/**
 * The movements of a freight accrual (V65), with the sign {@code ck_freight_accrual_entry_sign}
 * enforces: what each does to the open balance.
 */
public enum AccrualEntryType {

    /** Born: the trip's cost becomes an obligation. Positive. */
    ACCRUE,
    /** The closed actual cost differs from what was accrued. Either sign, never zero. */
    ADJUST,
    /** An approved invoice draws on it. Negative. */
    CONSUME,
    /** A person releases it: service not billable, or the remainder after the last invoice. Negative. */
    REVERSE;

    /** Whether {@code signedAmount} has the sign this movement must carry. */
    public boolean accepts(BigDecimal signedAmount) {
        int sign = signedAmount.signum();
        return switch (this) {
            case ACCRUE -> sign > 0;
            case CONSUME, REVERSE -> sign < 0;
            case ADJUST -> sign != 0;
        };
    }
}
