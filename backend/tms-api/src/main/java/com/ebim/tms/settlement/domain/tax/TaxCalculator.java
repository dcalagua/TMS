package com.ebim.tms.settlement.domain.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The VAT breakdown check and the withholdings of a carrier's document, under any regime (V66,
 * ADR-024 part 2 items 2, 7 and 8, generalised by ADR-027).
 *
 * <p><b>A pure function, and its rules are named</b>, because they are where an accountant will
 * disagree first (D-06): the rounding tolerance of the breakdown, the threshold comparison, the base
 * and the rounding of each kind ({@link WithholdingKind}). <b>TMS computes, never pays</b>: these
 * figures go to the ERP with the obligation.
 *
 * <ul>
 *   <li>Only documents in the regime's fiscal currency are computed: thresholds are in it and this
 *       product converts no currency. The generic regime has no fiscal currency and no withholdings.
 *   <li>Only an {@link DocumentKind#INVOICE}: a note corrects an operation whose withholdings were
 *       decided on the invoice; a person informs anything else.
 *   <li>Kinds are computed in the regime's order, and a kind is skipped when one that excludes it
 *       already applied (Peru: no retention where there is detraction).
 *   <li>A withholding whose base is zero or missing yields nothing (Ecuador's VAT withholding on a
 *       VAT-0% freight document).
 * </ul>
 */
public final class TaxCalculator {

    /** {@code subtotal + tax} may differ from the total by this much: one cent of rounding. */
    public static final BigDecimal BREAKDOWN_TOLERANCE = new BigDecimal("0.01");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private TaxCalculator() {
    }

    /** Whether a stated breakdown adds up to the total within {@link #BREAKDOWN_TOLERANCE}. */
    public static boolean breakdownAddsUp(BigDecimal subtotal, BigDecimal tax, BigDecimal total) {
        return subtotal.add(tax).subtract(total).abs().compareTo(BREAKDOWN_TOLERANCE) <= 0;
    }

    /** Whether {@code total} is subject to a withholding of {@code kind} under {@code threshold}. */
    public static boolean exceedsThreshold(WithholdingKind kind, BigDecimal total, BigDecimal threshold) {
        if (threshold == null) {
            return true;
        }
        int comparison = total.compareTo(threshold);
        return kind.strictlyAboveThreshold() ? comparison > 0 : comparison >= 0;
    }

    /**
     * The withholdings {@code rules} call for on one document, in the regime's order. Empty for a note,
     * a document outside the fiscal currency, or a regime without withholdings.
     */
    public static List<ComputedWithholding> compute(TaxRules rules, DocumentKind kind, String currency,
            BigDecimal subtotal, BigDecimal tax, BigDecimal total) {
        if (kind != DocumentKind.INVOICE || total == null
                || rules.regime().fiscalCurrency().filter(fiscal -> fiscal.equals(currency)).isEmpty()) {
            return List.of();
        }
        List<ComputedWithholding> computed = new ArrayList<>();
        Set<WithholdingKind> applied = EnumSet.noneOf(WithholdingKind.class);
        for (WithholdingRule rule : rules.withholdings()) {
            WithholdingKind withholding = rule.kind();
            if (!rule.enabled() || rule.rate() == null
                    || withholding.excludedBy().stream().anyMatch(applied::contains)
                    || !exceedsThreshold(withholding, total, rule.thresholdAmount())) {
                continue;
            }
            BigDecimal base = switch (withholding.base()) {
                case TOTAL -> total;
                case SUBTOTAL -> subtotal;
                case VAT -> tax;
            };
            if (base == null || base.signum() <= 0) {
                continue;
            }
            BigDecimal amount = percentOf(base, rule.rate())
                    .setScale(withholding.scale(), withholding.rounding())
                    .setScale(2, RoundingMode.UNNECESSARY);
            computed.add(new ComputedWithholding(withholding, rule.code(), withholding.base(),
                    base.setScale(2, RoundingMode.HALF_UP), rule.rate(), amount));
            applied.add(withholding);
        }
        return List.copyOf(computed);
    }

    /** The VAT a subtotal calls for at {@code rate}, to the cent - offered to a form, never enforced. */
    public static BigDecimal vatOf(BigDecimal subtotal, BigDecimal rate) {
        return percentOf(subtotal, rate).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentOf(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).divide(HUNDRED, 10, RoundingMode.HALF_UP);
    }
}
