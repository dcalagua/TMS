package com.ebim.tms.planning.application;

import java.math.BigDecimal;

/**
 * How much went wrong, and how much of it somebody is still sitting on
 * ({@code docs/domain/KPIS_REPORTING_V1.md}, section "Exceptions").
 *
 * @param exceptions   every problem raised against the range's shipments, whatever became of it
 * @param open         still open now. A quarter-old figure that is still open is a different fact
 *                     from one that was resolved the same afternoon, and a single total would say
 *                     neither
 * @param resolved     {@code exceptions - open}, sent rather than left to the client to subtract:
 *                     the two are read side by side and a screen that computed one of them would be
 *                     the place they start to disagree
 * @param per100Trips  problems per hundred shipments that ran - not a percentage, and it can pass
 *                     100, because one shipment can carry several problems. See
 *                     {@code KpiRate.per100}. Null when nothing ran
 */
public record KpiExceptionsView(long exceptions, long open, long resolved, BigDecimal per100Trips) {
}
