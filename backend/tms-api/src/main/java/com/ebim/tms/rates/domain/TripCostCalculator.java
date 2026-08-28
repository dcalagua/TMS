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

    /**
     * The linehaul's measured components, in the order they are calculated and shown.
     *
     * <p>{@code STOP_OFF} joins them in V39 and belongs here rather than with the accessorials: a
     * multi-drop charge is part of what the haul costs, and the fuel surcharge is taken on it.
     * {@code BASE} and the adjustments are handled apart because they are flat.
     */
    private static final List<RateComponent> LINEHAUL_MEASURED = List.of(
            RateComponent.DISTANCE, RateComponent.WEIGHT, RateComponent.VOLUME, RateComponent.PALLETS,
            RateComponent.STOP_OFF);

    /** The flat accessorials, after the fuel surcharge and before the adjustments. */
    private static final List<RateComponent> FLAT_ACCESSORIALS =
            List.of(RateComponent.TOLL, RateComponent.OTHER_ACCESSORIAL);

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

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

        // 1. The linehaul: the base, then everything charged per unit of the haul itself.
        if (card.baseAmount() != null) {
            lines.add(CostLine.flat(RateComponent.BASE, money(card.baseAmount())));
        }
        for (RateComponent component : LINEHAUL_MEASURED) {
            addMeasured(lines, card, inputs, component);
        }
        BigDecimal linehaul = sum(lines);

        // 2. The fuel surcharge, on the linehaul and on nothing after it (V39). Taken on the
        // subtotal as it stands here, which is why this sits between the two groups rather than at
        // the end: a percentage of a toll is not a fuel surcharge anybody bills.
        BigDecimal fuelPercent = card.fuelSurchargePercent();
        if (fuelPercent != null) {
            BigDecimal surcharge = money(linehaul.multiply(fuelPercent).divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP));
            lines.add(CostLine.measured(RateComponent.FUEL_SURCHARGE, fuelPercent, linehaul, surcharge));
        }

        // 3. The accessorials: detention, then the flat pass-throughs.
        addMeasured(lines, card, inputs, RateComponent.WAITING_TIME);
        for (RateComponent component : FLAT_ACCESSORIALS) {
            BigDecimal amount = card.flatAmountFor(component);
            if (amount != null) {
                lines.add(CostLine.flat(component, money(amount)));
            }
        }

        // 4. The floor and the ceiling, on the finished total. Mutually exclusive by arithmetic -
        // ck_rate_card_maximum_above_minimum makes a card where both could fire impossible - so a
        // breakdown never shows an adjustment up followed by an adjustment down.
        BigDecimal subtotal = sum(lines);
        BigDecimal minimum = card.minimumAmount();
        BigDecimal maximum = card.maximumAmount();
        if (minimum != null && subtotal.compareTo(money(minimum)) < 0) {
            lines.add(CostLine.flat(RateComponent.MINIMUM_ADJUSTMENT, money(minimum).subtract(subtotal)));
        } else if (maximum != null && subtotal.compareTo(money(maximum)) > 0) {
            // Negative: the only line on a breakdown that ever is, and it has to be, because a
            // ceiling that appeared as a positive number would read as another charge.
            lines.add(CostLine.flat(RateComponent.MAXIMUM_ADJUSTMENT, money(maximum).subtract(subtotal)));
        }

        return new CostEstimate(card.currency(), sum(lines), lines);
    }

    /**
     * Adds one measured line, or the non-calculable placeholder that says which quantity is
     * missing.
     *
     * <p>A card that says nothing about a component contributes no line at all, which is different
     * again: "this agreement does not charge for weight" and "this agreement charges for weight and
     * we do not know the weight" are two different statements and a breakdown must not conflate
     * them.
     */
    private static void addMeasured(List<CostLine> lines, RateCard card, CostInputs inputs,
            RateComponent component) {
        BigDecimal rate = card.rateFor(component);
        if (rate == null) {
            return;
        }
        BigDecimal quantity = inputs.quantityFor(component);
        if (quantity == null) {
            lines.add(CostLine.notCalculable(component));
        } else {
            lines.add(CostLine.measured(component, rate, quantity, money(rate.multiply(quantity)),
                    inputs.sourceFor(component)));
        }
    }

    private static BigDecimal sum(List<CostLine> lines) {
        return lines.stream().map(CostLine::amount).reduce(ZERO_MONEY, BigDecimal::add);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
