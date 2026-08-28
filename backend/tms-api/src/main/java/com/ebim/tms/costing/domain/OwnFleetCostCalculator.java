package com.ebim.tms.costing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a profile and a trip's measurements into an estimate (V48, JOB 22).
 *
 * <p><b>A pure function.</b> No repository, no clock, no routing call - the caller resolves the
 * distance and the duty and hands them in, the same shape {@code PlanningEngine} and
 * {@code TripCostCalculator} use and for the same reason: an estimate anybody can reproduce on a
 * machine with no database, and every rule below provable without one.
 *
 * <h2>What is charged for, and what is merely missing</h2>
 *
 * A profile charges for a component when it has a rate for it. A null rate is not a zero rate: a
 * company that does not model depreciation has said nothing about depreciation, and its estimate is
 * complete without it. A company that typed 0.00 has said depreciation is nil, and its estimate
 * still needs the kilometres before it can say so.
 *
 * <h2>Where the reposition goes</h2>
 *
 * The empty run to reach a trip's origin is charged to the trip it repositions <b>to</b>, never the
 * one it leaves - you drive that leg because of the next job. Applied consistently it means a
 * resource running two trips is charged for the reposition exactly once, and the caller passes it in
 * already folded into {@link OwnFleetCostInputs}, so this function cannot double it.
 */
public final class OwnFleetCostCalculator {

    /** Money is rounded once, at the end of each line, and never accumulated at higher precision. */
    private static final int MONEY_SCALE = 2;

    private OwnFleetCostCalculator() {
    }

    public static OwnFleetCostEstimate calculate(OwnFleetRates rates, OwnFleetCostInputs inputs) {
        if (rates == null) {
            throw new IllegalArgumentException("an estimate needs a profile's rates");
        }
        OwnFleetCostInputs measured = inputs == null ? OwnFleetCostInputs.NOTHING_MEASURED : inputs;

        List<OwnFleetCostLine> lines = new ArrayList<>();
        BigDecimal applied = BigDecimal.ZERO;
        boolean anythingMissing = false;

        for (OwnFleetComponent component : OwnFleetComponent.values()) {
            BigDecimal rate = rates.rateFor(component);
            if (rate == null) {
                // This profile does not charge for it. Not a gap - nothing is owed and nothing is
                // missing, so the estimate stays complete without it.
                continue;
            }

            OwnFleetCostLine line = lineFor(component, rate, measured);
            lines.add(line);
            if (line.isApplied()) {
                applied = applied.add(line.amount());
            } else {
                anythingMissing = true;
            }
        }

        BigDecimal subtotal = applied.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        // The total exists only if nothing this profile charges for was un-measurable. Withholding
        // it is the whole safety property: a plan must not look cheap because it is un-costable.
        BigDecimal total = anythingMissing ? null : subtotal;
        return new OwnFleetCostEstimate(rates.currency(), total, subtotal, lines);
    }

    private static OwnFleetCostLine lineFor(OwnFleetComponent component, BigDecimal rate,
            OwnFleetCostInputs inputs) {
        if (component.isFlat()) {
            return OwnFleetCostLine.flat(component, rate.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        }
        if (component.needsDistance()) {
            if (!inputs.hasDistance()) {
                return OwnFleetCostLine.notCalculable(component, rate, OwnFleetCostReason.DISTANCE_UNKNOWN);
            }
            BigDecimal amount = rate.multiply(inputs.distanceKm()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            return OwnFleetCostLine.measured(component, rate, inputs.distanceKm(), amount, inputs.distanceSource());
        }
        if (!inputs.hasDuty()) {
            return OwnFleetCostLine.notCalculable(component, rate, OwnFleetCostReason.DUTY_UNKNOWN);
        }
        BigDecimal hours = inputs.dutyHours();
        BigDecimal amount = rate.multiply(hours).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return OwnFleetCostLine.measured(component, rate, hours, amount, inputs.dutySource());
    }
}
