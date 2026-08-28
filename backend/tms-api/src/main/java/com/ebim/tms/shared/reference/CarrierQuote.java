package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What one carrier would charge to run one shipment (JOB 07).
 *
 * <p>The unit a carrier ranking is built from. It carries the agreement it came from, not only the
 * number: a planner asked to accept the second-cheapest carrier wants to know *which* card produced
 * that figure, and a tender sent on a price nobody can trace back to a contract is a tender nobody
 * can defend.
 *
 * @param carrierId   the carrier quoted
 * @param amount      the total the rate card produces for this shipment, {@code BigDecimal} because
 *                    it becomes an offer and then an invoice
 * @param currency    the card's own currency. Two carriers quoting in different currencies are
 *                    <b>not</b> comparable and the ranking says so rather than converting - this
 *                    product invents no FX rate (V30)
 * @param rateCardId  the agreement the figure came from
 * @param rateCardCode its code, so a screen can name it without a second lookup
 * @param estimated   true when any component of the price could not be calculated, so the total is
 *                    lower than the real charge will be. Surfaced rather than hidden: ranking
 *                    carriers on partial prices is legitimate, quoting one as final is not
 */
public record CarrierQuote(
        UUID carrierId,
        BigDecimal amount,
        String currency,
        UUID rateCardId,
        String rateCardCode,
        boolean estimated) {

    public CarrierQuote {
        if (carrierId == null) {
            throw new IllegalArgumentException("a quote must name the carrier it is for");
        }
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("a quote must carry a non-negative amount");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("a quote must state its currency");
        }
    }
}
