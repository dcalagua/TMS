package com.ebim.tms.shared.reference;

import java.math.BigDecimal;

/**
 * One priced option for moving a shipment, whoever would move it (V48, JOB 22).
 *
 * <p>The shape planning compares in. It carries the {@link TransportCostNature} because the two
 * kinds of figure are not interchangeable, and a nullable {@link #amount()} because "we cannot cost
 * this" is an answer options have to be allowed to give.
 *
 * @param amount   null when the option could not be costed - never zero standing in for unknown. A
 *                 quote with a null amount takes no part in a comparison; it does not win it
 * @param currency always present, even when the amount is not, because which currency an option
 *                 would have been in is what makes it comparable at all
 */
public record TransportCostQuote(
        TransportCostNature nature,
        BigDecimal amount,
        String currency,
        String sourceLabel) {

    public TransportCostQuote {
        if (nature == null) {
            throw new IllegalArgumentException("a quote must say whether it is a price or a cost");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("a quote needs an ISO-4217 currency");
        }
        if (amount != null && amount.signum() < 0) {
            throw new IllegalArgumentException("a quote cannot be negative");
        }
    }

    /** An option that exists and could not be costed. */
    public static TransportCostQuote uncosted(TransportCostNature nature, String currency, String sourceLabel) {
        return new TransportCostQuote(nature, null, currency, sourceLabel);
    }

    public boolean isCosted() {
        return amount != null;
    }
}
