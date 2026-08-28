package com.ebim.tms.planning.application;

import java.math.BigDecimal;

/**
 * What a proposed plan would cost, or why that cannot be said (JOB 11, open debt D1).
 *
 * <p><b>A total is reported only when it covers the whole plan in one currency.</b> Anything else
 * is null with a reason, and that is the whole design rather than a limitation of it: a planner
 * comparing two engines on cost is comparing the numbers, and a total that quietly omits the three
 * trips nobody has an agreement for makes the worse plan look cheaper. The same discipline V30's
 * {@code NOT_CALCULABLE} lines and V43's absent ETAs follow - say the number or say why not, never
 * a plausible substitute.
 *
 * @param totalCost the sum across every proposed trip, or null. Never a partial sum
 * @param currency  the one currency that sum is in, or null when there is no sum
 * @param reason    why there is no total, or null when there is one
 * @param pricedTrips  how many trips priced. Reported even when the total is null, because "7 of
 *                     10 have an agreement" is the sentence that tells a planner what to fix
 * @param totalTrips   how many there were
 */
public record ProposalPricing(
        BigDecimal totalCost,
        String currency,
        UnpricedReason reason,
        int pricedTrips,
        int totalTrips) {

    /**
     * Why a plan has no total.
     *
     * <p>Separate values because each needs a different response from a planner - sign an
     * agreement, geocode a destination, or decide which currency the comparison is in - which is
     * the same reason {@code UnplannedReason} is not one "could not plan".
     */
    public enum UnpricedReason {

        /** Nothing to price: the engine placed no trips. */
        NO_TRIPS,

        /**
         * At least one proposed trip has no applicable rate card, so any total would be missing it.
         *
         * <p>Not "priced at zero". A carrier nobody has an agreement with is not free, and a plan
         * that used three of them would otherwise win every comparison.
         */
        NO_AGREEMENT_FOR_SOME_TRIP,

        /**
         * The applicable agreements are not all in one currency.
         *
         * <p><b>No conversion.</b> This product invents no FX rate (V30, and {@code CarrierQuote}
         * says the same for carrier ranking). A plan costing 4,000 PEN and 900 USD has no total
         * that is a fact, and producing one would make the comparison confidently wrong.
         */
        MIXED_CURRENCIES
    }

    public static ProposalPricing none(UnpricedReason reason, int pricedTrips, int totalTrips) {
        return new ProposalPricing(null, null, reason, pricedTrips, totalTrips);
    }

    public static ProposalPricing of(BigDecimal totalCost, String currency, int trips) {
        return new ProposalPricing(totalCost, currency, null, trips, trips);
    }

    /** Nothing was asked: no engine ran, or costing is off. Distinct from "could not price". */
    public static final ProposalPricing NOT_ASKED = new ProposalPricing(null, null, null, 0, 0);
}
