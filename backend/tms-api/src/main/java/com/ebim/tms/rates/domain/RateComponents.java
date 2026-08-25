package com.ebim.tms.rates.domain;

import java.math.BigDecimal;

/**
 * The six money fields of a {@link RateCard}, carried together so that creating and editing a card
 * do not each take six positional {@code BigDecimal}s that a reader has to count to check.
 *
 * <p>Every field is nullable and null means "this card does not charge for it", which is a
 * different statement from zero ("this card charges nothing for it") - and the difference shows up
 * on the estimate: a null component produces no line at all, a zero one produces a 0.00 line that
 * proves the question was asked and answered.
 *
 * @param minimumAmount the floor applied after every other component, or null for no floor
 */
public record RateComponents(
        BigDecimal baseAmount,
        BigDecimal amountPerKm,
        BigDecimal amountPerKg,
        BigDecimal amountPerM3,
        BigDecimal amountPerPallet,
        BigDecimal minimumAmount) {

    /** A card that charges one flat amount and nothing else - the simplest legal card, and for tests. */
    public static RateComponents flat(BigDecimal baseAmount) {
        return new RateComponents(baseAmount, null, null, null, null, null);
    }
}
