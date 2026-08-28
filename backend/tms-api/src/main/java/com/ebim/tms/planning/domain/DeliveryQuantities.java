package com.ebim.tms.planning.domain;

import com.ebim.tms.shared.reference.OrderAmounts;
import java.math.BigDecimal;

/**
 * How much of an order was taken to a customer, how much they took, and how much they refused
 * (migration V45, closing debt D3).
 *
 * <p>Expressed in the three measures a vehicle is constrained by, because those are the only ones
 * that are <em>summable</em> - see {@code docs/domain/SHIP_UNITS_AND_ALLOCATION_V1.md}. Which
 * product was refused is a different question, answered by {@link OrderDeliveryLine} in the line's
 * own unit.
 *
 * <h2>Absent is not zero</h2>
 *
 * <p>A delivery either records quantities or does not. {@link #NOT_RECORDED} is the second, and it
 * is <b>not</b> a delivery of nothing: every delivery written before V45 has no quantities and must
 * keep meaning exactly what it meant - an outcome, and no claim about amounts. Reading those as
 * zero would assert that nothing was ever delivered, which is the single most damaging thing this
 * feature could do and would look like data.
 *
 * <h2>The invariant</h2>
 *
 * <p>{@code delivered + refused <= attempted}, per measure. Deliberately {@code <=} and not
 * {@code =}: goods can be attempted and neither delivered nor refused - left on the vehicle because
 * the dock closed, carried back to the depot - and that difference is what is still outstanding.
 * A real operational state, not an accounting error to forbid.
 *
 * <p>Per measure rather than over a total, because the three are not interchangeable: a shortfall
 * in pallets is not cancelled by a surplus in kilos.
 */
public record DeliveryQuantities(OrderAmounts attempted, OrderAmounts delivered, OrderAmounts refused) {

    /** No quantities were recorded. Not a delivery of nothing - see the class comment. */
    public static final DeliveryQuantities NOT_RECORDED = new DeliveryQuantities(null, null, null);

    public DeliveryQuantities {
        boolean anyPresent = attempted != null || delivered != null || refused != null;
        boolean allPresent = attempted != null && delivered != null && refused != null;
        if (anyPresent && !allPresent) {
            // Mirrors ck_order_delivery_*_block. A row claiming 800 kg delivered without saying how
            // much was attempted is not a partial record, it is an unanswerable one: 800 of what?
            throw new IllegalArgumentException(
                    "delivery quantities are recorded together or not at all: attempted, delivered and refused");
        }
        if (allPresent) {
            if (attempted.isNegative() || delivered.isNegative() || refused.isNegative()) {
                throw new IllegalArgumentException("delivery quantities cannot be negative");
            }
            if (delivered.plus(refused).exceeds(attempted)) {
                throw new IllegalArgumentException(
                        "more was delivered and refused than was attempted");
            }
        }
    }

    public static DeliveryQuantities of(OrderAmounts attempted, OrderAmounts delivered, OrderAmounts refused) {
        return new DeliveryQuantities(attempted, delivered, refused);
    }

    public boolean isRecorded() {
        return attempted != null;
    }

    /**
     * What was taken out and came back - attempted, less what the customer either took or refused.
     *
     * <p>Its own concept because it is neither a success nor a rejection: nobody said no, the goods
     * simply did not change hands. It is what a second attempt would carry.
     */
    public OrderAmounts outstanding() {
        return isRecorded() ? attempted.minus(delivered.plus(refused)) : OrderAmounts.NONE;
    }

    /** Whether the customer took everything that was taken to them. */
    public boolean isComplete() {
        return isRecorded() && delivered.covers(attempted);
    }

    /** Whether anything at all changed hands. */
    public boolean deliveredAnything() {
        return isRecorded() && !delivered.isZero();
    }

    // --- persistence helpers, so the entity never assembles nine columns by hand ---------

    public BigDecimal attemptedWeight() {
        return attempted == null ? null : attempted.weightKg();
    }

    public BigDecimal attemptedVolume() {
        return attempted == null ? null : attempted.volumeM3();
    }

    public BigDecimal attemptedPallets() {
        return attempted == null ? null : attempted.pallets();
    }

    public BigDecimal deliveredWeight() {
        return delivered == null ? null : delivered.weightKg();
    }

    public BigDecimal deliveredVolume() {
        return delivered == null ? null : delivered.volumeM3();
    }

    public BigDecimal deliveredPallets() {
        return delivered == null ? null : delivered.pallets();
    }

    public BigDecimal refusedWeight() {
        return refused == null ? null : refused.weightKg();
    }

    public BigDecimal refusedVolume() {
        return refused == null ? null : refused.volumeM3();
    }

    public BigDecimal refusedPallets() {
        return refused == null ? null : refused.pallets();
    }

    /**
     * Rebuilds from nine nullable columns. Null attempted means the row predates V45 or simply did
     * not record amounts, and the whole block is then {@link #NOT_RECORDED} rather than three zeros.
     */
    public static DeliveryQuantities fromColumns(
            BigDecimal attemptedWeight, BigDecimal attemptedVolume, BigDecimal attemptedPallets,
            BigDecimal deliveredWeight, BigDecimal deliveredVolume, BigDecimal deliveredPallets,
            BigDecimal refusedWeight, BigDecimal refusedVolume, BigDecimal refusedPallets) {
        if (attemptedWeight == null && attemptedVolume == null && attemptedPallets == null) {
            return NOT_RECORDED;
        }
        return new DeliveryQuantities(
                new OrderAmounts(attemptedWeight, attemptedVolume, attemptedPallets),
                new OrderAmounts(deliveredWeight, deliveredVolume, deliveredPallets),
                new OrderAmounts(refusedWeight, refusedVolume, refusedPallets));
    }
}
