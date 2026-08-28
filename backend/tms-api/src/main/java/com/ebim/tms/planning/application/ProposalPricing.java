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
 * @param ownFleetCostedTrips how many of {@code pricedTrips} are our own trucks, costed from a
 *                     V48 profile rather than quoted from a carrier's agreement. <b>The provenance
 *                     the total would otherwise lose.</b> A plan mixing the two adds a commercial
 *                     price to an internal cost, which is a real and useful figure and is not the
 *                     same kind of number throughout - see {@link #mixesPriceAndCost()}
 */
public record ProposalPricing(
        BigDecimal totalCost,
        String currency,
        UnpricedReason reason,
        int pricedTrips,
        int totalTrips,
        int ownFleetCostedTrips) {

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
        MIXED_CURRENCIES,

        /**
         * A proposed trip runs on our own truck and could not be costed - no profile in force for
         * that vehicle or its type, or a component the profile charges for whose quantity the
         * proposal cannot supply.
         *
         * <p>Its own reason and not {@link #NO_AGREEMENT_FOR_SOME_TRIP}, because the fix is
         * different: nobody is going to sign an agreement with themselves. Somebody has to
         * configure what that truck costs to run, and the message has to send them there.
         *
         * <p>Before V48 this case did not reach a reason at all - own fleet returned no quote and
         * the plan was reported as missing an agreement, which was the wrong sentence. That was
         * debt D6.
         */
        OWN_FLEET_NOT_COSTABLE
    }

    /**
     * Whether this total adds a carrier's price to our own internal cost.
     *
     * <p>Not a defect - a planner wanting the cost of a mixed day wants exactly this number - but
     * the screen must say so. A carrier's price contains their margin and our estimate contains
     * none, so a mixed total is lower than the same day fully subcontracted for a reason that is
     * partly real and partly the absence of somebody else's profit.
     */
    public boolean mixesPriceAndCost() {
        return totalCost != null && ownFleetCostedTrips > 0 && ownFleetCostedTrips < pricedTrips;
    }

    public static ProposalPricing none(UnpricedReason reason, int pricedTrips, int totalTrips) {
        return new ProposalPricing(null, null, reason, pricedTrips, totalTrips, 0);
    }

    public static ProposalPricing of(BigDecimal totalCost, String currency, int trips) {
        return new ProposalPricing(totalCost, currency, null, trips, trips, 0);
    }

    public static ProposalPricing of(BigDecimal totalCost, String currency, int trips, int ownFleetTrips) {
        return new ProposalPricing(totalCost, currency, null, trips, trips, ownFleetTrips);
    }

    /** Nothing was asked: no engine ran, or costing is off. Distinct from "could not price". */
    public static final ProposalPricing NOT_ASKED = new ProposalPricing(null, null, null, 0, 0, 0);
}
