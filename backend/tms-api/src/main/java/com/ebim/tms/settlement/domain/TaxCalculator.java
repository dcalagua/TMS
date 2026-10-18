package com.ebim.tms.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * IGV breakdown check, detraction (SPOT) and retention of a carrier's document (V66, ADR-024 part
 * 2, items 2, 7 and 8).
 *
 * <p><b>A pure function, and the rules are named constants with tests</b>, because they are where
 * an accountant will disagree first (D-06): the rounding tolerance of the breakdown, the threshold
 * comparison and the rounding of each amount. <b>TMS computes, never pays</b>: these figures go to
 * the ERP with the obligation.
 *
 * <ul>
 *   <li>Only documents in {@link #FISCAL_CURRENCY} are computed: the threshold is in soles and this
 *       product converts no currency.
 *   <li>Only an {@link DocumentType#INVOICE}: a note corrects an operation whose detraction was
 *       decided on the invoice; a person informs anything else.
 *   <li>Detraction and retention are exclusive: a document subject to detraction is not subject to
 *       retention ({@code ck_carrier_invoice_detraction_or_retention}).
 * </ul>
 */
public final class TaxCalculator {

    /** The currency the Peruvian rules apply to. */
    public static final String FISCAL_CURRENCY = "PEN";

    /** {@code subtotal + igv} may differ from the total by this much: one cent of rounding. */
    public static final BigDecimal BREAKDOWN_TOLERANCE = new BigDecimal("0.01");

    /**
     * Detraction applies to totals strictly <b>above</b> the threshold, as the SPOT rule reads
     * ("importe de la operación mayor a S/ 400"): 400.00 is not subject, 400.01 is.
     */
    public static final boolean DETRACTION_STRICTLY_ABOVE_THRESHOLD = true;

    /** SUNAT detraction deposits are in whole soles. */
    public static final int DETRACTION_SCALE = 0;

    public static final RoundingMode DETRACTION_ROUNDING = RoundingMode.HALF_UP;

    /** A retention is withheld to the cent. */
    public static final int RETENTION_SCALE = 2;

    public static final RoundingMode RETENTION_ROUNDING = RoundingMode.HALF_UP;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private TaxCalculator() {
    }

    /** What applies to one document; either part may be null, never both set. */
    public record TaxOutcome(Detraction detraction, Retention retention) {

        public static final TaxOutcome NONE = new TaxOutcome(null, null);
    }

    public record Detraction(String code, BigDecimal rate, BigDecimal amount) {
    }

    public record Retention(BigDecimal rate, BigDecimal amount) {
    }

    /** Whether a stated breakdown adds up to the total within {@link #BREAKDOWN_TOLERANCE}. */
    public static boolean breakdownAddsUp(BigDecimal subtotal, BigDecimal igv, BigDecimal total) {
        return subtotal.add(igv).subtract(total).abs().compareTo(BREAKDOWN_TOLERANCE) <= 0;
    }

    /** Whether {@code total} is subject to detraction under {@code threshold}. */
    public static boolean exceedsThreshold(BigDecimal total, BigDecimal threshold) {
        int comparison = total.compareTo(threshold);
        return DETRACTION_STRICTLY_ABOVE_THRESHOLD ? comparison > 0 : comparison >= 0;
    }

    public static TaxOutcome compute(FiscalRules rules, DocumentType type, String currency, BigDecimal total) {
        if (type != DocumentType.INVOICE || !FISCAL_CURRENCY.equals(currency) || total == null) {
            return TaxOutcome.NONE;
        }
        if (rules.detractionEnabled() && exceedsThreshold(total, rules.detractionThresholdAmount())) {
            BigDecimal amount = percentOf(total, rules.detractionRate())
                    .setScale(DETRACTION_SCALE, DETRACTION_ROUNDING)
                    .setScale(2, RoundingMode.UNNECESSARY);
            return new TaxOutcome(new Detraction(rules.detractionCode(), rules.detractionRate(), amount), null);
        }
        if (rules.retentionAgent()) {
            BigDecimal amount = percentOf(total, rules.retentionRate()).setScale(RETENTION_SCALE, RETENTION_ROUNDING);
            return new TaxOutcome(null, new Retention(rules.retentionRate(), amount));
        }
        return TaxOutcome.NONE;
    }

    /** The IGV a subtotal calls for at {@code rate}, to the cent - offered to a form, never enforced. */
    public static BigDecimal igvOf(BigDecimal subtotal, BigDecimal rate) {
        return percentOf(subtotal, rate).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentOf(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).divide(HUNDRED, 10, RoundingMode.HALF_UP);
    }
}
