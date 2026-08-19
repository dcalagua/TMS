package com.ebim.tms.planning.application;

import java.math.BigDecimal;

/**
 * One dimension of a trip's capacity as the API reports it.
 *
 * @param used         always a number, never null - an unknown contribution counts as zero
 * @param limit        null when the dimension is unlimited (a trip with no vehicle)
 * @param remaining    {@code limit - used}, null when unlimited; negative only if a limit was
 *                     lowered underneath an existing load, which the services refuse to do
 * @param percentUsed  null when the limit is unlimited <em>or</em> zero - a zero limit has no
 *                     meaningful percentage, and reporting 0% ("plenty of room") or 100% ("full")
 *                     would both be a lie. Never a division by zero. One decimal place otherwise.
 * @param exceeded     true when a real limit is exceeded; always false while unlimited
 * @param unlimited    true when no limit applies, so a client can say "no vehicle yet" rather
 *                     than rendering an empty bar
 */
public record CapacityDimension(
        BigDecimal used,
        BigDecimal limit,
        BigDecimal remaining,
        BigDecimal percentUsed,
        boolean exceeded,
        boolean unlimited) {
}
