package com.ebim.tms.settlement.domain;

/**
 * Where a freight accrual stands (V65, ADR-023).
 *
 * <ul>
 *   <li>{@link #OPEN} - accrued, nothing invoiced yet. The only state the sweep adjusts.</li>
 *   <li>{@link #PARTIALLY_SETTLED} - an approved invoice consumed part of it; a balance remains.</li>
 *   <li>{@link #SETTLED} - consumed to zero or beyond, or its remainder closed by a person.</li>
 *   <li>{@link #REVERSED} - a person reversed it before anything was invoiced.</li>
 * </ul>
 */
public enum AccrualStatus {
    OPEN,
    PARTIALLY_SETTLED,
    SETTLED,
    REVERSED
}
