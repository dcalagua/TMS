package com.ebim.tms.rates.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link RateCard} and a shipment's quantities into money. A pure function with no
 * repository, no clock and no Spring: the same card and the same inputs produce the same estimate
 * on any machine at any time, which is what makes "why does this shipment cost 412.50" a question
 * with an answer.
 *
 * <p>The whole calculation, in the order the lines come out:
 *
 * <ol>
 *   <li>{@code BASE}, if the card names one, as a flat line.</li>
 *   <li>Each of {@code DISTANCE}, {@code WEIGHT}, {@code VOLUME}, {@code PALLETS} the card names:
 *       {@code rate x quantity}, rounded to the currency's two decimals. A component whose
 *       quantity is unknown becomes a {@link CostComponentStatus#NOT_CALCULABLE} line contributing
 *       nothing - it is never skipped and never charged at zero.</li>
 *   <li>{@code MINIMUM_ADJUSTMENT}, if the card names a floor and the lines above came to less
 *       than it: the difference, so the total <em>is</em> the floor and the reason is visible.</li>
 * </ol>
 *
 * <p><b>Rounding is per line, half up, at two decimals.</b> Per line and not at the end, because
 * the lines are shown to an operator and printed on a settlement sheet: a total that is not the
 * sum of the numbers above it is a support ticket. Half up because that is what every carrier's
 * invoice does, whatever a numerical analyst would prefer.
 *
 * <p>The minimum is applied to the <em>calculated</em> lines and not to some notion of what the
 * shipment would have cost if everything had been calculable. A floor is a floor: if the only
 * calculable component came to 40 and the agreement says never less than 120, the shipment costs
 * 120 - and the non-calculable line is still on the estimate saying what is missing.
 */
public final class TripCostCalculator {

    /** The scale money is held at, matching {@code numeric(14,2)} in migration V30. */
    public static final int MONEY_SCALE = 2;

    static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);

    /** Ordered as they are calculated and as they are shown; {@code BASE} and the floor are handled apart. */
    private static final List<RateComponent> MEASURED_COMPONENTS = List.of(
            RateComponent.DISTANCE, RateComponent.WEIGHT, RateComponent.VOLUME, RateComponent.PALLETS);

    private TripCostCalculator() {
    }

    /**
     * Prices {@code inputs} against {@code card}.
     *
     * @throws IllegalArgumentException if the card charges nothing at all, which
     *     {@code ck_rate_card_has_a_component} makes unreachable through any supported write path
     */
    public static CostEstimate calculate(RateCard card, CostInputs inputs) {
        if (!card.hasAnyComponent()) {
            throw new IllegalArgumentException(
                    "rate card " + card.code() + " charges nothing and cannot price a shipment");
        }

        List<CostLine> lines = new ArrayList<>();
        if (card.baseAmount() != null) {
            lines.add(CostLine.flat(RateComponent.BASE, money(card.baseAmount())));
        }
        for (RateComponent component : MEASURED_COMPONENTS) {
            BigDecimal rate = card.rateFor(component);
            if (rate == null) {
                continue;
            }
            BigDecimal quantity = inputs.quantityFor(component);
            if (quantity == null) {
                lines.add(CostLine.notCalculable(component));
            } else {
                lines.add(CostLine.measured(component, rate, quantity, money(rate.multiply(quantity))));
            }
        }

        BigDecimal subtotal = sum(lines);
        BigDecimal minimum = card.minimumAmount();
        if (minimum != null && subtotal.compareTo(money(minimum)) < 0) {
            lines.add(CostLine.flat(RateComponent.MINIMUM_ADJUSTMENT, money(minimum).subtract(subtotal)));
        }

        return new CostEstimate(card.currency(), sum(lines), lines);
    }

    private static BigDecimal sum(List<CostLine> lines) {
        return lines.stream().map(CostLine::amount).reduce(ZERO_MONEY, BigDecimal::add);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
